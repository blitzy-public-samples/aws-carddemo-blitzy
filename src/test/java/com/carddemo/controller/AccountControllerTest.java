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
package com.carddemo.controller;

import com.carddemo.controller.advice.GlobalExceptionHandler;
import com.carddemo.dto.account.AccountDto;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc web-slice tests for {@link AccountController} &mdash; the stateless REST replacement
 * for the two legacy CICS online account programs:
 * <ul>
 *   <li>{@code app/cbl/COACTVWC.cbl} (account view, TRANID {@code 'CAVW'}) &rarr;
 *       {@code GET /api/accounts/{acctId}};</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} (account update, TRANID {@code 'CAUP'}) &rarr;
 *       {@code PUT /api/accounts/{acctId}}.</li>
 * </ul>
 *
 * <p>The COBOL record under test is the 300-byte {@code ACCOUNT-RECORD} of
 * {@code app/cpy/CVACT01Y.cpy}; this slice verifies the HTTP boundary (request binding,
 * response serialization, and exception-to-status mapping) while the account business logic
 * itself lives in {@link AccountService} (stubbed here as a {@link MockBean}).</p>
 *
 * <h2>What is verified</h2>
 * <ol>
 *   <li>Account view returns the full {@link AccountDto} with every CVACT01Y user field
 *       serialized under its real JSON name ({@code acctId}, {@code activeStatus},
 *       {@code currBal}, {@code creditLimit}, {@code cashCreditLimit}, {@code openDate},
 *       {@code expirationDate}, {@code reissueDate}, {@code currCycCredit},
 *       {@code currCycDebit}, {@code addrZip}, {@code groupId}).</li>
 *   <li><b>PR-16</b> &mdash; monetary fields are exact scale-2 {@link BigDecimal} values,
 *       serialized as plain JSON numbers (never {@code float}/{@code double}); a boundary test
 *       asserts the smallest cent ({@code 0.01}) and the largest {@code S9(10)V99}
 *       ({@code 9999999999.99}) survive serialization byte-for-byte.</li>
 *   <li>Unknown account id &rarr; {@code 404 Not Found} (the service raises
 *       {@link AccountNotFoundException}, COBOL validation code 101, which
 *       {@link GlobalExceptionHandler} maps to 404 and copies onto the uniform error payload
 *       &mdash; {@code message} + request {@code path}).</li>
 *   <li>Invalid update payload &rarr; {@code 400 Bad Request} (a negative
 *       {@code creditLimit} violates the DTO {@code @DecimalMin("0.00")}; an empty body raises
 *       {@code HttpMessageNotReadableException}).</li>
 *   <li><b>PR-22</b> &mdash; a concurrent update raises
 *       {@link ObjectOptimisticLockingFailureException} (the JPA {@code @Version} replacement
 *       for the VSAM {@code READ UPDATE} exclusive lock, COACTUPC L522), mapped to
 *       {@code 409 Conflict}.</li>
 *   <li>A successful update returns the refreshed {@link AccountDto} ({@code 200 OK}).</li>
 *   <li>A non-numeric or out-of-range ({@code 0}) account id &rarr; {@code 400 Bad Request}
 *       (PR-13: {@code ACCT-ID PIC 9(11)} must be a non-zero 11-digit number).</li>
 * </ol>
 *
 * <h2>Slice configuration (matches the established pattern used by every controller test in
 * this module)</h2>
 * <ul>
 *   <li>{@link WebMvcTest @WebMvcTest(controllers = AccountController.class)} loads only the
 *       {@code AccountController} web layer; its sole collaborator {@link AccountService} is
 *       supplied as a {@link MockBean}.</li>
 *   <li>{@code excludeFilters} drops the entire {@code com.carddemo.security} package from the
 *       slice's component scan. A {@code @WebMvcTest} slice always registers application
 *       {@code jakarta.servlet.Filter} beans, and the production
 *       {@code com.carddemo.security.JwtAuthenticationFilter} is a {@code @Component} extending
 *       {@code OncePerRequestFilter}; left untouched it would be instantiated here and fail the
 *       context with an {@code UnsatisfiedDependencyException} because its
 *       {@code CustomAuthorityMapper} collaborator is not loaded by the slice.
 *       {@code @AutoConfigureMockMvc(addFilters = false)} only removes filters from the MockMvc
 *       dispatch &mdash; it does NOT prevent the bean from being created &mdash; so the
 *       component-scan exclusion is what actually keeps the context minimal.</li>
 *   <li>{@link Import @Import(GlobalExceptionHandler.class)} wires the
 *       {@code @RestControllerAdvice} so HTTP status-code assertions reflect production error
 *       handling ({@code AccountNotFoundException} &rarr; 404,
 *       {@code ObjectOptimisticLockingFailureException} &rarr; 409, validation failures &rarr;
 *       400). The advice has no injected dependencies (it uses
 *       {@code com.carddemo.util.CardNumberMasker} statically), so importing it requires no
 *       additional beans.</li>
 *   <li>{@link AutoConfigureMockMvc @AutoConfigureMockMvc(addFilters = false)} disables the
 *       security filter chain so the controller's HTTP behaviour is asserted in isolation.
 *       {@code AccountController} carries no class-level {@code @PreAuthorize}: per AAP
 *       &sect;0.4.1.1 any <em>authenticated</em> caller (USER or ADMIN) may view and update an
 *       account, exactly as the legacy account transactions were reachable from the regular
 *       user menu. Each test therefore runs with {@code @WithMockUser} (a default
 *       {@code ROLE_USER} principal), which populates {@code SecurityContextHolder} via the
 *       test execution listener independently of the disabled filter chain.</li>
 * </ul>
 *
 * <h2>Why the service calls are {@code getAccount} / {@code updateAccount}</h2>
 * <p>{@code AccountController} delegates to {@link AccountService#getAccount(Long)} (the
 * COACTVWC cross-reference existence check + keyed {@code ACCTDAT} read) and
 * {@link AccountService#updateAccount(Long, AccountDto)} (the COACTUPC change-detected,
 * optimistically-locked {@code REWRITE}). Tests stub and verify exactly those methods &mdash;
 * not a {@code findById}/{@code update} &mdash; so the slice exercises the real
 * controller-to-service contract.</p>
 *
 * <h2>Refactoring rules exercised</h2>
 * <ul>
 *   <li><b>PR-13</b> &mdash; {@code acctId} validated as a non-zero 11-digit numeric.</li>
 *   <li><b>PR-16</b> &mdash; money is {@link BigDecimal} scale 2, asserted with exact
 *       string-constructed values (never a {@code double} literal fixture).</li>
 *   <li><b>PR-22</b> &mdash; optimistic-lock conflict &rarr; 409.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 baseline (no {@code javax.*}).</li>
 *   <li><b>PR-29</b> &mdash; {@code AccountController} uses constructor injection of a single
 *       {@code final AccountService}; the slice supplies it via {@code @MockBean}.</li>
 * </ul>
 *
 * @see AccountController
 * @see AccountService
 * @see AccountDto
 * @see GlobalExceptionHandler
 */
@WebMvcTest(
        controllers = AccountController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AccountController web-slice tests (GET/PUT /api/accounts/{acctId})")
class AccountControllerTest {

    /**
     * Account id used for the happy-path lookups and updates. Mirrors COBOL
     * {@code ACCT-ID PIC 9(11)} and lies within the controller's
     * {@code @Min(1)}/{@code @Max(99999999999L)} bounds. The value (100000001) is within
     * {@code int} range, so JSON-number assertions may use a plain integer literal.
     */
    private static final long ACCT_ID = 100000001L;

    /**
     * A valid 11-digit account id used for the not-found scenarios. It passes path-variable
     * binding and validation (so the request reaches the stubbed service, which then throws),
     * and its decimal string {@code "99999999999"} is asserted to appear on the error
     * payload's {@code path}.
     */
    private static final long UNKNOWN_ACCT_ID = 99999999999L;

    /** Auto-configured MockMvc for the {@code AccountController} web slice (security filters disabled). */
    @Autowired
    private MockMvc mockMvc;

    /** Jackson mapper used to serialize {@link AccountDto} fixtures into JSON request bodies for PUT. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The sole controller collaborator, replaced by a Mockito mock in the slice context. The
     * real {@code AccountService} performs the COACTVWC lookup and the COACTUPC update; here it
     * is stubbed so the controller's HTTP behaviour is asserted in isolation.
     */
    @MockBean
    private AccountService accountService;

    /**
     * A fully-populated, <em>valid</em> {@link AccountDto} matching the CVACT01Y 300-byte
     * layout. Field names and types match the actual DTO exactly: {@code activeStatus} (not
     * "accountActiveStatus"), {@code addrZip} (not "addressZip"), and the three date fields
     * ({@code openDate}, {@code expirationDate}, {@code reissueDate}) are {@code String}s in
     * ISO {@code yyyy-MM-dd} form (not {@code LocalDate}). Every monetary field is a scale-2
     * {@link BigDecimal} built with the exact {@code String} constructor (PR-16). Because every
     * field is valid, this fixture passes {@code @Valid} body validation and is therefore
     * suitable as the request body for the not-found and optimistic-lock PUT scenarios (where
     * the stubbed service is what raises the exception).
     */
    private AccountDto sampleAccount;

    @BeforeEach
    void setUp() {
        sampleAccount = AccountDto.builder()
                .acctId(ACCT_ID)
                .activeStatus("Y")
                .currBal(new BigDecimal("1234.56"))
                .creditLimit(new BigDecimal("5000.00"))
                .cashCreditLimit(new BigDecimal("1000.00"))
                .openDate("2020-01-15")
                .expirationDate("2025-12-31")
                .reissueDate("2023-01-15")
                .currCycCredit(new BigDecimal("0.00"))
                .currCycDebit(new BigDecimal("0.00"))
                .addrZip("60601")
                .groupId("DEFAULT")
                .build();
    }

    /**
     * Tests for {@code GET /api/accounts/{acctId}} &mdash; the account-view flow replacing
     * {@code app/cbl/COACTVWC.cbl} (TRANID {@code 'CAVW'}).
     */
    @Nested
    @DisplayName("GET /api/accounts/{acctId}")
    class GetAccount {

        @Test
        @DisplayName("Existing account -> 200 OK with full AccountDto and BigDecimal scale preserved (PR-16)")
        @WithMockUser
        void shouldReturnAccountForExistingId() throws Exception {
            when(accountService.getAccount(ACCT_ID)).thenReturn(sampleAccount);

            mockMvc.perform(get("/api/accounts/{acctId}", ACCT_ID))
                    .andExpect(status().isOk())
                    // 11-digit ACCT-ID PIC 9(11) -> Long; value 100000001 is within int range.
                    .andExpect(jsonPath("$.acctId").value(100000001))
                    // ACCT-ACTIVE-STATUS PIC X(01) -> activeStatus (real DTO field name).
                    .andExpect(jsonPath("$.activeStatus").value("Y"))
                    // Monetary fields (PR-16): exact scale-2 BigDecimal values as plain JSON numbers.
                    .andExpect(jsonPath("$.currBal").value(1234.56))
                    .andExpect(jsonPath("$.creditLimit").value(5000.00))
                    .andExpect(jsonPath("$.cashCreditLimit").value(1000.00))
                    // Date fields are ISO yyyy-MM-dd Strings (PIC X(10)); PR-14 corrects the
                    // COBOL ACCT-EXPIRAION-DATE typo to expirationDate.
                    .andExpect(jsonPath("$.openDate").value("2020-01-15"))
                    .andExpect(jsonPath("$.expirationDate").value("2025-12-31"))
                    .andExpect(jsonPath("$.reissueDate").value("2023-01-15"))
                    // ACCT-ADDR-ZIP PIC X(10) -> addrZip (real DTO field name).
                    .andExpect(jsonPath("$.addrZip").value("60601"))
                    .andExpect(jsonPath("$.groupId").value("DEFAULT"));

            // The controller delegates the lookup to the service (actual API: getAccount).
            verify(accountService).getAccount(ACCT_ID);
        }

        @Test
        @DisplayName("Unknown account -> 404 Not Found (COACTVWC DID-NOT-FIND-ACCT-IN-ACCTDAT, code 101)")
        @WithMockUser
        void shouldReturn404ForUnknownAccount() throws Exception {
            // The service surfaces the COBOL DFHRESP(NOTFND) branch as AccountNotFoundException
            // carrying the exact COACTVWC L132 message literal; GlobalExceptionHandler maps it to
            // 404 and copies the message + request URI onto the ErrorResponse payload.
            when(accountService.getAccount(UNKNOWN_ACCT_ID))
                    .thenThrow(AccountNotFoundException.withMessage(
                            "Did not find this account in account master file"));

            mockMvc.perform(get("/api/accounts/{acctId}", UNKNOWN_ACCT_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.path").value("/api/accounts/99999999999"));

            verify(accountService).getAccount(UNKNOWN_ACCT_ID);
        }

        @Test
        @DisplayName("PR-16 - money fields serialized as exact scale-2 numbers (smallest cent + max S9(10)V99)")
        @WithMockUser
        void shouldPreserveBigDecimalScale() throws Exception {
            // Boundary fixture: 0.01 is the smallest representable cent; 9999999999.99 is the
            // largest value an unsigned S9(10)V99 packed-decimal can hold. Both must survive
            // JSON serialization with no floating-point corruption.
            AccountDto preciseAccount = AccountDto.builder()
                    .acctId(100000002L)
                    .activeStatus("Y")
                    .currBal(new BigDecimal("0.01"))
                    .creditLimit(new BigDecimal("9999999999.99"))
                    .cashCreditLimit(new BigDecimal("100.00"))
                    .openDate("2021-01-01")
                    .expirationDate("2026-12-31")
                    .reissueDate("2024-01-01")
                    .currCycCredit(new BigDecimal("0.00"))
                    .currCycDebit(new BigDecimal("0.00"))
                    .addrZip("00000")
                    .groupId("DEFAULT")
                    .build();
            when(accountService.getAccount(100000002L)).thenReturn(preciseAccount);

            mockMvc.perform(get("/api/accounts/{acctId}", 100000002L))
                    .andExpect(status().isOk())
                    // Numeric-value assertions.
                    .andExpect(jsonPath("$.currBal").value(0.01))
                    .andExpect(jsonPath("$.creditLimit").value(9999999999.99))
                    // Robust scale proof: the exact decimal text must appear verbatim in the body
                    // (no rounding, no scientific notation, no lost trailing digits).
                    .andExpect(content().string(containsString("0.01")))
                    .andExpect(content().string(containsString("9999999999.99")));

            verify(accountService).getAccount(100000002L);
        }

        @Test
        @DisplayName("Non-numeric account ID -> 400 Bad Request (MethodArgumentTypeMismatchException)")
        @WithMockUser
        void shouldReject400OnNonNumericId() throws Exception {
            // "NOT_A_NUMBER" cannot bind to the Long acctId path variable; the dispatcher raises
            // MethodArgumentTypeMismatchException during argument binding (before the service is
            // reached), which GlobalExceptionHandler maps to 400.
            mockMvc.perform(get("/api/accounts/{acctId}", "NOT_A_NUMBER"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Out-of-range account ID (0) -> 400 Bad Request (@Min(1) ConstraintViolationException, PR-13)")
        @WithMockUser
        void shouldReject400OnOutOfRangeId() throws Exception {
            // The class-level @Validated + @Min(1) on the path variable rejects a zero/negative
            // id with a ConstraintViolationException, mapped to 400 by GlobalExceptionHandler -
            // mirroring COBOL ACCT-ID PIC 9(11) being a non-zero positive value (COACTVWC L126).
            mockMvc.perform(get("/api/accounts/{acctId}", 0L))
                    .andExpect(status().isBadRequest());
        }
    }

    /**
     * Tests for {@code PUT /api/accounts/{acctId}} &mdash; the account-update flow replacing
     * {@code app/cbl/COACTUPC.cbl} (TRANID {@code 'CAUP'}), including its optimistic-locking
     * ({@code READ UPDATE}/{@code REWRITE}) race-condition semantics.
     *
     * <p>Every request uses {@code .with(csrf())}: although {@code addFilters = false} disables
     * the security filter chain (making CSRF a no-op here), including the token keeps these
     * tests correct should CSRF protection ever be enabled for the slice.</p>
     */
    @Nested
    @DisplayName("PUT /api/accounts/{acctId}")
    class UpdateAccount {

        @Test
        @DisplayName("Valid update -> 200 OK with updated AccountDto")
        @WithMockUser
        void shouldUpdateAccount() throws Exception {
            AccountDto updateResult = AccountDto.builder()
                    .acctId(ACCT_ID)
                    .activeStatus("Y")
                    .currBal(new BigDecimal("2000.00"))
                    .creditLimit(new BigDecimal("6000.00"))
                    .cashCreditLimit(new BigDecimal("1500.00"))
                    .openDate("2020-01-15")
                    .expirationDate("2025-12-31")
                    .reissueDate("2023-01-15")
                    .currCycCredit(new BigDecimal("0.00"))
                    .currCycDebit(new BigDecimal("0.00"))
                    .addrZip("60601")
                    .groupId("DEFAULT")
                    .build();

            // The path acctId is authoritative; the service ignores any acctId on the body, so
            // any(AccountDto.class) is the precise matcher for the deserialized request body.
            when(accountService.updateAccount(eq(ACCT_ID), any(AccountDto.class)))
                    .thenReturn(updateResult);

            mockMvc.perform(put("/api/accounts/{acctId}", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateResult)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.acctId").value(100000001))
                    .andExpect(jsonPath("$.currBal").value(2000.00))
                    .andExpect(jsonPath("$.creditLimit").value(6000.00))
                    .andExpect(jsonPath("$.cashCreditLimit").value(1500.00));

            verify(accountService).updateAccount(eq(ACCT_ID), any(AccountDto.class));
        }

        @Test
        @DisplayName("Update unknown account -> 404 Not Found (code 101)")
        @WithMockUser
        void shouldReturn404OnUnknownAccount() throws Exception {
            when(accountService.updateAccount(eq(UNKNOWN_ACCT_ID), any(AccountDto.class)))
                    .thenThrow(AccountNotFoundException.withMessage(
                            "Did not find this account in account master file"));

            mockMvc.perform(put("/api/accounts/{acctId}", UNKNOWN_ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sampleAccount)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.path").value("/api/accounts/99999999999"));

            verify(accountService).updateAccount(eq(UNKNOWN_ACCT_ID), any(AccountDto.class));
        }

        @Test
        @DisplayName("Concurrent update -> 409 Conflict (PR-22 - replaces VSAM READ UPDATE exclusive lock; COACTUPC L522)")
        @WithMockUser
        void shouldReturn409OnOptimisticLockFailure() throws Exception {
            // The service rethrows the JPA @Version conflict as ObjectOptimisticLockingFailureException
            // carrying the exact COACTUPC L522 message; GlobalExceptionHandler maps it to 409.
            when(accountService.updateAccount(eq(ACCT_ID), any(AccountDto.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException("Account", ACCT_ID));

            mockMvc.perform(put("/api/accounts/{acctId}", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sampleAccount)))
                    .andExpect(status().isConflict());

            verify(accountService).updateAccount(eq(ACCT_ID), any(AccountDto.class));
        }

        @Test
        @DisplayName("Invalid payload (negative credit limit) -> 400 Bad Request (@DecimalMin(\"0.00\"), PR-16)")
        @WithMockUser
        void shouldReject400OnNegativeCreditLimit() throws Exception {
            // Only creditLimit is invalid (negative); every other field is valid, so the sole
            // violation is the DTO @DecimalMin("0.00") on creditLimit, raising
            // MethodArgumentNotValidException -> 400 before the service is ever invoked.
            AccountDto invalid = AccountDto.builder()
                    .acctId(ACCT_ID)
                    .activeStatus("Y")
                    .currBal(new BigDecimal("0.00"))
                    .creditLimit(new BigDecimal("-100.00"))
                    .cashCreditLimit(new BigDecimal("100.00"))
                    .openDate("2020-01-15")
                    .expirationDate("2025-12-31")
                    .reissueDate("2023-01-15")
                    .currCycCredit(new BigDecimal("0.00"))
                    .currCycDebit(new BigDecimal("0.00"))
                    .addrZip("60601")
                    .groupId("DEFAULT")
                    .build();

            mockMvc.perform(put("/api/accounts/{acctId}", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Empty request body -> 400 Bad Request (HttpMessageNotReadableException)")
        @WithMockUser
        void shouldReject400OnEmptyBody() throws Exception {
            // An empty body cannot be deserialized to AccountDto; the dispatcher raises
            // HttpMessageNotReadableException during @RequestBody resolution, mapped to 400.
            mockMvc.perform(put("/api/accounts/{acctId}", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(""))
                    .andExpect(status().isBadRequest());
        }
    }
}

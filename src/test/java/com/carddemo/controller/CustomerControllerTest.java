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
import com.carddemo.dto.customer.CustomerDto;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.service.CustomerService;

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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc web-slice tests for {@link CustomerController} &mdash; the REST replacement for the
 * customer-info read of {@code app/cbl/COACTVWC.cbl} (CICS online program, TRANID
 * {@code 'CAVW'}), paragraph {@code 9400-GETCUSTDATA-BYCUST}
 * ({@code app/cbl/COACTVWC.cbl} lines 825-870, which reads {@code DATASET('CUSTDAT')} by
 * {@code CUST-ID PIC 9(09)}).
 *
 * <p>The single endpoint under test is {@code GET /api/customers/{custId}}. The headline
 * behaviour being verified is <strong>PR-20 &mdash; SSN masking on outbound</strong>: a
 * customer's 9-digit Social Security Number is PII and must <em>never</em> leave the server
 * in plain form. The {@code CustomerMapper} (exercised inside {@code CustomerService}) masks
 * it to {@code ***-**-####} (only the last four digits visible) before the
 * {@link CustomerDto} is serialized. Because this is a controller slice, the masked DTO is
 * supplied to the test as the stubbed service return value, and the assertions confirm both
 * that the mask is present <em>and</em> &mdash; as a paranoid, full-body leakage guard &mdash;
 * that the raw 9-digit SSN never appears anywhere in the response.</p>
 *
 * <h2>Slice configuration (matches the established pattern used by every controller test in
 * this module)</h2>
 * <ul>
 *   <li>{@link WebMvcTest @WebMvcTest(controllers = CustomerController.class)} loads only the
 *       {@code CustomerController} web layer; its sole collaborator {@link CustomerService}
 *       is supplied as a {@link MockBean}.</li>
 *   <li>{@code excludeFilters} drops the entire {@code com.carddemo.security} package from the
 *       slice's component scan. A {@code @WebMvcTest} slice always registers application
 *       {@code jakarta.servlet.Filter} beans, and the production
 *       {@code com.carddemo.security.JwtAuthenticationFilter} is a {@code @Component} extending
 *       {@code OncePerRequestFilter}; left untouched it would be instantiated here and fail the
 *       context with an {@code UnsatisfiedDependencyException} because its
 *       {@code CustomAuthorityMapper} collaborator (a plain {@code @Component}) is not loaded
 *       by the slice. {@code @AutoConfigureMockMvc(addFilters = false)} only removes filters
 *       from the MockMvc dispatch &mdash; it does NOT prevent the bean from being created &mdash;
 *       so the component-scan exclusion is what actually keeps the context minimal.</li>
 *   <li>{@link Import @Import(GlobalExceptionHandler.class)} wires the
 *       {@code @RestControllerAdvice} so HTTP status-code assertions reflect production error
 *       handling: {@code AccountNotFoundException} &rarr; {@code 404} (COBOL
 *       {@code DID-NOT-FIND-CUST-IN-CUSTDAT}) and {@code MethodArgumentTypeMismatchException}
 *       (a non-numeric path segment) &rarr; {@code 400}.</li>
 *   <li>{@link AutoConfigureMockMvc @AutoConfigureMockMvc(addFilters = false)} disables the
 *       security filter chain so the controller's HTTP behaviour is asserted in isolation.
 *       {@code CustomerController} carries no class-level {@code @PreAuthorize}: per AAP
 *       &sect;0.4.1.1 any <em>authenticated</em> caller (USER or ADMIN) may read customer data,
 *       exactly as the legacy account-view transaction was reachable from the regular user
 *       menu. Each test therefore runs with {@code @WithMockUser} (a default {@code ROLE_USER}
 *       principal), which populates {@code SecurityContextHolder} via the test execution
 *       listener independently of the disabled filter chain.</li>
 * </ul>
 *
 * <h2>Why the service call is {@code getCustomer(custId)}</h2>
 * <p>The {@code CustomerController} delegates to {@link CustomerService#getCustomer(Long)} (the
 * actual service API that performs the {@code 9400-GETCUSTDATA-BYCUST} keyed read and applies
 * the mapper's SSN masking). Tests stub and verify exactly that method &mdash; not a
 * {@code findById} &mdash; so the slice exercises the real controller-to-service contract.</p>
 *
 * <h2>Refactoring rules exercised</h2>
 * <ul>
 *   <li><strong>PR-20</strong> &mdash; SSN is masked to {@code ***-**-####} on outbound; the
 *       raw 9-digit value is asserted to be absent from the entire response body.</li>
 *   <li><strong>PR-28</strong> &mdash; Jakarta EE 10 baseline (no {@code javax.*}).</li>
 *   <li><strong>PR-29</strong> &mdash; {@code CustomerController} uses constructor injection of
 *       a single {@code final CustomerService}; the slice supplies it via {@code @MockBean}.</li>
 * </ul>
 *
 * @see CustomerController
 * @see CustomerService
 * @see CustomerDto
 * @see GlobalExceptionHandler
 */
@WebMvcTest(
        controllers = CustomerController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("CustomerController web-slice tests (GET /api/customers/{custId}; SSN masking PR-20)")
class CustomerControllerTest {

    /**
     * The literal, fully-unmasked 9-digit SSN that must <strong>never</strong> appear anywhere
     * in a response body (PR-20). The masked form {@link #MASKED_SSN} is what the
     * {@code CustomerMapper} emits for this value: {@code 123456789} &rarr; {@code ***-**-6789}
     * (last four digits {@code 6789} preserved). The paranoid full-body assertion forbids this
     * exact string, so every other field of the sample customer is deliberately chosen to be
     * free of the substring {@code "123456789"}.
     */
    private static final String RAW_SSN = "123456789";

    /**
     * The masked SSN form produced by {@code CustomerMapper} on outbound responses: three
     * asterisks, a dash, two asterisks, a dash, and the last four digits of the real SSN
     * (PR-20, {@code ***-**-####} &mdash; 11 characters total).
     */
    private static final String MASKED_SSN = "***-**-6789";

    /**
     * Customer id used for the happy-path lookups. Mirrors COBOL {@code CUST-ID PIC 9(09)} and
     * lies within the controller's {@code @Min(1)}/{@code @Max(999999999)} bounds.
     */
    private static final long CUST_ID = 100000001L;

    /**
     * Customer id used for the not-found scenario; also a valid 9-digit-range value so the
     * request passes binding/validation and reaches the (stubbed) service, which then throws.
     */
    private static final long UNKNOWN_CUST_ID = 99999999L;

    /** Auto-configured MockMvc for the {@code CustomerController} web slice (security filters disabled). */
    @Autowired
    private MockMvc mockMvc;

    /**
     * The sole controller collaborator, replaced by a Mockito mock in the slice context. The
     * real {@code CustomerService} performs the keyed customer-master read and the PR-20 SSN
     * masking; here it is stubbed so the controller's HTTP behaviour is asserted in isolation.
     */
    @MockBean
    private CustomerService customerService;

    /**
     * A fully-populated {@link CustomerDto} representing what the real service returns
     * <em>after</em> {@code CustomerMapper} has already masked the SSN. Field names match the
     * actual {@code CustomerDto} (COBOL {@code CVCUS01Y} field-name conventions:
     * {@code addrLine1}, {@code addrStateCd}, {@code addrCountryCd}, {@code addrZip},
     * {@code govtIssuedId}, {@code priCardHolderInd}, {@code ficoCreditScore}); {@code dateOfBirth}
     * is a {@code String} in {@code yyyy-MM-dd} form. Every value is free of the substring
     * {@code "123456789"} so the PR-20 leakage guard is meaningful.
     */
    private CustomerDto sampleCustomer;

    @BeforeEach
    void setUp() {
        // The service returns an already-masked DTO: CustomerMapper has applied PR-20 masking
        // before the value reaches the controller, so .ssn is the masked form, never the raw SSN.
        sampleCustomer = CustomerDto.builder()
                .custId(CUST_ID)
                .firstName("JOHN")
                .middleName("Q")
                .lastName("PUBLIC")
                .addrLine1("123 MAIN ST")
                .addrLine2("APT 4B")
                .addrLine3("")
                .addrStateCd("IL")
                .addrCountryCd("USA")
                .addrZip("60601")
                .phoneNum1("3125551234")
                .phoneNum2("")
                .ssn(MASKED_SSN)                 // PR-20: already masked (***-**-6789)
                .govtIssuedId("DL-ILLINOIS-0042") // deliberately free of "123456789"
                .dateOfBirth("1980-05-15")        // CustomerDto.dateOfBirth is a String, not LocalDate
                .eftAccountId("ACCT000456")
                .priCardHolderInd("Y")
                .ficoCreditScore(720)
                .build();
    }

    /**
     * Tests for {@code GET /api/customers/{custId}} &mdash; the customer-info read replacing
     * {@code COACTVWC} paragraph {@code 9400-GETCUSTDATA-BYCUST}.
     */
    @Nested
    @DisplayName("GET /api/customers/{custId}")
    class GetCustomer {

        @Test
        @DisplayName("Existing customer -> 200 OK with masked SSN (PR-20 - never returns plain SSN)")
        @WithMockUser
        void shouldReturnCustomerWithMaskedSsn() throws Exception {
            when(customerService.getCustomer(CUST_ID)).thenReturn(sampleCustomer);

            mockMvc.perform(get("/api/customers/{custId}", CUST_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.custId").value(100000001))
                    .andExpect(jsonPath("$.firstName").value("JOHN"))
                    .andExpect(jsonPath("$.lastName").value("PUBLIC"))
                    // CRITICAL PR-20 ASSERTION: the SSN field must carry the masked form only.
                    .andExpect(jsonPath("$.ssn").value(MASKED_SSN));

            // The controller delegates the lookup + masking to the service (actual API: getCustomer).
            verify(customerService).getCustomer(CUST_ID);
        }

        @Test
        @DisplayName("Unknown customer -> 404 Not Found (COACTVWC DID-NOT-FIND-CUST-IN-CUSTDAT)")
        @WithMockUser
        void shouldReturn404ForUnknownCustomer() throws Exception {
            // The service surfaces the COBOL DFHRESP(NOTFND) branch as AccountNotFoundException
            // carrying the exact COBOL message literal; GlobalExceptionHandler maps it to 404 and
            // copies the message + request URI onto the ErrorResponse payload.
            when(customerService.getCustomer(UNKNOWN_CUST_ID))
                    .thenThrow(AccountNotFoundException.withMessage(
                            "Did not find associated customer in master file"));

            mockMvc.perform(get("/api/customers/{custId}", UNKNOWN_CUST_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.path").value("/api/customers/99999999"));

            verify(customerService).getCustomer(UNKNOWN_CUST_ID);
        }

        @Test
        @DisplayName("PR-20 - response body never contains the plain 9-digit SSN")
        @WithMockUser
        void shouldNeverReturnPlainSsn() throws Exception {
            when(customerService.getCustomer(CUST_ID)).thenReturn(sampleCustomer);

            // Use content().string() to inspect the FULL response body rather than a single JSON
            // path. JSON paths only check named fields; the full-body check catches leakage from
            // any field (or any future debug/echo field) - a paranoid double-check for PR-20.
            mockMvc.perform(get("/api/customers/{custId}", CUST_ID))
                    .andExpect(status().isOk())
                    // The masked prefix MUST be present.
                    .andExpect(content().string(containsString("***-**-")))
                    // The raw 9-digit SSN MUST NEVER appear anywhere in the body.
                    .andExpect(content().string(not(containsString(RAW_SSN))));
        }

        @Test
        @DisplayName("Response includes full customer details (actual CustomerDto field names)")
        @WithMockUser
        void shouldReturnAllCustomerFields() throws Exception {
            when(customerService.getCustomer(CUST_ID)).thenReturn(sampleCustomer);

            mockMvc.perform(get("/api/customers/{custId}", CUST_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.middleName").value("Q"))
                    .andExpect(jsonPath("$.addrLine1").value("123 MAIN ST"))
                    .andExpect(jsonPath("$.addrLine2").value("APT 4B"))
                    .andExpect(jsonPath("$.addrStateCd").value("IL"))
                    .andExpect(jsonPath("$.addrCountryCd").value("USA"))
                    .andExpect(jsonPath("$.addrZip").value("60601"))
                    .andExpect(jsonPath("$.phoneNum1").value("3125551234"))
                    .andExpect(jsonPath("$.dateOfBirth").value("1980-05-15"))
                    .andExpect(jsonPath("$.ficoCreditScore").value(720))
                    .andExpect(jsonPath("$.priCardHolderInd").value("Y"))
                    .andExpect(jsonPath("$.govtIssuedId").value("DL-ILLINOIS-0042"));
        }

        @Test
        @DisplayName("Non-numeric customer ID -> 400 Bad Request (MethodArgumentTypeMismatchException)")
        @WithMockUser
        void shouldReject400OnNonNumericId() throws Exception {
            // "NOT_A_NUMBER" cannot bind to the Long custId path variable; the dispatcher raises
            // MethodArgumentTypeMismatchException during argument binding (before the service is
            // reached), which GlobalExceptionHandler maps to 400.
            mockMvc.perform(get("/api/customers/{custId}", "NOT_A_NUMBER"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Out-of-range customer ID (0) -> 400 Bad Request (@Min(1) ConstraintViolationException)")
        @WithMockUser
        void shouldReject400OnOutOfRangeId() throws Exception {
            // The class-level @Validated + @Min(1) on the path variable rejects a zero/negative
            // 9-digit id with a ConstraintViolationException, mapped to 400 by
            // GlobalExceptionHandler - mirroring COBOL CUST-ID PIC 9(09) being a positive value.
            mockMvc.perform(get("/api/customers/{custId}", 0L))
                    .andExpect(status().isBadRequest());
        }
    }
}

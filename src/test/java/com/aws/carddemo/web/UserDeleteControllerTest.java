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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.UserDeleteRequest;
import com.aws.carddemo.dto.UserDeleteResponse;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.UserMapper;
import com.aws.carddemo.observability.CorrelationIdFilter;
import com.aws.carddemo.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest @WebMvcTest} slice test
 * for {@link UserDeleteController} &mdash; the online re-expression of the CardDemo COBOL
 * <em>Delete User</em> program {@code COUSR03C} (CICS transaction {@code CU03}, BMS map
 * {@code COUSR03}).
 *
 * <p><strong>What it verifies.</strong> This test locks the observable REST contract of the
 * administrator-only Delete User screen so that no future change can silently break behavioral
 * parity (AAP &sect;0.7.1 hotspot H2 &mdash; a dropped field or PF-key path is a regression; AAP
 * &sect;0.7.3 hotspot L1 &mdash; the credential is never exposed):</p>
 * <ul>
 *   <li><strong>Authorization</strong> &mdash; the endpoint is double-guarded (the
 *       {@code /api/v1/admin/**} URL rule in {@link SecurityConfig} plus the class-level
 *       {@code @PreAuthorize("hasRole('ADMIN')")}). An anonymous caller is rejected with
 *       {@code 401}, a non-admin ({@code ROLE_USER}) with {@code 403} (and the service is never
 *       invoked), and an administrator is served.</li>
 *   <li><strong>Fetch&rarr;confirm&rarr;delete flow</strong> &mdash; {@code ENTER} fetches the user
 *       for confirmation (and does <em>not</em> delete), {@code F5} confirms the delete, and an
 *       unknown user id surfaces as {@code 404 Record Not Found} on either path (the COBOL
 *       {@code DFHRESP(NOTFND)} outcome).</li>
 *   <li><strong>PF-key semantics</strong> &mdash; {@code F4} clears the screen, {@code F3}/{@code F12}
 *       navigate back to the Admin Menu ({@code COADM01C}/{@code CA00}) via navigation headers, and
 *       any other/unmapped key yields the shared invalid-key advisory.</li>
 *   <li><strong>Password-never-present</strong> &mdash; the Delete User map exposes no password
 *       field, so no response (fetch, confirm, or error) may ever carry a password property or the
 *       stored credential value.</li>
 *   <li><strong>Field-contract parity</strong> &mdash; the request/response DTOs preserve the field
 *       set of the {@code COUSR03} symbolic map.</li>
 * </ul>
 *
 * <p><strong>Harness.</strong> The slice loads only {@link UserDeleteController}, importing the real
 * {@link SecurityConfig} (so the authentication/authorization rules are exercised for real) and the
 * real {@link UserMapper} (so the DTO projection is exercised for real). The
 * {@code GlobalExceptionHandler} {@code @RestControllerAdvice} is auto-detected by the slice and
 * maps {@link RecordNotFoundException} to {@code 404}. Only {@link UserService} is mocked with
 * {@link MockitoBean @MockitoBean}. The test is deterministic and headless; it opens no database,
 * network, or terminal.</p>
 */
@WebMvcTest(UserDeleteController.class)
@Import({SecurityConfig.class, UserMapper.class})
@DisplayName("UserDeleteController (COUSR03C / CU03) — Delete User REST contract")
class UserDeleteControllerTest {

    /** The Delete User endpoint (matches {@code UserDeleteController @RequestMapping}). */
    private static final String ENDPOINT = "/api/v1/admin/users/delete";

    /** Canonical example user id (8 chars, matching the OpenAPI {@code UserDeleteRequest} example). */
    private static final String USER_ID = "USER0001";

    /**
     * A deliberately fake password value placed on the stub {@link UserSecurity} so the tests can
     * prove it never leaks into any response body. It intentionally matches no real credential
     * provider pattern.
     */
    private static final String STUB_PASSWORD = "SECRET99";

    /** Confirmation prompt after a successful fetch ({@code COUSR03C READ-USER-SEC-FILE} NORMAL). */
    private static final String MSG_CONFIRM_DELETE = "Press PF5 key to delete this user ...";

    /** Not-found message ({@code COUSR03C} {@code WHEN DFHRESP(NOTFND)}). */
    private static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    /** Shared invalid-key advisory ({@code CCDA-MSG-INVALID-KEY}, {@code WHEN OTHER}). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Screen-header program name ({@code WS-PGMNAME}). */
    private static final String PROGRAM_NAME = "COUSR03C";

    /** Screen-header transaction id ({@code WS-TRANID}). */
    private static final String TRANSACTION_NAME = "CU03";

    /** Admin Menu program navigated to on {@code F3}/{@code F12} ({@code RETURN-TO-PREV-SCREEN}). */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /** Admin Menu transaction navigated to on {@code F3}/{@code F12}. */
    private static final String ADMIN_TRANSACTION = "CA00";

    /** Response header carrying the next program to navigate to ({@code CDEMO-TO-PROGRAM}). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header carrying the next transaction id to navigate to ({@code CDEMO-TO-TRANID}). */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * MIME sniffing guard emitted by Spring Security's default header writers
     * ({@code X-Content-Type-Options: nosniff}). Asserted by the shared
     * production-filter contract (section&nbsp;K/L) so a future security change
     * cannot silently drop the response-hardening headers.
     */
    private static final String HEADER_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /** Clickjacking guard emitted by Spring Security ({@code X-Frame-Options: DENY}). */
    private static final String HEADER_FRAME_OPTIONS = "X-Frame-Options";

    /**
     * Cache-suppression header emitted by Spring Security
     * ({@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate}); the
     * {@code no-store} directive keeps authenticated screen/PII payloads out of
     * shared and browser caches.
     */
    private static final String HEADER_CACHE_CONTROL = "Cache-Control";

    /** HTTP/1.0 cache-suppression companion header ({@code Pragma: no-cache}). */
    private static final String HEADER_PRAGMA = "Pragma";

    /** HTTP/1.0 expiry companion header ({@code Expires: 0}). */
    private static final String HEADER_EXPIRES = "Expires";

    /**
     * Header listing the methods a matched route supports, emitted with a
     * {@code 405 Method Not Allowed} per RFC&nbsp;7231&nbsp;&sect;6.5.5 (asserted by
     * the wrong-method transport case).
     */
    private static final String HEADER_ALLOW = "Allow";

    /**
     * Challenge header emitted with a {@code 401 Unauthorized} by the HTTP&nbsp;Basic
     * entry point (asserted by the unauthenticated public-path case).
     */
    private static final String HEADER_WWW_AUTHENTICATE = "WWW-Authenticate";

    /**
     * Canonical lower-case textual form of a {@link java.util.UUID} (8-4-4-4-12 hex
     * groups). The {@link CorrelationIdFilter} substitutes a freshly generated
     * {@code UUID.randomUUID()} whenever the inbound correlation header is absent or
     * fails its allow-list, so a resolved-by-generation identifier always matches
     * this shape. Used to prove the generated id is a bounded, opaque token and that
     * a rejected hostile header was replaced by exactly such a token.
     */
    private static final String UUID_REGEX =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the stub user echoed on the confirm screen. It carries a (fake) password so the tests
     * can assert the credential never reaches a response.
     *
     * @return a {@link UserSecurity} with id {@link #USER_ID}
     */
    private static UserSecurity stubUser() {
        return new UserSecurity(USER_ID, "John", "Doe", STUB_PASSWORD, "U");
    }

    /**
     * Builds a stub user with an arbitrary id, used to prove the fetch requires an exact key match.
     *
     * @param id the user id to assign
     * @return a {@link UserSecurity} with the given id
     */
    private static UserSecurity userWithId(String id) {
        return new UserSecurity(id, "Jane", "Roe", STUB_PASSWORD, "A");
    }

    /**
     * Serializes a Delete User request body via the application's {@link ObjectMapper}, reusing the
     * real {@link UserDeleteRequest} record so the wire shape matches the published contract.
     *
     * @param userId the user id to send (may be {@code null})
     * @param action the PF-key action to send (may be {@code null} to omit it)
     * @return the JSON request body
     * @throws Exception if serialization fails
     */
    private String body(String userId, PfKeyAction action) throws Exception {
        return objectMapper.writeValueAsString(new UserDeleteRequest(userId, action));
    }

    // -------------------------------------------------------------------------
    // Shared production-filter contract (F16)
    //
    // The following helpers assert the cross-cutting contract that the production
    // servlet filter chain applies to EVERY response on this surface, independent
    // of the business branch, and are reused across the protected (section K),
    // public/error (section L), and invalid-input (section M) cases so the
    // contract is proven uniformly rather than per screen:
    //   * observability/CorrelationIdFilter echoes a bounded, opaque correlation
    //     id on the response and exposes it via the SLF4J MDC for the duration of
    //     the request;
    //   * Spring Security's default header writers emit the response-hardening
    //     headers (anti-sniff, anti-clickjacking, no-store cache suppression).
    // This is the "reusable production-filter contract" required by review finding
    // F16, expressed as helper methods scoped to the Delete User surface.
    // -------------------------------------------------------------------------

    /**
     * Asserts the Spring Security response-hardening headers are present on the
     * given result exactly as the production filter chain emits them. Applied to
     * success, navigation, and every error response so a future change to
     * {@link SecurityConfig} cannot silently drop the response-hardening headers
     * (which keep authenticated screen/PII payloads out of caches and defend
     * against MIME sniffing and clickjacking).
     *
     * @param result the completed {@link MvcResult} whose response headers are
     *               inspected
     */
    private static void assertHardeningHeaders(MvcResult result) {
        var response = result.getResponse();
        assertThat(response.getHeader(HEADER_CONTENT_TYPE_OPTIONS))
                .as("X-Content-Type-Options").isEqualTo("nosniff");
        assertThat(response.getHeader(HEADER_FRAME_OPTIONS))
                .as("X-Frame-Options").isEqualTo("DENY");
        assertThat(response.getHeader(HEADER_CACHE_CONTROL))
                .as("Cache-Control").contains("no-store", "no-cache");
        assertThat(response.getHeader(HEADER_PRAGMA))
                .as("Pragma").isEqualTo("no-cache");
        assertThat(response.getHeader(HEADER_EXPIRES))
                .as("Expires").isEqualTo("0");
    }

    /**
     * Asserts a correlation id was resolved by the {@link CorrelationIdFilter} and
     * echoed on the response {@value CorrelationIdFilter#CORRELATION_ID_HEADER}
     * header, and returns it so a caller can make further echo, sanitization, or
     * header/body-consistency assertions.
     *
     * @param result the completed {@link MvcResult} whose response is inspected
     * @return the resolved correlation id echoed on the response (never blank)
     */
    private static String resolvedCorrelationId(MvcResult result) {
        String correlationId =
                result.getResponse().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(correlationId)
                .as("echoed %s response header", CorrelationIdFilter.CORRELATION_ID_HEADER)
                .isNotBlank();
        return correlationId;
    }

    // -------------------------------------------------------------------------
    // A. Authorization matrix (401 / 403-USER-service-not-invoked / 200-ADMIN)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("A1: an unauthenticated request is rejected with 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());

        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("A2: a non-admin (USER) is forbidden (403) and the service is never invoked")
    void userRole_returns403_serviceNeverInvoked() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_ID, PfKeyAction.ENTER)))
                .andExpect(status().isForbidden());

        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("A3: an admin GET returns the blank Delete User screen (200) with no service call")
    void adminGet_returnsBlankScreen() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_NAME));

        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    // -------------------------------------------------------------------------
    // B. ENTER fetch — found (200 confirm; no password; fetch does not delete)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("B: ENTER fetches the user for confirmation (200) without deleting or leaking a password")
    void enterFetch_found_returnsConfirmationWithoutPassword() throws Exception {
        Page<UserSecurity> page = new PageImpl<>(List.of(stubUser()));
        when(userService.listUsers(eq(USER_ID), any(Pageable.class))).thenReturn(page);

        String responseBody = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_ID, PfKeyAction.ENTER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Doe"))
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.errorMessage").value(MSG_CONFIRM_DELETE))
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_NAME))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.secUsrPwd").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(STUB_PASSWORD);
        verify(userService).listUsers(eq(USER_ID), any(Pageable.class));
        verify(userService, never()).deleteUser(any());
    }

    // -------------------------------------------------------------------------
    // C. ENTER fetch — not found (404 problem+json)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("C1: ENTER on an unknown user id surfaces 404 Record Not Found (application/problem+json)")
    void enterFetch_notFound_returns404() throws Exception {
        when(userService.listUsers(eq("NOSUCH99"), any(Pageable.class)))
                .thenReturn(Page.<UserSecurity>empty());

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOSUCH99", PfKeyAction.ENTER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.detail").value(MSG_USER_ID_NOT_FOUND));

        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("C2: ENTER returns 404 when the browse lands on a different id (exact key match required)")
    void enterFetch_nonMatchingId_returns404() throws Exception {
        Page<UserSecurity> page = new PageImpl<>(List.of(userWithId("USER0009")));
        when(userService.listUsers(eq("USER0002"), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", PfKeyAction.ENTER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Record Not Found"));

        verify(userService, never()).deleteUser(any());
    }

    // -------------------------------------------------------------------------
    // D. PF5 confirm delete — success (verify deleteUser)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("D: PF5 confirms the delete (200) and invokes deleteUser exactly once")
    void pf5_deleteSuccess_invokesServiceOnce() throws Exception {
        String successMessage = "User " + USER_ID + " has been deleted ...";
        when(userService.deleteUser(USER_ID))
                .thenReturn(new UserService.UserResult(true, stubUser(), successMessage));

        String responseBody = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_ID, PfKeyAction.PF5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(successMessage))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.secUsrPwd").doesNotExist())
                .andExpect(jsonPath("$.firstName").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(STUB_PASSWORD);
        verify(userService).deleteUser(USER_ID);
        verify(userService, never()).listUsers(any(), any());
    }

    // -------------------------------------------------------------------------
    // E. PF5 confirm delete — not found (404)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("E: PF5 on an unknown user id surfaces 404 Record Not Found")
    void pf5_deleteNotFound_returns404() throws Exception {
        when(userService.deleteUser("GHOST99"))
                .thenThrow(new RecordNotFoundException(MSG_USER_ID_NOT_FOUND));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("GHOST99", PfKeyAction.PF5)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.detail").value(MSG_USER_ID_NOT_FOUND));

        verify(userService).deleteUser("GHOST99");
    }

    // -------------------------------------------------------------------------
    // F. PF4 clear (never deletes)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("F: PF4 clears the screen (200), emits no navigation header, and never deletes")
    void pf4_clear_returnsBlankScreen() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_ID, PfKeyAction.PF4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_NAME))
                .andExpect(header().doesNotExist(HEADER_NEXT_PROGRAM))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    // -------------------------------------------------------------------------
    // G. PF3 / PF12 navigate back to the Admin Menu (COADM01C / CA00)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("G1: PF3 navigates back to the Admin Menu (200 + navigation headers) and never deletes")
    void pf3_back_returnsAdminMenuNavigation() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_ID, PfKeyAction.PF3)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, ADMIN_PROGRAM))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, ADMIN_TRANSACTION))
                .andExpect(jsonPath("$.programName").value(ADMIN_PROGRAM))
                .andExpect(jsonPath("$.transactionName").value(ADMIN_TRANSACTION));

        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("G2: PF12 also navigates back to the Admin Menu (mirrors COUSR03C DFHPF12)")
    void pf12_back_returnsAdminMenuNavigation() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_ID, PfKeyAction.PF12)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, ADMIN_PROGRAM))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, ADMIN_TRANSACTION));

        verify(userService, never()).deleteUser(any());
    }

    // -------------------------------------------------------------------------
    // H. Other / unmapped key -> invalid-key advisory (never deletes)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("H1: an unmapped key (PF7) yields the invalid-key advisory (200) and never deletes")
    void unmappedKey_returnsInvalidKeyMessage() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_ID, PfKeyAction.PF7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_KEY));

        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("H2: a missing action also yields the invalid-key advisory (200)")
    void missingAction_returnsInvalidKeyMessage() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_ID, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_KEY));

        verify(userService, never()).deleteUser(any());
    }

    // -------------------------------------------------------------------------
    // I. Password never present (MANDATORY)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("I: no response exposes a password field or the stored credential value")
    void response_neverExposesPassword() throws Exception {
        Page<UserSecurity> page = new PageImpl<>(List.of(stubUser()));
        when(userService.listUsers(eq(USER_ID), any(Pageable.class))).thenReturn(page);

        String fetchBody = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_ID, PfKeyAction.ENTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.secUsrPwd").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(fetchBody)
                .doesNotContain(STUB_PASSWORD)
                .doesNotContain("password")
                .doesNotContain("secUsrPwd");
    }

    // -------------------------------------------------------------------------
    // J. Field-contract parity with the COUSR03 symbolic map
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("J1: UserDeleteRequest preserves the COUSR03 input contract {userId, action} with no password")
    void requestDto_fieldContract() {
        List<String> names = Arrays.stream(UserDeleteRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(names).containsExactly("userId", "action");
        assertThat(names).doesNotContain("password", "secUsrPwd", "pwd");
    }

    @Test
    @DisplayName("J2: UserDeleteResponse preserves the COUSR03 output contract and exposes no password")
    void responseDto_fieldContract() {
        List<String> names = Arrays.stream(UserDeleteResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(names).containsExactly(
                "transactionName", "title01", "title02", "currentDate", "programName",
                "currentTime", "userId", "firstName", "lastName", "userType", "errorMessage");
        assertThat(names).doesNotContain("password", "secUsrPwd", "pwd");
    }

    // -------------------------------------------------------------------------
    // K. Production-filter contract on the protected path — correlation id
    //    (generation / echo / sanitization / MDC cleanup) + hardening headers
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("K1: a protected 200 carries a generated correlation id and the security-hardening headers, and cleans up the MDC")
    void protectedResponse_carriesGeneratedCorrelationAndHardeningHeaders() throws Exception {
        // No inbound correlation header: the filter must generate a fresh UUID and
        // echo it, and Spring Security must emit the response-hardening headers.
        MvcResult result = mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(resolvedCorrelationId(result))
                .as("a generated correlation id is a bounded, opaque UUID token")
                .matches(UUID_REGEX);
        assertHardeningHeaders(result);

        // Thread-context hygiene: the filter removes its MDC key in a finally block
        // once the request completes, so the id cannot leak onto a pooled request
        // thread and contaminate a later, unrelated request. MockMvc runs the filter
        // chain synchronously on this thread, so that cleanup has already executed by
        // the time perform(...) returns.
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY))
                .as("correlation id is removed from the MDC after the request completes")
                .isNull();

        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("K2: a safe inbound correlation id is honored and echoed back unchanged")
    void inboundCorrelationId_isEchoedVerbatim() throws Exception {
        // A value drawn only from the filter's allow-list (letters/digits/._-).
        String inbound = "delete-user-corr-0001";

        MvcResult result = mockMvc.perform(get(ENDPOINT)
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, inbound))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(resolvedCorrelationId(result))
                .as("a safe upstream correlation id is preserved end to end")
                .isEqualTo(inbound);
        assertHardeningHeaders(result);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("K3: a hostile inbound correlation id is rejected and replaced with a safe generated token")
    void hostileInboundCorrelationId_isSanitized() throws Exception {
        // An attempted log/response-header injection through the correlation header.
        String hostile = "<script>alert('xss')</script>";

        MvcResult result = mockMvc.perform(get(ENDPOINT)
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, hostile))
                .andExpect(status().isOk())
                .andReturn();

        // The disallowed value must be discarded in favor of a fresh UUID; the
        // injected markup must never be reflected on the echoed header.
        assertThat(resolvedCorrelationId(result))
                .as("a hostile correlation header is replaced, never echoed")
                .isNotEqualTo(hostile)
                .doesNotContain("<", ">")
                .matches(UUID_REGEX);
        assertThat(result.getResponse().getContentAsString())
                .as("the injected markup must not appear anywhere in the response body")
                .doesNotContain(hostile);
        assertHardeningHeaders(result);
    }

    // -------------------------------------------------------------------------
    // L. Production-filter contract on the public / error paths — the same
    //    correlation + hardening contract holds when a failure is raised inside
    //    the security filter chain (401) and by the exception handler (404),
    //    with the correlation id present in BOTH the header and the problem body.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("L1: an unauthenticated request emits RFC-7807 401 with the correlation id in header and body, plus the hardening headers")
    void unauthenticated_errorPath_carriesUniformFilterContract() throws Exception {
        // Because CorrelationIdFilter runs at HIGHEST_PRECEDENCE (ahead of the
        // security chain), even a 401 raised before the DispatcherServlet carries
        // the correlation id on the header and in the RFC-7807 problem body.
        MvcResult result = mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andReturn();

        String correlationId = resolvedCorrelationId(result);
        assertThat(result.getResponse().getContentAsString())
                .as("the problem body echoes the same correlation id as the response header")
                .contains("\"correlationId\":\"" + correlationId + "\"");
        assertThat(result.getResponse().getHeader(HEADER_WWW_AUTHENTICATE))
                .as("HTTP Basic challenge is present on the 401")
                .contains("Basic");
        assertHardeningHeaders(result);

        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("L2: a 404 error response carries the correlation id in header and body, plus the hardening headers")
    void notFoundErrorPath_carriesUniformFilterContract() throws Exception {
        when(userService.listUsers(eq("NOSUCH99"), any(Pageable.class)))
                .thenReturn(Page.<UserSecurity>empty());

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOSUCH99", PfKeyAction.ENTER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        String correlationId = resolvedCorrelationId(result);
        assertThat(result.getResponse().getContentAsString())
                .as("the problem body echoes the same correlation id as the response header")
                .contains("\"correlationId\":\"" + correlationId + "\"");
        assertHardeningHeaders(result);

        verify(userService, never()).deleteUser(any());
    }

    // -------------------------------------------------------------------------
    // M. Per-surface invalid / hostile input coverage — each malformed transport
    //    is rejected with the correct typed status BEFORE any business logic runs
    //    (the service is never invoked), and no rejected value is echoed.
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("M1: a malformed JSON body is rejected with 400 Malformed Request before any service call")
    void malformedJson_returns400_serviceNeverInvoked() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"userId\": \"USER0001\", "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Malformed Request"));

        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("M2: an unknown action enum value is rejected with 400 Malformed Request and is not echoed")
    void hostileActionValue_returns400_serviceNeverInvoked() throws Exception {
        String hostileAction = "DROP-TABLES";

        String responseBody = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"userId\": \"" + USER_ID + "\", \"action\": \"" + hostileAction + "\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Malformed Request"))
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody)
                .as("the rejected action value must not be echoed back")
                .doesNotContain(hostileAction);
        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("M3: an over-width userId (> 8) is rejected with 400 Validation Failed and the rejected value is never echoed")
    void overWidthUserId_returns400_rejectedValueNotEchoed() throws Exception {
        String overWidth = "USER000123"; // 10 chars — violates the COUSR03 PIC X(8) @Size(max = 8)

        String responseBody = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"userId\": \"" + overWidth + "\", \"action\": \"ENTER\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andReturn().getResponse().getContentAsString();

        // The offending field NAME is disclosed so the caller can correct the input,
        // but the rejected VALUE is never echoed (it could carry sensitive input).
        assertThat(responseBody)
                .as("validation detail names the field but never echoes the rejected value")
                .contains("userId")
                .doesNotContain(overWidth);
        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("M4: a non-JSON Content-Type is rejected with 415 Unsupported Media Type before any service call")
    void unsupportedMediaType_returns415_serviceNeverInvoked() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("USER0001"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unsupported Media Type"));

        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("M5: an unsupported HTTP method is rejected with 405 Method Not Allowed and an Allow header")
    void wrongHttpMethod_returns405_withAllowHeader() throws Exception {
        MvcResult result = mockMvc.perform(put(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_ID, PfKeyAction.ENTER)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Method Not Allowed"))
                .andReturn();

        // The matched route exposes only GET (blank screen) and POST (submit).
        assertThat(result.getResponse().getHeader(HEADER_ALLOW))
                .as("Allow header lists the supported methods")
                .contains("POST", "GET");
        verify(userService, never()).listUsers(any(), any());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("M6: a hostile but in-width userId that is not found yields 404 without ever reflecting the hostile value")
    void hostileUserId_notFound_valueNotReflected() throws Exception {
        String hostile = "<b>x</b>"; // exactly 8 chars — passes @Size(max = 8) yet is hostile
        when(userService.listUsers(eq(hostile), any(Pageable.class)))
                .thenReturn(Page.<UserSecurity>empty());

        String responseBody = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(hostile, PfKeyAction.ENTER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.detail").value(MSG_USER_ID_NOT_FOUND))
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody)
                .as("the not-found problem detail must not reflect the hostile input")
                .doesNotContain(hostile)
                .doesNotContain("<b>", "</b>");
        verify(userService, never()).deleteUser(any());
    }
}

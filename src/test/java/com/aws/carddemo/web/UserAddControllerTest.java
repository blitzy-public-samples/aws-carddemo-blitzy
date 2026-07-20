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
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.eq;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.UserAddRequest;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.mapper.UserMapper;
import com.aws.carddemo.service.UserService;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
 * WebMvcTest} slice test for {@link UserAddController} &mdash; the REST
 * re-expression of the legacy online program {@code COUSR01C} (CICS transaction
 * {@code CU01}, screen {@code COUSR01}, mapset {@code COUSR1A}).
 *
 * <p>These tests exercise the <em>Add User</em> contract at the HTTP boundary
 * with the {@link UserService} mocked. They assert, per the migration's
 * behavioral-parity and security constraints (AAP &sect;0.7 hotspots H2/L1,
 * &sect;0.9.3):</p>
 * <ul>
 *   <li><strong>Authorization</strong> &mdash; the screen is ADMIN-only,
 *       double-guarded by the {@code /api/v1/admin/**} URL rule and the
 *       controller's class-level {@code @PreAuthorize("hasRole('ADMIN')")}:
 *       unauthenticated&nbsp;&rarr;&nbsp;401, an authenticated non-admin
 *       user&nbsp;&rarr;&nbsp;403 (with the service never invoked), and an admin
 *       GET&nbsp;&rarr;&nbsp;200 blank form.</li>
 *   <li><strong>ENTER (submit)</strong> &mdash; the controller forwards the raw,
 *       unmodified password to {@link UserService#addUser}; the success message
 *       is surfaced on the screen message line.</li>
 *   <li><strong>Duplicate</strong> &mdash; a {@link DuplicateKeyException} from
 *       the service maps to {@code 409 Conflict}
 *       ({@code application/problem+json}) via the global handler.</li>
 *   <li><strong>Mandatory-field edits</strong> &mdash; emptiness is owned by the
 *       service (COBOL {@code PROCESS-ENTER-KEY}); the controller forwards blank
 *       input and echoes the service message rather than performing its own
 *       validation. Bean Validation still rejects length/character violations
 *       with {@code 400} and never leaks the rejected value.</li>
 *   <li><strong>PF-key semantics</strong> &mdash; PF4 clears, PF3 navigates back
 *       to the Admin Menu ({@code COADM01C}/{@code CA00}), and any other key
 *       re-displays with the invalid-key advisory.</li>
 *   <li><strong>Password discipline</strong> &mdash; the request password is
 *       {@code WRITE_ONLY} (never serialized back) and never appears in any
 *       response body.</li>
 * </ul>
 *
 * <p>The harness imports the real {@link SecurityConfig} (so the actual
 * authorization rules, {@code @EnableMethodSecurity}, and RFC-7807 401/403
 * responders are exercised) and the real {@link UserMapper} (so the response
 * projection is assembled exactly as in production). The test uses
 * {@code @MockitoBean} for the single collaborator, {@link UserService}. CSRF is
 * disabled by the application's stateless-API configuration, so no CSRF token is
 * supplied.</p>
 */
@WebMvcTest(UserAddController.class)
@Import({SecurityConfig.class, UserMapper.class})
@DisplayName("UserAddController (COUSR01C / CU01) web slice")
class UserAddControllerTest {

    /** The Add User screen resource path (COBOL {@code COUSR01C}, tran {@code CU01}). */
    private static final String BASE_PATH = "/api/v1/admin/users/add";

    /** Response header carrying the PF3 navigation target program (Admin Menu). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header carrying the PF3 navigation target transaction id. */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** Admin Menu program name that PF3 (back) navigates to (COBOL {@code COADM01C}). */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** Admin Menu transaction id that PF3 (back) navigates to ({@code CA00}). */
    private static final String ADMIN_MENU_TRANSACTION = "CA00";

    /** This screen's program name shown in the header (COBOL {@code WS-PGMNAME}). */
    private static final String PROGRAM_NAME = "COUSR01C";

    /** This screen's transaction id shown in the header (COBOL {@code WS-TRANID}). */
    private static final String TRANSACTION_ID = "CU01";

    /** Invalid-key advisory (COBOL {@code CCDA-MSG-INVALID-KEY}). */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /** Sample new user id ({@code SEC-USR-ID PIC X(08)}). */
    private static final String NEW_USER_ID = "NEWUSER1";

    /** Sample first name ({@code SEC-USR-FNAME PIC X(20)}). */
    private static final String FIRST_NAME = "John";

    /** Sample last name ({@code SEC-USR-LNAME PIC X(20)}). */
    private static final String LAST_NAME = "Doe";

    /**
     * Sample raw password ({@code SEC-USR-PWD PIC X(08)}). This is an obvious,
     * fake test token (not a real-provider credential); it is used to prove the
     * raw value reaches the service verbatim and is never echoed back.
     */
    private static final String RAW_PASSWORD = "PwTok123";

    /** Expected success message (COBOL {@code 'User ' <id> ' has been added ...'}). */
    private static final String ADD_SUCCESS_MESSAGE = "User " + NEW_USER_ID + " has been added ...";

    /** Expected duplicate-key message (COBOL {@code 'User ID already exist...'}). */
    private static final String DUPLICATE_MESSAGE = "User ID already exist...";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    // ------------------------------------------------------------------
    // Request-body helpers
    // ------------------------------------------------------------------

    /**
     * Builds a fully populated, valid Add User request body carrying the ENTER
     * (submit) action.
     *
     * <p>The body is assembled as a mutable {@link Map} rather than a
     * {@link UserAddRequest} instance <em>on purpose</em>: the request password is
     * annotated {@code WRITE_ONLY}, so serializing a {@code UserAddRequest} would
     * drop it and the raw credential would never reach the endpoint. Building the
     * JSON from a map guarantees the {@code password} member is present on the
     * inbound request, which is exactly what the raw-to-service assertion needs.</p>
     *
     * @return a mutable map of the five keyable fields plus {@code action=ENTER}
     */
    private static Map<String, Object> validEnterBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", FIRST_NAME);
        body.put("lastName", LAST_NAME);
        body.put("userId", NEW_USER_ID);
        body.put("password", RAW_PASSWORD);
        body.put("userType", "U");
        body.put("action", PfKeyAction.ENTER.name());
        return body;
    }

    /**
     * Serializes a request-body map to a JSON string using the application's
     * configured {@link ObjectMapper}.
     *
     * @param body the request-body map
     * @return the JSON representation
     * @throws Exception if serialization fails
     */
    private String asJson(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // ==================================================================
    // A. AUTHORIZATION (ADMIN-only, double-guarded)
    // ==================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("A1: unauthenticated POST is rejected with 401 and never reaches the service")
    void postWithoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(validEnterBody())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(userService);
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("A2: an authenticated non-admin (USER) POST is forbidden with 403 and never reaches the service")
    void postAsNonAdmin_returns403() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(validEnterBody())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(userService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("A3: an admin GET renders the blank Add User form (200) without calling the service")
    void getAsAdmin_returnsBlankForm() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_ID))
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.errorMessage").value(""));

        verifyNoInteractions(userService);
    }

    // ==================================================================
    // B. ENTER (submit) success
    // ==================================================================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("B: ENTER adds the user, forwards the RAW password verbatim, and shows the success message")
    void enterAddsUserAndForwardsRawPassword() throws Exception {
        // The service (mocked) hashes the password; here we assert only that the
        // controller hands it the RAW value unchanged. The returned message is the
        // exact COBOL confirmation text.
        UserSecurity persisted = new UserSecurity(NEW_USER_ID, FIRST_NAME, LAST_NAME, "STORED-HASH", "U");
        when(userService.addUser(any(), any(), any(), any(), anyChar()))
                .thenReturn(new UserService.UserResult(true, persisted, ADD_SUCCESS_MESSAGE));

        String responseBody = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(validEnterBody())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_ID))
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.errorMessage").value(ADD_SUCCESS_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        // Capture the raw password argument (4th positional arg of addUser) and
        // assert every field is forwarded to the service exactly as submitted.
        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(userService).addUser(
                eq(NEW_USER_ID),
                eq(FIRST_NAME),
                eq(LAST_NAME),
                passwordCaptor.capture(),
                eq('U'));
        assertThat(passwordCaptor.getValue()).isEqualTo(RAW_PASSWORD);

        // The response must never echo the password (no field, and not the value).
        assertThat(responseBody).doesNotContain(RAW_PASSWORD);
        assertThat(responseBody).doesNotContainIgnoringCase("password");
    }

    // ==================================================================
    // C. Duplicate user id -> 409 Conflict
    // ==================================================================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("C: a duplicate user id surfaces as 409 Conflict (application/problem+json) with no password leaked")
    void enterDuplicateUserId_returns409() throws Exception {
        when(userService.addUser(any(), any(), any(), any(), anyChar()))
                .thenThrow(new DuplicateKeyException(DUPLICATE_MESSAGE));

        String responseBody = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(validEnterBody())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Duplicate Record"))
                .andExpect(jsonPath("$.detail").value(DUPLICATE_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(RAW_PASSWORD);
        assertThat(responseBody).doesNotContainIgnoringCase("password");
    }

    // ==================================================================
    // D. Mandatory-field edits
    // ==================================================================

    /**
     * The five mandatory-field cases in COBOL {@code PROCESS-ENTER-KEY} order:
     * first name, last name, user id, password, then user type &mdash; each with
     * the exact message the legacy program placed on {@code ERRMSGO}.
     *
     * @return a stream of (omitted field, expected COBOL message) argument pairs
     */
    static Stream<Arguments> emptyMandatoryFieldCases() {
        return Stream.of(
                Arguments.of("firstName", "First Name can NOT be empty..."),
                Arguments.of("lastName", "Last Name can NOT be empty..."),
                Arguments.of("userId", "User ID can NOT be empty..."),
                Arguments.of("password", "Password can NOT be empty..."),
                Arguments.of("userType", "User Type can NOT be empty..."));
    }

    @ParameterizedTest(name = "[{index}] {0} empty -> \"{1}\"")
    @MethodSource("emptyMandatoryFieldCases")
    @WithMockUser(roles = "ADMIN")
    @DisplayName("D: an empty mandatory field is forwarded to the service, which returns the COBOL edit message (200)")
    void enterWithEmptyMandatoryField_echoesServiceMessage(String omittedField, String expectedMessage)
            throws Exception {
        Map<String, Object> body = validEnterBody();
        body.remove(omittedField); // omit the field entirely -> COBOL "= SPACES OR LOW-VALUES"
        when(userService.addUser(any(), any(), any(), any(), anyChar()))
                .thenReturn(new UserService.UserResult(false, null, expectedMessage));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(body)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorMessage").value(expectedMessage));

        // The controller performs no emptiness edit of its own; it forwards the
        // (blank) input and echoes the service message. The COBOL priority ORDER
        // itself is owned by UserService (verified by UserServiceTest), not here.
        verify(userService).addUser(any(), any(), any(), any(), anyChar());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("D-validation: an out-of-range userType fails Bean Validation with 400 and never reaches the service")
    void enterWithInvalidUserType_returns400() throws Exception {
        Map<String, Object> body = validEnterBody();
        body.put("userType", "X"); // not one of [A U a u space] -> @Pattern violation

        String responseBody = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andReturn().getResponse().getContentAsString();

        // The offending field NAME is reported; the service is never invoked.
        assertThat(responseBody).contains("userType");
        verify(userService, never()).addUser(any(), any(), any(), any(), anyChar());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("D-validation: an over-length password fails with 400 and the rejected value is never leaked")
    void enterWithOverLengthPassword_returns400AndDoesNotLeakValue() throws Exception {
        String overLongPassword = "TooLong99"; // 9 chars > @Size(max = 8)
        Map<String, Object> body = validEnterBody();
        body.put("password", overLongPassword);

        String responseBody = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andReturn().getResponse().getContentAsString();

        // The problem detail may name the "password" FIELD, but must NEVER echo
        // the rejected VALUE (a rejected value could be a real credential; AAP 0.9.3).
        assertThat(responseBody).doesNotContain(overLongPassword);
        verify(userService, never()).addUser(any(), any(), any(), any(), anyChar());
    }

    // ==================================================================
    // E. PF4 (clear form)
    // ==================================================================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("E: PF4 clears the form (200, blank message) without calling the service")
    void pf4ClearsForm() throws Exception {
        Map<String, Object> body = validEnterBody();
        body.put("action", PfKeyAction.PF4.name());

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(body)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_ID))
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.errorMessage").value(""));

        verifyNoInteractions(userService);
    }

    // ==================================================================
    // F. PF3 (back to Admin Menu)
    // ==================================================================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("F: PF3 navigates back to the Admin Menu (COADM01C / CA00) via navigation headers")
    void pf3NavigatesToAdminMenu() throws Exception {
        Map<String, Object> body = validEnterBody();
        body.put("action", PfKeyAction.PF3.name());

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(body)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HEADER_NEXT_PROGRAM, ADMIN_MENU_PROGRAM))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, ADMIN_MENU_TRANSACTION))
                .andExpect(jsonPath("$.transactionName").value(ADMIN_MENU_TRANSACTION))
                .andExpect(jsonPath("$.programName").value(ADMIN_MENU_PROGRAM))
                .andExpect(jsonPath("$.errorMessage").value(""));

        verifyNoInteractions(userService);
    }

    // ==================================================================
    // G. Any other / unmapped attention key
    // ==================================================================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("G: an unmapped attention key re-displays with the invalid-key advisory (200)")
    void otherKeyShowsInvalidKeyMessage() throws Exception {
        Map<String, Object> body = validEnterBody();
        body.put("action", PfKeyAction.PF5.name()); // not ENTER/PF3/PF4 -> COBOL WHEN OTHER

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(body)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorMessage").value(INVALID_KEY_MESSAGE));

        verifyNoInteractions(userService);
    }

    // ==================================================================
    // H. Password discipline (WRITE_ONLY, never echoed)
    // ==================================================================

    @Test
    @DisplayName("H: the request password is WRITE_ONLY - serializing UserAddRequest never emits it")
    void requestPasswordIsWriteOnly() throws Exception {
        UserAddRequest request = new UserAddRequest(
                FIRST_NAME, LAST_NAME, NEW_USER_ID, RAW_PASSWORD, "U", PfKeyAction.ENTER);

        String serialized = objectMapper.writeValueAsString(request);

        // WRITE_ONLY: accepted on input, never serialized back out.
        assertThat(serialized).doesNotContain(RAW_PASSWORD);
        assertThat(serialized).doesNotContainIgnoringCase("password");
        // Sanity: the non-sensitive fields are still projected.
        assertThat(serialized).contains(NEW_USER_ID);
    }

    // ==================================================================
    // I. Field-contract parity (userType accepts [A U a u])
    // ==================================================================

    /**
     * Valid {@code userType} inputs and the single character the controller must
     * forward to the service. The BMS field is {@code USRTYPEI PIC X(1)} and the
     * DTO accepts {@code [AUau ]} (case-insensitive admin/user), matching the
     * {@code COCOM01Y} 88-level role condition names.
     *
     * @return a stream of (submitted userType, expected forwarded char) pairs
     */
    static Stream<Arguments> acceptedUserTypeCases() {
        return Stream.of(
                Arguments.of("A", 'A'),
                Arguments.of("U", 'U'),
                Arguments.of("a", 'a'),
                Arguments.of("u", 'u'));
    }

    @ParameterizedTest(name = "[{index}] userType={0} -> {1}")
    @MethodSource("acceptedUserTypeCases")
    @WithMockUser(roles = "ADMIN")
    @DisplayName("I: userType accepts A/U (case-insensitive) and forwards the first character to the service")
    void enterAcceptsValidUserTypes(String userType, char expectedChar) throws Exception {
        Map<String, Object> body = validEnterBody();
        body.put("userType", userType);
        when(userService.addUser(any(), any(), any(), any(), anyChar()))
                .thenReturn(new UserService.UserResult(true, null, ADD_SUCCESS_MESSAGE));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(ADD_SUCCESS_MESSAGE));

        verify(userService).addUser(
                eq(NEW_USER_ID), eq(FIRST_NAME), eq(LAST_NAME), eq(RAW_PASSWORD), eq(expectedChar));
    }
}

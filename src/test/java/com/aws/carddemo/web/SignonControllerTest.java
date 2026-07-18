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

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.SignonRequest;
import com.aws.carddemo.dto.SignonResponse;
import com.aws.carddemo.mapper.SignonMapper;
import com.aws.carddemo.service.SignonService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.constraints.Size;

/**
 * {@code @WebMvcTest} slice tests for {@link SignonController} &mdash; the REST re-expression of the
 * online COBOL program {@code COSGN00C} (CICS transaction {@code CC00}, BMS mapset/map
 * {@code COSGN00}, legacy source retained at {@code legacy/cbl/COSGN00C.cbl}).
 *
 * <p>There is no 3270 emulator in the migrated system: the sign-on screen is a REST
 * request/response DTO pair, so these tests lock down the <em>observable contracts</em> that define
 * behavioral parity (AAP &sect;0.7 hotspots H1/H2, &sect;0.9.2) rather than any rendered terminal:</p>
 * <ul>
 *   <li><strong>Pseudo-conversational translation (H1).</strong> The COBOL first-entry
 *       {@code EIBCALEN = 0} {@code SEND} becomes {@code GET /api/v1/auth/signon} (blank screen);
 *       the re-entry {@code RECEIVE MAP} + {@code EVALUATE EIBAID} becomes
 *       {@code POST /api/v1/auth/signon} routed by the {@link SignonRequest#action() action}.</li>
 *   <li><strong>{@code EVALUATE EIBAID} routing.</strong> {@code WHEN DFHENTER} delegates to
 *       {@link SignonService}; {@code WHEN DFHPF3} exits with the thank-you message; {@code WHEN
 *       OTHER} re-displays with the invalid-key message and never calls the service.</li>
 *   <li><strong>{@code EXEC CICS XCTL} navigation.</strong> A successful sign-on surfaces the next
 *       program ({@code COADM01C}/{@code COMEN01C}) and its derived transaction id
 *       ({@code CA00}/{@code CM00}) as the {@value #HEADER_NEXT_PROGRAM} and
 *       {@value #HEADER_NEXT_TRANSACTION} response headers.</li>
 *   <li><strong>Confidentiality (AAP &sect;0.9.3).</strong> The submitted password is never echoed:
 *       {@link SignonResponse} has no password field and no response body ever contains the secret
 *       value (Case&nbsp;G).</li>
 *   <li><strong>BMS field-contract parity (H2).</strong> The response preserves the
 *       {@code COSGN0AO} field set and the request honors the {@code PIC X(8)} user-id length and
 *       the write-only password contract (Case&nbsp;H).</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The slice loads only the web tier: the {@link SignonController} under test, the real
 * {@link SecurityConfig} (so the {@code permitAll} rule for {@code /api/v1/auth/**} and the disabled
 * CSRF filter are exercised exactly as in production) and the real {@link SignonMapper} (so the
 * outbound {@code COSGN0AO} assembly is genuinely covered). The {@link SignonService} is mocked, so
 * these tests assert the controller's own routing/translation logic in isolation from credential
 * validation and the database. Boot's {@link UserDetailsServiceAutoConfiguration} is excluded so the
 * slice needs no user store and emits no development-only default-user password; because
 * {@code /api/v1/auth/**} is {@code permitAll}, no authentication ever runs. The
 * {@code GlobalExceptionHandler} {@code @RestControllerAdvice} is auto-detected by the slice and
 * therefore intentionally not imported here.</p>
 *
 * <p>Because {@code SecurityConfig} disables CSRF and permits {@code /api/v1/auth/**}, the POSTs
 * need neither a CSRF token nor an authenticated user. Request bodies are built as JSON maps (not
 * serialized {@link SignonRequest} instances) so the write-only {@code password} field is actually
 * transmitted; serializing a {@code SignonRequest} would drop it.</p>
 */
@WebMvcTest(value = SignonController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, SignonMapper.class})
@DisplayName("SignonController (COSGN00C / CC00) @WebMvcTest slice")
class SignonControllerTest {

    /** The single sign-on endpoint path (both GET and POST are mapped here). */
    private static final String SIGNON_PATH = "/api/v1/auth/signon";

    /** Response header carrying the resolved next program on a successful sign-on (XCTL stand-in). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header carrying the CICS transaction id of the resolved next screen. */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * An obviously-fake, non-secret placeholder used as the submitted password across the POST
     * tests. Its only purpose is to be a distinctive token that must never appear in any response
     * body (Case&nbsp;G); it is not a real credential.
     */
    private static final String SECRET_PASSWORD = "Sup3rS3c";

    /** The exact wrong-password message the legacy program surfaced ({@code COSGN00C} L242). */
    private static final String MSG_WRONG_PASSWORD = SignonService.MSG_WRONG_PASSWORD;

    /** The exact blank-user-id message the legacy program surfaced ({@code COSGN00C} L120). */
    private static final String MSG_ENTER_USER_ID = SignonService.MSG_ENTER_USER_ID;

    /** The PF3 exit message the controller emits (COBOL {@code CCDA-MSG-THANK-YOU}). */
    private static final String MSG_THANK_YOU = "Thank you for using CardDemo application...";

    /** The invalid-key message the controller emits (COBOL {@code CCDA-MSG-INVALID-KEY}). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** MockMvc entry point, auto-configured by the {@code @WebMvcTest} slice. */
    @Autowired
    private MockMvc mockMvc;

    /** The application {@link ObjectMapper} provided by the slice (used to build request bodies). */
    @Autowired
    private ObjectMapper objectMapper;

    /** Mocked sign-on business service; the controller's only business collaborator. */
    @MockitoBean
    private SignonService signonService;

    /**
     * Serializes a set of screen input fields to a JSON request body. Building the body from a raw
     * map (rather than a {@link SignonRequest} instance) is deliberate: {@code SignonRequest}'s
     * {@code password} is annotated {@link JsonProperty.Access#WRITE_ONLY}, so serializing an
     * instance would omit the password and the controller would receive {@code null}.
     *
     * @param fields the request fields (a subset of {@code userId}, {@code password}, {@code action})
     * @return the JSON representation of {@code fields}
     * @throws Exception if serialization fails
     */
    private String toJson(Map<String, String> fields) throws Exception {
        return objectMapper.writeValueAsString(fields);
    }

    /**
     * Asserts the confidentiality contract on a response: the submitted password value must never
     * appear anywhere in the body, and the body must expose no {@code password} JSON property.
     *
     * @param result the completed MVC result whose response body is inspected
     * @param secret the submitted password value that must be absent
     * @throws Exception if the response body cannot be read
     */
    private static void assertNoPasswordLeak(MvcResult result, String secret) throws Exception {
        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody)
                .as("response body must never echo the submitted password value")
                .doesNotContain(secret);
        assertThat(responseBody)
                .as("response JSON must not expose a password property")
                .doesNotContain("\"password\"");
    }

    /**
     * Case&nbsp;A &mdash; the blank sign-on screen returned by {@code GET /api/v1/auth/signon},
     * reproducing the COBOL first-entry path ({@code EIBCALEN = 0} clears the map and issues
     * {@code SEND-SIGNON-SCREEN}).
     */
    @Nested
    @DisplayName("GET /api/v1/auth/signon - blank first-entry screen")
    class GetBlankScreen {

        @Test
        @DisplayName("returns HTTP 200 JSON with the populated header, blank user id, no error, and no navigation headers")
        void returnsBlankSignonScreen() throws Exception {
            MvcResult result = mockMvc.perform(get(SIGNON_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    // First entry is not a sign-on outcome: no XCTL navigation headers are emitted.
                    .andExpect(header().doesNotExist(HEADER_NEXT_PROGRAM))
                    .andExpect(header().doesNotExist(HEADER_NEXT_TRANSACTION))
                    // The password map field (COSGN0AO PASSWDO) is deliberately absent from the DTO.
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andReturn();

            // Deserialize back into the DTO so value/length assertions are robust regardless of the
            // NON_NULL serialization inclusion (omitted null fields simply read back as null).
            SignonResponse response =
                    objectMapper.readValue(result.getResponse().getContentAsString(), SignonResponse.class);

            // Header/screen-constant fields are populated (POPULATE-HEADER-INFO parity).
            assertThat(response.transactionName()).isEqualTo("CC00");
            assertThat(response.programName()).isEqualTo("COSGN00C");
            assertThat(response.title01()).hasSize(40).contains("AWS Mainframe Modernization");
            assertThat(response.title02()).hasSize(40).contains("CardDemo");
            // Current date/time rendered through DateUtils (MM/dd/uu and HH:mm:ss masks).
            assertThat(response.currentDate()).matches("\\d{2}/\\d{2}/\\d{2}");
            assertThat(response.currentTime()).matches("\\d{2}:\\d{2}:\\d{2}");
            // Blank screen: no echoed user id, no error line, and the CICS env placeholders are null.
            assertThat(response.userId()).isNull();
            assertThat(response.errorMessage()).isNull();
            assertThat(response.applId()).isNull();
            assertThat(response.sysId()).isNull();

            // The blank screen performs no credential validation.
            verifyNoInteractions(signonService);
        }
    }

    /**
     * Cases&nbsp;B/C &mdash; a successful Enter-key sign-on ({@code WHEN DFHENTER} &rarr;
     * {@code PROCESS-ENTER-KEY} success), which ended the COBOL program with
     * {@code EXEC CICS XCTL} to the Admin Menu ({@code COADM01C}) for an administrator or the Main
     * Menu ({@code COMEN01C}) for a standard user. The Java controller surfaces that destination as
     * the {@value #HEADER_NEXT_PROGRAM} / {@value #HEADER_NEXT_TRANSACTION} response headers.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/signon - ENTER, successful sign-on")
    class SuccessfulSignon {

        @Test
        @DisplayName("ADMIN user navigates to COADM01C / CA00 and the entered credentials are passed to the service")
        void adminSignonEmitsAdminNavigationHeaders() throws Exception {
            when(signonService.signon("ADMIN001", SECRET_PASSWORD))
                    .thenReturn(new SignonService.SignonResult(true, "ADMIN001", 'A', "COADM01C", null));

            MvcResult result = mockMvc.perform(post(SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of(
                                    "userId", "ADMIN001",
                                    "password", SECRET_PASSWORD,
                                    "action", PfKeyAction.ENTER.name()))))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    // XCTL PROGRAM('COADM01C') -> CA00 (COSGN00C L231-L233).
                    .andExpect(header().string(HEADER_NEXT_PROGRAM, "COADM01C"))
                    .andExpect(header().string(HEADER_NEXT_TRANSACTION, "CA00"))
                    // Success echoes the (upper-cased) user id from the service result, no error line.
                    .andExpect(jsonPath("$.userId").value("ADMIN001"))
                    .andExpect(jsonPath("$.errorMessage").doesNotExist())
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andReturn();

            // The entered id and password are passed through verbatim to the service (ArgumentCaptor).
            ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
            verify(signonService).signon(userIdCaptor.capture(), passwordCaptor.capture());
            assertThat(userIdCaptor.getValue()).isEqualTo("ADMIN001");
            assertThat(passwordCaptor.getValue()).isEqualTo(SECRET_PASSWORD);

            assertNoPasswordLeak(result, SECRET_PASSWORD);
        }

        @Test
        @DisplayName("standard USER navigates to COMEN01C / CM00")
        void userSignonEmitsMainMenuNavigationHeaders() throws Exception {
            when(signonService.signon("USER0001", SECRET_PASSWORD))
                    .thenReturn(new SignonService.SignonResult(true, "USER0001", 'U', "COMEN01C", null));

            MvcResult result = mockMvc.perform(post(SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of(
                                    "userId", "USER0001",
                                    "password", SECRET_PASSWORD,
                                    "action", PfKeyAction.ENTER.name()))))
                    .andExpect(status().isOk())
                    // XCTL PROGRAM('COMEN01C') -> CM00 (COSGN00C L236-L238).
                    .andExpect(header().string(HEADER_NEXT_PROGRAM, "COMEN01C"))
                    .andExpect(header().string(HEADER_NEXT_TRANSACTION, "CM00"))
                    .andExpect(jsonPath("$.userId").value("USER0001"))
                    .andExpect(jsonPath("$.errorMessage").doesNotExist())
                    .andReturn();

            verify(signonService).signon("USER0001", SECRET_PASSWORD);
            assertNoPasswordLeak(result, SECRET_PASSWORD);
        }
    }

    /**
     * Case&nbsp;D &mdash; a rejected Enter-key sign-on. The legacy program re-sent the sign-on map
     * with the failure message on the status line ({@code SEND-SIGNON-SCREEN}); this is a
     * same-screen re-display, <strong>not</strong> a hard error. The controller therefore returns
     * HTTP&nbsp;200 (never a 4xx), with the service's exact message and no navigation header. The
     * empty-field variant additionally proves the request body is intentionally not
     * {@code @Valid}-annotated: a blank user id/password is a business message, not a 400.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/signon - ENTER, rejected sign-on (same-screen 200)")
    class RejectedSignon {

        @Test
        @DisplayName("wrong password re-displays the screen (HTTP 200) with the message and no navigation headers")
        void wrongPasswordReDisplaysScreen() throws Exception {
            when(signonService.signon("USER0001", SECRET_PASSWORD))
                    .thenReturn(new SignonService.SignonResult(false, "USER0001", ' ', null, MSG_WRONG_PASSWORD));

            MvcResult result = mockMvc.perform(post(SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of(
                                    "userId", "USER0001",
                                    "password", SECRET_PASSWORD,
                                    "action", PfKeyAction.ENTER.name()))))
                    // Same-screen re-display, NOT a 4xx.
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errorMessage").value(MSG_WRONG_PASSWORD))
                    // The entered user id is echoed back so the operator can correct the password.
                    .andExpect(jsonPath("$.userId").value("USER0001"))
                    // A failed sign-on performs no XCTL, so no navigation headers are emitted.
                    .andExpect(header().doesNotExist(HEADER_NEXT_PROGRAM))
                    .andExpect(header().doesNotExist(HEADER_NEXT_TRANSACTION))
                    .andReturn();

            verify(signonService).signon("USER0001", SECRET_PASSWORD);
            assertNoPasswordLeak(result, SECRET_PASSWORD);
        }

        @Test
        @DisplayName("empty user id/password returns HTTP 200 with a business message (NOT 400) - the request is not @Valid")
        void emptyFieldsAreBusinessMessagesNotValidationErrors() throws Exception {
            // The controller does not @Valid the body; the empty-field checks live in the service
            // (COSGN00C PROCESS-ENTER-KEY), so the service IS reached and returns a same-screen message.
            when(signonService.signon("", ""))
                    .thenReturn(new SignonService.SignonResult(false, "", ' ', null, MSG_ENTER_USER_ID));

            MvcResult result = mockMvc.perform(post(SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of(
                                    "userId", "",
                                    "password", "",
                                    "action", PfKeyAction.ENTER.name()))))
                    // NOT Bad Request: a blank field is a business outcome, rendered HTTP 200.
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errorMessage").value(MSG_ENTER_USER_ID))
                    .andExpect(header().doesNotExist(HEADER_NEXT_PROGRAM))
                    .andReturn();

            // The service being invoked proves the request was not short-circuited by bean validation.
            verify(signonService).signon("", "");
            assertNoPasswordLeak(result, SECRET_PASSWORD);
        }
    }

    /**
     * Cases&nbsp;E/F &mdash; the non-Enter attention keys of {@code EVALUATE EIBAID}. PF3
     * ({@code WHEN DFHPF3}) reproduces the {@code SEND-PLAIN-TEXT} exit with the thank-you message;
     * any other key ({@code WHEN OTHER}) re-displays the screen with the invalid-key message. In
     * both branches the credential service is never called and no navigation header is emitted.
     */
    @Nested
    @DisplayName("POST /api/v1/auth/signon - non-Enter attention keys")
    class AttentionKeyRouting {

        @Test
        @DisplayName("PF3 exits with the thank-you message, no navigation, and the service is never called")
        void pf3ExitsWithThankYouMessage() throws Exception {
            MvcResult result = mockMvc.perform(post(SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("action", PfKeyAction.PF3.name()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errorMessage").value(MSG_THANK_YOU))
                    // The exit path erases the screen: no echoed user id.
                    .andExpect(jsonPath("$.userId").doesNotExist())
                    .andExpect(header().doesNotExist(HEADER_NEXT_PROGRAM))
                    .andExpect(header().doesNotExist(HEADER_NEXT_TRANSACTION))
                    .andReturn();

            // PF3 must not trigger credential validation.
            verifyNoInteractions(signonService);
            assertNoPasswordLeak(result, SECRET_PASSWORD);
        }

        @Test
        @DisplayName("an unmapped key (PF7) re-displays the screen with the invalid-key message and no navigation")
        void unmappedKeyReportsInvalidKey() throws Exception {
            MvcResult result = mockMvc.perform(post(SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of(
                                    "userId", "USER0001",
                                    "password", SECRET_PASSWORD,
                                    "action", PfKeyAction.PF7.name()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_KEY))
                    // WHEN OTHER echoes the entered user id back onto the re-displayed screen.
                    .andExpect(jsonPath("$.userId").value("USER0001"))
                    .andExpect(header().doesNotExist(HEADER_NEXT_PROGRAM))
                    .andExpect(header().doesNotExist(HEADER_NEXT_TRANSACTION))
                    .andReturn();

            // The credential service is deliberately not called on an invalid key.
            verifyNoInteractions(signonService);
            // Even though a password was submitted, it must never be reflected back.
            assertNoPasswordLeak(result, SECRET_PASSWORD);
        }
    }

    /**
     * Case&nbsp;G &mdash; confidentiality (AAP &sect;0.9.3). The submitted password is a write-only
     * credential: it must never be reflected back to the client. This verifies both the runtime
     * body (no secret value, no {@code password} JSON property) and the compile-time contract
     * (the {@link SignonResponse} record has no {@code password} component).
     */
    @Nested
    @DisplayName("Confidentiality - the password is never echoed")
    class Confidentiality {

        @Test
        @DisplayName("no response body ever contains the submitted password value or a password property")
        void passwordIsNeverReflected() throws Exception {
            String distinctiveSecret = "Zx9Qw7Rt";
            when(signonService.signon("USER0001", distinctiveSecret))
                    .thenReturn(new SignonService.SignonResult(true, "USER0001", 'U', "COMEN01C", null));

            MvcResult result = mockMvc.perform(post(SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of(
                                    "userId", "USER0001",
                                    "password", distinctiveSecret,
                                    "action", PfKeyAction.ENTER.name()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andReturn();

            assertNoPasswordLeak(result, distinctiveSecret);
        }

        @Test
        @DisplayName("the SignonResponse contract has no password field (compile-time + reflection)")
        void responseHasNoPasswordComponent() {
            List<String> responseComponents = Arrays.stream(SignonResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            assertThat(responseComponents)
                    .as("the sign-on response must never carry a password (COSGN0AO PASSWDO is deliberately dropped)")
                    .doesNotContain("password");
        }
    }

    /**
     * Case&nbsp;H &mdash; BMS field-contract parity (AAP &sect;0.7 hotspot H2, &sect;0.9.2). The
     * response DTO preserves the {@code COSGN0AO} output-field set (names/order), the request DTO
     * honors the {@code USERIDI PIC X(8)} maximum length and keeps the password write-only, and an
     * out-of-contract (9-character) user id is handled without a crash (the controller applies no
     * bean validation, so it echoes the value rather than rejecting it).
     */
    @Nested
    @DisplayName("BMS field-contract parity (COSGN00)")
    class FieldContractParity {

        @Test
        @DisplayName("SignonResponse exposes exactly the COSGN0AO output fields, in order")
        void responseExposesCosgn0aoFields() {
            List<String> responseComponents = Arrays.stream(SignonResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            assertThat(responseComponents).containsExactly(
                    "transactionName",
                    "title01",
                    "currentDate",
                    "programName",
                    "title02",
                    "currentTime",
                    "applId",
                    "sysId",
                    "userId",
                    "errorMessage");
        }

        @Test
        @DisplayName("SignonRequest honors USERID PIC X(8) (max 8) and keeps the password write-only")
        void requestHonorsFieldLengthsAndWriteOnlyPassword() throws Exception {
            Size userIdSize = SignonRequest.class.getDeclaredField("userId").getAnnotation(Size.class);
            assertThat(userIdSize)
                    .as("userId must carry the BMS PIC X(8) length constraint")
                    .isNotNull();
            assertThat(userIdSize.max()).isEqualTo(8);

            JsonProperty passwordJson =
                    SignonRequest.class.getDeclaredField("password").getAnnotation(JsonProperty.class);
            assertThat(passwordJson)
                    .as("password must be annotated @JsonProperty for write-only access")
                    .isNotNull();
            assertThat(passwordJson.access()).isEqualTo(JsonProperty.Access.WRITE_ONLY);
        }

        @Test
        @DisplayName("an out-of-contract 9-character user id is echoed, not rejected (no @Valid, no crash)")
        void nineCharacterUserIdIsEchoedNotRejected() throws Exception {
            String nineCharId = "USER00012";
            when(signonService.signon(nineCharId, SECRET_PASSWORD))
                    .thenReturn(new SignonService.SignonResult(false, nineCharId, ' ', null, MSG_WRONG_PASSWORD));

            mockMvc.perform(post(SIGNON_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of(
                                    "userId", nineCharId,
                                    "password", SECRET_PASSWORD,
                                    "action", PfKeyAction.ENTER.name()))))
                    // Not a 400: the controller does not bean-validate the body, so the over-length
                    // id is accepted and echoed on the re-displayed screen rather than crashing.
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(nineCharId))
                    .andExpect(jsonPath("$.errorMessage").value(MSG_WRONG_PASSWORD));

            verify(signonService).signon(nineCharId, SECRET_PASSWORD);
        }
    }

}

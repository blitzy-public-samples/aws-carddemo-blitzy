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

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.UserMapper;
import com.aws.carddemo.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest @WebMvcTest}
 * slice test for {@link UserUpdateController} &mdash; the Java re-platform of the
 * legacy CICS online program {@code COUSR02C} (transaction {@code CU02},
 * <em>Update User</em>, BMS map {@code COUSR02}).
 *
 * <p><strong>What this test proves.</strong> It exercises the REST contract of the
 * Update User screen through the full Spring MVC + Spring Security filter chain
 * (no servlet container), verifying the observable behaviour the migration must
 * preserve without any feature expansion:</p>
 * <ul>
 *   <li><em>Authorization</em> &mdash; the screen is ADMIN-only and double-guarded
 *       (URL rule {@code /api/v1/admin/**} plus the controller's
 *       {@code @PreAuthorize("hasRole('ADMIN')")}): unauthenticated &rarr; 401,
 *       authenticated non-admin &rarr; 403 (with the service never invoked),
 *       admin &rarr; 200.</li>
 *   <li><em>ENTER fetch</em> &mdash; a keyed read that echoes the editable fields,
 *       or raises {@link RecordNotFoundException} ("User ID NOT found...") mapped to
 *       HTTP 404 (reproducing {@code COUSR02C READ-USER-SEC-FILE}
 *       {@code WHEN DFHRESP(NOTFND)}).</li>
 *   <li><em>PF5 save</em> &mdash; delegates the edit/compare/persist decision to
 *       {@link UserService#updateUser}; a not-found id on save is 404, and an
 *       optimistic-lock conflict (should the service surface one) is 409.</li>
 *   <li><em>PF-key semantics</em> &mdash; PF4 clears, PF3/PF12 navigate back to the
 *       Admin Menu ({@code COADM01C}/{@code CA00}) via response headers, and any
 *       other key re-displays the invalid-key message.</li>
 *   <li><em>Password discipline</em> &mdash; the request password is
 *       <strong>write-only</strong> and is never echoed on any response
 *       (fetch, save, or error); the {@link UserUpdateResponse} has no password
 *       field at all (AAP &sect;0.9.3, hotspot L1).</li>
 * </ul>
 *
 * <p><strong>Design note (fidelity to the real controller).</strong> The
 * {@code UserUpdateController} save path calls
 * {@link UserService#updateUser(String, String, String, String, char)} with the
 * individual request fields and receives a {@link UserService.UserResult}; it does
 * <em>not</em> build a {@code UserSecurity} entity itself, and it does not invoke
 * {@code UserMapper.updateEntity(...)}. The "a blank password leaves the stored
 * credential unchanged" rule therefore lives inside {@code UserService} (and inside
 * {@code UserMapper.updateEntity}, which this controller does not use) and is
 * covered by their own unit tests. At this web-slice level &mdash; where the service
 * is a mock &mdash; the controller's own obligation is verified instead: it forwards
 * {@code request.password()} to the service <em>verbatim</em> (raw, unmodified, not
 * substituted or dropped) so the service can apply that rule, and it never copies a
 * password onto a response. Test cases {@code pf5Save_*} assert exactly that using
 * an {@link ArgumentCaptor} over the forwarded arguments.</p>
 *
 * <p>The harness follows the mandated shape: {@code @WebMvcTest} of the single
 * controller, importing the real {@link SecurityConfig} (so the true authorization
 * rules and RFC&nbsp;7807 error contract apply) and the real {@link UserMapper} (so
 * the DTO projection is exercised, not mocked), with the {@link UserService}
 * collaborator supplied as a Mockito mock via {@code @MockitoBean}. The Spring
 * Security test support auto-applies {@code springSecurity()} to {@link MockMvc},
 * so {@code @WithMockUser} establishes the caller's role; CSRF is disabled by
 * {@code SecurityConfig}, so POSTs need no token.</p>
 */
@WebMvcTest(UserUpdateController.class)
@Import({SecurityConfig.class, UserMapper.class})
@DisplayName("UserUpdateController (COUSR02C / CU02) web slice")
class UserUpdateControllerTest {

    /** Endpoint under test &mdash; the Update User screen (CICS transaction CU02). */
    private static final String URL = "/api/v1/admin/users/update";

    /** Success message emitted by the fetch (ENTER) path; mirrors {@code COUSR02C}. */
    private static final String MSG_PRESS_PF5 = "Press PF5 key to save your updates ...";

    /** Not-found message raised by an ENTER fetch or a PF5 save with an unknown id. */
    private static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * Verbatim COBOL edit message emitted by {@code COUSR02C} {@code PROCESS-ENTER-KEY}
     * when the operator presses ENTER with a blank user id (legacy
     * {@code WHEN USRIDINI = SPACES OR LOW-VALUES}). Asserted by the F-P4-G
     * blank-id-before-lookup guard test.
     */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Invalid-key message shown for any attention key the controller does not map. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Response header carrying the CICS {@code XCTL} program target on PF3/PF12. */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header carrying the CICS {@code XCTL} transaction target on PF3/PF12. */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** Request/response header carrying the observed {@code user_security} version (F-P7-STALE). */
    private static final String HEADER_USER_VERSION = "X-CardDemo-User-Version";

    /** Admin Menu program navigated to on cancel/back (PF3/PF12) &mdash; {@code COADM01C}. */
    private static final String BACK_TARGET_PROGRAM = "COADM01C";

    /** Admin Menu transaction navigated to on cancel/back (PF3/PF12) &mdash; {@code CA00}. */
    private static final String BACK_TARGET_TRANSACTION = "CA00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    // ------------------------------------------------------------------
    // Test fixtures / helpers
    // ------------------------------------------------------------------

    /**
     * Builds a stub {@link UserSecurity} entity for stubbing the mocked service.
     * The password argument is deliberately distinctive so tests can assert it is
     * never echoed on any response.
     */
    private static UserSecurity stubUser(String id, String firstName, String lastName,
                                         String password, String type) {
        return new UserSecurity(id, firstName, lastName, password, type);
    }

    /**
     * Serializes an Update User request body from the supplied fields. Fields whose
     * value is {@code null} are omitted from the JSON entirely (so an omitted
     * {@code action} exercises the controller's ENTER default). The body is built
     * with an {@link ObjectNode} rather than by serializing a
     * {@code UserUpdateRequest}, because that DTO's {@code password} is
     * {@code @JsonProperty(access = WRITE_ONLY)} and would be dropped on
     * serialization &mdash; the tests must be able to <em>send</em> a password.
     */
    private String body(String userId, String firstName, String lastName,
                        String password, String userType, PfKeyAction action) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        if (userId != null) {
            node.put("userId", userId);
        }
        if (firstName != null) {
            node.put("firstName", firstName);
        }
        if (lastName != null) {
            node.put("lastName", lastName);
        }
        if (password != null) {
            node.put("password", password);
        }
        if (userType != null) {
            node.put("userType", userType);
        }
        if (action != null) {
            node.put("action", action.name());
        }
        return objectMapper.writeValueAsString(node);
    }

    // ==================================================================
    // A. AUTHORIZATION MATRIX
    // ==================================================================

    @Test
    @DisplayName("A1: unauthenticated POST is rejected with 401 and never reaches the service")
    void unauthenticatedPostIsUnauthorized() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", "JOHN", "DOE", "PW1", "U", PfKeyAction.ENTER)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("A2: authenticated non-admin (USER) POST is forbidden with 403 and never reaches the service")
    @WithMockUser(roles = "USER")
    void userRolePostIsForbidden() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", "JOHN", "DOE", "PW1", "U", PfKeyAction.PF5)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("A2b: authenticated non-admin (USER) GET is forbidden with 403")
    @WithMockUser(roles = "USER")
    void userRoleGetIsForbidden() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("A3: ADMIN GET renders the blank Update User screen (200) with no password")
    @WithMockUser(roles = "ADMIN")
    void adminGetRendersBlankScreen() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value("CU02"))
                .andExpect(jsonPath("$.programName").value("COUSR02C"))
                // Blank first-entry screen: no user detail yet (null fields are omitted).
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.firstName").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                // The response contract carries no password field, ever.
                .andExpect(jsonPath("$.password").doesNotExist());

        verifyNoInteractions(userService);
    }

    // ==================================================================
    // B. ENTER fetch (found)
    // ==================================================================

    @Test
    @DisplayName("B1: ENTER fetches an existing user and echoes the editable fields (no password)")
    @WithMockUser(roles = "ADMIN")
    void enterFetchFoundEchoesEditableFields() throws Exception {
        UserSecurity existing = stubUser("USER0002", "JOHN", "DOE", "EXISTPW1", "U");
        Page<UserSecurity> page = new PageImpl<>(List.of(existing));
        when(userService.listUsers(any(), any())).thenReturn(page);

        String responseBody = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", null, null, null, null, PfKeyAction.ENTER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value("CU02"))
                .andExpect(jsonPath("$.programName").value("COUSR02C"))
                .andExpect(jsonPath("$.userId").value("USER0002"))
                .andExpect(jsonPath("$.firstName").value("JOHN"))
                .andExpect(jsonPath("$.lastName").value("DOE"))
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.errorMessage").value(MSG_PRESS_PF5))
                // The fetched record's password must never be projected onto the response.
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain("EXISTPW1");
    }

    @Test
    @DisplayName("B2: a null/omitted action defaults to ENTER (fetch), reproducing COUSR02C's DFHENTER default")
    @WithMockUser(roles = "ADMIN")
    void omittedActionDefaultsToEnterFetch() throws Exception {
        UserSecurity existing = stubUser("USER0002", "JANE", "ROE", "EXISTPW1", "A");
        when(userService.listUsers(any(), any())).thenReturn(new PageImpl<>(List.of(existing)));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        // action deliberately omitted -> controller treats it as ENTER.
                        .content(body("USER0002", null, null, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0002"))
                .andExpect(jsonPath("$.firstName").value("JANE"))
                .andExpect(jsonPath("$.errorMessage").value(MSG_PRESS_PF5))
                .andExpect(jsonPath("$.password").doesNotExist());

        // The save path must not have been taken for a fetch.
        verify(userService, never()).updateUser(any(), any(), any(), any(), anyChar(), any());
    }

    @Test
    @DisplayName("B2b (F-P7-STALE): ENTER fetch emits the observed X-CardDemo-User-Version header for the save to carry back")
    @WithMockUser(roles = "ADMIN")
    void fetchEmitsObservedUserVersionHeader() throws Exception {
        // F-P7-STALE: the edit screen must echo the user_security @Version it read, so the client can
        // return it on PF5/PF3 and the stale-form guard can verify it. The fetch response therefore
        // carries the observed version in the X-CardDemo-User-Version header.
        UserSecurity existing = stubUser("USER0002", "JANE", "ROE", "EXISTPW1", "A");
        existing.setVersion(7L);
        when(userService.listUsers(any(), any())).thenReturn(new PageImpl<>(List.of(existing)));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", null, null, null, null, PfKeyAction.ENTER)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_USER_VERSION, "7"))
                .andExpect(jsonPath("$.userId").value("USER0002"))
                .andExpect(jsonPath("$.errorMessage").value(MSG_PRESS_PF5))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("B3 (F-P4-G): ENTER with a blank user id is a same-screen edit (200) before any lookup")
    @WithMockUser(roles = "ADMIN")
    void enterBlankUserIdIsSameScreenEditBeforeLookup() throws Exception {
        // A blank (spaces) user id reproduces the legacy COUSR02C guard
        // WHEN USRIDINI = SPACES OR LOW-VALUES: the screen is redisplayed with
        // "User ID can NOT be empty..." and the record browse is never attempted.
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", null, null, null, null, PfKeyAction.ENTER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value("CU02"))
                .andExpect(jsonPath("$.programName").value("COUSR02C"))
                .andExpect(jsonPath("$.errorMessage").value(MSG_USER_ID_EMPTY))
                // No user detail is echoed and the response never carries a password.
                .andExpect(jsonPath("$.firstName").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        // The blank id short-circuits BEFORE the browse: the service is never consulted.
        verify(userService, never()).listUsers(any(), any());
    }

    // ==================================================================
    // C. ENTER fetch (not found) -> 404 problem+json
    // ==================================================================

    @Test
    @DisplayName("C1: ENTER for an unknown id returns 404 Record Not Found (empty browse result)")
    @WithMockUser(roles = "ADMIN")
    void enterFetchNotFoundEmptyResultIsNotFound() throws Exception {
        // No candidate records -> controller raises RecordNotFoundException.
        when(userService.listUsers(any(), any())).thenReturn(new PageImpl<>(List.<UserSecurity>of()));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOSUCH01", null, null, null, null, PfKeyAction.ENTER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.detail").value(MSG_USER_ID_NOT_FOUND));
    }

    @Test
    @DisplayName("C2: ENTER returns 404 when the browse yields only a non-matching id (exact-match required)")
    @WithMockUser(roles = "ADMIN")
    void enterFetchNotFoundNonMatchingIdIsNotFound() throws Exception {
        // The browse positions at the first id >= the key, which may not be an exact
        // match; the controller must reject a non-exact hit as not found.
        UserSecurity other = stubUser("USER0009", "SOME", "ONE", "EXISTPW1", "U");
        when(userService.listUsers(any(), any())).thenReturn(new PageImpl<>(List.of(other)));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", null, null, null, null, PfKeyAction.ENTER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.detail").value(MSG_USER_ID_NOT_FOUND));
    }


    // ==================================================================
    // D / E. PF5 save (delegates to UserService.updateUser)
    // ==================================================================

    @Test
    @DisplayName("D: PF5 saves the edited user, forwarding the RAW password verbatim and returning the success message")
    @WithMockUser(roles = "ADMIN")
    void pf5SaveForwardsRawPasswordAndReturnsSuccess() throws Exception {
        UserSecurity saved = stubUser("USER0002", "JOHNNY", "DOE", "EXISTPW1", "A");
        when(userService.updateUser(any(), any(), any(), any(), anyChar(), any()))
                .thenReturn(new UserService.UserResult(true, saved, "User USER0002 has been updated ..."));

        String responseBody = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        // userType 'a' proves the controller upper-cases it to the 'A' role code.
                        .content(body("USER0002", "JOHNNY", "DOE", "S3CRET1", "a", PfKeyAction.PF5)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value("USER0002"))
                .andExpect(jsonPath("$.errorMessage").value("User USER0002 has been updated ..."))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // The raw request password must never be echoed back on the response body.
        assertThat(responseBody).doesNotContain("S3CRET1");

        // Capture the exact password the controller forwarded to the service while
        // pinning the remaining arguments with value matchers. A single verify() call
        // must use either raw values or matchers throughout (not a mix), so the
        // id/first-name/last-name/type assertions ride on eq(...) matchers (any
        // mismatch fails the verification) and the security-critical password rides on
        // an ArgumentCaptor. The char argument uses eq('A') rather than a captor
        // because capturing a primitive via ArgumentCaptor<Character> would unbox a
        // null and throw.
        ArgumentCaptor<String> passwordCap = ArgumentCaptor.forClass(String.class);
        verify(userService).updateUser(eq("USER0002"), eq("JOHNNY"), eq("DOE"),
                passwordCap.capture(), eq('A'), any());

        // The RAW request password was forwarded UNCHANGED (the service alone is
        // responsible for hashing); the lower-case 'a' user type was folded to the
        // canonical role code 'A', asserted by the eq('A') matcher above.
        assertThat(passwordCap.getValue()).isEqualTo("S3CRET1");
    }

    @Test
    @DisplayName("E: PF5 with a BLANK password forwards it verbatim (preservation is the service's job) and echoes no password")
    @WithMockUser(roles = "ADMIN")
    void pf5SaveBlankPasswordForwardedVerbatimAndNeverEchoed() throws Exception {
        // The mocked service returns the existing record (carrying its stored password)
        // so we can also prove that stored credential is never leaked onto the response.
        UserSecurity existing = stubUser("USER0002", "JOHNNY", "DOE", "EXISTPW1", "U");
        when(userService.updateUser(any(), any(), any(), any(), anyChar(), any()))
                .thenReturn(new UserService.UserResult(true, existing, "User USER0002 has been updated ..."));

        String responseBody = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        // Blank password on the wire.
                        .content(body("USER0002", "JOHNNY", "DOE", "", "U", PfKeyAction.PF5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0002"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // Neither the stored password nor any password field appears on the response.
        assertThat(responseBody).doesNotContain("EXISTPW1");

        // Capture the forwarded password; pin the id/name/type with eq(...) matchers
        // (see the note in the previous test on why the char uses eq('U'), not a
        // captor).
        ArgumentCaptor<String> passwordCap = ArgumentCaptor.forClass(String.class);
        verify(userService).updateUser(eq("USER0002"), eq("JOHNNY"), eq("DOE"),
                passwordCap.capture(), eq('U'), any());

        // The controller forwarded the blank password UNCHANGED - it did not fabricate,
        // substitute, or drop it; UserService/UserMapper own the "leave existing" rule.
        // The id/first-name/last-name/type ride on the eq(...) matchers above (a
        // mismatch would fail the verification), so the entire forwarded tuple is
        // pinned.
        assertThat(passwordCap.getValue()).isEmpty();
    }

    @Test
    @DisplayName("E2: PF5 with an omitted user type forwards a space (COBOL SPACES empty-type parity)")
    @WithMockUser(roles = "ADMIN")
    void pf5SaveOmittedUserTypeForwardsSpace() throws Exception {
        // The service is mocked; here we only prove the controller's own mapping of an
        // absent PIC X(1) user type onto a COBOL space. The real service would reject
        // that space as the "User Type can NOT be empty..." same-screen edit, but that
        // rule lives in (and is unit-tested by) UserService, not the controller.
        UserSecurity saved = stubUser("USER0002", "JOHNNY", "DOE", "EXISTPW1", "U");
        when(userService.updateUser(any(), any(), any(), any(), anyChar(), any()))
                .thenReturn(new UserService.UserResult(true, saved, "User USER0002 has been updated ..."));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        // userType deliberately omitted -> the controller maps it to ' '.
                        .content(body("USER0002", "JOHNNY", "DOE", "S3CRET1", null, PfKeyAction.PF5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist());

        // The absent user type is forwarded verbatim as a single space (the COBOL
        // SPACES empty-type value), reproducing COUSR02C's blank USRTYPEI handling.
        verify(userService).updateUser(eq("USER0002"), eq("JOHNNY"), eq("DOE"),
                eq("S3CRET1"), eq(' '), any());
    }


    // ==================================================================
    // F. PF5 save error mapping (not-found -> 404; optimistic lock -> 409)
    // ==================================================================

    @Test
    @DisplayName("F1: PF5 save of an unknown id surfaces RecordNotFoundException as 404")
    @WithMockUser(roles = "ADMIN")
    void pf5SaveNotFoundIsNotFound() throws Exception {
        when(userService.updateUser(any(), any(), any(), any(), anyChar(), any()))
                .thenThrow(new RecordNotFoundException(MSG_USER_ID_NOT_FOUND));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOSUCH01", "JOHN", "DOE", "S3CRET1", "U", PfKeyAction.PF5)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.detail").value(MSG_USER_ID_NOT_FOUND));
    }

    @Test
    @DisplayName("F2: PF5 save under an optimistic-lock conflict maps to 409 (READ-UPDATE-REWRITE parity, hotspot H6)")
    @WithMockUser(roles = "ADMIN")
    void pf5SaveOptimisticLockConflictIsConflict() throws Exception {
        // The @Version REWRITE integrity check can surface a concurrent-update failure
        // from the service's save(); the global handler maps it to 409 CONFLICT.
        when(userService.updateUser(any(), any(), any(), any(), anyChar(), any()))
                .thenThrow(new OptimisticLockingFailureException("row was updated by another transaction"));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", "JOHN", "DOE", "S3CRET1", "U", PfKeyAction.PF5)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Concurrent Update Conflict"));
    }

    // ==================================================================
    // G. PF4 clear
    // ==================================================================

    @Test
    @DisplayName("G: PF4 clears the screen (200, blank) without saving")
    @WithMockUser(roles = "ADMIN")
    void pf4ClearsScreenWithoutSaving() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", "JOHN", "DOE", "S3CRET1", "U", PfKeyAction.PF4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionName").value("CU02"))
                // Cleared screen: detail fields and message are blank (omitted).
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.firstName").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        // PF4 neither reads nor writes: no service interaction at all.
        verifyNoInteractions(userService);
    }

    // ==================================================================
    // H. PF3 save-and-exit (COUSR02C WHEN DFHPF3) / PF12 pure cancel (COADM01C / CA00)
    // ==================================================================

    @Test
    @DisplayName("H1: PF3 SAVES the edit (delegates to updateUser) THEN navigates back to the Admin Menu (F-P4-F)")
    @WithMockUser(roles = "ADMIN")
    void pf3SavesThenNavigatesBackToAdminMenu() throws Exception {
        // F-P4-F: COUSR02C WHEN DFHPF3 performs UPDATE-USER-INFO (validate + rewrite) and THEN
        // RETURN-TO-PREV-SCREEN. PF3 must therefore persist a valid edit before leaving -- the prior
        // behavior (navigate back with verifyNoInteractions) silently dropped the change and is the
        // defect this asserts against. A successful save is followed by the navigate-back headers.
        UserSecurity saved = stubUser("USER0002", "JOHNNY", "DOE", "EXISTPW1", "A");
        when(userService.updateUser(any(), any(), any(), any(), anyChar(), any()))
                .thenReturn(new UserService.UserResult(true, saved, "User USER0002 has been updated ..."));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HEADER_USER_VERSION, "3")
                        .content(body("USER0002", "JOHNNY", "DOE", "S3CRET1", "A", PfKeyAction.PF3)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, BACK_TARGET_PROGRAM))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, BACK_TARGET_TRANSACTION))
                .andExpect(jsonPath("$.password").doesNotExist());

        // The save path WAS taken (unlike the legacy defect) and the observed version was forwarded.
        verify(userService).updateUser(eq("USER0002"), eq("JOHNNY"), eq("DOE"),
                eq("S3CRET1"), eq('A'), eq(3L));
    }

    @Test
    @DisplayName("H1b: PF3 with a failing mandatory-field edit stays same-screen with the message and does NOT navigate away")
    @WithMockUser(roles = "ADMIN")
    void pf3WithEditFailureStaysSameScreen() throws Exception {
        // COUSR02C UPDATE-USER-INFO sets the error flag on a blank mandatory field. In the REST
        // translation an unsuccessful save keeps the operator on the screen with the exact message
        // (mirroring the PF5 same-screen display) rather than navigating away and discarding a still
        // invalid edit. No navigation headers are emitted.
        when(userService.updateUser(any(), any(), any(), any(), anyChar(), any()))
                .thenReturn(new UserService.UserResult(false, null, MSG_USER_ID_EMPTY));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HEADER_USER_VERSION, "3")
                        .content(body("USER0002", "JOHNNY", "DOE", "", "A", PfKeyAction.PF3)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HEADER_NEXT_PROGRAM))
                .andExpect(header().doesNotExist(HEADER_NEXT_TRANSACTION))
                .andExpect(jsonPath("$.errorMessage").value(MSG_USER_ID_EMPTY))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(userService).updateUser(any(), any(), any(), any(), anyChar(), any());
    }

    @Test
    @DisplayName("H2: PF12 cancels and navigates back to the Admin Menu via COADM01C/CA00 headers")
    @WithMockUser(roles = "ADMIN")
    void pf12NavigatesBackToAdminMenu() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", null, null, null, null, PfKeyAction.PF12)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, BACK_TARGET_PROGRAM))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, BACK_TARGET_TRANSACTION));

        verifyNoInteractions(userService);
    }

    // ==================================================================
    // I. Any other attention key -> invalid-key message
    // ==================================================================

    @Test
    @DisplayName("I: an unmapped key (PF7) re-displays the invalid-key message (200)")
    @WithMockUser(roles = "ADMIN")
    void otherKeyShowsInvalidKeyMessage() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", null, null, null, null, PfKeyAction.PF7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_KEY))
                .andExpect(jsonPath("$.password").doesNotExist());

        verifyNoInteractions(userService);
    }


    // ==================================================================
    // J. PASSWORD DISCIPLINE (write-only, never echoed)
    // ==================================================================

    @Test
    @DisplayName("J: a password sent on the request is never echoed on the response (write-only, no leak)")
    @WithMockUser(roles = "ADMIN")
    void passwordIsNeverEchoedOnAnyResponse() throws Exception {
        // Fetch echoes the record's stored password? It must not. Send a distinctive
        // request password too, and prove neither value appears anywhere.
        UserSecurity existing = stubUser("USER0002", "JOHN", "DOE", "STORED99", "U");
        when(userService.listUsers(any(), any())).thenReturn(new PageImpl<>(List.of(existing)));

        String responseBody = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        // A password on the ENTER (fetch) request is irrelevant to the fetch and
                        // must not be reflected back.
                        .content(body("USER0002", null, null, "LEAKME99", null, PfKeyAction.ENTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0002"))
                // No password property on the response contract at all.
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.secUsrPwd").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // Neither the request password nor the stored password leaks into the body.
        assertThat(responseBody).doesNotContain("LEAKME99");
        assertThat(responseBody).doesNotContain("STORED99");
    }

    // ==================================================================
    // K. FIELD-CONTRACT PARITY (COUSR02 copybook lengths & field set)
    // ==================================================================

    @Test
    @DisplayName("K1: the response exposes exactly the COUSR2AO field set and no password")
    @WithMockUser(roles = "ADMIN")
    void responseExposesExpectedFieldSetAndNoPassword() throws Exception {
        UserSecurity existing = stubUser("USER0002", "JOHN", "DOE", "STORED99", "A");
        when(userService.listUsers(any(), any())).thenReturn(new PageImpl<>(List.of(existing)));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", null, null, null, null, PfKeyAction.ENTER)))
                .andExpect(status().isOk())
                // Header fields (COUSR2AO: TRNNAMEO/TITLE01O/CURDATEO/PGMNAMEO/TITLE02O/CURTIMEO).
                .andExpect(jsonPath("$.transactionName").exists())
                .andExpect(jsonPath("$.title01").exists())
                .andExpect(jsonPath("$.currentDate").exists())
                .andExpect(jsonPath("$.programName").exists())
                .andExpect(jsonPath("$.title02").exists())
                .andExpect(jsonPath("$.currentTime").exists())
                // Detail fields (USRIDINO/FNAMEO/LNAMEO/USRTYPEO) + ERRMSGO.
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.firstName").exists())
                .andExpect(jsonPath("$.lastName").exists())
                .andExpect(jsonPath("$.userType").exists())
                .andExpect(jsonPath("$.errorMessage").exists())
                // The COUSR2AO PASSWDO field is intentionally NOT carried on the response.
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("K2: userId beyond PIC X(8) (USRIDINI) fails validation with 400 and no value leak")
    @WithMockUser(roles = "ADMIN")
    void userIdBeyondEightCharsIsBadRequest() throws Exception {
        String overLongId = "USR123456"; // 9 chars > X(8)
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(overLongId, "JOHN", "DOE", "S3CRET1", "U", PfKeyAction.ENTER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("K3: firstName beyond PIC X(20) (FNAMEI) fails validation with 400")
    @WithMockUser(roles = "ADMIN")
    void firstNameBeyondTwentyCharsIsBadRequest() throws Exception {
        String overLongName = "A".repeat(21); // 21 chars > X(20)
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", overLongName, "DOE", "S3CRET1", "U", PfKeyAction.PF5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("K4: userType outside [A/U] (USRTYPEI pattern) fails validation with 400")
    @WithMockUser(roles = "ADMIN")
    void userTypeOutsidePatternIsBadRequest() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USER0002", "JOHN", "DOE", "S3CRET1", "Z", PfKeyAction.PF5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"));

        verifyNoInteractions(userService);
    }

}

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
import com.carddemo.dto.user.UserCreateRequest;
import com.carddemo.dto.user.UserDto;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.security.MethodSecurityConfig;
import com.carddemo.security.UserDetailsServiceImpl;
import com.carddemo.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc slice test that proves the user-administration endpoints under
 * {@code /api/admin/users} enforce {@code @PreAuthorize("hasRole('ADMIN')")}
 * (refactoring rule <b>PR-18</b>).
 *
 * <p><b>THIS IS THE CORNERSTONE PR-18 GAP-CLOSURE TEST.</b> Together with
 * {@code BatchAdminControllerTest} it verifies the security improvement that closes the
 * documented programmatic-authorization gap in the legacy
 * {@code COUSR00C}&ndash;{@code COUSR03C} user-administration programs
 * (Tech Spec &sect;6.4 / AAP &sect;0.6.9). Those COBOL programs contained <em>zero</em>
 * programmatic authorization checks &mdash; the only safeguard was menu routing through the
 * admin-only {@code COADM01C} menu, so any caller who invoked the {@code CU00}&ndash;{@code CU03}
 * transaction ids directly (bypassing the menu) gained unrestricted access. In a stateless REST
 * world the exposure is greater still, because any client (curl, Postman, a hostile script) can
 * target a URL directly; therefore authorization MUST be enforced programmatically at the method
 * boundary, and this test verifies it for all five endpoints:</p>
 * <ul>
 *   <li>{@code GET    /api/admin/users}            &mdash; list (COUSR00C, TRANID CU00)</li>
 *   <li>{@code GET    /api/admin/users/{userId}}   &mdash; view single (COUSR01C view)</li>
 *   <li>{@code POST   /api/admin/users}            &mdash; create (COUSR01C add)</li>
 *   <li>{@code PUT    /api/admin/users/{userId}}   &mdash; update (COUSR02C, TRANID CU02)</li>
 *   <li>{@code DELETE /api/admin/users/{userId}}   &mdash; delete (COUSR03C, TRANID CU03)</li>
 * </ul>
 *
 * <h2>Per-endpoint authorization matrix (PR-18)</h2>
 * <p>Each endpoint is exercised under three principals:</p>
 * <ol>
 *   <li><b>Anonymous</b> ({@link WithAnonymousUser}) &rarr; rejected with a {@code 4xx} status
 *       (the default Spring Boot filter chain commences authentication for an anonymous caller
 *       before the handler ever runs; {@code status().is4xxClientError()} matches both the 401 and
 *       403 outcomes).</li>
 *   <li><b>{@code USER} role</b> ({@link WithMockUser}{@code (roles = "USER")}) &rarr;
 *       {@code 403 Forbidden} (authenticated, passes the filter chain, then method security denies
 *       the missing {@code ROLE_ADMIN} &mdash; {@code AccessDeniedException} mapped to 403 by
 *       {@link GlobalExceptionHandler}).</li>
 *   <li><b>{@code ADMIN} role</b> ({@link WithMockUser}{@code (roles = "ADMIN")}) &rarr; success
 *       ({@code 200}/{@code 201}/{@code 204}).</li>
 * </ol>
 * <p>Every rejected (anonymous or {@code USER}) case additionally asserts
 * {@code verify(userService, never()).<method>()} &mdash; the strongest proof of PR-18: even if an
 * HTTP-layer bypass existed, the service layer must never have been reached for a non-admin caller.</p>
 *
 * <h2>Why this slice keeps Spring Security active (no {@code addFilters = false})</h2>
 * <p>The whole point of these tests is authorization enforcement, so the security filter chain and
 * the method-security advisor MUST be live:</p>
 * <ul>
 *   <li>{@link WebMvcTest} loads only the {@link UserController} web layer.</li>
 *   <li>{@link Import @Import(MethodSecurityConfig.class)} activates
 *       {@code @EnableMethodSecurity(prePostEnabled = true)} so the class-level
 *       {@code @PreAuthorize("hasRole('ADMIN')")} on the controller is actually applied &mdash;
 *       without it the annotation would be silently ignored.</li>
 *   <li>{@link Import @Import(GlobalExceptionHandler.class)} provides the
 *       {@code @RestControllerAdvice} that maps an {@code AccessDeniedException} (raised by method
 *       security) to {@code 403 Forbidden} and an {@code AccountNotFoundException} to
 *       {@code 404 Not Found}, enabling the {@code USER}&rarr;403 and unknown-user&rarr;404
 *       assertions.</li>
 * </ul>
 *
 * <h2>Why {@code excludeFilters} drops the {@code com.carddemo.security} package</h2>
 * <p>A {@code @WebMvcTest} slice always registers application {@code jakarta.servlet.Filter} beans.
 * The production {@code JwtAuthenticationFilter} is a {@code @Component} extending
 * {@code OncePerRequestFilter}; left untouched it would be instantiated by this slice and, because
 * its collaborator {@code CustomAuthorityMapper} (a plain {@code @Component}, not an MVC stereotype)
 * is not loaded by the slice, the application context would fail to start with an
 * {@code UnsatisfiedDependencyException}. Excluding the whole {@code com.carddemo.security} package
 * from the component scan keeps the context minimal. The {@code MethodSecurityConfig} and
 * {@code UserDetailsServiceImpl} beans the tests genuinely need are supplied explicitly &mdash; the
 * former via {@code @Import}, the latter via {@code @MockBean} &mdash; so the exclusion does not
 * remove them. With no custom {@code SecurityFilterChain} bean present, Spring Boot's default chain
 * applies ({@code anyRequest().authenticated()}), so an authenticated {@code USER} passes the filter
 * and is then denied by method security (403), while an anonymous caller is rejected at the filter
 * chain itself (4xx) before the handler method ever runs.</p>
 *
 * <h2>Password-leak paranoia (PR-17)</h2>
 * <p>Several tests assert the response body contains neither a plaintext password
 * ({@code "PASSWORD"} / {@code "NEWPASS"}) nor a BCrypt hash prefix ({@code "$2a$"} / {@code "$2b$"}).
 * The {@link UserDto} response shape has no password field at all, and
 * {@link UserCreateRequest#getPassword()} is {@code @JsonProperty(WRITE_ONLY)}; these checks are
 * defense in depth confirming the hash never crosses the REST boundary.</p>
 *
 * @see UserController
 * @see MethodSecurityConfig
 * @see GlobalExceptionHandler
 */
@WebMvcTest(
        controllers = UserController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import({GlobalExceptionHandler.class, MethodSecurityConfig.class})
class UserControllerTest {

    /** Auto-configured MockMvc with the Spring Security filter chain active (no {@code addFilters = false}). */
    @Autowired
    private MockMvc mockMvc;

    /** Auto-configured Jackson mapper used to serialize {@link UserCreateRequest} bodies for POST/PUT. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The single controller collaborator. Mocked so that every non-admin test can prove, via
     * {@code verify(userService, never()).<method>()}, that the service layer is never reached when
     * {@code @PreAuthorize} rejects the caller &mdash; the strongest PR-18 guarantee.
     */
    @MockBean
    private UserService userService;

    /**
     * Mocked {@code UserDetailsService} required by the security infrastructure so the
     * {@code @WebMvcTest} application context loads successfully with method security active (the
     * real implementation lives in the {@code com.carddemo.security} package excluded above).
     */
    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    /** Sample ADMIN user ({@code userType = 'A'} per PR-19); never carries a password (PR-17). */
    private UserDto sampleAdminUser;

    /** Sample regular user ({@code userType = 'U'} per PR-19); never carries a password (PR-17). */
    private UserDto sampleRegularUser;

    @BeforeEach
    void setUp() {
        sampleAdminUser = UserDto.builder()
                .userId("ADMIN001")
                .firstName("System")
                .lastName("Administrator")
                // PR-17: UserDto has NO password field — the hash can never be exposed.
                .userType("A") // PR-19: 'A' -> ROLE_ADMIN
                .build();

        sampleRegularUser = UserDto.builder()
                .userId("USER0001")
                .firstName("John")
                .lastName("Doe")
                .userType("U") // PR-19: 'U' -> ROLE_USER
                .build();
    }

    // =========================================================================
    // GET /api/admin/users  (list) — replaces COUSR00C (TRANID CU00)
    // =========================================================================

    /**
     * PR-18 enforcement for the paginated list endpoint. The controller returns a
     * {@code Page<UserDto>}, so the success assertions read the {@code $.content} wrapper produced by
     * the {@code PageImpl} serialization rather than a bare top-level array.
     */
    @Nested
    @DisplayName("GET /api/admin/users — PR-18 enforcement (list, COUSR00C)")
    class ListUsersAuthorization {

        @Test
        @DisplayName("PR-18: Anonymous → 4xx (unauthenticated MUST NEVER list users)")
        @WithAnonymousUser
        void shouldRejectAnonymous() throws Exception {
            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().is4xxClientError());

            // The service MUST NOT have been invoked for an unauthenticated caller.
            verify(userService, never()).listUsers(any());
        }

        @Test
        @DisplayName("PR-18: USER role → 403 Forbidden (insufficient role)")
        @WithMockUser(username = "USER0001", roles = {"USER"})
        void shouldReject403ForNonAdmin() throws Exception {
            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().isForbidden());

            // Method security denies BEFORE the handler body executes.
            verify(userService, never()).listUsers(any());
        }

        @Test
        @DisplayName("PR-18: ADMIN role → 200 OK with paginated user list (PR-19 userType A/U)")
        @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
        void shouldAllowAdmin() throws Exception {
            // Mirror production: the service returns a page backed by a REAL Pageable
            // (PageRequest), never Pageable.unpaged(). A page built with the single-arg
            // PageImpl constructor defaults to Unpaged, whose getOffset() throws
            // UnsupportedOperationException during Jackson serialization (HTTP 500).
            when(userService.listUsers(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            List.of(sampleAdminUser, sampleRegularUser),
                            PageRequest.of(0, 10),
                            2));

            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().isOk())
                    // Page<UserDto> serializes with a "content" array wrapper, not a bare array.
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].userId").value("ADMIN001"))
                    .andExpect(jsonPath("$.content[0].userType").value("A"))   // PR-19
                    .andExpect(jsonPath("$.content[1].userId").value("USER0001"))
                    .andExpect(jsonPath("$.content[1].userType").value("U"));  // PR-19

            verify(userService).listUsers(any(Pageable.class));
        }

        @Test
        @DisplayName("PR-17: list response NEVER includes a password or BCrypt hash")
        @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
        void shouldNeverExposePasswordHash() throws Exception {
            when(userService.listUsers(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            List.of(sampleAdminUser),
                            PageRequest.of(0, 10),
                            1));

            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().isOk())
                    // Paranoid check: no BCrypt hash prefix may appear anywhere in the body.
                    .andExpect(content().string(not(containsString("$2a$"))))
                    .andExpect(content().string(not(containsString("$2b$"))));
        }

        @Test
        @DisplayName("F4-PAG-01: ADMIN + sort=password → 400 INVALID_SORT (not 500)")
        @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
        void shouldRejectPasswordSortPropertyWith400() throws Exception {
            // 'password' resolves as a UserDetails bean getter on the User entity, so without an
            // explicit allowlist Spring Data would let it through and Hibernate would later fail
            // (mapped column is sec_usr_pwd) as an unmapped 500. The controller's sort allowlist
            // must instead reject it with the SAME 400 INVALID_SORT contract as the other lists.
            mockMvc.perform(get("/api/admin/users").param("sort", "password"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SORT"));

            // The sort is validated in the controller BEFORE the service is ever invoked.
            verify(userService, never()).listUsers(any());
        }

        @Test
        @DisplayName("F4-PAG-01: ADMIN + sort=secUsrPwd (sensitive column) → 400 INVALID_SORT")
        @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
        void shouldRejectSensitiveColumnSortPropertyWith400() throws Exception {
            // The real password-hash column must never be an accepted sort key either; the
            // allowlist ({userId, firstName, lastName, userType}) excludes it.
            mockMvc.perform(get("/api/admin/users").param("sort", "secUsrPwd"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SORT"));

            verify(userService, never()).listUsers(any());
        }

        @Test
        @DisplayName("F4-PAG-01: ADMIN + sort=firstName (whitelisted) → 200 OK")
        @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
        void shouldAllowWhitelistedSortProperty() throws Exception {
            when(userService.listUsers(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            List.of(sampleAdminUser),
                            PageRequest.of(0, 10),
                            1));

            mockMvc.perform(get("/api/admin/users").param("sort", "firstName"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());

            // A valid sort property passes the allowlist and reaches the service.
            verify(userService).listUsers(any(Pageable.class));
        }
    }

    // =========================================================================
    // GET /api/admin/users/{userId}  (view) — replaces COUSR01C view / COUSR02C-03C read
    // =========================================================================

    /** PR-18 enforcement for single-user retrieval, plus the unknown-user 404 path for an ADMIN caller. */
    @Nested
    @DisplayName("GET /api/admin/users/{userId} — PR-18 enforcement (view)")
    class GetUserAuthorization {

        @Test
        @DisplayName("PR-18: Anonymous → 4xx")
        @WithAnonymousUser
        void shouldRejectAnonymous() throws Exception {
            mockMvc.perform(get("/api/admin/users/{userId}", "ADMIN001"))
                    .andExpect(status().is4xxClientError());

            verify(userService, never()).getUser(any());
        }

        @Test
        @DisplayName("PR-18: USER role → 403 Forbidden")
        @WithMockUser(roles = {"USER"})
        void shouldReject403ForNonAdmin() throws Exception {
            mockMvc.perform(get("/api/admin/users/{userId}", "ADMIN001"))
                    .andExpect(status().isForbidden());

            verify(userService, never()).getUser(any());
        }

        @Test
        @DisplayName("PR-18: ADMIN role → 200 OK (PR-19 userType 'A')")
        @WithMockUser(roles = {"ADMIN"})
        void shouldAllowAdmin() throws Exception {
            when(userService.getUser("ADMIN001")).thenReturn(sampleAdminUser);

            mockMvc.perform(get("/api/admin/users/{userId}", "ADMIN001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value("ADMIN001"))
                    .andExpect(jsonPath("$.userType").value("A")) // PR-19
                    // PR-17: no password / hash in the single-user body either.
                    .andExpect(content().string(not(containsString("$2a$"))));

            verify(userService).getUser("ADMIN001");
        }

        @Test
        @DisplayName("Unknown user with ADMIN role → 404 Not Found")
        @WithMockUser(roles = {"ADMIN"})
        void shouldReturn404ForUnknownUser() throws Exception {
            when(userService.getUser("UNKNOWN1"))
                    .thenThrow(new AccountNotFoundException("UNKNOWN1"));

            mockMvc.perform(get("/api/admin/users/{userId}", "UNKNOWN1"))
                    .andExpect(status().isNotFound());

            verify(userService).getUser("UNKNOWN1");
        }
    }

    // =========================================================================
    // POST /api/admin/users  (create) — replaces COUSR01C add (TRANID CU01)
    // =========================================================================

    /**
     * PR-18 enforcement for user creation, plus the PR-17 password-leak paranoia on the success
     * body and the {@code @Valid} empty-body {@code 400} path for an ADMIN caller.
     */
    @Nested
    @DisplayName("POST /api/admin/users — PR-18 enforcement (create, COUSR01C)")
    class CreateUserAuthorization {

        /**
         * A fully valid create payload. {@code userId} is 8 uppercase-alphanumeric characters,
         * {@code userType} is {@code 'U'}, and a non-blank {@code password} is supplied &mdash; so a
         * {@code USER}-role request reaches (and is denied by) method security rather than failing
         * Bean Validation first, keeping the {@code 403} assertion meaningful.
         */
        private UserCreateRequest validRequest() {
            UserCreateRequest req = new UserCreateRequest();
            req.setUserId("NEWUSR01");
            req.setFirstName("New");
            req.setLastName("User");
            // PR-17: plaintext on the wire only; the service BCrypt-encodes before persisting.
            req.setPassword("PASSWORD");
            req.setUserType("U");
            return req;
        }

        @Test
        @DisplayName("PR-18: Anonymous → 4xx (unauthenticated MUST NEVER create users)")
        @WithAnonymousUser
        void shouldRejectAnonymous() throws Exception {
            mockMvc.perform(post("/api/admin/users")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().is4xxClientError());

            verify(userService, never()).createUser(any());
        }

        @Test
        @DisplayName("PR-18: USER role → 403 Forbidden (non-admin MUST NEVER create users)")
        @WithMockUser(roles = {"USER"})
        void shouldReject403ForNonAdmin() throws Exception {
            mockMvc.perform(post("/api/admin/users")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isForbidden());

            verify(userService, never()).createUser(any());
        }

        @Test
        @DisplayName("PR-18: ADMIN role → 201 Created (PR-17: no plaintext password or hash in body)")
        @WithMockUser(roles = {"ADMIN"})
        void shouldAllowAdminAndNeverEchoPassword() throws Exception {
            UserDto created = UserDto.builder()
                    .userId("NEWUSR01")
                    .firstName("New")
                    .lastName("User")
                    .userType("U")
                    .build();
            when(userService.createUser(any(UserCreateRequest.class))).thenReturn(created);

            mockMvc.perform(post("/api/admin/users")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.userId").value("NEWUSR01"))
                    .andExpect(jsonPath("$.userType").value("U"))        // PR-19
                    // PR-17: the submitted plaintext password is NEVER echoed back...
                    .andExpect(content().string(not(containsString("PASSWORD"))))
                    // ...and no BCrypt hash prefix may appear in the response.
                    .andExpect(content().string(not(containsString("$2a$"))))
                    .andExpect(content().string(not(containsString("$2b$"))));

            verify(userService).createUser(any(UserCreateRequest.class));
        }

        @Test
        @DisplayName("ADMIN with empty body → 400 Bad Request (@NotBlank fields violated)")
        @WithMockUser(roles = {"ADMIN"})
        void shouldRejectEmptyBody() throws Exception {
            mockMvc.perform(post("/api/admin/users")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            // Validation fails during argument binding; the service is never reached.
            verify(userService, never()).createUser(any());
        }
    }

    // =========================================================================
    // PUT /api/admin/users/{userId}  (update) — replaces COUSR02C (TRANID CU02)
    // =========================================================================

    /**
     * PR-18 enforcement for user update, plus the PR-17 paranoia on a password-change response and
     * the unknown-user 404 path for an ADMIN caller.
     */
    @Nested
    @DisplayName("PUT /api/admin/users/{userId} — PR-18 enforcement (update, COUSR02C)")
    class UpdateUserAuthorization {

        /** A valid update payload that also rotates the password (exercises the PR-17 re-encode path). */
        private UserCreateRequest updateRequest() {
            UserCreateRequest req = new UserCreateRequest();
            req.setUserId("USER0001");
            req.setFirstName("Updated");
            req.setLastName("Name");
            req.setPassword("NEWPASS");
            req.setUserType("U");
            return req;
        }

        @Test
        @DisplayName("PR-18: Anonymous → 4xx")
        @WithAnonymousUser
        void shouldRejectAnonymous() throws Exception {
            mockMvc.perform(put("/api/admin/users/{userId}", "USER0001")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest())))
                    .andExpect(status().is4xxClientError());

            verify(userService, never()).updateUser(any(), any());
        }

        @Test
        @DisplayName("PR-18: USER role → 403 Forbidden")
        @WithMockUser(roles = {"USER"})
        void shouldReject403ForNonAdmin() throws Exception {
            mockMvc.perform(put("/api/admin/users/{userId}", "USER0001")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest())))
                    .andExpect(status().isForbidden());

            verify(userService, never()).updateUser(any(), any());
        }

        @Test
        @DisplayName("PR-18: ADMIN role + password change → 200 OK (PR-17: new password/hash never echoed)")
        @WithMockUser(roles = {"ADMIN"})
        void shouldAllowAdminUpdate() throws Exception {
            UserDto updated = UserDto.builder()
                    .userId("USER0001")
                    .firstName("Updated")
                    .lastName("Name")
                    .userType("U")
                    .build();
            when(userService.updateUser(eq("USER0001"), any(UserCreateRequest.class)))
                    .thenReturn(updated);

            mockMvc.perform(put("/api/admin/users/{userId}", "USER0001")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value("USER0001"))
                    .andExpect(jsonPath("$.firstName").value("Updated"))
                    .andExpect(jsonPath("$.userType").value("U"))        // PR-19
                    // PR-17: the rotated plaintext password is NEVER echoed back...
                    .andExpect(content().string(not(containsString("NEWPASS"))))
                    // ...and no BCrypt hash prefix may appear in the response.
                    .andExpect(content().string(not(containsString("$2a$"))));

            verify(userService).updateUser(eq("USER0001"), any(UserCreateRequest.class));
        }

        @Test
        @DisplayName("Unknown user with ADMIN role → 404 Not Found")
        @WithMockUser(roles = {"ADMIN"})
        void shouldReturn404ForUnknownUser() throws Exception {
            when(userService.updateUser(eq("UNKNOWN1"), any(UserCreateRequest.class)))
                    .thenThrow(new AccountNotFoundException("UNKNOWN1"));

            mockMvc.perform(put("/api/admin/users/{userId}", "UNKNOWN1")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest())))
                    .andExpect(status().isNotFound());

            verify(userService).updateUser(eq("UNKNOWN1"), any(UserCreateRequest.class));
        }
    }

    // =========================================================================
    // DELETE /api/admin/users/{userId}  (delete) — replaces COUSR03C (TRANID CU03)
    // =========================================================================

    /**
     * PR-18 enforcement for user deletion &mdash; the highest-risk endpoint &mdash; plus the
     * unknown-user 404 path. {@code UserService.deleteUser(String)} returns {@code void}, so the
     * throw case is stubbed with {@code doThrow(...).when(userService).deleteUser(...)}.
     */
    @Nested
    @DisplayName("DELETE /api/admin/users/{userId} — PR-18 enforcement (delete, COUSR03C)")
    class DeleteUserAuthorization {

        @Test
        @DisplayName("PR-18: Anonymous → 4xx (unauthenticated MUST NEVER delete users)")
        @WithAnonymousUser
        void shouldRejectAnonymous() throws Exception {
            mockMvc.perform(delete("/api/admin/users/{userId}", "USER0001").with(csrf()))
                    .andExpect(status().is4xxClientError());

            verify(userService, never()).deleteUser(any());
        }

        @Test
        @DisplayName("PR-18: USER role → 403 Forbidden (CRITICAL — non-admin MUST NEVER delete users)")
        @WithMockUser(roles = {"USER"})
        void shouldReject403ForNonAdmin() throws Exception {
            mockMvc.perform(delete("/api/admin/users/{userId}", "USER0001").with(csrf()))
                    .andExpect(status().isForbidden());

            // CRITICAL: deletion must never reach the service for a non-admin caller.
            verify(userService, never()).deleteUser(any());
        }

        @Test
        @DisplayName("PR-18: ADMIN role → 204 No Content (deletion succeeded)")
        @WithMockUser(roles = {"ADMIN"})
        void shouldAllowAdminDeletion() throws Exception {
            mockMvc.perform(delete("/api/admin/users/{userId}", "USER0001").with(csrf()))
                    .andExpect(status().isNoContent());

            verify(userService).deleteUser("USER0001");
        }

        @Test
        @DisplayName("Unknown user with ADMIN role → 404 Not Found")
        @WithMockUser(roles = {"ADMIN"})
        void shouldReturn404ForUnknownUser() throws Exception {
            doThrow(new AccountNotFoundException("UNKNOWN1"))
                    .when(userService).deleteUser("UNKNOWN1");

            mockMvc.perform(delete("/api/admin/users/{userId}", "UNKNOWN1").with(csrf()))
                    .andExpect(status().isNotFound());

            verify(userService).deleteUser("UNKNOWN1");
        }
    }
}

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
package com.carddemo.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.AddUserRequestDto;
import com.carddemo.common.dto.UpdateUserRequestDto;
import com.carddemo.common.dto.UserListResponseDto;
import com.carddemo.common.dto.UserResponseDto;
import com.carddemo.common.dto.UserWriteResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.user.config.SecurityConfig;
import com.carddemo.user.service.UserService;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

/**
 * :purpose: Web-layer ({@code @WebMvcTest}) verification of {@link UserController} REST
 *     endpoints and administrator-only role gating for the user-CRUD service re-platformed
 *     from the CICS COBOL programs {@code COUSR00C}-{@code COUSR03C} (transactions
 *     {@code CU00}-{@code CU03}). Exercises the five endpoints (list/paging, add, view,
 *     update, delete), the exact HTTP status contract (200/201/204), the shared
 *     {@link GlobalExceptionHandler} error mapping ({@link CardDemoException} to 400,
 *     {@link RecordNotFoundException} to 404), and the {@code ROLE_ADMIN} gate declared by
 *     the imported {@link SecurityConfig}; the {@link UserService} collaborator is mocked.
 * :note: The imported {@link SecurityConfig} enables cookie-based CSRF, so every
 *     state-changing request carries a generated CSRF token via {@code csrf()}. As of
 *     Spring Boot 4.0 the auto-configured {@code MockMvc} no longer applies
 *     {@code springSecurity()} automatically, so it is applied explicitly when the
 *     {@code MockMvc} instance is built, activating the URL role-gating filter chain and
 *     the {@code @WithMockUser} test context. Every response body is asserted to omit any
 *     {@code password} field.
 */
@WebMvcTest(UserController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class UserControllerTest {

    /**
     * :purpose: Minimal configuration source for the web-layer slice. Its presence in the
     *     test's own package shadows the production {@code UserServiceApplication}, whose
     *     class-level {@code @EnableJpaRepositories}/{@code @EntityScan} would otherwise be
     *     processed by the slice and demand a JPA {@code entityManagerFactory} that a
     *     web-only context neither provides nor needs. Because this configuration performs
     *     no component scanning, the controller under test is contributed explicitly as a
     *     bean wired to the mocked {@link UserService}; the {@code RequestMappingHandlerMapping}
     *     supplied by the MVC slice registers its request mappings. Security and
     *     exception-handling beans arrive via {@code @Import}; the collaborator is a
     *     {@code @MockitoBean}. No persistence wiring is required.
     */
    @SpringBootConfiguration
    static class SliceConfig {

        /**
         * :purpose: Contribute the {@link UserController} under test to the slice context,
         *     constructor-injected with the mocked {@link UserService} bean.
         *
         * :param userService: the {@code @MockitoBean} collaborator resolved from the context
         * :returns: the controller instance whose request mappings the slice will register
         */
        @Bean
        UserController userController(UserService userService) {
            return new UserController(userService);
        }
    }

    /** :purpose: Frozen not-found outcome message (COUSR02C/COUSR03C keyed read). */
    private static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /** :purpose: Frozen duplicate-id rejection message (COUSR01C add). */
    private static final String MSG_USER_ALREADY_EXISTS = "User ID already exist...";

    /** :purpose: Frozen empty-field rejection message (COUSR01C add). */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /** :purpose: Frozen no-change rejection message (COUSR02C update). */
    private static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    /** :purpose: Frozen bottom-of-list boundary message (COUSR00C PF8 paging). */
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";

    /** :purpose: Spring MVC slice web context, used to build the security-aware MockMvc. */
    @Autowired
    private WebApplicationContext webApplicationContext;

    /** :purpose: Jackson (3.x) mapper used to serialize request DTOs to JSON request bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /** :purpose: Servlet MockMvc entry point with the Spring Security filter chain applied. */
    private MockMvc mockMvc;

    /** :purpose: Mocked user-management collaborator; the controller delegates every call to it. */
    @MockitoBean
    private UserService userService;

    /**
     * :purpose: Build MockMvc from the slice web context with the Spring Security test
     *     configurer applied, so the URL role-gating filter chain runs and the
     *     {@code @WithMockUser} principal propagates into each request.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    /**
     * :purpose: Build a {@link UserResponseDto} projection fixture (no password field).
     * :param userId: the user id (``SEC-USR-ID``).
     * :param firstName: the first name (``SEC-USR-FNAME``).
     * :param lastName: the last name (``SEC-USR-LNAME``).
     * :param userType: the user type code 'A'/'U' (``SEC-USR-TYPE``).
     * :returns: a populated response projection.
     */
    private UserResponseDto userResponse(String userId, String firstName, String lastName, String userType) {
        UserResponseDto dto = new UserResponseDto();
        dto.setUserId(userId);
        dto.setFirstName(firstName);
        dto.setLastName(lastName);
        dto.setUserType(userType);
        return dto;
    }

    /**
     * :purpose: Serialize an add-user request body (id + non-secret profile fields).
     * :param userId: the entered user id.
     * :param firstName: the entered first name.
     * :param lastName: the entered last name.
     * :param userType: the entered user type code.
     * :returns: the JSON request body.
     */
    private String addUserJson(String userId, String firstName, String lastName, String userType) throws Exception {
        AddUserRequestDto dto = new AddUserRequestDto();
        dto.setUserId(userId);
        dto.setFirstName(firstName);
        dto.setLastName(lastName);
        dto.setUserType(userType);
        return objectMapper.writeValueAsString(dto);
    }

    /**
     * :purpose: Wrap user rows in the ``COUSR00C`` list response so the paging cursors and
     *     the ``WS-MESSAGE`` banner travel with the page.
     * :param rows: the user projections on the page.
     * :returns: the populated {@link UserListResponseDto}.
     */
    private UserListResponseDto listResponse(UserResponseDto... rows) {
        UserListResponseDto response = new UserListResponseDto();
        response.setUsers(List.of(rows));
        if (rows.length > 0) {
            response.setUserIdFirst(rows[0].getUserId());
            response.setUserIdLast(rows[rows.length - 1].getUserId());
        }
        return response;
    }

    /**
     * :purpose: Build a write response carrying a verbatim legacy message.
     * :param userId: the affected user id.
     * :param firstName: the user's first name.
     * :param lastName: the user's last name.
     * :param userType: the user's type.
     * :param message: the verbatim legacy message.
     * :returns: the populated {@link UserWriteResponseDto}.
     */
    private UserWriteResponseDto writeResponse(String userId, String firstName, String lastName,
                                               String userType, String message) {
        return new UserWriteResponseDto(userId, firstName, lastName, userType, message);
    }

    /**
     * :purpose: Serialize an update-user request body (editable non-secret profile fields).
     * :param firstName: the new first name.
     * :param lastName: the new last name.
     * :param userType: the new user type code.
     * :returns: the JSON request body.
     */
    private String updateUserJson(String firstName, String lastName, String userType) throws Exception {
        UpdateUserRequestDto dto = new UpdateUserRequestDto();
        dto.setFirstName(firstName);
        dto.setLastName(lastName);
        dto.setUserType(userType);
        return objectMapper.writeValueAsString(dto);
    }

    // -----------------------------------------------------------------
    // GET /users - list and PF7/PF8 paging dispatch (COUSR00C / CU00)
    // -----------------------------------------------------------------

    /**
     * :purpose: With no paging direction, GET /users lists the requested page via
     *     {@code listUsers(0)} and never touches the keyset-paging methods.
     */
    @Test
    @DisplayName("GET /users as ADMIN with default params returns 200 and the listed users (no password)")
    @WithMockUser(roles = "ADMIN")
    void listUsersDefaultParamsReturnsOk() throws Exception {
        given(userService.listUsers(0)).willReturn(listResponse(
                userResponse("USER0001", "Alice", "Adminson", "A"),
                userResponse("USER0002", "Bob", "Bakerman", "U"),
                userResponse("USER0003", "Carol", "Clarkson", "U")));

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", hasSize(3)))
                .andExpect(jsonPath("$.users[0].userId").value("USER0001"))
                .andExpect(jsonPath("$.users[0].firstName").value("Alice"))
                .andExpect(jsonPath("$.users[0].lastName").value("Adminson"))
                .andExpect(jsonPath("$.users[0].userType").value("A"))
                .andExpect(jsonPath("$.users[1].userId").value("USER0002"))
                .andExpect(jsonPath("$.users[2].userId").value("USER0003"))
                .andExpect(jsonPath("$.users[*].password").doesNotExist());

        verify(userService).listUsers(0);
        verify(userService, never()).pageForward(any());
        verify(userService, never()).pageBackward(any());
    }

    /**
     * :purpose: {@code direction=forward} with a non-blank cursor dispatches to
     *     {@code pageForward(cursor)} (COUSR00C PF8) and never to the page-based list.
     */
    @Test
    @DisplayName("GET /users as ADMIN with direction=forward dispatches to pageForward(cursor) and returns 200")
    @WithMockUser(roles = "ADMIN")
    void listUsersForwardDispatchesToPageForward() throws Exception {
        given(userService.pageForward("USER0005")).willReturn(listResponse(
                userResponse("USER0006", "Dan", "Dyer", "U")));

        mockMvc.perform(get("/users").param("direction", "forward").param("cursor", "USER0005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", hasSize(1)))
                .andExpect(jsonPath("$.users[0].userId").value("USER0006"))
                .andExpect(jsonPath("$.users[*].password").doesNotExist());

        ArgumentCaptor<String> cursorCaptor = ArgumentCaptor.forClass(String.class);
        verify(userService).pageForward(cursorCaptor.capture());
        assertThat(cursorCaptor.getValue()).isEqualTo("USER0005");
        verify(userService, never()).listUsers(anyInt());
        verify(userService, never()).pageBackward(any());
    }

    /**
     * :purpose: {@code direction=backward} with a non-blank cursor dispatches to
     *     {@code pageBackward(cursor)} (COUSR00C PF7) and never to the page-based list.
     */
    @Test
    @DisplayName("GET /users as ADMIN with direction=backward dispatches to pageBackward(cursor) and returns 200")
    @WithMockUser(roles = "ADMIN")
    void listUsersBackwardDispatchesToPageBackward() throws Exception {
        given(userService.pageBackward("USER0005")).willReturn(listResponse(
                userResponse("USER0004", "Eve", "Evans", "U")));

        mockMvc.perform(get("/users").param("direction", "backward").param("cursor", "USER0005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", hasSize(1)))
                .andExpect(jsonPath("$.users[0].userId").value("USER0004"))
                .andExpect(jsonPath("$.users[*].password").doesNotExist());

        ArgumentCaptor<String> cursorCaptor = ArgumentCaptor.forClass(String.class);
        verify(userService).pageBackward(cursorCaptor.capture());
        assertThat(cursorCaptor.getValue()).isEqualTo("USER0005");
        verify(userService, never()).listUsers(anyInt());
        verify(userService, never()).pageForward(any());
    }

    /**
     * :purpose: An explicit {@code page} with no direction dispatches to the page-based
     *     {@code listUsers(page)} with the parsed integer page index.
     */
    @Test
    @DisplayName("GET /users as ADMIN with page=2 dispatches to listUsers(2) and returns 200")
    @WithMockUser(roles = "ADMIN")
    void listUsersWithExplicitPageDispatchesToListUsers() throws Exception {
        given(userService.listUsers(2)).willReturn(listResponse(
                userResponse("USER0021", "Frank", "Fisher", "U")));

        mockMvc.perform(get("/users").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", hasSize(1)))
                .andExpect(jsonPath("$.users[0].userId").value("USER0021"))
                .andExpect(jsonPath("$.users[*].password").doesNotExist());

        verify(userService).listUsers(2);
        verify(userService, never()).pageForward(any());
        verify(userService, never()).pageBackward(any());
    }

    /**
     * :purpose: A paging boundary surfaces as {@link CardDemoException}, mapped by the
     *     shared advice to HTTP 400 with the frozen bottom-of-list message.
     */
    @Test
    @DisplayName("GET /users as ADMIN at the bottom paging boundary returns 400 (CardDemoException)")
    @WithMockUser(roles = "ADMIN")
    void listUsersForwardAtBottomBoundaryReturnsBadRequest() throws Exception {
        given(userService.pageForward("USER9999")).willThrow(new CardDemoException(MSG_ALREADY_BOTTOM));

        mockMvc.perform(get("/users").param("direction", "forward").param("cursor", "USER9999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(MSG_ALREADY_BOTTOM));

        verify(userService).pageForward("USER9999");
    }


    // -----------------------------------------------------------------
    // POST /users - add user (COUSR01C / CU01); CSRF token required
    // -----------------------------------------------------------------

    /**
     * :purpose: A valid add returns 201 Created with the created projection and forwards the
     *     request body plus the separately-supplied raw password to the service.
     */
    @Test
    @DisplayName("POST /users as ADMIN with a valid body returns 201 Created (no password echoed)")
    @WithMockUser(roles = "ADMIN")
    void addUserValidReturnsCreated() throws Exception {
        given(userService.addUser(any(AddUserRequestDto.class), any()))
                .willReturn(writeResponse("USER0007", "Grace", "Green", "U",
                        "User USER0007 has been added ..."));

        mockMvc.perform(post("/users").with(csrf())
                        .param("password", "Pass1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addUserJson("USER0007", "Grace", "Green", "U")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("USER0007"))
                .andExpect(jsonPath("$.firstName").value("Grace"))
                .andExpect(jsonPath("$.lastName").value("Green"))
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.password").doesNotExist());

        ArgumentCaptor<AddUserRequestDto> addCaptor = ArgumentCaptor.forClass(AddUserRequestDto.class);
        verify(userService).addUser(addCaptor.capture(), eq("Pass1234"));
        AddUserRequestDto captured = addCaptor.getValue();
        assertThat(captured.getUserId()).isEqualTo("USER0007");
        assertThat(captured.getFirstName()).isEqualTo("Grace");
        assertThat(captured.getLastName()).isEqualTo("Green");
        assertThat(captured.getUserType()).isEqualTo("U");
    }

    /**
     * :purpose: A field the service rejects as empty surfaces as {@link CardDemoException},
     *     mapped by the shared advice to HTTP 400 with the frozen empty-field message.
     */
    @Test
    @DisplayName("POST /users as ADMIN with an empty field returns 400 (CardDemoException)")
    @WithMockUser(roles = "ADMIN")
    void addUserEmptyFieldReturnsBadRequest() throws Exception {
        given(userService.addUser(any(AddUserRequestDto.class), any()))
                .willThrow(new CardDemoException(MSG_USER_TYPE_EMPTY));

        mockMvc.perform(post("/users").with(csrf())
                        .param("password", "Pass1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addUserJson("USER0008", "Heidi", "Hunt", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(MSG_USER_TYPE_EMPTY));

        verify(userService).addUser(any(AddUserRequestDto.class), eq("Pass1234"));
    }

    /**
     * :purpose: A duplicate user id surfaces as {@link CardDemoException}, mapped by the
     *     shared advice to HTTP 400 with the frozen duplicate-id message.
     */
    @Test
    @DisplayName("POST /users as ADMIN with a duplicate id returns 400 (CardDemoException)")
    @WithMockUser(roles = "ADMIN")
    void addUserDuplicateReturnsBadRequest() throws Exception {
        given(userService.addUser(any(AddUserRequestDto.class), any()))
                .willThrow(new CardDemoException(MSG_USER_ALREADY_EXISTS));

        mockMvc.perform(post("/users").with(csrf())
                        .param("password", "Pass1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addUserJson("USER0001", "Ivan", "Irwin", "U")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(MSG_USER_ALREADY_EXISTS));
    }

    // -----------------------------------------------------------------
    // GET /users/{id} - view a single user (COUSR02C/COUSR03C keyed read)
    // -----------------------------------------------------------------

    /**
     * :purpose: An existing user is returned as a 200 projection with no password field.
     */
    @Test
    @DisplayName("GET /users/{id} as ADMIN for an existing user returns 200 (no password)")
    @WithMockUser(roles = "ADMIN")
    void getUserExistingReturnsOk() throws Exception {
        given(userService.getUser("USER0001"))
                .willReturn(userResponse("USER0001", "Alice", "Adminson", "A"));

        mockMvc.perform(get("/users/USER0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.lastName").value("Adminson"))
                .andExpect(jsonPath("$.userType").value("A"))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(userService).getUser("USER0001");
    }

    /**
     * :purpose: A missing user surfaces as {@link RecordNotFoundException}, mapped by the
     *     shared advice to HTTP 404.
     */
    @Test
    @DisplayName("GET /users/{id} as ADMIN for a missing user returns 404 (RecordNotFoundException)")
    @WithMockUser(roles = "ADMIN")
    void getUserMissingReturnsNotFound() throws Exception {
        given(userService.getUser("USER9999"))
                .willThrow(new RecordNotFoundException(MSG_USER_NOT_FOUND));

        mockMvc.perform(get("/users/USER9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        verify(userService).getUser("USER9999");
    }


    // -----------------------------------------------------------------
    // PUT /users/{id} - update user (COUSR02C / CU02); CSRF token required
    // -----------------------------------------------------------------

    /**
     * :purpose: A valid update returns the 200 updated projection and forwards the path id,
     *     the request body, and the separately-supplied raw password to the service.
     */
    @Test
    @DisplayName("PUT /users/{id} as ADMIN with a valid body returns 200 (no password echoed)")
    @WithMockUser(roles = "ADMIN")
    void updateUserValidReturnsOk() throws Exception {
        given(userService.updateUser(eq("USER0001"), any(UpdateUserRequestDto.class), any()))
                .willReturn(writeResponse("USER0001", "Alicia", "Adamson", "A",
                        "User USER0001 has been updated ..."));

        mockMvc.perform(put("/users/USER0001").with(csrf())
                        .param("password", "NewPass9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Alicia", "Adamson", "A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.firstName").value("Alicia"))
                .andExpect(jsonPath("$.lastName").value("Adamson"))
                .andExpect(jsonPath("$.userType").value("A"))
                .andExpect(jsonPath("$.password").doesNotExist());

        ArgumentCaptor<UpdateUserRequestDto> updateCaptor = ArgumentCaptor.forClass(UpdateUserRequestDto.class);
        verify(userService).updateUser(eq("USER0001"), updateCaptor.capture(), eq("NewPass9"));
        UpdateUserRequestDto captured = updateCaptor.getValue();
        assertThat(captured.getFirstName()).isEqualTo("Alicia");
        assertThat(captured.getLastName()).isEqualTo("Adamson");
        assertThat(captured.getUserType()).isEqualTo("A");
    }

    /**
     * :purpose: Updating a missing user surfaces as {@link RecordNotFoundException}, mapped
     *     by the shared advice to HTTP 404.
     */
    @Test
    @DisplayName("PUT /users/{id} as ADMIN for a missing user returns 404 (RecordNotFoundException)")
    @WithMockUser(roles = "ADMIN")
    void updateUserMissingReturnsNotFound() throws Exception {
        given(userService.updateUser(eq("USER9999"), any(UpdateUserRequestDto.class), any()))
                .willThrow(new RecordNotFoundException(MSG_USER_NOT_FOUND));

        mockMvc.perform(put("/users/USER9999").with(csrf())
                        .param("password", "NewPass9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Alicia", "Adamson", "A")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        verify(userService).updateUser(eq("USER9999"), any(UpdateUserRequestDto.class), eq("NewPass9"));
    }

    /**
     * :purpose: A no-change update is rejected by the service as {@link CardDemoException},
     *     mapped by the shared advice to HTTP 400 with the frozen modify-to-update message.
     */
    @Test
    @DisplayName("PUT /users/{id} as ADMIN with no changed field returns 400 (CardDemoException)")
    @WithMockUser(roles = "ADMIN")
    void updateUserRejectedReturnsBadRequest() throws Exception {
        given(userService.updateUser(eq("USER0001"), any(UpdateUserRequestDto.class), any()))
                .willThrow(new CardDemoException(MSG_PLEASE_MODIFY));

        mockMvc.perform(put("/users/USER0001").with(csrf())
                        .param("password", "NewPass9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Alice", "Adminson", "A")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(MSG_PLEASE_MODIFY));
    }

    // -----------------------------------------------------------------
    // DELETE /users/{id} - delete user (COUSR03C / CU03); CSRF token required
    // -----------------------------------------------------------------

    /**
     * :purpose: A successful delete returns 204 No Content with an empty body and invokes
     *     the service exactly once for the path id.
     */
    @Test
    @DisplayName("DELETE /users/{id} as ADMIN returns 204 No Content with an empty body")
    @WithMockUser(roles = "ADMIN")
    void deleteUserReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/users/USER0001").with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(userService).deleteUser("USER0001");
    }

    /**
     * :purpose: Deleting a missing user surfaces as {@link RecordNotFoundException}, mapped
     *     by the shared advice to HTTP 404.
     */
    @Test
    @DisplayName("DELETE /users/{id} as ADMIN for a missing user returns 404 (RecordNotFoundException)")
    @WithMockUser(roles = "ADMIN")
    void deleteUserMissingReturnsNotFound() throws Exception {
        willThrow(new RecordNotFoundException(MSG_USER_NOT_FOUND))
                .given(userService).deleteUser("USER9999");

        mockMvc.perform(delete("/users/USER9999").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        verify(userService).deleteUser("USER9999");
    }


    // -----------------------------------------------------------------
    // Role gating - administrator-only access (SecurityConfig filter chain)
    // -----------------------------------------------------------------

    /**
     * :purpose: A ROLE_USER principal is denied the list endpoint with 403 and the service
     *     is never reached.
     */
    @Test
    @DisplayName("GET /users as USER is forbidden (403) and never reaches the service")
    @WithMockUser(roles = "USER")
    void listUsersForbiddenForUserRole() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    /**
     * :purpose: A ROLE_USER principal is denied the add endpoint with 403 (CSRF satisfied so
     *     denial is by role, not token) and the service is never reached.
     */
    @Test
    @DisplayName("POST /users as USER is forbidden (403) and never reaches the service")
    @WithMockUser(roles = "USER")
    void addUserForbiddenForUserRole() throws Exception {
        mockMvc.perform(post("/users").with(csrf())
                        .param("password", "Pass1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addUserJson("USER0009", "Judy", "Jones", "U")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    /**
     * :purpose: A ROLE_USER principal is denied the delete endpoint with 403 (CSRF satisfied
     *     so denial is by role, not token) and the service is never reached.
     */
    @Test
    @DisplayName("DELETE /users/{id} as USER is forbidden (403) and never reaches the service")
    @WithMockUser(roles = "USER")
    void deleteUserForbiddenForUserRole() throws Exception {
        mockMvc.perform(delete("/users/USER0001").with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    /**
     * :purpose: An unauthenticated caller is rejected on the list endpoint with the single
     *     authoritative status the chain produces — 403. The chain declares no
     *     {@code AuthenticationEntryPoint} (HTTP Basic and form login are both disabled), so
     *     Spring Security's {@code Http403ForbiddenEntryPoint} applies and 401 is never
     *     produced; asserting one status rather than a 401/403 pair is what lets the test
     *     detect a regression that flips the rejection semantics. The response body stays
     *     empty so nothing about the application is disclosed, and the service is never reached.
     */
    @Test
    @DisplayName("GET /users unauthenticated -> 403 with an empty body and no service call")
    void listUsersUnauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());

        verifyNoInteractions(userService);
    }

    /**
     * :purpose: An unauthenticated caller is rejected on a mutation with the same single
     *     authoritative 403 (CSRF satisfied, so the denial is by authentication rather than by
     *     token) and an empty body, and the service is never reached.
     */
    @Test
    @DisplayName("POST /users unauthenticated -> 403 with an empty body and no service call")
    void addUserUnauthenticatedIsRejected() throws Exception {
        mockMvc.perform(post("/users").with(csrf())
                        .param("password", "Pass1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addUserJson("USER0010", "Karl", "King", "U")))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());

        verifyNoInteractions(userService);
    }

    /**
     * :purpose: The remaining mutations are rejected with the same authoritative 403 when
     *     unauthenticated, so the ADMIN gate covers every verb of the endpoint surface rather
     *     than only the two the suite previously exercised.
     */
    @Test
    @DisplayName("PUT and DELETE /users/{id} unauthenticated -> 403 and no service call")
    void updateAndDeleteUnauthenticatedAreRejected() throws Exception {
        mockMvc.perform(put("/users/USER0001").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateUserJson("Karl", "King", "U")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/users/USER0001").with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    /**
     * :purpose: The browser-hardening response headers Spring Security writes accompany a
     *     rejected request: ``nosniff``, frame denial, the no-store cache directives, the
     *     legacy XSS auditor switched off, and the matching ``Pragma``/``Expires`` pair. These
     *     were observable in the running service but asserted by no test.
     */
    @Test
    @DisplayName("security response headers are emitted on the rejected request")
    void securityResponseHeadersArePresentOnRejection() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"))
                .andExpect(header().string("Cache-Control",
                        "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"));
    }

    /**
     * :purpose: The same hardening headers accompany an authorized, successful response, so the
     *     protections are not limited to rejections.
     */
    @Test
    @DisplayName("security response headers are emitted on an authorized response")
    @WithMockUser(roles = "ADMIN")
    void securityResponseHeadersArePresentOnAuthorizedResponse() throws Exception {
        given(userService.listUsers(0)).willReturn(listResponse());

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"));
    }

}

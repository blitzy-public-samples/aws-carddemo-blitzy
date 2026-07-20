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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
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

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.UserListRequest;
import com.aws.carddemo.mapper.UserMapper;
import com.aws.carddemo.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
 * &#64;WebMvcTest} slice test for {@link UserListController} &mdash; the Java
 * re-platform of the legacy CICS online program {@code COUSR00C} (transaction
 * {@code CU00}, BMS map {@code COUSR00}), the administrator-only
 * <em>List Users</em> screen.
 *
 * <h2>What this test locks down</h2>
 * <p>The test exercises the controller through the real Spring MVC + Spring
 * Security stack (filters on), with the collaborators mocked, to verify the
 * caller-visible REST contract that preserves the legacy behavior (AAP
 * &sect;0.7.1 H1, &sect;0.7.1 H5, &sect;0.7.3 L1, &sect;0.9.2, &sect;0.9.3):</p>
 * <ul>
 *   <li><strong>Authorization matrix</strong> &mdash; the URL rule
 *       {@code /api/v1/admin/**} &rarr; {@code hasRole("ADMIN")} together with the
 *       class-level {@code @PreAuthorize("hasRole('ADMIN')")}: an anonymous caller
 *       receives {@code 401}, a {@code USER}-role caller {@code 403} (and the
 *       service is never consulted), and an {@code ADMIN}-role caller {@code 200}.</li>
 *   <li><strong>Paging parity</strong> &mdash; the ten-row page window
 *       ({@code PAGE_SIZE = 10}) sorted by {@code secUsrId}, a 1-based page number
 *       in the response, and the {@code PF7}/{@code PF8} backward/forward paging
 *       with the legacy top/bottom boundary messages.</li>
 *   <li><strong>Row selection navigation</strong> &mdash; a {@code 'U'} marker
 *       routes to Update User ({@code COUSR02C}/{@code CU02}) and a {@code 'D'}
 *       marker to Delete User ({@code COUSR03C}/{@code CU03}), each carrying the
 *       selected user id; an invalid marker re-displays the page with the legacy
 *       message and performs no navigation.</li>
 *   <li><strong>PF-key semantics</strong> &mdash; {@code PF3} returns to the Admin
 *       Menu ({@code COADM01C}/{@code CA00}); any other attention key yields the
 *       invalid-key message.</li>
 *   <li><strong>Password is never present</strong> &mdash; no list or navigation
 *       response body may contain a password property or the stub credential
 *       value; the {@code UserListRow} projection has no password field.</li>
 *   <li><strong>Field-contract parity</strong> &mdash; the response and each row
 *       carry exactly the {@code COUSR00} copybook field names.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The slice loads only {@link UserListController}; {@link SecurityConfig} and
 * the real {@link UserMapper} are imported so the genuine authorization rules and
 * the genuine (password-free) row projection are exercised. {@link UserService}
 * is replaced by a Mockito mock via {@link MockitoBean}. CSRF is disabled by the
 * imported security configuration, so the browse-submit {@code POST} needs no
 * token, and method security ({@code @EnableMethodSecurity}) is active.</p>
 *
 * <p>The mock is programmed with a single coherent {@link Answer} that mirrors the
 * real {@link UserService#listUsers(String, Pageable)} windowing (ascending id
 * order, inclusive start-key filter, offset/size page window). This keeps every
 * internal call the controller makes &mdash; the size-1 total-count probe and the
 * size-10 page fetch &mdash; mutually consistent, so the derived page numbers and
 * boundary conditions are exact rather than hand-stubbed per call. Each stub user
 * is given a sentinel password to prove it never surfaces.</p>
 */
@WebMvcTest(UserListController.class)
@Import({SecurityConfig.class, UserMapper.class})
class UserListControllerTest {

    /** Collection base path &mdash; the initial-display {@code GET} endpoint. */
    private static final String BASE_PATH = "/api/v1/admin/users";

    /** Browse-submit path &mdash; the {@code POST} endpoint that routes on the attention key. */
    private static final String LIST_PATH = "/api/v1/admin/users/list";

    /** Response header naming the next program to navigate to (legacy {@code XCTL} target). */
    private static final String HDR_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header naming the next transaction to navigate to. */
    private static final String HDR_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** Response header carrying the selected user id passed to the update/delete screen. */
    private static final String HDR_SELECTED_USER_ID = "X-CardDemo-Selected-User-Id";

    /** Update-User target program / transaction (row {@code 'U'} selection). */
    private static final String PROGRAM_UPDATE = "COUSR02C";
    private static final String TRANSACTION_UPDATE = "CU02";

    /** Delete-User target program / transaction (row {@code 'D'} selection). */
    private static final String PROGRAM_DELETE = "COUSR03C";
    private static final String TRANSACTION_DELETE = "CU03";

    /** Admin-Menu target program / transaction (PF3 back). */
    private static final String PROGRAM_ADMIN_MENU = "COADM01C";
    private static final String TRANSACTION_ADMIN_MENU = "CA00";

    /** This program's identifier and transaction id shown in the screen header. */
    private static final String PROGRAM_ID = "COUSR00C";
    private static final String TRANSACTION_ID = "CU00";

    /** Invalid row-selection message (legacy {@code COUSR00C} line 212). */
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid values are U and D";

    /** Invalid attention-key message ({@code CCDA-MSG-INVALID-KEY} from {@code CSMSG01Y}). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Top-of-list boundary message (legacy {@code COUSR00C} line 251). */
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";

    /** Bottom-of-list boundary message (legacy {@code COUSR00C} line 273). */
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";

    /**
     * Sentinel credential assigned to every stub user. It is deliberately
     * conspicuous (and not a real secret) so that any accidental exposure on a
     * response body is trivially detectable by {@link #assertNoPasswordLeak(String)}.
     */
    private static final String STUB_PASSWORD = "PLAINTEXT-PWD-SHOULD-NEVER-LEAK";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    // ------------------------------------------------------------------
    // A. AUTHORIZATION MATRIX
    // ------------------------------------------------------------------

    /**
     * A1 &mdash; an unauthenticated request to the admin URL is rejected with
     * {@code 401 Unauthorized} by the security filter chain, before the controller
     * runs, so the service is never consulted.
     */
    @Test
    @DisplayName("A1: unauthenticated GET is 401 and the service is never consulted")
    void unauthenticatedRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    /**
     * A2 &mdash; an authenticated {@code USER}-role principal is denied with
     * {@code 403 Forbidden} (List Users is admin-only). The denial happens in the
     * filter chain / method-security layer, so the service is never consulted.
     */
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("A2: a USER-role principal is forbidden (403); service not invoked")
    void userRoleIsForbidden() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    /**
     * A3 &mdash; an authenticated {@code ADMIN}-role principal is authorized and
     * receives the first page ({@code 200 OK}).
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("A3: an ADMIN-role principal receives the first page (200)")
    void adminRoleIsAuthorized() throws Exception {
        givenUsers(users(10));

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // B. FIRST PAGE (ADMIN)
    // ------------------------------------------------------------------

    /**
     * B &mdash; the initial display returns up to ten rows in a 1-based page ("1"),
     * exposing exactly the {@code userId}/{@code firstName}/{@code lastName}/
     * {@code userType} columns, and requests a page of size ten sorted ascending by
     * {@code secUsrId}. The password never appears on the body.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("B: first page returns <=10 rows, pageNumber '1', size-10 sort secUsrId")
    void firstPageReturnsTenRowsAndCorrectPageable() throws Exception {
        givenUsers(users(10));

        String body = mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.users.length()").value(10))
                .andExpect(jsonPath("$.users[0].userId").value("USER0001"))
                .andExpect(jsonPath("$.users[0].firstName").value("First0001"))
                .andExpect(jsonPath("$.users[0].lastName").value("Last0001"))
                .andExpect(jsonPath("$.users[0].userType").value("U"))
                .andExpect(jsonPath("$.users[0].password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // The single service call must request a size-10 window from the start,
        // sorted ascending by secUsrId, with no start-key filter.
        ArgumentCaptor<String> startCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userService).listUsers(startCaptor.capture(), pageableCaptor.capture());

        assertThat(startCaptor.getValue()).isNull();
        Pageable requested = pageableCaptor.getValue();
        assertThat(requested.getPageSize()).isEqualTo(10);
        assertThat(requested.getPageNumber()).isZero();
        var order = requested.getSort().getOrderFor("secUsrId");
        assertThat(order).isNotNull();
        assertThat(order.isAscending()).isTrue();

        assertNoPasswordLeak(body);
    }

    // ------------------------------------------------------------------
    // C. START-KEY FILTER
    // ------------------------------------------------------------------

    /**
     * C &mdash; an {@code ENTER} submit carrying a {@code userId} start filter
     * repositions the browse: the captured {@code startUserId} equals the request
     * value (legacy {@code STARTBR RIDFLD(SEC-USR-ID)}).
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("C: ENTER with a start-key filter positions the browse at the supplied user id")
    void startFilterIsPassedThroughAsStartKey() throws Exception {
        givenUsers(users(25));
        UserListRequest request = new UserListRequest("USER0005", List.of(), PfKeyAction.ENTER);

        mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("1"));

        ArgumentCaptor<String> startCaptor = ArgumentCaptor.forClass(String.class);
        verify(userService).listUsers(startCaptor.capture(), any(Pageable.class));
        assertThat(startCaptor.getValue()).isEqualTo("USER0005");
    }

    // ------------------------------------------------------------------
    // D. PAGING: PF8 FORWARD / PF7 BACKWARD (with boundaries)
    // ------------------------------------------------------------------

    /**
     * D1 &mdash; {@code PF8} pages forward. With twenty-five users the operator on
     * page one (anchor {@code USER0001}) moves to page two: the response reports
     * the 1-based page number "2", a full ten-row window, and no boundary message.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("D1: PF8 pages forward from page one to page two (1-based)")
    void pf8PagesForward() throws Exception {
        givenUsers(users(25));
        UserListRequest request = new UserListRequest("USER0001", List.of(), PfKeyAction.PF8);

        String body = mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("2"))
                .andExpect(jsonPath("$.users.length()").value(10))
                .andReturn().getResponse().getContentAsString();

        // A non-boundary forward page carries no "already at the ..." message.
        assertThat(body).doesNotContain(MSG_ALREADY_BOTTOM);
        assertThat(body).doesNotContain(MSG_ALREADY_TOP);
    }

    /**
     * D2 &mdash; {@code PF7} pages backward. The operator on page two (anchor
     * {@code USER0011}) moves back to page one: the response reports "1", a full
     * ten-row window, and no boundary message.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("D2: PF7 pages backward from page two to page one (1-based)")
    void pf7PagesBackward() throws Exception {
        givenUsers(users(25));
        UserListRequest request = new UserListRequest("USER0011", List.of(), PfKeyAction.PF7);

        String body = mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.users.length()").value(10))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(MSG_ALREADY_TOP);
        assertThat(body).doesNotContain(MSG_ALREADY_BOTTOM);
    }

    /**
     * D3 &mdash; {@code PF7} at the top of the list re-displays page one with the
     * legacy top-boundary message (COUSR00C {@code PROCESS-PF7-KEY}). A blank anchor
     * resolves to page index zero.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("D3: PF7 at the top re-displays page one with the top-boundary message")
    void pf7AtTopBoundary() throws Exception {
        givenUsers(users(25));
        UserListRequest request = new UserListRequest("", List.of(), PfKeyAction.PF7);

        mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.errorMessage").value(MSG_ALREADY_TOP));
    }

    /**
     * D4 &mdash; {@code PF8} at the bottom of the list re-displays the last page
     * (page three of twenty-five, anchor {@code USER0021}) with the legacy
     * bottom-boundary message (COUSR00C {@code PROCESS-PF8-KEY}).
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("D4: PF8 at the bottom re-displays the last page with the bottom-boundary message")
    void pf8AtBottomBoundary() throws Exception {
        givenUsers(users(25));
        UserListRequest request = new UserListRequest("USER0021", List.of(), PfKeyAction.PF8);

        mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("3"))
                .andExpect(jsonPath("$.users.length()").value(5))
                .andExpect(jsonPath("$.errorMessage").value(MSG_ALREADY_BOTTOM));
    }

    // ------------------------------------------------------------------
    // E / F / G. ROW SELECTION ('U' update, 'D' delete, invalid)
    // ------------------------------------------------------------------

    /**
     * E &mdash; a {@code 'U'} row marker navigates to Update User
     * ({@code COUSR02C}/{@code CU02}) carrying the selected user id. The marker at
     * index one selects the second displayed row ({@code USER0002}).
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("E: a 'U' selection navigates to Update User (COUSR02C/CU02) with the selected id")
    void rowSelectionUpdateNavigates() throws Exception {
        givenUsers(users(10));
        UserListRequest request = new UserListRequest(null, List.of("", "U"), PfKeyAction.ENTER);

        String body = mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HDR_NEXT_PROGRAM, PROGRAM_UPDATE))
                .andExpect(header().string(HDR_NEXT_TRANSACTION, TRANSACTION_UPDATE))
                .andExpect(header().string(HDR_SELECTED_USER_ID, "USER0002"))
                .andReturn().getResponse().getContentAsString();

        assertNoPasswordLeak(body);
    }

    /**
     * F &mdash; a {@code 'D'} row marker navigates to Delete User
     * ({@code COUSR03C}/{@code CU03}) carrying the selected user id. The marker at
     * index zero selects the first displayed row ({@code USER0001}).
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("F: a 'D' selection navigates to Delete User (COUSR03C/CU03) with the selected id")
    void rowSelectionDeleteNavigates() throws Exception {
        givenUsers(users(10));
        UserListRequest request = new UserListRequest(null, List.of("D"), PfKeyAction.ENTER);

        String body = mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HDR_NEXT_PROGRAM, PROGRAM_DELETE))
                .andExpect(header().string(HDR_NEXT_TRANSACTION, TRANSACTION_DELETE))
                .andExpect(header().string(HDR_SELECTED_USER_ID, "USER0001"))
                .andReturn().getResponse().getContentAsString();

        assertNoPasswordLeak(body);
    }

    /**
     * G &mdash; a non-blank marker that is neither {@code 'U'} nor {@code 'D'}
     * re-displays the page with the legacy invalid-selection message and performs
     * no navigation (no navigation headers are emitted).
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("G: an invalid selection marker yields the invalid-selection message and no navigation")
    void invalidRowSelectionMessage() throws Exception {
        givenUsers(users(10));
        UserListRequest request = new UserListRequest(null, List.of("X"), PfKeyAction.ENTER);

        mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_SELECTION))
                .andExpect(jsonPath("$.users.length()").value(10))
                .andExpect(header().doesNotExist(HDR_NEXT_PROGRAM))
                .andExpect(header().doesNotExist(HDR_NEXT_TRANSACTION))
                .andExpect(header().doesNotExist(HDR_SELECTED_USER_ID));
    }

    // ------------------------------------------------------------------
    // H / I. PF-KEY SEMANTICS (PF3 back, other/unmapped key)
    // ------------------------------------------------------------------

    /**
     * H &mdash; {@code PF3} returns to the Admin Menu ({@code COADM01C}/{@code CA00})
     * via navigation headers with an empty-list body; the browse is not re-run, so
     * the service is never consulted.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("H: PF3 navigates back to the Admin Menu (COADM01C/CA00); service not invoked")
    void pf3ReturnsToAdminMenu() throws Exception {
        UserListRequest request = new UserListRequest(null, List.of(), PfKeyAction.PF3);

        mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HDR_NEXT_PROGRAM, PROGRAM_ADMIN_MENU))
                .andExpect(header().string(HDR_NEXT_TRANSACTION, TRANSACTION_ADMIN_MENU))
                .andExpect(jsonPath("$.users.length()").value(0));

        verifyNoInteractions(userService);
    }

    /**
     * I &mdash; any attention key the screen does not honor ({@code WHEN OTHER})
     * yields the invalid-key message and does not re-browse the list, so the
     * service is never consulted.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("I: an unmapped attention key yields the invalid-key message; service not invoked")
    void unmappedKeyYieldsInvalidKeyMessage() throws Exception {
        UserListRequest request = new UserListRequest(null, List.of(), PfKeyAction.PF9);

        mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_KEY))
                .andExpect(jsonPath("$.users.length()").value(0))
                .andExpect(header().doesNotExist(HDR_NEXT_PROGRAM));

        verifyNoInteractions(userService);
    }

    // ------------------------------------------------------------------
    // J. PASSWORD IS NEVER PRESENT (MANDATORY)
    // ------------------------------------------------------------------

    /**
     * J &mdash; the mandatory sensitive-data rule (AAP &sect;0.9.3): neither a list
     * page nor a navigation response may carry a password. Every stub user holds a
     * conspicuous sentinel credential; the assertion proves that value (and any
     * {@code password}/{@code secUsrPwd} property) is absent from both a list body
     * and a row-selection navigation body, and that the row object exposes no
     * password property.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("J: no list or navigation response ever exposes a password (MANDATORY)")
    void passwordIsNeverPresentInAnyResponse() throws Exception {
        givenUsers(users(10));

        String listBody = mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.users[0].password").doesNotExist())
                .andExpect(jsonPath("$.users[0].secUsrPwd").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertNoPasswordLeak(listBody);

        // A navigation response echoes the current page as its body; it too must be
        // free of any credential.
        UserListRequest navRequest = new UserListRequest(null, List.of("U"), PfKeyAction.ENTER);
        String navBody = mockMvc.perform(post(LIST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(navRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].password").doesNotExist())
                .andExpect(jsonPath("$.users[0].secUsrPwd").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertNoPasswordLeak(navBody);
    }

    // ------------------------------------------------------------------
    // K. FIELD-CONTRACT PARITY (COUSR00 copybook)
    // ------------------------------------------------------------------

    /**
     * K &mdash; the response and each row preserve the {@code COUSR00} copybook
     * field contract: the header fields ({@code transactionName} = {@code CU00},
     * {@code programName} = {@code COUSR00C}, the two title lines, current date and
     * time, and the page number), the {@code users} array, and per-row exactly
     * {@code userId}/{@code firstName}/{@code lastName}/{@code userType} with no
     * password.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("K: response and row field names match the COUSR00 copybook contract")
    void responseFieldContractMatchesCopybook() throws Exception {
        givenUsers(users(10));

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                // Screen header contract (TRNNAMEO / PGMNAMEO / TITLE / DATE / TIME / PAGENUM).
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_ID))
                .andExpect(jsonPath("$.programName").value(PROGRAM_ID))
                .andExpect(jsonPath("$.title01").exists())
                .andExpect(jsonPath("$.title02").exists())
                .andExpect(jsonPath("$.currentDate").exists())
                .andExpect(jsonPath("$.currentTime").exists())
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.users").isArray())
                // Repeating row group contract (USRIDnn / FNAMEnn / LNAMEnn / UTYPEnn).
                .andExpect(jsonPath("$.users[0].userId").exists())
                .andExpect(jsonPath("$.users[0].firstName").exists())
                .andExpect(jsonPath("$.users[0].lastName").exists())
                .andExpect(jsonPath("$.users[0].userType").exists())
                .andExpect(jsonPath("$.users[0].password").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Test fixtures and helpers
    // ------------------------------------------------------------------

    /**
     * Programs the mocked {@link UserService} with a pair of coherent answers that
     * mirror the real service: {@link UserService#listUsers(String, Pageable)}
     * returns the requested database page window and
     * {@link UserService#countUsers(String)} returns the matching total. Both are
     * derived from one consistent data set, so the controller's page fetch and its
     * {@code PF7}/{@code PF8} position counts always agree.
     *
     * @param all the full, ascending-ordered set of users the "store" contains
     */
    private void givenUsers(List<UserSecurity> all) {
        when(userService.listUsers(any(), any(Pageable.class))).thenAnswer(pagedAnswer(all));
        when(userService.countUsers(any())).thenAnswer(countAnswer(all));
    }

    /**
     * Builds a coherent {@link Answer} reproducing the production page fetch: an
     * inclusive, upper-cased start-key filter over an ascending-ordered set, then
     * the requested offset/size window returned as a {@link PageImpl} that carries
     * the requested {@link Pageable} (so {@code getNumber()} reflects the requested
     * page and the controller's 1-based page number is exact). This mirrors the
     * database-side {@code LIMIT}/{@code OFFSET} paging the real service now performs.
     *
     * @param all the full ordered set of users
     * @return an answer that windows {@code all} per the invocation's arguments
     */
    private static Answer<Page<UserSecurity>> pagedAnswer(List<UserSecurity> all) {
        return invocation -> {
            String startKey = invocation.getArgument(0);
            Pageable pageable = invocation.getArgument(1);

            List<UserSecurity> ordered = filteredFrom(all, startKey);
            int total = ordered.size();
            int fromIndex = (int) Math.min(pageable.getOffset(), total);
            int toIndex = (int) Math.min((long) fromIndex + pageable.getPageSize(), total);
            return new PageImpl<>(List.copyOf(ordered.subList(fromIndex, toIndex)), pageable, total);
        };
    }

    /**
     * Builds an {@link Answer} for {@link UserService#countUsers(String)} that is
     * consistent with {@link #pagedAnswer(List)}: it returns the number of users at
     * or after the (inclusive, upper-cased) start key, or the grand total when the
     * key is {@code null}/blank. It lets the mocked service answer the controller's
     * {@code PF7}/{@code PF8} position counts from the same data set the page
     * fetches use, mirroring the efficient database {@code COUNT} the real service
     * now performs.
     *
     * @param all the full ordered set of users
     * @return an answer that counts {@code all} at or after the invocation's key
     */
    private static Answer<Long> countAnswer(List<UserSecurity> all) {
        return invocation -> (long) filteredFrom(all, invocation.getArgument(0)).size();
    }

    /**
     * Applies the inclusive, upper-cased start-key filter shared by
     * {@link #pagedAnswer(List)} and {@link #countAnswer(List)}: when {@code startKey}
     * is {@code null}/blank the full set is returned; otherwise only the users whose
     * id is greater than or equal to the normalized key are kept, reproducing the
     * legacy {@code STARTBR} reposition.
     *
     * @param all      the full ascending-ordered set of users
     * @param startKey the optional inclusive start key
     * @return the (possibly filtered) ascending-ordered users
     */
    private static List<UserSecurity> filteredFrom(List<UserSecurity> all, String startKey) {
        if (startKey == null || startKey.isBlank()) {
            return all;
        }
        String seekKey = startKey.trim().toUpperCase(Locale.ROOT);
        return all.stream()
                .filter(candidate -> candidate.getSecUsrId() != null
                        && candidate.getSecUsrId().compareTo(seekKey) >= 0)
                .toList();
    }

    /**
     * Creates {@code count} deterministic stub users with ascending, fixed-width
     * ids ({@code USER0001}, {@code USER0002}, &hellip;), simple names, an
     * alternating {@code 'U'}/{@code 'A'} role, and the conspicuous sentinel
     * {@link #STUB_PASSWORD} as the credential.
     *
     * @param count the number of users to create (at least one)
     * @return a mutable list of stub users in ascending id order
     */
    private static List<UserSecurity> users(int count) {
        List<UserSecurity> list = new ArrayList<>(count);
        for (int n = 1; n <= count; n++) {
            String type = (n % 2 == 0) ? "A" : "U";
            list.add(new UserSecurity(
                    String.format(Locale.ROOT, "USER%04d", n),
                    String.format(Locale.ROOT, "First%04d", n),
                    String.format(Locale.ROOT, "Last%04d", n),
                    STUB_PASSWORD,
                    type));
        }
        return list;
    }

    /**
     * Serializes a request DTO to JSON using the application {@link ObjectMapper}.
     *
     * @param request the request payload to serialize
     * @return the JSON representation
     * @throws Exception if serialization fails
     */
    private String json(UserListRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    /**
     * Asserts that a response body contains no credential in any form: neither a
     * {@code password} nor a {@code secUsrPwd} property (checked case-insensitively)
     * nor the sentinel {@link #STUB_PASSWORD} value.
     *
     * @param body the raw response body to inspect
     */
    private static void assertNoPasswordLeak(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("password");
        assertThat(lower).doesNotContain("secusrpwd");
        assertThat(body).doesNotContain(STUB_PASSWORD);
    }
}

package com.aws.carddemo.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.TestCredentials;
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COUSR02Form;
import com.aws.carddemo.dto.screen.COUSR03Form;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * Full Failsafe ({@code *IT}, Maven {@code verify} phase) integration test for
 * {@link UserAdminController} — the single Spring MVC controller that merges the four CICS
 * online user-administration transactions of the AWS CardDemo mainframe application. Each
 * assertion is grounded in the behavior of a COBOL oracle preserved read-only under
 * {@code legacy/} (§0.6.10 traceability):
 *
 * <ul>
 *   <li><b>CU00</b> → {@code GET/POST /admin/users} — oracle {@code legacy/cbl/COUSR00C.cbl} —
 *       paginated user list, page size exactly {@code 10}, BMS map / view name {@code COUSR00}.</li>
 *   <li><b>CU01</b> → {@code GET/POST /admin/users/add} — oracle {@code legacy/cbl/COUSR01C.cbl} —
 *       add user, view name {@code COUSR01}.</li>
 *   <li><b>CU02</b> → {@code GET/POST /admin/users/update} — oracle {@code legacy/cbl/COUSR02C.cbl} —
 *       update user, view name {@code COUSR02}.</li>
 *   <li><b>CU03</b> → {@code GET/POST /admin/users/delete} — oracle {@code legacy/cbl/COUSR03C.cbl} —
 *       <em>two-step</em> delete (ENTER looks up + prompts, PF5 confirms), view name {@code COUSR03}.</li>
 * </ul>
 *
 * <p>The headline contract verified here is authorization parity: every {@code /admin/**} route is
 * gated by {@link com.aws.carddemo.config.SecurityConfig} to {@code hasAuthority("ROLE_ADMIN")},
 * reproducing the mainframe RACF transaction-level protection. The {@code ROLE_USER → 403} /
 * {@code anonymous → redirect to /signon} / {@code ROLE_ADMIN → 200} triad is asserted for all four
 * routes.</p>
 *
 * <p>This is a real end-to-end integration test: it drives the production
 * {@link com.aws.carddemo.service.online.UserListService},
 * {@link com.aws.carddemo.service.online.UserAddService},
 * {@link com.aws.carddemo.service.online.UserUpdateService} and
 * {@link com.aws.carddemo.service.online.UserDeleteService} against a real
 * {@link com.aws.carddemo.repository.UserSecurityRepository} over a Testcontainers PostgreSQL
 * instance whose schema and pristine seed (USRSEC {@code ADMIN001-005} type {@code A},
 * {@code USER0001-005} type {@code U}, all cleartext password {@code PASSWORD}) are installed by
 * Flyway {@code V1 → V2 → V3} before each test. There is no Mockito and no {@code @MockBean}.</p>
 *
 * <p>Cleartext-password parity is preserved deliberately (no hashing) — the only credentials that
 * appear in this test are the seeded fixture users. Mutating scenarios (add / update / delete) are
 * annotated {@link Transactional} so their writes roll back and the seed stays pristine, in addition
 * to the per-test Flyway reset performed by {@link AbstractPostgresIntegrationTest}.</p>
 *
 * @see UserAdminController
 */
@AutoConfigureMockMvc
class UserAdminControllerIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Real {@link UserSecurityRepository} used by the finding-F12 adversarial tests to assert the
     * <em>committed</em> USRSEC state after a rejected/replayed confirmation (those tests are not
     * {@code @Transactional}; the per-test Flyway clean+migrate resets the seed).
     */
    @Autowired
    private UserSecurityRepository userSecurityRepository;

    /**
     * A forged confirmation nonce (64 hex zeros) that matches the token width but no armed value;
     * used by the finding-F12 forged-token tests.
     */
    private static final String FORGED_TOKEN =
            "0000000000000000000000000000000000000000000000000000000000000000";

    /** Fragment of the web-tier confirmation-integrity banner (review finding F12). */
    private static final String CONFIRM_INTEGRITY_FRAGMENT = "Confirmation could not be validated";

    /**
     * Session attribute under which the session-scoped {@link CardDemoContext} proxy stores its
     * real target. Seeding this attribute reproduces a live pseudo-conversational session so the
     * controller reaches its screen logic instead of bouncing an uninitialized context to
     * {@code /signon}. (The unqualified {@code "cardDemoContext"} name does NOT work.)
     */
    private static final String CONTEXT_SESSION_ATTRIBUTE = "scopedTarget.cardDemoContext";

    // Review finding #5: the seed password is externalized (CARDDEMO_SEED_PASSWORD env var, no
    // committed default) and read here for the real signon POSTs that reach the admin screens.
    private static final String SEEDED_PASSWORD = TestCredentials.seedPassword();

    /** BMS map names preserved one-for-one as Thymeleaf view names. */
    private static final String VIEW_LIST = "COUSR00";
    private static final String VIEW_ADD = "COUSR01";
    private static final String VIEW_UPDATE = "COUSR02";
    private static final String VIEW_DELETE = "COUSR03";

    /** Routes (one per CICS transaction id), all under the admin-only {@code /admin/**} surface. */
    private static final String ROUTE_LIST = "/admin/users";
    private static final String ROUTE_ADD = "/admin/users/add";
    private static final String ROUTE_UPDATE = "/admin/users/update";
    private static final String ROUTE_DELETE = "/admin/users/delete";

    /** PF3 (list) and PF12 (update/delete) return to the admin menu, NOT the user menu (parity). */
    private static final String ADMIN_MENU = "/admin/menu";

    // ---------------------------------------------------------------------------------------------
    // Session priming helpers
    //
    // Online CardDemo programs are pseudo-conversational: an uninitialized CardDemoContext reports
    // isNew()==true and every service mainEntry bounces such a request back to /signon. A live
    // session is therefore required before the controller will render a screen. Seeding the context
    // directly is faithful to the runtime (it is exactly the state a prior signon would leave) and
    // keeps the authorization assertions — which run WITHOUT a seeded session — as the honest proof
    // of the security gate.
    // ---------------------------------------------------------------------------------------------

    private CardDemoContext buildAdminContext(boolean reenter) {
        CardDemoContext context = new CardDemoContext();
        context.setAdmin();
        context.markInitialized();
        if (reenter) {
            context.markReenter();
        }
        return context;
    }

    /**
     * Reenter-state admin session (pgmContext = re-enter). Drives deterministic {@code switch(aid)}
     * dispatch for POST handlers (row selection, PF-keys, ENTER fetch/save/delete).
     */
    private MockHttpSession adminReenterSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONTEXT_SESSION_ATTRIBUTE, buildAdminContext(true));
        return session;
    }

    /**
     * First-entry admin session (pgmContext = enter). Yields the clean initial screen render for the
     * update/delete GET screens (no spurious "User ID can NOT be empty" message).
     */
    private MockHttpSession adminFirstEntrySession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONTEXT_SESSION_ATTRIBUTE, buildAdminContext(false));
        return session;
    }

    // =============================================================================================
    // Phase 1 — Authorization contract (ALL /admin/** are admin-only). Headline of this class.
    // =============================================================================================

    @ParameterizedTest(name = "ROLE_USER GET {0} -> 403")
    @ValueSource(strings = {ROUTE_LIST, ROUTE_ADD, ROUTE_UPDATE, ROUTE_DELETE})
    @WithMockUser(username = "USER0001", roles = "USER")
    @DisplayName("All /admin/** user-admin routes are forbidden for ROLE_USER (RACF parity)")
    void userAdminRoutesForbiddenForRoleUser(String route) throws Exception {
        mockMvc.perform(get(route))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "anonymous GET {0} -> redirect **/signon")
    @ValueSource(strings = {ROUTE_LIST, ROUTE_ADD, ROUTE_UPDATE, ROUTE_DELETE})
    @DisplayName("All /admin/** user-admin routes redirect an anonymous caller to the signon screen")
    void userAdminRoutesRedirectAnonymous(String route) throws Exception {
        mockMvc.perform(get(route))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/signon"));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU00: ROLE_ADMIN sees the user-list screen (COUSR00)")
    void userListScreenForAdmin() throws Exception {
        mockMvc.perform(get(ROUTE_LIST).session(adminFirstEntrySession()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU01: ROLE_ADMIN sees the add-user screen (COUSR01)")
    void userAddScreenForAdmin() throws Exception {
        // The add GET builds an empty form and calls no service, so it is context-independent.
        mockMvc.perform(get(ROUTE_ADD))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ADD));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU02: ROLE_ADMIN sees the update-user screen (COUSR02)")
    void userUpdateScreenForAdmin() throws Exception {
        mockMvc.perform(get(ROUTE_UPDATE).session(adminFirstEntrySession()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU03: ROLE_ADMIN sees the delete-user screen (COUSR03)")
    void userDeleteScreenForAdmin() throws Exception {
        mockMvc.perform(get(ROUTE_DELETE).session(adminFirstEntrySession()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DELETE));
    }

    // =============================================================================================
    // Phase 2 — CU00 list (COUSR00C): page size 10; row 'U' -> update, 'D' -> delete;
    //           PF3 -> /admin/menu; PF7/PF8 paging; invalid selection re-render.
    // =============================================================================================

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU00: list renders up to ten rows on one page (COUSR00)")
    void userListShowsTenRows() throws Exception {
        // The seed holds exactly 10 users, so page 1 fills every one of the form's 10 fixed row
        // slots (usrid01..usrid10). The structural 10-slot form guarantees "at most 10 per page";
        // asserting the first row is populated proves rows were rendered.
        mockMvc.perform(get(ROUTE_LIST).session(adminFirstEntrySession()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST))
                .andExpect(model().attribute("form", hasProperty("usrid01", containsString("001"))));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU00: row action 'U' navigates to the update screen")
    void userListRowSelectUNavigatesToUpdate() throws Exception {
        // The rendered list shows user ids as read-only cells that are not re-submitted; the
        // controller restores them from the session, so the row must first be populated via GET.
        MockHttpSession session = adminReenterSession();
        mockMvc.perform(get(ROUTE_LIST).session(session)).andReturn();

        mockMvc.perform(post(ROUTE_LIST).session(session)
                        .param("pfkey", "ENTER")
                        .param("sel0001", "U")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ROUTE_UPDATE));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU00: row action 'D' navigates to the delete screen")
    void userListRowSelectDNavigatesToDelete() throws Exception {
        MockHttpSession session = adminReenterSession();
        mockMvc.perform(get(ROUTE_LIST).session(session)).andReturn();

        mockMvc.perform(post(ROUTE_LIST).session(session)
                        .param("pfkey", "ENTER")
                        .param("sel0001", "D")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ROUTE_DELETE));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU00: an invalid row action re-renders the list (COUSR00)")
    void userListInvalidSelectionRerenders() throws Exception {
        // COBOL rejects any selection other than U/D ("Invalid selection. Valid values are U and
        // D"). With the 10-user seed the browse simultaneously reaches end-of-file, and the
        // end-of-page message overwrites the invalid-selection message (last-write-wins parity
        // quirk). The stable, faithful assertions are therefore status + view: the screen
        // re-renders rather than navigating away.
        MockHttpSession session = adminReenterSession();
        mockMvc.perform(get(ROUTE_LIST).session(session)).andReturn();

        mockMvc.perform(post(ROUTE_LIST).session(session)
                        .param("pfkey", "ENTER")
                        .param("sel0001", "X")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU00: PF8 then PF7 keep the user on the list screen (COUSR00)")
    void userListPf7Pf8Paging() throws Exception {
        MockHttpSession session = adminReenterSession();
        mockMvc.perform(get(ROUTE_LIST).session(session)).andReturn();

        // PF8 = page down. With a single page of results this is guarded ("already at the bottom").
        mockMvc.perform(post(ROUTE_LIST).session(session)
                        .param("pfkey", "PF8")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("bottom"))));

        // PF7 = page up. On page 1 this is guarded ("already at the top").
        mockMvc.perform(post(ROUTE_LIST).session(session)
                        .param("pfkey", "PF7")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("top"))));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU00: PF3 returns to the ADMIN menu, not the user menu (parity)")
    void userListPf3ReturnsToAdminMenu() throws Exception {
        // COUSR00C transfers control back to COADM01C (admin menu), which SecurityConfig maps to
        // /admin/menu — deliberately distinct from the ordinary user menu at /menu.
        mockMvc.perform(post(ROUTE_LIST).session(adminReenterSession())
                        .param("pfkey", "PF3")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ADMIN_MENU));
    }

    // =============================================================================================
    // Phase 3 — CU01 add (COUSR01C) / CU02 update (COUSR02C) / CU03 delete (COUSR03C).
    //           Mutating scenarios are @Transactional so the seed rolls back.
    // =============================================================================================

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @Transactional
    @DisplayName("CU01: a valid add creates the user and re-renders COUSR01 with a success message")
    void userAddValidCreatesUser() throws Exception {
        // Success is a re-rendered screen (SHOW_SCREEN) carrying the confirmation message, not a
        // redirect. The password is stored cleartext (parity — no hashing).
        mockMvc.perform(post(ROUTE_ADD).session(adminReenterSession())
                        .param("pfkey", "ENTER")
                        .param("fname", "New")
                        .param("lname", "User")
                        .param("userid", "NEWUSR01")
                        .param("passwd", SEEDED_PASSWORD)
                        .param("usrtype", "U")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ADD))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("has been added"))));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @Transactional
    @DisplayName("CU01: adding an existing user id raises DuplicateKeyException handled as 409")
    void userAddDuplicatePropagates() throws Exception {
        // COBOL DUPKEY on the write becomes a DuplicateKeyException that GlobalExceptionHandler
        // renders as HTTP 409 with the shared "error" view and an "errorMessage" model attribute —
        // it is NOT a COUSR01 form re-render.
        mockMvc.perform(post(ROUTE_ADD).session(adminReenterSession())
                        .param("pfkey", "ENTER")
                        .param("fname", "Dup")
                        .param("lname", "User")
                        .param("userid", "USER0001")
                        .param("passwd", SEEDED_PASSWORD)
                        .param("usrtype", "U")
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("errorMessage", containsString("already exist")));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU01: an empty required field re-renders COUSR01 with the field message")
    void userAddEmptyFieldRerenders() throws Exception {
        // One of five empty-field edits (FNAME/LNAME/USERID/PASSWD/USRTYPE); a blank first name
        // yields "First Name can NOT be empty...".
        mockMvc.perform(post(ROUTE_ADD).session(adminReenterSession())
                        .param("pfkey", "ENTER")
                        .param("fname", "")
                        .param("lname", "User")
                        .param("userid", "NEWUSR02")
                        .param("passwd", SEEDED_PASSWORD)
                        .param("usrtype", "U")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ADD))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("First Name can NOT be empty"))));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU01 (finding #11): an over-width field is rejected with the neutral length banner, no user created")
    void userAddOverWidthFieldRejectedNoCreate() throws Exception {
        // userid is PIC X(8) / @Size(max = 8); a 9-char value is only reachable by a crafted request
        // (COUSR01 pins maxlength="8"). @Valid + BindingResult must bounce it with the neutral length
        // banner BEFORE COUSR01C's own field edits run - so their message ordering is preserved - and
        // no USRSEC row is written. This also exercises the multi-form @InitBinder switch resolving the
        // bound target to COUSR01Form (the blank-field test above proves in-width edits still reach the
        // service, so validation restricts rather than replaces the COBOL edits).
        mockMvc.perform(post(ROUTE_ADD).session(adminReenterSession())
                        .param("pfkey", "ENTER")
                        .param("fname", "New")
                        .param("lname", "User")
                        .param("userid", "NINECHAR9")
                        .param("passwd", SEEDED_PASSWORD)
                        .param("usrtype", "U")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ADD))
                .andExpect(model().attribute("form",
                        hasProperty("errmsg", containsString("exceeds the maximum length"))));

        assertThat(userSecurityRepository.findByUsrId("NINECHAR9"))
                .as("an over-width add must not create any USRSEC row")
                .isEmpty();
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU01: PF4 clears the add screen (COUSR01)")
    void userAddPf4Clear() throws Exception {
        mockMvc.perform(post(ROUTE_ADD).session(adminReenterSession())
                        .param("pfkey", "PF4")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ADD));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU01: PF3 returns to the ADMIN menu (parity)")
    void userAddPf3ReturnsToAdminMenu() throws Exception {
        mockMvc.perform(post(ROUTE_ADD).session(adminReenterSession())
                        .param("pfkey", "PF3")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ADMIN_MENU));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU02: ENTER fetches an existing user and prompts for a PF5 save (COUSR02)")
    void userUpdateFetchExisting() throws Exception {
        // Fetching a seeded user populates the editable fields and prompts "Press PF5 key to save
        // your updates ...". USER0002 is used so the identities exercised elsewhere stay untouched.
        mockMvc.perform(post(ROUTE_UPDATE).session(adminReenterSession())
                        .param("pfkey", "ENTER")
                        .param("usridin", "USER0002")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                // USER0002 is seeded as "AJITH KUMAR"; a populated first name proves the fetch
                // loaded the record into the editable fields.
                .andExpect(model().attribute("form", hasProperty("fname", containsString("AJITH"))))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("PF5"))));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU02: an unknown user id re-renders COUSR02 inline with a NOT-found message")
    void userUpdateNotFoundRerenders() throws Exception {
        // Unlike the add-duplicate path, NOTFND on update is handled INLINE by the service (the
        // COBOL sets the on-screen message and re-displays), so the outcome is 200 + COUSR02 with
        // "User ID NOT found...", never a 404 / error view.
        mockMvc.perform(post(ROUTE_UPDATE).session(adminReenterSession())
                        .param("pfkey", "ENTER")
                        .param("usridin", "ZZZZZZZZ")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("NOT found"))));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @Transactional
    @DisplayName("CU02: PF5 saves a changed field and confirms the update (COUSR02)")
    void userUpdatePf5Save() throws Exception {
        // The fetch (ENTER) and the save (PF5) must share one session so the update targets the
        // record loaded in step one (COBOL USR-MODIFIED flag). The write rolls back via @Transactional.
        MockHttpSession session = adminReenterSession();
        // Step one (ENTER) fetches USER0002 and arms the confirmation nonce (finding F12); capture
        // the armed token off the redisplayed form so step two can present it, mirroring the hidden
        // field a browser round-trips. Without a valid token the PF5 save is rejected with no write.
        MvcResult fetch = mockMvc.perform(post(ROUTE_UPDATE).session(session)
                        .param("pfkey", "ENTER")
                        .param("usridin", "USER0002")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("PF5"))))
                .andReturn();
        String token = ((COUSR02Form) fetch.getModelAndView().getModel().get("form")).getConfirmToken();
        assertThat(token).as("the fetch turn must arm a confirmation token").isNotBlank();

        mockMvc.perform(post(ROUTE_UPDATE).session(session)
                        .param("pfkey", "PF5")
                        .param("usridin", "USER0002")
                        .param("confirmToken", token)
                        .param("fname", "Changed")
                        .param("lname", "Name")
                        .param("passwd", SEEDED_PASSWORD)
                        .param("usrtype", "U")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("has been updated"))));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU02: PF12 returns to the ADMIN menu (parity)")
    void userUpdatePf12ReturnsToAdminMenu() throws Exception {
        mockMvc.perform(post(ROUTE_UPDATE).session(adminReenterSession())
                        .param("pfkey", "PF12")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ADMIN_MENU));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @Transactional
    @DisplayName("CU03: two-step delete — ENTER prompts for PF5, PF5 confirms the delete (COUSR03)")
    void userDeleteTwoStepEnterThenPf5() throws Exception {
        // Step 1 (ENTER) looks the user up and prompts "Press PF5 key to delete this user ...".
        // Step 2 (PF5, same session) performs the delete and confirms "... has been deleted ...".
        // This two-step ENTER-then-PF5 flow is an explicit AAP parity requirement. USER0003 is used
        // and the delete rolls back via @Transactional.
        MockHttpSession session = adminReenterSession();

        // Step one (ENTER) looks USER0003 up, prompts for PF5, and arms the confirmation nonce
        // (finding F12); capture the armed token so step two can present it (the browser round-trips
        // it via the hidden field). Without a valid token the PF5 delete is rejected with no delete.
        MvcResult lookup = mockMvc.perform(post(ROUTE_DELETE).session(session)
                        .param("pfkey", "ENTER")
                        .param("usridin", "USER0003")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DELETE))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("PF5"))))
                .andReturn();
        String token = ((COUSR03Form) lookup.getModelAndView().getModel().get("form")).getConfirmToken();
        assertThat(token).as("the lookup turn must arm a confirmation token").isNotBlank();

        mockMvc.perform(post(ROUTE_DELETE).session(session)
                        .param("pfkey", "PF5")
                        .param("usridin", "USER0003")
                        .param("confirmToken", token)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DELETE))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("has been deleted"))));
    }

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("CU03: PF12 returns to the ADMIN menu (parity)")
    void userDeletePf12ReturnsToAdminMenu() throws Exception {
        mockMvc.perform(post(ROUTE_DELETE).session(adminReenterSession())
                        .param("pfkey", "PF12")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ADMIN_MENU));
    }

    @Test
    @DisplayName("CU03 parity: the delete form (COUSR03Form) exposes no password field")
    void userDeleteFormHasNoPasswordField() throws Exception {
        // The delete screen needs no password (COUSR03Form has no passwd field), unlike add/update.
        // A pure-reflection check keeps this parity assertion Docker-free.
        boolean hasPasswordField = Arrays.stream(COUSR03Form.class.getDeclaredFields())
                .map(field -> field.getName().toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains("passw") || name.contains("pwd"));
        assertThat(hasPasswordField)
                .as("COUSR03Form (delete) must not declare a password field (parity with COUSR03C)")
                .isFalse();

        boolean hasPasswordAccessor = Arrays.stream(COUSR03Form.class.getMethods())
                .map(method -> method.getName().toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains("passw") || name.contains("pwd"));
        assertThat(hasPasswordAccessor)
                .as("COUSR03Form (delete) must not expose a password accessor (parity with COUSR03C)")
                .isFalse();
    }

    // =============================================================================================
    // Phase 4 — CSRF negative. Every state-changing POST requires a CSRF token.
    // =============================================================================================

    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    @DisplayName("A POST without a CSRF token is forbidden even for ROLE_ADMIN")
    void userAddPostWithoutCsrfIsForbidden() throws Exception {
        // Kept on ROLE_ADMIN so the 403 is unambiguously the missing CSRF token, not the role gate.
        mockMvc.perform(post(ROUTE_ADD).session(adminReenterSession())
                        .param("pfkey", "ENTER")
                        .param("fname", "New")
                        .param("lname", "User")
                        .param("userid", "NEWUSR03")
                        .param("passwd", SEEDED_PASSWORD)
                        .param("usrtype", "U"))
                .andExpect(status().isForbidden());
    }

    // =============================================================================================
    // Finding F12 — confirmation-integrity (server-owned target + single-use nonce). These tests
    // are deliberately NOT @Transactional: they assert COMMITTED state (or the absence of a write)
    // through the repository, relying on the per-test Flyway clean+migrate to reset the seed. They
    // reproduce the attacker moves the finding calls out: target-swap, forged nonce, and replay.
    // =============================================================================================

    /**
     * CU02 target-swap: the confirmation nonce armed for one user cannot save a re-post aimed at a
     * different user. The fetch arms the nonce bound to USER0002; the PF5 then re-posts USER0004
     * with that nonce and edited fields. The save is rejected (target mismatch), no write occurs,
     * and the swapped victim (USER0004) is untouched.
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void userUpdatePf5SwappedTargetRejectedVictimUnchanged() throws Exception {
        MockHttpSession session = adminReenterSession();
        MvcResult fetch = mockMvc.perform(post(ROUTE_UPDATE).session(session)
                        .param("pfkey", "ENTER").param("usridin", "USER0002").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String token = ((COUSR02Form) fetch.getModelAndView().getModel().get("form")).getConfirmToken();
        assertThat(token).isNotBlank();

        // Re-aim the save at USER0004 while presenting USER0002's nonce.
        mockMvc.perform(post(ROUTE_UPDATE).session(session)
                        .param("pfkey", "PF5")
                        .param("usridin", "USER0004")
                        .param("confirmToken", token)
                        .param("fname", "HACKED")
                        .param("lname", "SWAPPED")
                        .param("passwd", SEEDED_PASSWORD)
                        .param("usrtype", "U")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                .andExpect(model().attribute("form",
                        hasProperty("errmsg", containsString(CONFIRM_INTEGRITY_FRAGMENT))));

        UserSecurity victim = userSecurityRepository.findByUsrId("USER0004").orElseThrow();
        assertThat(victim.getUsrFname().trim())
                .as("the swapped victim must not be overwritten").isEqualTo("AVERARDO");
        assertThat(victim.getUsrLname().trim()).isEqualTo("MAZZI");
    }

    /**
     * CU02 forged nonce: a PF5 presenting a syntactically valid but never-armed nonce is rejected
     * with the integrity banner and performs no write.
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void userUpdatePf5ForgedTokenRejectedNoWrite() throws Exception {
        MockHttpSession session = adminReenterSession();
        mockMvc.perform(post(ROUTE_UPDATE).session(session)
                        .param("pfkey", "ENTER").param("usridin", "USER0002").with(csrf()))
                .andExpect(status().isOk()).andReturn();

        mockMvc.perform(post(ROUTE_UPDATE).session(session)
                        .param("pfkey", "PF5")
                        .param("usridin", "USER0002")
                        .param("confirmToken", FORGED_TOKEN)
                        .param("fname", "HACKED")
                        .param("lname", "FORGED")
                        .param("passwd", SEEDED_PASSWORD)
                        .param("usrtype", "U")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                .andExpect(model().attribute("form",
                        hasProperty("errmsg", containsString(CONFIRM_INTEGRITY_FRAGMENT))));

        UserSecurity user = userSecurityRepository.findByUsrId("USER0002").orElseThrow();
        assertThat(user.getUsrFname().trim())
                .as("a forged confirmation must not write").isEqualTo("AJITH");
    }

    /**
     * CU02 replay: a nonce is single-use. The first PF5 (valid nonce) commits the change; replaying
     * the same nonce on a second PF5 with different edits is rejected and performs no further write,
     * so the committed record stays at the first save's value.
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void userUpdatePf5ReplayedTokenNoSecondWrite() throws Exception {
        MockHttpSession session = adminReenterSession();
        MvcResult fetch = mockMvc.perform(post(ROUTE_UPDATE).session(session)
                        .param("pfkey", "ENTER").param("usridin", "USER0002").with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String token = ((COUSR02Form) fetch.getModelAndView().getModel().get("form")).getConfirmToken();
        assertThat(token).isNotBlank();

        // First PF5 with the valid nonce commits the change.
        mockMvc.perform(post(ROUTE_UPDATE).session(session)
                        .param("pfkey", "PF5")
                        .param("usridin", "USER0002")
                        .param("confirmToken", token)
                        .param("fname", "ZORRO")
                        .param("lname", "MASK")
                        .param("passwd", SEEDED_PASSWORD)
                        .param("usrtype", "U")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("has been updated"))));
        String committedFname = userSecurityRepository.findByUsrId("USER0002").orElseThrow()
                .getUsrFname().trim();

        // Replaying the now-consumed nonce with different edits is rejected: no second write.
        mockMvc.perform(post(ROUTE_UPDATE).session(session)
                        .param("pfkey", "PF5")
                        .param("usridin", "USER0002")
                        .param("confirmToken", token)
                        .param("fname", "REPLAYED")
                        .param("lname", "AGAIN")
                        .param("passwd", SEEDED_PASSWORD)
                        .param("usrtype", "U")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("form",
                        hasProperty("errmsg", containsString(CONFIRM_INTEGRITY_FRAGMENT))));

        UserSecurity user = userSecurityRepository.findByUsrId("USER0002").orElseThrow();
        assertThat(user.getUsrFname().trim())
                .as("a replayed nonce must not overwrite the committed value")
                .isEqualTo(committedFname);
        assertThat(user.getUsrFname().trim()).isNotEqualTo("REPLAYED");
    }

    /**
     * CU03 target-swap: the delete nonce armed for one user cannot delete a different user. The
     * lookup arms the nonce for USER0002; the PF5 then re-posts USER0004 with that nonce. The delete
     * is rejected and neither user is removed.
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void userDeletePf5SwappedTargetRejectedVictimNotDeleted() throws Exception {
        MockHttpSession session = adminReenterSession();
        MvcResult lookup = mockMvc.perform(post(ROUTE_DELETE).session(session)
                        .param("pfkey", "ENTER").param("usridin", "USER0002").with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String token = ((COUSR03Form) lookup.getModelAndView().getModel().get("form")).getConfirmToken();
        assertThat(token).isNotBlank();

        mockMvc.perform(post(ROUTE_DELETE).session(session)
                        .param("pfkey", "PF5")
                        .param("usridin", "USER0004")
                        .param("confirmToken", token)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DELETE))
                .andExpect(model().attribute("form",
                        hasProperty("errmsg", containsString(CONFIRM_INTEGRITY_FRAGMENT))));

        assertThat(userSecurityRepository.findByUsrId("USER0004"))
                .as("the swapped victim must not be deleted").isPresent();
        assertThat(userSecurityRepository.findByUsrId("USER0002"))
                .as("the confirmed user must not be deleted on a rejected swap").isPresent();
    }

    /**
     * CU03 forged nonce: a PF5 delete presenting a never-armed nonce is rejected and deletes nobody.
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void userDeletePf5ForgedTokenRejectedUserNotDeleted() throws Exception {
        MockHttpSession session = adminReenterSession();
        mockMvc.perform(post(ROUTE_DELETE).session(session)
                        .param("pfkey", "ENTER").param("usridin", "USER0003").with(csrf()))
                .andExpect(status().isOk()).andReturn();

        mockMvc.perform(post(ROUTE_DELETE).session(session)
                        .param("pfkey", "PF5")
                        .param("usridin", "USER0003")
                        .param("confirmToken", FORGED_TOKEN)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DELETE))
                .andExpect(model().attribute("form",
                        hasProperty("errmsg", containsString(CONFIRM_INTEGRITY_FRAGMENT))));

        assertThat(userSecurityRepository.findByUsrId("USER0003"))
                .as("a forged confirmation must not delete the user").isPresent();
    }
}

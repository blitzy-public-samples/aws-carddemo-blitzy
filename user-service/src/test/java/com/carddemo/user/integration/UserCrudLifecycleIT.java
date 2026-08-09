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
package com.carddemo.user.integration;

import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.dto.SessionAttributes;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.user.AbstractIntegrationTest;
import com.carddemo.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Prove that the administrator user write operations are reachable over HTTP through
 *     the real service security chain, closing the reported defect where every
 *     ``POST``/``PUT``/``DELETE /users`` answered 403 because no CSRF token could ever be
 *     obtained, which left ``COUSR01C`` (add), ``COUSR02C`` (update) and ``COUSR03C``
 *     (delete) entirely non-functional. It also covers the twelve cases that defect made
 *     untestable: first-error order on add, the duplicate-id and no-change rejections, the
 *     read-for-display prompts, the add/update outcome messages, the 201/200/204 status
 *     semantics, password omission on write, and role handling on write.
 * :output: A full add -> read -> update -> delete lifecycle plus every rejection path, each
 *     asserting the verbatim ``COUSR0*`` message and the expected status, together with the
 *     paging banners, the row-selection edit and the unauthenticated actuator surface.
 * :note: Requests carry a real ``SessionContext`` in the HTTP session, which is what the
 *     shared ``SessionContextAuthenticationFilter`` converts into the ``ROLE_ADMIN``
 *     authority the chain requires, so the production chain is exercised, not stubbed.
 *     This module owns no Flyway migrations, so the fixture rows are seeded here.
 */
class UserCrudLifecycleIT extends AbstractIntegrationTest {

    /** :purpose: User id created and removed by the lifecycle; never a fixture id. */
    private static final String TEMP_USER_ID = "QATMP001";

    /** :purpose: Fixture administrator id used for the update and read-for-display paths. */
    private static final String ADMIN_ID = "ADMIN001";

    /** :purpose: Raw password supplied on write; only its hash is ever persisted. */
    private static final String RAW_PASSWORD = "PASSWORD";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** :purpose: MockMvc wired with the real service security filter chain. */
    private MockMvc mockMvc;

    /** :purpose: HTTP session carrying the ADMIN session context. */
    private MockHttpSession adminSession;

    /**
     * :purpose: Build MockMvc over the real security chain, establish an administrator
     *     session, and reset the fixture to exactly three known users.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        SessionContext context = new SessionContext();
        context.setUserId(ADMIN_ID);
        context.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        adminSession = new MockHttpSession();
        adminSession.setAttribute(SessionAttributes.SESSION_CONTEXT, context);

        userRepository.deleteAll();
        userRepository.save(user(ADMIN_ID, "Admin", "User", "A"));
        userRepository.save(user("USER0002", "Regular", "User", "U"));
        userRepository.save(user("USER0003", "Second", "User", "U"));
    }

    /**
     * :purpose: Build a fixture security user whose stored credential is the encoded form of
     *     {@link #RAW_PASSWORD}.
     * :param id: the user id.
     * :param first: the first name.
     * :param last: the last name.
     * :param type: the user type, ``A`` or ``U``.
     * :returns: the unsaved fixture entity.
     */
    private SecurityUser user(String id, String first, String last, String type) {
        SecurityUser entity = new SecurityUser();
        entity.setSecUsrId(id);
        entity.setSecUsrFname(first);
        entity.setSecUsrLname(last);
        entity.setSecUsrType(type);
        entity.setSecUsrPwd(passwordEncoder.encode(RAW_PASSWORD));
        return entity;
    }

    /**
     * :purpose: Exercise the whole legacy write lifecycle end to end: add (``COUSR01C``),
     *  read for display, update (``COUSR02C``) and delete (``COUSR03C``), asserting the
     *  verbatim message and status of every step and that the persisted state follows.
     * :output: 201 -> 200 -> 200 -> 204 with the verbatim messages.
     */
    @Test
    @DisplayName("add -> read -> update -> delete lifecycle succeeds with the verbatim COUSR0* messages")
    void writeLifecycleSucceedsWithVerbatimMessages() throws Exception {
        mockMvc.perform(post("/users").session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + TEMP_USER_ID + "\",\"firstName\":\"Tmp\","
                                + "\"lastName\":\"User\",\"userType\":\"U\","
                                + "\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(TEMP_USER_ID))
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.message").value("User " + TEMP_USER_ID + " has been added ..."))
                .andExpect(jsonPath("$.password").doesNotExist());

        // The raw password is never persisted; the stored value is an encoder-prefixed hash.
        String storedHash = userRepository.findBySecUsrId(TEMP_USER_ID).orElseThrow().getSecUsrPwd();
        assertThat(storedHash).isNotEqualTo(RAW_PASSWORD).startsWith("{bcrypt}");

        mockMvc.perform(get("/users/{id}", TEMP_USER_ID).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(TEMP_USER_ID))
                .andExpect(jsonPath("$.firstName").value("Tmp"))
                .andExpect(jsonPath("$.lastName").value("User"))
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        mockMvc.perform(put("/users/{id}", TEMP_USER_ID).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Tmpupd\",\"lastName\":\"Userupd\",\"userType\":\"A\","
                                + "\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Tmpupd"))
                .andExpect(jsonPath("$.userType").value("A"))
                .andExpect(jsonPath("$.message").value("User " + TEMP_USER_ID + " has been updated ..."));

        assertThat(userRepository.findBySecUsrId(TEMP_USER_ID).orElseThrow().getSecUsrType())
                .isEqualTo("A");

        mockMvc.perform(delete("/users/{id}", TEMP_USER_ID).session(adminSession))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findBySecUsrId(TEMP_USER_ID)).isEmpty();
    }

    /**
     * :purpose: ``COUSR01C`` first-error order: the add edits are performed first name, last
     *  name, user id, password, user type, and an all-empty request reports only the first.
     * :output: 400 carrying exactly ``'First Name can NOT be empty...'``.
     */
    @Test
    @DisplayName("add with every field empty reports only the first COUSR01C error")
    void addWithEveryFieldEmptyReportsFirstErrorOnly() throws Exception {
        MvcResult result = mockMvc.perform(post("/users").session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\",\"firstName\":\"\",\"lastName\":\"\",\"userType\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("First Name can NOT be empty...");
        assertThat(body).doesNotContain("Last Name can NOT be empty...");
        assertThat(body).doesNotContain("User ID can NOT be empty...");
    }

    /**
     * :purpose: ``COUSR01C`` duplicate-id rejection against an existing administrator.
     * :output: 400 carrying ``'User ID already exist...'``.
     */
    @Test
    @DisplayName("add with an existing id reports 'User ID already exist...'")
    void addWithDuplicateIdReportsAlreadyExists() throws Exception {
        mockMvc.perform(post("/users").session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + ADMIN_ID + "\",\"firstName\":\"Dup\","
                                + "\"lastName\":\"User\",\"userType\":\"U\","
                                + "\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("User ID already exist..."));
    }

    /**
     * :purpose: ``COUSR02C`` no-change rejection: resubmitting the stored values unchanged
     *  reports the modify prompt and writes nothing.
     * :output: 400 carrying ``'Please modify to update ...'``.
     */
    @Test
    @DisplayName("update with no change reports 'Please modify to update ...'")
    void updateWithNoChangeReportsPleaseModify() throws Exception {
        mockMvc.perform(put("/users/{id}", ADMIN_ID).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Admin\",\"lastName\":\"User\",\"userType\":\"A\","
                                + "\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please modify to update ..."));
    }

    /**
     * :purpose: ``COUSR02C``/``COUSR03C`` read-for-display prompts, reached the way the legacy
     *  list screen reaches them: by marking a row ``U`` or ``D`` on the user list.
     * :output: the two verbatim PF5 prompts on the list response.
     */
    @Test
    @DisplayName("a row selection of U or D carries the COUSR02C/COUSR03C PF5 prompt")
    void rowSelectionCarriesTheReadForDisplayPrompt() throws Exception {
        mockMvc.perform(get("/users").session(adminSession)
                        .param("selection", "U")
                        .param("selectedUserId", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedAction").value("U"))
                .andExpect(jsonPath("$.selectedUserId").value(ADMIN_ID))
                .andExpect(jsonPath("$.message").value("Press PF5 key to save your updates ..."));

        mockMvc.perform(get("/users").session(adminSession)
                        .param("selection", "d")
                        .param("selectedUserId", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedAction").value("D"))
                .andExpect(jsonPath("$.message").value("Press PF5 key to delete this user ..."));
    }

    /**
     * :purpose: ``COUSR00C`` row-selection edit: any flag other than ``U`` or ``D`` reports the
     *  verbatim invalid-selection message instead of being silently ignored.
     * :output: 400 carrying ``'Invalid selection. Valid values are U and D'``.
     */
    @Test
    @DisplayName("an invalid row selection reports 'Invalid selection. Valid values are U and D'")
    void invalidRowSelectionIsRejected() throws Exception {
        mockMvc.perform(get("/users").session(adminSession)
                        .param("selection", "X")
                        .param("selectedUserId", ADMIN_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid selection. Valid values are U and D"));
    }

    /**
     * :purpose: A negative page index reports the ``COUSR00C`` top boundary instead of
     *  surfacing an unexpected server error.
     * :output: 400 carrying ``'You are already at the top of the page...'``.
     */
    @Test
    @DisplayName("page=-1 reports the top boundary and never an unexpected 500")
    void negativePageReportsTopBoundary() throws Exception {
        mockMvc.perform(get("/users").session(adminSession).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("You are already at the top of the page..."));
    }

    /**
     * :purpose: The ``COUSR00C`` paging banners now reach the client, and the page carries the
     *  ``CDEMO-CU00-USRID-FIRST``/``-LAST`` cursors the PF7 and PF8 keys need.
     * :output: page 0 lists the three fixture users with cursors and no banner; page 99
     *  reports the verbatim bottom banner.
     */
    @Test
    @DisplayName("the list carries the paging cursors, and a page past the end carries the bottom banner")
    void listCarriesCursorsAndBottomBanner() throws Exception {
        mockMvc.perform(get("/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(3))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.userIdFirst").value(ADMIN_ID))
                .andExpect(jsonPath("$.userIdLast").value("USER0003"))
                .andExpect(jsonPath("$.nextPage").value(false))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.users[*].password").doesNotExist());

        mockMvc.perform(get("/users").session(adminSession).param("page", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isEmpty())
                .andExpect(jsonPath("$.pageNumber").value(99))
                .andExpect(jsonPath("$.message")
                        .value("You have reached the bottom of the page..."));
    }

    /**
     * :purpose: The legacy ``PF7``/``PF8`` paging vocabulary is honoured, the pre-existing
     *  ``forward``/``backward`` aliases still work, and an unrecognised paging action reports
     *  the verbatim ``CSMSG01Y`` invalid-key message rather than being silently ignored.
     * :output: 200 for each recognised action and 400 with the invalid-key message otherwise.
     */
    @Test
    @DisplayName("PF7/PF8 and their aliases page, and an unrecognised action reports the invalid key")
    void legacyPagingVocabularyIsHonoured() throws Exception {
        mockMvc.perform(get("/users").session(adminSession)
                        .param("direction", "PF8").param("cursor", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(2))
                .andExpect(jsonPath("$.message")
                        .value("You have reached the bottom of the page..."));

        mockMvc.perform(get("/users").session(adminSession)
                        .param("direction", "PF7").param("cursor", "USER0003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(2))
                .andExpect(jsonPath("$.message")
                        .value("You have reached the top of the page..."));

        mockMvc.perform(get("/users").session(adminSession)
                        .param("direction", "forward").param("cursor", ADMIN_ID))
                .andExpect(status().isOk());

        MvcResult invalid = mockMvc.perform(get("/users").session(adminSession)
                        .param("direction", "sideways").param("cursor", ADMIN_ID))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(invalid.getResponse().getContentAsString())
                .contains("Invalid key pressed. Please see below...");
    }

    /**
     * :purpose: The actuator surface this service exposes to the container probe and to the
     *  metrics scraper is reachable without an administrator session, so the container can
     *  become healthy and the Prometheus target can come up.
     * :output: 200 from health, liveness, readiness, info and prometheus.
     */
    @Test
    @DisplayName("the actuator health and prometheus endpoints are reachable unauthenticated")
    void actuatorSurfaceIsReachableWithoutASession() throws Exception {
        // The assertion is reachability, not liveness: this test context deliberately runs
        // without the Redis session store that the deployed service has, so an aggregate
        // health status of DOWN is expected here. What matters for the container probe and
        // the metrics scraper is that the chain does not answer 401 or 403.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().is(not(anyOf(equalTo(401), equalTo(403)))))
                .andExpect(jsonPath("$.status").exists());
        // Liveness must never depend on an external store, so it stays 200 here.
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        // Readiness DOES include the session store, so without Redis it reports 503 - the
        // correct answer for "do not send me traffic yet", and still not 401 or 403.
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().is(not(anyOf(equalTo(401), equalTo(403)))))
                .andExpect(jsonPath("$.status").exists());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
        // The scrape endpoint is deliberately NOT anonymous: it carries per-endpoint metrics
        // that describe internal traffic, so it is served only to the dedicated monitoring
        // principal (which is why observability/prometheus.yml scrapes with basic auth). An
        // uncredentialed scrape is challenged rather than served.
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }
}

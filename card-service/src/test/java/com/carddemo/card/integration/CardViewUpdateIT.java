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
package com.carddemo.card.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.repository.CardXrefRepository;
import com.carddemo.common.domain.Card;
import com.carddemo.common.dto.CardKeyRequestDto;
import com.carddemo.common.dto.CardListItemDto;
import com.carddemo.common.dto.CardListResponseDto;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.testsupport.MigratedSchemaContainer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;



import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.ObjectMapper;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * :purpose: End-to-end integration test that boots the full ``card-service`` Spring
 *  Boot context against a real ``postgres:18`` Testcontainer and drives the card
 *  list (legacy ``COCRDLIC`` / CICS ``CCLI``), detail (``COCRDSLC`` / ``CCDL``) and
 *  update (``COCRDUPC`` / ``CCUP``) flows through the REST layer via
 *  :java:type:`MockMvc`, proving functional equivalence with the legacy COBOL card
 *  programs. Assertions bind to the materialized shared DTOs and to the byte-exact
 *  legacy messages surfaced by the shared ``GlobalExceptionHandler``.
 * :note: The class name ends in ``IT`` so the Maven Failsafe plugin runs it in the
 *  integration-test phase. No test-level transaction is used: real commits are
 *  required so pagination and update persistence reflect true database state. The
 *  sensitive CVV and the full card number (PAN) are never asserted (AAP 0.6.7).
 */
@SpringBootTest
// The production filter chain IS applied: every request below presents the shared
// session context, which is exactly how a request authenticates in production, so the
// security, correlation-id and hardening filters are all exercised end to end.
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class CardViewUpdateIT {

    /**
     * :purpose: Build the JSON body of a card-detail read. The composite key travels in the
     *  BODY rather than in the URL: a card number is a Primary Account Number, and a path
     *  segment or query string is written verbatim into every access log, proxy log and
     *  distributed trace on the request path.
     * :param cardNumber: the card number to address.
     * :returns: the serialized request body.
     */
    private String cardKeyJson(String cardNumber) throws Exception {
        CardKeyRequestDto key = new CardKeyRequestDto();
        key.setCardNumber(cardNumber);
        return objectMapper.writeValueAsString(key);
    }

    /** :purpose: HttpSession attribute key carrying the externalized COMMAREA session context. */
    private static final String SESSION_ATTR = "carddemoSessionContext";

    /** :purpose: Seeded card owned by account 1 (embossed name ``Immanuel Kessler``). */
    private static final String CARD_ACCT1 = "9680294154603697";

    /** :purpose: Seeded card owned by account 50, used as the dedicated update-mutation target. */
    private static final String CARD_ACCT50 = "0500024453765740";

    /** :purpose: Syntactically valid but unseeded sixteen-digit card number for the not-found case. */
    private static final String UNSEEDED_CARD = "9999999999999999";

    /**
     * :purpose: Manually-managed ``postgres:18`` Testcontainer shared by every test in
     *  this class. Started in the static initializer so its mapped port is available to
     *  ``@DynamicPropertySource`` before the Spring context (and its Flyway migrations)
     *  connect to it.
     */
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18").withDatabaseName("carddemo");

    static {
        POSTGRES.start();
    }

    /**
     * :purpose: Point the application datasource at the manually-managed container. The
     *  Testcontainers JDBC driver configured by the ``test`` profile is replaced with the
     *  plain PostgreSQL driver so exactly one container backs the whole test class.
     * :note: The context's Flyway auto-configuration applies the shared carddemo-common
     *  migration set (``V1`` schema, ``V2`` reference data, ``V3`` test data, ``V4`` seeded-PII
     *  encryption, ``V5`` batch metadata, ``V6``–``V8`` optimistic-lock and card foreign-key
     *  additions) to the empty container before Hibernate runs, so every entity-scanned
     *  table — including the 50 seeded cards and cross-references this class asserts on —
     *  exists and Hibernate ``ddl-auto: validate`` confirms the mapping.
     * :param registry: the dynamic property registry supplied by the Spring Test context.
     */
    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    /** :purpose: MVC entry point exercised end-to-end (no mocked collaborators). */
    @Autowired
    private MockMvc mockMvc;

    /** :purpose: Card repository used for cache-bypass reload/restore assertions. */
    @Autowired
    private CardRepository cardRepository;

    /** :purpose: Card cross-reference repository injected to prove the real wiring resolves. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** :purpose: JSON serializer for building request bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /** :purpose: Factory used to create a fresh EntityManager for first-level-cache-bypass reloads. */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** :purpose: Raw JDBC access used only to observe (never edit) the seeded CVV column. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** :purpose: Captured embossed name of the mutation-target card, restored after each test. */
    private String origEmbossedName;

    /** :purpose: Captured active status of the mutation-target card, restored after each test. */
    private String origActiveStatus;

    /** :purpose: Captured expiry date (legacy-spelled) of the mutation-target card. */
    private String origExpiraionDate;

    /** :purpose: Captured CVV of the mutation-target card (neutralized to null before reads). */
    private String origCvvCd;

    /**
     * :purpose: Snapshot the mutation-target card for deterministic restoration in
     *  ``tearDown``.
     * :note: The seeded ``card_cvv_cd`` column holds a genuine AES-256-GCM token, written by
     *  the shared migration set's version-4 Java migration under the test key, so every card
     *  read hydrates through the at-rest ``CryptoConverter`` unchanged - no fixture patching
     *  is required and the snapshot round-trips the real value.
     */
    @BeforeEach
    void setUp() {
        Card card = cardRepository.findById(CARD_ACCT50).orElseThrow();
        origEmbossedName = card.getCardEmbossedName();
        origActiveStatus = card.getCardActiveStatus();
        origExpiraionDate = card.getCardExpiraionDate();
        origCvvCd = card.getCardCvvCd();
    }

    /**
     * :purpose: Restore the mutation-target card to its snapshot so the shared, seeded
     *  dataset stays deterministic across test methods and across the sibling IT classes.
     */
    @AfterEach
    void tearDown() {
        Card card = cardRepository.findById(CARD_ACCT50).orElseThrow();
        card.setCardEmbossedName(origEmbossedName);
        card.setCardActiveStatus(origActiveStatus);
        card.setCardExpiraionDate(origExpiraionDate);
        card.setCardCvvCd(origCvvCd);
        cardRepository.save(card);
    }

    /**
     * :purpose: The card detail path must work against the committed seed exactly as
     *  deployed: ``V3__seed_cards.sql`` writes ``card_cvv_cd`` the way the legacy
     *  fixed-width load did, and the at-rest converter has to hydrate it. No column is
     *  edited by this test.
     * :output: HTTP 200 for a seeded card whose CVV column is untouched, with the CVV
     *  absent from the response body.
     */

    @Test
    void getCard_withUnmodifiedSeededCvv_returns200AndNeverEchoesTheCvv() throws Exception {
        Integer untouchedCvvs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cards WHERE card_cvv_cd IS NOT NULL", Integer.class);
        assertThat(untouchedCvvs).isPositive();

        String body = mockMvc.perform(post("/cards/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardKeyJson(CARD_ACCT1))
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("cardCvvCd");
        assertThat(body).doesNotContain("cvv");
    }

    // ------------------------------------------------------------------------
    // Card list (COCRDLIC, CICS CCLI, GET /cards)
    // ------------------------------------------------------------------------

    /**
     * :purpose: An administrator listing the first page receives exactly seven rows,
     *  reproducing the legacy ``WS-MAX-SCREEN-LINES VALUE 7`` page size.
     */
    @Test
    void adminListFirstPageReturnsSevenRows() throws Exception {
        mockMvc.perform(get("/cards")
                        .param("page", "1")
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards", hasSize(7)));
    }

    /**
     * :purpose: Exercise the forward-paging boundaries: the last populated page (8) of the
     *  fifty seeded cards holds a single row, and a page beyond the data returns an empty
     *  collection with HTTP 200 (the ``NO RECORDS FOUND`` outcome is an information state,
     *  not an error; the materialized list DTO carries only the row collection).
     */
    @Test
    void adminListPaginationBoundaries() throws Exception {
        mockMvc.perform(get("/cards")
                        .param("page", "8")
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards", hasSize(1)));

        mockMvc.perform(get("/cards")
                        .param("page", "9")
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards", hasSize(0)));
    }

    /**
     * :purpose: Page forward through the WHOLE browse and prove the pages tile the card
     *  master exactly: every seeded card is reachable, no card is skipped at a page
     *  boundary, no card appears on two pages, and the concatenated pages reproduce the
     *  ``card_num`` ascending order of the VSAM primary-key browse. Screens advance by
     *  ``WS-MAX-SCREEN-LINES`` (7) even though the window reads one lookahead record, so a
     *  page size of eight would step eight rows and silently drop one card per boundary.
     * :note: The expected order is READ FROM the database at runtime; no primary account
     *  number is written into this source.
     */
    @Test
    void adminPagesTileTheCardMasterWithoutGapOrOverlap() throws Exception {
        List<String> expectedOrder = jdbcTemplate.queryForList(
                "SELECT card_num FROM cards ORDER BY card_num ASC", String.class);
        assertThat(expectedOrder).hasSize(50);

        List<String> pagedOrder = new ArrayList<>();
        int pageCount = (expectedOrder.size() + 6) / 7;
        assertThat(pageCount).isEqualTo(8);
        for (int page = 1; page <= pageCount; page++) {
            String body = mockMvc.perform(get("/cards")
                            .param("page", String.valueOf(page))
                            .sessionAttr(SESSION_ATTR, adminSession()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            CardListResponseDto response = objectMapper.readValue(body, CardListResponseDto.class);
            assertThat(response.getPageNumber()).isEqualTo(page);
            assertThat(response.getCards()).isNotEmpty().hasSizeLessThanOrEqualTo(7);
            assertThat(response.isNextPage()).isEqualTo(page < pageCount);
            response.getCards().stream().map(CardListItemDto::getCardNum).forEach(pagedOrder::add);
        }

        assertThat(pagedOrder).containsExactlyElementsOf(expectedOrder).doesNotHaveDuplicates();
    }

    /**
     * :purpose: The browse scope is the ACCTSID the operator supplies, NOT the caller's role
     *  or the account its session happens to carry: ``COCRDLIC 9500-FILTER-RECORDS``
     *  (L1382-1396) filters only on the supplied account and card filters and has no
     *  user-type branch. A non-admin with no filter therefore sees the same unfiltered first
     *  page an administrator sees, and supplying the filter is what narrows the browse to one
     *  account. The full PAN is never asserted (PII); scope is proven via the owning
     *  account id. The filter is written at its full eleven-digit width because
     *  ``IF CC-ACCT-ID IS NOT NUMERIC`` is a class test on a ``PIC X(11)`` item, so a shorter
     *  run is refused rather than widened; see decision log section 44.7.
     */
    @Test
    void listScopeFollowsTheSuppliedFilterNotTheCallerRole() throws Exception {
        // No filter: the ordinary user gets the full-master first page, seven rows.
        mockMvc.perform(get("/cards")
                        .param("page", "1")
                        .sessionAttr(SESSION_ATTR, userSession(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards", hasSize(7)));

        // The supplied filter is the only scope, and it applies to an ordinary user exactly
        // as it does to an administrator: account 1 owns exactly one seeded card.
        mockMvc.perform(get("/cards")
                        .param("page", "1")
                        .param("accountId", "00000000001")
                        .sessionAttr(SESSION_ATTR, userSession(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards", hasSize(1)))
                .andExpect(jsonPath("$.cards[0].cardAcctId").value(1));

        mockMvc.perform(get("/cards")
                        .param("page", "1")
                        .param("accountId", "00000000001")
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards", hasSize(1)))
                .andExpect(jsonPath("$.cards[0].cardAcctId").value(1));
    }

    /**
     * :purpose: A supplied account filter that is not EXACTLY eleven digits is rejected with
     *  HTTP 400 and the byte-exact legacy edit message -- over-wide, short and non-numeric
     *  alike, because ``COCRDSLC``'s edit is a class test on a ``PIC X(11)`` item.
     * :param filter: an account filter violating the exactly-eleven-digits rule.
     */
    @ParameterizedTest
    @ValueSource(strings = {"123456789012", "1", "0000000000a"})
    void listInvalidAccountFilterReturns400(String filter) throws Exception {
        mockMvc.perform(get("/cards")
                        .param("accountId", filter)
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"));
    }

    /**
     * :purpose: A supplied card-number filter that is not sixteen digits is rejected with
     *  HTTP 400 and the byte-exact legacy edit message.
     */
    @Test
    void listInvalidCardFilterReturns400() throws Exception {
        mockMvc.perform(get("/cards")
                        .param("cardNumber", "123")
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"));
    }

    // ------------------------------------------------------------------------
    // Card detail (COCRDSLC, CICS CCDL, GET /cards/{cardNumber})
    // ------------------------------------------------------------------------

    /**
     * :purpose: Reading a seeded card returns its non-sensitive business fields, including
     *  the legacy-spelled ``cardExpiraionDate``, and the card-customer-account
     *  cross-reference resolves to the same customer the response reports. The CVV and the
     *  full card number are never asserted (AAP 0.6.7).
     */
    @Test
    void getCardDetailHappyPath() throws Exception {
        mockMvc.perform(post("/cards/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardKeyJson(CARD_ACCT1))
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardAcctId").value(1))
                .andExpect(jsonPath("$.cardEmbossedName").value("Immanuel Kessler"))
                .andExpect(jsonPath("$.cardExpiraionDate").value("2025-05-20"))
                .andExpect(jsonPath("$.cardActiveStatus").value("Y"))
                .andExpect(jsonPath("$.custId").value(1));

        // The cross-reference repository wires and the CXACAIX linkage for account 1
        // resolves to customer 1 (seed: xref_cust_id == xref_acct_id == card_acct_id).
        var crossReference = cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(1L);
        assertThat(crossReference).isPresent();
        assertThat(crossReference.get().getXrefCustId()).isEqualTo(1L);
    }

    /**
     * :purpose: A syntactically valid but unseeded card number yields HTTP 404 with the
     *  byte-exact legacy not-found message.
     */
    @Test
    void getCardDetailNotFoundReturns404() throws Exception {
        mockMvc.perform(post("/cards/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardKeyJson(UNSEEDED_CARD))
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Did not find cards for this search condition"));
    }

    /**
     * :purpose: A malformed (non-sixteen-digit) card key yields HTTP 400 with the byte-exact
     *  literal ``2220-EDIT-CARD`` MOVEs for its ``IS NOT NUMERIC`` branch. The mixed-case
     *  ``SEARCHED-CARD-NOT-NUMERIC`` 88-level the program declares is never SET.
     */
    @Test
    void getCardDetailMalformedNumberReturns400() throws Exception {
        mockMvc.perform(post("/cards/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardKeyJson("123"))
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"));
    }

    // ------------------------------------------------------------------------
    // Card update (COCRDUPC, CICS CCUP, PUT /cards/{cardNumber})
    // ------------------------------------------------------------------------

    /**
     * :purpose: A valid update toggling the active status persists the change; a
     *  cache-bypassing reload confirms the new status and that the legacy-spelled
     *  ``card_expiraion_date`` column round-trips unchanged.
     */
    @Test
    void updateCardHappyPathPersists() throws Exception {
        String newStatus = "Y".equals(origActiveStatus) ? "N" : "Y";
        CardUpdateRequestDto request = new CardUpdateRequestDto();
        // The addressed card number rides in the BODY, never in the URL.
        request.setCardNumber(CARD_ACCT50);
        request.setCardEmbossedName(origEmbossedName);
        request.setCardActiveStatus(newStatus);
        request.setCardExpiraionDate(origExpiraionDate);
        request.setCardCvvCd(null);

        mockMvc.perform(put("/cards")
                        .sessionAttr(SESSION_ATTR, adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardAcctId").value(50))
                .andExpect(jsonPath("$.cardActiveStatus").value(newStatus));

        Card reloaded = reloadCardBypassingCache(CARD_ACCT50);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getCardActiveStatus()).isEqualTo(newStatus);
        assertThat(reloaded.getCardExpiraionDate()).isEqualTo(origExpiraionDate);
    }

    /**
     * :purpose: An embossed name containing non-alphabetic characters is rejected with
     *  HTTP 400 and the byte-exact legacy name-edit message.
     */
    @Test
    void updateCardInvalidNameReturns400() throws Exception {
        CardUpdateRequestDto request = validBaseUpdate();
        request.setCardEmbossedName("Invalid123");

        performUpdateExpectingBadRequest(request, "Card name can only contain alphabets and spaces");
    }

    /**
     * :purpose: An active status other than ``Y`` or ``N`` is rejected with HTTP 400 and
     *  the byte-exact legacy status-edit message.
     */
    @Test
    void updateCardInvalidStatusReturns400() throws Exception {
        CardUpdateRequestDto request = validBaseUpdate();
        request.setCardActiveStatus("X");

        performUpdateExpectingBadRequest(request, "Card Active Status must be Y or N");
    }

    /**
     * :purpose: An expiry month outside 1-12 is rejected with HTTP 400 and the byte-exact
     *  legacy month-edit message.
     */
    @Test
    void updateCardInvalidMonthReturns400() throws Exception {
        CardUpdateRequestDto request = validBaseUpdate();
        request.setCardExpiraionDate("2025-13-09");

        performUpdateExpectingBadRequest(request, "Card expiry month must be between 1 and 12");
    }

    /**
     * :purpose: An expiry year outside 1950-2099 is rejected with HTTP 400 and the
     *  byte-exact legacy year-edit message.
     */
    @Test
    void updateCardInvalidYearReturns400() throws Exception {
        CardUpdateRequestDto request = validBaseUpdate();
        request.setCardExpiraionDate("1949-05-20");

        performUpdateExpectingBadRequest(request, "Invalid card expiry year");
    }

    // ------------------------------------------------------------------------
    // Observability
    // ------------------------------------------------------------------------

    /**
     * :purpose: A successful request propagates a correlation id back to the caller via
     *  the ``X-Correlation-Id`` response header (structured-logging correlation, AAP 0.7.5).
     */
    @Test
    void observabilitySmoke() throws Exception {
        mockMvc.perform(get("/cards")
                        .param("page", "1")
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * :purpose: Build an administrator session context (``CDEMO-USER-TYPE`` ``'A'``) that
     *  sees every account's cards.
     * :returns: a populated admin :java:type:`SessionContext`.
     */
    private SessionContext adminSession() {
        SessionContext ctx = new SessionContext();
        ctx.setUserId("ADMIN001");
        ctx.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        return ctx;
    }

    /**
     * :purpose: Build a standard-user session context (``CDEMO-USER-TYPE`` ``'U'``) scoped
     *  to a single owning account.
     * :param acctId: the owning account id the user is scoped to.
     * :returns: a populated user :java:type:`SessionContext`.
     */
    private SessionContext userSession(long acctId) {
        SessionContext ctx = new SessionContext();
        ctx.setUserId("USER0001");
        ctx.setUserType(SessionContext.UserType.CDEMO_USRTYP_USER);
        ctx.setAcctId(acctId);
        return ctx;
    }

    /**
     * :purpose: Build a card update request whose name, status and expiry date are all
     *  individually valid, so a single field mutated by a caller is the sole edit failure.
     * :returns: a valid baseline :java:type:`CardUpdateRequestDto`.
     */
    private CardUpdateRequestDto validBaseUpdate() {
        CardUpdateRequestDto request = new CardUpdateRequestDto();
        // The addressed card number rides in the BODY, never in the URL.
        request.setCardNumber(CARD_ACCT50);
        request.setCardEmbossedName("Valid Name");
        request.setCardActiveStatus("Y");
        request.setCardExpiraionDate("2025-05-20");
        request.setCardCvvCd(null);
        return request;
    }

    /**
     * :purpose: Issue a card update expected to fail validation and assert the HTTP 400
     *  status and the byte-exact error message on the shared error contract.
     * :param request: the update request body under test.
     * :param expectedMessage: the byte-exact legacy edit message expected in the error body.
     */
    private void performUpdateExpectingBadRequest(CardUpdateRequestDto request, String expectedMessage)
            throws Exception {
        mockMvc.perform(put("/cards")
                        .sessionAttr(SESSION_ATTR, adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(expectedMessage));
    }

    /**
     * :purpose: Reload a card through a fresh EntityManager so the read bypasses any
     *  first-level cache and reflects the committed database state.
     * :param cardNumber: the card number primary key to reload.
     * :returns: the freshly loaded managed card, or ``null`` when absent.
     */
    private Card reloadCardBypassingCache(String cardNumber) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return entityManager.find(Card.class, cardNumber);
        } finally {
            entityManager.close();
        }
    }

    // ------------------------------------------------------------------------
    // Security contract (the service's own port, not only behind the gateway)
    // ------------------------------------------------------------------------

    /**
     * :purpose: Freezes the authentication contract of the card service on its own port,
     *     where an anonymous caller previously read every card and reached the update
     *     path. It also pins the request-size cap and the hardened response headers,
     *     which must hold here and not only at the gateway.
     * :note: Nested inside this integration test so it reuses the provisioned container,
     *     the seeded schema, and the production filter chain this class already boots.
     */
    @Nested
    @DisplayName("security contract on the card-service port")
    class SecurityContract {

        @Test
        @DisplayName("anonymous card reads are rejected with 401 and return no card data")
        void anonymousReadsAreRejected() throws Exception {
            String listBody = mockMvc.perform(get("/cards").param("page", "1"))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();
            String detailBody = mockMvc.perform(post("/cards/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardKeyJson(CARD_ACCT1)))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(listBody).doesNotContain(CARD_ACCT1);
            assertThat(detailBody).doesNotContain(CARD_ACCT1);
        }

        @Test
        @DisplayName("an anonymous card update is rejected with 401 and changes nothing")
        void anonymousUpdateIsRejected() throws Exception {
            Card before = reloadCardBypassingCache(CARD_ACCT50);

            mockMvc.perform(put("/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());

            Card after = reloadCardBypassingCache(CARD_ACCT50);
            assertThat(after.getCardEmbossedName()).isEqualTo(before.getCardEmbossedName());
            assertThat(after.getCardActiveStatus()).isEqualTo(before.getCardActiveStatus());
            assertThat(after.getCardExpiraionDate()).isEqualTo(before.getCardExpiraionDate());
        }

        @Test
        @DisplayName("the health probe stays reachable without credentials")
        void healthProbeIsAnonymous() throws Exception {
            mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("an oversized request body is rejected with 413 before authentication")
        void oversizedBodyIsRejected() throws Exception {
            byte[] oversized = new byte[(int) (com.carddemo.common.config.RequestSizeLimitFilter
                    .DEFAULT_MAX_BODY_BYTES + 1024)];
            java.util.Arrays.fill(oversized, (byte) 'A');

            mockMvc.perform(put("/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(oversized))
                    .andExpect(status().isContentTooLarge());
        }

        @Test
        @DisplayName("every response carries the hardened security headers")
        void responsesCarryHardenedHeaders() throws Exception {
            var response = mockMvc.perform(get("/actuator/health")).andReturn().getResponse();

            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.getHeader("Content-Security-Policy")).isNotBlank();
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(response.getHeader("Permissions-Policy")).isNotBlank();
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
        }
    }
}

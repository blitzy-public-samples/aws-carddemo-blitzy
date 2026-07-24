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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.repository.CardXrefRepository;
import com.carddemo.common.domain.Card;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.SessionContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.ObjectMapper;

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
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class CardViewUpdateIT {

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
     *  this class. Started in the static initializer so the cross-service prerequisite
     *  schema can be provisioned before the Spring context (and its Flyway migrations)
     *  connect to it.
     */
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18").withDatabaseName("carddemo");

    /** :purpose: Flyway history table name matching the service's configured card history table. */
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history_card";

    static {
        POSTGRES.start();
        provisionCrossServicePrerequisites();
        migrateCardSchema();
    }

    /**
     * :purpose: Point the application datasource at the manually-managed container. The
     *  Testcontainers JDBC driver configured by the ``test`` profile is replaced with the
     *  plain PostgreSQL driver so the context connects to the same instance that the
     *  static initializer already provisioned and migrated.
     * :note: The Spring Boot Flyway auto-configuration module is not present in this
     *  environment (only the raw ``flyway-core`` library is), so ``spring.flyway.*``
     *  properties are inert; the card ``V1``-``V4`` migrations are therefore applied
     *  directly against this datasource in :java:meth:`migrateCardSchema` before the
     *  context refreshes, leaving Hibernate ``ddl-auto: validate`` to confirm the mapping.
     * :param registry: the dynamic property registry supplied by the Spring Test context.
     */
    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    /**
     * :purpose: Apply the card-service's real Flyway migrations (``V1``-``V4``: create
     *  ``cards`` and ``card_xref`` and seed 50 cards / 50 cross-references) against the
     *  container, layering them over the pre-provisioned prerequisite tables. Because the
     *  prerequisite schema is non-empty and carries no card history table, the migration
     *  baselines at version ``0`` so every card migration (version > 0) runs. This uses
     *  the service's actual migration scripts rather than redefining any card table.
     */
    private static void migrateCardSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .table(FLYWAY_HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    /**
     * :purpose: Create and minimally seed the entity-scanned tables that ``card-service``
     *  does not own but that Hibernate ``ddl-auto: validate`` and the card foreign keys
     *  require. ``CardServiceApplication`` entity-scans ``com.carddemo.common.domain``,
     *  so every shared entity needs a matching table; ``cards`` and ``card_xref`` are
     *  created by the service's own Flyway ``V1``/``V2``, while the account, customer and
     *  reference tables are created here. The ``accounts`` and ``customers`` tables are
     *  also the foreign-key targets of the card seed rows, so they are seeded with ids
     *  ``1``-``50``; these rows exist solely to satisfy referential integrity and are
     *  never asserted upon.
     */
    private static void provisionCrossServicePrerequisites() {
        String[] ddl = {
            // customers: column-verbatim from account-service V1 so validate matches Customer.
            """
            CREATE TABLE IF NOT EXISTS customers (
                cust_id                  BIGINT        PRIMARY KEY,
                cust_first_name          VARCHAR(25)   NOT NULL,
                cust_middle_name         VARCHAR(25)   NOT NULL,
                cust_last_name           VARCHAR(25)   NOT NULL,
                cust_addr_line_1         VARCHAR(50)   NOT NULL,
                cust_addr_line_2         VARCHAR(50)   NOT NULL,
                cust_addr_line_3         VARCHAR(50)   NOT NULL,
                cust_addr_state_cd       VARCHAR(2)    NOT NULL,
                cust_addr_country_cd     VARCHAR(3)    NOT NULL,
                cust_addr_zip            VARCHAR(10)   NOT NULL,
                cust_phone_num_1         VARCHAR(15)   NOT NULL,
                cust_phone_num_2         VARCHAR(15)   NOT NULL,
                cust_ssn                 VARCHAR(512)  NOT NULL,
                cust_govt_issued_id      VARCHAR(512)  NOT NULL,
                cust_dob_yyyy_mm_dd      VARCHAR(10)   NOT NULL,
                cust_eft_account_id      VARCHAR(512)  NOT NULL,
                cust_pri_card_holder_ind VARCHAR(1)    NOT NULL,
                cust_fico_credit_score   INTEGER       NOT NULL,
                version                  BIGINT        NOT NULL DEFAULT 0
            )
            """,
            // accounts: column-verbatim from account-service V2 (money NUMERIC(12,2) + version).
            """
            CREATE TABLE IF NOT EXISTS accounts (
                acct_id                BIGINT         PRIMARY KEY,
                acct_active_status     VARCHAR(1)     NOT NULL,
                acct_curr_bal          NUMERIC(12,2)  NOT NULL,
                acct_credit_limit      NUMERIC(12,2)  NOT NULL,
                acct_cash_credit_limit NUMERIC(12,2)  NOT NULL,
                acct_open_date         VARCHAR(10)    NOT NULL,
                acct_expiraion_date    VARCHAR(10)    NOT NULL,
                acct_reissue_date      VARCHAR(10)    NOT NULL,
                acct_curr_cyc_credit   NUMERIC(12,2)  NOT NULL,
                acct_curr_cyc_debit    NUMERIC(12,2)  NOT NULL,
                acct_addr_zip          VARCHAR(10)    NOT NULL,
                acct_group_id          VARCHAR(10),
                version                BIGINT         NOT NULL DEFAULT 0
            )
            """,
            // transactions: column-verbatim from transaction-service V1.
            """
            CREATE TABLE IF NOT EXISTS transactions (
                tran_id             VARCHAR(16)   PRIMARY KEY,
                tran_type_cd        VARCHAR(2),
                tran_cat_cd         INTEGER,
                tran_source         VARCHAR(10),
                tran_desc           VARCHAR(100),
                tran_amt            NUMERIC(11,2),
                tran_merchant_id    BIGINT,
                tran_merchant_name  VARCHAR(50),
                tran_merchant_city  VARCHAR(50),
                tran_merchant_zip   VARCHAR(10),
                tran_card_num       VARCHAR(16),
                tran_orig_ts        VARCHAR(26),
                tran_proc_ts        VARCHAR(26)
            )
            """,
            // tran_cat_bal: column-verbatim from transaction-service V3 (compound key).
            """
            CREATE TABLE IF NOT EXISTS tran_cat_bal (
                trancat_acct_id   BIGINT,
                trancat_type_cd   VARCHAR(2),
                trancat_cd        INTEGER,
                tran_cat_bal      NUMERIC(11,2),
                PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
            )
            """,
            // security_users: column-verbatim from auth-service V1.
            """
            CREATE TABLE IF NOT EXISTS security_users (
                sec_usr_id    VARCHAR(8)   PRIMARY KEY,
                sec_usr_fname VARCHAR(20)  NOT NULL,
                sec_usr_lname VARCHAR(20)  NOT NULL,
                sec_usr_pwd   VARCHAR(100) NOT NULL,
                sec_usr_type  VARCHAR(1)   NOT NULL,
                CONSTRAINT chk_sec_usr_type CHECK (sec_usr_type IN ('A','U'))
            )
            """,
            // disclosure_group: derived from DiscGroup (no owning migration in this tranche).
            """
            CREATE TABLE IF NOT EXISTS disclosure_group (
                dis_acct_group_id VARCHAR(10)  NOT NULL,
                dis_tran_type_cd  VARCHAR(2)   NOT NULL,
                dis_tran_cat_cd   INTEGER      NOT NULL,
                dis_int_rate      NUMERIC(6,2),
                PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
            )
            """,
            // tran_category: derived from TranCatg (no owning migration in this tranche).
            """
            CREATE TABLE IF NOT EXISTS tran_category (
                tran_type_cd       VARCHAR(2)  NOT NULL,
                tran_cat_cd        INTEGER     NOT NULL,
                tran_cat_type_desc VARCHAR(50),
                PRIMARY KEY (tran_type_cd, tran_cat_cd)
            )
            """,
            // tran_type: derived from TranType (no owning migration in this tranche).
            """
            CREATE TABLE IF NOT EXISTS tran_type (
                tran_type      VARCHAR(2) PRIMARY KEY,
                tran_type_desc VARCHAR(50)
            )
            """,
            // Seed customers 1..50: only the NOT-NULL columns, with benign placeholders.
            """
            INSERT INTO customers (
                cust_id, cust_first_name, cust_middle_name, cust_last_name,
                cust_addr_line_1, cust_addr_line_2, cust_addr_line_3,
                cust_addr_state_cd, cust_addr_country_cd, cust_addr_zip,
                cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id,
                cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind,
                cust_fico_credit_score
            )
            SELECT g, 'First', 'Middle', 'Last', 'Addr1', 'Addr2', 'Addr3',
                   'NC', 'USA', '00000', '0000000000000', '0000000000000',
                   'x', 'x', '1970-01-01', 'x', 'Y', 700
            FROM generate_series(1, 50) AS g
            ON CONFLICT (cust_id) DO NOTHING
            """,
            // Seed accounts 1..50: only the NOT-NULL columns, with benign placeholders.
            """
            INSERT INTO accounts (
                acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,
                acct_cash_credit_limit, acct_open_date, acct_expiraion_date,
                acct_reissue_date, acct_curr_cyc_credit, acct_curr_cyc_debit, acct_addr_zip
            )
            SELECT g, 'Y', 0, 0, 0, '2000-01-01', '2099-12-31', '2000-01-01', 0, 0, '00000'
            FROM generate_series(1, 50) AS g
            ON CONFLICT (acct_id) DO NOTHING
            """
        };
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            for (String sql : ddl) {
                statement.execute(sql);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to provision cross-service prerequisites", ex);
        }
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

    /** :purpose: Application datasource used to neutralize the plaintext-seeded CVV before reads. */
    @Autowired
    private DataSource dataSource;

    /** :purpose: Captured embossed name of the mutation-target card, restored after each test. */
    private String origEmbossedName;

    /** :purpose: Captured active status of the mutation-target card, restored after each test. */
    private String origActiveStatus;

    /** :purpose: Captured expiry date (legacy-spelled) of the mutation-target card. */
    private String origExpiraionDate;

    /** :purpose: Captured CVV of the mutation-target card (neutralized to null before reads). */
    private String origCvvCd;

    /**
     * :purpose: Neutralize the plaintext-seeded card CVV column and snapshot the
     *  mutation-target card. The seed stores the CVV as plaintext, which the
     *  at-rest ``CryptoConverter`` cannot decrypt on read; setting it to ``NULL`` lets
     *  every card read succeed (the converter passes ``null`` through). The snapshot of
     *  card ``CARD_ACCT50`` is captured for deterministic restoration in ``tearDown``.
     */
    @BeforeEach
    void setUp() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE cards SET card_cvv_cd = NULL WHERE card_cvv_cd IS NOT NULL");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to neutralize seeded card CVV values", ex);
        }
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
     * :purpose: A non-admin user is scoped to the account carried in its session, so
     *  listing returns only that account's card(s) (account 1 owns exactly one card).
     *  The full PAN is not asserted (PII); scoping is proven via the owning account id.
     */
    @Test
    void nonAdminListScopedToSessionAccount() throws Exception {
        mockMvc.perform(get("/cards")
                        .param("page", "1")
                        .sessionAttr(SESSION_ATTR, userSession(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards", hasSize(1)))
                .andExpect(jsonPath("$.cards[0].cardAcctId").value(1))
                .andExpect(jsonPath("$.cards[0].cardActiveStatus").value("Y"));
    }

    /**
     * :purpose: A supplied account filter that is not a one-to-eleven digit number is
     *  rejected with HTTP 400 and the byte-exact legacy edit message.
     */
    @Test
    void listInvalidAccountFilterReturns400() throws Exception {
        mockMvc.perform(get("/cards")
                        .param("accountId", "123456789012")
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
        mockMvc.perform(get("/cards/{cardNumber}", CARD_ACCT1)
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardAcctId").value(1))
                .andExpect(jsonPath("$.cardEmbossedName").value("Immanuel Kessler"))
                .andExpect(jsonPath("$.cardExpiraionDate").value("2025-05-20"))
                .andExpect(jsonPath("$.cardActiveStatus").value("Y"))
                .andExpect(jsonPath("$.custId").value(1));

        // The cross-reference repository wires and the CXACAIX linkage for account 1
        // resolves to customer 1 (seed: xref_cust_id == xref_acct_id == card_acct_id).
        var crossReference = cardXrefRepository.findByXrefAcctId(1L);
        assertThat(crossReference).isPresent();
        assertThat(crossReference.get().getXrefCustId()).isEqualTo(1L);
    }

    /**
     * :purpose: A syntactically valid but unseeded card number yields HTTP 404 with the
     *  byte-exact legacy not-found message.
     */
    @Test
    void getCardDetailNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/cards/{cardNumber}", UNSEEDED_CARD)
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Did not find cards for this search condition"));
    }

    /**
     * :purpose: A malformed (non-sixteen-digit) card-number path variable yields HTTP 400
     *  with the byte-exact controller edit message.
     */
    @Test
    void getCardDetailMalformedNumberReturns400() throws Exception {
        mockMvc.perform(get("/cards/{cardNumber}", "123")
                        .sessionAttr(SESSION_ATTR, adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Card number if supplied must be a 16 digit number"));
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
        request.setCardEmbossedName(origEmbossedName);
        request.setCardActiveStatus(newStatus);
        request.setCardExpiraionDate(origExpiraionDate);
        request.setCardCvvCd(null);

        mockMvc.perform(put("/cards/{cardNumber}", CARD_ACCT50)
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
        mockMvc.perform(put("/cards/{cardNumber}", CARD_ACCT50)
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
}

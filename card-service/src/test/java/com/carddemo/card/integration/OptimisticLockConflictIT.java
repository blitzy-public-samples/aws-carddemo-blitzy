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
package com.carddemo.card.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.service.CardService;
import com.carddemo.common.domain.Card;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.OptimisticLockConflictException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * :purpose: Mandatory optimistic-lock (HTTP 409) integration test for the card-update
 *     flow (``COCRDUPC``, CICS ``CCUP``). Boots the full card-service Spring Boot context
 *     against a real ``postgres:18`` Testcontainer and proves that a concurrent
 *     modification of a card surfaces the frozen conflict message
 *     ``"Record changed by some one else. Please review"`` as HTTP 409, produced by
 *     ``CardService`` throwing ``OptimisticLockConflictException`` and the imported
 *     ``GlobalExceptionHandler`` mapping it to 409.
 * :output: A Failsafe integration test (class name ends in ``IT``) exercising the
 *     read-snapshot-compare-rewrite concurrency check (``9300-CHECK-CHANGE-IN-REC``).
 * :note: ``Card`` carries no JPA ``@Version`` column; the 409 is produced entirely at the
 *     service layer by a field-by-field compare of the re-read card against the
 *     display-time snapshot (``CCUP-OLD-*``) carried in the request. See
 *     ``docs/decision-log.md``.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OptimisticLockConflictIT {

    /**
     * :purpose: Shared, manually-managed ``postgres:18`` container (singleton pattern). It is
     *     started and provisioned once in the static initializer below, before the Spring
     *     context refreshes, so the card Flyway migrations can create and seed the
     *     ``cards``/``card_xref`` tables against the cross-service prerequisite schema.
     */
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("carddemo")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
        provisionPrerequisiteSchema();
    }

    /**
     * :purpose: HTTP session attribute key under which the pseudo-conversational
     *     :java:type:`SessionContext` is carried, matching ``CardController``.
     */
    private static final String SESSION_KEY = "carddemoSessionContext";

    /** :purpose: A real seeded card (``V3__seed_cards.sql``); all seeded cards are status ``'Y'``. */
    private static final String TARGET_CARD_NUM = "0500024453765740";

    /** :purpose: Seeded embossed name for the target card. */
    private static final String SEED_NAME = "Aniya Von";

    /** :purpose: Seeded expiry date (legacy-spelled ``CARD-EXPIRAION-DATE``) for the target card. */
    private static final String SEED_EXPIRY = "2023-03-09";

    /** :purpose: Seeded active status for the target card. */
    private static final String SEED_STATUS = "Y";

    /**
     * :purpose: Provision the cross-service prerequisite schema the card-service context
     *     requires. ``CardServiceApplication`` entity-scans ``com.carddemo.common.domain`` and
     *     runs with ``ddl-auto: validate``, so every mapped entity's table must exist before
     *     the context refreshes; the card migrations additionally foreign-key into
     *     ``accounts``/``customers`` (owned by other services, absent from this classpath).
     *     This copies the owning services' table definitions verbatim (customers, accounts,
     *     security_users, transactions, tran_cat_bal) and derives the three reference tables
     *     that have no migration (disclosure_group, tran_type, tran_category), then seeds
     *     customers and accounts ids 1..50 so the card foreign keys resolve. The
     *     ``cards``/``card_xref`` tables are intentionally NOT created here; the real card
     *     Flyway ``V1``..``V4`` create and seed them.
     * :note: A non-empty schema plus ``baseline-on-migrate: true`` would otherwise baseline at
     *     version 1 and skip card ``V1``; the ``@DynamicPropertySource`` below sets
     *     ``spring.flyway.baseline-version=0`` so all card migrations run.
     */
    private static void provisionPrerequisiteSchema() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {

            // --- customers (account-service V1__create_customers_table.sql, verbatim) --------
            statement.execute("""
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
                    )""");

            // --- accounts (account-service V2__create_accounts_table.sql, verbatim) ----------
            statement.execute("""
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
                    )""");

            // --- security_users (auth-service V1__create_security_users_table.sql, verbatim) -
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS security_users (
                        sec_usr_id    VARCHAR(8)   PRIMARY KEY,
                        sec_usr_fname VARCHAR(20)  NOT NULL,
                        sec_usr_lname VARCHAR(20)  NOT NULL,
                        sec_usr_pwd   VARCHAR(100) NOT NULL,
                        sec_usr_type  VARCHAR(1)   NOT NULL,
                        CONSTRAINT chk_sec_usr_type CHECK (sec_usr_type IN ('A','U'))
                    )""");

            // --- transactions (transaction-service V1__create_transactions_table.sql) --------
            statement.execute("""
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
                    )""");

            // --- tran_cat_bal (transaction-service V3__create_tran_cat_bal_table.sql) --------
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS tran_cat_bal (
                        trancat_acct_id   BIGINT,
                        trancat_type_cd   VARCHAR(2),
                        trancat_cd        INTEGER,
                        tran_cat_bal      NUMERIC(11,2),
                        PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
                    )""");

            // --- disclosure_group (derived from DiscGroup @Entity; no migration exists) ------
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS disclosure_group (
                        dis_acct_group_id VARCHAR(10)  NOT NULL,
                        dis_tran_type_cd  VARCHAR(2)   NOT NULL,
                        dis_tran_cat_cd   INTEGER      NOT NULL,
                        dis_int_rate      NUMERIC(6,2),
                        PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
                    )""");

            // --- tran_type (derived from TranType @Entity; no migration exists) --------------
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS tran_type (
                        tran_type      VARCHAR(2)  PRIMARY KEY,
                        tran_type_desc VARCHAR(50)
                    )""");

            // --- tran_category (derived from TranCatg @Entity; no migration exists) ----------
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS tran_category (
                        tran_type_cd       VARCHAR(2)  NOT NULL,
                        tran_cat_cd        INTEGER     NOT NULL,
                        tran_cat_type_desc VARCHAR(50),
                        PRIMARY KEY (tran_type_cd, tran_cat_cd)
                    )""");

            // Seed customers and accounts ids 1..50 so the card foreign keys resolve.
            for (int id = 1; id <= 50; id++) {
                statement.execute("""
                        INSERT INTO customers (
                            cust_id, cust_first_name, cust_middle_name, cust_last_name,
                            cust_addr_line_1, cust_addr_line_2, cust_addr_line_3,
                            cust_addr_state_cd, cust_addr_country_cd, cust_addr_zip,
                            cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id,
                            cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind,
                            cust_fico_credit_score
                        ) VALUES (
                            %d, 'Test', 'M', 'User', 'Addr1', 'Addr2', 'Addr3', 'NY', 'USA',
                            '00000', '0000000000', '0000000000', '000000000',
                            'GOVTID00000000000000', '1990-01-01', 'EFT0000000', 'Y', 700
                        ) ON CONFLICT (cust_id) DO NOTHING""".formatted(id));
                statement.execute("""
                        INSERT INTO accounts (
                            acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,
                            acct_cash_credit_limit, acct_open_date, acct_expiraion_date,
                            acct_reissue_date, acct_curr_cyc_credit, acct_curr_cyc_debit,
                            acct_addr_zip
                        ) VALUES (
                            %d, 'Y', 0.00, 0.00, 0.00, '2020-01-01', '2099-12-31',
                            '2020-01-01', 0.00, 0.00, '00000'
                        ) ON CONFLICT (acct_id) DO NOTHING""".formatted(id));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "Failed to provision cross-service prerequisite schema for card-service IT", ex);
        }
    }

    /**
     * :purpose: Point the Spring datasource at the manually-managed container (overriding the
     *     ``jdbc:tc:`` URL from ``application-test.yml``) and baseline Flyway at version 0 so
     *     the card ``V1``..``V4`` migrations all run against the pre-provisioned schema.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.baseline-version", () -> "0");
    }

    /** :purpose: MockMvc entry point for the card REST endpoints. */
    @Autowired
    private MockMvc mockMvc;

    /** :purpose: Repository used to stage the out-of-band committed modification and reload. */
    @Autowired
    private CardRepository cardRepository;

    /** :purpose: Service used to prove the 409 originates from the service-layer snapshot compare. */
    @Autowired
    private CardService cardService;

    /** :purpose: JSON serializer for request bodies and response parsing. */
    @Autowired
    private ObjectMapper objectMapper;

    /** :purpose: JDBC template for deterministic per-test reset and committed-state assertions. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** :purpose: Persistence context used to clear the first-level cache before reloads. */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * :purpose: Reset the target card to a deterministic baseline before each test — seeded
     *     name, expiry and active status, with the CVV nulled. Nulling the encrypted CVV
     *     column avoids invoking the ``CryptoConverter`` on the plaintext seed value when the
     *     card is later read, keeping the harness self-contained (no PII key required).
     */
    @BeforeEach
    void resetTargetCard() {
        applyBaseline();
    }

    /**
     * :purpose: Restore the target card to the deterministic baseline after each test, so this
     *     class's scenarios and the sibling ``CardViewUpdateIT`` observe the same seed state.
     */
    @AfterEach
    void restoreTargetCard() {
        applyBaseline();
    }

    /**
     * :purpose: Force the target card's editable columns to the seeded baseline and null the
     *     CVV column, committing immediately (no surrounding test transaction).
     */
    private void applyBaseline() {
        jdbcTemplate.update(
                "UPDATE cards SET card_embossed_name = ?, card_expiraion_date = ?, "
                        + "card_active_status = ?, card_cvv_cd = NULL WHERE card_num = ?",
                SEED_NAME, SEED_EXPIRY, SEED_STATUS, TARGET_CARD_NUM);
    }

    /**
     * :purpose: Build an administrator session context (``CDEMO-USER-TYPE 'A'``) so
     *     authorization scoping never interferes with the conflict assertion.
     * :returns: a :java:type:`SessionContext` whose user type is ``CDEMO_USRTYP_ADMIN``.
     */
    private SessionContext adminSession() {
        SessionContext context = new SessionContext();
        context.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        return context;
    }

    /**
     * :purpose: Assemble a card-update request carrying both the new values and the
     *     display-time snapshot (``CCUP-OLD-*``), then serialize it to JSON.
     * :param newName: the new embossed name.
     * :param newStatus: the new active status.
     * :param newExpiry: the new expiry date (legacy-spelled ``CARD-EXPIRAION-DATE``).
     * :param oldName: the snapshot embossed name (``CCUP-OLD-CRDNAME``).
     * :param oldStatus: the snapshot active status (``CCUP-OLD-CRDSTCD``).
     * :param oldExpiry: the snapshot expiry date (``CCUP-OLD-EXPIRAION-DATE``).
     * :returns: the request body as a JSON string.
     * :raises Exception: when serialization fails.
     */
    private String updateJson(String newName, String newStatus, String newExpiry,
                              String oldName, String oldStatus, String oldExpiry) throws Exception {
        CardUpdateRequestDto request = new CardUpdateRequestDto();
        request.setCardEmbossedName(newName);
        request.setCardActiveStatus(newStatus);
        request.setCardExpiraionDate(newExpiry);
        // CVV is left unset (null) on both the new and snapshot sides: the reset nulls the
        // stored CVV, so a null-to-null CVV compare never masks the status-driven conflict.
        request.setOldCardEmbossedName(oldName);
        request.setOldCardActiveStatus(oldStatus);
        request.setOldCardExpiraionDate(oldExpiry);
        return objectMapper.writeValueAsString(request);
    }

    /**
     * :purpose: Read the target card's currently committed active status directly from the
     *     database, bypassing any persistence-context cache.
     * :returns: the committed ``card_active_status`` value for the target card.
     */
    private String currentStatusInDb() {
        return jdbcTemplate.queryForObject(
                "SELECT card_active_status FROM cards WHERE card_num = ?",
                String.class, TARGET_CARD_NUM);
    }

    /**
     * :purpose: Prove the pseudo-conversational concurrent-modification race
     *     (``COCRDUPC`` read-snapshot-compare-rewrite): a client reads the card, someone
     *     else commits a change, and the client's update — carrying the now-stale
     *     display-time snapshot — is rejected with HTTP 409 and the frozen conflict message.
     * :raises Exception: when the MockMvc exchange fails.
     */
    @Test
    void concurrentModificationReturns409WithConflictMessage() throws Exception {
        // 1. Display-time read: capture the snapshot a client would hold on screen.
        String body = mockMvc.perform(get("/cards/{cardNumber}", TARGET_CARD_NUM)
                        .sessionAttr(SESSION_KEY, adminSession()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode displayed = objectMapper.readTree(body);
        String displayName = displayed.get("cardEmbossedName").asText();
        String displayExpiry = displayed.get("cardExpiraionDate").asText();
        String displayStatus = displayed.get("cardActiveStatus").asText();
        assertEquals(SEED_STATUS, displayStatus);

        // 2. Out-of-band committed modification by "someone else": flip the active status.
        //    There is no test-level transaction, so this save commits and becomes visible.
        Card current = cardRepository.findById(TARGET_CARD_NUM).orElseThrow();
        current.setCardActiveStatus("N");
        cardRepository.save(current);
        entityManager.clear();

        // 3. Submit the update carrying the now-stale display-time snapshot -> conflict.
        String requestBody = updateJson(displayName, SEED_STATUS, displayExpiry,
                displayName, displayStatus, displayExpiry);
        mockMvc.perform(put("/cards/{cardNumber}", TARGET_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .sessionAttr(SESSION_KEY, adminSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(OptimisticLockConflictException.MESSAGE));

        // 4. The rejected update must not have further modified the card; the out-of-band
        //    value stands (proving the new value 'Y' was never written).
        entityManager.clear();
        assertEquals("N", currentStatusInDb());
    }

    /**
     * :purpose: Prove the same 409 code path deterministically, without an out-of-band step:
     *     an update whose display-time snapshot (``CCUP-OLD-CRDSTCD``) deliberately disagrees
     *     with the seeded current status is rejected with HTTP 409 and the frozen message.
     * :raises Exception: when the MockMvc exchange fails.
     */
    @Test
    void staleSnapshotUpdateReturns409() throws Exception {
        // The seed status is 'Y'; claim the display-time status was 'N' -> snapshot mismatch.
        String requestBody = updateJson(SEED_NAME, SEED_STATUS, SEED_EXPIRY,
                SEED_NAME, "N", SEED_EXPIRY);
        mockMvc.perform(put("/cards/{cardNumber}", TARGET_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .sessionAttr(SESSION_KEY, adminSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(OptimisticLockConflictException.MESSAGE));

        // No persisted change occurred: the seeded status stands.
        entityManager.clear();
        assertEquals(SEED_STATUS, currentStatusInDb());
    }

    /**
     * :purpose: Prove the 409 originates from the ``CardService`` service-layer snapshot
     *     compare (``9300-CHECK-CHANGE-IN-REC``), independent of the web layer and of any JPA
     *     ``@Version`` (``Card`` has none): a direct ``updateCard`` call with a stale snapshot
     *     throws ``OptimisticLockConflictException`` carrying the frozen message.
     */
    @Test
    void serviceLayerThrowsOptimisticLockConflictException() {
        CardUpdateRequestDto request = new CardUpdateRequestDto();
        request.setCardEmbossedName(SEED_NAME);
        request.setCardActiveStatus(SEED_STATUS);
        request.setCardExpiraionDate(SEED_EXPIRY);
        request.setOldCardEmbossedName(SEED_NAME);
        request.setOldCardActiveStatus("N"); // stale: the seeded current status is 'Y'
        request.setOldCardExpiraionDate(SEED_EXPIRY);

        OptimisticLockConflictException exception = assertThrows(
                OptimisticLockConflictException.class,
                () -> cardService.updateCard(TARGET_CARD_NUM, request, adminSession()));
        assertEquals(OptimisticLockConflictException.MESSAGE, exception.getMessage());

        // The failed conflict check must not have persisted any change.
        assertEquals(SEED_STATUS, currentStatusInDb());
    }
}

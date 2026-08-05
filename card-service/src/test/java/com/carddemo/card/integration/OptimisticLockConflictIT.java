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
import com.carddemo.common.testsupport.MigratedSchemaContainer;
import com.carddemo.common.exception.OptimisticLockConflictException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.containers.PostgreSQLContainer;

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
// The production filter chain IS applied: every request below presents the shared
// session context, which is exactly how a request authenticates in production, so the
// security, correlation-id and hardening filters are all exercised end to end.
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OptimisticLockConflictIT {

    // The shared, already-migrated ``postgres:18`` container from
    // com.carddemo.common.testsupport.MigratedSchemaContainer backs this class: its schema is
    // produced exclusively by the committed Flyway migrations of every owning module, so the
    // card-service context boots with ``ddl-auto: validate`` against the deployed schema and
    // no table definition is fabricated here.
    /**
     * :purpose: Shared, manually-managed ``postgres:18`` container (singleton pattern). It is
     *     started once in the static initializer below so its mapped port is available to
     *     ``@DynamicPropertySource`` before the Spring context refreshes and the shared
     *     carddemo-common Flyway migration set creates and seeds every table.
     */
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("carddemo")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    /**
     * :purpose: HTTP session attribute key under which the pseudo-conversational
     *     :java:type:`SessionContext` is carried, matching ``CardController``.
     */
    private static final String SESSION_KEY = "carddemoSessionContext";

    /** :purpose: A real seeded card (``V3__seed_test_data.sql``); all seeded cards are status ``'Y'``. */
    private static final String TARGET_CARD_NUM = "0500024453765740";

    /** :purpose: Seeded embossed name for the target card. */
    private static final String SEED_NAME = "Aniya Von";

    /** :purpose: Seeded expiry date (legacy-spelled ``CARD-EXPIRAION-DATE``) for the target card. */
    private static final String SEED_EXPIRY = "2023-03-09";

    /** :purpose: Seeded active status for the target card. */
    private static final String SEED_STATUS = "Y";

    /**
     * :purpose: Point the Spring datasource at the manually-managed container (overriding the
     *     ``jdbc:tc:`` URL from ``application-test.yml``). The context's own Flyway
     *     auto-configuration then applies the shared carddemo-common migration set
     *     (``V1``..``V4``) to the empty container, creating and seeding every table the
     *     entity-scanned mapping validates against.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        // Every card migration is already applied under the service's own history table, so the
        // Spring-managed Flyway run during context refresh is a no-op; baselining at version 0
        // keeps that run from treating the populated schema as a version-1 baseline.
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
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
     * :purpose: Force the target card's editable columns back to the seeded baseline,
     *     committing immediately (no surrounding test transaction). The ``card_cvv_cd``
     *     column is deliberately left as the migration seeded it: the at-rest converter
     *     hydrates the committed seed, and the CVV is never asserted here.
     */
    private void applyBaseline() {
        jdbcTemplate.update(
                "UPDATE cards SET card_embossed_name = ?, card_expiraion_date = ?, "
                        + "card_active_status = ? WHERE card_num = ?",
                SEED_NAME, SEED_EXPIRY, SEED_STATUS, TARGET_CARD_NUM);
    }

    /**
     * :purpose: Build an administrator session context (``CDEMO-USER-TYPE 'A'``) in the
     *     state sign-on leaves it, so authorization scoping never interferes with the
     *     conflict assertion. The user id is required: the service
     *     ``SecurityFilterChain`` derives the authenticated principal from it, so a
     *     context without one is not a signed-on identity and yields 401.
     * :returns: a :java:type:`SessionContext` for signed-on administrator ``ADMIN001``.
     */
    private SessionContext adminSession() {
        SessionContext context = new SessionContext();
        // Sign-on always publishes BOTH the user id and the user type; a context without a
        // user id is not a signed-on session and authorizes nothing.
        context.setUserId("ADMIN001");
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
     * :purpose: Prove that a snapshot-carrying update succeeds against a card whose stored CVV
     *     is populated, and that the stored CVV survives the rewrite untouched. No read path
     *     returns ``CARD-CVV-CD`` (AAP 0.6.7), so a client can never echo it back; when the
     *     ``9300`` compare demanded it anyway EVERY snapshot-bearing update was rejected as a
     *     conflict that had not happened, and the rewrite path nulled the stored CVV.
     * :raises Exception: when the MockMvc exchange fails.
     */
    @Test
    void snapshotUpdateWithoutCvvSucceedsAndPreservesStoredCvv() throws Exception {
        jdbcTemplate.update("UPDATE cards SET card_cvv_cd = ? WHERE card_num = ?",
                "417", TARGET_CARD_NUM);
        entityManager.clear();

        // Display-time read: exactly what a client can observe -- note there is no CVV here.
        String body = mockMvc.perform(get("/cards/{cardNumber}", TARGET_CARD_NUM)
                        .sessionAttr(SESSION_KEY, adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardCvvCd").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        JsonNode displayed = objectMapper.readTree(body);

        // Submit a real change carrying the observable snapshot only (no CVV on either side).
        String requestBody = updateJson("Aniya Vonn", SEED_STATUS, SEED_EXPIRY,
                displayed.get("cardEmbossedName").asText(),
                displayed.get("cardActiveStatus").asText(),
                displayed.get("cardExpiraionDate").asText());
        mockMvc.perform(put("/cards/{cardNumber}", TARGET_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .sessionAttr(SESSION_KEY, adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardEmbossedName").value("Aniya Vonn"))
                .andExpect(jsonPath("$.cardCvvCd").doesNotExist());

        entityManager.clear();
        assertEquals("Aniya Vonn", jdbcTemplate.queryForObject(
                "SELECT card_embossed_name FROM cards WHERE card_num = ?",
                String.class, TARGET_CARD_NUM).trim());
        // Read through the entity (and therefore through CryptoConverter): the rewrite
        // re-encrypts the column with a fresh IV, so the ciphertext is not comparable by
        // value -- the decrypted CVV is, and it must be exactly what was stored before.
        assertEquals("417", cardRepository.findById(TARGET_CARD_NUM).orElseThrow()
                .getCardCvvCd());
    }

    /**
     * :purpose: Prove the ``@Version`` token path end to end: the display read publishes the
     *     version, an update echoing it succeeds and advances it by exactly one, and a second
     *     update replaying the now-stale token is rejected with HTTP 409 without overwriting
     *     the committed value (``COCRDUPC DATA-WAS-CHANGED-BEFORE-UPDATE``, AAP 0.6.2).
     * :raises Exception: when the MockMvc exchange fails.
     */
    @Test
    void versionTokenAdvancesOnceAndStaleTokenReturns409() throws Exception {
        String body = mockMvc.perform(get("/cards/{cardNumber}", TARGET_CARD_NUM)
                        .sessionAttr(SESSION_KEY, adminSession()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long displayedVersion = objectMapper.readTree(body).get("version").asLong();

        CardUpdateRequestDto first = new CardUpdateRequestDto();
        first.setCardEmbossedName("Aniya Vonx");
        first.setCardActiveStatus(SEED_STATUS);
        first.setCardExpiraionDate(SEED_EXPIRY);
        first.setVersion(displayedVersion);
        mockMvc.perform(put("/cards/{cardNumber}", TARGET_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first))
                        .sessionAttr(SESSION_KEY, adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(displayedVersion + 1));

        // Replay the stale token: the second writer must lose rather than overwrite.
        CardUpdateRequestDto replay = new CardUpdateRequestDto();
        replay.setCardEmbossedName("Aniya Vony");
        replay.setCardActiveStatus("N");
        replay.setCardExpiraionDate(SEED_EXPIRY);
        replay.setVersion(displayedVersion);
        mockMvc.perform(put("/cards/{cardNumber}", TARGET_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replay))
                        .sessionAttr(SESSION_KEY, adminSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(OptimisticLockConflictException.MESSAGE));

        entityManager.clear();
        assertEquals("Aniya Vonx", jdbcTemplate.queryForObject(
                "SELECT card_embossed_name FROM cards WHERE card_num = ?",
                String.class, TARGET_CARD_NUM).trim());
        assertEquals(SEED_STATUS, currentStatusInDb());
        assertEquals(displayedVersion + 1, jdbcTemplate.queryForObject(
                "SELECT version FROM cards WHERE card_num = ?", Long.class, TARGET_CARD_NUM));
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
     *     compare (``9300-CHECK-CHANGE-IN-REC``), independent of the web layer and of the JPA
     *     ``@Version`` token: a direct ``updateCard`` call with a stale snapshot
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
                () -> cardService.updateCard(TARGET_CARD_NUM, null, request, adminSession()));
        assertEquals(OptimisticLockConflictException.MESSAGE, exception.getMessage());

        // The failed conflict check must not have persisted any change.
        assertEquals(SEED_STATUS, currentStatusInDb());
    }
}

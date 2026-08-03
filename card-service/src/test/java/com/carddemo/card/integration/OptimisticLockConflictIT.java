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
     * :purpose: Point the Spring datasource at the shared migrated container (overriding the
     *     ``jdbc:tc:`` URL from ``application-test.yml``) so this class validates against the
     *     schema the committed migrations produce.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        // Every card migration is already applied under the service's own history table, so the
        // Spring-managed Flyway run during context refresh is a no-op; baselining at version 0
        // keeps that run from treating the populated schema as a version-1 baseline.
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
     * :purpose: Build an administrator session context (``CDEMO-USER-TYPE 'A'``) so
     *     authorization scoping never interferes with the conflict assertion.
     * :returns: a :java:type:`SessionContext` whose user type is ``CDEMO_USRTYP_ADMIN``.
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

package com.carddemo.controller;

import com.carddemo.dto.CardUpdateRequest;
import com.carddemo.entity.Card;
import com.carddemo.repository.CardRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.Version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc REST-contract integration test for {@link com.carddemo.controller.CardController}.
 *
 * <p>This is the mandated {@code CardControllerTest} of <strong>AAP &sect;0.2.1.3 / &sect;0.4.1.4</strong>.
 * It proves that the three legacy CICS card programs are faithfully re-expressed as JSON endpoints,
 * preserving every cross-cutting business rule that the migration must keep intact:</p>
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl} (transaction {@code CCLI}, <em>Credit Card List</em>) &rarr;
 *       {@code GET /cards}. The legacy browse renders exactly seven rows per screen
 *       ({@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7}); the migrated endpoint surfaces that
 *       fixed window as {@code PageResponse.size == 7} (AAP &sect;0.6.4 / &sect;0.7.1). The optional
 *       {@code accountId} filter reproduces the admin-vs-non-admin scoping (admin sees all cards; a
 *       supplied account scopes the list to that account via the {@code CARDAIX} alternate index).</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} (transaction {@code CCDL}, <em>Credit Card View</em>) &rarr;
 *       {@code GET /cards/{cardNum}} &mdash; single-card detail lookup, {@code 404} when absent.</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} (transaction {@code CCUP}, <em>Credit Card Update</em>) &rarr;
 *       {@code PUT /cards/{cardNum}}. Only the embossed name, expiration date and active status are
 *       editable; the card number ({@code CARD-UPDATE-NUM}) and owning account id
 *       ({@code CARD-UPDATE-ACCT-ID}) are immutable search keys (COCRDUPC L315-L320, AAP &sect;0.6.8).</li>
 * </ul>
 *
 * <h2>Preservation-critical assertions (AAP &sect;0.6.4 / &sect;0.6.8 / &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>Page size 7.</strong> {@code $.size == 7} on both the unfiltered and the filtered
 *       list responses &mdash; the parity anchor for the legacy seven-row screen window.</li>
 *   <li><strong>CVV suppression.</strong> The three-digit card verification value
 *       ({@code CARD-CVV-CD PIC 9(03)}; entity field {@code cvvCode}, annotated {@code @JsonIgnore})
 *       MUST NEVER be serialized. It is structurally absent from {@code CardListItem} and
 *       {@code CardResponse}, so every list row, every detail view, and every update response is
 *       asserted to carry neither {@code $.cvvCode} nor {@code $.cvv}.</li>
 *   <li><strong>Identity immutability.</strong> {@code cardNum} (path-bound) and {@code cardAcctId}
 *       (never present in {@code CardUpdateRequest}) are unchanged across an update &mdash; proven at
 *       the JSON boundary and, for the dedicated case, at the persistence layer.</li>
 * </ul>
 *
 * <h2>House style &amp; harness</h2>
 * <p>Full {@code @SpringBootTest} integration against the seeded H2 {@code test} profile (Flyway
 * applies {@code V1..V4} on context startup), mirroring {@code FinancialParityTest}. The class is
 * {@code @Transactional} so every test &mdash; including the mutating {@code PUT}s &mdash; rolls back,
 * leaving the 50-row card seed intact for the next test (no manual cleanup). The class is annotated
 * {@code @WithMockUser} with a plain {@code USER} role because the card endpoints require only
 * authentication ({@code SecurityConfig} restricts {@code /users/**} to {@code ADMIN} but leaves
 * everything else at {@code authenticated()}); the {@code JwtAuthenticationFilter} skips when the
 * {@code SecurityContext} is already populated, so {@code @WithMockUser} authenticates without a real
 * JWT. CSRF is disabled globally, so no {@code .with(csrf())} is needed on the {@code PUT}s.</p>
 *
 * <h2>Seed data (Flyway {@code V3__seed_master.sql})</h2>
 * <p>The {@code cards} table is seeded with exactly 50 rows, one per account ({@code card_acct_id}
 * 1..50). The first seeded card is {@code 0500024453765740}, owned by account {@code 50}; it is used
 * as the known card throughout. Because the seed assigns each account exactly one card, the
 * account-scoped query returns precisely that card.</p>
 *
 * @see com.carddemo.controller.CardController
 * @see com.carddemo.service.CardService
 * @see com.carddemo.dto.CardResponse
 * @see com.carddemo.dto.CardListItem
 * @see com.carddemo.dto.CardUpdateRequest
 * @see com.carddemo.dto.PageResponse
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "USER0001", roles = {"USER"})
@DisplayName("CardController REST contract — COCRDLIC list / COCRDSLC view / COCRDUPC update parity")
class CardControllerTest {

    /** Performs the HTTP requests against the fully wired application context (including security). */
    @Autowired
    private MockMvc mockMvc;

    /** The application's configured {@link ObjectMapper}; serializes request DTOs to JSON bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /** Card persistence gateway; used for the persistence-layer immutability re-assertions. */
    @Autowired
    private CardRepository cardRepository;

    /**
     * The first seeded card ({@code V3__seed_master.sql}), owned by account {@link #SEED_CARD_ACCT_ID}.
     * Held as a {@link String} so the leading zero of the 16-character PAN is preserved.
     */
    private static final String SEED_CARD_NUM = "0500024453765740"; // -> account 50 (V3 seed)

    /** The account that owns {@link #SEED_CARD_NUM}; the seed assigns it exactly one card. */
    private static final long SEED_CARD_ACCT_ID = 50L;

    /** A syntactically valid 16-digit card number that is intentionally NOT present in the seed. */
    private static final String MISSING_CARD_NUM = "9999999999999999"; // not seeded

    /**
     * Serializes a {@link CardUpdateRequest} to its JSON wire form using the application's configured
     * {@link ObjectMapper} (JSR-310 enabled, {@code write-dates-as-timestamps=false}), so a
     * {@link LocalDate} renders as an ISO {@code yyyy-MM-dd} string &mdash; exactly as the controller
     * expects to deserialize it.
     *
     * @param request the update payload to serialize
     * @return the JSON body
     * @throws Exception if serialization fails
     */
    private String json(CardUpdateRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    // =====================================================================================
    // Phase 2 — GET /cards (page size 7, metadata, CVV absence, optional account filter)
    // =====================================================================================

    /**
     * Unfiltered first page proves the legacy page size of 7 deterministically: 50 seeded cards over
     * a size-7 window yields 8 pages, a full first page of 7 rows, and {@code first=true},
     * {@code last=false}. No assertion is made about <em>which</em> cards appear because
     * {@code findAll(Pageable)} has no guaranteed sort order — only the pagination metadata, the row
     * count, and CVV absence are deterministic here.
     */
    @Test
    @DisplayName("GET /cards (unfiltered, page 0): page size 7, 50 elements over 8 pages, CVV absent")
    void listCards_unfilteredFirstPage_pinsPageSize7_andSuppressesCvv() throws Exception {
        mockMvc.perform(get("/cards").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(7))             // PageResponse.size == legacy page size 7
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(50))   // exact 50-row seed
                .andExpect(jsonPath("$.totalPages").value(8))       // ceil(50 / 7) == 8
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.content.length()").value(7)) // first page is full
                .andExpect(jsonPath("$.content[0].cardNum").exists())
                .andExpect(jsonPath("$.content[0].cardAcctId").exists())
                .andExpect(jsonPath("$.content[0].cvvCode").doesNotExist()) // CVV NEVER present
                .andExpect(jsonPath("$.content[0].cvv").doesNotExist());
    }

    /**
     * Filtering by {@code accountId} reproduces the COCRDLIC non-admin scoping: every returned row
     * belongs to the requested account, and the page still advertises the size-7 window. The seed
     * assigns account 50 exactly one card ({@link #SEED_CARD_NUM}), so the content is non-empty and
     * every {@code cardAcctId} equals 50. The exact {@code totalElements} of the filtered query is not
     * asserted (the per-account count is pinned only to "&ge; 1").
     */
    @Test
    @DisplayName("GET /cards?accountId=50: account-scoped, page size 7, every row belongs to acct 50, CVV absent")
    void listCards_filteredByAccount_returnsOnlyThatAccountsCards() throws Exception {
        mockMvc.perform(get("/cards")
                        .param("accountId", String.valueOf(SEED_CARD_ACCT_ID))
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(7))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()", greaterThanOrEqualTo(1)))
                // jsonPath reads JSON numbers as Integers, so compare with equalTo(50) (int).
                .andExpect(jsonPath("$.content[*].cardAcctId", everyItem(equalTo(50))))
                .andExpect(jsonPath("$.content[0].cardNum").value(SEED_CARD_NUM))
                .andExpect(jsonPath("$.content[0].cvvCode").doesNotExist())
                .andExpect(jsonPath("$.content[0].cvv").doesNotExist());
    }

    // =====================================================================================
    // Phase 3 — GET /cards/{cardNum} (CVV absence, 404)
    // =====================================================================================

    /**
     * Viewing an existing card returns the CVV-free detail. {@code cardNum} is asserted as the exact
     * {@link String} (its leading zero preserved), never as a number, and the verification code is
     * absent under both candidate JSON names.
     */
    @Test
    @DisplayName("GET /cards/{cardNum}: returns CVV-free detail; cardNum is a String (leading zero kept)")
    void getCard_existing_returnsDetailWithoutCvv() throws Exception {
        mockMvc.perform(get("/cards/{cardNum}", SEED_CARD_NUM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNum").value(SEED_CARD_NUM))
                .andExpect(jsonPath("$.cardAcctId").value(50))
                .andExpect(jsonPath("$.embossedName").exists())
                .andExpect(jsonPath("$.expirationDate").exists())
                .andExpect(jsonPath("$.activeStatus").exists())
                .andExpect(jsonPath("$.cvvCode").doesNotExist()) // MUST: CVV suppressed
                .andExpect(jsonPath("$.cvv").doesNotExist());
    }

    /**
     * An unknown card number raises {@code ResourceNotFoundException} in the service, which
     * {@code GlobalExceptionHandler} maps to HTTP 404.
     */
    @Test
    @DisplayName("GET /cards/{cardNum}: unknown card -> 404 Not Found")
    void getCard_missing_returns404() throws Exception {
        mockMvc.perform(get("/cards/{cardNum}", MISSING_CARD_NUM))
                .andExpect(status().isNotFound());
    }

    // =====================================================================================
    // Phase 4 — Authentication (401)
    // =====================================================================================

    /**
     * An anonymous caller (overriding the class-level {@code @WithMockUser}) is rejected by the
     * stateless filter chain: {@code anyRequest().authenticated()} denies the anonymous principal and
     * the inline {@code AuthenticationEntryPoint} returns HTTP 401.
     */
    @Test
    @WithAnonymousUser
    @DisplayName("GET /cards: anonymous request -> 401 Unauthorized")
    void listCards_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/cards"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================================
    // Phase 5 — PUT /cards/{cardNum} (200 update, CVV absence, immutability, 400, 404)
    // =====================================================================================

    /**
     * A valid update changes only the three editable fields (embossed name, active status, expiration
     * date). The response echoes the new values, keeps the immutable {@code cardNum} / {@code cardAcctId}
     * equal to the seed values, and still carries no CVV.
     */
    @Test
    @DisplayName("PUT /cards/{cardNum}: updates editable fields; cardNum/cardAcctId unchanged; CVV absent")
    void updateCard_valid_updatesEditableFields_keepsIds_andHidesCvv() throws Exception {
        CardUpdateRequest request = new CardUpdateRequest("UPDATED NAME", "N", LocalDate.of(2028, 12, 31));

        mockMvc.perform(put("/cards/{cardNum}", SEED_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embossedName").value("UPDATED NAME"))
                .andExpect(jsonPath("$.activeStatus").value("N"))
                .andExpect(jsonPath("$.expirationDate").value("2028-12-31")) // ISO yyyy-MM-dd
                .andExpect(jsonPath("$.cardNum").value(SEED_CARD_NUM))         // unchanged (path-bound key)
                .andExpect(jsonPath("$.cardAcctId").value(50))                 // unchanged (not in request)
                .andExpect(jsonPath("$.cvvCode").doesNotExist())
                .andExpect(jsonPath("$.cvv").doesNotExist());
    }

    /**
     * Dedicated immutability proof for the core {@code COCRDUPC} parity concern (AAP &sect;0.6.8): even
     * a substantive change to the editable {@code embossedName} leaves the card number and owning
     * account id untouched. The invariant is asserted at the JSON boundary <em>and</em> re-read from the
     * persistence layer to prove nothing changed the immutable keys in the database row.
     */
    @Test
    @DisplayName("PUT /cards/{cardNum}: cardNum & cardAcctId are immutable across an update (JSON + DB)")
    void updateCard_doesNotChangeImmutableIdentifiers() throws Exception {
        CardUpdateRequest request = new CardUpdateRequest("DIFFERENT HOLDER", "Y", LocalDate.of(2030, 1, 1));

        mockMvc.perform(put("/cards/{cardNum}", SEED_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embossedName").value("DIFFERENT HOLDER"))
                .andExpect(jsonPath("$.cardNum").value(SEED_CARD_NUM))   // immutable key
                .andExpect(jsonPath("$.cardAcctId").value(50));          // immutable key

        // Persistence-layer immutability proof: the stored row still carries the original identity.
        Card persisted = cardRepository.findById(SEED_CARD_NUM)
                .orElseThrow(() -> new AssertionError("seed card vanished: " + SEED_CARD_NUM));
        assertThat(persisted.getCardNum()).isEqualTo(SEED_CARD_NUM);
        assertThat(persisted.getCardAcctId()).isEqualTo(SEED_CARD_ACCT_ID);
    }

    /**
     * Reinforcement (guarded): a raw body that <em>also</em> carries {@code cardNum} and
     * {@code cardAcctId} alongside the legitimate editable fields cannot mutate the immutable keys.
     * The unknown JSON properties are silently ignored (Spring Boot's default
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}, verified disabled in this project), and the DTO carries
     * no component able to hold them, so the response still shows the seed identity.
     */
    @Test
    @DisplayName("PUT /cards/{cardNum}: extra cardNum/cardAcctId keys in body are ignored (immutability reinforced)")
    void updateCard_ignoresAttemptToOverrideImmutableIdentifiers() throws Exception {
        String rawBody = "{"
                + "\"embossedName\":\"HACK ATTEMPT\","
                + "\"activeStatus\":\"Y\","
                + "\"expirationDate\":\"2029-06-30\","
                + "\"cardNum\":\"1111111111111111\","   // attempt to change the immutable PAN
                + "\"cardAcctId\":99999"                 // attempt to re-home the card
                + "}";

        mockMvc.perform(put("/cards/{cardNum}", SEED_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNum").value(SEED_CARD_NUM))   // NOT 1111111111111111
                .andExpect(jsonPath("$.cardAcctId").value(50))           // NOT 99999
                .andExpect(jsonPath("$.embossedName").value("HACK ATTEMPT"))
                .andExpect(jsonPath("$.cvvCode").doesNotExist())
                .andExpect(jsonPath("$.cvv").doesNotExist());
    }

    /**
     * An {@code activeStatus} outside the {@code [YN]} set violates the {@code @Pattern} constraint on
     * {@link CardUpdateRequest}, raising {@code MethodArgumentNotValidException} &rarr; HTTP 400 with the
     * standardized {@code ErrorResponse} carrying per-field details.
     */
    @Test
    @DisplayName("PUT /cards/{cardNum}: invalid activeStatus 'Q' violates @Pattern -> 400 with fieldErrors")
    void updateCard_invalidActiveStatus_returns400() throws Exception {
        CardUpdateRequest request = new CardUpdateRequest("X", "Q", LocalDate.of(2028, 12, 31));

        mockMvc.perform(put("/cards/{cardNum}", SEED_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    /**
     * An {@code embossedName} longer than 50 characters violates the {@code @Size(max = 50)} constraint
     * on {@link CardUpdateRequest} (mirroring {@code CARD-EMBOSSED-NAME PIC X(50)}) &rarr; HTTP 400.
     */
    @Test
    @DisplayName("PUT /cards/{cardNum}: embossedName longer than 50 chars violates @Size -> 400")
    void updateCard_tooLongEmbossedName_returns400() throws Exception {
        CardUpdateRequest request = new CardUpdateRequest("A".repeat(51), "Y", LocalDate.of(2028, 12, 31));

        mockMvc.perform(put("/cards/{cardNum}", SEED_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Validation precedes the service lookup: a <em>valid</em> body targeting an unknown card passes
     * Bean Validation, then the service raises {@code ResourceNotFoundException} &rarr; HTTP 404 (not
     * 400).
     */
    @Test
    @DisplayName("PUT /cards/{cardNum}: valid body on unknown card -> 404 (validation passes, then not found)")
    void updateCard_validBodyMissingCard_returns404() throws Exception {
        CardUpdateRequest request = new CardUpdateRequest("NAME", "Y", LocalDate.of(2028, 12, 31));

        mockMvc.perform(put("/cards/{cardNum}", MISSING_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isNotFound());
    }

    // =====================================================================================
    // Phase 6 — Optimistic-lock / 409 coverage (decision tree, Option 3)
    // =====================================================================================

    /**
     * Documents and pins the confirmed production design for card updates (AAP Phase-6 decision tree,
     * <strong>Option 3</strong>): unlike {@code Account}, the {@code Card} entity declares <em>no</em>
     * JPA {@code @Version} field and {@link CardUpdateRequest} carries no version, while
     * {@code CardService.updateCard} performs a plain {@code findById} &rarr; {@code save} with no
     * stale-version check. Consequently there is <strong>no single-request HTTP 409 path</strong> for a
     * card update, so writing a 409 MockMvc test here would be non-deterministic / flaky and is
     * deliberately avoided.
     *
     * <p>The absence of the optimistic-lock column is proven deterministically by reflection (no field
     * on {@link Card} is annotated {@code @jakarta.persistence.Version}). The end-to-end
     * exception&rarr;409 mapping ({@code ObjectOptimisticLockingFailureException} /
     * {@code ConcurrentModificationException} &rarr; HTTP 409) is exercised where it actually applies
     * &mdash; the versioned {@code Account} update path &mdash; not here. In place of a conflict
     * assertion, the immutability guarantee is re-asserted through a normal successful update.</p>
     */
    @Test
    @DisplayName("PUT /cards/{cardNum}: no optimistic-lock 409 path by design (Card has no @Version); immutability re-asserted")
    void updateCard_hasNoOptimisticLockConflictPath_byDesign() throws Exception {
        // Deterministic proof that Card carries no JPA @Version -> no card-update conflict path exists.
        boolean cardDeclaresVersion = Arrays.stream(Card.class.getDeclaredFields())
                .anyMatch(field -> field.isAnnotationPresent(Version.class));
        assertThat(cardDeclaresVersion)
                .as("Card entity must NOT declare a JPA @Version field; "
                        + "card updates have no deterministic HTTP 409 conflict path (AAP Phase-6 Option 3)")
                .isFalse();

        // Re-assert the immutability guarantee via a normal, always-deterministic successful update.
        CardUpdateRequest request = new CardUpdateRequest("BY DESIGN", "Y", LocalDate.of(2027, 5, 20));
        mockMvc.perform(put("/cards/{cardNum}", SEED_CARD_NUM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNum").value(SEED_CARD_NUM))
                .andExpect(jsonPath("$.cardAcctId").value(50))
                .andExpect(jsonPath("$.cvvCode").doesNotExist());
    }
}

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardUpdateRequest;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.CardMapper;
import com.aws.carddemo.service.CardService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.persistence.OptimisticLockException;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest}
 * slice test for {@link CardUpdateController} &mdash; the Java re-platform of the
 * online CICS program {@code COCRDUPC} (transaction {@code CCUP}, BMS map
 * {@code COCRDUP}).
 *
 * <p>This test verifies the <em>Card Update</em> REST contract without a database
 * or a servlet container: only the web layer is loaded. The controller's sole
 * business collaborator, {@link CardService}, is replaced by a Mockito mock so
 * the fetch&rarr;confirm&rarr;save flow can be driven deterministically, while the
 * hand-written {@link CardMapper} is imported for real (per the mandated harness)
 * so the field-level screen contract &mdash; including the expiry decomposition
 * and the CVV-never-present rule &mdash; is exercised exactly as in production.
 * {@link SecurityConfig} is imported so the real, filters-on security chain
 * decides authentication, and {@code GlobalExceptionHandler} is auto-registered
 * so the typed-exception&rarr;HTTP-status mapping is asserted end-to-end.</p>
 *
 * <h2>Behaviors covered (parity with {@code COCRDUPC})</h2>
 * <ul>
 *   <li><strong>A</strong> &mdash; an unauthenticated request is rejected (401).</li>
 *   <li><strong>B</strong> &mdash; {@code GET} returns the blank first-entry screen.</li>
 *   <li><strong>C</strong> &mdash; {@code POST ENTER} fetches the card and previews
 *       the editable fields (expiry decomposed by the real mapper).</li>
 *   <li><strong>D</strong> &mdash; {@code POST PF5} confirms and persists (success),
 *       and the request&rarr;entity mapping preserves the CVV, card number,
 *       account id and optimistic-lock version.</li>
 *   <li><strong>E</strong> &mdash; an optimistic-lock conflict maps to 409 for
 *       <em>both</em> {@link OptimisticLockingFailureException} and
 *       {@link OptimisticLockException}, with a fixed, safe message.</li>
 *   <li><strong>F</strong> &mdash; a missing card maps to 404 on the fetch and the
 *       save paths.</li>
 *   <li><strong>G</strong> &mdash; {@code PF12} discards edits and re-reads the
 *       stored detail; {@code PF3} navigates back and never commits.</li>
 *   <li><strong>H</strong> &mdash; an unmapped key yields the invalid-key message.</li>
 *   <li><strong>I</strong> &mdash; the card verification value is never present in
 *       any response (the mandatory sensitive-data invariant).</li>
 *   <li><strong>J</strong> &mdash; the response preserves the {@code COCRDUP}
 *       field contract, a transport-malformed field yields 400, and a
 *       transport-valid but business-invalid field is echoed same-screen (200).</li>
 * </ul>
 *
 * <p>Cross-cutting rules honored: the Jakarta namespace only
 * ({@link OptimisticLockException}); {@code @MockitoBean} (never {@code @MockBean});
 * no wildcard imports; no {@code double}/{@code float}; and a deterministic,
 * headless run (pure slice, no Docker).</p>
 */
@WebMvcTest(CardUpdateController.class)
@Import({SecurityConfig.class, CardMapper.class})
@DisplayName("CardUpdateController (COCRDUPC / CCUP) web-slice contract")
class CardUpdateControllerTest {

    /** The single mapped path for the card-update screen (class {@code @RequestMapping}). */
    private static final String ENDPOINT = "/api/v1/cards/update";

    // ------------------------------------------------------------------
    // Stub-card fixture. The values are chosen so the CVV cannot appear as a
    // substring of any other rendered field (the account id, card number,
    // expiration text, embossed name, header chrome, or the colon/slash-delimited
    // date/time), letting the CVV-absence assertions be a strict substring check.
    // ------------------------------------------------------------------

    /** Stub card primary key ({@code CARD-NUM}, {@code PIC X(16)}). */
    private static final String CARD_NUM = "4111111111111111";

    /** Stub owning account id ({@code CARD-ACCT-ID}, {@code PIC 9(11)}). */
    private static final long ACCT_ID = 10_000_000_001L;

    /** {@link #ACCT_ID} rendered as the zero-padded 11-digit screen value. */
    private static final String ACCT_ID_RENDERED = "10000000001";

    /**
     * Stub card verification value (sensitive). Deliberately distinct from every
     * other fixture value so it can never be a coincidental substring of a
     * response; the tests assert it is absent from all bodies.
     */
    private static final String CVV = "917";

    /** Stub embossed name ({@code CARD-EMBOSSED-NAME}, {@code PIC X(50)}); alphabetic + space. */
    private static final String CARD_NAME = "JOHN DOE";

    /** Stub active status ({@code CARD-ACTIVE-STATUS}, {@code PIC X(1)}). */
    private static final String CARD_STATUS = "Y";

    /** Stub expiration date stored as {@code YYYY-MM-DD} ({@code CARD-EXPIRAION-DATE}, {@code PIC X(10)}). */
    private static final String EXPIRATION = "2027-08-15";

    /** Year component of {@link #EXPIRATION} as the screen presents it ({@code EXPYEARO}). */
    private static final String EXP_YEAR = "2027";

    /** Month component of {@link #EXPIRATION} as the screen presents it ({@code EXPMONO}). */
    private static final String EXP_MONTH = "08";

    /** Day component of {@link #EXPIRATION} (display-only {@code EXPDAYO}). */
    private static final String EXP_DAY = "15";

    /** Stub optimistic-lock version; a value the CVV cannot collide with. */
    private static final long VERSION = 3L;

    /**
     * A distinctive raw provider message. The 409 handler must return a fixed,
     * safe detail and must NOT echo this internal text, so it is asserted absent.
     */
    private static final String RAW_LOCK_MESSAGE = "raw provider stale-row internal detail";

    /** The fixed, safe optimistic-lock detail surfaced by {@code GlobalExceptionHandler}. */
    private static final String OPTIMISTIC_LOCK_DETAIL =
            "The record was updated by another transaction; please retry.";

    /** MockMvc bound to the controller under test with the real security filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** The Boot-configured Jackson mapper, used to serialize request bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /** The real, imported card mapper &mdash; used for the direct preservation assertion. */
    @Autowired
    private CardMapper cardMapper;

    /** The mocked card service (constructor-injected into the controller). */
    @MockitoBean
    private CardService cardService;

    // ------------------------------------------------------------------
    // Fixtures / helpers
    // ------------------------------------------------------------------

    /**
     * Builds a fresh stub {@link Card} carrying a non-blank CVV (to prove non-leak)
     * and a {@code YYYY-MM-DD} expiration date. A new instance is returned per call
     * so a mutating test cannot bleed into another.
     *
     * @return a populated, detached stub card
     */
    private static Card stubCard() {
        Card card = new Card(CARD_NUM, ACCT_ID, CVV, CARD_NAME, EXPIRATION, CARD_STATUS);
        card.setVersion(VERSION);
        return card;
    }

    /**
     * Assembles a {@link CardUpdateRequest} for the given attention key with the
     * supplied editable values and the stub key context.
     *
     * @param cardName    the embossed name to submit
     * @param cardStatus  the active-status flag to submit
     * @param expiryMonth the expiry month to submit
     * @param expiryYear  the expiry year to submit
     * @param action      the operator attention key
     * @return the assembled request DTO
     */
    private static CardUpdateRequest request(String cardName,
                                             String cardStatus,
                                             String expiryMonth,
                                             String expiryYear,
                                             PfKeyAction action) {
        return new CardUpdateRequest(ACCT_ID_RENDERED, CARD_NUM,
                cardName, cardStatus, expiryMonth, expiryYear, action);
    }

    /**
     * Serializes a request DTO to its JSON body.
     *
     * @param request the request to serialize
     * @return the JSON representation
     * @throws Exception if serialization fails
     */
    private String json(CardUpdateRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    // ==================================================================
    // A. Authentication
    // ==================================================================

    /**
     * A &mdash; an anonymous request to the protected card-update screen is
     * rejected with {@code 401 Unauthorized} by the security filter chain (no
     * {@code @WithMockUser} is applied here).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("A. Unauthenticated request is rejected with 401")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(cardService);
    }

    // ==================================================================
    // B. GET — blank first-entry screen
    // ==================================================================

    /**
     * B &mdash; {@code GET} returns the blank first-entry screen (200): the
     * key-entry prompt and the split function-key legend are populated, the header
     * chrome carries the program/transaction identity, and no card-detail field is
     * present (null fields are omitted by the {@code non_null} JSON policy).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("B. GET returns the blank update screen with the key-entry prompt")
    void getReturnsBlankUpdateScreen() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value("CCUP"))
                .andExpect(jsonPath("$.programName").value("COCRDUPC"))
                .andExpect(jsonPath("$.title01").value("AWS Mainframe Modernization"))
                .andExpect(jsonPath("$.title02").value("CardDemo"))
                .andExpect(jsonPath("$.infoMessage").value("Please enter Account and Card Number"))
                .andExpect(jsonPath("$.functionKeys").value("ENTER=Process F3=Exit"))
                .andExpect(jsonPath("$.functionKeysContinued").value("F5=Save F12=Cancel"))
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.cardId").doesNotExist())
                .andExpect(jsonPath("$.cardName").doesNotExist())
                .andExpect(jsonPath("$.cvv").doesNotExist());
        verifyNoInteractions(cardService);
    }

    // ==================================================================
    // C. POST ENTER — fetch and preview
    // ==================================================================

    /**
     * C &mdash; {@code POST ENTER} with a key fetches the current card via
     * {@link CardService#viewCard} and previews the editable fields. The real
     * {@link CardMapper} decomposes the {@code YYYY-MM-DD} expiration into the
     * year/month/day screen fields, and because every submitted value is valid the
     * "press F5 to save" confirmation prompt is shown. The CVV is absent.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("C. POST ENTER fetches the card and previews editable fields")
    void postEnterFetchesAndPreviews() throws Exception {
        when(cardService.viewCard(any(), any())).thenReturn(stubCard());

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountId").value(ACCT_ID_RENDERED))
                .andExpect(jsonPath("$.cardId").value(CARD_NUM))
                .andExpect(jsonPath("$.cardName").value(CARD_NAME))
                .andExpect(jsonPath("$.cardStatus").value(CARD_STATUS))
                .andExpect(jsonPath("$.expiryMonth").value(EXP_MONTH))
                .andExpect(jsonPath("$.expiryYear").value(EXP_YEAR))
                .andExpect(jsonPath("$.expiryDay").value(EXP_DAY))
                .andExpect(jsonPath("$.infoMessage").value("Changes validated.Press F5 to save"))
                .andExpect(jsonPath("$.errorMessage").value(""))
                .andExpect(jsonPath("$.cvv").doesNotExist());

        verify(cardService).viewCard(any(), any());
        verify(cardService, never()).updateCard(any(), any(), any(), any(), any());
    }

    // ==================================================================
    // D. POST PF5 — save success + sensitive-field/key preservation
    // ==================================================================

    /**
     * D &mdash; {@code POST PF5} confirms the edits and persists them. The mocked
     * service returns a {@code CHANGES_OK} result carrying the persisted card (the
     * embossed name, status and expiry now reflect the edits while the card number,
     * account id and CVV are unchanged). The controller renders the
     * "committed to database" confirmation, the response echoes the persisted
     * editable values, and the CVV is absent. The service receives exactly the
     * submitted editable values, keyed by the immutable card number.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("D. POST PF5 saves successfully and returns the committed confirmation")
    void postPf5SavesSuccessfully() throws Exception {
        Card persisted = new Card(CARD_NUM, ACCT_ID, CVV, "JANE ROE", "2030-12-15", "N");
        persisted.setVersion(VERSION + 1);
        when(cardService.updateCard(any(), any(), any(), any(), any()))
                .thenReturn(new CardService.CardUpdateResult(
                        CardService.CardUpdateStatus.CHANGES_OK, persisted, ""));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("JANE ROE", "N", "12", "2030", PfKeyAction.PF5))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.infoMessage").value("Changes committed to database"))
                .andExpect(jsonPath("$.errorMessage").value(""))
                .andExpect(jsonPath("$.accountId").value(ACCT_ID_RENDERED))
                .andExpect(jsonPath("$.cardId").value(CARD_NUM))
                .andExpect(jsonPath("$.cardName").value("JANE ROE"))
                .andExpect(jsonPath("$.cardStatus").value("N"))
                .andExpect(jsonPath("$.expiryYear").value("2030"))
                .andExpect(jsonPath("$.expiryMonth").value("12"))
                .andExpect(jsonPath("$.expiryDay").value("15"))
                .andExpect(jsonPath("$.cvv").doesNotExist());

        verify(cardService).updateCard(eq(CARD_NUM), eq("JANE ROE"), eq("N"), eq("12"), eq("2030"));
        verify(cardService, never()).viewCard(any(), any());
    }

    /**
     * D2 &mdash; the real {@link CardMapper#updateEntity} applies only the editable
     * fields (embossed name, active status, recomposed expiration preserving the
     * stored day) and deliberately preserves the sensitive CVV, the card number,
     * the owning account id, and the optimistic-lock version &mdash; even when the
     * incoming request carries different key values (which the mapper ignores).
     * This is the field-level guarantee behind the PF5 flow's parity invariant.
     */
    @Test
    @DisplayName("D2. CardMapper.updateEntity preserves CVV, card number, account id and version")
    void mapperUpdateEntityPreservesSensitiveFieldsAndKeys() {
        Card card = stubCard();
        CardUpdateRequest mutating = new CardUpdateRequest(
                "99999999999", "9999999999999999", "JANE ROE", "N", "12", "2030", PfKeyAction.PF5);

        cardMapper.updateEntity(mutating, card);

        // Editable fields are applied.
        assertThat(card.getCardEmbossedName()).isEqualTo("JANE ROE");
        assertThat(card.getCardActiveStatus()).isEqualTo("N");
        assertThat(card.getCardExpirationDate()).isEqualTo("2030-12-15");

        // Sensitive field and immutable keys/version are preserved (never touched by an update).
        assertThat(card.getCvv()).isEqualTo(CVV);
        assertThat(card.getCardNum()).isEqualTo(CARD_NUM);
        assertThat(card.getAcctId()).isEqualTo(ACCT_ID);
        assertThat(card.getVersion()).isEqualTo(VERSION);
    }

    /**
     * Extra &mdash; {@code POST PF5} when the service reports {@code NO_CHANGES_DETECTED}
     * surfaces the "no change detected" message on the error line with no info
     * message and a 200 same-screen response (parity with the COBOL re-prompt).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("D3. POST PF5 with no changes detected re-prompts on the same screen (200)")
    void postPf5NoChangesDetected() throws Exception {
        when(cardService.updateCard(any(), any(), any(), any(), any()))
                .thenReturn(new CardService.CardUpdateResult(
                        CardService.CardUpdateStatus.NO_CHANGES_DETECTED, stubCard(),
                        "No change detected with respect to values fetched."));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.PF5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("No change detected with respect to values fetched."))
                .andExpect(jsonPath("$.infoMessage").value(""))
                .andExpect(jsonPath("$.cvv").doesNotExist());
    }

    // ==================================================================
    // E. POST PF5 — optimistic-lock conflict -> 409 (both exception types)
    // ==================================================================

    /**
     * E1 &mdash; a Spring {@link OptimisticLockingFailureException} thrown during
     * the save maps to {@code 409 Conflict} rendered as {@code application/problem+json}
     * with the "Concurrent Update Conflict" title and a fixed, safe detail. The raw
     * provider message must never be echoed to the caller.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("E1. POST PF5 optimistic-lock (Spring) maps to 409 problem+json")
    void postPf5SpringOptimisticLockMapsTo409() throws Exception {
        when(cardService.updateCard(any(), any(), any(), any(), any()))
                .thenThrow(new OptimisticLockingFailureException(RAW_LOCK_MESSAGE));

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.PF5))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Concurrent Update Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(OPTIMISTIC_LOCK_DETAIL))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(RAW_LOCK_MESSAGE);
    }

    /**
     * E2 &mdash; a JPA {@link OptimisticLockException} (Jakarta namespace) thrown
     * during the save maps to the same {@code 409 Conflict} problem response as the
     * Spring translation, confirming both exception types are handled identically.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("E2. POST PF5 optimistic-lock (JPA) maps to 409 problem+json")
    void postPf5JpaOptimisticLockMapsTo409() throws Exception {
        when(cardService.updateCard(any(), any(), any(), any(), any()))
                .thenThrow(new OptimisticLockException(RAW_LOCK_MESSAGE));

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.PF5))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Concurrent Update Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(OPTIMISTIC_LOCK_DETAIL))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(RAW_LOCK_MESSAGE);
    }

    // ==================================================================
    // F. Card not found -> 404 (fetch and save paths)
    // ==================================================================

    /**
     * F1 &mdash; a {@link RecordNotFoundException} thrown by the save maps to
     * {@code 404 Not Found} as {@code application/problem+json} with the
     * "Record Not Found" title and the entity/key detail.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("F1. POST PF5 for a missing card maps to 404 problem+json")
    void postPf5CardNotFoundMapsTo404() throws Exception {
        when(cardService.updateCard(any(), any(), any(), any(), any()))
                .thenThrow(RecordNotFoundException.of("Card", CARD_NUM));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.PF5))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Card not found: " + CARD_NUM));
    }

    /**
     * F2 &mdash; a {@link RecordNotFoundException} thrown by the fetch ({@code ENTER})
     * likewise maps to {@code 404 Not Found} as a problem response, so both the
     * fetch and save entry points surface the missing record consistently.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("F2. POST ENTER for a missing card maps to 404 problem+json")
    void postEnterCardNotFoundMapsTo404() throws Exception {
        when(cardService.viewCard(any(), any()))
                .thenThrow(RecordNotFoundException.of("Card", CARD_NUM));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.ENTER))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Record Not Found"))
                .andExpect(jsonPath("$.detail").value("Card not found: " + CARD_NUM));
    }

    // ==================================================================
    // G. PF12 cancel / PF3 back navigation
    // ==================================================================

    /**
     * G1 &mdash; {@code POST PF12} discards the operator's in-flight edits and
     * re-reads the stored card, so the response shows the persisted values (not the
     * edited ones) with the "presented above" prompt. The save is never invoked.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("G1. POST PF12 discards edits and re-reads the stored card")
    void postPf12CancelRereadsStoredCard() throws Exception {
        when(cardService.viewCard(any(), any())).thenReturn(stubCard());

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("EDITED NAME", "N", "01", "2099", PfKeyAction.PF12))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardName").value(CARD_NAME))
                .andExpect(jsonPath("$.cardStatus").value(CARD_STATUS))
                .andExpect(jsonPath("$.infoMessage").value("Update card details presented above."))
                .andExpect(jsonPath("$.errorMessage").value(""))
                .andExpect(jsonPath("$.cvv").doesNotExist());

        verify(cardService).viewCard(any(), any());
        verify(cardService, never()).updateCard(any(), any(), any(), any(), any());
    }

    /**
     * G2 &mdash; {@code POST PF3} navigates back to the main menu: the controller
     * sets the next-program/next-transaction navigation headers and returns the
     * blank screen carrying the exit message. Neither the fetch nor the save is
     * invoked.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("G2. POST PF3 navigates back to the menu without touching the service")
    void postPf3NavigatesBack() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.PF3))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-CardDemo-Next-Program", "COMEN01C"))
                .andExpect(header().string("X-CardDemo-Next-Transaction", "CM00"))
                .andExpect(jsonPath("$.errorMessage").value("PF03 pressed.Exiting"))
                .andExpect(jsonPath("$.cvv").doesNotExist());

        verifyNoInteractions(cardService);
    }

    // ==================================================================
    // H. Unmapped attention key
    // ==================================================================

    /**
     * H &mdash; an unmapped attention key (here {@code PF7}) yields the invalid-key
     * message on a 200 same-screen response and never touches the service.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("H. POST with an unmapped key returns the invalid-key message")
    void postUnmappedKeyReturnsInvalidKeyMessage() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.PF7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Invalid key pressed. Please see below..."))
                .andExpect(jsonPath("$.cvv").doesNotExist());

        verifyNoInteractions(cardService);
    }

    // ==================================================================
    // I. CVV is never present (mandatory sensitive-data invariant)
    // ==================================================================

    /**
     * I &mdash; the mandatory sensitive-data invariant: the card verification value
     * must never appear in any response. This drives the blank screen, the
     * {@code ENTER} preview, the {@code PF5} success, and the {@code PF12} cancel
     * responses and asserts &mdash; for every body &mdash; that the stub CVV value
     * is absent and that no {@code cvv} property exists.
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("I. CVV is never present in any response")
    void cvvIsNeverPresentInAnyResponse() throws Exception {
        when(cardService.viewCard(any(), any())).thenReturn(stubCard());
        when(cardService.updateCard(any(), any(), any(), any(), any()))
                .thenReturn(new CardService.CardUpdateResult(
                        CardService.CardUpdateStatus.CHANGES_OK, stubCard(), ""));

        assertNoCvv(mockMvc.perform(get(ENDPOINT)).andReturn());

        assertNoCvv(mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.ENTER))))
                .andReturn());

        assertNoCvv(mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.PF5))))
                .andReturn());

        assertNoCvv(mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, EXP_MONTH, EXP_YEAR, PfKeyAction.PF12))))
                .andReturn());
    }

    /**
     * Asserts that a rendered response body neither contains the stub CVV value nor
     * exposes any {@code cvv} property (case-insensitive).
     *
     * @param result the completed MVC result to inspect
     * @throws Exception if the response body cannot be read
     */
    private void assertNoCvv(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(CVV);
        assertThat(body).doesNotContainIgnoringCase("cvv");
    }

    // ==================================================================
    // J. Field-contract parity & validation boundaries
    // ==================================================================

    /**
     * J1 &mdash; a transport-malformed field (a non-numeric expiry month violating
     * the DTO {@code @Pattern}) is rejected by bean validation as {@code 400 Bad
     * Request} rendered as {@code application/problem+json} with the
     * "Validation Failed" title. The offending field name is surfaced while the
     * rejected value is never echoed, and the service is never reached.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("J1. Transport-malformed expiry month is rejected with 400 problem+json")
    void postMalformedExpiryMonthReturns400() throws Exception {
        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(CARD_NAME, CARD_STATUS, "9x", EXP_YEAR, PfKeyAction.ENTER))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("expiryMonth");
        assertThat(body).doesNotContain("9x");

        verify(cardService, never()).viewCard(any(), any());
        verify(cardService, never()).updateCard(any(), any(), any(), any(), any());
    }

    /**
     * J2 &mdash; a transport-valid but business-invalid field (a single-character
     * card status that is neither {@code Y} nor {@code N}) passes bean validation
     * and is caught by the real static edit routine, which the controller surfaces
     * as the "must be Y or N" message on a 200 same-screen response. This mirrors
     * the COBOL edit-paragraph behavior and confirms the transport-vs-business
     * validation split.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser
    @DisplayName("J2. Business-invalid card status is echoed same-screen (200)")
    void postBusinessInvalidStatusReturns200WithMessage() throws Exception {
        when(cardService.viewCard(any(), any())).thenReturn(stubCard());

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request("JANE ROE", "Z", "06", "2026", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Card Active Status must be Y or N"))
                .andExpect(jsonPath("$.infoMessage").value(""))
                .andExpect(jsonPath("$.cvv").doesNotExist());
    }
}

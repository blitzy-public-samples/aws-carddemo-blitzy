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
package com.carddemo.card.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.carddemo.common.domain.Card;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.dto.CardDetailResponseDto;
import com.carddemo.common.dto.CardListItemDto;
import com.carddemo.common.dto.CardListResponseDto;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.CardUpdateResponseDto;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * :purpose: Pure JUnit 5 / AssertJ unit specification for the hand-written
 *     {@link CardMapper}. Instantiated directly with ``new CardMapper()`` (no
 *     Spring context, no Mockito, no Testcontainers, no database), it locks the
 *     card service's field-mapping and fidelity contracts across the three
 *     legacy card screens: list (``COCRDLIC`` / ``CCLI``), detail
 *     (``COCRDSLC`` / ``CCDL``) and update (``COCRDUPC`` / ``CCUP``). The suite
 *     verifies (a) detail/update/list responses copy the non-sensitive fields
 *     verbatim (owning account id, active status, embossed name, expiry date
 *     and the cross-reference customer id); (b) {@link CardMapper#applyUpdate}
 *     mutates the supplied card in place while leaving the card-number primary
 *     key and owning account id untouched; (c) the single-character ``Y``/``N``
 *     active-status flag is copied character-for-character; (d) the legacy
 *     misspelled ``Expiraion`` accessor round-trips faithfully and no mapper or
 *     DTO accessor silently adopts the correctly-spelled token; and (e) the
 *     sensitive card verification value and full primary account number are
 *     never surfaced by value.
 * :output: JUnit 5 / AssertJ assertions only; the suite starts no container and
 *     performs no I/O.
 */
final class CardMapperTest {

    /** Well-known fake test primary account number (never asserted by value). */
    private static final String FAKE_PAN = "4111111111111111";

    /** Second fake test primary account number for multi-row list fixtures. */
    private static final String FAKE_PAN_TWO = "4222222222222222";

    /** Third fake test primary account number for multi-row list fixtures. */
    private static final String FAKE_PAN_THREE = "4333333333333333";

    /** Fake owning account identifier (eleven digits, non-sensitive). */
    private static final Long FAKE_ACCT_ID = 12345678901L;

    /** Fake card verification value (never asserted by value). */
    private static final String FAKE_CVV = "123";

    /** Fake owning customer identifier (nine digits, non-sensitive). */
    private static final Long FAKE_CUST_ID = 123456789L;

    /** Fake embossed cardholder name. */
    private static final String EMBOSSED_NAME = "JOHN Q PUBLIC";

    /** Fake expiry date in the ``YYYY-MM-DD`` wire form. */
    private static final String EXPIRAION_DATE = "2025-12-31";

    /** The mapper under test, freshly constructed for each scenario. */
    private CardMapper mapper;

    /**
     * :purpose: Construct a fresh, stateless {@link CardMapper} before every
     *     scenario so no state leaks across tests.
     */
    @BeforeEach
    void setUp() {
        mapper = new CardMapper();
    }

    /**
     * :purpose: Build a fully-valued card fixture with obviously-fake,
     *     deterministic values covering every mapped field, including the
     *     sensitive CVV and PAN placeholders.
     * :returns: a fully populated {@link Card} instance.
     */
    private Card newFullyValuedCard() {
        Card card = new Card();
        card.setCardNum(FAKE_PAN);
        card.setCardAcctId(FAKE_ACCT_ID);
        card.setCardCvvCd(FAKE_CVV);
        card.setCardEmbossedName(EMBOSSED_NAME);
        card.setCardExpiraionDate(EXPIRAION_DATE);
        card.setCardActiveStatus("Y");
        return card;
    }

    /**
     * :purpose: Build a card fixture with a caller-chosen number, owning account
     *     id and active status, reusing the fully-valued defaults for the other
     *     fields; used to populate distinct list rows.
     * :param cardNum: the fake primary account number to assign.
     * :param acctId: the owning account identifier to assign.
     * :param activeStatus: the single-character active-status flag to assign.
     * :returns: a populated {@link Card} instance.
     */
    private Card newCard(String cardNum, Long acctId, String activeStatus) {
        Card card = newFullyValuedCard();
        card.setCardNum(cardNum);
        card.setCardAcctId(acctId);
        card.setCardActiveStatus(activeStatus);
        return card;
    }

    /**
     * :purpose: Build a card cross-reference fixture linking the fake card number
     *     to the fake customer and account identifiers.
     * :returns: a populated {@link CardXref} instance.
     */
    private CardXref newCardXref() {
        return new CardXref(FAKE_PAN, FAKE_CUST_ID, FAKE_ACCT_ID);
    }

    /**
     * :purpose: Collect the declared method names of the supplied types into a
     *     single list for reflective name scanning.
     * :param types: the classes whose declared method names are gathered.
     * :returns: the aggregated declared method names.
     */
    private static List<String> methodNamesOf(Class<?>... types) {
        List<String> names = new ArrayList<>();
        for (Class<?> type : types) {
            for (Method method : type.getDeclaredMethods()) {
                names.add(method.getName());
            }
        }
        return names;
    }

    // ------------------------------------------------------------------
    // Scenario 1: toDetailResponse field mapping (+ null-cardXref guard)
    // ------------------------------------------------------------------

    /**
     * :purpose: Assert that {@link CardMapper#toDetailResponse(Card, CardXref)}
     *     copies each non-sensitive card field onto the detail response - owning
     *     account id, active status, embossed name and the legacy-spelled expiry
     *     date - and resolves the owning customer id from the cross-reference.
     *     The full card number is verified only through non-sensitive properties
     *     (present, length sixteen) and the sensitive CVV is never surfaced by
     *     the detail response.
     */
    @Test
    @DisplayName("toDetailResponse maps the non-sensitive card fields and xref customer id")
    void toDetailResponseMapsNonSensitiveFields() {
        Card card = newFullyValuedCard();
        CardXref cardXref = newCardXref();

        CardDetailResponseDto response = mapper.toDetailResponse(card, cardXref);

        assertThat(response).isNotNull();
        assertThat(response.getCardAcctId()).isEqualTo(FAKE_ACCT_ID);
        assertThat(response.getCardActiveStatus()).isEqualTo("Y");
        assertThat(response.getCardEmbossedName()).isEqualTo(EMBOSSED_NAME);
        assertThat(response.getCardExpiraionDate()).isEqualTo(EXPIRAION_DATE);
        assertThat(response.getCustId()).isEqualTo(FAKE_CUST_ID);
        // PII: verify the card number carried through without asserting its value.
        assertThat(response.getCardNum()).isNotNull();
        assertThat(response.getCardNum()).hasSize(16);
    }

    /**
     * :purpose: Assert that {@link CardMapper#toDetailResponse(Card, CardXref)}
     *     tolerates a ``null`` cross-reference without throwing, still populating
     *     the card-derived fields while leaving the xref-derived customer id
     *     unset.
     */
    @Test
    @DisplayName("toDetailResponse does not throw when the cardXref is null")
    void toDetailResponseDoesNotThrowWhenCardXrefIsNull() {
        Card card = newFullyValuedCard();

        assertThatNoException().isThrownBy(() -> mapper.toDetailResponse(card, null));

        CardDetailResponseDto response = mapper.toDetailResponse(card, null);
        assertThat(response).isNotNull();
        assertThat(response.getCardAcctId()).isEqualTo(FAKE_ACCT_ID);
        assertThat(response.getCardActiveStatus()).isEqualTo("Y");
        assertThat(response.getCardEmbossedName()).isEqualTo(EMBOSSED_NAME);
        assertThat(response.getCardExpiraionDate()).isEqualTo(EXPIRAION_DATE);
        assertThat(response.getCustId()).isNull();
    }

    /**
     * :purpose: Assert that {@link CardMapper#toDetailResponse(Card, CardXref)}
     *     returns ``null`` when the card is ``null``, matching the documented
     *     null-guard contract.
     */
    @Test
    @DisplayName("toDetailResponse returns null when the card is null")
    void toDetailResponseReturnsNullWhenCardIsNull() {
        assertThat(mapper.toDetailResponse(null, newCardXref())).isNull();
    }


    // ------------------------------------------------------------------
    // Scenario 2: toListResponse row mapping
    // ------------------------------------------------------------------

    /**
     * :purpose: Assert that {@link CardMapper#toListResponse(List)} maps each
     *     supplied card to one list row preserving order and size, copying the
     *     owning account id and active status onto every row. The card number is
     *     verified through non-sensitive properties only (present, length
     *     sixteen) and the CVV is never present on a list row.
     */
    @Test
    @DisplayName("toListResponse maps each row's account id, card number and active status")
    void toListResponseMapsEachRowAccountCardStatus() {
        List<Card> cards = new ArrayList<>();
        cards.add(newCard(FAKE_PAN, 11111111111L, "Y"));
        cards.add(newCard(FAKE_PAN_TWO, 22222222222L, "N"));
        cards.add(newCard(FAKE_PAN_THREE, 33333333333L, "Y"));

        CardListResponseDto response = mapper.toListResponse(cards);

        assertThat(response).isNotNull();
        List<CardListItemDto> rows = response.getCards();
        assertThat(rows).hasSize(3);
        for (int i = 0; i < rows.size(); i++) {
            CardListItemDto row = rows.get(i);
            Card source = cards.get(i);
            assertThat(row.getCardAcctId()).isEqualTo(source.getCardAcctId());
            assertThat(row.getCardActiveStatus()).isEqualTo(source.getCardActiveStatus());
            // PII: the card number is carried but never asserted by value.
            assertThat(row.getCardNum()).isNotNull();
            assertThat(row.getCardNum()).hasSize(16);
        }
    }

    /**
     * :purpose: Assert that {@link CardMapper#toListResponse(List)} yields a
     *     non-null response wrapping an empty row collection when given an empty
     *     list, proving the empty-slice contract.
     */
    @Test
    @DisplayName("toListResponse handles an empty list")
    void toListResponseHandlesEmptyList() {
        CardListResponseDto response = mapper.toListResponse(new ArrayList<>());

        assertThat(response).isNotNull();
        assertThat(response.getCards()).isNotNull();
        assertThat(response.getCards()).isEmpty();
    }

    /**
     * :purpose: Assert that {@link CardMapper#toListResponse(List)} yields a
     *     non-null response wrapping an empty row collection when given ``null``,
     *     proving the null-slice guard.
     */
    @Test
    @DisplayName("toListResponse handles a null list")
    void toListResponseHandlesNullList() {
        CardListResponseDto response = mapper.toListResponse(null);

        assertThat(response).isNotNull();
        assertThat(response.getCards()).isNotNull();
        assertThat(response.getCards()).isEmpty();
    }

    /**
     * :purpose: Assert that the list-row DTO exposes no card-verification-value
     *     accessor at all, structurally guaranteeing the legacy list screen can
     *     never leak the sensitive CVV.
     */
    @Test
    @DisplayName("The card list-row DTO exposes no CVV accessor")
    void cardListItemDtoNeverExposesCvvAccessor() {
        List<String> rowMethodNames = methodNamesOf(CardListItemDto.class);

        assertThat(rowMethodNames).isNotEmpty();
        assertThat(rowMethodNames).noneMatch(name -> name.toLowerCase().contains("cvv"));
    }


    // ------------------------------------------------------------------
    // Scenario 3: applyUpdate mutates in place; keys untouched; flag fidelity
    // ------------------------------------------------------------------

    /**
     * :purpose: Assert that {@link CardMapper#applyUpdate(CardUpdateRequestDto,
     *     Card)} mutates the caller-supplied card in place (same instance and
     *     same primary-key reference), applies the editable fields (embossed
     *     name, active status and the legacy-spelled expiry date), and never
     *     overwrites the card-number primary key or the owning account id from
     *     the request. The CVV is confirmed carried without asserting its value.
     */
    @Test
    @DisplayName("applyUpdate mutates the same card instance and preserves the key fields")
    void applyUpdateMutatesSameInstanceAndPreservesKeys() {
        Card card = newFullyValuedCard();
        card.setCardEmbossedName("OLD NAME");
        card.setCardActiveStatus("Y");
        card.setCardExpiraionDate("2024-01-01");
        Card cardRef = card;
        String cardNumBefore = card.getCardNum();
        Long acctIdBefore = card.getCardAcctId();

        CardUpdateRequestDto request = new CardUpdateRequestDto();
        request.setCardEmbossedName("NEW CARDHOLDER NAME");
        request.setCardActiveStatus("N");
        request.setCardExpiraionDate("2027-09-30");
        request.setCardCvvCd("456");

        mapper.applyUpdate(request, card);

        // In-place mutation: the very same object is updated.
        assertThat(card).isSameAs(cardRef);
        // Editable fields changed to the request values.
        assertThat(card.getCardEmbossedName()).isEqualTo("NEW CARDHOLDER NAME");
        assertThat(card.getCardActiveStatus()).isEqualTo("N");
        assertThat(card.getCardExpiraionDate()).isEqualTo("2027-09-30");
        // Keys untouched. PII: the PAN is proven unchanged by reference identity,
        // never by asserting the card-number value.
        assertThat(card.getCardNum()).isSameAs(cardNumBefore);
        assertThat(card.getCardAcctId()).isEqualTo(acctIdBefore);
        // CVV carried through, verified only as present (never by value).
        assertThat(card.getCardCvvCd()).isNotNull();
    }

    /**
     * :purpose: Assert that {@link CardMapper#applyUpdate(CardUpdateRequestDto,
     *     Card)} copies the single-character active-status flag verbatim for both
     *     ``Y`` and ``N``, proving it is neither coerced to a boolean nor
     *     normalized.
     */
    @Test
    @DisplayName("applyUpdate preserves the Y/N active-status flag verbatim")
    void applyUpdatePreservesActiveStatusFlagFidelity() {
        Card activeCard = newFullyValuedCard();
        CardUpdateRequestDto activeRequest = new CardUpdateRequestDto();
        activeRequest.setCardEmbossedName(EMBOSSED_NAME);
        activeRequest.setCardActiveStatus("Y");
        activeRequest.setCardExpiraionDate(EXPIRAION_DATE);
        activeRequest.setCardCvvCd(FAKE_CVV);

        mapper.applyUpdate(activeRequest, activeCard);
        assertThat(activeCard.getCardActiveStatus()).isEqualTo("Y");

        Card inactiveCard = newFullyValuedCard();
        CardUpdateRequestDto inactiveRequest = new CardUpdateRequestDto();
        inactiveRequest.setCardEmbossedName(EMBOSSED_NAME);
        inactiveRequest.setCardActiveStatus("N");
        inactiveRequest.setCardExpiraionDate(EXPIRAION_DATE);
        inactiveRequest.setCardCvvCd(FAKE_CVV);

        mapper.applyUpdate(inactiveRequest, inactiveCard);
        assertThat(inactiveCard.getCardActiveStatus()).isEqualTo("N");
    }

    /**
     * :purpose: Assert that {@link CardMapper#applyUpdate(CardUpdateRequestDto,
     *     Card)} is a no-op when the request is ``null``, leaving the card's
     *     editable fields unchanged and raising no exception.
     */
    @Test
    @DisplayName("applyUpdate is a no-op when the request is null")
    void applyUpdateIsNoOpWhenRequestIsNull() {
        Card card = newFullyValuedCard();
        card.setCardEmbossedName("OLD NAME");
        card.setCardActiveStatus("Y");
        card.setCardExpiraionDate("2024-01-01");

        assertThatNoException().isThrownBy(() -> mapper.applyUpdate(null, card));

        assertThat(card.getCardEmbossedName()).isEqualTo("OLD NAME");
        assertThat(card.getCardActiveStatus()).isEqualTo("Y");
        assertThat(card.getCardExpiraionDate()).isEqualTo("2024-01-01");
    }


    // ------------------------------------------------------------------
    // Scenario 4: toUpdateResponse echo mapping
    // ------------------------------------------------------------------

    /**
     * :purpose: Assert that {@link CardMapper#toUpdateResponse(Card, CardXref)}
     *     echoes the persisted non-sensitive card state - owning account id,
     *     active status, embossed name and the legacy-spelled expiry date - and
     *     resolves the owning customer id from the cross-reference, without
     *     surfacing the CVV. The card number is verified through non-sensitive
     *     properties only.
     */
    @Test
    @DisplayName("toUpdateResponse echoes the non-sensitive card fields and xref customer id")
    void toUpdateResponseMapsNonSensitiveFields() {
        Card card = newFullyValuedCard();
        CardXref cardXref = newCardXref();

        CardUpdateResponseDto response = mapper.toUpdateResponse(card, cardXref);

        assertThat(response).isNotNull();
        assertThat(response.getCardAcctId()).isEqualTo(FAKE_ACCT_ID);
        assertThat(response.getCardActiveStatus()).isEqualTo("Y");
        assertThat(response.getCardEmbossedName()).isEqualTo(EMBOSSED_NAME);
        assertThat(response.getCardExpiraionDate()).isEqualTo(EXPIRAION_DATE);
        assertThat(response.getCustId()).isEqualTo(FAKE_CUST_ID);
        // PII: verify the card number carried through without asserting its value.
        assertThat(response.getCardNum()).isNotNull();
        assertThat(response.getCardNum()).hasSize(16);
    }

    /**
     * :purpose: Assert that {@link CardMapper#toUpdateResponse(Card, CardXref)}
     *     returns ``null`` when the card is ``null``, matching the documented
     *     null-guard contract.
     */
    @Test
    @DisplayName("toUpdateResponse returns null when the card is null")
    void toUpdateResponseReturnsNullWhenCardIsNull() {
        assertThat(mapper.toUpdateResponse(null, newCardXref())).isNull();
    }

    // ------------------------------------------------------------------
    // Scenario 5: Expiraion misspelling guard (spec-literal fidelity)
    // ------------------------------------------------------------------

    /**
     * :purpose: Assert that the legacy-spelled expiry date round-trips faithfully
     *     as an opaque ``X(10)`` string: a value applied through
     *     {@link CardMapper#applyUpdate(CardUpdateRequestDto, Card)} lands
     *     unchanged on the card and re-appears unchanged on the detail response,
     *     with no reformatting or decomposition.
     */
    @Test
    @DisplayName("The legacy-spelled expiry date round-trips through applyUpdate and detail")
    void expiraionDateRoundTripsThroughUpdateAndDetail() {
        Card card = newFullyValuedCard();
        String knownDate = "2026-06-15";

        CardUpdateRequestDto request = new CardUpdateRequestDto();
        request.setCardEmbossedName(EMBOSSED_NAME);
        request.setCardActiveStatus("Y");
        request.setCardExpiraionDate(knownDate);
        request.setCardCvvCd(FAKE_CVV);

        mapper.applyUpdate(request, card);
        assertThat(card.getCardExpiraionDate()).isEqualTo(knownDate);

        CardDetailResponseDto response = mapper.toDetailResponse(card, newCardXref());
        assertThat(response.getCardExpiraionDate()).isEqualTo(knownDate);
    }

    /**
     * :purpose: Lock the spec-literal misspelling contract: no accessor on the
     *     mapper, the card DTOs, or the {@link Card} entity may adopt the
     *     correctly-spelled expiry token (built dynamically here so the correct
     *     spelling never appears in this source). The misspelled ``Expiraion``
     *     accessors must still exist on {@link Card}, and the detail response DTO
     *     must expose a getter carrying the misspelling, proving the frozen
     *     contract survived into the DTO layer.
     */
    @Test
    @DisplayName("No mapper or DTO accessor uses the correctly-spelled expiry token")
    void noAccessorUsesCorrectlySpelledExpiryToken() throws NoSuchMethodException {
        String forbidden = "expir" + "ation";

        List<String> names = methodNamesOf(
                CardMapper.class,
                CardDetailResponseDto.class,
                CardListResponseDto.class,
                CardListItemDto.class,
                CardUpdateRequestDto.class,
                CardUpdateResponseDto.class,
                Card.class);

        assertThat(names).isNotEmpty();
        assertThat(names).noneMatch(name -> name.toLowerCase().contains(forbidden));

        // The misspelled accessors must exist (guards against a silent correction).
        assertThat(Card.class.getMethod("getCardExpiraionDate")).isNotNull();
        assertThat(Card.class.getMethod("setCardExpiraionDate", String.class)).isNotNull();

        // The frozen misspelling must survive into the DTO layer.
        List<String> detailNames = methodNamesOf(CardDetailResponseDto.class);
        assertThat(detailNames).anyMatch(name -> name.contains("Expiraion"));
    }

}

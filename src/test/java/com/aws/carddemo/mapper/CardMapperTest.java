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
package com.aws.carddemo.mapper;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardListResponse;
import com.aws.carddemo.dto.CardUpdateRequest;
import com.aws.carddemo.dto.CardUpdateResponse;
import com.aws.carddemo.dto.CardViewResponse;
import com.aws.carddemo.dto.PfKeyAction;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast, isolated, pure-logic unit tests for {@link CardMapper}, the hand-written mapper that bridges
 * the {@link Card} JPA entity and the three CardDemo card-screen DTOs. {@code CardMapper} is the Java
 * re-platform of the field-level presentation mapping performed by the three online COBOL card
 * programs (relocated under {@code legacy/**} during migration): {@code COCRDLIC} (transaction
 * {@code CCLI}, card list, BMS map {@code COCRDLI}), {@code COCRDSLC} (transaction {@code CCDL}, card
 * view/detail, BMS map {@code COCRDSL}), and {@code COCRDUPC} (transaction {@code CCUP}, card update,
 * BMS map {@code COCRDUP}). The {@link Card} entity itself derives from the {@code CARD-RECORD}
 * copybook ({@code legacy/cpy/CVACT02Y.cpy}, source {@code app/cpy/CVACT02Y.cpy}).
 *
 * <p>The suite proves two behaviours that carry the highest parity/security risk:</p>
 * <ol>
 *   <li><strong>The card verification value (CVV) is never placed on any response DTO and never
 *       logged</strong> (AAP &sect;0.7.3 hotspot L1 / &sect;0.9.3). The legacy screens
 *       ({@code app/cpy-bms/COCRDLI.CPY}, {@code COCRDSL.CPY}, {@code COCRDUP.CPY}) carry no CVV
 *       field, so neither may any response.</li>
 *   <li><strong>{@link CardMapper#updateEntity(CardUpdateRequest, Card)} preserves</strong> the
 *       entity's {@code cvv}, {@code cardNum}, {@code acctId}, and optimistic-lock {@code version}
 *       (the update screen carries none of them), while recomposing the expiration date and
 *       preserving the existing day the screen does not edit.</li>
 * </ol>
 *
 * <p>The tests run headlessly and reproducibly: there is no Spring context, no database, and no
 * Mockito. {@code CardMapper} lives in this package and is therefore constructed directly and
 * referenced without an import. Every assertion uses a fixed {@link #NOW} clock so the rendered
 * header date/time are deterministic.</p>
 */
class CardMapperTest {

    // ------------------------------------------------------------------------
    // Deterministic fixtures
    // ------------------------------------------------------------------------

    /**
     * A fixed clock; every header-rendering assertion is deterministic against it. The instant
     * mirrors the {@code CVACT02Y.cpy} version stamp (2022-07-19 23:16 CDT) for thematic continuity.
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2022, 7, 19, 23, 15, 58);

    /** {@link #NOW} rendered by {@code DateUtils.formatDateMmDdYy} ({@code MM/dd/uu}). */
    private static final String EXPECTED_DATE = "07/19/22";

    /** {@link #NOW} rendered by {@code DateUtils.formatTimeHhMmSs} ({@code HH:mm:ss}). */
    private static final String EXPECTED_TIME = "23:15:58";

    /** Fixture card number ({@code CARD-NUM PIC X(16)}), primary key. */
    private static final String CARD_NUM = "4111111111111111";

    /** Fixture owning account id ({@code CARD-ACCT-ID PIC 9(11)}). */
    private static final long ACCT_ID = 12345678901L;

    /** {@link #ACCT_ID} rendered as the screen's account-number string. */
    private static final String ACCT_ID_STR = "12345678901";

    /**
     * Fixture CVV ({@code CARD-CVV-CD PIC 9(03)}, SENSITIVE). Chosen as a short, recognisable value
     * so the leakage scans can prove it never reaches a response. Note that the account-number string
     * {@link #ACCT_ID_STR} contains "123" as a substring, so the CVV scans assert exact-equality
     * (never a substring match) to remain free of false positives.
     */
    private static final String CVV = "123";

    /** Fixture embossed name ({@code CARD-EMBOSSED-NAME PIC X(50)}). */
    private static final String EMBOSSED_NAME = "ALICE CARDHOLDER";

    /** Fixture expiration date ({@code CARD-EXPIRAION-DATE PIC X(10)}), {@code YYYY-MM-DD}. */
    private static final String EXPIRATION = "2025-12-31";

    /** Fixture active status flag ({@code CARD-ACTIVE-STATUS PIC X(01)}). */
    private static final String ACTIVE_STATUS = "Y";

    /** The mapper under test; stateless, so a single shared instance is safe. */
    private final CardMapper mapper = new CardMapper();

    /**
     * Builds a fresh, fully populated fixture card (expiration {@code 2025-12-31}, {@code cvv=123},
     * {@code version=0}). A new instance is returned per call so mutating tests never interfere.
     *
     * @return a new fixture {@link Card}
     */
    private static Card newCard() {
        Card card = new Card(CARD_NUM, ACCT_ID, CVV, EMBOSSED_NAME, EXPIRATION, ACTIVE_STATUS);
        card.setVersion(0L);
        return card;
    }

    // ------------------------------------------------------------------------
    // Reflection helpers (record-component contract + value scan)
    // ------------------------------------------------------------------------

    /**
     * Returns the declared record-component names of the supplied record type.
     *
     * @param recordType the record class to inspect; must be a record
     * @return the component names in declaration order
     */
    private static List<String> componentNames(Class<?> recordType) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * Reads the {@link String}-valued record components of the supplied record instance by invoking
     * each accessor reflectively. Non-string and {@code null} component values are omitted.
     *
     * @param instance the record instance to scan
     * @return the string component values (declaration order, nulls omitted)
     */
    private static List<String> stringComponentValues(Object instance) {
        List<String> values = new ArrayList<>();
        for (RecordComponent component : instance.getClass().getRecordComponents()) {
            Object value;
            try {
                value = component.getAccessor().invoke(instance);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(
                        "unable to read record component '" + component.getName() + "'", ex);
            }
            if (value instanceof String text) {
                values.add(text);
            }
        }
        return values;
    }

    // ========================================================================
    // List screen - toListRow / toListResponse (COCRDLIC, CCLI)
    // ========================================================================

    @Nested
    @DisplayName("List mapping (COCRDLIC / CCLI)")
    class ListMapping {

        @Test
        @DisplayName("toListRow projects only account number, card number and status")
        void toListRowProjectsDisplayColumns() {
            CardListResponse.CardListRow row = mapper.toListRow(newCard());

            assertThat(row.accountNumber()).isEqualTo(ACCT_ID_STR);
            assertThat(row.cardNumber()).isEqualTo(CARD_NUM);
            assertThat(row.cardStatus()).isEqualTo(ACTIVE_STATUS);
        }

        @Test
        @DisplayName("toListResponse wraps one row, echoing page number, messages and header clock")
        void toListResponseBuildsSingleRowPage() {
            CardListResponse response = mapper.toListResponse(
                    List.of(newCard()), "1", "info", null, NOW,
                    "CCLI", "Card List", "CardDemo", "COCRDLIC");

            assertThat(response.cards()).hasSize(1);
            CardListResponse.CardListRow row = response.cards().get(0);
            assertThat(row.accountNumber()).isEqualTo(ACCT_ID_STR);
            assertThat(row.cardNumber()).isEqualTo(CARD_NUM);
            assertThat(row.cardStatus()).isEqualTo(ACTIVE_STATUS);

            assertThat(response.pageNumber()).isEqualTo("1");
            assertThat(response.infoMessage()).isEqualTo("info");
            assertThat(response.errorMessage()).isNull();
            assertThat(response.transactionName()).isEqualTo("CCLI");
            assertThat(response.title01()).isEqualTo("Card List");
            assertThat(response.title02()).isEqualTo("CardDemo");
            assertThat(response.programName()).isEqualTo("COCRDLIC");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
        }

        @Test
        @DisplayName("toListResponse skips null entities within the page")
        void toListResponseFiltersNullEntities() {
            List<Card> cards = new ArrayList<>();
            cards.add(newCard());
            cards.add(null);

            CardListResponse response = mapper.toListResponse(
                    cards, "1", null, null, NOW, "CCLI", "t1", "t2", "COCRDLIC");

            assertThat(response.cards()).hasSize(1);
        }

        @Test
        @DisplayName("toListResponse returns an immutable, defensively-copied page")
        void toListResponseReturnsImmutableDefensiveCopy() {
            List<Card> source = new ArrayList<>();
            source.add(newCard());

            CardListResponse response = mapper.toListResponse(
                    source, "1", null, null, NOW, "CCLI", "t1", "t2", "COCRDLIC");

            // Mutating the source list after mapping must not alter the response (defensive copy).
            source.add(newCard());
            assertThat(response.cards()).hasSize(1);

            // The returned page itself is unmodifiable.
            CardListResponse.CardListRow probe = mapper.toListRow(newCard());
            assertThatThrownBy(() -> response.cards().add(probe))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("toListResponse maps a null card list to a non-null, immutable empty page")
        void toListResponseNullListYieldsEmptyPage() {
            CardListResponse response = mapper.toListResponse(
                    null, "1", null, null, NOW, "CCLI", "t1", "t2", "COCRDLIC");

            assertThat(response.cards()).isNotNull().isEmpty();

            CardListResponse.CardListRow probe = mapper.toListRow(newCard());
            assertThatThrownBy(() -> response.cards().add(probe))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("toListResponse with a null clock still renders a header date and time")
        void toListResponseNullNowStillRendersClock() {
            CardListResponse response = mapper.toListResponse(
                    List.of(newCard()), "1", null, null, null,
                    "CCLI", "t1", "t2", "COCRDLIC");

            assertThat(response.currentDate()).isNotBlank();
            assertThat(response.currentTime()).isNotBlank();
        }
    }

    // ========================================================================
    // View screen - toViewResponse (COCRDSLC, CCDL)
    // ========================================================================

    @Nested
    @DisplayName("View mapping (COCRDSLC / CCDL)")
    class ViewMapping {

        @Test
        @DisplayName("toViewResponse copies identifiers, name, status and split expiry (no day)")
        void toViewResponseMapsAllDisplayFields() {
            CardViewResponse response = mapper.toViewResponse(
                    newCard(), "info", "err", NOW,
                    "CCDL", "View Card", "CardDemo", "COCRDSLC", "ENTER=Search F3=Exit");

            assertThat(response.accountId()).isEqualTo(ACCT_ID_STR);
            assertThat(response.cardId()).isEqualTo(CARD_NUM);
            assertThat(response.cardName()).isEqualTo(EMBOSSED_NAME);
            assertThat(response.cardStatus()).isEqualTo(ACTIVE_STATUS);
            assertThat(response.expiryMonth()).isEqualTo("12");
            assertThat(response.expiryYear()).isEqualTo("2025");

            assertThat(response.transactionName()).isEqualTo("CCDL");
            assertThat(response.title01()).isEqualTo("View Card");
            assertThat(response.title02()).isEqualTo("CardDemo");
            assertThat(response.programName()).isEqualTo("COCRDSLC");
            assertThat(response.functionKeys()).isEqualTo("ENTER=Search F3=Exit");
            assertThat(response.infoMessage()).isEqualTo("info");
            assertThat(response.errorMessage()).isEqualTo("err");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
        }

        @Test
        @DisplayName("toViewResponse is null-safe: a null expiration yields blank month and year")
        void toViewResponseHandlesNullExpiration() {
            Card card = new Card(CARD_NUM, ACCT_ID, CVV, EMBOSSED_NAME, null, ACTIVE_STATUS);

            CardViewResponse response = mapper.toViewResponse(
                    card, null, null, NOW, "CCDL", "t1", "t2", "COCRDSLC", "keys");

            assertThat(response.expiryMonth()).isEmpty();
            assertThat(response.expiryYear()).isEmpty();
        }

        @Test
        @DisplayName("toViewResponse is length-safe: a too-short expiration extracts only the year")
        void toViewResponseHandlesShortExpiration() {
            // "2025" is long enough for the year slice (0-4) but not the month slice (5-7);
            // the null-/length-safe helper extracts the year and leaves the month blank
            // without ever throwing IndexOutOfBoundsException.
            Card card = new Card(CARD_NUM, ACCT_ID, CVV, EMBOSSED_NAME, "2025", ACTIVE_STATUS);

            CardViewResponse response = mapper.toViewResponse(
                    card, null, null, NOW, "CCDL", "t1", "t2", "COCRDSLC", "keys");

            assertThat(response.expiryYear()).isEqualTo("2025");
            assertThat(response.expiryMonth()).isEmpty();
        }
    }

    // ========================================================================
    // Update screen response - toUpdateResponse (COCRDUPC, CCUP)
    // ========================================================================

    @Nested
    @DisplayName("Update response mapping (COCRDUPC / CCUP)")
    class UpdateResponseMapping {

        @Test
        @DisplayName("toUpdateResponse adds the display-only expiry day and split PF-key legend")
        void toUpdateResponseMapsAllDisplayFields() {
            CardUpdateResponse response = mapper.toUpdateResponse(
                    newCard(), "info", "err", NOW,
                    "CCUP", "Update Card", "CardDemo", "COCRDUPC",
                    "ENTER=Process F3=Exit", "F5=Save F12=Cancel");

            assertThat(response.accountId()).isEqualTo(ACCT_ID_STR);
            assertThat(response.cardId()).isEqualTo(CARD_NUM);
            assertThat(response.cardName()).isEqualTo(EMBOSSED_NAME);
            assertThat(response.cardStatus()).isEqualTo(ACTIVE_STATUS);
            assertThat(response.expiryMonth()).isEqualTo("12");
            assertThat(response.expiryYear()).isEqualTo("2025");
            // The update screen exposes the day (EXPDAYO) that the view screen omits.
            assertThat(response.expiryDay()).isEqualTo("31");

            assertThat(response.transactionName()).isEqualTo("CCUP");
            assertThat(response.title01()).isEqualTo("Update Card");
            assertThat(response.title02()).isEqualTo("CardDemo");
            assertThat(response.programName()).isEqualTo("COCRDUPC");
            assertThat(response.infoMessage()).isEqualTo("info");
            assertThat(response.errorMessage()).isEqualTo("err");
            assertThat(response.functionKeys()).isEqualTo("ENTER=Process F3=Exit");
            assertThat(response.functionKeysContinued()).isEqualTo("F5=Save F12=Cancel");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
        }

        @Test
        @DisplayName("toUpdateResponse is null-safe: a null expiration yields blank month/year/day")
        void toUpdateResponseHandlesNullExpiration() {
            Card card = new Card(CARD_NUM, ACCT_ID, CVV, EMBOSSED_NAME, null, ACTIVE_STATUS);

            CardUpdateResponse response = mapper.toUpdateResponse(
                    card, null, null, NOW, "CCUP", "t1", "t2", "COCRDUPC", "k1", "k2");

            assertThat(response.expiryMonth()).isEmpty();
            assertThat(response.expiryYear()).isEmpty();
            assertThat(response.expiryDay()).isEmpty();
        }
    }

    // ========================================================================
    // Update in place - updateEntity (COCRDUPC READ-UPDATE-REWRITE)
    // ========================================================================

    @Nested
    @DisplayName("Update-in-place (updateEntity)")
    class UpdateEntityMutation {

        @Test
        @DisplayName("updateEntity applies name/status and recomposes expiry, preserving the day")
        void updateEntityAppliesEditableFieldsPreservingDay() {
            Card card = newCard(); // expiration 2025-12-31

            CardUpdateRequest request = new CardUpdateRequest(
                    ACCT_ID_STR, CARD_NUM, "ALICE M CARDHOLDER", "N",
                    "06", "2027", PfKeyAction.PF5);

            mapper.updateEntity(request, card);

            assertThat(card.getCardEmbossedName()).isEqualTo("ALICE M CARDHOLDER");
            assertThat(card.getCardActiveStatus()).isEqualTo("N");
            // Year + month come from the request; the DAY "31" is preserved from the
            // original "2025-12-31" because the update screen never edits the day.
            assertThat(card.getCardExpirationDate()).isEqualTo("2027-06-31");
        }

        @Test
        @DisplayName("updateEntity preserves cvv, cardNum, acctId and version (screen carries no CVV)")
        void updateEntityPreservesSensitiveAndKeyFields() {
            Card card = newCard();

            CardUpdateRequest request = new CardUpdateRequest(
                    ACCT_ID_STR, CARD_NUM, "ALICE M CARDHOLDER", "N",
                    "06", "2027", PfKeyAction.PF5);

            mapper.updateEntity(request, card);

            // AAP 0.7.3 (L1) / 0.9.3: an update that does not carry the CVV must NEVER
            // null it out or overwrite it; the primary key, foreign key and the
            // optimistic-lock version must likewise survive the update-in-place.
            assertThat(card.getCvv()).isEqualTo(CVV);
            assertThat(card.getCardNum()).isEqualTo(CARD_NUM);
            assertThat(card.getAcctId()).isEqualTo(ACCT_ID);
            assertThat(card.getVersion()).isEqualTo(0L);
        }

        @Test
        @DisplayName("updateEntity defaults the day to 01 when the existing expiration is null")
        void updateEntityDefaultsDayWhenExistingExpirationAbsent() {
            Card card = new Card(CARD_NUM, ACCT_ID, CVV, EMBOSSED_NAME, null, ACTIVE_STATUS);

            CardUpdateRequest request = new CardUpdateRequest(
                    ACCT_ID_STR, CARD_NUM, "BOB CARDHOLDER", "Y",
                    "06", "2027", PfKeyAction.PF5);

            mapper.updateEntity(request, card);

            assertThat(card.getCardExpirationDate()).isEqualTo("2027-06-01");
            // Preservation still holds even on the default-day path.
            assertThat(card.getCvv()).isEqualTo(CVV);
        }

        @Test
        @DisplayName("updateEntity defaults the day to 01 when the existing expiration is too short")
        void updateEntityDefaultsDayWhenExistingExpirationShort() {
            Card card = new Card(CARD_NUM, ACCT_ID, CVV, EMBOSSED_NAME, "2025", ACTIVE_STATUS);

            CardUpdateRequest request = new CardUpdateRequest(
                    ACCT_ID_STR, CARD_NUM, "BOB CARDHOLDER", "Y",
                    "06", "2027", PfKeyAction.PF5);

            mapper.updateEntity(request, card);

            assertThat(card.getCardExpirationDate()).isEqualTo("2027-06-01");
        }
    }

    // ========================================================================
    // SENSITIVE - CVV never on any response (AAP 0.7.3 L1 / 0.9.3)
    // ========================================================================

    @Nested
    @DisplayName("Sensitive data: CVV never exposed (AAP 0.7.3 L1 / 0.9.3)")
    class SensitiveCvv {

        @Test
        @DisplayName("No card response record declares a component named like a CVV")
        void noResponseRecordDeclaresCvvComponent() {
            // Locks the contract at the type level: the CVV can never be added to a
            // response DTO without failing this test. The legacy screens
            // (COCRDLI/COCRDSL/COCRDUP) carry no CVV field, so neither may any response.
            assertThat(componentNames(CardListResponse.class))
                    .allSatisfy(name -> assertThat(name).doesNotContainIgnoringCase("cvv"));
            assertThat(componentNames(CardListResponse.CardListRow.class))
                    .allSatisfy(name -> assertThat(name).doesNotContainIgnoringCase("cvv"));
            assertThat(componentNames(CardViewResponse.class))
                    .allSatisfy(name -> assertThat(name).doesNotContainIgnoringCase("cvv"));
            assertThat(componentNames(CardUpdateResponse.class))
                    .allSatisfy(name -> assertThat(name).doesNotContainIgnoringCase("cvv"));
        }

        @Test
        @DisplayName("No response built from the fixture card carries the CVV value")
        void noResponseCarriesCvvValue() {
            Card card = newCard(); // cvv = "123"

            CardListResponse.CardListRow row = mapper.toListRow(card);
            CardListResponse list = mapper.toListResponse(
                    List.of(card), "1", null, null, NOW, "CCLI", "t1", "t2", "COCRDLIC");
            CardViewResponse view = mapper.toViewResponse(
                    card, null, null, NOW, "CCDL", "t1", "t2", "COCRDSLC", "k");
            CardUpdateResponse update = mapper.toUpdateResponse(
                    card, null, null, NOW, "CCUP", "t1", "t2", "COCRDUPC", "k1", "k2");

            // Exact-equality (never substring): the account number "12345678901" legitimately
            // contains "123", so a substring match would be a false positive. Asserting that no
            // string component EQUALS the CVV cleanly proves the value never leaks.
            assertThat(stringComponentValues(row)).doesNotContain(CVV);
            assertThat(stringComponentValues(list)).doesNotContain(CVV);
            assertThat(stringComponentValues(view)).doesNotContain(CVV);
            assertThat(stringComponentValues(update)).doesNotContain(CVV);

            // Every nested list row is likewise free of the CVV value.
            assertThat(list.cards())
                    .allSatisfy(r -> assertThat(stringComponentValues(r)).doesNotContain(CVV));
        }

        @Test
        @DisplayName("No response toString reveals a field named like a CVV")
        void noResponseToStringRevealsCvv() {
            Card card = newCard();

            CardViewResponse view = mapper.toViewResponse(
                    card, null, null, NOW, "CCDL", "t1", "t2", "COCRDSLC", "k");
            CardUpdateResponse update = mapper.toUpdateResponse(
                    card, null, null, NOW, "CCUP", "t1", "t2", "COCRDUPC", "k1", "k2");

            // The auto-generated record toString names every component; a CVV field would surface.
            assertThat(view.toString()).doesNotContainIgnoringCase("cvv");
            assertThat(update.toString()).doesNotContainIgnoringCase("cvv");
        }
    }

    // ========================================================================
    // Null-argument guards
    // ========================================================================

    @Nested
    @DisplayName("Null-argument guards")
    class NullGuards {

        @Test
        @DisplayName("toListRow rejects a null card")
        void toListRowRejectsNullCard() {
            assertThatThrownBy(() -> mapper.toListRow(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("toViewResponse rejects a null card")
        void toViewResponseRejectsNullCard() {
            assertThatThrownBy(() -> mapper.toViewResponse(
                    null, null, null, NOW, "CCDL", "t1", "t2", "COCRDSLC", "k"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("toUpdateResponse rejects a null card")
        void toUpdateResponseRejectsNullCard() {
            assertThatThrownBy(() -> mapper.toUpdateResponse(
                    null, null, null, NOW, "CCUP", "t1", "t2", "COCRDUPC", "k1", "k2"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("updateEntity rejects a null request")
        void updateEntityRejectsNullRequest() {
            Card card = newCard();
            assertThatThrownBy(() -> mapper.updateEntity(null, card))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("updateEntity rejects a null entity")
        void updateEntityRejectsNullEntity() {
            CardUpdateRequest request = new CardUpdateRequest(
                    ACCT_ID_STR, CARD_NUM, EMBOSSED_NAME, ACTIVE_STATUS,
                    "12", "2025", PfKeyAction.PF5);
            assertThatThrownBy(() -> mapper.updateEntity(request, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

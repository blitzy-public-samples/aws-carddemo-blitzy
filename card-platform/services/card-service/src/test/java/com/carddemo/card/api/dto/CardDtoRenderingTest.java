package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.EventEnvelope;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts that no rendering of a card request or response carries a card number, a cardholder name
 * or an expiry date.
 *
 * <p>The rendering a Java record carries by default prints every component, and every type below is
 * a record. {@link CardUpdateRequest} is the one that matters most: its first component is
 * the full sixteen-digit Primary Account Number, and beside it sit the embossed name and the expiry
 * date in three parts. A number, a name and an expiry date together are what a card-not-present
 * authorization asks for, so the default rendering of that one record is enough to use the card. A
 * log line, an assertion failure, a debugger view or the message of an exception that interpolated
 * the request would persist all of it.
 *
 * <p>Two of the six render every component deliberately, because no component of either is
 * sensitive. Those two are asserted here as well, so that the decision is pinned rather than
 * assumed, and so that a component added later has to be weighed against a failing test instead of
 * appearing silently in every log line that touches the type.
 *
 * <p>Every test runs in memory. None opens a connection, sends a request or reads a file.
 */
@DisplayName("card request and response renderings carry no cardholder data")
class CardDtoRenderingTest {

    /** Row one of {@code app/data/ASCII/carddata.txt}, columns 1 through 16. */
    private static final String CARD_NUMBER = "0500024453765740";

    /** Row one of {@code app/data/ASCII/carddata.txt}, columns 17 through 27. */
    private static final String ACCOUNT_ID = "00000000050";

    /** Row one of {@code app/data/ASCII/carddata.txt}, columns 31 through 80, trimmed. */
    private static final String EMBOSSED_NAME = "Aniya Von";

    /** Row one of {@code app/data/ASCII/carddata.txt}, columns 81 through 90. */
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2023, 3, 9);

    /** The four-digit expiry year of {@link #EXPIRATION_DATE}, as the update request carries it. */
    private static final String EXPIRY_YEAR = "2023";

    /** The two-digit expiry month of {@link #EXPIRATION_DATE}. */
    private static final String EXPIRY_MONTH = "03";

    /** The two-digit expiry day of {@link #EXPIRATION_DATE}. */
    private static final String EXPIRY_DAY = "09";

    /** Column 91 of row one of {@code app/data/ASCII/carddata.txt}. */
    private static final String ACTIVE_STATUS = "Y";

    /**
     * {@link #CARD_NUMBER} in the only form the card responses accept: twelve mask characters then
     * the last four digits. {@link CardDetailResponse} and {@link CardSummary} each refuse any other
     * form in their canonical constructors, which is what makes a rendering of either safe to print
     * the component in full.
     */
    private static final String MASKED = "*".repeat(12) + CARD_NUMBER.substring(12);

    @Nested
    @DisplayName("The update request withholds every value it carries")
    class UpdateRequestRendering {

        @Test
        @DisplayName("the rendering names no card number, name, expiry part or status")
        void theRenderingNamesNoValue() {
            String rendered = new CardUpdateRequest(CARD_NUMBER, EMBOSSED_NAME, EXPIRY_YEAR,
                    EXPIRY_MONTH, EXPIRY_DAY, ACTIVE_STATUS).toString();

            for (String value : List.of(CARD_NUMBER, EMBOSSED_NAME, EXPIRY_YEAR)) {
                assertFalse(rendered.contains(value),
                        "the rendering carries " + value + ": " + rendered);
            }
            assertTrue(rendered.contains("6 of 6 components supplied"),
                    "the rendering reports how many components arrived: " + rendered);
            assertTrue(rendered.contains(EventEnvelope.WITHHELD),
                    "the rendering states that every value is withheld: " + rendered);
        }

        @Test
        @DisplayName("a sparse request reports a lower count, which is the whole diagnostic value")
        void aSparseRequestReportsALowerCount() {
            String rendered = new CardUpdateRequest(CARD_NUMBER, null, "  ", null, null, null)
                    .toString();

            assertTrue(rendered.contains("1 of 6 components supplied"),
                    "a blank component counts as absent and a null one does too: " + rendered);
            assertFalse(rendered.contains(CARD_NUMBER),
                    "the one supplied component is still withheld: " + rendered);
        }

        @Test
        @DisplayName("nothing is masked rather than withheld, because the value may be malformed")
        void aMalformedCardNumberRendersWithoutFailing() {
            String rendered =
                    new CardUpdateRequest("41112222", EMBOSSED_NAME, EXPIRY_YEAR, EXPIRY_MONTH,
                            EXPIRY_DAY, ACTIVE_STATUS).toString();

            assertFalse(rendered.contains("41112222"),
                    "an eight-character number is still withheld: " + rendered);
            assertTrue(rendered.startsWith("CardUpdateRequest["),
                    "the rendering of a record that has not passed its constraints still renders");
        }
    }

    @Nested
    @DisplayName("The detail response withholds the cardholder name and the expiry date")
    class DetailResponseRendering {

        @Test
        @DisplayName("the name and the expiry date are withheld, the identifiers are kept")
        void theNameAndExpiryAreWithheld() {
            String rendered = new CardDetailResponse(MASKED, ACCOUNT_ID, EMBOSSED_NAME,
                    EXPIRATION_DATE, ACTIVE_STATUS).toString();

            assertFalse(rendered.contains(EMBOSSED_NAME),
                    "the rendering carries the embossed name: " + rendered);
            assertFalse(rendered.contains(EXPIRATION_DATE.toString()),
                    "the rendering carries the expiry date: " + rendered);
            assertFalse(rendered.contains(CARD_NUMBER),
                    "the rendering carries a full card number: " + rendered);

            assertTrue(rendered.contains(MASKED),
                    "the rendering publishes the masked number: " + rendered);
            assertTrue(rendered.contains("accountId=" + ACCOUNT_ID),
                    "the rendering keeps the account identifier: " + rendered);
            assertTrue(rendered.contains("activeStatus=" + ACTIVE_STATUS),
                    "the rendering keeps the status flag: " + rendered);
        }
    }

    @Nested
    @DisplayName("The list response counts its page instead of expanding it")
    class ListResponseRendering {

        @Test
        @DisplayName("the rendering names a count and no card")
        void theRenderingNamesACountAndNoCard() {
            CardSummary summary = new CardSummary(MASKED, ACCOUNT_ID, ACTIVE_STATUS);
            String rendered =
                    new CardListResponse(List.of(summary, summary), true, MASKED).toString();

            assertTrue(rendered.contains("cards=2 on this page"),
                    "the rendering counts the page: " + rendered);
            assertFalse(rendered.contains(MASKED),
                    "expanding the page would repeat every masked number in it, and the cursor "
                            + "holds a card number of its own: " + rendered);
            assertTrue(rendered.contains("nextPageExists=true")
                            && rendered.contains("nextCursor=" + EventEnvelope.WITHHELD),
                    "the rendering keeps the paging state, which is what a paging problem needs, "
                            + "and withholds the cursor, which holds the browse key of "
                            + "app/cbl/COCRDLIC.cbl:L488-L489: " + rendered);
        }

        @Test
        @DisplayName("an empty page renders a count of zero rather than an empty list")
        void anEmptyPageRendersACountOfZero() {
            String rendered = new CardListResponse(List.of(), false, null).toString();

            assertTrue(rendered.contains("cards=0 on this page"),
                    "the rendering counts an empty page: " + rendered);
            assertTrue(rendered.contains("nextCursor=absent"),
                    "an absent cursor reads as absent rather than as a withheld value: " + rendered);
        }
    }

    @Nested
    @DisplayName("The update response reports its snapshot as present and withholds its values")
    class UpdateResponseRendering {

        @Test
        @DisplayName("the snapshot is reported as present, never expanded")
        void theSnapshotIsReportedRatherThanExpanded() {
            CardUpdateResponse.RefreshedCard snapshot = new CardUpdateResponse.RefreshedCard(
                    EMBOSSED_NAME, EXPIRY_YEAR, EXPIRY_MONTH, EXPIRY_DAY, ACTIVE_STATUS);
            String rendered = CardUpdateResponse.changedBeforeUpdate(snapshot).toString();

            assertTrue(rendered.contains("refreshedCard=present"),
                    "the rendering reports the snapshot as present: " + rendered);
            assertFalse(rendered.contains(EMBOSSED_NAME),
                    "expanding the snapshot would carry the cardholder name: " + rendered);
            assertTrue(rendered.contains("outcome=CHANGED_BEFORE_UPDATE"),
                    "the rendering keeps the outcome, which is the whole diagnostic: " + rendered);
        }

        @Test
        @DisplayName("a response with no snapshot reports it as absent")
        void aResponseWithNoSnapshotReportsItAbsent() {
            String rendered = CardUpdateResponse.updated().toString();

            assertTrue(rendered.contains("refreshedCard=absent"),
                    "the rendering distinguishes an absent snapshot from a present one: "
                            + rendered);
        }

        @Test
        @DisplayName("the snapshot's own rendering withholds all four cardholder values")
        void theSnapshotRenderingWithholdsEveryCardholderValue() {
            String rendered = new CardUpdateResponse.RefreshedCard(EMBOSSED_NAME, EXPIRY_YEAR,
                    EXPIRY_MONTH, EXPIRY_DAY, ACTIVE_STATUS).toString();

            for (String value : List.of(EMBOSSED_NAME, EXPIRY_YEAR, EXPIRY_MONTH, EXPIRY_DAY)) {
                assertFalse(rendered.contains("=" + value),
                        "the rendering carries " + value + ": " + rendered);
            }
            assertTrue(rendered.contains("activeStatus=" + ACTIVE_STATUS),
                    "the rendering keeps the status flag: " + rendered);
        }
    }

    @Nested
    @DisplayName("Two types render every component, and that is a decision rather than a default")
    class DeliberatelyCompleteRenderings {

        @Test
        @DisplayName("the summary renders all three components, none of which is sensitive")
        void theSummaryRendersEveryComponent() {
            String rendered = new CardSummary(MASKED, ACCOUNT_ID, ACTIVE_STATUS).toString();

            assertTrue(rendered.contains("cardNumber=" + MASKED),
                    "the masked number is safe by construction: " + rendered);
            assertTrue(rendered.contains("accountId=" + ACCOUNT_ID), "the rendering names the"
                    + " account: " + rendered);
            assertTrue(rendered.contains("activeStatus=" + ACTIVE_STATUS),
                    "the rendering names the status: " + rendered);
            assertFalse(rendered.contains(CARD_NUMBER),
                    "no full number can reach the rendering, because the component type refuses"
                            + " one: " + rendered);
        }

        @Test
        @DisplayName("the summary refuses a full card number, so no rendering of one can exist")
        void theSummaryRefusesAFullCardNumber() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new CardSummary(CARD_NUMBER, ACCOUNT_ID, ACTIVE_STATUS),
                    "a full Primary Account Number must not be constructible into a row whose"
                            + " rendering prints the component in full");

            assertFalse(refused.getMessage().contains(CARD_NUMBER),
                    "the refusal reports the width and never the number: " + refused.getMessage());
        }

        @Test
        @DisplayName("the detail response refuses a full card number too")
        void theDetailResponseRefusesAFullCardNumber() {
            assertThrows(IllegalArgumentException.class,
                    () -> new CardDetailResponse(CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME,
                            EXPIRATION_DATE, ACTIVE_STATUS),
                    "the response prints its card number component in full, so the component may"
                            + " hold only the masked form");
        }

        @Test
        @DisplayName("the summary declares exactly the three components this decision covers")
        void theSummaryDeclaresExactlyThreeComponents() {
            RecordComponent[] components = CardSummary.class.getRecordComponents();

            assertEquals(3, components.length,
                    "a fourth component would have to be weighed against the rendering decision"
                            + " rather than joining it silently");
            assertEquals(String.class, components[0].getType(),
                    "the card number component carries the masked form as text, and the canonical"
                            + " constructor is what refuses a full Primary Account Number, which is"
                            + " what makes rendering it safe");
        }
    }
}

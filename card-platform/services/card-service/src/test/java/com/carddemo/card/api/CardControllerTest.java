package com.carddemo.card.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.card.api.dto.ApiErrorResponse;
import com.carddemo.card.api.dto.CardDetailRequest;
import com.carddemo.card.api.dto.CardDetailResponse;
import com.carddemo.card.api.dto.CardListResponse;
import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardUpdateResponse.RefreshedCard;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.config.SecurityConfig;
import com.carddemo.card.domain.CardQueryService;
import com.carddemo.card.domain.CardQueryService.CardListRow;
import com.carddemo.card.domain.CardQueryService.CardPage;
import com.carddemo.card.domain.CardUpdateService;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.cobol.PanMasker;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Behaviour tests for {@link CardController}.
 *
 * <p>Three Customer Information Control System (CICS) transactions are under test.
 * {@code app/cbl/COCRDLIC.cbl} lists cards, {@code app/cbl/COCRDSLC.cbl} reads one, and
 * {@code app/cbl/COCRDUPC.cbl} updates one. What these tests assert is the translation of their
 * outcomes into status codes, the masking of every card number that leaves, the four ordered edits of
 * the read route, and the ownership decision the read route makes because the filter chain cannot.
 *
 * <p>The read side and the update side are stubbed, so nothing here re-tests the paging arithmetic or
 * the edit order. Those belong to {@code domain/CardQueryServiceTest} and
 * {@code domain/CardUpdateServiceTest}, which drive them directly.
 *
 * <p>No application context, no database and no broker takes part.
 */
@DisplayName("the card list, read and update surface")
class CardControllerTest {

    /** Card number of the row under test, sixteen digits. */
    private static final String CARD_NUMBER = "4111111111111150";

    /** The masked form of that card number, which every response carries. */
    private static final String MASKED = "************1150";

    /** Irreversible cursor token of {@link #CARD_NUMBER}. */
    private static final String CARD_TOKEN = PanMasker.cardToken(CARD_NUMBER);

    /** Another card number used as the first row of a two-row page. */
    private static final String FIRST_CARD_NUMBER = "4111111111111101";

    /** Irreversible cursor token of {@link #FIRST_CARD_NUMBER}. */
    private static final String FIRST_CARD_TOKEN = PanMasker.cardToken(FIRST_CARD_NUMBER);

    /** The account the card belongs to, eleven digits. */
    private static final String ACCOUNT_ID = "00000000050";

    /** An account identifier the stored card does not belong to, eleven digits like the column. */
    private static final String OTHER_ACCOUNT_ID = "00000000099";

    private CardQueryService cardQueries;
    private CardUpdateService cardUpdates;
    private SecurityConfig.CardOwnership ownership;
    private CardController controller;

    /** Builds the controller over stubbed collaborators before each test. */
    @BeforeEach
    void buildController() {
        cardQueries = mock(CardQueryService.class);
        cardUpdates = mock(CardUpdateService.class);
        ownership = cardNumber -> true;
        controller = new CardController(cardQueries, cardUpdates, cardNumber -> ownership
                .ownsCard(cardNumber));
    }

    /** The list route. */
    @Nested
    @DisplayName("listing one account's cards")
    class ListingCards {

        /** Asserts a forward page reaches the caller with every card number masked. */
        @Test
        void aForwardPageMasksEveryCardNumber() {
            when(cardQueries.listForward(isNull(), isNull(), eq(ACCOUNT_ID), isNull()))
                    .thenReturn(new CardPage(List.of(
                            new CardListRow(CARD_NUMBER, ACCOUNT_ID, "Y")), false, CARD_TOKEN,
                            CARD_TOKEN));

            CardListResponse page = controller.listCards(ACCOUNT_ID, null, "forward", null);

            assertEquals(1, page.cards().size(), "one row matched");
            assertEquals(MASKED, page.cards().getFirst().cardNumber(),
                    "the masker runs before the row reaches the body");
            assertEquals(ACCOUNT_ID, page.cards().getFirst().accountId(),
                    "the account keeps its leading zeros");
            assertFalse(page.nextPageExists(), "a single row is the whole page");
            assertNull(page.nextCursor(), "a page with no further page carries no cursor");
        }

        /** Asserts a forward page with a further page hands back its last card token. */
        @Test
        void aForwardPageHandsBackItsLastCardToken() {
            when(cardQueries.listForward(isNull(), isNull(), eq(ACCOUNT_ID), isNull()))
                    .thenReturn(new CardPage(List.of(
                            new CardListRow(FIRST_CARD_NUMBER, ACCOUNT_ID, "Y"),
                            new CardListRow(CARD_NUMBER, ACCOUNT_ID, "Y")), true,
                            FIRST_CARD_TOKEN, CARD_TOKEN));

            CardListResponse page = controller.listCards(ACCOUNT_ID, null, "forward", null);

            assertTrue(page.nextPageExists(), "a further page follows");
            assertEquals(CARD_TOKEN, page.nextCursor(),
                    "the last source key is published only through its irreversible token");
        }

        /**
         * Asserts a backward page hands back its first card token.
         *
         * <p>Going back, the next page holds lower card numbers, so the cursor that continues the
         * browse is the lowest row of this page. {@code app/cbl/COCRDLIC.cbl:L1350-L1353} writes that
         * value into {@code WS-CA-FIRST-CARDKEY} for the same purpose. Handing back the last row
         * would return the page the caller already has.
         */
        @Test
        void aBackwardPageHandsBackItsFirstCardToken() {
            when(cardQueries.listBackward(isNull(), isNull(), eq(ACCOUNT_ID), isNull()))
                    .thenReturn(new CardPage(List.of(
                            new CardListRow(FIRST_CARD_NUMBER, ACCOUNT_ID, "Y"),
                            new CardListRow(CARD_NUMBER, ACCOUNT_ID, "Y")), true,
                            FIRST_CARD_TOKEN, CARD_TOKEN));

            CardListResponse page = controller.listCards(ACCOUNT_ID, null, "backward", null);

            assertEquals(FIRST_CARD_TOKEN, page.nextCursor(),
                    "the token continues the browse without publishing its source key");
        }

        /** Asserts the direction chooses which of the two browses runs. */
        @Test
        void theDirectionChoosesWhichBrowseRuns() {
            when(cardQueries.listBackward(any(), any(), any(), any()))
                    .thenReturn(new CardPage(List.of(), false, null, null));
            when(cardQueries.listForward(any(), any(), any(), any()))
                    .thenReturn(new CardPage(List.of(), false, null, null));

            controller.listCards(ACCOUNT_ID, CARD_NUMBER, "backward", 3);
            verify(cardQueries).listBackward(CARD_NUMBER, 3, ACCOUNT_ID, null);
            verify(cardQueries, never()).listForward(any(), any(), any(), any());

            controller.listCards(ACCOUNT_ID, CARD_NUMBER, "forward", 3);
            verify(cardQueries).listForward(CARD_NUMBER, 3, ACCOUNT_ID, null);
        }

        /**
         * Asserts no card filter reaches the read side from this route.
         *
         * <p>The source screen carries one, and it would be a card number in a query string here. The
         * fourth argument is therefore always absent.
         */
        @Test
        void noCardFilterReachesTheReadSide() {
            when(cardQueries.listForward(any(), any(), any(), any()))
                    .thenReturn(new CardPage(List.of(), false, null, null));

            controller.listCards(ACCOUNT_ID, null, "forward", null);

            verify(cardQueries).listForward(isNull(), isNull(), eq(ACCOUNT_ID), isNull());
        }

        /** Asserts an account with no card answers an empty page rather than a failure. */
        @Test
        void anAccountWithNoCardAnswersAnEmptyPage() {
            when(cardQueries.listForward(any(), any(), any(), any()))
                    .thenReturn(new CardPage(List.of(), false, null, null));

            CardListResponse page = controller.listCards(ACCOUNT_ID, null, "forward", null);

            assertEquals(List.of(), page.cards(), "no row matched");
            assertFalse(page.nextPageExists(), "and no further page follows");
        }
    }

    /** The read route. */
    @Nested
    @DisplayName("reading one card")
    class ReadingOneCard {

        /** Asserts a stored card is returned with its number masked and its name as stored. */
        @Test
        void aStoredCardIsReturnedMasked() {
            when(cardQueries.findByCardNumber(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard()));

            ResponseEntity<?> response = controller.readCard(
                    new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER));

            assertEquals(HttpStatus.OK, response.getStatusCode(), "a stored row answers 200");
            CardDetailResponse detail =
                    assertInstanceOf(CardDetailResponse.class, response.getBody());
            assertEquals(MASKED, detail.maskedCardNumber(), "the masker runs before the body");
            assertEquals(ACCOUNT_ID, detail.accountId(), "the account keeps its leading zeros");
            assertEquals(LocalDate.of(2028, 11, 30), detail.expirationDate(), "the expiry date");
            assertEquals("Y", detail.activeStatus(), "the status carried through");
            assertEquals(MASKED, PanMasker.maskCardNumber(CARD_NUMBER),
                    "the value tied to the production masker rather than to a literal");
        }

        /** Asserts a read that missed answers 404 with the source's text. */
        @Test
        void aReadThatMissedAnswersNotFound() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.readCard(
                    new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER));

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "no row answers 404");
            ApiErrorResponse body = assertInstanceOf(ApiErrorResponse.class, response.getBody());
            assertEquals(CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO, body.message(),
                    "the text app/cbl/COCRDSLC.cbl:L760 sets");
            assertEquals(CardController.DETAIL_ROUTE, body.route(),
                    "the body carries the route template and no resolved path");
        }

        /**
         * Asserts a card the caller does not own answers 403 without reading the table.
         *
         * <p>Reading first and refusing afterwards would let a caller time the two answers apart. The
         * check therefore runs before the read, and the read is never issued.
         */
        @Test
        void aCardTheCallerDoesNotOwnAnswersForbiddenWithoutReading() {
            ownership = cardNumber -> false;

            ResponseEntity<?> response = controller.readCard(
                    new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER));

            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                    "the caller holds no scope for this card");
            verify(cardQueries, never()).findByCardNumber(any());
        }

        /**
         * Asserts the refusal and the absence carry the same text, so the two differ in status alone.
         */
        @Test
        void aRefusalAndAnAbsenceCarryTheSameText() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());
            ApiErrorResponse absent = assertInstanceOf(ApiErrorResponse.class, controller
                    .readCard(new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER)).getBody());

            ownership = cardNumber -> false;
            ApiErrorResponse refused = assertInstanceOf(ApiErrorResponse.class, controller
                    .readCard(new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER)).getBody());

            assertEquals(absent.message(), refused.message(),
                    "neither answer discloses which cards this service holds");
        }

        /**
          * Asserts the ownership check is asked about the full number, so it can derive the token.
          *
          * <p>The masked form names every card ending in the same four digits. Handing the predicate
          * a masked value would mean one entitlement admitted a group of cards, which is the defect
          * this assertion exists to keep closed. The masked value still reaches the response, and
          * {@link #aStoredCardIsReturnedMasked()} holds that half.
          */
        @Test
        void theOwnershipCheckIsAskedAboutTheFullNumber() {
            List<String> asked = new java.util.ArrayList<>();
            ownership = cardNumber -> {
                asked.add(cardNumber);
                return true;
            };
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

            controller.readCard(new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER));

            assertEquals(List.of(CARD_NUMBER), asked,
                    "SecurityConfig derives the card token, so it needs the number and not a mask");
            assertNotEquals(List.of(MASKED), asked,
                    "a masked value would name a group of cards rather than one");
        }

        /**
         * Asserts the read keys on the card number alone, and never on the account the caller sent.
         *
         * <p>{@code app/cbl/COCRDSLC.cbl:L739} is commented out and L740 is not, so the source edits
         * the account and keys on the card. The key is reproduced; the account decides the answer
         * afterwards rather than the row that is read.
         */
        @Test
        void theReadKeysOnTheCardNumberAlone() {
            when(cardQueries.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

            controller.readCard(new CardDetailRequest(OTHER_ACCOUNT_ID, CARD_NUMBER));

            verify(cardQueries).findByCardNumber(CARD_NUMBER);
            verify(cardQueries, never()).findByAccountId(any());
        }

        /**
         * Asserts a row belonging to another account is answered as an absent row.
         *
         * <p>The source granted every signed-on user every card, so its unchecked account changed
         * nothing. Here the account is a caller-supplied key, and returning the row would let one
         * entitlement be exercised under any account identifier a caller chose. The answer is the
         * absent-row answer exactly: {@code 404} and the text
         * {@code app/cbl/COCRDSLC.cbl:L760} sets, so a caller cannot tell the two cases apart and
         * learns nothing about which accounts hold which cards.
         */
        @Test
        void aRowOfAnotherAccountIsAnsweredAsAnAbsentRow() {
            when(cardQueries.findByCardNumber(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard()));

            ResponseEntity<?> response = controller.readCard(
                    new CardDetailRequest(OTHER_ACCOUNT_ID, CARD_NUMBER));

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                    "a card of another account is not this caller's card to read");
            ApiErrorResponse body = assertInstanceOf(ApiErrorResponse.class, response.getBody());
            assertEquals(CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO, body.message(),
                    "the same text an absent row carries");
        }

        /**
         * Asserts the account comparison reads a padded request value as the same account.
         *
         * <p>The four edits strip before they measure width, so a caller can reach the comparison
         * with a trailing space. {@code CARD-ACCT-ID PIC 9(11)} in a fixed-width field is the source
         * of that habit. A comparison that failed on the space would refuse a request every edit
         * accepted, and a caller would read {@code 404} for a card it owns.
         */
        @Test
        void theAccountComparisonReadsAPaddedRequestValueAsTheSameAccount() {
            when(cardQueries.findByCardNumber(CARD_NUMBER))
                    .thenReturn(Optional.of(storedCard()));

            ResponseEntity<?> response = controller.readCard(
                    new CardDetailRequest(ACCOUNT_ID + " ", CARD_NUMBER));

            assertEquals(HttpStatus.OK, response.getStatusCode(),
                    "padding is field shape and not a different account");
        }
    }

    /** The four ordered edits of the read route. */
    @Nested
    @DisplayName("the ordered edits of the read route")
    class OrderedEditsOfTheReadRoute {

        /** Asserts both values absent answers the one text the cross-field rule sets. */
        @Test
        void bothValuesAbsentAnswerTheCrossFieldText() {
            ResponseEntity<?> response = controller.readCard(new CardDetailRequest("", ""));

            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode(),
                    "an edit refused the request");
            assertEquals(CardValidationMessages.NO_SEARCH_CRITERIA_RECEIVED,
                    assertInstanceOf(ApiErrorResponse.class, response.getBody()).message(),
                    "app/cbl/COCRDSLC.cbl:L637-L640 overwrites both edits");
        }

        /** Asserts all-zero values count as absent, not as malformed. */
        @Test
        void allZeroValuesCountAsAbsent() {
            ResponseEntity<?> response = controller.readCard(
                    new CardDetailRequest("0".repeat(11), "0".repeat(16)));

            assertEquals(CardValidationMessages.NO_SEARCH_CRITERIA_RECEIVED,
                    assertInstanceOf(ApiErrorResponse.class, response.getBody()).message(),
                    "the numeric redefine against zero is the third condition of each test");
        }

        /** Asserts an absent account answers the account prompt when the card arrived. */
        @Test
        void anAbsentAccountAnswersTheAccountPrompt() {
            ResponseEntity<?> response = controller.readCard(
                    new CardDetailRequest(null, CARD_NUMBER));

            assertEquals(CardValidationMessages.PROMPT_FOR_ACCT,
                    assertInstanceOf(ApiErrorResponse.class, response.getBody()).message(),
                    "the text app/cbl/COCRDSLC.cbl:L657 sets");
        }

        /**
         * Asserts a malformed account answers ahead of an absent card.
         *
         * <p>The account edit runs first and its message is written under the off guard, so the card
         * edit that follows writes nothing. The cross-field rule needs both blank flags and the
         * account carries the not-ok flag instead, so it does not fire either.
         */
        @Test
        void aMalformedAccountAnswersAheadOfAnAbsentCard() {
            ResponseEntity<?> response = controller.readCard(new CardDetailRequest("50", ""));

            assertEquals(CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC,
                    assertInstanceOf(ApiErrorResponse.class, response.getBody()).message(),
                    "2210-EDIT-ACCOUNT writes first and the guard keeps the text");
        }

        /** Asserts an absent card answers the card prompt when the account is well formed. */
        @Test
        void anAbsentCardAnswersTheCardPrompt() {
            ResponseEntity<?> response = controller.readCard(
                    new CardDetailRequest(ACCOUNT_ID, "   "));

            assertEquals(CardValidationMessages.PROMPT_FOR_CARD,
                    assertInstanceOf(ApiErrorResponse.class, response.getBody()).message(),
                    "the text app/cbl/COCRDSLC.cbl:L697 sets");
        }

        /** Asserts a malformed card answers the card character-class text. */
        @Test
        void aMalformedCardAnswersTheCharacterClassText() {
            ResponseEntity<?> response = controller.readCard(
                    new CardDetailRequest(ACCOUNT_ID, "411111111111115X"));

            assertEquals(CardValidationMessages.CARD_FILTER_NOT_NUMERIC,
                    assertInstanceOf(ApiErrorResponse.class, response.getBody()).message(),
                    "the text app/cbl/COCRDSLC.cbl:L711 carries");
        }

        /** Asserts a well-formed pair passes all four edits. */
        @Test
        void aWellFormedPairPassesEveryEdit() {
            assertNull(CardController.firstSearchFailure(
                            new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER)),
                    "eleven digits and sixteen digits pass every edit");
        }
    }

    /** The update route, and the seven statuses its outcomes carry. */
    @Nested
    @DisplayName("updating one card")
    class UpdatingOneCard {

        /** Asserts each of the seven outcomes carries the status this surface promises. */
        @Test
        void eachOutcomeCarriesItsStatus() {
            assertEquals(HttpStatus.OK, statusOf(CardUpdateResponse.updated()),
                    "a rewritten row is an answer and not a failure");
            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT,
                    statusOf(CardUpdateResponse.noChangeDetected()),
                    "the gate at app/cbl/COCRDUPC.cbl:L680-L682 writes nothing and asks the caller"
                            + " to change a value");
            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, statusOf(CardUpdateResponse
                            .validationRejected(CardValidationMessages.NAME_MUST_BE_ALPHA)),
                    "a failing edit asks the caller to correct one value");
            assertEquals(HttpStatus.NOT_FOUND, statusOf(CardUpdateResponse.cardNotFound()),
                    "an absent row is a 404");
            assertEquals(HttpStatus.CONFLICT, statusOf(CardUpdateResponse.changedBeforeUpdate(
                            new RefreshedCard("MORGAN", "2029", "12", "31", "N"))),
                    "a lost race is a 409");
            assertEquals(HttpStatus.CONFLICT, statusOf(CardUpdateResponse.lockNotAcquired()),
                    "a row that could not be held is a 409 too");
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                    statusOf(CardUpdateResponse.updateFailedAfterLock()),
                    "a rewrite that failed after the lock is a fault inside this service");
        }

        /** Asserts the outcome the service produced reaches the caller unchanged. */
        @Test
        void theOutcomeReachesTheCallerUnchanged() {
            CardUpdateRequest submitted = new CardUpdateRequest(CARD_NUMBER, "MORGAN", "2029",
                    "12", "31", "N");
            when(cardUpdates.updateCard(submitted)).thenReturn(CardUpdateResponse.updated());

            ResponseEntity<CardUpdateResponse> response = controller.updateCard(submitted);

            assertEquals(HttpStatus.OK, response.getStatusCode(), "an applied update answers 200");
            assertNotNull(response.getBody(), "the outcome reaches the caller");
            assertEquals(CardUpdateResponse.UpdateOutcome.UPDATED, response.getBody().outcome(),
                    "the outcome is the one the service produced");
            verify(cardUpdates).updateCard(submitted);
        }

        /** Asserts a refreshed snapshot reaches the caller on a lost race. */
        @Test
        void aLostRaceCarriesTheRefreshedSnapshot() {
            CardUpdateRequest submitted = new CardUpdateRequest(CARD_NUMBER, "MORGAN", "2029",
                    "12", "31", "N");
            when(cardUpdates.updateCard(submitted)).thenReturn(CardUpdateResponse
                    .changedBeforeUpdate(new RefreshedCard("SOMEBODY ELSE", "2030", "01", "02",
                            "N")));

            ResponseEntity<CardUpdateResponse> response = controller.updateCard(submitted);

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode(), "a lost race answers 409");
            assertNotNull(response.getBody().refreshedCard(),
                    "the caller receives the row as it now stands");
            assertEquals("2030", response.getBody().refreshedCard().expiryYear(),
                    "the refreshed year slice");
        }

        /**
         * Reads the status one outcome carries.
         *
         * @param outcome the outcome
         * @return the status
         */
        private HttpStatus statusOf(CardUpdateResponse outcome) {
            when(cardUpdates.updateCard(any())).thenReturn(outcome);
            return HttpStatus.valueOf(controller.updateCard(
                    new CardUpdateRequest(CARD_NUMBER, "MORGAN", "2029", "12", "31", "N"))
                    .getStatusCode().value());
        }
    }

    /** The constructor's own guards. */
    @Nested
    @DisplayName("the constructor")
    class Constructor {

        /** Asserts every collaborator is required. */
        @Test
        void everyCollaboratorIsRequired() {
            assertThrows(NullPointerException.class,
                    () -> new CardController(null, cardUpdates, ownership),
                    "the read side is required");
            assertThrows(NullPointerException.class,
                    () -> new CardController(cardQueries, null, ownership),
                    "the update side is required");
            assertThrows(NullPointerException.class,
                    () -> new CardController(cardQueries, cardUpdates, null),
                    "the ownership predicate is required");
        }
    }

    /**
     * Builds the stored card row the read tests return.
     *
     * @return the row
     */
    private static CardEntity storedCard() {
        return new CardEntity(CARD_NUMBER, ACCOUNT_ID, "123", "ALEXANDER J MORGAN",
                LocalDate.of(2028, 11, 30), "Y");
    }
}

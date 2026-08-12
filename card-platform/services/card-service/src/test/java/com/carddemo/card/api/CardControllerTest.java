package com.carddemo.card.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.card.api.dto.ApiErrorResponse;
import com.carddemo.card.api.dto.CardDetailResponse;
import com.carddemo.card.api.dto.CardListResponse;
import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.api.dto.CardUpdateResponse.RefreshedCard;
import com.carddemo.card.api.dto.CardUpdateResponse.UpdateOutcome;
import com.carddemo.card.config.SecurityConfig;
import com.carddemo.card.domain.CardQueryService;
import com.carddemo.card.domain.CardQueryService.CardListRow;
import com.carddemo.card.domain.CardQueryService.CardPage;
import com.carddemo.card.domain.CardUpdateService;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.cobol.PanMasker;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Behaviour tests for {@link CardController}.
 *
 * <p>Three Customer Information Control System (CICS) transactions reach this surface.
 * {@code app/cbl/COCRDLIC.cbl} lists cards a page at a time, {@code app/cbl/COCRDSLC.cbl} reads
 * one card, and {@code app/cbl/COCRDUPC.cbl} updates one. These tests assert the status code and
 * the text each outcome carries, and the masking of every card number that leaves. They also
 * assert the ordered edits of the read route and the shape of every body.
 *
 * <p>Both domain collaborators are test doubles, so no assertion here re-derives the paging
 * arithmetic or the edit order. {@code domain/CardQueryServiceTest} and
 * {@code domain/CardUpdateServiceTest} drive those directly. {@link PanMasker} is the real
 * component and is never stubbed.
 *
 * <p>Every text is asserted against a literal typed in this file rather than against the
 * production constant that carries it. {@code api/dto/CardValidationMessagesTest} holds the
 * constants to the same literals.
 *
 * <p>The single stubbed card is row one of {@code app/data/ASCII/carddata.txt}, a file of fifty
 * records at one hundred and fifty bytes. Its card verification value occurs once in that record,
 * and on one other row inside a card number whose own verification value differs.
 *
 * <p>No application context, no database and no broker takes part.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("the card list, read and update surface")
class CardControllerTest {

    // Row one of app/data/ASCII/carddata.txt, sliced at the offsets app/cpy/CVACT02Y.cpy declares.

    /** {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. */
    private static final String CARD_NUMBER = "0500024453765740";

    /** The masked form of {@link #CARD_NUMBER}, which every response carries in its place. */
    private static final String MASKED_CARD_NUMBER = "************5740";

    /**
     * The token of {@link #CARD_NUMBER}, which is the value the two card routes carry in their path.
     *
     * <p>Derived under the build-scope card-token key {@code card-platform/pom.xml} supplies, so it is
     * the same value {@code entity/CardEntity} stores for that row in this build. A path carries this
     * and never the number: a path reaches an access log, a proxy log, a distributed trace and a
     * browser history, and none of the four is reachable by this application's redaction.
     */
    private static final String CARD_TOKEN = PanMasker.cardToken(CARD_NUMBER);

    /** Twelve mask characters and the last four digits, which total the sixteen of the column. */
    private static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /** {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}. */
    private static final String ACCOUNT_ID = "00000000050";

    /**
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}.
     *
     * <p>The card verification value of the stubbed row, three digits. No response body and no
     * event carries it.
     */
    private static final String CARD_VERIFICATION_VALUE = "747";

    /** {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}. */
    private static final String EMBOSSED_NAME = "Aniya Von";

    /**
     * {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:L9}.
     *
     * <p>The source spells the field name with the syllable transposed. The target column and the
     * target property both read {@code expirationDate}.
     */
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2023, 3, 9);

    /** The year slice of {@link #EXPIRATION_DATE}, from {@code app/cbl/COCRDUPC.cbl:L117}. */
    private static final String EXPIRY_YEAR = "2023";

    /** The month slice of {@link #EXPIRATION_DATE}, from {@code app/cbl/COCRDUPC.cbl:L119}. */
    private static final String EXPIRY_MONTH = "03";

    /** The day slice of {@link #EXPIRATION_DATE}, from {@code app/cbl/COCRDUPC.cbl:L121}. */
    private static final String EXPIRY_DAY = "09";

    /** {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10}. */
    private static final String ACTIVE_STATUS = "Y";

    // Two further card numbers. Neither is a fixture row, and both are constructed for one
    // assertion each.

    /**
     * A sixteen-digit value whose closing check digit fails the Luhn test.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L784} tests {@code IF CC-CARD-NUM IS NOT NUMERIC} and no
     * checksum. Used to assert that this surface refuses the value on no such ground.
     */
    private static final String CARD_NUMBER_FAILING_LUHN = "4111111111111112";

    /**
     * A second card number, one greater than {@link #CARD_NUMBER}, which makes the LAST row of a
     * two-row page.
     *
     * <p>{@code app/jcl/CARDFILE.jcl:L40} declares {@code KEYS(16 0)} and a browse over a
     * key-sequenced dataset returns ascending keys, so the higher of two card numbers is the second
     * row of a page and never the first. The fixture built it first, which made the backward-cursor
     * assertion read the highest row as the lowest.</p>
     */
    private static final String SECOND_CARD_NUMBER = "0500024453765741";

    /** The token of {@link #SECOND_CARD_NUMBER}, the path value that names that second card. */
    private static final String SECOND_CARD_TOKEN = PanMasker.cardToken(SECOND_CARD_NUMBER);

    /** An account identifier the stubbed card does not belong to, eleven digits like the column. */
    private static final String OTHER_ACCOUNT_ID = "00000000099";

    /** The year a lost race reports back, which the submitted update does not carry. */
    private static final String REFRESHED_EXPIRY_YEAR = "2030";

    // Outcome texts. Each literal is typed here and compared for equality, never for containment.

    /**
     * {@code DID-NOT-FIND-ACCTCARD-COMBO} at {@code app/cbl/COCRDUPC.cbl:L203/L204}.
     *
     * <p>{@code app/cbl/COCRDSLC.cbl:L153/L154} declares a byte-identical twin, which
     * {@code app/cbl/COCRDSLC.cbl:L760} sets on the read path.
     */
    private static final String DID_NOT_FIND_CARD = "Did not find cards for this search condition";

    /** {@code COULD-NOT-LOCK-FOR-UPDATE} at {@code app/cbl/COCRDUPC.cbl:L205/L206}. */
    private static final String COULD_NOT_LOCK = "Could not lock record for update";

    /**
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} at {@code app/cbl/COCRDUPC.cbl:L207/L208}.
     *
     * <p>The source writes {@code some one} as two words, and this literal keeps that spelling.
     */
    private static final String CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /** {@code LOCKED-BUT-UPDATE-FAILED} at {@code app/cbl/COCRDUPC.cbl:L209/L210}. */
    private static final String UPDATE_OF_RECORD_FAILED = "Update of record failed";

    /**
     * {@code NO-CHANGES-DETECTED} at {@code app/cbl/COCRDUPC.cbl:L188}.
     *
     * <p>The trailing full stop belongs to the source literal.
     */
    private static final String NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";

    /** {@code WS-NAME-MUST-BE-ALPHA} at {@code app/cbl/COCRDUPC.cbl:L184}. */
    private static final String NAME_MUST_BE_ALPHA =
            "Card name can only contain alphabets and spaces";

    /**
     * {@code CARD-STATUS-MUST-BE-YES-NO} at {@code app/cbl/COCRDUPC.cbl:L196}.
     *
     * <p>The edit that reaches it is {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at
     * {@code app/cbl/COCRDUPC.cbl:L91}.
     */
    private static final String STATUS_MUST_BE_YES_NO = "Card Active Status must be Y or N";

    /**
     * {@code CARD-EXPIRY-MONTH-NOT-VALID} at {@code app/cbl/COCRDUPC.cbl:L198}.
     *
     * <p>The edit that reaches it is {@code 88 VALID-MONTH VALUES 1 THRU 12.} at
     * {@code app/cbl/COCRDUPC.cbl:L95}.
     */
    private static final String EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /**
     * {@code CARD-EXPIRY-YEAR-NOT-VALID} at {@code app/cbl/COCRDUPC.cbl:L200}.
     *
     * <p>The edit that reaches it is {@code 88 VALID-YEAR VALUES 1950 THRU 2099.} at
     * {@code app/cbl/COCRDUPC.cbl:L99}.
     */
    private static final String EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";

    /**
     * {@code NO-SEARCH-CRITERIA-RECEIVED} at {@code app/cbl/COCRDUPC.cbl:L186}.
     *
     * <p>{@code app/cbl/COCRDSLC.cbl:L637-L640} sets the twin when both search values are blank.
     */
    private static final String NO_INPUT_RECEIVED = "No input received";

    /** {@code WS-PROMPT-FOR-ACCT} at {@code app/cbl/COCRDUPC.cbl:L178}. */
    private static final String ACCOUNT_NOT_PROVIDED = "Account number not provided";

    /** {@code WS-PROMPT-FOR-CARD} at {@code app/cbl/COCRDUPC.cbl:L180}. */
    private static final String CARD_NOT_PROVIDED = "Card number not provided";

    /**
     * The account filter text, moved with no condition name at {@code app/cbl/COCRDUPC.cbl:L745}.
     *
     * <p>The literal carries no space after the comma and reads {@code A 11}.
     */
    private static final String ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * The card filter text, moved with no condition name at {@code app/cbl/COCRDUPC.cbl:L789}.
     *
     * <p>{@code app/cbl/COCRDSLC.cbl:L711} repeats the same literal on the read path.
     */
    private static final String CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** Width of {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}. */
    private static final int MESSAGE_FIELD_WIDTH = 75;

    /** Serializes a response body so an assertion can read its shape and its whole text. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CardQueryService cardQueries;
    private CardUpdateService cardUpdates;
    private CardController controller;

    /** Builds the controller over stubbed collaborators before each test. */
    @BeforeEach
    void buildController() {
        cardQueries = mock(CardQueryService.class);
        cardUpdates = mock(CardUpdateService.class);
        controller = new CardController(cardQueries, cardUpdates);
    }

    /** The list route: what it delegates, what it projects and what it reports. */
    @Nested
    @DisplayName("listing one account's cards")
    class ListingCards {

        /**
         * Asserts the row count of a page with no requested size reaches the read side absent.
         *
         * <p>{@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} at
         * {@code app/cbl/COCRDLIC.cbl:L177-L178}, inside {@code 01 WS-CONSTANTS.} at
         * {@code app/cbl/COCRDLIC.cbl:L176}, fixes the row count of the source screen.
         * {@link CardQueryService} holds that number, and the argument the controller passes is
         * absent rather than seven.
         */
        @Test
        void anAbsentRowCountReachesTheReadSideAbsent() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(emptyPage());
            ArgumentCaptor<Integer> requested = ArgumentCaptor.forClass(Integer.class);

            controller.listCards(ACCOUNT_ID, null, CardController.FORWARD_DIRECTION, null);

            verify(cardQueries).listForward(isNull(), requested.capture(), eq(ACCOUNT_ID),
                    isNull());
            assertNull(requested.getValue(),
                    "the row count reached the read side as a value rather than as an absence");
        }

        @Test
        void aRequestedRowCountReachesTheReadSideUnchanged() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(emptyPage());
            ArgumentCaptor<Integer> requested = ArgumentCaptor.forClass(Integer.class);

            controller.listCards(ACCOUNT_ID, null, CardController.FORWARD_DIRECTION, "3");

            verify(cardQueries).listForward(isNull(), requested.capture(), eq(ACCOUNT_ID),
                    isNull());
            assertEquals(3, requested.getValue(), "the requested row count was not the one sent");
        }

        /**
         * Asserts this controller declares no row count of its own.
         *
         * <p>{@link CardQueryService} owns the seven of
         * {@code app/cbl/COCRDLIC.cbl:L177-L178}. This class declares no numeric member holding
         * that number.
         */
        @Test
        void theControllerDeclaresNoRowCountOfItsOwn() {
            List<String> holdingSeven = new ArrayList<>();
            for (Field field : CardController.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !holdsAWholeNumber(field)) {
                    continue;
                }
                field.setAccessible(true);
                Object value = readStaticValue(field);
                if (value instanceof Number number && number.longValue() == 7L) {
                    holdingSeven.add(field.getName());
                }
            }

            assertEquals(List.of(), holdingSeven,
                    "CardController declares a numeric member holding the source row count: "
                            + holdingSeven);
        }

        /**
         * Asserts one row of the list carries exactly three properties.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L250-L260} declares
         * {@code WS-ALL-ROWS PIC X(196)} redefined as {@code WS-SCREEN-ROWS OCCURS 7 TIMES} of
         * {@code WS-ROW-ACCTNO PIC X(11)}, {@code WS-ROW-CARD-NUM PIC X(16)} and
         * {@code WS-ROW-CARD-STATUS PIC X(1)}, and eleven plus sixteen plus one times seven is the
         * one hundred and ninety-six the author records at {@code app/cbl/COCRDLIC.cbl:L250}.
         * {@code app/cbl/COCRDLIC.cbl:L1165-L1171} moves exactly those three fields.
         */
        @Test
        void oneRowCarriesExactlyThreeProperties() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(oneRowPage());

            JsonNode row = serialize(listOneAccount()).get("cards").get(0);

            assertAll(
                    () -> assertEquals(Set.of("cardNumber", "accountId", "activeStatus"),
                            propertyNames(row), "the row projection changed width"),
                    () -> assertEquals(3, row.size(), "the row carries three properties"),
                    () -> assertEquals(MASKED_CARD_NUMBER, row.get("cardNumber").asString(),
                            "the masker runs before the row reaches the body"),
                    () -> assertEquals(ACCOUNT_ID, row.get("accountId").asString(),
                            "the account keeps its leading zeros"),
                    () -> assertEquals(ACTIVE_STATUS, row.get("activeStatus").asString(),
                            "the status carried through"));
        }

        /**
         * Asserts a row carries no embossed name, no expiry and no card verification value.
         *
         * <p>The three fields {@code app/cbl/COCRDLIC.cbl:L1165-L1171} moves are the whole
         * projection. {@code CARD-EMBOSSED-NAME} at {@code app/cpy/CVACT02Y.cpy:L8},
         * {@code CARD-EXPIRAION-DATE} at {@code app/cpy/CVACT02Y.cpy:L9} and
         * {@code CARD-CVV-CD} at {@code app/cpy/CVACT02Y.cpy:L7} are none of them.
         */
        @Test
        void aRowCarriesNoNameNoExpiryAndNoVerificationValue() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(oneRowPage());

            Set<String> written = propertyNames(serialize(listOneAccount()).get("cards").get(0));

            assertAll(
                    () -> assertFalse(named(written, "embossed"),
                            "a list row started carrying the cardholder name: " + written),
                    () -> assertFalse(named(written, "expir"),
                            "a list row started carrying the expiry: " + written),
                    () -> assertFalse(named(written, "cvv") || named(written, "verification"),
                            "a list row started naming the card verification value: " + written));
        }

        /**
         * Asserts the next-page flag follows the read side both ways.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L1191-L1216} derives the source flag. L1191 tests the
         * page-full condition and L1192 leaves the read loop. L1197-L1205 then reads the one
         * lookahead row. L1207-L1216 reads the response code: a normal or duplicate condition sets
         * next-page-exists at L1210, and an end-of-file condition sets next-page-not-exists at
         * L1216. This route reports the flag and derives nothing.
         */
        @Test
        void theNextPageFlagFollowsTheReadSide() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(twoRowPage(true));
            CardListResponse further = listOneAccount();

            assertAll(
                    () -> assertTrue(further.nextPageExists(), "a further page follows"),
                    () -> assertEquals(cardToken(SECOND_CARD_NUMBER), further.nextCursor(),
                            "a forward page continues from the token of its last row, which is the"
                                    + " higher of the two card numbers"),
                    () -> assertNotEquals(cardToken(CARD_NUMBER), further.nextCursor(),
                            "and not from the token of its first row"));

            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(twoRowPage(false));
            CardListResponse last = listOneAccount();

            assertAll(
                    () -> assertFalse(last.nextPageExists(), "no further page follows"),
                    () -> assertNull(last.nextCursor(), "a last page carries no cursor"));
        }

        /**
         * Asserts the list body carries exactly three properties and no page ordinal.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L230-L235} holds two browse keys and
         * {@code app/cbl/COCRDLIC.cbl:L242-L244} holds the next-page indicator. Neither is a page
         * number and neither is a total.
         */
        @Test
        void theListBodyCarriesExactlyThreeProperties() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(oneRowPage());

            JsonNode body = serialize(listOneAccount());
            Set<String> written = propertyNames(body);

            assertAll(
                    () -> assertEquals(Set.of("cards", "nextPageExists", "nextCursor"), written,
                            "the list body changed shape"),
                    () -> assertEquals(3, body.size(), "the list body carries three properties"),
                    () -> assertFalse(named(written, "total"),
                            "the list body started carrying a count: " + written),
                    () -> assertEquals(List.of(), forbidden(written, "total", "totalElements",
                                    "page", "pageNumber", "size", "pageSize", "count"),
                            "the list body started carrying a paging ordinal: " + written));
        }

        /**
         * Asserts the account filter reaches the read side as the caller wrote it.
         *
         * <p>{@code 9500-FILTER-RECORDS} at {@code app/cbl/COCRDLIC.cbl:L1382-L1405} includes a
         * record by default at L1383, matches the account filter exactly at L1385-L1391 and
         * matches the card-number filter at L1396-L1405. The filter runs before the count:
         * {@code app/cbl/COCRDLIC.cbl:L1162} tests the do-not-exclude flag and L1163 steps the
         * screen counter, so a page of seven holds seven matching rows.
         */
        @Test
        void theAccountFilterReachesTheReadSide() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(oneRowPage());
            ArgumentCaptor<String> filtered = ArgumentCaptor.forClass(String.class);

            controller.listCards(ACCOUNT_ID, null, CardController.FORWARD_DIRECTION, null);

            verify(cardQueries).listForward(isNull(), isNull(), filtered.capture(), isNull());
            assertEquals(ACCOUNT_ID, filtered.getValue(),
                    "the account the caller named is the one the read side filters on");
        }

        /**
         * Asserts no card filter reaches the read side from this route.
         *
         * <p>The source screen carries the filter {@code app/cbl/COCRDLIC.cbl:L1396-L1405} reads.
         * No route here accepts one, and the fourth argument is always absent.
         */
        @Test
        void noCardFilterReachesTheReadSide() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(emptyPage());

            controller.listCards(ACCOUNT_ID, null, CardController.FORWARD_DIRECTION, null);

            verify(cardQueries).listForward(isNull(), isNull(), eq(ACCOUNT_ID), isNull());
        }

        /**
         * Asserts the direction chooses which of the two browses runs.
         *
         * <p>{@code 9000-READ-FORWARD} sits at {@code app/cbl/COCRDLIC.cbl:L1123} and
         * {@code 9100-READ-BACKWARDS} at {@code app/cbl/COCRDLIC.cbl:L1264}. One runs per request.
         */
        @Test
        void theDirectionChoosesWhichBrowseRuns() {
            when(cardQueries.listBackward(any(), any(), any(), any())).thenReturn(emptyPage());
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(emptyPage());
            String cursor = cardToken(CARD_NUMBER);

            controller.listCards(ACCOUNT_ID, cursor, CardController.BACKWARD_DIRECTION, "3");
            verify(cardQueries).listBackward(cursor, 3, ACCOUNT_ID, null);
            verify(cardQueries, never()).listForward(any(), any(), any(), any());

            controller.listCards(ACCOUNT_ID, cursor, CardController.FORWARD_DIRECTION, "3");
            verify(cardQueries).listForward(cursor, 3, ACCOUNT_ID, null);
        }

        /**
         * Asserts a backward page hands back the token of its first row, which is its lowest.
         *
         * <p>Going back, the following page holds lower card numbers, so the row that continues the
         * browse is the lowest of this page. {@code app/cbl/COCRDLIC.cbl:L1350-L1353} writes that
         * value into {@code WS-CA-FIRST-CARDKEY}.
         *
         * <p>The row order is asserted here rather than assumed. The page this reads was built with
         * its higher card number first, so the value it called the first row was the highest one, and
         * the assertion held for a page no forward browse of a key-sequenced dataset can produce. With
         * the page in ascending order the expected token is the LOWER of the two, and it is a
         * different value from the one a forward page returns.
         */
        @Test
        void aBackwardPageHandsBackItsFirstCardToken() {
            when(cardQueries.listBackward(any(), any(), any(), any())).thenReturn(twoRowPage(true));

            CardListResponse page = controller.listCards(ACCOUNT_ID, null,
                    CardController.BACKWARD_DIRECTION, null);

            assertAll(
                    () -> assertEquals(2, page.cards().size(), "row count"),
                    () -> assertEquals(PanMasker.maskCardNumber(CARD_NUMBER), page.cards().getFirst()
                            .cardNumber(), "row zero carries the lower card number"),
                    () -> assertEquals(PanMasker.maskCardNumber(SECOND_CARD_NUMBER), page.cards().get(1)
                            .cardNumber(), "row one carries the higher card number"),
                    () -> assertTrue(CARD_NUMBER.compareTo(SECOND_CARD_NUMBER) < 0,
                            "the two fixture card numbers are in ascending order"),
                    () -> assertEquals(cardToken(CARD_NUMBER), page.nextCursor(),
                            "a backward page continues from the token of its first row, which is the"
                                    + " lower of the two card numbers"),
                    () -> assertNotEquals(cardToken(SECOND_CARD_NUMBER), page.nextCursor(),
                            "and not from the token of its last row, which is where a forward page"
                                    + " continues from"));
        }

        /**
         * Asserts an account holding no card answers an empty page.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L1235} clears the next-page indicator and the source
         * screen shows no row.
         */
        @Test
        void anAccountWithNoCardAnswersAnEmptyPage() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(emptyPage());

            CardListResponse page = listOneAccount();

            assertAll(
                    () -> assertEquals(List.of(), page.cards(), "no row matched"),
                    () -> assertFalse(page.nextPageExists(), "no further page follows"),
                    () -> assertNull(page.nextCursor(), "and no cursor is issued"));
        }

        /** Lists the fixture account going forward with no cursor and no requested row count. */
        private CardListResponse listOneAccount() {
            return controller.listCards(ACCOUNT_ID, null, CardController.FORWARD_DIRECTION, null);
        }
    }

    /** The read route: the order of the lookup and the masking, and the answers it carries. */
    @Nested
    @DisplayName("reading one card")
    class ReadingOneCard {

        /**
         * Asserts the lookup runs on the token the path carried and the body carries the masked
         * number read from the row.
         *
         * <p>{@code app/bms/COCRDSL.bms} renders all sixteen digits in the clear: the field carries
         * {@code ATTRB=(FSET,NORM,UNPROT)} at L96, {@code HILIGHT=UNDERLINE} at L98,
         * {@code LENGTH=16} at L99 and {@code POS=(8,45)} at L100. Masking is additive, and so is the
         * token: {@code card_token} carries a unique constraint, so it selects the single row
         * {@code app/cbl/COCRDSLC.cbl:L740} selects by the number.
         *
         * <p>The masked value is derived from the number the row holds and never from the path, which
         * is what makes it a value the caller could not have supplied.
         */
        @Test
        void theLookupRunsOnTheTokenAndTheBodyCarriesTheMaskedNumberOfTheRow() {
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.of(storedCard()));
            ArgumentCaptor<String> looked = ArgumentCaptor.forClass(String.class);

            ResponseEntity<?> response = readFixtureCard();

            verify(cardQueries).findByCardToken(looked.capture());
            verify(cardQueries, never()).findByCardNumber(any());
            CardDetailResponse detail =
                    assertInstanceOf(CardDetailResponse.class, response.getBody());
            assertAll(
                    () -> assertEquals(HttpStatus.OK, response.getStatusCode(),
                            "a stored row answers 200"),
                    () -> assertEquals(CARD_TOKEN, looked.getValue(),
                            "the lookup received the token the path carried"),
                    () -> assertEquals(PanMasker.CARD_TOKEN_LENGTH, looked.getValue().length(),
                            "the lookup key holds the width a card token declares"),
                    () -> assertNotEquals(CARD_NUMBER, looked.getValue(),
                            "no card number reached the lookup, so none reached the request line"),
                    () -> assertEquals(MASKED_CARD_NUMBER, detail.maskedCardNumber(),
                            "the body carries the masked form"),
                    () -> assertTrue(detail.maskedCardNumber().matches(MASKED_CARD_NUMBER_PATTERN),
                            "the masked form is twelve mask characters and four digits: "
                                    + detail.maskedCardNumber()),
                    () -> assertEquals(MASKED_CARD_NUMBER, PanMasker.maskCardNumber(CARD_NUMBER),
                            "the expected value is the one the production masker produces, taken "
                                    + "over the number the row holds"));
        }

        /**
         * Asserts the read body carries exactly five properties.
         *
         * <p>{@code app/cpy/CVACT02Y.cpy} declares six fields and one filler. The body drops
         * {@code CARD-CVV-CD} at L7 and the fifty-nine-byte filler at L11, and carries the other
         * five.
         */
        @Test
        void theReadBodyCarriesExactlyFiveProperties() {
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.of(storedCard()));

            JsonNode body = serialize(readFixtureCard().getBody());
            Set<String> written = propertyNames(body);

            assertAll(
                    () -> assertEquals(Set.of("maskedCardNumber", "accountId", "embossedName",
                            "expirationDate", "activeStatus"), written,
                            "the read body changed shape"),
                    () -> assertEquals(5, body.size(), "the read body carries five properties"),
                    () -> assertFalse(named(written, "cvv") || named(written, "verification"),
                            "the read body started naming the card verification value: "
                                    + written));
        }

        /**
         * Asserts the read body carries the four values it holds beside the masked number.
         *
         * <p>The account keeps its leading zeros, the name is carried as stored, the expiry is the
         * one row one of {@code app/data/ASCII/carddata.txt} holds, and the status is carried with
         * no interpretation.
         */
        @Test
        void theReadBodyCarriesTheStoredValues() {
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.of(storedCard()));

            CardDetailResponse detail = assertInstanceOf(CardDetailResponse.class,
                    readFixtureCard().getBody());

            assertAll(
                    () -> assertEquals(ACCOUNT_ID, detail.accountId(),
                            "the account keeps its leading zeros"),
                    () -> assertEquals(EMBOSSED_NAME, detail.embossedName(),
                            "the name is carried as stored"),
                    () -> assertEquals(EXPIRATION_DATE, detail.expirationDate(),
                            "the expiry of fixture row one"),
                    () -> assertEquals(ACTIVE_STATUS, detail.activeStatus(),
                            "the status carried through"));
        }

        /**
         * Asserts a read that found no row answers 404 with the text the source sets.
         *
         * <p>{@code DID-NOT-FIND-ACCTCARD-COMBO} at {@code app/cbl/COCRDUPC.cbl:L203/L204} is the
         * pinned text. {@code app/cbl/COCRDSLC.cbl:L153/L154} declares a byte-identical twin, which
         * {@code app/cbl/COCRDSLC.cbl:L760} sets on the read path this handler replaces.
         */
        @Test
        void aReadThatFoundNoRowAnswersNotFound() {
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.empty());

            ResponseEntity<?> response = readFixtureCard();
            ApiErrorResponse body = assertInstanceOf(ApiErrorResponse.class, response.getBody());

            assertAll(
                    () -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                            "no row answers 404"),
                    () -> assertEquals(DID_NOT_FIND_CARD, body.message(),
                            "the text the source sets"),
                    () -> assertEquals(404, body.status(),
                            "the body reports the status it was sent with"),
                    () -> assertEquals(CardController.CARD_ROUTE, body.route(),
                            "the body carries the route template and no resolved path"));
        }

        /**
         * Asserts the read keys on the card number alone.
         *
         * <p>{@code app/cbl/COCRDSLC.cbl:L739} moves the account into the read key and is commented
         * out. {@code app/cbl/COCRDSLC.cbl:L740} moves the card number and is not, so the card
         * number is the whole key. No account accompanies the card on this route and no
         * relationship between the two is tested: the row the key finds is the row the caller
         * reads.
         */
        @Test
        void theReadKeysOnTheCardNumberAlone() {
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.of(storedCard()));

            ResponseEntity<?> response = controller.readCard(CARD_TOKEN);
            CardDetailResponse detail =
                    assertInstanceOf(CardDetailResponse.class, response.getBody());

            verify(cardQueries).findByCardToken(CARD_TOKEN);
            verify(cardQueries, never()).findByAccountId(any());
            assertAll(
                    () -> assertEquals(HttpStatus.OK, response.getStatusCode(),
                            "the row the card key found answers 200"),
                    () -> assertEquals(ACCOUNT_ID, detail.accountId(),
                            "the account the row holds is the account the body reports"));
        }

        /**
         * Asserts the handler carries no ownership rule of its own.
         *
         * <p>The card number arrives in the path, so the filter chain of
         * {@code config/SecurityConfig} reads it and decides ownership before this handler runs.
         * This class therefore takes two collaborators and no predicate, and every parameter of
         * every handler is a request value rather than an authority.
         */
        @Test
        void theHandlerCarriesNoOwnershipRule() {
            List<String> ownershipMembers = new ArrayList<>();
            for (Field field : CardController.class.getDeclaredFields()) {
                String folded = field.getName().toLowerCase(Locale.ROOT);
                if (folded.contains("own") || folded.contains("scope")
                        || folded.contains("authority")) {
                    ownershipMembers.add(field.getName());
                }
            }

            assertAll(
                    () -> assertEquals(List.of(), ownershipMembers,
                            "CardController declares an ownership member: " + ownershipMembers),
                    () -> assertEquals(1, CardController.class.getDeclaredConstructors().length,
                            "one constructor"),
                    () -> assertEquals(2,
                            CardController.class.getDeclaredConstructors()[0]
                                    .getParameterCount(),
                            "the read side and the update side, and no predicate"));
        }

        private ResponseEntity<?> readFixtureCard() {
            return controller.readCard(CARD_TOKEN);
        }
    }

    /** The update route: the status each outcome carries and the text it carries with it. */
    @Nested
    @DisplayName("updating one card")
    class UpdatingOneCard {

        /**
         * Asserts each outcome carries its status and the text the source declares.
         *
         * <p>{@link CardUpdateService} owns the order of the checks and this route owns the status.
         * A rewritten row carries 200 and no text. The no-change comparison at
         * {@code app/cbl/COCRDUPC.cbl:L680-L682} and a failing edit both carry 422. An absent row
         * carries 404, and a lost race and a row that could not be locked both carry 409.
         *
         * <p>A rewrite that failed after the lock carries 503. The source reaches it from a file
         * status other than normal on the {@code REWRITE} at
         * {@code app/cbl/COCRDUPC.cbl:L1483-L1491}, which is the datastore refusing the write, so
         * the status names a condition a caller may retry rather than a defect of this service.
         */
        @Test
        void eachOutcomeCarriesItsStatusAndItsText() {
            assertAll(
                    () -> assertEquals(HttpStatus.OK, statusOf(CardUpdateResponse.updated()),
                            "a rewritten row is an answer rather than a failure"),
                    () -> assertNull(textOf(CardUpdateResponse.updated()),
                            "a rewritten row carries no text"),

                    () -> assertEquals(HttpStatus.UNPROCESSABLE_CONTENT,
                            statusOf(CardUpdateResponse.noChangeDetected()),
                            "the no-change comparison asks the caller to change a value"),
                    () -> assertEquals(NO_CHANGES_DETECTED,
                            textOf(CardUpdateResponse.noChangeDetected()),
                            "the text app/cbl/COCRDUPC.cbl:L188 declares, full stop included"),

                    () -> assertEquals(HttpStatus.NOT_FOUND,
                            statusOf(CardUpdateResponse.cardNotFound()), "an absent row is a 404"),
                    () -> assertEquals(DID_NOT_FIND_CARD,
                            textOf(CardUpdateResponse.cardNotFound()),
                            "the text app/cbl/COCRDUPC.cbl:L204 declares"),

                    () -> assertEquals(HttpStatus.CONFLICT, statusOf(lostRace()),
                            "a lost race is a 409"),
                    () -> assertEquals(CHANGED_BEFORE_UPDATE, textOf(lostRace()),
                            "the text app/cbl/COCRDUPC.cbl:L208 declares"),

                    () -> assertEquals(HttpStatus.CONFLICT,
                            statusOf(CardUpdateResponse.lockNotAcquired()),
                            "a row that could not be held is a 409 too"),
                    () -> assertEquals(COULD_NOT_LOCK,
                            textOf(CardUpdateResponse.lockNotAcquired()),
                            "the text app/cbl/COCRDUPC.cbl:L206 declares"),

                    () -> assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                            statusOf(CardUpdateResponse.updateFailedAfterLock()),
                            "a rewrite the datastore refused is retryable rather than a defect"),
                    () -> assertEquals(UPDATE_OF_RECORD_FAILED,
                            failureTextOf(CardUpdateResponse.updateFailedAfterLock()),
                            "the text app/cbl/COCRDUPC.cbl:L210 declares"));
        }

        /**
         * Asserts each of the four field edits reaches the caller as 422 with its own text.
         *
         * <p>The three constraints are {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at
         * {@code app/cbl/COCRDUPC.cbl:L91}, {@code 88 VALID-MONTH VALUES 1 THRU 12.} at
         * {@code app/cbl/COCRDUPC.cbl:L95} and
         * {@code 88 VALID-YEAR VALUES 1950 THRU 2099.} at {@code app/cbl/COCRDUPC.cbl:L99}. The
         * fourth text belongs to the name edit at {@code app/cbl/COCRDUPC.cbl:L184}.
         *
         * <p>{@link CardUpdateService} applies the edits and names the failing one.
         * {@code domain/CardUpdateServiceTest} drives each constraint from a submitted value. What
         * this test asserts is that the named text reaches the caller unchanged and under 422.
         */
        @Test
        void eachFieldEditReachesTheCallerAsUnprocessableContent() {
            for (String text : List.of(EXPIRY_MONTH_NOT_VALID, EXPIRY_YEAR_NOT_VALID,
                    STATUS_MUST_BE_YES_NO, NAME_MUST_BE_ALPHA)) {
                CardUpdateResponse rejected = CardUpdateResponse.validationRejected(text);

                assertAll(
                        () -> assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, statusOf(rejected),
                                "a failing edit asks the caller to correct one value"),
                        () -> assertEquals(text, textOf(rejected),
                                "the text of the failing edit reached the caller changed"),
                        () -> assertNull(assertInstanceOf(CardUpdateResponse.class,
                                answerFor(rejected).getBody()).refreshedCard(),
                                "a failing edit carries no snapshot"));
            }
        }

        /**
         * Asserts the answer carries the first failing text and no second one.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L384} sets the message field off, and fifteen
         * {@code IF WS-RETURN-MSG-OFF} guards then admit only the first setter: L730, L743, L773,
         * L787, L816, L833, L855, L868, L888, L903, L921, L939, L1399, L1404 and L1445. The
         * count is arithmetic. Seventeen occurrences of the condition name, less the declaration
         * at L174 and the reset at L384, leave fifteen guards.
         *
         * <p>The outcome record holds one message and admits no second, so a caller reads the first
         * failing text alone.
         */
        @Test
        void theAnswerCarriesTheFirstFailingTextAndNoSecondOne() {
            CardUpdateResponse rejected =
                    CardUpdateResponse.validationRejected(EXPIRY_MONTH_NOT_VALID);

            JsonNode body = serialize(answerFor(rejected).getBody());

            assertAll(
                    () -> assertEquals(EXPIRY_MONTH_NOT_VALID, body.get("message").asString(),
                            "the first failing text owns the answer"),
                    () -> assertEquals(Set.of("outcome", "message", "refreshedCard"),
                            propertyNames(body), "the update body changed shape"),
                    () -> assertFalse(body.get("message").isArray(),
                            "one message rather than a collection of them"),
                    () -> assertEquals(List.of(), forbidden(propertyNames(body), "errors",
                                    "fieldErrors", "violations", "details", "messages"),
                            "the update body started carrying a violation collection: "
                                    + propertyNames(body)));
        }

        /**
         * Asserts a rewrite that failed after the lock carries its own text.
         *
         * <p>{@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE} at {@code app/cbl/COCRDUPC.cbl:L1491}
         * sits in the bare {@code ELSE} at L1490 with no {@code IF WS-RETURN-MSG-OFF} wrapper, so
         * it overwrites a text already written. That override lives in {@link CardUpdateService},
         * and {@code domain/CardUpdateServiceTest} drives it. What this test asserts is that the
         * route reports the override text and revives no suppressed one.
         */
        @Test
        void aRewriteThatFailedAfterTheLockCarriesItsOwnText() {
            CardUpdateResponse failed = CardUpdateResponse.updateFailedAfterLock();

            ResponseEntity<?> response = answerFor(failed);
            ApiErrorResponse body = assertInstanceOf(ApiErrorResponse.class, response.getBody());

            assertAll(
                    () -> assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                            "a write the datastore refused invites a retry"),
                    () -> assertEquals(UPDATE_OF_RECORD_FAILED, body.message(),
                            "the override text reached the caller changed"),
                    () -> assertNotEquals(EXPIRY_MONTH_NOT_VALID, body.message(),
                            "no earlier text was revived on this arm"),
                    () -> assertEquals(503, body.status(),
                            "the body reports the status it was sent with"),
                    () -> assertEquals(CardController.CARD_ROUTE, body.route(),
                            "the body carries the route template and no resolved path"));
        }

        /**
         * Asserts the failure shape is the one this operation carries at 503, and the only one.
         *
         * <p>{@code api/CardApiExceptionHandler.onDatastoreUnreachable} answers the same status when
         * the card table cannot be reached at all, and it writes {@code ApiErrorResponse}. One
         * status on one operation therefore carries one schema, which is what
         * {@code src/main/resources/openapi.yaml} declares for it.
         */
        @Test
        void theRetryableWriteAndTheUnreachableStoreCarryOneShape() {
            ResponseEntity<?> refusedWrite =
                    answerFor(CardUpdateResponse.updateFailedAfterLock());

            assertAll(
                    () -> assertInstanceOf(ApiErrorResponse.class, refusedWrite.getBody(),
                            "the retryable write carries the failure shape"),
                    () -> assertEquals(MediaType.APPLICATION_JSON,
                            new CardApiExceptionHandler(Counter.builder("carddemo.card.failures")
                                    .register(new SimpleMeterRegistry()))
                                    .onDatastoreUnreachable(new QueryTimeoutException("x"), null)
                                    .getHeaders().getContentType(),
                            "the unreachable store carries the same media type"),
                    () -> assertInstanceOf(ApiErrorResponse.class,
                            new CardApiExceptionHandler(Counter.builder("carddemo.card.failures")
                                    .register(new SimpleMeterRegistry()))
                                    .onDatastoreUnreachable(new QueryTimeoutException("x"), null)
                                    .getBody(),
                            "the unreachable store carries the same shape"));
        }

        /**
         * Asserts a lost race carries five snapshot components and no card verification value.
         *
         * <p>{@code 9300-CHECK-CHANGE-IN-REC} at {@code app/cbl/COCRDUPC.cbl:L1498} compares six
         * fields at L1503-L1508 with {@code CARD-CVV-CD} first at L1503. L1511 sets the changed
         * condition and L1512-L1517 refreshes all six, the verification value at L1512. The
         * snapshot this route carries holds five of the six.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1499-L1501} folds the name to upper case before the
         * comparison, so the constructed race changes the year rather than the letter case.
         */
        @Test
        void aLostRaceCarriesFiveSnapshotComponentsAndNoVerificationValue() {
            ResponseEntity<?> response = answerFor(lostRace());
            CardUpdateResponse outcome =
                    assertInstanceOf(CardUpdateResponse.class, response.getBody());
            JsonNode snapshot = serialize(outcome).get("refreshedCard");
            Set<String> written = propertyNames(snapshot);

            assertAll(
                    () -> assertEquals(HttpStatus.CONFLICT, response.getStatusCode(),
                            "a lost race answers 409"),
                    () -> assertEquals(CHANGED_BEFORE_UPDATE, outcome.message(),
                            "the text the source sets, with some one as two words"),
                    () -> assertEquals(Set.of("embossedName", "expiryYear", "expiryMonth",
                            "expiryDay", "activeStatus"), written, "the snapshot changed shape"),
                    () -> assertEquals(5, snapshot.size(),
                            "the snapshot carries five components"),
                    () -> assertFalse(named(written, "cvv") || named(written, "verification"),
                            "the snapshot started naming the card verification value: " + written),
                    () -> assertEquals(REFRESHED_EXPIRY_YEAR,
                            outcome.refreshedCard().expiryYear(),
                            "the refreshed year slice reached the caller"),
                    () -> assertEquals(5, RefreshedCard.class.getRecordComponents().length,
                            "RefreshedCard declares five components"));
        }

        /**
         * Asserts a lock that was not acquired carries no snapshot at all.
         *
         * <p>The read for update at {@code app/cbl/COCRDUPC.cbl:L1427-L1436} takes a Customer
         * Information Control System record lock: every one of the eight {@code DEFINE FILE} blocks
         * in {@code app/csd/CARDDEMO.CSD} carries {@code UPDATEMODEL(LOCKING)} and
         * {@code READINTEG(UNCOMMITTED)}. On failure L1441 falls to the {@code ELSE} at L1443, the
         * guard at L1445 admits the setter at L1446, and L1448 jumps to
         * {@code 9200-WRITE-PROCESSING-EXIT} ahead of the {@code PERFORM 9300-CHECK-CHANGE-IN-REC}
         * at L1453. The comparison never runs, so no refreshed row exists to carry.
         */
        @Test
        void aLockThatWasNotAcquiredCarriesNoSnapshot() {
            ResponseEntity<?> response = answerFor(CardUpdateResponse.lockNotAcquired());
            CardUpdateResponse outcome =
                    assertInstanceOf(CardUpdateResponse.class, response.getBody());
            JsonNode body = serialize(outcome);

            assertAll(
                    () -> assertEquals(HttpStatus.CONFLICT, response.getStatusCode(),
                            "a row that could not be held answers 409"),
                    () -> assertEquals(COULD_NOT_LOCK, outcome.message(),
                            "the text the source sets"),
                    () -> assertNull(outcome.refreshedCard(),
                            "the conflict comparison never ran, so no snapshot exists"),
                    () -> assertTrue(body.get("refreshedCard") == null
                                    || body.get("refreshedCard").isNull(),
                            "the serialized body carries no snapshot: " + body));
        }

        /**
         * Asserts the update request carries six components with the expiry decomposed.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L115-L123} redefines
         * {@code CARD-EXPIRAION-DATE-X PIC X(10)} into a year at L117, a month at L119 and a day at
         * L121. The write path rejoins the three at L1467-L1474 and the conflict comparison slices
         * positions one to four, six to seven and nine to ten at L1505-L1507.
         *
         * <p>The card number is no component. It names the row the update rewrites and arrives as
         * the path variable of the route, which is the value
         * {@code CCUP-NEW-CARDID PIC X(16)} at {@code app/cbl/COCRDUPC.cbl:L305} names above the
         * payload group.
         */
        @Test
        void theUpdateRequestCarriesFiveComponentsWithTheExpiryDecomposed() {
            Set<String> declared = recordComponents(CardUpdateRequest.class);

            assertAll(
                    () -> assertEquals(Set.of("embossedName", "expiryYear",
                            "expiryMonth", "expiryDay", "activeStatus"), declared,
                            "the update request changed shape"),
                    () -> assertEquals(5, CardUpdateRequest.class.getRecordComponents().length,
                            "the update request declares five components"),
                    () -> assertFalse(declared.contains("cardNumber"),
                            "the card number names the row and travels in the path"),
                    () -> assertFalse(declared.contains("expirationDate"),
                            "the expiry stays decomposed rather than joined"),
                    () -> assertFalse(named(declared, "cvv") || named(declared, "verification"),
                            "the update request started naming the card verification value: "
                                    + declared));
        }

        /** Asserts the outcome the update side produced reaches the caller unchanged. */
        @Test
        void theOutcomeReachesTheCallerUnchanged() {
            CardUpdateRequest submitted = submittedUpdate();
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.of(storedCard()));
            when(cardUpdates.updateCard(eq(CARD_NUMBER), eq(submitted), any()))
                    .thenReturn(CardUpdateResponse.updated());

            ResponseEntity<?> response = controller.updateCard(CARD_TOKEN, submitted);
            CardUpdateResponse body =
                    assertInstanceOf(CardUpdateResponse.class, response.getBody());

            assertAll(
                    () -> assertEquals(HttpStatus.OK, response.getStatusCode(),
                            "an applied update answers 200"),
                    () -> assertEquals(UpdateOutcome.UPDATED, body.outcome(),
                            "the outcome is the one the update side produced"));
            verify(cardUpdates).updateCard(eq(CARD_NUMBER), eq(submitted), any());
        }

        /**
         * Asserts the row this handler resolved is the row it hands on, so nothing reads it twice.
         *
         * <p>Resolving a token to a card is the only way this route can name a card at all, so the
         * row is in hand before the update side is called. Calling it without the row would read the
         * same key again: two statements answering the same question with the same row.
         *
         * <p>The identity of the instance is asserted, not its values. A row read a second time
         * carries the same values, so comparing values would pass either way and prove nothing.
         */
        @Test
        void theResolvedRowIsHandedToTheUpdateSideRatherThanReadAgain() {
            CardUpdateRequest submitted = submittedUpdate();
            CardEntity resolved = storedCard();
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.of(resolved));
            when(cardUpdates.updateCard(any(), any(), any()))
                    .thenReturn(CardUpdateResponse.updated());
            ArgumentCaptor<CardEntity> handedOn = ArgumentCaptor.forClass(CardEntity.class);

            controller.updateCard(CARD_TOKEN, submitted);

            verify(cardUpdates).updateCard(eq(CARD_NUMBER), eq(submitted), handedOn.capture());
            assertAll(
                    () -> assertSame(resolved, handedOn.getValue(),
                            "the update side received a row other than the one resolved here, so it"
                                    + " has to read the key again"),
                    () -> verify(cardQueries, times(1)).findByCardToken(CARD_TOKEN),
                    () -> verify(cardQueries, never()).findByCardNumber(any()));
        }

        /**
         * Asserts the card the path names is the card the update side receives.
         *
         * <p>The path value is the key {@code app/cbl/COCRDUPC.cbl:L1425} moves into the read for
         * update. It reaches the update side as its own argument, so no body property can name a
         * different row.
         */
        @Test
        void theCardThePathNamesReachesTheUpdateSide() {
            CardUpdateRequest submitted = submittedUpdate();
            when(cardQueries.findByCardToken(SECOND_CARD_TOKEN))
                    .thenReturn(Optional.of(storedCard(SECOND_CARD_NUMBER)));
            when(cardUpdates.updateCard(any(), any(), any()))
                    .thenReturn(CardUpdateResponse.updated());
            ArgumentCaptor<String> named = ArgumentCaptor.forClass(String.class);

            controller.updateCard(SECOND_CARD_TOKEN, submitted);

            verify(cardUpdates).updateCard(named.capture(), eq(submitted), any());
            assertAll(
                    () -> assertEquals(SECOND_CARD_NUMBER, named.getValue(),
                            "the update side received the number of the card the path's token named,"
                                    + " read from the row rather than from the request"),
                    () -> assertEquals(16, named.getValue().length(),
                            "the key holds the width CARD-NUM PIC X(16) declares"),
                    () -> assertNotEquals(SECOND_CARD_TOKEN, named.getValue(),
                            "the token itself never reaches the update side, which edits a number"));
        }

        /**
         * Asserts a token naming no row answers the not-found outcome without reaching the update
         * side, so the seven outcomes of this route stay seven.
         *
         * <p>A caller cannot tell this answer from the one the update service reaches for a number
         * naming no row, which is the point: the two lookups are one resource to a caller.
         */
        @Test
        void aTokenNamingNoRowAnswersTheNotFoundOutcomeAndReachesNoUpdate() {
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.updateCard(CARD_TOKEN, submittedUpdate());
            CardUpdateResponse body =
                    assertInstanceOf(CardUpdateResponse.class, response.getBody());

            verify(cardUpdates, never()).updateCard(any(), any());
            assertAll(
                    () -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                            "a token naming no row answers 404"),
                    () -> assertEquals(UpdateOutcome.CARD_NOT_FOUND, body.outcome(),
                            "the outcome is the one the update service reaches for a number naming "
                                    + "no row"),
                    () -> assertEquals(DID_NOT_FIND_CARD, body.message(),
                            "the text app/cbl/COCRDUPC.cbl:L1400 sets"));
        }

        /**
         * Asserts every text this file expects fits the source message field.
         *
         * <p>{@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173} holds one text,
         * and {@code CCARD-RETURN-MSG PIC X(75)} at {@code app/cpy/CVCRD01Y.cpy:L29} repeats the
         * width. A text wider than the field would arrive truncated.
         */
        @Test
        void everyExpectedTextFitsTheSourceMessageField() {
            for (String text : allExpectedTexts()) {
                assertTrue(text.length() <= MESSAGE_FIELD_WIDTH,
                        "text holds " + text.length() + " characters and the field holds "
                                + MESSAGE_FIELD_WIDTH + ": " + text);
            }
        }

        /** Reads the status one outcome carries through the route. */
        private HttpStatus statusOf(CardUpdateResponse outcome) {
            return HttpStatus.valueOf(answerFor(outcome).getStatusCode().value());
        }

        /** Reads the text one outcome carries through the route in the update body. */
        private String textOf(CardUpdateResponse outcome) {
            return assertInstanceOf(CardUpdateResponse.class, answerFor(outcome).getBody())
                    .message();
        }

        /** Reads the text one outcome carries through the route in the failure body. */
        private String failureTextOf(CardUpdateResponse outcome) {
            return assertInstanceOf(ApiErrorResponse.class, answerFor(outcome).getBody()).message();
        }

        /** Sends one submitted update whose outcome the update side reports. */
        private ResponseEntity<?> answerFor(CardUpdateResponse outcome) {
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.of(storedCard()));
            when(cardUpdates.updateCard(any(), any(), any())).thenReturn(outcome);
            return controller.updateCard(CARD_TOKEN, submittedUpdate());
        }
    }

    /** What this surface does not check, does not expose and does not emit. */
    @Nested
    @DisplayName("what this surface never adds and never emits")
    class WhatThisSurfaceNeverAdds {

        /**
         * Asserts the token of a card whose Luhn check digit fails still reaches the read side.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L784} tests {@code IF CC-CARD-NUM IS NOT NUMERIC} and
         * nothing else. The comments above it at L782-L783 name a numeric test and a
         * sixteen-character test, and the field is {@code PIC X(16)} with no length test present.
         *
         * <p>The token is derived from that number the same way every other token is, so a checksum
         * rule could not be applied on this route even if one were wanted: nothing here reads a card
         * number at all. The read side is what would have refused, and it does not.
         *
         * <p>What the stub answers afterwards is not the subject. The subject is the argument that
         * arrived and the absence of a refusal on a checksum ground.
         */
        @Test
        void theTokenOfACardFailingTheLuhnCheckStillReachesTheReadSide() {
            String tokenOfTheFailingCard = cardToken(CARD_NUMBER_FAILING_LUHN);
            when(cardQueries.findByCardToken(tokenOfTheFailingCard))
                    .thenReturn(Optional.empty());
            ArgumentCaptor<String> looked = ArgumentCaptor.forClass(String.class);

            ResponseEntity<?> response = controller.readCard(tokenOfTheFailingCard);

            verify(cardQueries).findByCardToken(looked.capture());
            assertAll(
                    () -> assertEquals(tokenOfTheFailingCard, looked.getValue(),
                            "the token reached the read side"),
                    () -> assertNotEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                            "no checksum refusal was added"),
                    () -> assertNotEquals(HttpStatus.UNPROCESSABLE_CONTENT,
                            response.getStatusCode(),
                            "sixteen digits pass every edit, checksum or not"),
                    () -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                            "the answer is the absent-row answer the stub arranged"));
        }

        /**
         * Asserts no route of this controller answers a card-status question.
         *
         * <p>{@code app/jcl/POSTTRAN.jcl} runs the posting program in STEP15 at L23 and allocates
         * six datasets, at L28, L30, L32, L34, L39 and L41. The one at L32 is the card
         * cross-reference rather than the card file, and no allocation names the card file, so the
         * posting path never reads {@code CARD-ACTIVE-STATUS} at
         * {@code app/cpy/CVACT02Y.cpy:L10}. This controller maps two paths and neither answers a
         * status question.
         */
        @Test
        void noRouteAnswersACardStatusQuestion() {
            Set<String> mapped = mappedPaths();

            assertAll(
                    () -> assertEquals(Set.of(CardController.BASE_PATH,
                            CardController.CARD_ROUTE), mapped,
                            "the mapped path set changed"),
                    () -> assertFalse(mapped.contains(CardController.BASE_PATH + "/status"),
                            "a status route appeared: " + mapped),
                    () -> assertEquals(List.of(), mapped.stream()
                                    .filter(path -> path.toLowerCase(Locale.ROOT)
                                            .contains("status"))
                                    .toList(),
                            "a route naming a status appeared: " + mapped));
        }

        /**
         * Asserts this controller declares exactly three request-mapped handlers.
         *
         * <p>One replaces each of the three source transactions: the list of
         * {@code app/cbl/COCRDLIC.cbl}, the read of {@code app/cbl/COCRDSLC.cbl} and the update of
         * {@code app/cbl/COCRDUPC.cbl}.
         */
        @Test
        void theControllerDeclaresExactlyThreeRequestMappedHandlers() {
            Set<String> handlers = requestMappedHandlers();

            assertAll(
                    () -> assertEquals(3, handlers.size(),
                            "the handler set changed width: " + handlers),
                    () -> assertEquals(Set.of("listCards", "readCard", "updateCard"), handlers,
                            "the handler set changed: " + handlers));
        }

        /**
         * Asserts every path template holds a variable name and no identifier value.
         *
         * <p>Two routes carry the card number as a path variable, reproducing transaction
         * {@code CCDL} at {@code app/csd/CARDDEMO.CSD:L347-L348} and transaction {@code CCUP} at
         * {@code app/csd/CARDDEMO.CSD:L367-L369}. A template holds the variable name and never a
         * resolved value, which is what every failing body reports:
         * {@code api/dto/ApiErrorResponse} refuses a route holding a run of more than four digits.
         */
        @Test
        void everyPathTemplateHoldsAVariableNameAndNoIdentifierValue() {
            for (String path : mappedPaths()) {
                assertFalse(path.matches(".*[0-9]{5,}.*"),
                        "a route template holds a run of digits: " + path);
                assertFalse(path.contains("{}"), "a route template holds an unnamed variable: "
                        + path);
            }
            assertEquals(Set.of(CardController.BASE_PATH, CardController.CARD_ROUTE), mappedPaths(),
                    "the mapped path set changed");
            assertEquals("/cards/{cardToken}", CardController.CARD_ROUTE,
                    "the two card-numbered routes carry the variable the source keys on at "
                            + "app/cbl/COCRDSLC.cbl:L740");
        }

        /**
         * Asserts the card verification value reaches none of the three bodies.
         *
         * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} is stored in the
         * clear, and {@code app/cbl/COCRDUPC.cbl:L1464-L1465} shows the update record carrying it.
         * The column exists here and the value leaves through no response.
         *
         * <p>Each assertion reads the whole serialized text of one body rather than one property
         * of it. The one stubbed row is the only source of the three digits in these three
         * bodies.
         */
        @Test
        void theCardVerificationValueReachesNoBody() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(oneRowPage());
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.of(storedCard()));
            when(cardUpdates.updateCard(any(), any(), any())).thenReturn(lostRace());

            String listBody = rendered(controller.listCards(ACCOUNT_ID, null,
                    CardController.FORWARD_DIRECTION, null));
            String readBody = rendered(controller.readCard(CARD_TOKEN).getBody());
            String updateBody =
                    rendered(controller.updateCard(CARD_TOKEN, submittedUpdate()).getBody());

            assertAll(
                    () -> assertTrue(listBody.contains(MASKED_CARD_NUMBER),
                            "the list body holds the stubbed row: " + listBody),
                    () -> assertTrue(readBody.contains(EMBOSSED_NAME),
                            "the read body holds the stubbed row: " + readBody),
                    () -> assertTrue(updateBody.contains(REFRESHED_EXPIRY_YEAR),
                            "the update body holds the lost-race snapshot: " + updateBody),

                    () -> assertFalse(listBody.contains(CARD_VERIFICATION_VALUE),
                            "the list body carries the value: " + listBody),
                    () -> assertFalse(readBody.contains(CARD_VERIFICATION_VALUE),
                            "the read body carries the value: " + readBody),
                    () -> assertFalse(updateBody.contains(CARD_VERIFICATION_VALUE),
                            "the update body carries the value: " + updateBody),

                    () -> assertFalse(namesTheValue(listBody), "the list body names it: "
                            + listBody),
                    () -> assertFalse(namesTheValue(readBody), "the read body names it: "
                            + readBody),
                    () -> assertFalse(namesTheValue(updateBody), "the update body names it: "
                            + updateBody));
        }

        /**
         * Asserts no body carries the full card number.
         *
         * <p>The masked form is the only one that leaves, and the list cursor is a card token that
         * holds no digit of a card number.
         */
        @Test
        void noBodyCarriesTheFullCardNumber() {
            when(cardQueries.listForward(any(), any(), any(), any())).thenReturn(oneRowPage());
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.of(storedCard()));

            String listBody = rendered(controller.listCards(ACCOUNT_ID, null,
                    CardController.FORWARD_DIRECTION, null));
            String readBody = rendered(controller.readCard(CARD_TOKEN).getBody());

            assertAll(
                    () -> assertFalse(listBody.contains(CARD_NUMBER),
                            "the list body carries the full number: " + listBody),
                    () -> assertFalse(readBody.contains(CARD_NUMBER),
                            "the read body carries the full number: " + readBody),
                    () -> assertTrue(listBody.contains(MASKED_CARD_NUMBER),
                            "the list body carries the masked form: " + listBody),
                    () -> assertTrue(readBody.contains(MASKED_CARD_NUMBER),
                            "the read body carries the masked form: " + readBody));
        }

        /**
         * Asserts a failing read body carries three properties and one text.
         *
         * <p>{@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173} is the whole
         * reporting device of the source, and {@code CCARD-RETURN-MSG PIC X(75)} at
         * {@code app/cpy/CVCRD01Y.cpy:L29} repeats it. One field holds one text.
         */
        @Test
        void aFailingReadBodyCarriesThreePropertiesAndOneText() {
            when(cardQueries.findByCardToken(CARD_TOKEN)).thenReturn(Optional.empty());

            JsonNode body = serialize(controller.readCard(CARD_TOKEN).getBody());
            Set<String> written = propertyNames(body);

            assertAll(
                    () -> assertEquals(Set.of("status", "message", "route"), written,
                            "the failing read body changed shape"),
                    () -> assertEquals(3, body.size(),
                            "the failing read body carries three properties"),
                    () -> assertFalse(body.get("message").isArray(),
                            "one text rather than a collection of them"),
                    () -> assertEquals(List.of(), forbidden(written, "errors", "fieldErrors",
                                    "violations", "details", "messages", "fields"),
                            "the failing read body started carrying a violation collection: "
                                    + written));
        }

        /**
         * Asserts a failing body echoes no submitted value.
         *
         * <p>The refusal names the route template and quotes no value the request carried. A read
         * that found no row is the failure this test drives, since a malformed path value is
         * refused by the constraint on the path variable before the handler runs.
         */
        @Test
        void aFailingBodyEchoesNoSubmittedValue() {
            when(cardQueries.findByCardToken(SECOND_CARD_TOKEN)).thenReturn(Optional.empty());

            ApiErrorResponse body = assertInstanceOf(ApiErrorResponse.class,
                    controller.readCard(SECOND_CARD_TOKEN).getBody());
            String rendered = rendered(body);

            assertAll(
                    () -> assertFalse(rendered.contains(SECOND_CARD_TOKEN),
                            "the refusal echoed the token the path named: " + rendered),
                    () -> assertFalse(rendered.contains(SECOND_CARD_NUMBER),
                            "the refusal named the card number of that token: " + rendered),
                    () -> assertEquals(DID_NOT_FIND_CARD, body.message(),
                            "the literal app/cbl/COCRDSLC.cbl:L760 sets"),
                    () -> assertEquals(CardController.CARD_ROUTE, body.route(),
                            "the body carries the template and no resolved path"));
        }

        /**
         * Asserts the path constraint admits a card token and refuses a card number.
         *
         * <p>The path variable is the token, so the constraint is the one shape
         * {@link PanMasker#CARD_TOKEN_PATTERN} declares and the text is additive. The source edit is
         * not replaced by it: {@code app/cbl/COCRDUPC.cbl:L784} reads
         * {@code IF CC-CARD-NUM IS NOT NUMERIC} over a {@code PIC X(16)} field and
         * {@code app/cbl/COCRDUPC.cbl:L789} declares its text, and both still run in
         * {@code domain/CardUpdateService} on the number a token resolved to. A full card number in
         * the path fails this constraint, which is the property that keeps a Primary Account Number
         * out of a request line even where a caller sends one.
         */
        @Test
        void thePathConstraintAdmitsATokenAndRefusesACardNumber() {
            assertAll(
                    () -> assertEquals(PanMasker.CARD_TOKEN_PATTERN,
                            CardController.CARD_TOKEN_PATTERN,
                            "one declaration of the token shape across the platform"),
                    () -> assertEquals("^[0-9a-f]{64}$", CardController.CARD_TOKEN_PATTERN,
                            "sixty-four lower-case hexadecimal characters"),
                    () -> assertTrue(PanMasker.cardToken(CARD_NUMBER)
                                    .matches(CardController.CARD_TOKEN_PATTERN),
                            "the fixture card's token passes"),
                    () -> assertFalse(CARD_NUMBER.matches(CardController.CARD_TOKEN_PATTERN),
                            "a full sixteen-digit card number fails, so no Primary Account Number "
                                    + "reaches the handler through this path"),
                    () -> assertFalse(PanMasker.cardToken(CARD_NUMBER).toUpperCase(Locale.ROOT)
                                    .matches(CardController.CARD_TOKEN_PATTERN),
                            "an upper-case rendering fails, so one card has one path value"),
                    () -> assertFalse(PanMasker.cardToken(CARD_NUMBER).substring(1)
                                    .matches(CardController.CARD_TOKEN_PATTERN),
                            "sixty-three characters fail"),
                    () -> assertEquals(CardValidationMessages.ADDITIVE_CARD_TOKEN_MALFORMED,
                            CardController.CARD_TOKEN_MESSAGE,
                            "the refusal names the shape it required and no value"),
                    () -> assertEquals(CARD_FILTER_NOT_NUMERIC,
                            CardValidationMessages.CARD_FILTER_NOT_NUMERIC,
                            "the source text app/cbl/COCRDUPC.cbl:L789 declares is still held, and "
                                    + "domain/CardUpdateServiceTest is where it is exercised"));
        }

        /** Reports whether one rendered body names the card verification value. */
        private boolean namesTheValue(String body) {
            String lowered = body.toLowerCase(Locale.ROOT);
            return lowered.contains("cvv") || lowered.contains("verification");
        }
    }

    /** The constructor's own guards. */
    @Nested
    @DisplayName("the constructor")
    class Constructor {

        @Test
        void everyCollaboratorIsRequired() {
            assertAll(
                    () -> assertThrows(NullPointerException.class,
                            () -> new CardController(null, cardUpdates),
                            "the read side is required"),
                    () -> assertThrows(NullPointerException.class,
                            () -> new CardController(cardQueries, null),
                            "the update side is required"));
        }
    }

    /**
     * Builds the stored card the read tests return, holding the six values of fixture row one.
     *
     * @return the stored card
     */
    private static CardEntity storedCard() {
        return storedCard(CARD_NUMBER);
    }

    /**
     * Builds a stored card carrying one card number and the other five values of fixture row one.
     *
     * <p>The row is what a token resolves to, and the number it holds is what reaches the update
     * side, so a test that names a second card needs a row carrying that second number.
     *
     * @param cardNumber the card number the row holds
     * @return the stored card
     */
    private static CardEntity storedCard(String cardNumber) {
        return new CardEntity(cardNumber, ACCOUNT_ID, CARD_VERIFICATION_VALUE, EMBOSSED_NAME,
                EXPIRATION_DATE, ACTIVE_STATUS);
    }

    /**
     * Builds the page the list tests return when one row matches.
     *
     * @return a page of one row with no further page
     */
    private static CardPage oneRowPage() {
        return new CardPage(List.of(new CardListRow(CARD_NUMBER, ACCOUNT_ID, ACTIVE_STATUS)),
                false, cardToken(CARD_NUMBER), cardToken(CARD_NUMBER));
    }

    /**
     * Builds a page of two rows in ascending card-number order, with the cursors that order implies.
     *
     * <p>{@code app/jcl/CARDFILE.jcl:L40} declares {@code KEYS(16 0)}, and the browse at
     * {@code app/cbl/COCRDLIC.cbl:L1160-L1216} reads that dataset forward, so a page arrives with its
     * lowest card number first. {@link #CARD_NUMBER} is the lower of the two, so it is row zero and
     * the first-row cursor, and {@link #SECOND_CARD_NUMBER} is row one and the last-row cursor.
     *
     * <p>The order is what makes the two cursor assertions mean anything. A page built the other way
     * round names the highest row as its first, so a backward page appears to continue from the token
     * the production service would only ever return going forward, and both directions pass on one
     * value.
     *
     * @param furtherPage whether a further page follows
     * @return the page
     */
    private static CardPage twoRowPage(boolean furtherPage) {
        List<CardListRow> rows = List.of(
                new CardListRow(CARD_NUMBER, ACCOUNT_ID, ACTIVE_STATUS),
                new CardListRow(SECOND_CARD_NUMBER, ACCOUNT_ID, ACTIVE_STATUS));
        return new CardPage(rows, furtherPage, cardToken(CARD_NUMBER),
                cardToken(SECOND_CARD_NUMBER));
    }

    /**
     * Builds the page the list tests return when nothing matches.
     *
     * @return a page of no rows, no further page and no cursor
     */
    private static CardPage emptyPage() {
        return new CardPage(List.of(), false, null, null);
    }

    /**
     * Builds the lost-race outcome, whose snapshot changes the year rather than the letter case.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1499-L1501} folds the name to upper case before the
     * comparison, so a case-only difference is no difference.
     *
     * @return the outcome carrying a refreshed snapshot
     */
    private static CardUpdateResponse lostRace() {
        return CardUpdateResponse.changedBeforeUpdate(
                new RefreshedCard(EMBOSSED_NAME, REFRESHED_EXPIRY_YEAR, "01", "02", "N"));
    }

    /**
     * Builds the submitted update the update tests send.
     *
     * @return the request, with the expiry decomposed into a year, a month and a day
     */
    private static CardUpdateRequest submittedUpdate() {
        return new CardUpdateRequest("MORGAN", EXPIRY_YEAR, EXPIRY_MONTH, EXPIRY_DAY, "N");
    }

    /**
     * Derives the keyed card token of one card number.
     *
     * <p>The build supplies the key through the two surefire system properties the aggregator
     * declares, so no test prepares anything.
     *
     * @param cardNumber the full card number
     * @return the token
     */
    private static String cardToken(String cardNumber) {
        return PanMasker.cardToken(cardNumber);
    }

    /**
     * Names every text this file expects a response to carry.
     *
     * @return the texts
     */
    private static List<String> allExpectedTexts() {
        return List.of(DID_NOT_FIND_CARD, COULD_NOT_LOCK, CHANGED_BEFORE_UPDATE,
                UPDATE_OF_RECORD_FAILED, NO_CHANGES_DETECTED, NAME_MUST_BE_ALPHA,
                STATUS_MUST_BE_YES_NO, EXPIRY_MONTH_NOT_VALID, EXPIRY_YEAR_NOT_VALID,
                NO_INPUT_RECEIVED, ACCOUNT_NOT_PROVIDED, CARD_NOT_PROVIDED,
                ACCOUNT_FILTER_NOT_NUMERIC, CARD_FILTER_NOT_NUMERIC);
    }

    /**
     * Serializes one body and parses it back, so an assertion can read its shape.
     *
     * @param body the response body
     * @return the parsed document
     */
    private static JsonNode serialize(Object body) {
        return MAPPER.readTree(rendered(body));
    }

    /**
     * Serializes one body to text, so an assertion can read the whole of it.
     *
     * @param body the response body
     * @return the serialized text
     */
    private static String rendered(Object body) {
        return MAPPER.writeValueAsString(body);
    }

    /**
     * Names the properties one document carries.
     *
     * @param document the parsed document
     * @return the property names, in the order written
     */
    private static Set<String> propertyNames(JsonNode document) {
        return new LinkedHashSet<>(document.propertyNames());
    }

    /**
     * Names the components one record declares.
     *
     * @param type the record type
     * @return the component names, sorted
     */
    private static Set<String> recordComponents(Class<?> type) {
        Set<String> named = new TreeSet<>();
        for (RecordComponent component : type.getRecordComponents()) {
            named.add(component.getName());
        }
        return named;
    }

    /**
     * Reports whether any name carries one fragment, compared without case.
     *
     * @param names    the names to search
     * @param fragment the fragment to look for, in lower case
     * @return {@code true} when one name carries it
     */
    private static boolean named(Set<String> names, String fragment) {
        for (String name : names) {
            if (name.toLowerCase(Locale.ROOT).contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Names which of several forbidden properties one document carries.
     *
     * @param names     the property names the document carries
     * @param forbidden the names that must not appear
     * @return the forbidden names present, which is empty when none is
     */
    private static List<String> forbidden(Set<String> names, String... forbidden) {
        List<String> present = new ArrayList<>();
        for (String candidate : forbidden) {
            if (names.contains(candidate)) {
                present.add(candidate);
            }
        }
        return present;
    }

    /**
     * Names every path the controller maps, joining the collection path to each handler path.
     *
     * @return the mapped paths
     */
    private static Set<String> mappedPaths() {
        Set<String> paths = new TreeSet<>();
        for (Method method : CardController.class.getDeclaredMethods()) {
            for (String suffix : mappedSuffixes(method)) {
                paths.add(CardController.BASE_PATH + suffix);
            }
        }
        return paths;
    }

    /**
     * Names every handler the controller maps to a request.
     *
     * @return the handler names
     */
    private static Set<String> requestMappedHandlers() {
        Set<String> handlers = new TreeSet<>();
        for (Method method : CardController.class.getDeclaredMethods()) {
            if (!mappedSuffixes(method).isEmpty()) {
                handlers.add(method.getName());
            }
        }
        return handlers;
    }

    /**
     * Names the paths one method maps below the collection path.
     *
     * <p>A mapping that names no path contributes the empty suffix, which is the collection itself.
     *
     * @param method the candidate handler
     * @return the suffixes, which is empty when the method maps no request
     */
    private static List<String> mappedSuffixes(Method method) {
        List<String> suffixes = new ArrayList<>();
        GetMapping get = method.getAnnotation(GetMapping.class);
        PostMapping post = method.getAnnotation(PostMapping.class);
        PutMapping put = method.getAnnotation(PutMapping.class);
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        RequestMapping request = method.getAnnotation(RequestMapping.class);

        if (get != null) {
            collect(suffixes, get.path(), get.value());
        }
        if (post != null) {
            collect(suffixes, post.path(), post.value());
        }
        if (put != null) {
            collect(suffixes, put.path(), put.value());
        }
        if (patch != null) {
            collect(suffixes, patch.path(), patch.value());
        }
        if (delete != null) {
            collect(suffixes, delete.path(), delete.value());
        }
        if (request != null) {
            collect(suffixes, request.path(), request.value());
        }
        return suffixes;
    }

    /**
     * Adds the declared paths of one mapping, or the empty suffix when it declares none.
     *
     * @param suffixes the list to add to
     * @param path     the {@code path} member of the mapping
     * @param value    the {@code value} member of the mapping
     */
    private static void collect(List<String> suffixes, String[] path, String[] value) {
        for (String declared : path) {
            suffixes.add(declared);
        }
        for (String declared : value) {
            suffixes.add(declared);
        }
        if (path.length == 0 && value.length == 0) {
            suffixes.add("");
        }
    }

    /**
     * Reports whether one field holds a whole number.
     *
     * @param field the field
     * @return {@code true} for the five integral types a row count could use
     */
    private static boolean holdsAWholeNumber(Field field) {
        Class<?> type = field.getType();
        return type == int.class || type == Integer.class || type == long.class
                || type == Long.class || type == short.class;
    }

    /**
     * Reads the value one static field holds.
     *
     * @param field the field
     * @return the value
     */
    private static Object readStaticValue(Field field) {
        try {
            return field.get(null);
        } catch (IllegalAccessException unreachable) {
            throw new AssertionError("a field this test made accessible refused a read: "
                    + field.getName(), unreachable);
        }
    }
}

package com.carddemo.card.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.carddemo.card.domain.CardQueryService.CardPage;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * Asserts the read side of the card service carries no card number where a caller can see one, and
 * refuses a card number that is not a sixteen-digit number.
 *
 * <p>Two properties are under test here, and both concern what crosses the boundary of this service
 * rather than what it computes.
 *
 * <p>The first is the paging cursor. The card list program keeps its browse position in
 * {@code WS-CA-LAST-CARD-NUM PIC X(16)} at {@code app/cbl/COCRDLIC.cbl:L231} and reads it back as
 * the browse key at {@code app/cbl/COCRDLIC.cbl:L488-L489}. That value never leaves the region: it
 * lives in working storage no terminal receives. A cursor here does leave, in a response, and
 * returns in the next request, which makes it published data. Section 0.6.4 of the plan admits only
 * a tokenized or masked form in published data, and a masked form names every card sharing four
 * digits and so names no single browse position. The cursor therefore carries a card token, and
 * {@link CardQueryService} resolves it back to a card number itself.
 *
 * <p>The second is the character class of a card number. {@code CARD-NUM PIC X(16)} at
 * {@code app/cpy/CVACT02Y.cpy:L5} is alphanumeric where it is stored, and every input path in the
 * source narrows it to digits: {@code IF CC-CARD-NUM IS NOT NUMERIC} at
 * {@code app/cbl/COCRDLIC.cbl:L1052} on the list filter, and the same test at
 * {@code app/cbl/COCRDUPC.cbl:L784} on the update screen. Both report a sixteen-digit number, at
 * {@code app/cbl/COCRDLIC.cbl:L1058} and {@code app/cbl/COCRDUPC.cbl:L193-L194}.
 *
 * <p>No checksum is asserted anywhere in this class, and none may be added. Section 0.2.2 of the
 * plan records that the source validates sixteen digits and nothing further, so a checksum check
 * would decline a card the source approves.
 *
 * <p>How this class runs. The repository is a mock, so no database, no container and no
 * Representational State Transfer (REST) request takes part. Every fixture is typed or derived here.
 */
@DisplayName("CardQueryService: an opaque paging cursor, and digits where a card number belongs")
class CardQueryServiceTest {

    /** Card number of row one of {@code app/data/ASCII/carddata.txt}, offset 1, width 16. */
    private static final String ROW_1_CARD_NUMBER = "0500024453765740";

    /** Card number of row two of the same fixture. */
    private static final String ROW_2_CARD_NUMBER = "0683586198171516";

    /** Account identifier of row one, offset 17, width 11. */
    private static final String ROW_1_ACCOUNT_ID = "00000000050";

    /** Account identifier of row two. */
    private static final String ROW_2_ACCOUNT_ID = "00000000027";

    /** Card verification value of row one, offset 88, width 3. */
    private static final String ROW_1_VERIFICATION_VALUE = "747";

    /** Embossed name of row one, offset 28, width 50, trimmed of its padding. */
    private static final String ROW_1_EMBOSSED_NAME = "Aniya Von";

    /** Expiration date of row one, offset 78, width 10. */
    private static final LocalDate ROW_1_EXPIRATION_DATE = LocalDate.of(2023, 3, 9);

    /** Active status of row one, offset 91, width 1. */
    private static final String ACTIVE_STATUS_YES = "Y";

    /**
     * Rows one page holds by default, from {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
     * {@code app/cbl/COCRDLIC.cbl:L177-L178}.
     */
    private static final int DEFAULT_PAGE_SIZE = 7;

    private CardRepository cardRepository;

    private CardQueryService service;

    @BeforeEach
    void setUp() {
        cardRepository = mock(CardRepository.class);
        service = new CardQueryService(cardRepository);
    }

    /**
     * Builds one card row through the production constructor, which derives its card token.
     *
     * @param cardNumber the card number of the row
     * @param accountId  the account identifier of the row
     * @return the row
     */
    private static CardEntity cardRow(String cardNumber, String accountId) {
        return new CardEntity(cardNumber, accountId, ROW_1_VERIFICATION_VALUE, ROW_1_EMBOSSED_NAME,
                ROW_1_EXPIRATION_DATE, ACTIVE_STATUS_YES);
    }

    /**
     * Reads a page forward with no cursor and no filter, at the default page size.
     *
     * @param fetched the rows the repository returns
     * @return the page the service built
     */
    private CardPage firstPageOf(List<CardEntity> fetched) {
        when(cardRepository.findFirstPage(any(Limit.class)))
                .thenReturn(fetched);
        return service.listForward(null, null, null, null);
    }

    /** The cursor a page carries is a card token, and no card number reaches a caller. */
    @Nested
    @DisplayName("The paging cursor carries a card token")
    class CursorForm {

        @Test
        @DisplayName("both cursors of a page are the card tokens of its first and last row")
        void bothCursorsAreCardTokens() {
            CardPage page = firstPageOf(List.of(cardRow(ROW_1_CARD_NUMBER, ROW_1_ACCOUNT_ID),
                    cardRow(ROW_2_CARD_NUMBER, ROW_2_ACCOUNT_ID)));

            assertAll(
                    () -> assertEquals(PanMasker.cardToken(ROW_1_CARD_NUMBER),
                            page.firstCardToken(),
                            "the backward cursor is the token of the first row"),
                    () -> assertEquals(PanMasker.cardToken(ROW_2_CARD_NUMBER),
                            page.lastCardToken(),
                            "the forward cursor is the token of the last row"),
                    () -> assertTrue(page.firstCardToken()
                                    .matches(PanMasker.CARD_TOKEN_PATTERN),
                            "the cursor holds the one card-token shape this platform declares"),
                    () -> assertTrue(page.lastCardToken().matches(PanMasker.CARD_TOKEN_PATTERN),
                            "the cursor holds the one card-token shape this platform declares"));
        }

        @Test
        @DisplayName("no cursor carries a card number, its masked form or any four-digit tail")
        void noCursorCarriesACardNumber() {
            CardPage page = firstPageOf(List.of(cardRow(ROW_1_CARD_NUMBER, ROW_1_ACCOUNT_ID),
                    cardRow(ROW_2_CARD_NUMBER, ROW_2_ACCOUNT_ID)));

            assertAll(
                    () -> assertFalse(page.firstCardToken().contains(ROW_1_CARD_NUMBER),
                            "a cursor carrying a card number is the disclosure section 0.6.4 "
                                    + "of the plan forbids"),
                    () -> assertFalse(page.lastCardToken().contains(ROW_2_CARD_NUMBER),
                            "a cursor carrying a card number is the disclosure section 0.6.4 "
                                    + "of the plan forbids"),
                    () -> assertNotEquals(PanMasker.maskCardNumber(ROW_1_CARD_NUMBER),
                            page.firstCardToken(),
                            "the masked form names every card sharing four digits, so it names "
                                    + "no single browse position"),
                    () -> assertEquals(PanMasker.CARD_TOKEN_LENGTH, page.lastCardToken().length(),
                            "a cursor at card-number width would be a card number, whatever it "
                                    + "held. Card-number width is " + PicClause.CARD_NUM_WIDTH));
        }

        @Test
        @DisplayName("the rendering of a page names its cursors and counts its rows")
        void theRenderingNamesTheCursorsAndCountsTheRows() {
            CardPage page = firstPageOf(List.of(cardRow(ROW_1_CARD_NUMBER, ROW_1_ACCOUNT_ID)));
            String rendered = page.toString();

            assertAll(
                    () -> assertTrue(rendered.contains("rows=1 on this page"),
                            "the rendering counts the page: " + rendered),
                    () -> assertTrue(rendered.contains(PanMasker.cardToken(ROW_1_CARD_NUMBER)),
                            "a card token discloses nothing, so it appears in full: " + rendered),
                    () -> assertFalse(rendered.contains(ROW_1_CARD_NUMBER),
                            "no card number reaches a log line: " + rendered));
        }

        @Test
        @DisplayName("an empty page carries neither cursor and reports no further page")
        void anEmptyPageCarriesNoCursor() {
            CardPage page = firstPageOf(List.of());

            assertAll(
                    () -> assertTrue(page.rows().isEmpty(), "the page holds no row"),
                    () -> assertFalse(page.nextPageExists(), "no further page follows an empty one"),
                    () -> assertNull(page.firstCardToken(), "an empty page carries no cursor"),
                    () -> assertNull(page.lastCardToken(), "an empty page carries no cursor"),
                    () -> assertTrue(page.toString().contains("absent"),
                            "the rendering names an absent cursor: " + page));
        }

        @Test
        @DisplayName("a page refuses a cursor that is not a card token")
        void aPageRefusesACursorThatIsNotACardToken() {
            List<CardQueryService.CardListRow> rows = List.of(new CardQueryService.CardListRow(
                    ROW_1_CARD_NUMBER, ROW_1_ACCOUNT_ID, ACTIVE_STATUS_YES));
            String token = PanMasker.cardToken(ROW_1_CARD_NUMBER);

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new CardPage(rows, false, ROW_1_CARD_NUMBER, ROW_1_CARD_NUMBER),
                            "a page carried a card number as its cursor"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new CardPage(rows, false,
                                    PanMasker.maskCardNumber(ROW_1_CARD_NUMBER), token),
                            "a page carried a masked card number as its cursor"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new CardPage(rows, false, token,
                                    token.toUpperCase(Locale.ROOT)),
                            "a page carried upper-case hexadecimal, which is not the shape "
                                    + "ck_card_card_token_hex declares"));
        }
    }

    /** A cursor is resolved to a browse position server-side, and an unknown one is refused. */
    @Nested
    @DisplayName("A cursor resolves to a browse position through the token column")
    class CursorResolution {

        @Test
        @DisplayName("a cursor becomes the card number of the row it names")
        void aCursorBecomesACardNumber() {
            String token = PanMasker.cardToken(ROW_1_CARD_NUMBER);
            when(cardRepository.findByCardToken(token))
                    .thenReturn(Optional.of(cardRow(ROW_1_CARD_NUMBER, ROW_1_ACCOUNT_ID)));
            when(cardRepository.findPageAfter(eq(ROW_1_CARD_NUMBER), any(Limit.class)))
                    .thenReturn(List.of());

            CardPage page = service.listForward(token, null, null, null);

            assertTrue(page.rows().isEmpty(), "the stub returned no row");
            verify(cardRepository).findPageAfter(eq(ROW_1_CARD_NUMBER), any(Limit.class));
        }

        @Test
        @DisplayName("a backward cursor resolves through the same column")
        void aBackwardCursorResolvesThroughTheSameColumn() {
            String token = PanMasker.cardToken(ROW_2_CARD_NUMBER);
            when(cardRepository.findByCardToken(token))
                    .thenReturn(Optional.of(cardRow(ROW_2_CARD_NUMBER, ROW_2_ACCOUNT_ID)));
            when(cardRepository.findPageBefore(eq(ROW_2_CARD_NUMBER), any(Limit.class)))
                    .thenReturn(List.of());

            service.listBackward(token, null, null, null);

            verify(cardRepository).findPageBefore(eq(ROW_2_CARD_NUMBER), any(Limit.class));
        }

        @Test
        @DisplayName("a blank or absent cursor asks for the first page and reaches no lookup")
        void anAbsentCursorAsksForTheFirstPage() {
            when(cardRepository.findFirstPage(any(Limit.class)))
                    .thenReturn(List.of());

            service.listForward(null, null, null, null);
            service.listForward("   ", null, null, null);

            verify(cardRepository, never()).findByCardToken(any());
        }

        @Test
        @DisplayName("a cursor naming no card is refused rather than restarting the browse")
        void aCursorNamingNoCardIsRefused() {
            String token = PanMasker.cardToken(ROW_1_CARD_NUMBER);
            when(cardRepository.findByCardToken(token)).thenReturn(Optional.empty());

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> service.listForward(token, null, null, null));

            assertAll(
                    () -> assertTrue(thrown.getMessage().contains("cursor names no card"),
                            "the failure names the cursor: " + thrown.getMessage()),
                    () -> verify(cardRepository, never()).findFirstPage(any(Limit.class)));
        }

        @Test
        @DisplayName("a cursor at card-number width is refused before any lookup runs")
        void aCardNumberIsRefusedAsACursor() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.listForward(ROW_1_CARD_NUMBER, null, null, null),
                            "a card number is not a cursor"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.listBackward(
                                    PanMasker.maskCardNumber(ROW_1_CARD_NUMBER), null, null, null),
                            "a masked card number is not a cursor"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.listForward(PanMasker.cardToken(ROW_1_CARD_NUMBER)
                                    .toUpperCase(Locale.ROOT), null, null, null),
                            "upper-case hexadecimal is not the shape the column declares"),
                    () -> verify(cardRepository, never()).findByCardToken(any()));
        }
    }

    /** A card number reaching this service carries sixteen digits and nothing else. */
    @Nested
    @DisplayName("A card number carries digits, on every path that accepts one")
    class CardNumberCharacterClass {

        @Test
        @DisplayName("a card lookup left-pads a short value and reads the padded key")
        void aCardLookupPadsAShortValue() {
            when(cardRepository.findByCardNumber("0000000000005740")).thenReturn(Optional.empty());

            assertTrue(service.findByCardNumber("5740").isEmpty(),
                    "a short value is padded to the declared width and matches no row here");
            verify(cardRepository).findByCardNumber("0000000000005740");
        }

        @Test
        @DisplayName("a card lookup refuses a value holding a character outside 0 through 9")
        void aCardLookupRefusesANonNumericValue() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.findByCardNumber("050002445376574A"),
                            "IF CC-CARD-NUM IS NOT NUMERIC at app/cbl/COCRDUPC.cbl:L784 rejects "
                                    + "a letter in a card number"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.findByCardNumber("0500-0244-5376-5740"),
                            "separators break both the width and the character class"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.findByCardNumber(
                                    PanMasker.maskCardNumber(ROW_1_CARD_NUMBER)),
                            "a masked card number holds twelve asterisks, and a lookup on it "
                                    + "would match no row while reporting nothing wrong"),
                    () -> verify(cardRepository, never()).findByCardNumber(any()));
        }

        @Test
        @DisplayName("a card lookup refuses a blank value and a value past its declared width")
        void aCardLookupRefusesABlankOrOversizedValue() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.findByCardNumber("   "),
                            "a blank value names no row"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.findByCardNumber("0".repeat(
                                    PicClause.CARD_NUM_WIDTH + 1)),
                            "CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5 declares sixteen "
                                    + "characters"));
        }

        @Test
        @DisplayName("a card number filter refuses a value holding a character outside 0 through 9")
        void aCardNumberFilterRefusesANonNumericValue() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.listForward(null, null, null, "050002445376574A"),
                            "IF CC-CARD-NUM IS NOT NUMERIC at app/cbl/COCRDLIC.cbl:L1052 rejects "
                                    + "a letter in the filter, and only its ELSE branch at "
                                    + "app/cbl/COCRDLIC.cbl:L1063-L1065 sets FLG-CARDFILTER-ISVALID"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.listBackward(null, null, null, "****************"),
                            "a masked value is not a filter the source would accept"),
                    () -> verify(cardRepository, never()).findFirstPage(any(Limit.class)));
        }

        @Test
        @DisplayName("a blank or all-zero card filter is absent rather than invalid")
        void aBlankOrZeroCardFilterIsAbsent() {
            when(cardRepository.findFirstPage(any(Limit.class)))
                    .thenReturn(List.of());

            service.listForward(null, null, null, "   ");
            service.listForward(null, null, null, "0000000000000000");
            service.listForward(null, null, null, "0");

            verify(cardRepository, times(3)).findFirstPage(any(Limit.class));
        }

        @Test
        @DisplayName("a card number filter reaches the query padded to its declared width")
        void aCardNumberFilterReachesTheQueryPadded() {
            when(cardRepository.findByCardNumber("0000000000005740"))
                    .thenReturn(Optional.empty());

            service.listForward(null, null, null, "5740");

            verify(cardRepository).findByCardNumber("0000000000005740");
        }

        @Test
        @DisplayName("an account identifier filter keeps the digit check it already carried")
        void anAccountFilterKeepsItsDigitCheck() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.listForward(null, null, "0000000005A", null),
                    "CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6 is a numeric display field");
        }
    }

    /** The page size and the lookahead keep the counts the source declares. */
    @Nested
    @DisplayName("Page size and lookahead")
    class Paging {

        @Test
        @DisplayName("the default page trims the lookahead row and reports a further page")
        void theDefaultPageTrimsTheLookaheadRow() {
            List<CardEntity> fetched = new ArrayList<>();
            for (int ordinal = 0; ordinal <= DEFAULT_PAGE_SIZE; ordinal++) {
                fetched.add(cardRow(String.format("%016d", ordinal + 1), ROW_1_ACCOUNT_ID));
            }

            CardPage page = firstPageOf(fetched);

            assertAll(
                    () -> assertEquals(DEFAULT_PAGE_SIZE, page.rows().size(),
                            "WS-MAX-SCREEN-LINES VALUE 7 at app/cbl/COCRDLIC.cbl:L177-L178"),
                    () -> assertTrue(page.nextPageExists(),
                            "the extra row is the lookahead at app/cbl/COCRDLIC.cbl:L1197-L1205"),
                    () -> assertEquals(PanMasker.cardToken(String.format("%016d",
                                    DEFAULT_PAGE_SIZE)), page.lastCardToken(),
                            "the forward cursor names the last row on the page and not the "
                                    + "lookahead row a caller never received"));
        }

        @Test
        @DisplayName("a backward page reaches a caller in ascending order")
        void aBackwardPageReachesACallerAscending() {
            when(cardRepository.findLastPage(any(Limit.class)))
                    .thenReturn(List.of(cardRow(ROW_2_CARD_NUMBER, ROW_2_ACCOUNT_ID),
                            cardRow(ROW_1_CARD_NUMBER, ROW_1_ACCOUNT_ID)));

            CardPage page = service.listBackward(null, null, null, null);

            assertAll(
                    () -> assertEquals(PanMasker.cardToken(ROW_1_CARD_NUMBER),
                            page.firstCardToken(),
                            "the descending rows the database returns are reversed for display, "
                                    + "which app/cbl/COCRDLIC.cbl:L1338-L1346 reaches by filling "
                                    + "its row table from the high index down"),
                    () -> assertEquals(PanMasker.cardToken(ROW_2_CARD_NUMBER),
                            page.lastCardToken(),
                            "the last row of an ascending page is the highest card number"));
        }

        @Test
        @DisplayName("a page size outside its bounds is refused")
        void aPageSizeOutsideItsBoundsIsRefused() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.listForward(null, 0, null, null),
                            "a page holding no row is not a page"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> service.listForward(null, 101, null, null),
                            "a page size past the ceiling is refused"),
                    () -> verify(cardRepository, never()).findFirstPage(any(Limit.class)));
        }
    }
}

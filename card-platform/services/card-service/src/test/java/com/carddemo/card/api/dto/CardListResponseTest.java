package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Shape tests for {@link CardListResponse}, one page of the card list.
 *
 * <p>Each test reads the record declaration through reflection and compares it against literals
 * typed in this file. No test here sends a Representational State Transfer (REST) request, opens a
 * database connection or reads a file from disk. The Java Development Kit and Apache Maven are the
 * only tools a run needs.
 *
 * <p>A page declares three components: the rows, a flag stating whether a further page exists, and
 * the number the next request asks for. The card list program projects one row per card at
 * {@code app/cbl/COCRDLIC.cbl:L1165-L1171}. The row table holds seven rows at
 * {@code app/cbl/COCRDLIC.cbl:L250-L260}.
 *
 * <p>The program derives the next-page flag with a forward lookahead at
 * {@code app/cbl/COCRDLIC.cbl:L1191-L1216}. The page-full test at L1191 leaves the read loop at
 * L1192. The Customer Information Control System command {@code EXEC CICS READNEXT} at
 * L1197-L1205 then reads one row past the page. Conditions {@code DFHRESP(NORMAL)} at L1208 and
 * {@code DFHRESP(DUPREC)} at L1209 set {@code CA-NEXT-PAGE-EXISTS} at L1210-L1211. Condition
 * {@code DFHRESP(ENDFILE)} at L1215 sets {@code CA-NEXT-PAGE-NOT-EXISTS} at L1216.
 *
 * <p>The source keeps its browse position in {@code WS-CA-LAST-CARD-NUM PIC X(16)} at
 * {@code app/cbl/COCRDLIC.cbl:L231}, a full Primary Account Number (PAN). The read loop captures it
 * at L1194-L1195 and refreshes it at L1212-L1214. The page-down path reads it as the browse key at
 * {@code app/cbl/COCRDLIC.cbl:L488-L489}. That field lives in a communication area between screen
 * turns and never leaves the mainframe, so no component of this response carries it. The page number
 * carries the same paging intent and no card data. Several tests here hold that boundary: they
 * assert the response declares no free-text component, and that its paging component is a whole
 * number rather than a browse key.
 *
 * fixes the locator of the derived next-page flag at {@code app/cbl/COCRDLIC.cbl:L1191-L1216} and
 * supersedes the earlier citation of L1284-L1287. Lines L1284-L1286 compute
 * {@code WS-SCRN-COUNTER} as {@code WS-MAX-SCREEN-LINES + 1}, L1287 sets
 * {@code CA-NEXT-PAGE-EXISTS} with no condition, and L1288 sets {@code MORE-RECORDS-TO-READ}.
 * Those five lines preset the backward browse.
 *
 * at {@code app/cbl/COCRDLIC.cbl:L1287}.
 *
 * <p>Three fields stay out of {@link CardListResponse}: a total count, a row offset and a page size.
 * {@code WS-SCRN-COUNTER PIC S9(4) COMP} at
 * {@code app/cbl/COCRDLIC.cbl:L145} counts the rows of one screen and stops at seven. No paragraph
 * of the program counts the result set. The page size belongs to the query that builds a page
 * rather than to this payload, and comes from
 * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at {@code app/cbl/COCRDLIC.cbl:L176-L178}.
 *
 * <p>{@link CardSummaryTest} pins the row shape and the masked card number. No test here asserts a
 * page size of seven, an HTTP status code or an endpoint path. No test here reads a
 * {@code DFHRESP} condition or a screen field from {@code app/cpy/CVCRD01Y.cpy}.
 */
final class CardListResponseTest {

    /** Component that holds the rows of one page. */
    private static final String COMPONENT_CARDS = "cards";

    /** Component that states whether a further page exists. */
    private static final String COMPONENT_NEXT_PAGE_EXISTS = "nextPageExists";

    /** Component that holds the number the next request asks for. */
    private static final String COMPONENT_NEXT_PAGE_NUMBER = "nextPageNumber";

    /** The three component names, in the order {@link CardListResponse} declares them. */
    private static final List<String> EXPECTED_COMPONENT_NAMES =
            List.of(COMPONENT_CARDS, COMPONENT_NEXT_PAGE_EXISTS, COMPONENT_NEXT_PAGE_NUMBER);

    /** The number the next request asks for on a page that reports a further page. */
    private static final int SECOND_PAGE_NUMBER = CardListResponse.FIRST_PAGE_NUMBER + 1;

    /**
     * The masked card number of record one of {@code app/data/ASCII/carddata.txt}: twelve mask
     * characters and the last four digits of the card number at offset 1, width 16.
     */
    private static final String FIXTURE_MASKED_CARD_NUMBER = "************5740";

    /** Account identifier of record one of {@code app/data/ASCII/carddata.txt}, offset 17, width 11. */
    private static final String FIXTURE_ACCOUNT_ID = "00000000050";

    /** Active status of record one of {@code app/data/ASCII/carddata.txt}, offset 91, width 1. */
    private static final String FIXTURE_ACTIVE_STATUS = "Y";

    /** The count of components one page declares. */
    private static final int EXPECTED_COMPONENT_COUNT = 3;

    /** The single type argument a row list declares. */
    private static final int EXPECTED_TYPE_ARGUMENT_COUNT = 1;

    /**
     * Rows one page carries, from the seven-row table at
     * {@code app/cbl/COCRDLIC.cbl:L250-L260}.
     */
    private static final int EXPECTED_PAGE_SIZE = 7;

    /**
     * Leading characters of a masked card number a construction test supplies. One ordinal digit
     * completes it into the twelve masks and four digits {@link MaskedCardNumber} accepts, so no
     * test in this class holds a full Primary Account Number (PAN).
     */
    private static final String MASKED_CARD_NUMBER_PREFIX = "************574";

    /** Locator of the lookahead that derives the next-page flag. */
    private static final String LOOKAHEAD_LOCATOR =
            "Derived by the forward lookahead at app/cbl/COCRDLIC.cbl:L1191-L1216, which sets "
                    + "CA-NEXT-PAGE-EXISTS at L1210-L1211 and CA-NEXT-PAGE-NOT-EXISTS at L1216.";

    /**
     * Locator of the browse key the source keeps between screen turns, and which no component of
     * this response carries.
     */
    private static final String BROWSE_KEY_LOCATOR =
            "The source keeps its browse position in WS-CA-LAST-CARD-NUM PIC X(16), a full Primary "
                    + "Account Number, at app/cbl/COCRDLIC.cbl:L231. It is captured at "
                    + "app/cbl/COCRDLIC.cbl:L1194-L1195, refreshed at "
                    + "app/cbl/COCRDLIC.cbl:L1212-L1214 and read as the browse key at "
                    + "app/cbl/COCRDLIC.cbl:L488-L489. It stays in a communication area between "
                    + "screen turns. A page number carries the same paging intent and no card data.";

    /** Locator of the row projection and the row table. */
    private static final String ROW_LOCATOR =
            "Rows projected at app/cbl/COCRDLIC.cbl:L1165-L1171 into the table declared at "
                    + "app/cbl/COCRDLIC.cbl:L250-L260.";

    /** Locator of the screen row counter, which never counts the result set. */
    private static final String COUNTER_LOCATOR =
            "WS-SCRN-COUNTER PIC S9(4) COMP at app/cbl/COCRDLIC.cbl:L145 counts the rows of one "
                    + "screen and stops at seven. The lookahead at "
                    + "app/cbl/COCRDLIC.cbl:L1191-L1216 answers whether a further page exists.";

    /** Locator of the page size the source holds as a program constant. */
    private static final String PAGE_SIZE_LOCATOR =
            "WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7 at app/cbl/COCRDLIC.cbl:L176-L178 is a "
                    + "program constant, and no component of this payload carries it.";

    /** Lower-case name fragments that would expose a total count. */
    private static final List<String> TOTAL_COUNT_FRAGMENTS = List.of("total", "count");

    /**
     * Lower-case name fragments that would name card data or a browse key on a paging component.
     *
     * <p>The fragments cover the card number, its common abbreviations, the source field name and
     * the words a later contributor might reach for when reinstating a key-based browse marker. The
     * scan skips the row list, whose name legitimately carries the word {@code card}.
     */
    private static final List<String> BROWSE_KEY_FRAGMENTS = List.of(
            "cardnum",
            "cardnumber",
            "pan",
            "primaryaccount",
            "cursor",
            "browsekey",
            "lastcard",
            "lastkey",
            "token",
            "marker");

    /** Lower-case name fragments that would expose a row offset. */
    private static final List<String> ROW_OFFSET_FRAGMENTS =
            List.of("offset", "skip", "startat", "firstresult");

    /** Lower-case name fragments that would expose a page size. */
    private static final List<String> PAGE_SIZE_FRAGMENTS =
            List.of("size", "limit", "maxresult", "perpage", "screenlines");

    /** Types a total count, a page ordinal, a row offset or a page size would need. */
    private static final List<Class<?>> WHOLE_NUMBER_TYPES = List.of(
            short.class,
            int.class,
            long.class,
            Short.class,
            Integer.class,
            Long.class);

    /** JUnit builds one instance of this class per test method through this constructor. */
    CardListResponseTest() {
    }

    /**
     * Asserts that a page declares exactly three components.
     *
     * <p>A page carries the rows, the next-page flag and the next page number. {@link #ROW_LOCATOR}
     * covers the rows, {@link #LOOKAHEAD_LOCATOR} the flag and {@link #BROWSE_KEY_LOCATOR} the
     * paging position the source held as a card number.
     */
    @Test
    void pageDeclaresExactlyThreeComponents() {
        assertEquals(EXPECTED_COMPONENT_COUNT, components().length,
                "CardListResponse must declare exactly " + EXPECTED_COMPONENT_COUNT
                        + " components: " + EXPECTED_COMPONENT_NAMES + ". Declared components: "
                        + componentNames() + ". " + ROW_LOCATOR + " " + LOOKAHEAD_LOCATOR);
    }

    /**
     * Asserts the three component names, position by position.
     *
     * <p>A renamed or reordered component fails here with the expected name in the message. The
     * page number closes the page, and the source captures its own paging position once the page
     * fills, at {@code app/cbl/COCRDLIC.cbl:L1191-L1195}.
     */
    @Test
    void pageNamesThreeComponentsInDeclarationOrder() {
        List<String> declared = componentNames();

        assertEquals(EXPECTED_COMPONENT_NAMES.size(), declared.size(),
                "CardListResponse must declare " + EXPECTED_COMPONENT_NAMES
                        + " in that order. Declared components: " + declared + ". " + ROW_LOCATOR);

        for (int index = 0; index < EXPECTED_COMPONENT_NAMES.size(); index++) {
            assertEquals(EXPECTED_COMPONENT_NAMES.get(index), declared.get(index),
                    "Component at position " + index + " must be named "
                            + EXPECTED_COMPONENT_NAMES.get(index) + ". Declared components: "
                            + declared + ". " + BROWSE_KEY_LOCATOR);
        }
    }

    /**
     * Asserts the rows arrive as a {@link List} of {@link CardSummary}.
     *
     * <p>The check resolves the generic type argument, so a raw list or a list of another element
     * type fails. The forward browse fills one row per card at
     * {@code app/cbl/COCRDLIC.cbl:L1165-L1171}.
     */
    @Test
    void pageCarriesRowsOfTypeCardSummary() {
        RecordComponent rows = componentNamed(COMPONENT_CARDS);

        assertEquals(List.class, rows.getType(),
                "Component " + COMPONENT_CARDS + " must have type " + List.class.getName()
                        + ". Declared type: " + rows.getType().getName() + ". " + ROW_LOCATOR);

        ParameterizedType rowList = assertInstanceOf(ParameterizedType.class, rows.getGenericType(),
                "Component " + COMPONENT_CARDS + " must declare a type argument. A raw list "
                        + "carries no element type. " + ROW_LOCATOR);

        Type[] arguments = rowList.getActualTypeArguments();

        assertEquals(EXPECTED_TYPE_ARGUMENT_COUNT, arguments.length,
                "Component " + COMPONENT_CARDS + " must declare "
                        + EXPECTED_TYPE_ARGUMENT_COUNT + " type argument. Declared arguments: "
                        + List.of(arguments) + ". " + ROW_LOCATOR);

        assertEquals(CardSummary.class, arguments[0],
                "Component " + COMPONENT_CARDS + " must hold elements of type "
                        + CardSummary.class.getName() + ". Declared element type: " + arguments[0]
                        + ". " + ROW_LOCATOR);
    }

    /**
     * Asserts the next-page flag arrives as the {@code boolean} primitive.
     *
     * <p>The source holds the flag as one character, {@code WS-CA-NEXT-PAGE-IND PIC X(1)} at
     * {@code app/cbl/COCRDLIC.cbl:L242}, with condition names
     * {@code CA-NEXT-PAGE-NOT-EXISTS} at L243 and {@code CA-NEXT-PAGE-EXISTS} at L244. The
     * lookahead resolves one of the two on every page.
     */
    @Test
    void pageTypesTheNextPageFlagAsABooleanPrimitive() {
        RecordComponent flag = componentNamed(COMPONENT_NEXT_PAGE_EXISTS);

        assertEquals(boolean.class, flag.getType(),
                "Component " + COMPONENT_NEXT_PAGE_EXISTS + " must have type "
                        + boolean.class.getName() + ". Declared type: " + flag.getType().getName()
                        + ". " + LOOKAHEAD_LOCATOR);
    }

    /**
     * Asserts the next page number arrives as a nullable {@link Integer} and holds no value on the
     * final page.
     *
     * <p>{@link Integer} rather than {@code int} because a page that ends the browse names no
     * successor, and the primitive has no absent value. A whole number cannot hold the sixteen
     * characters of {@code WS-CA-LAST-CARD-NUM PIC X(16)} at {@code app/cbl/COCRDLIC.cbl:L231}, so
     * the type itself keeps a card number out of the paging component.
     */
    @Test
    void pageTypesTheNextPageNumberAsANullableInteger() {
        RecordComponent pageNumber = componentNamed(COMPONENT_NEXT_PAGE_NUMBER);

        assertEquals(Integer.class, pageNumber.getType(),
                "Component " + COMPONENT_NEXT_PAGE_NUMBER + " must have type "
                        + Integer.class.getName() + ". Declared type: "
                        + pageNumber.getType().getName() + ". " + BROWSE_KEY_LOCATOR);

        CardListResponse finalPage = new CardListResponse(List.of(), false, null);

        assertNull(finalPage.nextPageNumber(),
                "Accessor " + COMPONENT_NEXT_PAGE_NUMBER + " must return null on the final page. "
                        + BROWSE_KEY_LOCATOR);
    }

    /**
     * Asserts that no paging component names card data or a browse key, and that no component
     * declares a free-text type.
     *
     * <p>A {@link String} component is the shape a browse key would take, so its absence is the
     * structural guarantee. The row list is exempt from the name scan: it legitimately carries the
     * word {@code cards}, and {@link CardSummaryTest} pins its element to a masked card number.
     */
    @Test
    void pageDeclaresNoBrowseKeyAndNoFreeTextComponent() {
        for (RecordComponent component : components()) {
            assertFalse(String.class.equals(component.getType()),
                    "Component " + component.getName() + " declares type "
                            + String.class.getName() + ". A page carries "
                            + EXPECTED_COMPONENT_NAMES + " and no free-text component that could "
                            + "hold a card number. " + BROWSE_KEY_LOCATOR);
        }

        List<String> declared = componentNames();

        for (int index = 0; index < declared.size(); index++) {
            if (COMPONENT_CARDS.equals(declared.get(index))) {
                continue;
            }

            String folded = foldedComponentNames().get(index);

            for (String fragment : BROWSE_KEY_FRAGMENTS) {
                assertFalse(folded.contains(fragment),
                        "Component name '" + declared.get(index) + "' holds the fragment '"
                                + fragment + "', which names card data or a browse key. A page "
                                + "carries " + EXPECTED_COMPONENT_NAMES + ". " + BROWSE_KEY_LOCATOR);
            }
        }
    }

    /**
     * Asserts that a page reporting a further page names the number of that page, and that a final
     * page names none.
     *
     * <p>The two states come from the lookahead: {@code CA-NEXT-PAGE-EXISTS} at
     * {@code app/cbl/COCRDLIC.cbl:L1210-L1211} and {@code CA-NEXT-PAGE-NOT-EXISTS} at L1216. A
     * response that carried the flag and the number independently could report a further page and
     * leave the client with no way to ask for it.
     */
    @Test
    void pageKeepsItsFlagAndItsNumberInAgreement() {
        CardListResponse pageWithSuccessor =
                new CardListResponse(List.of(), true, SECOND_PAGE_NUMBER);
        CardListResponse finalPage = new CardListResponse(List.of(), false, null);

        assertEquals(SECOND_PAGE_NUMBER, pageWithSuccessor.nextPageNumber(),
                "A page that reports a further page must name the number of that page. "
                        + LOOKAHEAD_LOCATOR);
        assertNull(finalPage.nextPageNumber(),
                "A final page must name no page number. " + LOOKAHEAD_LOCATOR);

        assertThrows(IllegalArgumentException.class,
                () -> new CardListResponse(List.of(), true, null),
                "a page reported a further page and named no number for it");
        assertThrows(IllegalArgumentException.class,
                () -> new CardListResponse(List.of(), false, SECOND_PAGE_NUMBER),
                "a page reported no further page and named a number anyway");
        assertThrows(IllegalArgumentException.class,
                () -> new CardListResponse(List.of(), true, CardListResponse.FIRST_PAGE_NUMBER),
                "a page named itself as its own successor");
    }

    /**
     * Asserts the row list is copied on construction and cannot be modified afterwards, and that a
     * null row list becomes an empty list.
     *
     * <p>The source clears its row table before every browse at
     * {@code app/cbl/COCRDLIC.cbl:L1124}, so a page never shows rows of an earlier browse. A copied
     * list carries the same property: a caller that keeps its own list cannot change a page after
     * the fact.
     */
    @Test
    void pageCopiesItsRowListAndAcceptsNoRowsAtAll() {
        List<CardSummary> supplied = new ArrayList<>();
        supplied.add(new CardSummary(
                MaskedCardNumber.of(FIXTURE_MASKED_CARD_NUMBER),
                FIXTURE_ACCOUNT_ID,
                FIXTURE_ACTIVE_STATUS));

        CardListResponse page = new CardListResponse(supplied, false, null);
        supplied.clear();

        assertEquals(1, page.cards().size(),
                "A page must copy the row list it was given. " + ROW_LOCATOR);
        assertThrows(UnsupportedOperationException.class, () -> page.cards().add(null),
                "the row list of a page accepted a further row");
        assertEquals(List.of(), new CardListResponse(null, false, null).cards(),
                "A page built with no row list must carry an empty list. " + ROW_LOCATOR);
    }

    /**
     * Asserts that no component name exposes a total count.
     *
     * <p>The lookahead answers whether a further page exists and yields no size. A total would
     * need a full pass over the card file, which the browse never makes.
     */
    @Test
    void pageDeclaresNoTotalCount() {
        assertNoComponentNameMatches("a total count", TOTAL_COUNT_FRAGMENTS, COUNTER_LOCATOR);
    }

    /**
     * Asserts that no component name exposes a row offset.
     *
     * <p>The source moves its browse key into the read key at
     * {@code app/cbl/COCRDLIC.cbl:L488-L489} and starts the read from that key. The page number
     * names a page, not a row, so no row position enters the response.
     */
    @Test
    void pageDeclaresNoRowOffset() {
        assertNoComponentNameMatches("a row offset", ROW_OFFSET_FRAGMENTS, BROWSE_KEY_LOCATOR);
    }

    /**
     * Asserts that no component name exposes a page size.
     *
     * <p>The page-full test at {@code app/cbl/COCRDLIC.cbl:L1191} compares the screen counter
     * against the program constant at {@code app/cbl/COCRDLIC.cbl:L176-L178}, and no component of
     * this payload carries it.
     */
    @Test
    void pageDeclaresNoPageSize() {
        assertNoComponentNameMatches("a page size", PAGE_SIZE_FRAGMENTS, PAGE_SIZE_LOCATOR);
    }

    /**
     * Asserts that the next page number is the only whole-number component.
     *
     * <p>A total count, a row offset and a page size each need one of the six types in
     * {@link #WHOLE_NUMBER_TYPES}. The check gates the three omissions by type, so a component that
     * carries a bound under an unforeseen name still fails. The next page number is the one
     * permitted whole number, and it names a page rather than a bound on the rows.
     */
    @Test
    void pageDeclaresNoWholeNumberComponentBeyondTheNextPageNumber() {
        for (RecordComponent component : components()) {
            if (COMPONENT_NEXT_PAGE_NUMBER.equals(component.getName())) {
                continue;
            }

            Class<?> type = component.getType();

            assertFalse(WHOLE_NUMBER_TYPES.contains(type),
                    "Component " + component.getName() + " declares whole-number type "
                            + type.getName() + ". A page carries " + EXPECTED_COMPONENT_NAMES
                            + " and carries no bound on its rows. " + PAGE_SIZE_LOCATOR + " "
                            + COUNTER_LOCATOR);
        }
    }

    /**
     * Asserts that no component name holds any of the given fragments.
     *
     * @param subject   the field the fragments name, for the failure message
     * @param fragments the lower-case fragments no component name may hold
     * @param locator   the source locator, for the failure message
     */
    private static void assertNoComponentNameMatches(String subject, List<String> fragments,
            String locator) {
        List<String> declared = componentNames();

        for (String name : foldedComponentNames()) {
            for (String fragment : fragments) {
                assertFalse(name.contains(fragment),
                        "Component name '" + name + "' holds the fragment '" + fragment
                                + "', which names " + subject + ". A page carries "
                                + EXPECTED_COMPONENT_NAMES + ". Declared components: " + declared
                                + ". " + locator);
            }
        }
    }

    /**
     * Reads the record components of {@link CardListResponse}. The record check runs first, so a
     * class that stops being a record fails with a named assertion.
     *
     * @return the record components, in declaration order
     */
    private static RecordComponent[] components() {
        assertTrue(CardListResponse.class.isRecord(),
                "CardListResponse must be a record. Its " + EXPECTED_COMPONENT_COUNT
                        + " components carry one page of the card list. " + ROW_LOCATOR);
        return CardListResponse.class.getRecordComponents();
    }

    /**
     * Reads the component names of {@link CardListResponse}.
     *
     * @return the component names, in declaration order
     */
    private static List<String> componentNames() {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : components()) {
            names.add(component.getName());
        }
        return List.copyOf(names);
    }

    // Construction. Every test below builds a page and reads the values it holds.

    /**
     * Asserts that a {@code null} card list becomes an empty list, and that the page still carries
     * the flag and the page number it was handed.
     */
    @Test
    void aNullCardListBecomesAnEmptyList() {
        CardListResponse page = new CardListResponse(null, false, null);

        assertNotNull(page.cards(), "A null card list must become an empty list, not stay null.");
        assertTrue(page.cards().isEmpty(), "A null card list must become an empty list.");
        assertFalse(page.nextPageExists(), "The next-page flag must carry the value supplied.");
        assertNull(page.nextPageNumber(), "The page number must carry the value supplied.");
    }

    /**
     * Asserts that an empty card list stays empty and that a populated list keeps its rows in
     * order, at the page size the seven-row table at {@code app/cbl/COCRDLIC.cbl:L250-L260}
     * declares.
     */
    @Test
    void anEmptyListStaysEmptyAndAPopulatedListKeepsItsOrder() {
        assertTrue(new CardListResponse(List.of(), false, null).cards().isEmpty(),
                "An empty card list must stay empty.");

        List<CardSummary> rows = new ArrayList<>();
        for (int ordinal = 0; ordinal < EXPECTED_PAGE_SIZE; ordinal++) {
            rows.add(row(MASKED_CARD_NUMBER_PREFIX + ordinal));
        }

        CardListResponse page = new CardListResponse(rows, true, SECOND_PAGE_NUMBER);

        assertEquals(EXPECTED_PAGE_SIZE, page.cards().size(),
                "A page must carry every row it was handed.");
        for (int ordinal = 0; ordinal < EXPECTED_PAGE_SIZE; ordinal++) {
            assertEquals(MASKED_CARD_NUMBER_PREFIX + ordinal,
                    page.cards().get(ordinal).cardNumber().value(),
                    "Row " + ordinal + " must keep its position.");
        }
        assertTrue(page.nextPageExists(), "The next-page flag must carry the value supplied.");
        assertEquals(SECOND_PAGE_NUMBER, page.nextPageNumber(),
                "The page number must carry the value supplied.");
    }

    /**
     * Asserts that changing the source list after construction leaves the page unchanged, so the
     * constructor copied the rows rather than keeping the caller's list.
     */
    @Test
    void changingTheSourceListAfterConstructionLeavesThePageUnchanged() {
        List<CardSummary> source = new ArrayList<>();
        source.add(row(MASKED_CARD_NUMBER_PREFIX + 0));

        CardListResponse page = new CardListResponse(source, false, null);

        source.add(row(MASKED_CARD_NUMBER_PREFIX + 1));
        source.clear();

        assertEquals(1, page.cards().size(),
                "A page must not follow a change to the list it was handed.");
        assertEquals(MASKED_CARD_NUMBER_PREFIX + 0, page.cards().get(0).cardNumber().value(),
                "A page must keep the row it copied.");
    }

    /**
     * Asserts that the returned card list refuses every change, so a caller cannot add, remove or
     * replace a row after construction.
     */
    @Test
    void theReturnedCardListRefusesEveryChange() {
        CardListResponse page =
                new CardListResponse(List.of(row(MASKED_CARD_NUMBER_PREFIX + 0)), false, null);
        List<CardSummary> cards = page.cards();
        CardSummary replacement = row(MASKED_CARD_NUMBER_PREFIX + 1);

        assertThrows(UnsupportedOperationException.class, () -> cards.add(replacement),
                "The returned card list must refuse an addition.");
        assertThrows(UnsupportedOperationException.class, () -> cards.remove(0),
                "The returned card list must refuse a removal.");
        assertThrows(UnsupportedOperationException.class, () -> cards.set(0, replacement),
                "The returned card list must refuse a replacement.");
        assertThrows(UnsupportedOperationException.class, cards::clear,
                "The returned card list must refuse a clear.");
    }

    /**
     * Asserts that a {@code null} row raises {@link NullPointerException}, whichever position it
     * occupies.
     */
    @Test
    void aNullRowIsRefused() {
        List<CardSummary> leadingNull = new ArrayList<>();
        leadingNull.add(null);

        assertThrows(NullPointerException.class,
                () -> new CardListResponse(leadingNull, false, null),
                "A null row must be refused.");

        List<CardSummary> trailingNull = new ArrayList<>();
        trailingNull.add(row(MASKED_CARD_NUMBER_PREFIX + 0));
        trailingNull.add(null);

        assertThrows(NullPointerException.class,
                () -> new CardListResponse(trailingNull, false, null),
                "A null row must be refused wherever it sits.");
    }

    /**
     * Asserts that two pages built from equal values are equal, and that a page built from
     * different values is not. The flag and the page number agree in every page below, because
     * the constructor refuses a page whose flag and page number disagree.
     */
    @Test
    void twoPagesBuiltFromEqualValuesAreEqual() {
        List<CardSummary> rows = List.of(row(MASKED_CARD_NUMBER_PREFIX + 0));

        CardListResponse first = new CardListResponse(rows, true, SECOND_PAGE_NUMBER);
        CardListResponse second =
                new CardListResponse(new ArrayList<>(rows), true, SECOND_PAGE_NUMBER);

        assertEquals(first, second, "Two pages built from equal values must be equal.");
        assertEquals(first.hashCode(), second.hashCode(),
                "Two equal pages must carry one hash code.");
        assertNotEquals(first, new CardListResponse(rows, false, null),
                "A final page must not equal a page that has a successor.");
        assertNotEquals(first, new CardListResponse(rows, true, SECOND_PAGE_NUMBER + 1),
                "A page with a different next page number must not be equal.");
    }

    /**
     * Builds one card row for a construction test.
     *
     * <p>The card number is a masked form, so no assertion description can carry a Primary Account
     * Number (PAN). The account identifier width comes from
     * {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6} and the status width from
     * {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10}.</p>
     *
     * @param maskedCardNumber the masked card number the row carries
     * @return the row
     */
    private static CardSummary row(String maskedCardNumber) {
        return new CardSummary(MaskedCardNumber.of(maskedCardNumber), FIXTURE_ACCOUNT_ID,
                FIXTURE_ACTIVE_STATUS);
    }

    /**
     * Reads the component names, folds each one to lower case and drops every character outside
     * {@code a-z0-9}. The fold makes a fragment search exact.
     *
     * @return the folded component names, in declaration order
     */
    private static List<String> foldedComponentNames() {
        List<String> folded = new ArrayList<>();
        for (String name : componentNames()) {
            folded.add(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
        }
        return List.copyOf(folded);
    }

    /**
     * Reads one component of {@link CardListResponse} by name.
     *
     * @param componentName the component name to look up
     * @return the component that carries the name
     */
    private static RecordComponent componentNamed(String componentName) {
        for (RecordComponent component : components()) {
            if (component.getName().equals(componentName)) {
                return component;
            }
        }
        throw new AssertionError("CardListResponse must declare a component named " + componentName
                + ". Declared components: " + componentNames() + ". " + ROW_LOCATOR);
    }
}

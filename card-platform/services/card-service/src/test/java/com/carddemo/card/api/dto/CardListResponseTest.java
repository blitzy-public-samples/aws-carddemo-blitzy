package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * the cursor the next request supplies. The card list program projects one row per card at
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
 * <p>The cursor comes from {@code WS-CA-LAST-CARD-NUM PIC X(16)} at
 * {@code app/cbl/COCRDLIC.cbl:L231}. The read loop captures it at L1194-L1195 and refreshes it at
 * L1212-L1214. The page-down path reads it as the browse key at
 * {@code app/cbl/COCRDLIC.cbl:L488-L489}.
 *
 * <p>{@code card-platform/docs/decision-log.md} carries one entry these tests depend on. The entry
 * fixes the locator of the derived next-page flag at {@code app/cbl/COCRDLIC.cbl:L1191-L1216} and
 * supersedes the earlier citation of L1284-L1287. Lines L1284-L1286 compute
 * {@code WS-SCRN-COUNTER} as {@code WS-MAX-SCREEN-LINES + 1}, L1287 sets
 * {@code CA-NEXT-PAGE-EXISTS} with no condition, and L1288 sets {@code MORE-RECORDS-TO-READ}.
 * Those five lines preset the backward browse.
 *
 * <p>{@code card-platform/docs/business-rule-flags.md} records the unconditional next-page preset
 * at {@code app/cbl/COCRDLIC.cbl:L1287}.
 *
 * <p>Four fields stay out of {@link CardListResponse}: a total count, a page ordinal, a row offset
 * and a page size. {@code card-platform/docs/traceability-matrix.md} records the four omissions.
 * {@code WS-SCRN-COUNTER PIC S9(4) COMP} at
 * {@code app/cbl/COCRDLIC.cbl:L145} counts the rows of one screen and stops at seven. No paragraph
 * of the program counts the result set. The page size sits on {@code CardQueryService}, from
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

    /** Component that holds the cursor the next request supplies. */
    private static final String COMPONENT_NEXT_CURSOR = "nextCursor";

    /** The three component names, in the order {@link CardListResponse} declares them. */
    private static final List<String> EXPECTED_COMPONENT_NAMES =
            List.of(COMPONENT_CARDS, COMPONENT_NEXT_PAGE_EXISTS, COMPONENT_NEXT_CURSOR);

    /** The count of components one page declares. */
    private static final int EXPECTED_COMPONENT_COUNT = 3;

    /** The single type argument a row list declares. */
    private static final int EXPECTED_TYPE_ARGUMENT_COUNT = 1;

    /** Locator of the lookahead that derives the next-page flag. */
    private static final String LOOKAHEAD_LOCATOR =
            "Derived by the forward lookahead at app/cbl/COCRDLIC.cbl:L1191-L1216, which sets "
                    + "CA-NEXT-PAGE-EXISTS at L1210-L1211 and CA-NEXT-PAGE-NOT-EXISTS at L1216.";

    /** Locator of the cursor field, its capture and its refresh. */
    private static final String CURSOR_LOCATOR =
            "Source WS-CA-LAST-CARD-NUM PIC X(16) at app/cbl/COCRDLIC.cbl:L231, captured at "
                    + "app/cbl/COCRDLIC.cbl:L1194-L1195, refreshed at "
                    + "app/cbl/COCRDLIC.cbl:L1212-L1214 and read as the browse key at "
                    + "app/cbl/COCRDLIC.cbl:L488-L489.";

    /** Locator of the row projection and the row table. */
    private static final String ROW_LOCATOR =
            "Rows projected at app/cbl/COCRDLIC.cbl:L1165-L1171 into the table declared at "
                    + "app/cbl/COCRDLIC.cbl:L250-L260.";

    /** Locator of the screen row counter, which never counts the result set. */
    private static final String COUNTER_LOCATOR =
            "WS-SCRN-COUNTER PIC S9(4) COMP at app/cbl/COCRDLIC.cbl:L145 counts the rows of one "
                    + "screen and stops at seven. The lookahead at "
                    + "app/cbl/COCRDLIC.cbl:L1191-L1216 answers whether a further page exists.";

    /** Locator of the page size, which the query service holds as a constant. */
    private static final String PAGE_SIZE_LOCATOR =
            "WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7 at app/cbl/COCRDLIC.cbl:L176-L178 is a "
                    + "program constant. CardQueryService holds the page size.";

    /** Lower-case name fragments that would expose a total count. */
    private static final List<String> TOTAL_COUNT_FRAGMENTS = List.of("total", "count");

    /** Lower-case name fragments that would expose a page ordinal. */
    private static final List<String> PAGE_ORDINAL_FRAGMENTS =
            List.of("pagenum", "pageno", "pageindex", "pageordinal");

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
     * <p>A page carries the rows, the next-page flag and the next cursor. {@link #ROW_LOCATOR}
     * covers the rows, {@link #LOOKAHEAD_LOCATOR} the flag and {@link #CURSOR_LOCATOR} the cursor.
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
     * cursor closes the page, and the source captures it once the page fills, at
     * {@code app/cbl/COCRDLIC.cbl:L1191-L1195}.
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
                            + declared + ". " + CURSOR_LOCATOR);
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
     * Asserts the cursor arrives as a {@link String} and holds no value on the final page.
     *
     * <p>The cursor carries the sixteen characters of {@code WS-CA-LAST-CARD-NUM PIC X(16)} at
     * {@code app/cbl/COCRDLIC.cbl:L231}. A page that ends the browse leaves the cursor empty, so
     * the accessor returns {@code null}.
     */
    @Test
    void pageTypesTheNextCursorAsANullableString() {
        RecordComponent cursor = componentNamed(COMPONENT_NEXT_CURSOR);

        assertEquals(String.class, cursor.getType(),
                "Component " + COMPONENT_NEXT_CURSOR + " must have type "
                        + String.class.getName() + ". Declared type: "
                        + cursor.getType().getName() + ". " + CURSOR_LOCATOR);

        CardListResponse finalPage = new CardListResponse(List.of(), false, null);

        assertNull(finalPage.nextCursor(),
                "Accessor " + COMPONENT_NEXT_CURSOR + " must return null on the final page. "
                        + CURSOR_LOCATOR);
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
     * Asserts that no component name exposes a page ordinal.
     *
     * <p>A page identifies its successor with the cursor at
     * {@code app/cbl/COCRDLIC.cbl:L1194-L1195}. The screen ordinal
     * {@code WS-CA-SCREEN-NUM PIC 9(1)} at {@code app/cbl/COCRDLIC.cbl:L237} advances screen
     * navigation state at L492, which carries no target component.
     */
    @Test
    void pageDeclaresNoPageOrdinal() {
        assertNoComponentNameMatches("a page ordinal", PAGE_ORDINAL_FRAGMENTS, CURSOR_LOCATOR);
    }

    /**
     * Asserts that no component name exposes a row offset.
     *
     * <p>The page-down path moves the cursor into the browse key at
     * {@code app/cbl/COCRDLIC.cbl:L488-L489} and starts the read from that key. No ordinal
     * position enters the browse.
     */
    @Test
    void pageDeclaresNoRowOffset() {
        assertNoComponentNameMatches("a row offset", ROW_OFFSET_FRAGMENTS, CURSOR_LOCATOR);
    }

    /**
     * Asserts that no component name exposes a page size.
     *
     * <p>The page-full test at {@code app/cbl/COCRDLIC.cbl:L1191} compares the screen counter
     * against the program constant at {@code app/cbl/COCRDLIC.cbl:L176-L178}.
     * {@code CardQueryService} holds the constant, and no payload carries it.
     */
    @Test
    void pageDeclaresNoPageSize() {
        assertNoComponentNameMatches("a page size", PAGE_SIZE_FRAGMENTS, PAGE_SIZE_LOCATOR);
    }

    /**
     * Asserts that no component declares a whole-number type.
     *
     * <p>A total count, a page ordinal, a row offset and a page size each need one of the six
     * types in {@link #WHOLE_NUMBER_TYPES}. The check gates the four omissions by type, so a
     * component that carries a bound under an unforeseen name still fails.
     */
    @Test
    void pageDeclaresNoWholeNumberComponentThatCouldBoundTheRowList() {
        for (RecordComponent component : components()) {
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

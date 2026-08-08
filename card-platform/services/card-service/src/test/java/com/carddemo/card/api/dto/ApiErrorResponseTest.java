package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.card.api.CardController;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Shape tests for {@link ApiErrorResponse}.
 *
 * <p>Each test reads the record declaration through reflection and compares it against literals
 * typed in this file. No test here sends a Representational State Transfer request, opens a
 * database connection, or reads a file from disk. A run needs the Java Development Kit and Apache
 * Maven and nothing else.
 *
 * <p>The record declares three components: the Hypertext Transfer Protocol status code, one
 * message, and the failing route template. The message component carries one working-storage field,
 * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}.
 * {@code app/cbl/COCRDSLC.cbl} declares the counterpart field at L134, and
 * {@code app/cpy/CVCRD01Y.cpy} declares {@code CCARD-RETURN-MSG PIC X(75)} at L29 with its off
 * condition name {@code CCARD-RETURN-MSG-OFF VALUE LOW-VALUES} at L30. One field holds one message.
 *
 * <p>The card update program keeps the first message of a pass. Line 174 declares the blank-state
 * condition name {@code 88 WS-RETURN-MSG-OFF VALUE SPACES}, and line 384 sets it at the start of
 * the pass. Fifteen {@code IF WS-RETURN-MSG-OFF} guards then wrap the writes, at lines 730, 743,
 * 773, 787, 816, 833, 855, 868, 888, 903, 921, 939, 1399, 1404 and 1445. The condition name occurs
 * seventeen times in the program, and the L174 declaration plus the L384 reset account for the two
 * occurrences that guard nothing.
 *
 * <p>One write sits outside those fifteen guards. Line 1488 reads
 * {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)}, line 1489 reads {@code CONTINUE}, line 1490 is a
 * bare {@code ELSE}, line 1491 reads {@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE}, and line 1492
 * closes the test. That write carries no {@code IF WS-RETURN-MSG-OFF} wrapper and overwrites a
 * message the pass already set. Line 995 reads {@code LOCKED-BUT-UPDATE-FAILED} inside the
 * {@code EVALUATE TRUE} that opens at line 992.
 *
 * <p>Three condition names under {@code WS-RETURN-MSG} are declared and never set:
 * {@code SEARCHED-ACCT-ZEROES}, {@code SEARCHED-ACCT-NOT-NUMERIC} and
 * {@code SEARCHED-CARD-NOT-NUMERIC} at {@code app/cbl/COCRDUPC.cbl:L189-L194}.
 * {@code CardValidationMessagesTest} holds the text inventory.
 *
 * <p>Mapping a source outcome onto a status code is orchestration and sits outside this record,
 * so no test in this file asserts that mapping.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
final class ApiErrorResponseTest {

    /** The component count the error payload declares. */
    private static final int EXPECTED_COMPONENT_COUNT = 3;

    /** The name of the component that carries the message. */
    private static final String MESSAGE_COMPONENT_NAME = "message";

    /** The name of the component that carries the failing route template. */
    private static final String ROUTE_COMPONENT_NAME = "route";

    /**
     * The three component names, in record declaration order.
     *
     * <p>{@code ApiErrorResponse} declares {@code status}, {@code message} and {@code route}.
     */
    private static final List<String> EXPECTED_COMPONENT_NAMES =
            List.of("status", MESSAGE_COMPONENT_NAME, ROUTE_COMPONENT_NAME);

    /**
     * The component types, aligned position for position with
     * {@link #EXPECTED_COMPONENT_NAMES}.
     */
    private static final List<Class<?>> EXPECTED_COMPONENT_TYPES =
            List.of(int.class, String.class, String.class);

    /** What each component carries, aligned with {@link #EXPECTED_COMPONENT_NAMES}. */
    private static final List<String> COMPONENT_ROLE_CITATIONS = List.of(
            "the Hypertext Transfer Protocol status code of the failing response",
            "the first failing message, carrying WS-RETURN-MSG PIC X(75) at"
                    + " app/cbl/COCRDUPC.cbl:L173 and its counterpart at app/cbl/COCRDSLC.cbl:L134",
            "the route template of the endpoint that produced the failing response");

    /** Width of {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}. */
    private static final int MESSAGE_FIELD_WIDTH = 75;

    /**
     * Container types that would turn the one message into a list of field errors.
     *
     * <p>{@code Collection} covers {@code List} and {@code Set}. {@code Iterable} covers a type
     * that supplies elements without implementing {@code Collection}.
     */
    private static final List<Class<?>> BANNED_CONTAINER_TYPES =
            List.of(Collection.class, Iterable.class, Map.class);

    /**
     * Lower-case name fragments that would name a violation list, a detail list or a sub-error
     * list.
     */
    private static final List<String> BANNED_VIOLATION_FRAGMENTS = List.of(
            "violation",
            "errors",
            "detail",
            "suberror",
            "sub_error",
            "constraint");

    /** Lower-case name fragments that would name a field-to-message map. */
    private static final List<String> BANNED_FIELD_MAP_FRAGMENTS = List.of(
            "field",
            "messages",
            "bindingresult",
            "issue",
            "problem",
            "reasons");

    /**
     * Text of condition name {@code LOCKED-BUT-UPDATE-FAILED}, the literal at
     * {@code app/cbl/COCRDUPC.cbl:L210}.
     */
    private static final String UPDATE_FAILED_TEXT = "Update of record failed";

    /** Measured length of {@link #UPDATE_FAILED_TEXT}. */
    private static final int UPDATE_FAILED_TEXT_LENGTH = 23;

    /**
     * Text of condition name {@code COULD-NOT-LOCK-FOR-UPDATE}, the literal at
     * {@code app/cbl/COCRDUPC.cbl:L206}, set at line 1446 under the guard on line 1445.
     */
    private static final String COULD_NOT_LOCK_TEXT = "Could not lock record for update";

    /** Measured length of {@link #COULD_NOT_LOCK_TEXT}. */
    private static final int COULD_NOT_LOCK_TEXT_LENGTH = 32;

    /** The status code the payload under test carries. */
    private static final int SUPPLIED_STATUS = 503;

    /** The route template the payload under test carries. */
    private static final String SUPPLIED_ROUTE = "/cards";

    /**
     * Route template of the two endpoints that name one card. The card number is a path variable of
     * both, and the template carries its brace-delimited name in place of any value, so no full
     * Primary Account Number reaches a response body or a log line built from one.
     */
    private static final String CARD_NUMBER_ROUTE = "/cards/{cardToken}";

    ApiErrorResponseTest() {
    }

    // Shape of the record: three components, one of them the message.

    /**
     * The error payload declares exactly three components.
     *
     * <p>The three carry the Hypertext Transfer Protocol status code, the one message of
     * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}, and the route template.
     */
    @Test
    void errorResponseDeclaresExactlyThreeComponents() {
        assertEquals(EXPECTED_COMPONENT_COUNT, components().length,
                "ApiErrorResponse must declare exactly " + EXPECTED_COMPONENT_COUNT
                        + " components: a status code, the one message of WS-RETURN-MSG PIC X(75)"
                        + " at app/cbl/COCRDUPC.cbl:L173, and the route template."
                        + " Declared components: " + componentNames());
    }

    @Test
    void errorResponseNamesThreeComponentsInDeclarationOrder() {
        List<String> declared = componentNames();
        assertEquals(EXPECTED_COMPONENT_NAMES.size(), declared.size(),
                "ApiErrorResponse must declare " + EXPECTED_COMPONENT_NAMES
                        + " in that order. Declared components: " + declared);

        for (int index = 0; index < EXPECTED_COMPONENT_NAMES.size(); index++) {
            assertEquals(EXPECTED_COMPONENT_NAMES.get(index), declared.get(index),
                    "Component at position " + index + " must be named "
                            + EXPECTED_COMPONENT_NAMES.get(index) + ", which carries "
                            + COMPONENT_ROLE_CITATIONS.get(index) + ".");
        }
    }

    /**
     * The three component types, position by position.
     *
     * <p>The status arrives as an {@code int}, and the message and the route arrive as a
     * {@link String}. The message type matches the alphanumeric {@code PIC X(75)} of
     * {@code app/cbl/COCRDUPC.cbl:L173}.
     */
    @Test
    void errorResponseTypesThreeComponentsAsTheRecordDeclaresThem() {
        RecordComponent[] declared = components();
        assertEquals(EXPECTED_COMPONENT_TYPES.size(), declared.length,
                "ApiErrorResponse must declare " + EXPECTED_COMPONENT_TYPES.size()
                        + " components before a type check runs. Declared components: "
                        + componentNames());

        for (int index = 0; index < EXPECTED_COMPONENT_TYPES.size(); index++) {
            assertEquals(EXPECTED_COMPONENT_TYPES.get(index), declared[index].getType(),
                    "Component " + declared[index].getName() + " must have type "
                            + EXPECTED_COMPONENT_TYPES.get(index).getName() + ", which carries "
                            + COMPONENT_ROLE_CITATIONS.get(index) + ".");
        }
    }

    /**
     * The message component holds one scalar {@link String}.
     *
     * <p>The check covers the erased type and the generic type. A generic type that resolves to a
     * parameterized type or to a generic array would hold several messages, and
     * {@code app/cbl/COCRDUPC.cbl:L173} declares one field of 75 alphanumeric characters.
     */
    @Test
    void messageComponentHoldsOneScalarString() {
        RecordComponent message = messageComponent();

        assertAll("the message component of ApiErrorResponse",
                () -> assertEquals(String.class, message.getType(),
                        "Component " + MESSAGE_COMPONENT_NAME + " must have type"
                                + " java.lang.String, matching WS-RETURN-MSG PIC X(75) at"
                                + " app/cbl/COCRDUPC.cbl:L173."),
                () -> assertFalse(message.getType().isArray(),
                        "Component " + MESSAGE_COMPONENT_NAME + " must not be an array."
                                + " WS-RETURN-MSG PIC X(75) at app/cbl/COCRDUPC.cbl:L173 holds one"
                                + " message."),
                () -> assertFalse(message.getGenericType() instanceof ParameterizedType,
                        "Component " + MESSAGE_COMPONENT_NAME + " must not carry a type argument."
                                + " Its generic type reads " + message.getGenericType()
                                        .getTypeName()
                                + " and WS-RETURN-MSG PIC X(75) at app/cbl/COCRDUPC.cbl:L173 holds"
                                + " one message."),
                () -> assertFalse(message.getGenericType() instanceof GenericArrayType,
                        "Component " + MESSAGE_COMPONENT_NAME + " must not be a generic array."
                                + " Its generic type reads " + message.getGenericType()
                                        .getTypeName() + "."),
                () -> assertEquals(String.class, message.getGenericType(),
                        "The generic type of component " + MESSAGE_COMPONENT_NAME + " must read"
                                + " java.lang.String."));
    }

    // Absences: shapes the payload does not have.

    /**
     * No component holds a collection, an array or a map.
     *
     * <p>The check runs over every component and tests the erased type and the generic type. The
     * card update program reports through one field, {@code WS-RETURN-MSG PIC X(75)} at
     * {@code app/cbl/COCRDUPC.cbl:L173}.
     */
    @Test
    void errorResponseDeclaresNoFieldErrorCollection() {
        for (RecordComponent component : components()) {
            Class<?> type = component.getType();

            assertFalse(type.isArray(),
                    "Component " + component.getName() + " holds an array of "
                            + type.getComponentType() + ". WS-RETURN-MSG PIC X(75) at"
                            + " app/cbl/COCRDUPC.cbl:L173 holds one message.");
            assertFalse(component.getGenericType() instanceof GenericArrayType,
                    "Component " + component.getName() + " holds a generic array, reading "
                            + component.getGenericType().getTypeName() + ".");
            assertFalse(component.getGenericType() instanceof ParameterizedType,
                    "Component " + component.getName() + " carries a type argument, reading "
                            + component.getGenericType().getTypeName()
                            + ". WS-RETURN-MSG PIC X(75) at app/cbl/COCRDUPC.cbl:L173 holds one"
                            + " message.");

            for (Class<?> banned : BANNED_CONTAINER_TYPES) {
                assertFalse(banned.isAssignableFrom(type),
                        "Component " + component.getName() + " has type " + type.getName()
                                + ", which is assignable to " + banned.getName()
                                + ". WS-RETURN-MSG PIC X(75) at app/cbl/COCRDUPC.cbl:L173 holds one"
                                + " message.");
            }
        }
    }

    @Test
    void errorResponseDeclaresNoViolationOrDetailComponent() {
        for (String name : lowerCaseComponentNames()) {
            for (String fragment : BANNED_VIOLATION_FRAGMENTS) {
                assertFalse(name.contains(fragment),
                        "Component " + name + " matches the fragment '" + fragment
                                + "', which names a violation list. WS-RETURN-MSG PIC X(75) at"
                                + " app/cbl/COCRDUPC.cbl:L173 holds one message, and the fifteen"
                                + " IF WS-RETURN-MSG-OFF guards keep the first one.");
            }
        }
    }

    /**
     * No component name points at a field-to-message map.
     *
     * <p>The scan covers the per-field map fragments, including the plural {@code messages}.
     * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173} holds text and carries
     * no field identifier beside it.
     */
    @Test
    void errorResponseDeclaresNoPerFieldMessageMap() {
        for (String name : lowerCaseComponentNames()) {
            for (String fragment : BANNED_FIELD_MAP_FRAGMENTS) {
                assertFalse(name.contains(fragment),
                        "Component " + name + " matches the fragment '" + fragment
                                + "', which names a field to message map. WS-RETURN-MSG PIC X(75)"
                                + " at app/cbl/COCRDUPC.cbl:L173 holds one message.");
            }
        }
    }

    /**
     * Exactly one component carries a message, and that the component is a scalar
     * {@link String}.
     *
     * <p>The count runs over the component names and the type check runs on the one match, so
     * neither a renamed collection nor a second scalar message passes. Line 384 of
     * {@code app/cbl/COCRDUPC.cbl} blanks the one field at the start of a pass.
     */
    @Test
    void errorResponseCarriesExactlyOneMessageBearingComponent() {
        List<String> messageBearing = new ArrayList<>();
        for (RecordComponent component : components()) {
            if (component.getName().toLowerCase(Locale.ROOT).contains(MESSAGE_COMPONENT_NAME)) {
                messageBearing.add(component.getName());
            }
        }

        assertEquals(1, messageBearing.size(),
                "ApiErrorResponse must declare one message bearing component."
                        + " WS-RETURN-MSG PIC X(75) at app/cbl/COCRDUPC.cbl:L173 holds one message,"
                        + " and line 384 blanks it at the start of a pass. Message bearing"
                        + " components: " + messageBearing);
        assertEquals(MESSAGE_COMPONENT_NAME, messageBearing.get(0),
                "The one message bearing component must be named " + MESSAGE_COMPONENT_NAME + ".");
        assertEquals(String.class, messageComponent().getType(),
                "The one message bearing component must have type java.lang.String, matching the"
                        + " alphanumeric WS-RETURN-MSG PIC X(75) at app/cbl/COCRDUPC.cbl:L173.");
    }

    // One payload, one message, compared character for character.

    /**
     * A payload carrying the text of {@code LOCKED-BUT-UPDATE-FAILED} keeps it
     * character for character.
     *
     * <p>The literal sits at {@code app/cbl/COCRDUPC.cbl:L210} and line 1491 sets the condition
     * name. The second assertion compares the typed literal against
     * {@link CardValidationMessages#LOCKED_BUT_UPDATE_FAILED}, so a changed character in either
     * place fails here. The status and the route assertions read back the two values the caller
     * supplied.
     */
    @Test
    void payloadCarriesTheUpdateFailedTextFromLine210() {
        ApiErrorResponse response =
                new ApiErrorResponse(SUPPLIED_STATUS, UPDATE_FAILED_TEXT, SUPPLIED_ROUTE);

        assertAll("payload carrying the text of app/cbl/COCRDUPC.cbl:L210",
                () -> assertEquals(UPDATE_FAILED_TEXT, response.message(),
                        "app/cbl/COCRDUPC.cbl:L210 reads: " + UPDATE_FAILED_TEXT),
                () -> assertEquals(CardValidationMessages.LOCKED_BUT_UPDATE_FAILED,
                        response.message(),
                        "The payload message must match"
                                + " CardValidationMessages.LOCKED_BUT_UPDATE_FAILED, which holds"
                                + " the literal of app/cbl/COCRDUPC.cbl:L210."),
                () -> assertEquals(UPDATE_FAILED_TEXT_LENGTH, response.message().length(),
                        "The literal of app/cbl/COCRDUPC.cbl:L210 measures "
                                + UPDATE_FAILED_TEXT_LENGTH + " characters."),
                () -> assertTrue(response.message().length() <= MESSAGE_FIELD_WIDTH,
                        "WS-RETURN-MSG at app/cbl/COCRDUPC.cbl:L173 holds "
                                + MESSAGE_FIELD_WIDTH + " characters and the payload message holds "
                                + response.message().length() + "."),
                () -> assertEquals(SUPPLIED_STATUS, response.status(),
                        "The payload must read back the status code its caller supplied, "
                                + SUPPLIED_STATUS + "."),
                () -> assertEquals(SUPPLIED_ROUTE, response.route(),
                        "The payload must read back the route template its caller supplied, "
                                + SUPPLIED_ROUTE + "."));
    }

    /**
     * Two texts the payload carries fit the field they come from.
     *
     * <p>{@code WS-RETURN-MSG} holds 75 characters at {@code app/cbl/COCRDUPC.cbl:L173}. The text
     * of {@code LOCKED-BUT-UPDATE-FAILED} at L210 measures 23 characters, and the text of
     * {@code COULD-NOT-LOCK-FOR-UPDATE} at L206 measures 32. Each assertion also compares the
     * typed literal against the matching constant.
     */
    @Test
    void payloadTextsFitTheSeventyFiveCharacterField() {
        assertAll("texts of app/cbl/COCRDUPC.cbl that the payload carries",
                () -> assertEquals(UPDATE_FAILED_TEXT,
                        CardValidationMessages.LOCKED_BUT_UPDATE_FAILED,
                        "app/cbl/COCRDUPC.cbl:L210 reads: " + UPDATE_FAILED_TEXT),
                () -> assertEquals(UPDATE_FAILED_TEXT_LENGTH, UPDATE_FAILED_TEXT.length(),
                        "The literal of app/cbl/COCRDUPC.cbl:L210 measures "
                                + UPDATE_FAILED_TEXT_LENGTH + " characters."),
                () -> assertTrue(UPDATE_FAILED_TEXT.length() <= MESSAGE_FIELD_WIDTH,
                        "WS-RETURN-MSG at app/cbl/COCRDUPC.cbl:L173 holds "
                                + MESSAGE_FIELD_WIDTH + " characters."),
                () -> assertEquals(COULD_NOT_LOCK_TEXT,
                        CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE,
                        "app/cbl/COCRDUPC.cbl:L206 reads: " + COULD_NOT_LOCK_TEXT),
                () -> assertEquals(COULD_NOT_LOCK_TEXT_LENGTH, COULD_NOT_LOCK_TEXT.length(),
                        "The literal of app/cbl/COCRDUPC.cbl:L206 measures "
                                + COULD_NOT_LOCK_TEXT_LENGTH + " characters."),
                () -> assertTrue(COULD_NOT_LOCK_TEXT.length() <= MESSAGE_FIELD_WIDTH,
                        "WS-RETURN-MSG at app/cbl/COCRDUPC.cbl:L173 holds "
                                + MESSAGE_FIELD_WIDTH + " characters."));
    }

    @Test
    void errorResponseAndItsComponentsCarryNoAnnotation() {
        assertEquals(0, ApiErrorResponse.class.getAnnotations().length,
                "ApiErrorResponse carries no annotation.");
        assertEquals(0, ApiErrorResponse.class.getDeclaredAnnotations().length,
                "ApiErrorResponse declares no annotation.");

        for (RecordComponent component : components()) {
            assertEquals(0, component.getAnnotations().length,
                    "Component " + component.getName() + " carries no annotation.");
        }
    }

    /**
     * The route component holds a template and refuses a resolved path.
     *
     * <p>The two endpoints that name one card take the card number as a path variable, so a
     * resolved path would place a full Primary Account Number in the response body. The card record
     * declares {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}, and
     * {@code CARD-ACCT-ID PIC 9(11)} at L6 is eleven digits.
     */
    @Test
    void errorResponseRouteCarriesATemplateAndRefusesAResolvedPath() {
        ApiErrorResponse templated =
                new ApiErrorResponse(SUPPLIED_STATUS, UPDATE_FAILED_TEXT, CARD_NUMBER_ROUTE);
        assertEquals(CARD_NUMBER_ROUTE, templated.route(),
                "A route template is valid. The card number stays a path variable name.");
        assertFalse(templated.route().matches(".*[0-9]{5,}.*"),
                "A route template holds no run of five digits or more.");
        assertTrue(templated.route().contains("{" + CardController.CARD_TOKEN_VARIABLE + "}"),
                "The template of the two routes that name one card holds the variable name.");
        assertFalse(templated.route().matches(".*[0-9].*"),
                "A route template of this service holds no digit, so none can resolve to a "
                        + "card number or an account identifier.");

        for (String resolved : List.of("/cards/" + "0".repeat(12) + "5740", "/cards/00000000050",
                "/accounts/00000000050/cards")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ApiErrorResponse(SUPPLIED_STATUS, UPDATE_FAILED_TEXT, resolved),
                    "Route '" + resolved + "' holds a resolved identifier, and the response body "
                            + "must not carry one.");
        }
    }

    // Reflection helpers.

    private static RecordComponent[] components() {
        assertTrue(ApiErrorResponse.class.isRecord(),
                "ApiErrorResponse must be a record. Its three components carry a status code, the"
                        + " one message of WS-RETURN-MSG PIC X(75) at app/cbl/COCRDUPC.cbl:L173,"
                        + " and the route template.");
        return ApiErrorResponse.class.getRecordComponents();
    }

    private static List<String> componentNames() {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : components()) {
            names.add(component.getName());
        }
        return List.copyOf(names);
    }

    private static List<String> lowerCaseComponentNames() {
        List<String> names = new ArrayList<>();
        for (String name : componentNames()) {
            names.add(name.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }

    private static RecordComponent messageComponent() {
        for (RecordComponent component : components()) {
            if (MESSAGE_COMPONENT_NAME.equals(component.getName())) {
                return component;
            }
        }
        throw new AssertionError("ApiErrorResponse must declare a component named "
                + MESSAGE_COMPONENT_NAME + ", which carries WS-RETURN-MSG PIC X(75) at"
                + " app/cbl/COCRDUPC.cbl:L173. Declared components: " + componentNames());
    }
}

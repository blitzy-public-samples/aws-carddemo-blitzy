package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Shape tests for {@link ApiErrorResponse}.
 *
 * <p>Two properties are under test. The record carries the same three components the card service
 * returns, so one caller reads either service's failure. And the route component refuses a resolved
 * path, so an identifier cannot travel in an error body.
 */
final class ApiErrorResponseTest {

    /** The route template this service's one endpoint reports. */
    private static final String ROUTE = "/notifications/{cardToken}";

    /**
     * A refusal text of this route, used where a method needs any message at all.
     *
     * <p>Read from the constant the endpoint declares rather than written out here, so a change to
     * the wording cannot leave this file naming a text no response carries.
     */
    private static final String SAMPLE_MESSAGE =
            NotificationHistoryController.CARD_TOKEN_MESSAGE;

    @Test
    void theRecordCarriesTheThreeComponentsTheCardServiceCarries() {
        List<String> components = Arrays.stream(ApiErrorResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("status", "message", "route"), components, "components");
    }

    @Test
    void aTemplateRouteIsAccepted() {
        ApiErrorResponse error =
                new ApiErrorResponse(400, SAMPLE_MESSAGE, ROUTE);
        assertEquals(400, error.status(), "status");
        assertEquals(ROUTE, error.route(), "route");
    }

    @Test
    void aResolvedPathCarryingACardNumberIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ApiErrorResponse(400, SAMPLE_MESSAGE,
                        "/notifications/4859452612877" + "065"),
                "a resolved path is refused");
        assertTrue(refused.getMessage().contains("route"), "the message names the component");
    }

    @Test
    void aShortDigitRunIsAcceptedBecauseAPortOrAStatusIsNotACardNumber() {
        ApiErrorResponse error =
                new ApiErrorResponse(400, SAMPLE_MESSAGE,
                        "/v1/notifications");
        assertEquals("/v1/notifications", error.route(), "route");
    }

    @Test
    void aNullComponentIsRefusedByName() {
        NullPointerException noMessage = assertThrows(NullPointerException.class,
                () -> new ApiErrorResponse(400, null, ROUTE), "a null message");
        assertTrue(noMessage.getMessage().contains("message"), "named");
        NullPointerException noRoute = assertThrows(NullPointerException.class,
                () -> new ApiErrorResponse(400, SAMPLE_MESSAGE, null),
                "a null route");
        assertTrue(noRoute.getMessage().contains("route"), "named");
    }

    /**
     * Every text a response of this service can carry names no value read from a request.
     *
     * <p>Letters, spaces and four punctuation marks are the whole character set, so no digit and no
     * hexadecimal run can appear. That is what keeps a card token, a card number and a page size out
     * of a body a caller reads and out of any log line built from one. The hyphen belongs to that set
     * because a text naming a shape reads better with one, as in sixty-four lower-case hexadecimal
     * characters, and a hyphen carries no value.
     *
     * <p>The three bad-request texts are the three refusals this route answers, one per cause:
     * {@code NotificationHistoryController.CARD_TOKEN_MESSAGE} for a path value of the wrong shape,
     * {@code PAGE_SIZE_MESSAGE} for a page size below its floor, and
     * {@code PAGE_SIZE_NOT_A_NUMBER_MESSAGE} for one that is no whole number. Before the split, one
     * text answered all of them and it named an account identifier of eleven digits, which is a
     * value this route does not carry.
     */
    @Test
    void everyTextAResponseCanCarryNamesNoValueReadFromARequest() {
        List<String> texts = List.of(
                NotificationHistoryController.CARD_TOKEN_MESSAGE,
                NotificationHistoryController.PAGE_SIZE_MESSAGE,
                NotificationHistoryController.PAGE_SIZE_NOT_A_NUMBER_MESSAGE,
                NotificationApiExceptionHandler.INVALID_REQUEST_MESSAGE,
                NotificationApiExceptionHandler.SERVICE_FAULT_MESSAGE);

        for (String text : texts) {
            assertTrue(text.matches("[A-Za-z ,.:-]+"),
                    "letters and punctuation alone, and this reads: " + text);
        }
        assertEquals(texts.size(), texts.stream().distinct().count(),
                "each cause carries its own wording, so no two of these texts are the same");
        assertTrue(NotificationHistoryController.CARD_TOKEN_MESSAGE.contains("Card token"),
                "the path value of this route is a card token, so its refusal names one");
        assertTrue(texts.stream().noneMatch(text -> text.contains("Account identifier")),
                "no text of this route names an account identifier, which it does not carry");
    }
}

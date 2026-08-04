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
 * path, so a card number cannot travel in an error body.
 */
final class ApiErrorResponseTest {

    /** The route template this service's one endpoint reports. */
    private static final String ROUTE = "/notifications/{maskedCardNumber}";

    @Test
    void theRecordCarriesTheThreeComponentsTheCardServiceCarries() {
        List<String> components = Arrays.stream(ApiErrorResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("status", "message", "route"), components, "components");
    }

    @Test
    void aTemplateRouteIsAccepted() {
        ApiErrorResponse error = new ApiErrorResponse(400, "Card number must be masked.", ROUTE);
        assertEquals(400, error.status(), "status");
        assertEquals(ROUTE, error.route(), "route");
    }

    @Test
    void aResolvedPathCarryingACardNumberIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ApiErrorResponse(400, "Card number must be masked.",
                        "/notifications/4859452612877" + "065"),
                "a resolved path is refused");
        assertTrue(refused.getMessage().contains("route"), "the message names the component");
    }

    @Test
    void aShortDigitRunIsAcceptedBecauseAPortOrAStatusIsNotACardNumber() {
        ApiErrorResponse error =
                new ApiErrorResponse(400, "Card number must be masked.", "/v1/notifications");
        assertEquals("/v1/notifications", error.route(), "route");
    }

    @Test
    void aNullComponentIsRefusedByName() {
        NullPointerException noMessage = assertThrows(NullPointerException.class,
                () -> new ApiErrorResponse(400, null, ROUTE), "a null message");
        assertTrue(noMessage.getMessage().contains("message"), "named");
        NullPointerException noRoute = assertThrows(NullPointerException.class,
                () -> new ApiErrorResponse(400, "Card number must be masked.", null),
                "a null route");
        assertTrue(noRoute.getMessage().contains("route"), "named");
    }

    @Test
    void theTwoTextsTheHandlerReturnsNameNoValueReadFromARequest() {
        assertTrue(NotificationApiExceptionHandler.INVALID_CARD_NUMBER_MESSAGE
                .matches("[A-Za-z ,.:]+"), "the bad-request text carries letters and punctuation");
        assertTrue(NotificationApiExceptionHandler.SERVICE_FAULT_MESSAGE
                .matches("[A-Za-z ,.:]+"), "the fault text carries letters and punctuation");
    }
}

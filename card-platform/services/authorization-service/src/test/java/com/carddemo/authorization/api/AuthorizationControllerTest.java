package com.carddemo.authorization.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.authorization.domain.AuthenticatedActor;
import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.events.DeclineReason;
import java.math.BigDecimal;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Status and body tests for {@code POST /authorizations}.
 *
 * <p>The endpoint replaces one Customer Information Control System (CICS) transaction. The online
 * capture program {@code app/cbl/COTRN02C.cbl} sends a mapset, receives it back and re-sends it on
 * every failure; this endpoint takes one body and returns one body.
 *
 * <p>An approval answers {@code 200} and a decline answers {@code 422}.
 * {@code app/cbl/CBTRN02C.cbl:L229} tests the reject count and {@code app/cbl/CBTRN02C.cbl:L230}
 * moves 4 into the return code of a batch run that rejected records, so no decline reaches
 * {@code 500} or {@code 503}. A request whose fields fail validation also answers {@code 422},
 * carrying the verbatim texts {@code app/cbl/COTRN02C.cbl} emits under {@link ApiErrorResponse}.
 *
 * <p>These tests stand the controller and its advice up alone. No database, no broker and no
 * application context takes part, so what they prove is the contract of the endpoint.
 */
final class AuthorizationControllerTest {

    /** A complete request body, presenting a card number. */
    private static final String COMPLETE_BODY = """
            {
              "transactionTypeCode": "01",
              "transactionCategoryCode": "0001",
              "source": "POS TERM",
              "description": "Purchase at Abshire-Lowe",
              "amount": "504.77",
              "merchantId": "800000000",
              "merchantName": "Abshire-Lowe",
              "merchantCity": "North Enoshaven",
              "merchantZip": "72112",
              "cardNumber": "4859452612877065",
              "originTimestamp": "2022-06-10 19:27:53.412000",
              "processingTimestamp": "2022-06-10-19.27.53.410000"
            }""";

    /** The card number the complete body presents. */
    private static final String CARD_NUMBER = "4859452612877065";

    /** The service the controller calls, stubbed. */
    private AuthorizationService authorizations;

    /** The endpoint under test, standing alone with its advice. */
    private MockMvc mockMvc;

    /** Stands the controller and its advice up before each test. */
    @BeforeEach
    void standUpEndpoint() {
        authorizations = mock(AuthorizationService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthorizationController(authorizations))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** Asserts an approval answers 200 carrying the decision. */
    @Test
    void anApprovalAnswersTwoHundredCarryingTheDecision() throws Exception {
        when(authorizations.authorize(any(), any()))
                .thenReturn(AuthorizationService.Outcome.approved(new BigDecimal("00000000077"),
                        "0000001000000001"));

        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.accountId").value("00000000077"))
                .andExpect(jsonPath("$.transactionId").value("0000001000000001"));
    }

    /**
     * Asserts a decline answers 422, carrying the reject code under the name the event uses.
     */
    @Test
    void aDeclineAnswersFourTwentyTwoCarryingTheRejectCode() throws Exception {
        when(authorizations.authorize(any(), any())).thenReturn(AuthorizationService.Outcome.declined(
                DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("00000000077"),
                "0000001000000002"));

        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.accountId").value("00000000077"))
                .andExpect(jsonPath("$.transactionId").value("0000001000000002"))
                .andExpect(jsonPath("$.declineReasonCode").value("0102"))
                .andExpect(jsonPath("$.declineReasonDescription").value("OVERLIMIT TRANSACTION"));
    }

    /** Asserts the one decline naming no account answers 422 with a null account identifier. */
    @Test
    void theDeclineNamingNoAccountAnswersFourTwentyTwoWithNoAccountIdentifier() throws Exception {
        when(authorizations.authorize(any(), any()))
                .thenReturn(AuthorizationService.Outcome.declined(
                        DeclineReason.INVALID_CARD_NUMBER, null, "0000001000000003"));

        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineReasonCode").value("0100"))
                .andExpect(jsonPath("$.declineReasonDescription")
                        .value(DeclineReason.INVALID_CARD_NUMBER.description()))
                .andExpect(jsonPath("$.accountId").value(Matchers.nullValue()));
    }

    /**
     * Asserts a declined body carries the reject code the enum defines and never a second one.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L181} declares one {@code PIC 9(04)} field, so one call reports
     * one reject code. Each of the three declines that resolve an account is exercised here, and each
     * reads its text from {@link DeclineReason#description()}.
     */
    @Test
    void everyResolvedDeclineAnswersFourTwentyTwoWithItsOwnEnumText() throws Exception {
        for (DeclineReason reason : DeclineReason.values()) {
            if (!reason.resolvesAccount()) {
                continue;
            }
            when(authorizations.authorize(any(), any()))
                    .thenReturn(AuthorizationService.Outcome.declined(reason,
                            new BigDecimal("00000000077"), "0000001000000005"));

            mockMvc.perform(post("/authorizations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(COMPLETE_BODY))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.declineReasonCode").value(reason.code()))
                    .andExpect(jsonPath("$.declineReasonDescription").value(reason.description()));
        }
    }

    /**
     * Asserts a body missing required fields answers 422 carrying the verbatim source texts, and that
     * the service was never called.
     */
    @Test
    void aBodyMissingRequiredFieldsAnswersFourTwentyTwoWithTheSourceTexts() throws Exception {
        String missingThree = """
                {
                  "amount": "504.77",
                  "merchantId": "800000000",
                  "merchantName": "Abshire-Lowe",
                  "merchantCity": "North Enoshaven",
                  "merchantZip": "72112",
                  "cardNumber": "4859452612877065",
                  "originTimestamp": "2022-06-10 19:27:53.412000",
                  "processingTimestamp": "2022-06-10-19.27.53.410000"
                }""";

        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingThree))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value(ApiErrorResponse.VALIDATION_FAILED))
                .andExpect(jsonPath("$.messages",
                        Matchers.hasItem(AuthorizationRequest.TYPE_CODE_EMPTY_MESSAGE)))
                .andExpect(jsonPath("$.messages",
                        Matchers.hasItem(AuthorizationRequest.CATEGORY_CODE_EMPTY_MESSAGE)))
                .andExpect(jsonPath("$.messages",
                        Matchers.hasItem(AuthorizationRequest.SOURCE_EMPTY_MESSAGE)));

        verify(authorizations, never()).authorize(any(), any());
    }

    /** Asserts a non-numeric category code answers 422 carrying its own verbatim text. */
    @Test
    void aNonNumericCategoryCodeAnswersFourTwentyTwoWithItsOwnText() throws Exception {
        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY.replace("\"0001\"", "\"00X1\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.messages",
                        Matchers.hasItem(AuthorizationRequest.CATEGORY_CODE_NOT_NUMERIC_MESSAGE)));
    }

    /**
     * Asserts a rejected request body reaches no error field, so a card number sent in the wrong
     * position is never reflected back.
     */
    @Test
    void aRejectionCarriesNoValueReadFromTheRequest() throws Exception {
        String cardNumberInTheWrongField =
                COMPLETE_BODY.replace("\"POS TERM\"", "\"" + CARD_NUMBER + "\"")
                        .replace("\"0001\"", "\"00X1\"");

        String body = mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardNumberInTheWrongField))
                .andExpect(status().isUnprocessableContent())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains(CARD_NUMBER),
                "no error body repeats a value read from the request");
        assertFalse(body.contains("/authorizations"),
                "no error body echoes the request path");
    }

    /** Asserts a body the reader cannot parse answers 400 carrying one fixed text. */
    @Test
    void anUnreadableBodyAnswersFourHundredWithOneFixedText() throws Exception {
        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value(ApiErrorResponse.UNPROCESSABLE))
                .andExpect(jsonPath("$.messages[0]")
                        .value(GlobalExceptionHandler.UNREADABLE_BODY_MESSAGE));
    }

    /**
     * Asserts the identity the request carries reaches the service, and that a call carrying none
     * reads as the unauthenticated name rather than as an absence.
     */
    @Test
    void theIdentityBehindTheCallReachesTheService() throws Exception {
        when(authorizations.authorize(any(), any()))
                .thenReturn(AuthorizationService.Outcome.approved(new BigDecimal("00000000077"),
                        "0000001000000004"));

        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY)
                        .principal(() -> "user0001"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<String> actors = ArgumentCaptor.forClass(String.class);
        verify(authorizations, times(2)).authorize(any(), actors.capture());

        assertEquals("user0001", actors.getAllValues().get(0),
                "ADDITIVE. app/cbl/COMEN01C.cbl:L149-L150 disabled the statements that would have "
                        + "carried the signed-on identifier forward; this endpoint carries it");
        assertEquals(AuthenticatedActor.UNAUTHENTICATED_ACTOR, actors.getAllValues().get(1),
                "a call carrying no principal still names an actor the audit column accepts");
    }

    /** Asserts a fault inside the service answers 500 carrying no value from the request. */
    @Test
    void aFaultInsideTheServiceAnswersFiveHundredWithNoRequestValue() throws Exception {
        when(authorizations.authorize(any(), any()))
                .thenThrow(new IllegalStateException("the datasource holds " + CARD_NUMBER));

        String body = mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value(ApiErrorResponse.INTERNAL_FAILURE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains(CARD_NUMBER),
                "the exception message reaches no response body");
    }
}

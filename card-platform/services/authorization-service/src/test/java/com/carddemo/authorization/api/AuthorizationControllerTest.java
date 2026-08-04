package com.carddemo.authorization.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.events.DeclineReason;
import java.math.BigDecimal;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * <p>Two outcomes share status {@code 200}. A decline is a decision and not a failure.
 * {@code app/cbl/CBTRN02C.cbl:L229-L230} treats a rejection the same way, ending a batch run with
 * return code 4. A request whose fields fail validation answers {@code 422} carrying the verbatim
 * texts {@code app/cbl/COTRN02C.cbl} emits.
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
        when(authorizations.authorize(any()))
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
     * Asserts a decline also answers 200, carrying the reject code under the name the event uses.
     */
    @Test
    void aDeclineAnswersTwoHundredCarryingTheRejectCode() throws Exception {
        when(authorizations.authorize(any())).thenReturn(AuthorizationService.Outcome.declined(
                DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("00000000077"),
                "0000001000000002"));

        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineReasonCode").value("0102"))
                .andExpect(jsonPath("$.declineReasonDescription").value("OVERLIMIT TRANSACTION"));
    }

    /** Asserts the one decline naming no account answers 200 with a null account identifier. */
    @Test
    void theDeclineNamingNoAccountAnswersTwoHundredWithNoAccountIdentifier() throws Exception {
        when(authorizations.authorize(any()))
                .thenReturn(AuthorizationService.Outcome.declined(
                        DeclineReason.INVALID_CARD_NUMBER, null, "0000001000000003"));

        mockMvc.perform(post("/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineReasonCode").value("0100"))
                .andExpect(jsonPath("$.accountId").value(Matchers.nullValue()));
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

        verify(authorizations, never()).authorize(any());
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

    /** Asserts a fault inside the service answers 500 carrying no value from the request. */
    @Test
    void aFaultInsideTheServiceAnswersFiveHundredWithNoRequestValue() throws Exception {
        when(authorizations.authorize(any()))
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

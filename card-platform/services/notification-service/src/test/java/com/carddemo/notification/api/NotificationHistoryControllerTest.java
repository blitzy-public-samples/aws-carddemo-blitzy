package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import com.carddemo.notification.repository.StatementTransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Endpoint tests for {@link NotificationHistoryController}.
 *
 * <p>Three properties are under test. The masked card number is the path value and matches the key
 * column, so a lookup needs no conversion. A full sixteen-digit value is refused, so no full
 * Primary Account Number (PAN) reaches the read model or a log line. And a failing response carries
 * the route template, never the value the caller sent.
 *
 * <p>The repository is stubbed, so no database takes part.
 */
final class NotificationHistoryControllerTest {

    /** A masked card number. */
    private static final String MASKED_CARD = "************7065";

    /** Reads the read model. */
    private StatementTransactionRepository statementTransactions;

    /** Drives the endpoint. */
    private MockMvc mockMvc;

    @BeforeEach
    void buildEndpoint() {
        statementTransactions = mock(StatementTransactionRepository.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new NotificationHistoryController(statementTransactions))
                .setControllerAdvice(new NotificationApiExceptionHandler())
                .build();
    }

    @Test
    void aMaskedCardWithRowsAnswersTwoHundredCarryingItsHistory() throws Exception {
        when(statementTransactions.findByIdCardNumberOrderByIdTransactionIdAsc(MASKED_CARD))
                .thenReturn(List.of(row("0000000000000001", "194.00"),
                        row("0000000000000002", "310.77")));

        mockMvc.perform(get("/notifications/{maskedCardNumber}", MASKED_CARD))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD))
                .andExpect(jsonPath("$.transactionCount").value(2))
                .andExpect(jsonPath("$.totalAmount").value("504.77"))
                .andExpect(jsonPath("$.transactions[0].transactionId")
                        .value("0000000000000001"))
                .andExpect(jsonPath("$.transactions[0].amount").value("194.00"))
                .andExpect(jsonPath("$.transactions[1].transactionId")
                        .value("0000000000000002"));
    }

    @Test
    void aMaskedCardWithNoRowsAnswersTwoHundredWithAnEmptyHistory() throws Exception {
        when(statementTransactions.findByIdCardNumberOrderByIdTransactionIdAsc(MASKED_CARD))
                .thenReturn(List.of());

        mockMvc.perform(get("/notifications/{maskedCardNumber}", MASKED_CARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionCount").value(0))
                .andExpect(jsonPath("$.totalAmount").value("0.00"))
                .andExpect(jsonPath("$.transactions").isEmpty());
    }

    @Test
    void aFullCardNumberIsRefusedAndReachesNoLookup() throws Exception {
        mockMvc.perform(get("/notifications/{maskedCardNumber}", "4859452612877" + "065"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.route").value("/notifications/{maskedCardNumber}"));

        verify(statementTransactions, never())
                .findByIdCardNumberOrderByIdTransactionIdAsc(anyString());
    }

    @Test
    void aValueOfTheWrongShapeIsRefused() throws Exception {
        mockMvc.perform(get("/notifications/{maskedCardNumber}", "not-a-card"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void aRefusalCarriesTheRouteTemplateAndNoValueReadFromTheRequest() throws Exception {
        String sent = "4859452612877" + "065";
        String body = mockMvc.perform(get("/notifications/{maskedCardNumber}", sent))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(sent),
                "the body echoes the value the caller sent");
        assertTrue(body.contains("{maskedCardNumber}"),
                "the body carries the route template");
        assertFalse(body.contains("instance"),
                "the body carries no resolved request path");
        assertFalse(body.contains("\"path\""),
                "the body carries no resolved request path");
    }

    @Test
    void aFaultInsideTheLookupAnswersFiveHundredWithNoRequestValue() throws Exception {
        when(statementTransactions.findByIdCardNumberOrderByIdTransactionIdAsc(MASKED_CARD))
                .thenThrow(new IllegalStateException("connection refused to 10.0.0.1:5432"));

        String body = mockMvc.perform(get("/notifications/{maskedCardNumber}", MASKED_CARD))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.route").value("/notifications/{maskedCardNumber}"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("10.0.0.1"),
                "the body carries no value read from the fault");
    }

    /**
     * Builds one read-model row.
     *
     * @param transactionId the sixteen-character identifier
     * @param amount the amount at two fractional digits
     * @return the row
     */
    private static StatementTransactionEntity row(String transactionId, String amount) {
        return new StatementTransactionEntity(
                new StatementTransactionId(MASKED_CARD, transactionId), "01", "1",
                "POS TERM", "Purchase", new BigDecimal(amount), "800000000",
                "Abshire-Lowe", "North Enoshaven", "72112", "2022-06-10 19:27:53.000000",
                "2022-07-19-23.16.01.470000");
    }
}

package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.config.NotificationProperties;
import com.carddemo.notification.config.ObservabilityConfig;
import com.carddemo.notification.domain.NotificationRenderer;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Endpoint tests for {@link NotificationHistoryController}.
 *
 * <p>Four properties are under test. The card token is the path value and matches the key column, so
 * a lookup needs no conversion. A card number of either form is refused: a full sixteen-digit value
 * keeps no Primary Account Number (PAN) out of the read model or a log line, and a masked value
 * identifies no single card. Every lookup names the row ceiling, so no request reads a whole card
 * history. And a failing response carries the route template, never the value the caller sent.
 *
 * <p>The repository is stubbed, so no database takes part.
 */
final class NotificationHistoryControllerTest {

    /** A masked card number, the display value the stored rows carry. */
    private static final String MASKED_CARD = "************7065";

    /** A full card number, split so no sixteen-digit literal appears in one piece. */
    private static final String FULL_CARD = "4859452612877" + "065";

    /** Card token of that card, and the path value every request below carries. */
    private static final String CARD_TOKEN = PanMasker.cardToken(FULL_CARD);

    /**
     * The paging bounds this route answers under, the values
     * {@code src/main/resources/application.yml} ships.
     */
    private static final NotificationProperties.History HISTORY =
            new NotificationProperties.History(400, 90, 3_600_000L, 50,
                    NotificationRenderer.MAXIMUM_STATEMENT_ROWS);

    /** The limit a request naming no page size reaches the database with. */
    private static final Limit PAGE_LIMIT = Limit.of(HISTORY.defaultPageSize());

    /** Reads the read model. */
    private StatementTransactionRepository statementTransactions;

    /** Drives the endpoint. */
    private MockMvc mockMvc;

    @BeforeEach
    void buildEndpoint() {
        statementTransactions = mock(StatementTransactionRepository.class);
        NotificationService notifications = new NotificationService(List.of(), statementTransactions,
                mock(NotificationLogRepository.class),
                new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()));
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new NotificationHistoryController(statementTransactions, notifications,
                                properties()))
                .setControllerAdvice(new NotificationApiExceptionHandler())
                .build();
    }

    /**
     * Builds the bound configuration this route reads, carrying the shipped paging bounds.
     *
     * @return the properties
     */
    private static NotificationProperties properties() {
        return new NotificationProperties(
                new NotificationProperties.Kafka(
                        new NotificationProperties.Kafka.Groups("authorized", "posted", "fraud",
                                "customer"),
                        new NotificationProperties.Kafka.Topics("transaction.authorized",
                                "transaction.posted", "fraud.assessed",
                                "customer.context-changed", "carddemo.dead-letter", ".DLT")),
                new NotificationProperties.Consumer(
                        new NotificationProperties.Consumer.Retry(3, 1000L)),
                new NotificationProperties.ProcessedEvent(168),
                HISTORY);
    }

    @Test
    void aCardTokenWithRowsAnswersTwoHundredCarryingItsHistory() throws Exception {
        when(statementTransactions.findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, PAGE_LIMIT))
                .thenReturn(List.of(row("0000000000000001", "194.00"),
                        row("0000000000000002", "310.77")));

        mockMvc.perform(get("/notifications/{cardToken}", CARD_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.cardToken").value(CARD_TOKEN))
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
    void aCardTokenWithNoRowsAnswersTwoHundredWithAnEmptyHistory() throws Exception {
        when(statementTransactions.findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, PAGE_LIMIT))
                .thenReturn(List.of());

        mockMvc.perform(get("/notifications/{cardToken}", CARD_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardToken").value(CARD_TOKEN))
                .andExpect(jsonPath("$.cardNumber").doesNotExist())
                .andExpect(jsonPath("$.transactionCount").value(0))
                .andExpect(jsonPath("$.totalAmount").value("0.00"))
                .andExpect(jsonPath("$.transactions").isEmpty());
    }

    @Test
    void aFullCardNumberIsRefusedAndReachesNoLookup() throws Exception {
        mockMvc.perform(get("/notifications/{cardToken}", FULL_CARD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.route").value("/notifications/{cardToken}"));

        verify(statementTransactions, never())
                .findByIdCardTokenOrderByIdTransactionIdAsc(anyString(), any(Limit.class));
    }

    @Test
    void aMaskedCardNumberIsRefusedAndReachesNoLookup() throws Exception {
        mockMvc.perform(get("/notifications/{cardToken}", MASKED_CARD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.route").value("/notifications/{cardToken}"));

        verify(statementTransactions, never())
                .findByIdCardTokenOrderByIdTransactionIdAsc(anyString(), any(Limit.class));
    }

    @Test
    void everyLookupNamesTheResolvedPageSizeAndNeverAnUnboundedRead() throws Exception {
        when(statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, PAGE_LIMIT))
                .thenReturn(List.of(row("0000000000000001", "194.00")));

        mockMvc.perform(get("/notifications/{cardToken}", CARD_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionCount").value(1));

        verify(statementTransactions)
                .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, PAGE_LIMIT);
        verify(statementTransactions, never())
                .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, Limit.unlimited());
    }

    @Test
    void aValueOfTheWrongShapeIsRefused() throws Exception {
        mockMvc.perform(get("/notifications/{cardToken}", "not-a-card"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void aRefusalCarriesTheRouteTemplateAndNoValueReadFromTheRequest() throws Exception {
        String sent = FULL_CARD;
        String body = mockMvc.perform(get("/notifications/{cardToken}", sent))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(sent),
                "the body echoes the value the caller sent");
        assertTrue(body.contains("{cardToken}"),
                "the body carries the route template");
        assertFalse(body.contains("instance"),
                "the body carries no resolved request path");
        assertFalse(body.contains("\"path\""),
                "the body carries no resolved request path");
    }

    @Test
    void aFaultInsideTheLookupAnswersFiveHundredWithNoRequestValue() throws Exception {
        when(statementTransactions.findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, PAGE_LIMIT))
                .thenThrow(new IllegalStateException("connection refused to 10.0.0.1:5432"));

        String body = mockMvc.perform(get("/notifications/{cardToken}", CARD_TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.route").value("/notifications/{cardToken}"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("10.0.0.1"),
                "the body carries no value read from the fault");
    }

    @Test
    void aPageSizeAboveTheConfiguredMaximumIsCappedBeforeItReachesTheLookup() throws Exception {
        when(statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/notifications/{cardToken}", CARD_TOKEN).param("pageSize", "5000"))
                .andExpect(status().isOk());

        ArgumentCaptor<Limit> applied = ArgumentCaptor.forClass(Limit.class);
        verify(statementTransactions)
                .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), applied.capture());
        assertEquals(HISTORY.maximumPageSize(), applied.getValue().max(),
                "carddemo.history.maximum-page-size caps what a caller may ask for");
    }

    @Test
    void aRequestNamingNoPageSizeTakesTheConfiguredDefault() throws Exception {
        when(statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/notifications/{cardToken}", CARD_TOKEN))
                .andExpect(status().isOk());

        ArgumentCaptor<Limit> applied = ArgumentCaptor.forClass(Limit.class);
        verify(statementTransactions)
                .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), applied.capture());
        assertEquals(HISTORY.defaultPageSize(), applied.getValue().max(),
                "carddemo.history.default-page-size applies when a caller names none");
    }

    @Test
    void aPageSizeBelowOneIsRefusedAndReachesNoLookup() throws Exception {
        mockMvc.perform(get("/notifications/{cardToken}", CARD_TOKEN).param("pageSize", "0"))
                .andExpect(status().isBadRequest());

        verify(statementTransactions, never())
                .findByIdCardTokenOrderByIdTransactionIdAsc(anyString(), any(Limit.class));
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
                new StatementTransactionId(CARD_TOKEN, transactionId), MASKED_CARD, "01", "1",
                "POS TERM", "Purchase", new BigDecimal(amount), "800000000",
                "Abshire-Lowe", "North Enoshaven", "72112", "2022-06-10 19:27:53.000000",
                "2022-07-19-23.16.01.470000");
    }
}

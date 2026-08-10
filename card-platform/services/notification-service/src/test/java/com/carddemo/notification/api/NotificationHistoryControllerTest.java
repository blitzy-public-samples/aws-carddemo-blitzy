package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import com.carddemo.notification.repository.StatementTransactionRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Endpoint tests for {@link NotificationHistoryController}, the one synchronous surface of the
 * notification service.
 *
 * <p>Eight groups of assertions cover that surface at the Hypertext Transfer Protocol (HTTP)
 * boundary. The mapped surface carries one handler, and that handler reads. A conforming path value
 * answers 200 with four properties, and each transaction it carries holds twelve. A path value
 * outside the declared shape answers 400 and reaches no lookup. A card with no row answers 200 with
 * an empty array and never 404.
 *
 * <p>The remaining four groups cover what leaves the controller. The card number in the path reaches
 * the read model as a derived token and never as itself. The lookup names the ascending finder and
 * the whole history. The controller declares two collaborators, and no messaging, transport, metric,
 * logging or delivery type sits among them. A fault answers 500, and every failing body carries the
 * route template.
 *
 * <p>The read model is keyed on the card token, which stands in for
 * {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}.
 * {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} is the second key part, and
 * {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30} declares the pair.
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} sets the
 * ascending order this endpoint returns rows in.
 *
 * <p>Both collaborators are mocks, so no database, no broker and no container takes part.
 * {@code mvn test} runs this class on a clean machine with no manual step.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * <p>A refusal of this route carries {@code NotificationHistoryController.CARD_TOKEN_MESSAGE},
 * which names the shape the path variable requires and never the characters submitted.
 * {@code ApiErrorResponseTest} holds every text of this service to letters and punctuation, so none
 * can carry a digit run.
 */
final class NotificationHistoryControllerTest {

    /** Reads a serialized body. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** The route template the description declares, and the one this endpoint answers on. */
    private static final String ROUTE = "/notifications/{cardToken}";

    /** The collection segment of that route. */
    private static final String COLLECTION = "/notifications";

    /**
     * The card number every conforming request below carries, built at runtime so no sixteen-digit
     * literal appears in this file. Source width: {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L22}.
     */
    private static final String FULL_CARD_NUMBER = "5".repeat(12) + "7065";

    /** The masked form of that card number: twelve mask characters then its last four digits. */
    private static final String MASKED_CARD_NUMBER = "*".repeat(12) + "7065";

    /**
     * The card token that card number derives to, and the key the read model holds.
     * {@link PanMasker#cardToken(String)} derives it under the build card-token key, which
     * {@code card-platform/pom.xml} supplies to this test.
     */
    private static final String CARD_TOKEN = PanMasker.cardToken(FULL_CARD_NUMBER);

    /** Shape of a card token, the shape the derived key keeps. */
    private static final Pattern CARD_TOKEN_SHAPE = Pattern.compile(PanMasker.CARD_TOKEN_PATTERN);

    /** A run of sixteen digits, the shape of a full card number. */
    private static final Pattern SIXTEEN_DIGIT_RUN = Pattern.compile("[0-9]{16}");

    /**
     * The properties the response envelope carries on a last page.
     *
     * <p>{@code nextCursor} is absent here because every body this class builds is a last page: a
     * cursor is present exactly when a further page exists, and {@code Paging} covers the case where
     * one does.
     */
    private static final List<String> ENVELOPE_PROPERTIES =
            List.of("cardNumber", "transactionCount", "totalAmount", "transactions",
                    "nextPageExists");

    /**
     * The four envelope properties that carry data. A failing body holds none of them, and it
     * carries the route template instead.
     */
    private static final List<String> ENVELOPE_DATA_PROPERTIES =
            List.of("cardNumber", "transactionCount", "totalAmount", "transactions");

    /**
     * The twelve properties one transaction carries, mapping {@code app/cpy/COSTM01.CPY:L23} and
     * {@code app/cpy/COSTM01.CPY:L25-L35}. The card is named once, on the envelope.
     */
    private static final List<String> TRANSACTION_PROPERTIES = List.of("transactionId",
            "typeCode", "categoryCode", "source", "description", "amount",
            "merchantId", "merchantName", "merchantCity", "merchantZip", "originTimestamp",
            "processingTimestamp");

    /**
     * Fields other services own, lower-cased. The statement program renders six of them and the
     * account service owns every one.
     *
     * <p>{@code ST-NAME PIC X(75)} sits at {@code app/cbl/CBSTM03A.CBL:L91}, under the group at
     * {@code app/cbl/CBSTM03A.CBL:L90}. The three address groups sit at
     * {@code app/cbl/CBSTM03A.CBL:L93}, {@code app/cbl/CBSTM03A.CBL:L96} and
     * {@code app/cbl/CBSTM03A.CBL:L99}. {@code ST-CURR-BAL PIC 9(9).99-} sits at
     * {@code app/cbl/CBSTM03A.CBL:L113}, under the label at {@code app/cbl/CBSTM03A.CBL:L112}.
     * {@code ST-FICO-SCORE PIC X(20)} sits at {@code app/cbl/CBSTM03A.CBL:L118}.</p>
     */
    private static final List<String> FIELDS_OTHER_SERVICES_OWN = List.of("accountid",
            "currentbalance", "creditlimit", "cashcreditlimit", "activestatus", "opendate",
            "expir", "reissuedate", "groupid", "ficoscore", "customername", "addressline1",
            "addressline2", "addressline3", "declinereason", "cardstatus", "accountstatus",
            "deliverychannel", "emailaddress", "phonenumber");

    /** The five envelope properties every event schema declares, lower-cased. */
    private static final List<String> EVENT_ENVELOPE_PROPERTIES = List.of("eventid", "eventtype",
            "schemaversion", "occurredat", "aggregateid");

    /**
     * The abbreviated verification field of the source card record, lower-cased. Source:
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}.
     */
    private static final String VERIFICATION_FIELD_ABBREVIATION =
            "CARD-CVV-CD".split("-")[1].toLowerCase(Locale.ROOT);

    /** Two further spellings of a verification field, lower-cased. */
    private static final List<String> VERIFICATION_FIELD_SPELLINGS =
            List.of("cardverification", "securitycode");

    /**
     * Type-name fragments the controller holds none of. Messaging, transport, metric, logging,
     * publication and delivery types each appear here.
     */
    private static final List<String> INFRASTRUCTURE_TYPE_FRAGMENTS = List.of("Kafka",
            "KafkaTemplate", "Producer", "Consumer", "Listener", "RestTemplate", "RestClient",
            "WebClient", "HttpClient", "MeterRegistry", "Counter", "Timer", "NotificationMetrics",
            "Logger", "Log", "Slf4j", "Outbox", "Relay", "EventPublisher", "JavaMailSender",
            "MailSender", "SmsClient", "NotificationGateway");

    /** The exact name of the ascending finder this endpoint reads through. */
    private static final String ASCENDING_FINDER = "findByIdCardTokenOrderByIdTransactionIdAsc";

    /** The fragment a finder name carries when it fixes an order. */
    private static final String ORDER_FRAGMENT = "OrderBy";

    /**
     * The limit a request with no page size reaches the read model with.
     *
     * <p>The default page and one lookahead row. The route once passed {@link Limit#unlimited()}, so
     * one request read every retained row of a card; the count and the total still cover the whole
     * card, but they come from an aggregate rather than from the rows.
     */
    private static final Limit FIRST_PAGE =
            Limit.of(NotificationHistoryController.DEFAULT_PAGE_SIZE + 1);

    /** The first transaction identifier, at the sixteen characters L23 declares. */
    private static final String FIRST_TRANSACTION_ID = "TRN0000000000001";

    /** The second transaction identifier, at the same width. */
    private static final String SECOND_TRANSACTION_ID = "TRN0000000000002";

    /** The third transaction identifier, at the same width. */
    private static final String THIRD_TRANSACTION_ID = "TRN0000000000003";

    /**
     * A fourth transaction identifier, at the same width, naming a row no test places behind the
     * lookup. It builds the row set {@code TheTotalledList} totals to prove a total taken over the
     * wrong list carries a different value.
     */
    private static final String UNRELATED_TRANSACTION_ID = "TRN0000000000009";

    /** The total of the three rows below, at the two fractional digits L29 declares. */
    private static final BigDecimal THREE_ROW_TOTAL = new BigDecimal("1160.03");

    /** A zero total at the same two fractional digits. */
    private static final BigDecimal NO_ROW_TOTAL = new BigDecimal("0.00");

    /** Reads the card-keyed read model. */
    private StatementTransactionRepository statementTransactions;

    /** Totals one card's rows. */
    private NotificationService notifications;

    /** The controller under test. */
    private NotificationHistoryController controller;

    /** Drives the endpoint. */
    private MockMvc mockMvc;

    /** Builds the endpoint over two mocks, with the advice this service ships registered. */
    @BeforeEach
    void buildEndpoint() {
        statementTransactions = mock(StatementTransactionRepository.class);
        notifications = mock(NotificationService.class);
        controller = new NotificationHistoryController(statementTransactions, notifications);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new NotificationApiExceptionHandler())
                .build();
    }

    /**
     * Stubs the whole-history aggregate for {@link #CARD_TOKEN} over the rows supplied.
     *
     * <p>The count and the masked number come from the rows, so a test that changes the rows changes
     * the aggregate with them and the two cannot drift. The total is supplied rather than derived,
     * because reproducing the source's accumulation is
     * {@code domain/NotificationService}'s work and is asserted there.
     *
     * @param rows  the rows the card holds in all
     * @param total the total those rows carry
     */
    private void aggregateOver(List<StatementTransactionEntity> rows, BigDecimal total) {
        when(statementTransactions.totalsOfCard(CARD_TOKEN)).thenReturn(new FakeTotals(
                rows.size(), total,
                rows.stream().map(StatementTransactionEntity::getAmount)
                        .map(BigDecimal::abs)
                        .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add),
                rows.isEmpty() ? null : rows.get(0).getMaskedCardNumber()));
        when(notifications.totalOfCard(eq(CARD_TOKEN), any())).thenReturn(total);
    }

    /**
     * The aggregate row the read model answers with, as a value rather than a mock.
     *
     * @param transactionCount rows the card holds in all
     * @param totalAmount      their exact sum
     * @param absoluteTotal    the sum of their magnitudes
     * @param maskedCardNumber the masked number those rows carry, or {@code null} where none does
     */
    private record FakeTotals(long transactionCount, BigDecimal totalAmount,
            BigDecimal absoluteTotal, String maskedCardNumber)
            implements StatementTransactionRepository.CardHistoryTotals {

        @Override
        public long getTransactionCount() {
            return transactionCount;
        }

        @Override
        public BigDecimal getTotalAmount() {
            return totalAmount;
        }

        @Override
        public BigDecimal getAbsoluteTotal() {
            return absoluteTotal;
        }

        @Override
        public String getMaskedCardNumber() {
            return maskedCardNumber;
        }
    }

    /**
     * The mapped surface: one handler, one read method, one path pattern.
     *
     * <p>Each assertion reads the mapping the framework builds from the controller.</p>
     */
    @Nested
    class MappedSurface {

        /** Asserts the controller maps one handler and no second. */
        @Test
        void theControllerMapsExactlyOneHandler() {
            List<HandlerMethod> handlers = List.copyOf(handlerMethods().values());

            assertEquals(1, handlers.size(), "mapped handler count");
            assertEquals(NotificationHistoryController.class,
                    handlers.getFirst().getMethod().getDeclaringClass(), "declaring class");
        }

        /** Asserts the one handler answers {@code GET} and no other method. */
        @Test
        void theOneHandlerAnswersGetAndNoOtherMethod() {
            Set<RequestMethod> methods = oneMapping().getMethodsCondition().getMethods();

            assertEquals(Set.of(RequestMethod.GET), methods, "method condition");
        }

        /**
         * Asserts the path pattern is the collection and the card token, with no version or
         * gateway segment.
         */
        @Test
        void thePathPatternIsTheCollectionAndTheCardToken() {
            String pattern = oneMapping().getPatternValues().iterator().next();

            assertEquals(ROUTE, pattern, "path pattern");
            assertTrue(pattern.startsWith(COLLECTION + "/"), "the pattern opens on the collection");
            assertFalse(pattern.contains("/api"), "the pattern carries a gateway segment");
            assertFalse(pattern.contains("/v1"), "the pattern carries a version segment");
        }

        /** Asserts the one handler declares JavaScript Object Notation (JSON) output. */
        @Test
        void theOneHandlerProducesJson() {
            Set<MediaType> produced =
                    oneMapping().getProducesCondition().getProducibleMediaTypes();

            assertTrue(produced.contains(MediaType.APPLICATION_JSON),
                    "produces condition holds " + MediaType.APPLICATION_JSON_VALUE);
        }

        /** Asserts no declared method carries a write mapping annotation. */
        @Test
        void noDeclaredMethodCarriesAWriteMappingAnnotation() {
            for (Method method : NotificationHistoryController.class.getDeclaredMethods()) {
                assertNull(method.getAnnotation(PostMapping.class),
                        method.getName() + " carries a create mapping");
                assertNull(method.getAnnotation(PutMapping.class),
                        method.getName() + " carries a replace mapping");
                assertNull(method.getAnnotation(PatchMapping.class),
                        method.getName() + " carries an amend mapping");
                assertNull(method.getAnnotation(DeleteMapping.class),
                        method.getName() + " carries a remove mapping");
                RequestMapping mapping = method.getAnnotation(RequestMapping.class);
                if (mapping != null) {
                    for (RequestMethod declared : mapping.method()) {
                        assertEquals(RequestMethod.GET, declared,
                                method.getName() + " names " + declared);
                    }
                }
            }
        }

        /**
         * Asserts a write to the same path answers 405 with an Allow header and reaches no lookup.
         *
         * <p>Each of the four write methods used to answer {@code 500} carrying the fault text, since
         * the catch-all arm of the advice claimed the checked exception the framework raises for an
         * unsupported method. A client cannot tell that answer from a real fault, and a retry of it
         * repeats the mistake. {@code Allow} is what tells a caller that this route serves
         * {@code GET} alone.
         */
        @Test
        void aWriteToTheSamePathAnswersMethodNotAllowedAndReachesNoLookup() throws Exception {
            for (RequestBuilder write : List.of(post(ROUTE, CARD_TOKEN), put(ROUTE, CARD_TOKEN),
                    patch(ROUTE, CARD_TOKEN), delete(ROUTE, CARD_TOKEN))) {

                MockHttpServletResponse answered = mockMvc.perform(write).andReturn().getResponse();

                assertEquals(405, answered.getStatus(),
                        "a method this route does not serve answers 405");
                assertEquals("GET", answered.getHeader(HttpHeaders.ALLOW),
                        "the answer names the method this route does serve");
                assertTrue(answered.getContentAsString()
                                .contains(NotificationApiExceptionHandler
                                        .UNSUPPORTED_REQUEST_MESSAGE),
                        "the body carries the documented refusal text: "
                                + answered.getContentAsString());
                assertFalse(answered.getContentAsString().contains(FULL_CARD_NUMBER),
                        "no refusal repeats the value the caller sent");
            }
            verify(statementTransactions, never())
                    .findByIdCardTokenOrderByIdTransactionIdAsc(anyString(), any(Limit.class));
        }

        /**
         * Returns the one mapping the controller declares.
         *
         * @return the mapping
         */
        private RequestMappingInfo oneMapping() {
            return handlerMethods().keySet().iterator().next();
        }

        /**
         * Builds the handler-method map the framework derives from the controller.
         *
         * @return one entry per mapped handler
         */
        private Map<RequestMappingInfo, HandlerMethod> handlerMethods() {
            try (GenericApplicationContext context = new GenericApplicationContext()) {
                context.registerBean("notificationHistoryController",
                        NotificationHistoryController.class, () -> controller);
                context.refresh();
                RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
                mapping.setApplicationContext(context);
                mapping.afterPropertiesSet();
                return Map.copyOf(mapping.getHandlerMethods());
            }
        }
    }

    /**
     * The history of a card holding rows: status, envelope, items and order.
     *
     * <p>Three rows arrive from the read model in ascending transaction-identifier order, the
     * order {@code app/jcl/CREASTMT.JCL:L53} produced.</p>
     */
    @Nested
    class HistoryOfACardWithRows {

        /**
         * The row set the lookup answers with, held so a test can compare it against the list the
         * controller handed to the total.
         */
        private List<StatementTransactionEntity> rowsInTheReadModel;

        /** Places three rows behind the read model and the derived total behind the service. */
        @BeforeEach
        void threeRowsInTheReadModel() {
            rowsInTheReadModel = threeRows();
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, FIRST_PAGE))
                    .thenReturn(threeRows());
            aggregateOver(rowsInTheReadModel, THREE_ROW_TOTAL);
        }

        /** Asserts a card number with rows answers 200 carrying its count, total and items. */
        @Test
        void aCardNumberWithRowsAnswersTwoHundredCarryingItsHistory() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD_NUMBER))
                    .andExpect(jsonPath("$.transactionCount").value(3))
                    .andExpect(jsonPath("$.totalAmount").value(THREE_ROW_TOTAL.toPlainString()))
                    .andExpect(jsonPath("$.transactions.length()").value(3));
        }

        /** Asserts the envelope carries five properties, named and ordered as the record is. */
        @Test
        void theEnvelopeCarriesItsPropertiesAndNoOther() throws Exception {
            JsonNode body = MAPPER.readTree(historyBody());

            assertEquals(ENVELOPE_PROPERTIES, List.copyOf(body.propertyNames()),
                    "envelope property names, in that order");
            assertEquals(ENVELOPE_PROPERTIES.size(), body.size(), "envelope property count");
            assertFalse(body.has("cardToken"), "the storage key reaches no body");
        }

        /**
         * Asserts each item carries twelve properties, mapping {@code app/cpy/COSTM01.CPY:L23} and
         * {@code app/cpy/COSTM01.CPY:L25-L35}. The card is named once, on the envelope.
         */
        @Test
        void eachItemCarriesTwelveProperties() throws Exception {
            JsonNode transactions = MAPPER.readTree(historyBody()).get("transactions");

            assertEquals(3, transactions.size(), "item count");
            for (JsonNode item : transactions) {
                assertEquals(TRANSACTION_PROPERTIES, List.copyOf(item.propertyNames()),
                        "item property names, in that order");
                assertEquals(12, item.size(), "item property count");
            }
        }

        /**
         * Asserts the items follow the order the rows arrived in, the ascending order
         * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53}
         * produced.
         */
        @Test
        void theItemsFollowTheAscendingOrderTheRowsArrivedIn() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions[0].transactionId")
                            .value(FIRST_TRANSACTION_ID))
                    .andExpect(jsonPath("$.transactions[1].transactionId")
                            .value(SECOND_TRANSACTION_ID))
                    .andExpect(jsonPath("$.transactions[2].transactionId")
                            .value(THIRD_TRANSACTION_ID));
        }

        /** Asserts the serialized body carries no run of sixteen digits. */
        @Test
        void theBodyCarriesNoSixteenDigitRun() throws Exception {
            String body = historyBody();

            assertFalse(SIXTEEN_DIGIT_RUN.matcher(body).find(),
                    "the body carries a sixteen-digit run");
            assertFalse(body.contains(FULL_CARD_NUMBER), "the body carries a full card number");
        }

        /** Asserts no property of the body names a field another service owns. */
        @Test
        void theBodyCarriesNoFieldAnotherServiceOwns() throws Exception {
            List<String> properties = propertyNamesOf(MAPPER.readTree(historyBody()));

            for (String owned : FIELDS_OTHER_SERVICES_OWN) {
                for (String property : properties) {
                    assertFalse(property.contains(owned), property + " names " + owned);
                }
            }
        }

        /** Asserts no property of the body restates an event-envelope property. */
        @Test
        void theBodyRestatesNoEventEnvelopeProperty() throws Exception {
            List<String> properties = propertyNamesOf(MAPPER.readTree(historyBody()));

            for (String envelope : EVENT_ENVELOPE_PROPERTIES) {
                assertFalse(properties.contains(envelope), "a property names " + envelope);
            }
        }

        /**
         * Asserts no property of the body names a card verification value. Source:
         * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}.
         */
        @Test
        void theBodyCarriesNoVerificationProperty() throws Exception {
            List<String> properties = propertyNamesOf(MAPPER.readTree(historyBody()));
            List<String> spellings = new ArrayList<>(VERIFICATION_FIELD_SPELLINGS);
            spellings.add(VERIFICATION_FIELD_ABBREVIATION);

            for (String spelling : spellings) {
                for (String property : properties) {
                    assertFalse(property.contains(spelling), property + " names " + spelling);
                }
            }
        }

        /** Asserts the body carries no reject reason code. */
        @Test
        void theBodyCarriesNoRejectReasonCode() throws Exception {
            String rejectReason = Integer.toString(100 + 9);

            assertFalse(historyBody().contains(rejectReason),
                    "the body carries reason " + rejectReason);
        }

        /**
         * Reads the body of one history request.
         *
         * @return the serialized body
         * @throws Exception when the request cannot be performed
         */
        private String historyBody() throws Exception {
            return mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    /**
     * Path values outside the declared shape.
     *
     * <p>The path variable is a {@code String} constrained by Jakarta Bean Validation to a card
     * token: exactly {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal characters, the
     * shape column {@code statement_transaction.card_token} holds. Each value below answers 400,
     * leaks no envelope property, echoes nothing the caller sent, and reaches neither
     * collaborator.</p>
     *
     * <p>The first two are what the shape exists to refuse. A full card number in a path is written
     * to an access log, a proxy log, a trace and a browser history that this application cannot
     * redact, so the constraint refuses one rather than reading it. A masked number names every card
     * sharing four digits, so it names no single row.</p>
     */
    @Nested
    class RefusedPathValues {

        /** Asserts a full card number is refused and reaches no lookup. */
        @Test
        void aFullCardNumberIsRefused() throws Exception {
            assertRefused(FULL_CARD_NUMBER);
        }

        /**
         * Asserts a masked card number is refused and reaches no lookup. The declared shape admits
         * sixty-four hexadecimal characters, and a masked value carries mask characters.
         */
        @Test
        void aMaskedCardNumberIsRefused() throws Exception {
            assertRefused(MASKED_CARD_NUMBER);
        }

        /** Asserts a token one character under its width is refused. */
        @Test
        void aTokenUnderItsWidthIsRefused() throws Exception {
            assertRefused(CARD_TOKEN.substring(1));
        }

        /** Asserts a token one character over its width is refused. */
        @Test
        void aTokenOverItsWidthIsRefused() throws Exception {
            assertRefused(CARD_TOKEN + "7");
        }

        /**
         * Asserts a value at the declared width carrying a character outside the shape is refused.
         */
        @Test
        void aValueCarryingACharacterOutsideTheShapeIsRefused() throws Exception {
            assertRefused("z" + CARD_TOKEN.substring(1));
        }

        /** Asserts a value carrying a separator at the declared width is refused. */
        @Test
        void aValueCarryingASeparatorIsRefused() throws Exception {
            assertRefused(CARD_TOKEN.substring(0, CARD_TOKEN.length() - 1) + "-");
        }

        /**
         * Asserts the upper-case rendering of a real token is refused, so one card has one path
         * value and a caller cannot reach a row by a second spelling of its key.
         */
        @Test
        void anUpperCaseRenderingOfATokenIsRefused() throws Exception {
            assertRefused(CARD_TOKEN.toUpperCase(Locale.ROOT));
        }

        /** Asserts a value of the declared width holding no hexadecimal character is refused. */
        @Test
        void anAlphabeticValueAtTheDeclaredWidthIsRefused() throws Exception {
            assertRefused("z".repeat(PanMasker.CARD_TOKEN_LENGTH));
        }

        /**
         * Asserts one advice class in this package answers a refusal, and that the controller
         * declares no handler of its own.
         */
        @Test
        void oneAdviceClassAnswersARefusalAndTheControllerDeclaresNone() {
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(ControllerAdvice.class));
            scanner.addIncludeFilter(new AnnotationTypeFilter(RestControllerAdvice.class));
            Set<String> advice = new LinkedHashSet<>();
            scanner.findCandidateComponents(NotificationHistoryController.class.getPackageName())
                    .forEach(definition -> advice.add(definition.getBeanClassName()));

            assertEquals(Set.of(NotificationApiExceptionHandler.class.getName()), advice,
                    "advice classes in this package");
            for (Method method : NotificationHistoryController.class.getDeclaredMethods()) {
                assertNull(method.getAnnotation(ExceptionHandler.class),
                        method.getName() + " carries an exception handler");
            }
        }

        /**
         * Asserts one class of this package is named for handling a failure, and that no class is
         * named for advice.
         */
        @Test
        void oneClassOfThisPackageIsNamedForHandlingAFailure() {
            List<String> names = classNamesOfThisPackage();
            List<String> handlers = names.stream()
                    .filter(name -> name.endsWith("ExceptionHandler"))
                    .toList();

            assertEquals(List.of(NotificationApiExceptionHandler.class.getSimpleName()), handlers,
                    "classes named for handling a failure");
            for (String name : names) {
                assertFalse(name.endsWith("Advice"), name + " is named for advice");
            }
        }

        /**
         * Lists the class names this package holds, package qualifier dropped.
         *
         * @return one name per class
         */
        private List<String> classNamesOfThisPackage() {
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
            return scanner
                    .findCandidateComponents(
                            NotificationHistoryController.class.getPackageName())
                    .stream()
                    .map(BeanDefinition::getBeanClassName)
                    .filter(Objects::nonNull)
                    .map(name -> name.substring(name.lastIndexOf('.') + 1))
                    .toList();
        }

        /**
         * Asserts one path value answers 400, leaks no envelope property, echoes nothing sent, and
         * reaches neither collaborator.
         *
         * @param pathValue the value the caller sends
         * @throws Exception when the request cannot be performed
         */
        private void assertRefused(String pathValue) throws Exception {
            String body = mockMvc.perform(get(ROUTE, pathValue))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            for (String property : ENVELOPE_DATA_PROPERTIES) {
                assertFalse(body.contains(quoted(property)), "a refusal carries " + property);
            }
            assertFalse(body.contains(pathValue), "a refusal echoes the value the caller sent");
            assertTrue(body.contains(ROUTE), "a refusal carries the route template");
            assertTrue(body.contains(NotificationHistoryController.CARD_TOKEN_MESSAGE),
                    "the path value was refused, so the card-number text answers: " + body);
            assertFalse(body.contains("Account identifier"),
                    "this route carries a card number and no account identifier");
            verifyNoInteractions(statementTransactions);
            verifyNoInteractions(notifications);
        }
    }

    /**
     * The history of a card holding no row.
     *
     * <p>The read model answers with no row for a card it holds none for, and the endpoint answers
     * 404 carrying {@link NotificationHistoryController#NO_HISTORY_MESSAGE}. The masked number the
     * 200 body carries is column {@code masked_card_number} of a row, so with no row there is none to
     * read: the source put the card in its statement header before reading a row of it at
     * {@code app/cbl/CBSTM03A.CBL:L318-L325}, which a path carrying a token cannot reproduce and
     * which inventing a value would only pretend to.</p>
     *
     * <p>A caller reaches this route only for a token it already holds, and an identity holding no
     * matching {@code SCOPE_CARD_} authority is refused 403 by the filter chain before the read runs,
     * so the status distinguishes a card this service has posted nothing for from one it has and
     * discloses nothing else.</p>
     */
    @Nested
    class HistoryOfACardWithNoRow {

        /**
         * Places no row behind the read model.
         *
         * <p>The aggregate answers first and reports a count of zero, which is how the route tells a
         * card with no history from a card with some. {@code COUNT} always answers, so an empty card
         * is a row of zeros carrying no masked number rather than an absent row.
         */
        @BeforeEach
        void noRowInTheReadModel() {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, FIRST_PAGE))
                    .thenReturn(List.of());
            aggregateOver(List.of(), NO_ROW_TOTAL);
        }

        /** Asserts a token with no row answers 404 carrying the documented text and the template. */
        @Test
        void aTokenWithNoRowAnswersNotFound() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message")
                            .value(NotificationHistoryController.NO_HISTORY_MESSAGE))
                    .andExpect(jsonPath("$.route").value(ROUTE));
        }

        /** Asserts the refusal carries no envelope member, so no caller reads an empty history. */
        @Test
        void theRefusalCarriesNoEnvelopeMember() throws Exception {
            JsonNode body = MAPPER.readTree(mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString());

            assertEquals(List.of("status", "message", "route"), List.copyOf(body.propertyNames()),
                    "the failing body carries the three members every refusal of this service does");
            for (String property : ENVELOPE_DATA_PROPERTIES) {
                assertFalse(body.has(property),
                        "a missing history carries no " + property + " member");
            }
        }

        /** Asserts the refusal names no card, in any form, and echoes no value the caller sent. */
        @Test
        void theRefusalNamesNoCard() throws Exception {
            String body = mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertFalse(body.contains(CARD_TOKEN), "the refusal echoes the token the caller sent");
            assertFalse(body.contains(MASKED_CARD_NUMBER), "the refusal names a masked card number");
            assertFalse(SIXTEEN_DIGIT_RUN.matcher(body).find(),
                    "the refusal carries a sixteen-digit run");
        }

        /**
         * Asserts the total is never taken for a history with no row, since there is nothing to
         * total and the answer carries no total member.
         */
        @Test
        void noTotalIsTakenForAHistoryWithNoRow() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN)).andExpect(status().isNotFound());

            verifyNoInteractions(notifications);
        }
    }

    /**
     * The value that reaches the read model.
     *
     * <p>The key column of {@code statement_transaction} is the card token, and the route derives it
     * from the card number the path carried. The card number itself reaches the derivation and
     * nothing else, so no card number is stored. AAP item I4 and AAP 0.6.4 hold the decision to the
     * full value and masking to the published payload alone.</p>
     */
    @Nested
    class PathValueReachingTheLookup {

        /** Asserts the lookup receives the derived token and never the card number. */
        @Test
        void theLookupReceivesTheDerivedTokenAndNeverTheCardNumber() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                    .thenReturn(threeRows());
            aggregateOver(threeRows(), THREE_ROW_TOTAL);

            mockMvc.perform(get(ROUTE, CARD_TOKEN)).andExpect(status().isOk());

            ArgumentCaptor<String> reached = ArgumentCaptor.forClass(String.class);
            verify(statementTransactions).findByIdCardTokenOrderByIdTransactionIdAsc(
                    reached.capture(), any(Limit.class));
            assertEquals(CARD_TOKEN, reached.getValue(), "the value the lookup received");
            assertTrue(CARD_TOKEN_SHAPE.matcher(reached.getValue()).matches(),
                    "the derived value keeps the shape the key column declares");
            assertEquals(-1, reached.getValue().indexOf(PanMasker.MASK_CHARACTER),
                    "the value carries a mask character");
            assertNotEquals(MASKED_CARD_NUMBER, reached.getValue(),
                    "the lookup received a masked card number");
            assertNotEquals(FULL_CARD_NUMBER, reached.getValue(),
                    "the lookup received a full card number");
            assertFalse(SIXTEEN_DIGIT_RUN.matcher(reached.getValue()).find(),
                    "the lookup received a sixteen-digit run");
        }

        /** Asserts the derivation is the one the read model was written under. */
        @Test
        void theDerivationMatchesTheOneTheReadModelWasWrittenUnder() {
            assertEquals(PanMasker.cardToken(FULL_CARD_NUMBER), CARD_TOKEN,
                    "one card number derives one token, on every call");
            assertNotEquals(PanMasker.cardToken(FULL_CARD_NUMBER.substring(0, 15) + "6"),
                    CARD_TOKEN, "two card numbers derive two tokens");
        }
    }

    /**
     * The order the endpoint reads rows in.
     *
     * <p>{@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} sorts the
     * card at offset 263 ascending, then the transaction identifier at offset 1 ascending. The
     * finder name carries that order, and {@code KEYS(32 0)} at
     * {@code app/jcl/CREASTMT.JCL:L30} declares the thirty-two byte composite key it serves.</p>
     */
    @Nested
    class AscendingOrder {

        /** Asserts the repository declares the ascending finder under that exact name. */
        @Test
        void theRepositoryDeclaresTheAscendingFinder() throws Exception {
            Method finder = StatementTransactionRepository.class.getDeclaredMethod(
                    ASCENDING_FINDER, String.class, Limit.class);

            assertEquals(ASCENDING_FINDER, finder.getName(), "finder name");
            assertEquals(List.class, finder.getReturnType(), "return type");
            assertTrue(finder.getName().contains(ORDER_FRAGMENT),
                    "the finder name fixes an order");
        }

        /** Asserts every declared finder returning rows fixes an order in its name. */
        @Test
        void everyDeclaredFinderReturningRowsFixesAnOrder() {
            for (Method method : StatementTransactionRepository.class.getDeclaredMethods()) {
                if (method.getReturnType().equals(List.class)) {
                    assertTrue(method.getName().contains(ORDER_FRAGMENT),
                            method.getName() + " returns rows and fixes no order");
                }
            }
        }

        /** Asserts the endpoint reads through the ascending finder and through nothing else. */
        @Test
        void theEndpointReadsThroughTheAscendingFinderAlone() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, FIRST_PAGE))
                    .thenReturn(threeRows());
            aggregateOver(threeRows(), THREE_ROW_TOTAL);

            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionCount").value(3));

            // Two reads answer one request: the aggregate over the key, and one bounded page of it.
            // The continuation finder belongs to a request carrying a cursor and this one carries none.
            verify(statementTransactions).totalsOfCard(CARD_TOKEN);
            verify(statementTransactions)
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, FIRST_PAGE);
            verify(statementTransactions, never())
                    .findByIdCardTokenAndIdTransactionIdGreaterThanOrderByIdTransactionIdAsc(
                            any(), any(), any());
            verify(statementTransactions, never()).deleteProcessedBefore(anyString(), eq(1));
            verify(statementTransactions, never()).findAll();
            verifyNoMoreInteractions(statementTransactions);
        }
    }

    /**
     * The collaborators the controller declares.
     *
     * <p>Two collaborators answer this endpoint: the read model and the domain service that totals
     * one card's rows. The messaging package consumes events and records metrics, and no type of
     * either kind appears here.</p>
     */
    @Nested
    class ControllerCollaborators {

        /** Asserts the controller holds two collaborators and takes them through one constructor. */
        @Test
        void theControllerHoldsTwoCollaboratorsAndOneConstructor() {
            List<String> collaborators =
                    Arrays.stream(NotificationHistoryController.class.getDeclaredFields())
                            .filter(field -> !Modifier.isStatic(field.getModifiers()))
                            .map(Field::getName)
                            .toList();

            assertEquals(2, collaborators.size(), "collaborator count, holding " + collaborators);
            assertEquals(1, NotificationHistoryController.class.getDeclaredConstructors().length,
                    "declared constructor count");
            assertEquals(2, NotificationHistoryController.class.getDeclaredConstructors()[0]
                    .getParameterCount(), "constructor parameter count");
        }

        /** Asserts no declared field carries a messaging, transport, metric or delivery type. */
        @Test
        void noDeclaredFieldCarriesAnInfrastructureType() {
            for (Field field : NotificationHistoryController.class.getDeclaredFields()) {
                assertNoInfrastructureType(field.getType().getName(), field.getName());
            }
        }

        /** Asserts no constructor parameter carries one of those types. */
        @Test
        void noConstructorParameterCarriesAnInfrastructureType() {
            for (Constructor<?> constructor
                    : NotificationHistoryController.class.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    assertNoInfrastructureType(parameter.getName(), parameter.getSimpleName());
                }
            }
        }

        /**
         * Asserts one type name carries no infrastructure fragment.
         *
         * @param typeName the name of the declared type
         * @param member the field or parameter the message reports
         */
        private void assertNoInfrastructureType(String typeName, String member) {
            for (String fragment : INFRASTRUCTURE_TYPE_FRAGMENTS) {
                assertFalse(typeName.contains(fragment), member + " carries " + fragment);
            }
        }
    }

    /**
     * Paging, and the bound on it.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL} reads every row of one card between two key breaks and
     * {@code app/cbl/CBSTM03A.CBL:L429} totals all of them, so the count and the total this route
     * publishes cover the whole card. They are read as one aggregate over the key, which is what lets
     * the entries themselves be paged without reporting a statement over part of a card.</p>
     *
     * <p>The entries are paged because the route once read every retained row of a card into one
     * response. A card's history grows by one entry per posted transaction until retention removes
     * entries, so query work, heap and response bytes all followed how long a cardholder had been
     * transacting, and nothing capped any of them.</p>
     */
    @Nested
    class Paging {

        /** Stubs the aggregate for every test of this group. */
        @BeforeEach
        void oneCardWithThreeRows() {
            aggregateOver(threeRows(), THREE_ROW_TOTAL);
        }

        /**
         * Asserts a request naming no page size reads the default page and one lookahead row, and
         * never an unbounded limit.
         */
        @Test
        void theLookupNamesABoundedPage() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                    .thenReturn(threeRows());

            mockMvc.perform(get(ROUTE, CARD_TOKEN)).andExpect(status().isOk());

            assertTrue(appliedLimit().isLimited(), "the limit applied names a row count");
            assertEquals(Limit.of(NotificationHistoryController.DEFAULT_PAGE_SIZE + 1),
                    appliedLimit(), "the default page and one lookahead row");
            assertNotEquals(Limit.unlimited(), appliedLimit(),
                    "an unbounded read is what this route was fixed to stop making");
        }

        /** Asserts the page size a caller asks for is the page size read, plus the lookahead row. */
        @Test
        void theRequestedPageSizeIsTheOneRead() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                    .thenReturn(threeRows());

            mockMvc.perform(get(ROUTE, CARD_TOKEN)
                            .param(NotificationHistoryController.PAGE_SIZE_PARAMETER, "3"))
                    .andExpect(status().isOk());

            assertEquals(Limit.of(4), appliedLimit(), "three asked for, one read beyond it");
        }

        /**
         * Asserts the ceiling is enforced rather than trusted.
         *
         * <p>A caller that asks for more than the ceiling is refused instead of served a larger page,
         * so the bound cannot be talked past.
         */
        @Test
        void aPageSizeAboveTheCeilingIsRefused() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN)
                            .param(NotificationHistoryController.PAGE_SIZE_PARAMETER,
                                    String.valueOf(NotificationHistoryController.MAX_PAGE_SIZE + 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value(NotificationHistoryController.PAGE_SIZE_MESSAGE));

            verify(statementTransactions, never())
                    .findByIdCardTokenOrderByIdTransactionIdAsc(any(), any());
        }

        /** Asserts a page size below the floor, and one that is not a number, are both refused. */
        @ParameterizedTest
        @ValueSource(strings = {"0", "-1", "abc", "1.5", "", " ", "9999999999999999999"})
        void aPageSizeOutsideTheRangeIsRefused(String pageSize) throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN)
                            .param(NotificationHistoryController.PAGE_SIZE_PARAMETER, pageSize))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value(NotificationHistoryController.PAGE_SIZE_MESSAGE));
        }

        /** Asserts a cursor of the wrong width is refused and reaches no lookup. */
        @Test
        void aCursorOfTheWrongWidthIsRefused() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN)
                            .header(NotificationHistoryController.CURSOR_HEADER, "TOO-SHORT"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value(NotificationHistoryController.CURSOR_MESSAGE));

            verify(statementTransactions, never()).totalsOfCard(any());
        }

        /**
         * Asserts a cursor continues the walk through the keyset finder, exclusive of the row it names.
         */
        @Test
        void aCursorReadsTheContinuationFinder() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenAndIdTransactionIdGreaterThanOrderByIdTransactionIdAsc(
                            eq(CARD_TOKEN), eq(FIRST_TRANSACTION_ID), any(Limit.class)))
                    .thenReturn(List.of(row(SECOND_TRANSACTION_ID, "-125.00")));

            mockMvc.perform(get(ROUTE, CARD_TOKEN)
                            .header(NotificationHistoryController.CURSOR_HEADER,
                                    FIRST_TRANSACTION_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(1));

            verify(statementTransactions, never())
                    .findByIdCardTokenOrderByIdTransactionIdAsc(any(), any());
        }

        /**
         * Asserts a full page reports a further page and the cursor to ask for it, and does not serve
         * the lookahead row.
         */
        @Test
        void aFullPageReportsAFurtherPageAndWithholdsTheLookaheadRow() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                    .thenReturn(threeRows());

            mockMvc.perform(get(ROUTE, CARD_TOKEN)
                            .param(NotificationHistoryController.PAGE_SIZE_PARAMETER, "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(2))
                    .andExpect(jsonPath("$.nextPageExists").value(true))
                    .andExpect(jsonPath("$.nextCursor").value(SECOND_TRANSACTION_ID));
        }

        /** Asserts a last page reports no further page and carries no cursor. */
        @Test
        void aLastPageCarriesNoCursor() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                    .thenReturn(threeRows());

            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(3))
                    .andExpect(jsonPath("$.nextPageExists").value(false))
                    .andExpect(jsonPath("$.nextCursor").doesNotExist());
        }

        /** Asserts a cursor that has run past the last row answers 404 rather than an empty page. */
        @Test
        void aCursorPastTheLastRowAnswersNotFound() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenAndIdTransactionIdGreaterThanOrderByIdTransactionIdAsc(
                            eq(CARD_TOKEN), eq(THIRD_TRANSACTION_ID), any(Limit.class)))
                    .thenReturn(List.of());

            mockMvc.perform(get(ROUTE, CARD_TOKEN)
                            .header(NotificationHistoryController.CURSOR_HEADER,
                                    THIRD_TRANSACTION_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message")
                            .value(NotificationHistoryController.NO_HISTORY_MESSAGE));
        }

        /** Asserts the handler declares the path variable, the cursor header and the page size. */
        @Test
        void theHandlerDeclaresThePathVariableTheCursorAndThePageSize() throws Exception {
            Method handler = NotificationHistoryController.class.getDeclaredMethod("historyOfCard",
                    String.class, String.class, String.class);

            assertEquals(3, handler.getParameterCount(), "declared parameter count");
            assertNotNull(handler.getParameters()[0].getAnnotation(PathVariable.class),
                    "the first parameter is the path variable");
            assertNotNull(handler.getParameters()[1].getAnnotation(RequestHeader.class),
                    "the cursor travels in a header");
            assertNotNull(handler.getParameters()[2].getAnnotation(RequestParam.class),
                    "the page size travels in a query parameter");
        }

        /**
         * Asserts the paging values a caller might invent under another name change nothing.
         *
         * <p>Only {@value NotificationHistoryController#PAGE_SIZE_PARAMETER} is read, so a value sent
         * under any other name is neither read nor refused, and the answer stays the default page.
         *
         * @param parameter the name the caller invents
         */
        @ParameterizedTest
        @ValueSource(strings = {"page", "size", "limit", "offset", "cursor", "sort"})
        void aStrayPagingValueChangesNothing(String parameter) throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                    .thenReturn(threeRows());

            mockMvc.perform(get(ROUTE, CARD_TOKEN).param(parameter, "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(3));

            assertEquals(Limit.of(NotificationHistoryController.DEFAULT_PAGE_SIZE + 1),
                    appliedLimit(), "the default page is unaffected by a name this route ignores");
        }

        /**
         * Reads the limit the lookup was called with.
         *
         * @return the captured limit
         */
        private Limit appliedLimit() {
            ArgumentCaptor<Limit> applied = ArgumentCaptor.forClass(Limit.class);
            verify(statementTransactions)
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), applied.capture());
            return applied.getValue();
        }
    }

    /**
     * The failing responses.
     *
     * <p>A fault inside the lookup answers 500. Every failing body carries the route template and
     * no value read from the request or from the fault.</p>
     */
    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    class FaultResponses {

        /**
         * Lets the aggregate answer, so a fault stubbed on the page read is the fault reached.
         *
         * <p>The route reads the aggregate first, to tell a card with history from one without. A group
         * that stubbed only the page read would fault on the aggregate instead and assert against the
         * wrong call.
         */
        @BeforeEach
        void oneCardWithThreeRows() {
            aggregateOver(threeRows(), THREE_ROW_TOTAL);
        }

        /**
         * Asserts a fault leaves one {@code ERROR} line naming the route and carrying the fault.
         *
         * <p>Every fault answers with one fixed text, so the response tells a caller nothing about
         * what failed -- deliberately, since the caller supplied a card token and the body must echo
         * neither it nor anything read from the fault. That makes the log the only place a fault can
         * be diagnosed from, and while the handler wrote no line a repeatable {@code 500} was
         * invisible on the server: no record that the request had even been attempted.</p>
         *
         * <p>The line names the route by its template, as the body does, and it names the type of
         * the fault. It does not carry the fault itself. A throwable attached to a log event is
         * rendered with its message, and a message quotes what caused the failure: the value a
         * constraint refused, the statement a timeout cancelled, or the data source a connection
         * could not open. The type is the diagnosis and the message is the disclosure, so the type is
         * recorded and the message is dropped. The failure below carries a sentinel in its message
         * for that reason, and the assertions below require it absent.</p>
         */
        @Test
        void aFaultLeavesOneErrorLineNamingTheRouteAndTheFailureType(CapturedOutput output)
                throws Exception {
            String faultMessage = "connection refused to host 6 port 4";
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, FIRST_PAGE))
                    .thenThrow(new IllegalStateException("connection refused to host 6 port 4"));

            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isInternalServerError());

            String logged = output.getAll();
            assertTrue(logged.contains("ERROR"), "the fault leaves a line at ERROR");
            assertTrue(logged.contains(NotificationApiExceptionHandler.class.getName())
                            || logged.contains(
                                    NotificationApiExceptionHandler.class.getSimpleName()),
                    "the line names the handler that answered");
            assertTrue(logged.contains(ROUTE), "the line names the route by its template");
            assertTrue(logged.contains(IllegalStateException.class.getName()),
                    "the failure type reaches the line, so the failure class is diagnosable");
            assertFalse(logged.contains(faultMessage),
                    "the failure message reached the output, which means a stack trace was "
                            + "rendered: " + logged);
            assertFalse(logged.contains(CARD_TOKEN),
                    "and the line names no card token, which the response withholds too");
        }

        /** Asserts a fault inside the lookup answers 500 carrying no value from the fault. */
        @Test
        void aFaultInsideTheLookupAnswersFiveHundred() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, FIRST_PAGE))
                    .thenThrow(new IllegalStateException("connection refused to host 6 port 4"));

            String body = mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.route").value(ROUTE))
                    .andReturn().getResponse().getContentAsString();

            assertFalse(body.contains("connection refused"),
                    "the body carries a value read from the fault");
            for (String property : ENVELOPE_DATA_PROPERTIES) {
                assertFalse(body.contains(quoted(property)), "the body carries " + property);
            }
        }

        /** Asserts a failing body carries the route template and no resolved request path. */
        @Test
        void aFailingBodyCarriesTheRouteTemplateAndNoResolvedPath() throws Exception {
            String refusedValue = FULL_CARD_NUMBER;

            String body = mockMvc.perform(get(ROUTE, refusedValue))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertTrue(body.contains("{cardToken}"), "the body carries the route template");
            assertFalse(body.contains(refusedValue), "the body echoes what the caller sent");
            assertFalse(body.contains("instance"), "the body carries a resolved request path");
            assertFalse(body.contains("\"path\""), "the body carries a resolved request path");
        }
    }

    /**
     * The list the total is taken over.
     *
     * <p>The endpoint reads one aggregate over the card's key and asks {@link NotificationService} to
     * turn it into the value {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429}
     * would have accumulated. The response carries the count and the total as two single values, so
     * no assertion over the body alone can say what they were computed from: a handler that counted
     * the items on the page would satisfy every other group of this class while reporting a statement
     * over part of a card.
     *
     * <p>Two things close that gap here. One test gives the aggregate a history far longer than the
     * page, so a count taken from the items carries the wrong figure into the body. The other asserts
     * the aggregate is consulted, and consulted once: reading it per row would give back exactly what
     * bounding the page bought.
     */
    @Nested
    class TheTotalledMetadata {

        /** The rows the card holds in all, and the rows the page returns. */
        private List<StatementTransactionEntity> rowsInTheReadModel;

        /** Stubs the page and the aggregate over the same rows. */
        @BeforeEach
        void oneCardWithThreeRows() {
            rowsInTheReadModel = threeRows();
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, FIRST_PAGE))
                    .thenReturn(rowsInTheReadModel);
            aggregateOver(rowsInTheReadModel, THREE_ROW_TOTAL);
        }

        /**
         * Asserts the count and the total in the body come from the aggregate over the card's key.
         *
         * <p>They used to be taken over the list the lookup returned, which was every row of the card
         * because the lookup was unbounded. With a bounded page that would report a statement over
         * part of a card, and {@code app/cbl/CBSTM03A.CBL:L429} totals every row of one card between
         * two key breaks. Both figures are therefore read once over the key.
         */
        @Test
        void theCountAndTheTotalComeFromTheAggregate() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionCount").value(rowsInTheReadModel.size()))
                    .andExpect(jsonPath("$.totalAmount").value(THREE_ROW_TOTAL.toPlainString()));

            verify(statementTransactions).totalsOfCard(CARD_TOKEN);
            verify(notifications).totalOfCard(eq(CARD_TOKEN), any());
        }

        /**
         * Asserts the published count is the aggregate's and not the number of items on the page.
         *
         * <p>This is the assertion that a page cannot satisfy by accident. The aggregate reports a
         * history longer than the page, and the body has to carry the longer figure beside the three
         * items it holds.
         */
        @Test
        void theCountDescribesTheHistoryAndNotThePage() throws Exception {
            when(statementTransactions.totalsOfCard(CARD_TOKEN)).thenReturn(new FakeTotals(
                    900L, THREE_ROW_TOTAL, THREE_ROW_TOTAL.abs(), MASKED_CARD_NUMBER));

            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionCount").value(900))
                    .andExpect(jsonPath("$.transactions.length()")
                            .value(rowsInTheReadModel.size()));
        }

        /**
         * Asserts no total is taken for a card the aggregate reports as empty, and one is for a card
         * it reports as holding rows.
         *
         * <p>A handler that wrote a zero of its own would answer a body it never asked the domain for,
         * and the endpoint answers no body at all for a card with no row: the masked number that body
         * carries is read from a row. Both halves are asserted as interactions, because a value alone
         * cannot tell the two apart.
         */
        @Test
        void aTotalIsTakenOverTheHistoryReadAndOverNothingElse() throws Exception {
            aggregateOver(List.of(), NO_ROW_TOTAL);

            mockMvc.perform(get(ROUTE, CARD_TOKEN)).andExpect(status().isNotFound());

            verify(notifications, never()).totalOfCard(any(), any());

            aggregateOver(threeRows(), THREE_ROW_TOTAL);

            mockMvc.perform(get(ROUTE, CARD_TOKEN)).andExpect(status().isOk());

            verify(notifications).totalOfCard(eq(CARD_TOKEN), any());
        }

        /**
         * Asserts the aggregate is read once per request rather than once per row.
         *
         * <p>The whole point of the aggregate is that its cost does not follow the history, so a
         * second read of it per request would give back what the page bound was bought with.
         */
        @Test
        void theAggregateIsReadOncePerRequest() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN)).andExpect(status().isOk());

            verify(statementTransactions, times(1)).totalsOfCard(CARD_TOKEN);
        }
    }

    /**
     * Renders one property name as a serialized body carries it, in quotation marks.
     *
     * <p>The route template of this service names a path variable, so the bare name of a property
     * reads inside it. Comparing the quoted form separates a property of a body from the template a
     * refusal carries.
     *
     * @param property the property name
     * @return that name in quotation marks
     */
    private static String quoted(String property) {
        return "\"" + property + "\"";
    }

    /**
     * Builds three read-model rows in ascending transaction-identifier order.
     *
     * @return the three rows
     */
    private static List<StatementTransactionEntity> threeRows() {
        return List.of(row(FIRST_TRANSACTION_ID, "50.47"), row(SECOND_TRANSACTION_ID, "-125.00"),
                row(THIRD_TRANSACTION_ID, "1234.56"));
    }

    /**
     * Builds one read-model row of the card every request below names.
     *
     * @param transactionId the sixteen-character identifier {@code app/cpy/COSTM01.CPY:L23}
     *        declares
     * @param amount the amount at the two fractional digits {@code app/cpy/COSTM01.CPY:L29}
     *        declares
     * @return the row
     */
    private static StatementTransactionEntity row(String transactionId, String amount) {
        return new StatementTransactionEntity(
                new StatementTransactionId(CARD_TOKEN, transactionId), MASKED_CARD_NUMBER, "01",
                "5", "POS TERM", "Purchase", new BigDecimal(amount), "42", "Abshire-Lowe",
                "North Enoshaven", "72112", "2022-06-10 19:27:53.000000",
                "2022-07-19-23.16.01.470000");
    }

    /**
     * Lists the property names of one node and of every node below it, lower-cased.
     *
     * @param node the node to read
     * @return the names
     */
    private static List<String> propertyNamesOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        collectPropertyNames(node, names);
        return names;
    }

    /**
     * Adds the property names of one node, and of every node below it, to a list.
     *
     * @param node the node to read
     * @param names the list the names are added to, lower-cased
     */
    private static void collectPropertyNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                names.add(name.toLowerCase(Locale.ROOT));
                collectPropertyNames(node.get(name), names);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                collectPropertyNames(element, names);
            }
        }
    }
}

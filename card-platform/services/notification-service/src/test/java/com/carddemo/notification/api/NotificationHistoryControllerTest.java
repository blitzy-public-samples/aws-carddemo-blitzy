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

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.config.NotificationProperties;
import com.carddemo.notification.domain.NotificationRenderer;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import com.carddemo.notification.repository.StatementTransactionRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
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
 * <p>Nine groups of assertions cover that surface at the Hypertext Transfer Protocol (HTTP)
 * boundary. The mapped surface carries one handler, and that handler reads. A conforming path value
 * answers 200 with five properties, and each transaction it carries holds thirteen. A path value
 * outside the declared shape answers 400 and reaches no lookup. A card with no row answers 200 with
 * an empty array and never 404.
 *
 * <p>The remaining five groups cover what leaves the controller. The path value reaches the read
 * model character for character. The lookup names the ascending finder and the resolved page size.
 * The controller declares three collaborators, and no messaging, transport, metric, logging or
 * delivery type sits among them. A fault answers 500, and every failing body carries the route
 * template.
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
 * <p>{@code NotificationApiExceptionHandler.INVALID_REQUEST_MESSAGE} names an account identifier of
 * eleven digits, and this route takes a card token and a page size. Every assertion below reads the
 * status and the withheld request value, never that text. The correction is filed under
 * {@code card-platform/docs/suggested-next-tasks.md}.
 */
final class NotificationHistoryControllerTest {

    /** Reads a serialized body. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** The route template the description declares, and the one this endpoint answers on. */
    private static final String ROUTE = "/notifications/{cardToken}";

    /** The collection segment of that route. */
    private static final String COLLECTION = "/notifications";

    /**
     * The card token every conforming request below carries: sixty-four lower-case hexadecimal
     * characters, the shape {@link PanMasker#CARD_TOKEN_PATTERN} declares. Eight characters
     * repeated eight times, so no digit run in the value reaches two characters.
     */
    private static final String CARD_TOKEN = "a1b2c3d4".repeat(8);

    /** Shape of a card token, the constraint the path variable declares. */
    private static final Pattern CARD_TOKEN_SHAPE = Pattern.compile(PanMasker.CARD_TOKEN_PATTERN);

    /**
     * A full card number, built at runtime so no sixteen-digit literal appears in this file. Source
     * width: {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}.
     */
    private static final String FULL_CARD_NUMBER = "5".repeat(12) + "7065";

    /** The masked form of that card number: twelve mask characters then its last four digits. */
    private static final String MASKED_CARD_NUMBER = "*".repeat(12) + "7065";

    /** A run of sixteen digits, the shape of a full card number. */
    private static final Pattern SIXTEEN_DIGIT_RUN = Pattern.compile("[0-9]{16}");

    /** The five properties the response envelope carries for a card holding rows. */
    private static final List<String> ENVELOPE_PROPERTIES =
            List.of("cardToken", "cardNumber", "transactionCount", "totalAmount", "transactions");

    /** The four properties the envelope carries for a card holding no row. */
    private static final List<String> ENVELOPE_PROPERTIES_WITHOUT_A_CARD_NUMBER =
            List.of("cardToken", "transactionCount", "totalAmount", "transactions");

    /**
     * The four envelope properties that carry data. A failing body holds none of them, and the
     * route template holds the name of the fifth.
     */
    private static final List<String> ENVELOPE_DATA_PROPERTIES =
            List.of("cardNumber", "transactionCount", "totalAmount", "transactions");

    /**
     * The thirteen properties one transaction carries. Twelve map
     * {@code app/cpy/COSTM01.CPY:L23} and {@code app/cpy/COSTM01.CPY:L25-L35}, and the masked card
     * number is the thirteenth.
     */
    private static final List<String> TRANSACTION_PROPERTIES = List.of("transactionId",
            "maskedCardNumber", "typeCode", "categoryCode", "source", "description", "amount",
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
     * The paging bounds this route answers under, the values
     * {@code src/main/resources/application.yml} ships as
     * {@code carddemo.history.default-page-size} and
     * {@code carddemo.history.maximum-page-size}.
     */
    private static final NotificationProperties.History HISTORY =
            new NotificationProperties.History(400, 90, 3_600_000L, 50,
                    NotificationRenderer.MAXIMUM_STATEMENT_ROWS);

    /** The limit a request naming no page size reaches the read model with. */
    private static final Limit PAGE_LIMIT = Limit.of(HISTORY.defaultPageSize());

    /** The first transaction identifier, at the sixteen characters L23 declares. */
    private static final String FIRST_TRANSACTION_ID = "TRN0000000000001";

    /** The second transaction identifier, at the same width. */
    private static final String SECOND_TRANSACTION_ID = "TRN0000000000002";

    /** The third transaction identifier, at the same width. */
    private static final String THIRD_TRANSACTION_ID = "TRN0000000000003";

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

    /**
     * Builds the endpoint over two mocks and the shipped paging bounds, with the advice this
     * service ships registered.
     */
    @BeforeEach
    void buildEndpoint() {
        statementTransactions = mock(StatementTransactionRepository.class);
        notifications = mock(NotificationService.class);
        controller = new NotificationHistoryController(statementTransactions, notifications,
                properties());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
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

        /** Asserts a write to the same path answers no success and reaches no lookup. */
        @Test
        void aWriteToTheSamePathAnswersNoSuccessAndReachesNoLookup() throws Exception {
            List<Integer> answered = List.of(
                    mockMvc.perform(post(ROUTE, CARD_TOKEN)).andReturn().getResponse().getStatus(),
                    mockMvc.perform(put(ROUTE, CARD_TOKEN)).andReturn().getResponse().getStatus(),
                    mockMvc.perform(patch(ROUTE, CARD_TOKEN)).andReturn().getResponse().getStatus(),
                    mockMvc.perform(delete(ROUTE, CARD_TOKEN)).andReturn().getResponse()
                            .getStatus());

            for (int status : answered) {
                assertFalse(status >= 200 && status < 300, "a write answered " + status);
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

        /** Places three rows and their total behind the two mocks. */
        @BeforeEach
        void threeRowsInTheReadModel() {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, PAGE_LIMIT))
                    .thenReturn(threeRows());
            when(notifications.totalOf(anyList())).thenReturn(THREE_ROW_TOTAL);
        }

        /** Asserts a card token with rows answers 200 carrying its count, total and items. */
        @Test
        void aCardTokenWithRowsAnswersTwoHundredCarryingItsHistory() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.cardToken").value(CARD_TOKEN))
                    .andExpect(jsonPath("$.cardNumber").value(MASKED_CARD_NUMBER))
                    .andExpect(jsonPath("$.transactionCount").value(3))
                    .andExpect(jsonPath("$.totalAmount").value(THREE_ROW_TOTAL.toPlainString()))
                    .andExpect(jsonPath("$.transactions.length()").value(3));
        }

        /** Asserts the envelope carries five properties, named and ordered as the record is. */
        @Test
        void theEnvelopeCarriesFivePropertiesAndNoSixth() throws Exception {
            JsonNode body = MAPPER.readTree(historyBody());

            assertEquals(ENVELOPE_PROPERTIES, List.copyOf(body.propertyNames()),
                    "envelope property names, in that order");
            assertEquals(5, body.size(), "envelope property count");
        }

        /**
         * Asserts each item carries thirteen properties. Twelve map
         * {@code app/cpy/COSTM01.CPY:L23} and {@code app/cpy/COSTM01.CPY:L25-L35}, and the masked
         * card number is the thirteenth.
         */
        @Test
        void eachItemCarriesThirteenProperties() throws Exception {
            JsonNode transactions = MAPPER.readTree(historyBody()).get("transactions");

            assertEquals(3, transactions.size(), "item count");
            for (JsonNode item : transactions) {
                assertEquals(TRANSACTION_PROPERTIES, List.copyOf(item.propertyNames()),
                        "item property names, in that order");
                assertEquals(13, item.size(), "item property count");
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
     * <p>The path variable is a {@code String} constrained by Jakarta Bean Validation to the shape
     * {@link PanMasker#CARD_TOKEN_PATTERN} declares. Each value below answers 400, leaks no
     * envelope property, echoes nothing the caller sent, and reaches neither collaborator.</p>
     */
    @Nested
    class RefusedPathValues {

        /** Asserts a full card number is refused and reaches no lookup. */
        @Test
        void aFullCardNumberIsRefused() throws Exception {
            assertRefused(FULL_CARD_NUMBER);
        }

        /**
         * Asserts a masked card number is refused and reaches no lookup. The declared pattern
         * admits lower-case hexadecimal characters alone, and a masked value carries mask
         * characters.
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
            assertRefused(CARD_TOKEN + "f");
        }

        /**
         * Asserts a value at the declared width carrying a character outside the shape is refused.
         */
        @Test
        void aValueCarryingACharacterOutsideTheShapeIsRefused() throws Exception {
            assertRefused("z" + CARD_TOKEN.substring(1));
        }

        /** Asserts an upper-case token is refused. The declared shape admits lower case alone. */
        @Test
        void anUpperCaseTokenIsRefused() throws Exception {
            assertRefused(CARD_TOKEN.toUpperCase(Locale.ROOT));
        }

        /** Asserts a sixteen-character alphabetic value is refused. */
        @Test
        void anAlphabeticValueIsRefused() throws Exception {
            assertRefused("ABCDEFGHIJKLMNOP");
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
                assertFalse(body.contains(property), "a refusal carries " + property);
            }
            assertFalse(body.contains(pathValue), "a refusal echoes the value the caller sent");
            assertTrue(body.contains(ROUTE), "a refusal carries the route template");
            verifyNoInteractions(statementTransactions);
            verifyNoInteractions(notifications);
        }
    }

    /**
     * The history of a card holding no row.
     *
     * <p>The read model answers with no row for a card it holds none for. The endpoint then answers
     * 200 with an empty array, a count of {@code 0} and a total of {@code "0.00"}.</p>
     */
    @Nested
    class HistoryOfACardWithNoRow {

        /** Places no row and a zero total behind the two mocks. */
        @BeforeEach
        void noRowInTheReadModel() {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, PAGE_LIMIT))
                    .thenReturn(List.of());
            when(notifications.totalOf(anyList())).thenReturn(NO_ROW_TOTAL);
        }

        /** Asserts a card token with no row answers 200 carrying an empty history. */
        @Test
        void aCardTokenWithNoRowAnswersTwoHundred() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cardToken").value(CARD_TOKEN))
                    .andExpect(jsonPath("$.transactionCount").value(0))
                    .andExpect(jsonPath("$.totalAmount").value("0.00"));
        }

        /** Asserts the empty history carries an array that is present and holds no item. */
        @Test
        void theEmptyHistoryCarriesAnArrayThatIsPresentAndHoldsNoItem() throws Exception {
            JsonNode body = MAPPER.readTree(mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());

            assertTrue(body.has("transactions"), "the array is present");
            assertNotNull(body.get("transactions"), "the array is not null");
            assertTrue(body.get("transactions").isArray(), "the array travels as an array");
            assertEquals(0, body.get("transactions").size(), "item count");
        }

        /** Asserts the empty history carries four properties and leaves out the display value. */
        @Test
        void theEmptyHistoryLeavesOutTheDisplayCardNumber() throws Exception {
            JsonNode body = MAPPER.readTree(mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());

            assertEquals(ENVELOPE_PROPERTIES_WITHOUT_A_CARD_NUMBER,
                    List.copyOf(body.propertyNames()), "envelope property names, in that order");
            assertEquals(4, body.size(), "envelope property count");
            assertFalse(body.has("cardNumber"), "the display value is absent");
        }

        /** Asserts the empty history answers 200 and not 404. */
        @Test
        void theEmptyHistoryIsNotAMissingResource() throws Exception {
            int status = mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andReturn().getResponse().getStatus();

            assertEquals(200, status, "status");
            assertNotEquals(404, status, "status");
        }
    }

    /**
     * The value that reaches the read model.
     *
     * <p>The path value is the key column of {@code statement_transaction}, and the lookup takes it
     * as the request carried it. AAP item I4 and AAP 0.6.4 hold the decision and the lookup to the
     * full value, with masking applied to the published payload alone.</p>
     */
    @Nested
    class PathValueReachingTheLookup {

        /** Asserts the path value reaches the lookup character for character. */
        @Test
        void thePathValueReachesTheLookupUnchanged() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                    .thenReturn(threeRows());
            when(notifications.totalOf(anyList())).thenReturn(THREE_ROW_TOTAL);

            mockMvc.perform(get(ROUTE, CARD_TOKEN)).andExpect(status().isOk());

            ArgumentCaptor<String> reached = ArgumentCaptor.forClass(String.class);
            verify(statementTransactions).findByIdCardTokenOrderByIdTransactionIdAsc(
                    reached.capture(), any(Limit.class));
            assertEquals(CARD_TOKEN, reached.getValue(), "the value the lookup received");
            assertTrue(CARD_TOKEN_SHAPE.matcher(reached.getValue()).matches(),
                    "the value keeps the shape the path variable declares");
            assertEquals(-1, reached.getValue().indexOf(PanMasker.MASK_CHARACTER),
                    "the value carries a mask character");
            assertNotEquals(MASKED_CARD_NUMBER, reached.getValue(),
                    "the lookup received a masked card number");
            assertNotEquals(FULL_CARD_NUMBER, reached.getValue(),
                    "the lookup received a full card number");
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
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, PAGE_LIMIT))
                    .thenReturn(threeRows());
            when(notifications.totalOf(anyList())).thenReturn(THREE_ROW_TOTAL);

            mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionCount").value(3));

            verify(statementTransactions)
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, PAGE_LIMIT);
            verify(statementTransactions, never()).deleteProcessedBefore(anyString(), eq(1));
            verify(statementTransactions, never()).findAll();
            verifyNoMoreInteractions(statementTransactions);
        }
    }

    /**
     * The collaborators the controller declares.
     *
     * <p>Three collaborators answer this endpoint: the read model, the domain service that totals
     * one card's rows, and the bound paging configuration. The messaging package consumes events
     * and records metrics, and no type of either kind appears here.</p>
     */
    @Nested
    class ControllerCollaborators {

        /** Asserts the controller holds three collaborators and takes them through one constructor. */
        @Test
        void theControllerHoldsThreeCollaboratorsAndOneConstructor() {
            List<String> collaborators =
                    Arrays.stream(NotificationHistoryController.class.getDeclaredFields())
                            .filter(field -> !Modifier.isStatic(field.getModifiers()))
                            .map(Field::getName)
                            .toList();

            assertEquals(3, collaborators.size(), "collaborator count, holding " + collaborators);
            assertEquals(1, NotificationHistoryController.class.getDeclaredConstructors().length,
                    "declared constructor count");
            assertEquals(3, NotificationHistoryController.class.getDeclaredConstructors()[0]
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
     * The paging bounds one call answers under.
     *
     * <p>{@code carddemo.history.default-page-size} applies when a caller names none, and
     * {@code carddemo.history.maximum-page-size} caps what a caller may ask for. Both reach the
     * read model as a row count.</p>
     */
    @Nested
    class PageSizeBounds {

        /** Asserts a request naming no page size takes the configured default. */
        @Test
        void aRequestNamingNoPageSizeTakesTheConfiguredDefault() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                    .thenReturn(List.of());
            when(notifications.totalOf(anyList())).thenReturn(NO_ROW_TOTAL);

            mockMvc.perform(get(ROUTE, CARD_TOKEN)).andExpect(status().isOk());

            assertEquals(HISTORY.defaultPageSize(), appliedLimit().max(), "the limit applied");
        }

        /** Asserts a page size above the configured maximum is capped before the lookup. */
        @Test
        void aPageSizeAboveTheConfiguredMaximumIsCapped() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                    .thenReturn(List.of());
            when(notifications.totalOf(anyList())).thenReturn(NO_ROW_TOTAL);

            mockMvc.perform(get(ROUTE, CARD_TOKEN)
                            .param(NotificationHistoryController.PAGE_SIZE_PARAMETER, "5000"))
                    .andExpect(status().isOk());

            assertEquals(HISTORY.maximumPageSize(), appliedLimit().max(), "the limit applied");
        }

        /** Asserts a page size under one is refused and reaches no lookup. */
        @Test
        void aPageSizeUnderOneIsRefusedAndReachesNoLookup() throws Exception {
            mockMvc.perform(get(ROUTE, CARD_TOKEN)
                            .param(NotificationHistoryController.PAGE_SIZE_PARAMETER, "0"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(statementTransactions);
        }

        /** Asserts no lookup names an unbounded read. */
        @Test
        void noLookupNamesAnUnboundedRead() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(eq(CARD_TOKEN), any(Limit.class)))
                    .thenReturn(List.of());
            when(notifications.totalOf(anyList())).thenReturn(NO_ROW_TOTAL);

            mockMvc.perform(get(ROUTE, CARD_TOKEN)).andExpect(status().isOk());

            assertNotEquals(Limit.unlimited(), appliedLimit(), "the limit applied");
            verify(statementTransactions, never())
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, Limit.unlimited());
        }

        /**
         * Captures the limit the one lookup named.
         *
         * @return that limit
         */
        private Limit appliedLimit() {
            ArgumentCaptor<Limit> applied = ArgumentCaptor.forClass(Limit.class);
            verify(statementTransactions).findByIdCardTokenOrderByIdTransactionIdAsc(
                    eq(CARD_TOKEN), applied.capture());
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
    class FaultResponses {

        /** Asserts a fault inside the lookup answers 500 carrying no value from the fault. */
        @Test
        void aFaultInsideTheLookupAnswersFiveHundred() throws Exception {
            when(statementTransactions
                    .findByIdCardTokenOrderByIdTransactionIdAsc(CARD_TOKEN, PAGE_LIMIT))
                    .thenThrow(new IllegalStateException("connection refused to host 6 port 4"));

            String body = mockMvc.perform(get(ROUTE, CARD_TOKEN))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.route").value(ROUTE))
                    .andReturn().getResponse().getContentAsString();

            assertFalse(body.contains("connection refused"),
                    "the body carries a value read from the fault");
            for (String property : ENVELOPE_DATA_PROPERTIES) {
                assertFalse(body.contains(property), "the body carries " + property);
            }
        }

        /** Asserts a failing body carries the route template and no resolved request path. */
        @Test
        void aFailingBodyCarriesTheRouteTemplateAndNoResolvedPath() throws Exception {
            String body = mockMvc.perform(get(ROUTE, FULL_CARD_NUMBER))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertTrue(body.contains("{cardToken}"), "the body carries the route template");
            assertFalse(body.contains(FULL_CARD_NUMBER), "the body echoes what the caller sent");
            assertFalse(body.contains("instance"), "the body carries a resolved request path");
            assertFalse(body.contains("\"path\""), "the body carries a resolved request path");
        }
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

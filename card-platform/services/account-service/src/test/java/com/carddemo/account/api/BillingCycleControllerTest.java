package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.carddemo.account.api.dto.CycleCloseResponse;
import com.carddemo.account.domain.BillingCycleService;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.cobol.PicClause;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Web-layer tests for {@link BillingCycleController}, driving the one route it declares and reading
 * each answer off the wire.
 *
 * <p>{@code POST /accounts/{accountId}/cycle-close} reproduces two statements of paragraph
 * {@code 1050-UPDATE-ACCOUNT} at {@code app/cbl/CBACT04C.cbl:L350}:
 * {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} at {@code app/cbl/CBACT04C.cbl:L353} and
 * {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at {@code app/cbl/CBACT04C.cbl:L354}. These tests measure
 * what the route answers: which properties arrive, in what order, in what form, and which never
 * arrive at all.
 *
 * <p>The dispatcher is built over the controller and the real {@link AccountApiExceptionHandler}, so
 * request mapping, path-variable binding and serialization are the running ones. The one
 * collaborator is stubbed. No application context, no database and no broker takes part, and one
 * command runs this class on a clean machine.
 *
 * <p>Both accumulators feed one authorization rule. {@code app/cbl/CBTRN02C.cbl:L403-L405} computes
 * a working balance from cycle credit minus cycle debit plus the transaction amount.
 * {@code app/cbl/CBTRN02C.cbl:L407} compares that working balance with the credit limit, and
 * {@code app/cbl/CBTRN02C.cbl:L410-L412} assigns reject reason 102. The rule belongs to the
 * authorization service, and no test here asserts it.
 *
 * <p>Nothing here restates a sibling's contract. {@code domain/BillingCycleServiceTest} owns the
 * stored row, the stored balance and the event row that commits with it.
 * {@code api/dto/AccountMoneyWireFormTest} owns the refusals of the response record, and
 * {@code repository/SchemaColumnTypeTest} owns every schema-level claim.
 *
 * <p>The finding that the only source code returning both accumulators to zero sits in a program
 * this engagement leaves batch: {@code card-platform/docs/business-rule-flags.md}. The mapping from
 * the three source fields to the three response components:
 * {@code card-platform/docs/traceability-matrix.md}. The divergence measured here, reproducing
 * {@code app/cbl/CBACT04C.cbl:L353-L354} and omitting the balance add at
 * {@code app/cbl/CBACT04C.cbl:L352}: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("the cycle-close surface")
class BillingCycleControllerTest {

    /**
     * An account identifier of {@code app/data/ASCII/acctdata.txt}, eleven digits wide.
     *
     * <p>{@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} is numeric display, so the ten
     * leading zeros belong to the value.
     */
    private static final String ACCOUNT_ID = "00000000050";

    /**
     * A balance the stored row carries, {@code ACCT-CURR-BAL PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L7}. No answer of this route carries the figure.
     */
    private static final String STORED_BALANCE = "1010.00";

    /**
     * The three properties an answer carries, in the order {@link CycleCloseResponse} declares them.
     *
     * <ul>
     *   <li>{@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}</li>
     *   <li>{@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L13}</li>
     *   <li>{@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L14}</li>
     * </ul>
     */
    private static final List<String> ANSWER_PROPERTIES_IN_DECLARATION_ORDER =
            List.of("accountId", "currentCycleCredit", "currentCycleDebit");

    /**
     * Property names no answer of this route carries: three spellings of a balance, three of an
     * interest figure, a customer identifier, three message slots, and two card fields.
     *
     * <p>{@code 01 ACCOUNT-RECORD} declares twelve fields at {@code app/cpy/CVACT01Y.cpy:L5-L16},
     * and no customer identifier sits among them. Interest accrual stays batch, and this module
     * holds no card data of any kind.
     */
    private static final List<String> PROPERTIES_THE_ANSWER_OMITS = List.of(
            "currentBalance", "balance", "newBalance",
            "interest", "totalInterest", "monthlyInterest",
            "customerId",
            "message", "info", "returnMessage",
            "cardNumber", "cardVerificationValue");

    /**
     * Zero at the scale {@code ACCT-CURR-CYC-CREDIT} declares, from {@link PicClause}. The field is
     * {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L13}.
     */
    private static final String ZEROED_CREDIT =
            BigDecimal.ZERO.setScale(PicClause.ACCT_CURR_CYC_CREDIT_SCALE).toPlainString();

    /**
     * Zero at the scale {@code ACCT-CURR-CYC-DEBIT} declares, from {@link PicClause}. The field is
     * {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    private static final String ZEROED_DEBIT =
            BigDecimal.ZERO.setScale(PicClause.ACCT_CURR_CYC_DEBIT_SCALE).toPlainString();

    /** Packages holding a type that could publish an event. */
    private static final List<String> PUBLISHING_PACKAGES = List.of(
            "com.carddemo.account.outbox", "com.carddemo.account.messaging",
            "com.carddemo.account.config", "com.carddemo.events");

    /** Package prefix every metric instrument carries. */
    private static final String METRIC_PACKAGE = "io.micrometer";

    private static final ObjectMapper JSON = new ObjectMapper();

    private BillingCycleService billingCycles;
    private MockMvc mockMvc;

    @BeforeEach
    void buildSlice() {
        billingCycles = mock(BillingCycleService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new BillingCycleController(billingCycles))
                .setControllerAdvice(new AccountApiExceptionHandler())
                .build();
    }

    /**
     * A close answers exactly three properties, in the order the response record declares
     * them.
     *
     * <p>The three are {@code ACCT-ID} at {@code app/cpy/CVACT01Y.cpy:L5},
     * {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13} and
     * {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14}. Each name is located in the
     * raw answer, and the positions strictly increase.
     */
    @Test
    void aCloseAnswersExactlyThreePropertiesInDeclarationOrder() throws Exception {
        stubClose(Optional.of(closedAccount()));

        MvcResult result = closeCycle();
        String wire = result.getResponse().getContentAsString();
        JsonNode answer = JSON.readTree(wire);

        assertAll("the answer carries the identifier and the two accumulators a close zeroes",
                () -> assertEquals(200, result.getResponse().getStatus(),
                        "a stored row answers 200"),
                () -> assertEquals(3, answer.size(), "three properties arrive and no fourth"),
                () -> assertEquals(ANSWER_PROPERTIES_IN_DECLARATION_ORDER,
                        List.copyOf(answer.propertyNames()),
                        "and they are the three the record declares"),
                () -> assertEquals(ANSWER_PROPERTIES_IN_DECLARATION_ORDER,
                        componentNamesOf(CycleCloseResponse.class),
                        "which is the order the components sit in"),
                () -> assertPositionsIncrease(wire));
    }

    /**
     * Both accumulators arrive at zero, each at the scale its source field declares.
     *
     * <p>{@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} at {@code app/cbl/CBACT04C.cbl:L353} zeroes the
     * first of the two. {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at
     * {@code app/cbl/CBACT04C.cbl:L354} zeroes the second. {@code PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L13} and at {@code app/cpy/CVACT01Y.cpy:L14} declares two
     * fractional digits, and each figure reaches the wire as a decimal string.
     */
    @Test
    void bothAccumulatorsArriveAtZeroAtTheScaleTheirFieldsDeclare() throws Exception {
        stubClose(Optional.of(closedAccount()));

        JsonNode answer = JSON.readTree(closeCycle().getResponse().getContentAsString());

        assertAll("app/cbl/CBACT04C.cbl:L353-L354 leaves both accumulators at zero",
                () -> assertEquals(ZEROED_CREDIT, answer.get("currentCycleCredit").stringValue(),
                        "app/cbl/CBACT04C.cbl:L353 zeroes ACCT-CURR-CYC-CREDIT"),
                () -> assertEquals(ZEROED_DEBIT, answer.get("currentCycleDebit").stringValue(),
                        "app/cbl/CBACT04C.cbl:L354 zeroes ACCT-CURR-CYC-DEBIT"),
                () -> assertEquals(ACCOUNT_ID, answer.get("accountId").stringValue(),
                        "the identifier keeps the ten leading zeros of ACCT-ID PIC 9(11)"));
    }

    /**
     * An answer carries no balance, no interest figure, no customer identifier, no message
     * slot and no card field.
     *
     * <p>The balance add at {@code app/cbl/CBACT04C.cbl:L352} has no counterpart in this module. No
     * component holds {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy:L7}, and the stored
     * figure reaches no answer. Each omitted name is measured twice, once against the declaration of
     * {@link CycleCloseResponse} and once against the raw answer.
     */
    @Test
    void theAnswerCarriesNoBalanceNoInterestAndNoMessageSlot() throws Exception {
        stubClose(Optional.of(closedAccount()));

        List<String> declared = componentNamesOf(CycleCloseResponse.class);
        String wire = closeCycle().getResponse().getContentAsString();

        for (String omitted : PROPERTIES_THE_ANSWER_OMITS) {
            assertFalse(declared.contains(omitted), "no component is named " + omitted);
            assertFalse(wire.contains('"' + omitted + '"'),
                    "and no property of the answer is named " + omitted);
        }
        assertFalse(wire.contains(STORED_BALANCE),
                "the stored balance of app/cpy/CVACT01Y.cpy:L7 reaches no answer");
    }

    /**
     * One request reaches {@link BillingCycleService} exactly once. The service is stubbed here, so
     * this measures the delegation and not the transaction or the outbox row that
     * {@code BillingCycleServiceTest} covers.
     */
    @Test
    void aCloseDelegatesExactlyOnceToTheServiceThatOwnsTheWrite() throws Exception {
        stubClose(Optional.of(closedAccount()));

        closeCycle();

        verify(billingCycles, times(1)).closeBillingCycle(ACCOUNT_ID);
        verifyNoMoreInteractions(billingCycles);
    }

    /**
     * A close on an identifier no row carries answers 404 and no payload property.
     *
     * <p>The service answers an empty {@link Optional}, which stands for the file-status test at
     * {@code app/cbl/CBACT04C.cbl:L357}. The answer names none of the three properties a close
     * carries, and it repeats no submitted identifier. The one delegation still happens: the service
     * is what reports the miss.
     */
    @Test
    void anIdentifierNoRowCarriesAnswersNotFoundAndNoPayloadProperty() throws Exception {
        stubClose(Optional.empty());

        MvcResult result = closeCycle();
        String wire = result.getResponse().getContentAsString();

        assertEquals(404, result.getResponse().getStatus(), "no row answers 404");
        for (String property : ANSWER_PROPERTIES_IN_DECLARATION_ORDER) {
            assertFalse(wire.contains('"' + property + '"'),
                    "an answer to a miss carries no " + property);
        }
        assertFalse(wire.contains(ACCOUNT_ID), "and it repeats no submitted identifier");
        verify(billingCycles, times(1)).closeBillingCycle(ACCOUNT_ID);
        verifyNoMoreInteractions(billingCycles);
    }

    /**
     * No declared field of the controller could publish an event.
     *
     * <p>Request handling reaches no publisher, no outbox writer and no event type.
     * {@link BillingCycleService} writes the one outbox row a close produces, inside the transaction
     * it opens. The publication path itself: {@code card-platform/docs/event-flow.md}.
     */
    @Test
    void noDeclaredFieldCouldPublish() {
        List<String> reachable = declaredFieldTypesOfController();

        assertAll("nothing that publishes is reachable from a request",
                PUBLISHING_PACKAGES.stream().map(banned -> () -> assertTrue(
                        reachable.stream().noneMatch(type -> type.startsWith(banned + ".")),
                        "no declared field has a type in " + banned)));
    }

    /**
     * The controller declares no error-handler method and no metric instrument, and holds
     * one collaborator.
     *
     * <p>{@link AccountApiExceptionHandler} carries the error path of the whole module, and
     * {@code config/ObservabilityConfig} carries every counter, among them the one each committed
     * close increments. {@link BillingCycleController} declares neither.
     */
    @Test
    void theControllerDeclaresNoErrorHandlerAndNoMetricInstrument() {
        List<String> handlers = Arrays.stream(BillingCycleController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                .map(Method::getName)
                .toList();
        List<String> fieldTypes = declaredFieldTypesOfController();

        assertAll("one collaborator, and no error path or counter of its own",
                () -> assertEquals(List.of(), handlers,
                        "no method of the controller handles an exception"),
                () -> assertTrue(fieldTypes.stream()
                                .noneMatch(type -> type.startsWith(METRIC_PACKAGE + ".")),
                        "no declared field is a metric instrument"),
                () -> assertTrue(fieldTypes.contains(BillingCycleService.class.getName()),
                        "the one collaborator is the cycle-close service"));
    }

    /**
     * Both routes carrying an account identifier pin one width.
     *
     * <p>{@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} fixes that width, and
     * {@code api/AccountRouteWiringTest} holds the pattern itself. An identifier of another width
     * matches no stored row.
     */
    @Test
    void bothRoutesCarryingAnAccountIdentifierPinOneWidth() {
        assertEquals(AccountController.ACCOUNT_ID_PATTERN,
                BillingCycleController.ACCOUNT_ID_PATTERN,
                "one width for one identifier, on both routes that carry it");
        assertTrue(ACCOUNT_ID.matches(BillingCycleController.ACCOUNT_ID_PATTERN),
                "eleven digits with leading zeros is the shape a row carries");
    }

    /**
     * Stubs the cycle-close service to answer one outcome for {@link #ACCOUNT_ID}.
     *
     * @param outcome the stored account after a close, or an empty {@link Optional} for a miss
     */
    private void stubClose(Optional<AccountEntity> outcome) {
        when(billingCycles.closeBillingCycle(ACCOUNT_ID)).thenReturn(outcome);
    }

    /**
     * Closes the billing cycle of {@link #ACCOUNT_ID} over the dispatcher.
     *
     * <p>The media type is required even though no body is sent. It is the cross-site request
     * forgery control of this route: {@code application/json} is not one of the three content types
     * a browser can send cross-origin without a preflight, so requiring it forces one, and this
     * service answers no preflight. A call omitting it reads 415, which
     * {@link #aFormEncodedCloseIsRefusedWithoutReachingTheService()} and
     * {@link #aCloseNamingNoMediaTypeReadsUnsupportedMediaType()} both assert.
     *
     * @return the result, carrying the answer
     * @throws Exception when the request fails
     */
    private MvcResult closeCycle() throws Exception {
        return mockMvc.perform(post("/accounts/{accountId}/cycle-close", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    /**
     * A close naming no media type is refused by the route rather than performed.
     *
     * <p>This is the standalone half of the control. The security chain is not in this setup, so a
     * form-encoded call is refused here by the route's own {@code consumes}; the chain's 403 for the
     * same call is asserted in the security integration test.
     *
     * @throws Exception when the request fails
     */
    @Test
    @DisplayName("a close naming no media type reads 415 and reaches no service")
    void aCloseNamingNoMediaTypeReadsUnsupportedMediaType() throws Exception {
        stubClose(Optional.of(closedAccount()));

        MvcResult result = mockMvc
                .perform(post("/accounts/{accountId}/cycle-close", ACCOUNT_ID))
                .andReturn();

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), result.getResponse().getStatus(),
                "a bodyless POST still has to name the media type this route accepts");
        verify(billingCycles, never()).closeBillingCycle(anyString());
    }

    /**
     * A form-encoded close is refused without the accumulators being touched.
     *
     * <p>The forged shape. A browser driven from another origin can send exactly this, carrying the
     * credential it holds for this origin, and the accumulators it would zero are what the
     * credit-limit rule tests.
     *
     * @throws Exception when the request fails
     */
    @Test
    @DisplayName("a form-encoded close is refused and the accumulators are not touched")
    void aFormEncodedCloseIsRefusedWithoutReachingTheService() throws Exception {
        stubClose(Optional.of(closedAccount()));

        MvcResult result = mockMvc
                .perform(post("/accounts/{accountId}/cycle-close", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andReturn();

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), result.getResponse().getStatus(),
                "a form-encoded POST must not reach the service that zeroes the accumulators");
        verify(billingCycles, never()).closeBillingCycle(anyString());
    }

    /**
     * Asserts the three property names sit at strictly increasing positions in the raw answer.
     *
     * @param wire the answer as it arrived
     */
    private static void assertPositionsIncrease(String wire) {
        int previous = -1;
        for (String property : ANSWER_PROPERTIES_IN_DECLARATION_ORDER) {
            int position = wire.indexOf('"' + property + '"');
            assertTrue(position > previous,
                    property + " follows the property the record declares before it");
            previous = position;
        }
    }

    /**
     * Reads the type name of every field the controller declares.
     *
     * @return the type names, in declaration order
     */
    private static List<String> declaredFieldTypesOfController() {
        return Arrays.stream(BillingCycleController.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName)
                .toList();
    }

    /**
     * Reads the component names of one record.
     *
     * @param type the record type
     * @return the component names, in the order the record declares them
     */
    private static List<String> componentNamesOf(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /**
     * @return the stored row a close answers with
     */
    private static AccountEntity closedAccount() {
        AccountEntity closed = new AccountEntity();
        closed.setAccountId(ACCOUNT_ID);
        closed.setCurrentBalance(new BigDecimal(STORED_BALANCE));
        closed.setCurrentCycleCredit(new BigDecimal(ZEROED_CREDIT));
        closed.setCurrentCycleDebit(new BigDecimal(ZEROED_DEBIT));
        return closed;
    }
}

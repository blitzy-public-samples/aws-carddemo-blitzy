package com.carddemo.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.carddemo.events.EventEnvelope;
import com.carddemo.ledger.api.BalanceQueryController.AccountBalance;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import jakarta.validation.ConstraintViolationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Asserts what a balance query answers when it succeeds, and what it answers when the projection
 * table holds no such account.
 *
 * <p>{@link LedgerApiExceptionHandlerTest} drives the same route for its three failing answers, so
 * this class covers what that one does not: the {@code 200} body, the mapping of each stored column
 * onto its own response component, the {@code 404}, and the two handler arms no request through this
 * route can reach.
 *
 * <p><b>Why the body is four strings and not four numbers.</b> Every amount here is
 * {@code PIC S9(10)V99} in the source — {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy:L7},
 * {@code ACCT-CURR-CYC-CREDIT} at {@code :L13} and {@code ACCT-CURR-CYC-DEBIT} at {@code :L14} —
 * and column {@code NUMERIC(12,2)} keeps that scale. A JSON number deserializes into a binary
 * floating-point double in most clients, which is the one representation this platform's arithmetic
 * argument rules out, so each amount travels as a decimal string carrying exactly two places. The
 * account identifier travels as a string for a different reason: {@code ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT01Y.cpy:L5} is numeric display and row 1 of {@code app/data/ASCII/acctdata.txt}
 * is {@code 00000000001}, ten leading zeros a number would discard.
 *
 * <p>Every stored value below carries a different amount, so a component reading from the wrong
 * column fails here rather than passing on equal values.
 *
 * <p>No database, no broker and no Spring context take part. The repository is stubbed and the route
 * is stood up with the advice the running service registers.
 */
@DisplayName("What a balance query answers when it finds a row, and when it does not")
final class BalanceQueryControllerTest {

    /** Route of one account's balance. */
    private static final String ROUTE = "/balances/{accountId}";

    /**
     * The account identifier of seeded row 1, eleven characters carrying ten leading zeros.
     * {@code V2__seed.sql} loads it from {@code app/data/ASCII/acctdata.txt}.
     */
    private static final String ACCOUNT = "00000000001";

    /** An eleven-digit identifier the stub holds no row for. */
    private static final String ABSENT_ACCOUNT = "00000099999";

    /** The stored balance, distinct from both accumulators. */
    private static final BigDecimal STORED_BALANCE = new BigDecimal("194.00");

    /** The stored cycle credit, distinct from the balance and the debit. */
    private static final BigDecimal STORED_CYCLE_CREDIT = new BigDecimal("150.25");

    /**
     * The stored cycle debit, negative and distinct from the other two.
     * {@code app/cbl/CBTRN02C.cbl:L551} adds a negative amount to this accumulator, so the column
     * holds a signed value and the rendered component carries a minus sign.
     */
    private static final BigDecimal STORED_CYCLE_DEBIT = new BigDecimal("-75.50");

    /** The four components the answer declares, and no fifth. */
    private static final List<String> DECLARED_COMPONENTS =
            List.of("accountId", "currentBalance", "cycleCredit", "cycleDebit");

    /** The shape every amount serializes as: at most ten digits, then exactly two. */
    private static final String MONEY_PATTERN = "^-?\\d{1,10}\\.\\d{2}$";

    /** Reads one response body. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The stubbed projection store. */
    private AccountBalanceProjectionRepository balances;

    /** The route under test, with the advice registered as the running service registers it. */
    private MockMvc mockMvc;

    /** Stands the controller and the advice up over a stubbed repository. */
    @BeforeEach
    void standUpRoute() {
        balances = mock(AccountBalanceProjectionRepository.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BalanceQueryController(balances))
                .setControllerAdvice(new LedgerApiExceptionHandler())
                .setValidator(new LocalValidatorFactoryBean())
                .build();
    }

    @Nested
    @DisplayName("A stored account answers 200 carrying four values")
    class StoredAccount {

        @Test
        @DisplayName("the four components carry the stored values, the identifier keeping its zeros")
        void theFourComponentsCarryTheStoredValues() throws Exception {
            when(balances.findById(ACCOUNT)).thenReturn(Optional.of(storedRow()));

            Map<String, Object> body = okBody();

            assertEquals(DECLARED_COMPONENTS.size(), body.size(),
                    "the answer carries the four declared components and no fifth: " + body);
            assertEquals(ACCOUNT, body.get("accountId"),
                    "the identifier keeps the ten leading zeros ACCT-ID PIC 9(11) holds");
            assertEquals("194.00", body.get("currentBalance"),
                    "the balance comes from current_balance");
            assertEquals("150.25", body.get("cycleCredit"),
                    "the cycle credit comes from cycle_credit");
            assertEquals("-75.50", body.get("cycleDebit"),
                    "the cycle debit comes from cycle_debit, sign kept");
            verify(balances).findById(ACCOUNT);
            verifyNoMoreInteractions(balances);
        }

        @Test
        @DisplayName("each amount is a decimal string at two places, never a JSON number")
        void eachAmountIsADecimalStringAtTwoPlaces() throws Exception {
            when(balances.findById(ACCOUNT)).thenReturn(Optional.of(storedRow()));

            String rendered = perform(get(ROUTE, ACCOUNT)).getResponse().getContentAsString();
            Map<String, Object> body = MAPPER.readValue(rendered,
                    new TypeReference<Map<String, Object>>() { });

            for (String component : DECLARED_COMPONENTS) {
                assertTrue(body.get(component) instanceof String,
                        component + " is rendered as a string, so no client parses it into a "
                                + "binary floating-point double: " + rendered);
            }
            for (String amount : List.of("currentBalance", "cycleCredit", "cycleDebit")) {
                assertTrue(String.valueOf(body.get(amount)).matches(MONEY_PATTERN),
                        amount + " carries exactly two fractional digits: " + body.get(amount));
            }
            assertFalse(rendered.contains(":194.0"),
                    "an unquoted number would drop the trailing zero the scale holds: " + rendered);
        }

        @Test
        @DisplayName("the answer is JSON under 200 and carries no problem media type")
        void theAnswerIsJsonUnderTwoHundred() throws Exception {
            when(balances.findById(ACCOUNT)).thenReturn(Optional.of(storedRow()));

            MvcResult result = perform(get(ROUTE, ACCOUNT));

            assertEquals(HttpStatus.OK.value(), result.getResponse().getStatus(),
                    "a stored account answers 200");
            assertEquals(MediaType.APPLICATION_JSON_VALUE,
                    MediaType.parseMediaType(result.getResponse().getContentType())
                            .toString().split(";")[0],
                    "the route declares application/json and produces it");
        }

        @Test
        @DisplayName("AccountBalance.of reads each component from its own column")
        void theRendererReadsEachComponentFromItsOwnColumn() {
            AccountBalance rendered = AccountBalance.of(storedRow());

            assertEquals(ACCOUNT, rendered.accountId(), "the identifier column");
            assertEquals(STORED_BALANCE.toPlainString(), rendered.currentBalance(),
                    "the balance column, rendered without an exponent");
            assertEquals(STORED_CYCLE_CREDIT.toPlainString(), rendered.cycleCredit(),
                    "the cycle credit column");
            assertEquals(STORED_CYCLE_DEBIT.toPlainString(), rendered.cycleDebit(),
                    "the cycle debit column");
            assertNotEquals(rendered.cycleCredit(), rendered.cycleDebit(),
                    "the two accumulators are read from different columns, which equal stored "
                            + "values would hide");
        }

        @Test
        @DisplayName("a rendered answer withholds all four values from every log")
        void aRenderedAnswerWithholdsAllFourValues() {
            String rendered = AccountBalance.of(storedRow()).toString();

            assertFalse(rendered.contains(ACCOUNT),
                    "the account identifier reaches no log: " + rendered);
            assertFalse(rendered.contains(STORED_BALANCE.toPlainString()),
                    "the balance reaches no log: " + rendered);
            assertFalse(rendered.contains(STORED_CYCLE_CREDIT.toPlainString()),
                    "the cycle credit reaches no log: " + rendered);
            assertFalse(rendered.contains(STORED_CYCLE_DEBIT.toPlainString()),
                    "the cycle debit reaches no log: " + rendered);
            assertEquals(DECLARED_COMPONENTS.size(),
                    rendered.split(EventEnvelope.WITHHELD, -1).length - 1,
                    "one withheld marker per component: " + rendered);
        }

        /** @return one projection row, each value distinct from the others */
        private static AccountBalanceProjectionEntity storedRow() {
            return new AccountBalanceProjectionEntity(ACCOUNT, STORED_BALANCE, STORED_CYCLE_CREDIT,
                    STORED_CYCLE_DEBIT);
        }

        /** @return the parsed body of a successful query */
        private Map<String, Object> okBody() throws Exception {
            MvcResult result = perform(get(ROUTE, ACCOUNT));
            assertEquals(HttpStatus.OK.value(), result.getResponse().getStatus(),
                    result.getResponse().getContentAsString());
            return MAPPER.readValue(result.getResponse().getContentAsString(),
                    new TypeReference<Map<String, Object>>() { });
        }
    }

    @Nested
    @DisplayName("An account the projection table does not hold answers 404")
    class AbsentAccount {

        @Test
        @DisplayName("404 carries no body, and the identifier the caller named is not echoed")
        void anAbsentAccountAnswersNotFoundWithNoBody() throws Exception {
            when(balances.findById(ABSENT_ACCOUNT)).thenReturn(Optional.empty());

            MvcResult result = perform(get(ROUTE, ABSENT_ACCOUNT));

            assertEquals(HttpStatus.NOT_FOUND.value(), result.getResponse().getStatus(),
                    "a well-formed identifier no row holds answers 404, not 400 and not 500");
            assertEquals("", result.getResponse().getContentAsString(),
                    "the answer carries no body, so nothing echoes the identifier submitted");
            verify(balances).findById(ABSENT_ACCOUNT);
        }

        @Test
        @DisplayName("the read is asked for the identifier the path carried, unchanged")
        void theReadIsAskedForThePathIdentifier() throws Exception {
            when(balances.findById(ABSENT_ACCOUNT)).thenReturn(Optional.empty());

            perform(get(ROUTE, ABSENT_ACCOUNT));

            verify(balances).findById(ABSENT_ACCOUNT);
            verifyNoMoreInteractions(balances);
        }
    }

    @Nested
    @DisplayName("The two refusal types no request through this route can raise")
    class UnreachableRefusalArms {

        @Test
        @DisplayName("a violation reported by a validating proxy answers the documented 400")
        void aConstraintViolationAnswersTheDocumentedRefusal() {
            ResponseEntity<ApiProblem> answer = new LedgerApiExceptionHandler()
                    .onInvalidRequestValue(new ConstraintViolationException(Set.of()));

            assertRefusal(answer);
        }

        @Test
        @DisplayName("a path value the binding could not convert answers the same documented 400")
        void aTypeMismatchAnswersTheDocumentedRefusal() {
            ResponseEntity<ApiProblem> answer = new LedgerApiExceptionHandler()
                    .onInvalidRequestValue(new MethodArgumentTypeMismatchException(
                            "not-a-number", Long.class, "accountId", null, null));

            assertRefusal(answer);
        }

        /**
         * Asserts one refusal is the documented {@code 400} document.
         *
         * <p>The advice declares three types for this arm and the route can only raise one of them:
         * the constraint on the handler parameter produces
         * {@code HandlerMethodValidationException}, which {@link LedgerApiExceptionHandlerTest}
         * drives through a request. The other two are declared because a validating proxy and a
         * failed conversion report a different type for the same mistake, and a handler that
         * dropped either would answer {@code 500} to a malformed request. Calling the advice
         * directly is what holds those two arms.
         *
         * @param answer the response the advice built
         */
        private static void assertRefusal(ResponseEntity<ApiProblem> answer) {
            assertEquals(HttpStatus.BAD_REQUEST, answer.getStatusCode(),
                    "a malformed request value answers 400");
            assertEquals(MediaType.APPLICATION_PROBLEM_JSON, answer.getHeaders().getContentType(),
                    "the media type RFC 9457 names");
            ApiProblem problem = answer.getBody();
            assertEquals(ApiProblem.ABOUT_BLANK, problem.type(), "the problem type");
            assertEquals(ApiProblem.BAD_REQUEST, problem.title(), "the title of this class");
            assertEquals(HttpStatus.BAD_REQUEST.value(), problem.status(),
                    "the status repeated in the body");
            assertEquals(ApiProblem.INVALID_ACCOUNT_ID, problem.detail(),
                    "the one documented detail, which names the shape and no submitted value");
        }
    }

    /**
     * Performs one request and returns its result.
     *
     * @param request the request to perform
     * @return the result
     * @throws Exception when the request cannot be performed
     */
    private MvcResult perform(org.springframework.test.web.servlet.RequestBuilder request)
            throws Exception {
        return mockMvc.perform(request).andReturn();
    }
}

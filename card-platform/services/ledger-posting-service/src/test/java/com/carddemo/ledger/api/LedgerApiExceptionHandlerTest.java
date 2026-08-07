package com.carddemo.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Asserts every failing balance query answers one problem document, and that an unreachable
 * datastore is told apart from a fault.
 *
 * <p>This service answered the framework default body before {@link LedgerApiExceptionHandler}
 * existed, and that body carries the resolved request path in a {@code path} member. A caller naming
 * {@code /balances/00000000050} therefore read that account identifier back and copied it into its
 * own access log, and a caller naming a malformed value read its own value back. Neither member of
 * this document echoes anything a caller sent.
 *
 * <p>The same body also gave one answer, {@code 500}, to three unrelated situations. A paused
 * datastore answered {@code {"timestamp":...,"status":500,"error":"Internal Server
 * Error","path":"/balances/..."}}, which invites no retry and names no dependency. The three arms
 * below are what separate them.
 *
 * <p>Each test drives the controller with the advice registered and reads the body. No database, no
 * broker and no Spring context take part.
 */
@DisplayName("Every failing balance query answers one problem document")
final class LedgerApiExceptionHandlerTest {

    /** Route of one account's balance. */
    private static final String ROUTE = "/balances/{accountId}";

    /** An account identifier of the declared width, so a refusal comes from another cause. */
    private static final String ACCOUNT = "00000000050";

    /** The four members the document declares, and no fifth. */
    private static final List<String> DECLARED_MEMBERS =
            List.of("type", "title", "status", "detail");

    /** Reads one response body. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The stubbed projection store. No test below reaches a row through it. */
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

    /**
     * Asserts a path value outside the declared shape answers the documented refusal.
     *
     * <p>Four shapes reach the same text, because the route reads one value and the text names the
     * shape that value must carry rather than the way this one missed it: too short, too long,
     * carrying a letter, and carrying a separator.
     */
    @Test
    @DisplayName("A path value outside the eleven-digit shape answers 400 as one problem document")
    void aPathValueOutsideItsShapeAnswersOneProblemDocument() throws Exception {
        for (String malformed : List.of("1", "000000000500", "abcdefghijk", "0000000-050")) {
            assertProblem(perform(get(ROUTE, malformed)), 400, ApiProblem.BAD_REQUEST,
                    ApiProblem.INVALID_ACCOUNT_ID);
        }
    }

    /**
     * Asserts the refusal text names no value the caller submitted.
     *
     * <p>The framework body named it in a {@code path} member. A caller can send an account
     * identifier that is malformed and still real, so the value is withheld the same way a
     * well-formed one is.
     */
    @Test
    @DisplayName("The refusal names the shape the route accepts and never the value submitted")
    void theRefusalNamesNoSubmittedValue() throws Exception {
        Map<String, Object> body =
                assertProblem(perform(get(ROUTE, "00000000050x")), 400, ApiProblem.BAD_REQUEST,
                        ApiProblem.INVALID_ACCOUNT_ID);

        assertFalse(String.valueOf(body).contains("00000000050"),
                "no member echoes the value the caller sent: " + body);
        assertTrue(ApiProblem.INVALID_ACCOUNT_ID.contains("eleven digits"),
                "the detail names the shape the route accepts");
    }

    /**
     * Asserts each way of the datastore being away answers 503 and invites a retry.
     *
     * <p>These are the three types a paused database produces: a connection this service cannot
     * open, a transaction it cannot begin, and a statement that ran out of time. None of them is the
     * request's fault and none of them is this service's, so neither {@code 400} nor {@code 500}
     * describes what happened.
     */
    @Test
    @DisplayName("An unreachable datastore answers 503 inviting a retry, not 500")
    void anUnreachableDatastoreAnswersFiveOhThree() throws Exception {
        List<RuntimeException> awayShapes = List.of(
                new DataAccessResourceFailureException("could not open JDBC connection"),
                new CannotCreateTransactionException("could not open JPA transaction"),
                new QueryTimeoutException("canceling statement due to statement timeout"));

        for (RuntimeException away : awayShapes) {
            // doThrow rather than when: a second when(...) would invoke the stub already primed to
            // throw, and the throw would arrive here instead of through the route.
            doThrow(away).when(balances).findById(anyString());

            Map<String, Object> body = assertProblem(perform(get(ROUTE, ACCOUNT)), 503,
                    ApiProblem.SERVICE_UNAVAILABLE, ApiProblem.BALANCE_DEPENDENCY_UNAVAILABLE);

            assertTrue(String.valueOf(body.get("detail")).contains("Retry"),
                    away.getClass().getSimpleName() + " invites a retry: " + body);
        }
    }

    /**
     * Asserts a fault inside this service answers 500 and leaks nothing of the fault.
     *
     * <p>A driver message quotes the statement it failed on, and that statement carries a table name
     * and can carry a stored value. The documented detail says the read failed and stops there.
     */
    @Test
    @DisplayName("A fault answers 500 carrying the documented text and no fault detail")
    void aFaultAnswersTheDocumentedTextAlone() throws Exception {
        doThrow(new IllegalStateException(
                "relation \"account_balance_projection\" does not exist"))
                .when(balances).findById(ACCOUNT);

        Map<String, Object> body = assertProblem(perform(get(ROUTE, ACCOUNT)), 500,
                ApiProblem.INTERNAL_SERVER_ERROR, ApiProblem.BALANCE_NOT_READ);

        assertFalse(String.valueOf(body).contains("account_balance_projection"),
                "no table name reaches the caller: " + body);
        assertFalse(String.valueOf(body).contains("IllegalStateException"),
                "no exception class reaches the caller: " + body);
    }

    /**
     * Asserts an unavailable dependency and a fault answer different statuses for one route.
     *
     * <p>The two arms are worth telling apart only if they are told apart, and a single advice
     * catching every data-access failure would answer {@code 503} for a broken query too. This test
     * is what fails if the arms are merged.
     */
    @Test
    @DisplayName("A broken query answers 500 while an unreachable datastore answers 503")
    void aBrokenQueryAndAnAwayDatastoreAnswerDifferentStatuses() throws Exception {
        doThrow(new org.springframework.dao.InvalidDataAccessResourceUsageException(
                "column bal0_0.currnt_bal does not exist"))
                .when(balances).findById(ACCOUNT);

        assertProblem(perform(get(ROUTE, ACCOUNT)), 500, ApiProblem.INTERNAL_SERVER_ERROR,
                ApiProblem.BALANCE_NOT_READ);

        doThrow(new DataAccessResourceFailureException("connection refused"))
                .when(balances).findById(ACCOUNT);

        assertProblem(perform(get(ROUTE, ACCOUNT)), 503, ApiProblem.SERVICE_UNAVAILABLE,
                ApiProblem.BALANCE_DEPENDENCY_UNAVAILABLE);
    }

    /**
     * Performs one request and returns its result.
     *
     * @param request the request to perform
     * @return the result
     * @throws Exception when the request cannot be performed
     */
    private MvcResult perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    /**
     * Asserts one result is the documented problem document.
     *
     * @param result the result to read
     * @param status the status the response carries
     * @param title  the title the body carries
     * @param detail the detail the body carries
     * @return the parsed body
     * @throws Exception when the body cannot be read
     */
    private static Map<String, Object> assertProblem(MvcResult result, int status, String title,
            String detail) throws Exception {

        assertEquals(status, result.getResponse().getStatus(), "the status of the answer");
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                MediaType.parseMediaType(result.getResponse().getContentType())
                        .toString().split(";")[0],
                "the media type RFC 9457 names");

        Map<String, Object> body = MAPPER.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() { });

        assertEquals(DECLARED_MEMBERS.size(), body.size(),
                "the body carries the four declared members and no fifth: " + body);
        for (String member : DECLARED_MEMBERS) {
            assertTrue(body.containsKey(member), "the body carries " + member + ": " + body);
        }
        assertEquals(ApiProblem.ABOUT_BLANK, body.get("type"), "the problem type");
        assertEquals(title, body.get("title"), "the title of this class of failure");
        assertEquals(status, body.get("status"), "the status repeated in the body");
        assertEquals(detail, body.get("detail"), "the documented detail");
        assertFalse(body.containsKey("path"), "no member echoes the resolved request path");
        assertFalse(body.containsKey("timestamp"), "the framework body carried a timestamp");
        return body;
    }
}

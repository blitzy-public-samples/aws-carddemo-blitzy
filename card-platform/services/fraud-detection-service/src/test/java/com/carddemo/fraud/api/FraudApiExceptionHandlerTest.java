package com.carddemo.fraud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.fraud.repository.FraudAssessmentRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Asserts every failing fraud-assessment call answers the one documented error shape.
 *
 * <p>{@code src/main/resources/openapi.yaml} declares {@code application/problem+json} carrying
 * {@link ApiProblem} for {@code 400}, {@code 401}, {@code 403} and {@code 500}.
 * {@code config/SecurityConfig} writes that shape for the two security statuses, and
 * {@link FraudApiExceptionHandler} is what makes the other two keep the promise. Before it existed
 * the container's error dispatch answered them in {@code application/json} carrying
 * {@code timestamp}, {@code status}, {@code error} and {@code path}, which is a second shape for a
 * client to parse and one whose {@code path} member copies the resolved request path into the body.
 *
 * <p>Each test drives the controller with the advice registered and reads the body. No database, no
 * broker and no Spring context take part.
 */
@DisplayName("Every failing fraud-assessment call answers one problem document")
final class FraudApiExceptionHandlerTest {

    /** Route of one account's assessments. */
    private static final String COLLECTION_ROUTE = "/fraud-assessments";

    /** Route of the single assessment. */
    private static final String ITEM_ROUTE = "/fraud-assessments/{transactionId}";

    /** An account identifier of the declared width, so a refusal comes from another value. */
    private static final String ACCOUNT = "00000000007";

    /** The four members the document declares, and no fifth. */
    private static final List<String> DECLARED_MEMBERS =
            List.of("type", "title", "status", "detail");

    /** Reads one response body. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The stubbed store. No test below reaches a row through it. */
    private FraudAssessmentRepository assessments;

    /** The routes under test, with the advice registered as the running service registers it. */
    private MockMvc mockMvc;

    /** Stands the controller and the advice up over a stubbed repository. */
    @BeforeEach
    void standUpRoutes() {
        assessments = mock(FraudAssessmentRepository.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FraudAssessmentController(assessments))
                .setControllerAdvice(new FraudApiExceptionHandler())
                .setValidator(new LocalValidatorFactoryBean())
                .build();
    }

    /**
     * Asserts each refusal of a declared constraint answers the documented problem document.
     *
     * <p>These are the six cases a caller reaches by sending a value outside the bounds the document
     * publishes: an identifier of the wrong shape on either route, a page below zero, a page size
     * outside one through 200, and a page size that is not a whole number at all.
     */
    @Test
    @DisplayName("A value outside its declared bounds answers 400 as one problem document")
    void aValueOutsideItsDeclaredBoundsAnswersOneProblemDocument() throws Exception {
        assertProblem(perform(get(COLLECTION_ROUTE).param("accountId", "1")), 400,
                ApiProblem.BAD_REQUEST, ApiProblem.INVALID_REQUEST_CONTENT);
        assertProblem(perform(get(COLLECTION_ROUTE).param("accountId", ACCOUNT)
                .param("size", "0")), 400, ApiProblem.BAD_REQUEST,
                ApiProblem.INVALID_REQUEST_CONTENT);
        assertProblem(perform(get(COLLECTION_ROUTE).param("accountId", ACCOUNT)
                .param("size", "201")), 400, ApiProblem.BAD_REQUEST,
                ApiProblem.INVALID_REQUEST_CONTENT);
        assertProblem(perform(get(COLLECTION_ROUTE).param("accountId", ACCOUNT)
                .param("size", "abc")), 400, ApiProblem.BAD_REQUEST,
                ApiProblem.INVALID_REQUEST_CONTENT);
        assertProblem(perform(get(COLLECTION_ROUTE).param("accountId", ACCOUNT)
                .param("page", "-1")), 400, ApiProblem.BAD_REQUEST,
                ApiProblem.INVALID_REQUEST_CONTENT);
        assertProblem(perform(get(ITEM_ROUTE, "short")), 400, ApiProblem.BAD_REQUEST,
                ApiProblem.INVALID_REQUEST_CONTENT);
    }

    /** Asserts the required account parameter not arriving answers the same shape. */
    @Test
    @DisplayName("A collection request naming no account answers 400 as one problem document")
    void aCollectionRequestNamingNoAccountAnswersOneProblemDocument() throws Exception {
        assertProblem(perform(get(COLLECTION_ROUTE)), 400, ApiProblem.BAD_REQUEST,
                ApiProblem.INVALID_REQUEST_CONTENT);
    }

    /**
     * Asserts a {@code sort} parameter answers 400 carrying its own text.
     *
     * <p>This is the one refusal with something to say: the order is a property of the repository
     * finder, so an order a caller asked for could only be ignored while the response claimed
     * success.
     */
    @Test
    @DisplayName("A sort parameter answers 400 naming the parameter and the order this route applies")
    void aSortParameterAnswersItsOwnText() throws Exception {
        Map<String, Object> body = assertProblem(
                perform(get(COLLECTION_ROUTE).param("accountId", ACCOUNT)
                        .param("sort", "riskScore")),
                400, ApiProblem.BAD_REQUEST, null);

        String detail = String.valueOf(body.get("detail"));
        assertTrue(detail.contains("sort parameter is not supported"),
                "the detail names the parameter this route refuses: " + detail);
        assertTrue(detail.contains("assessment-time descending order only"),
                "the detail names the order this route applies: " + detail);
    }

    /**
     * Asserts a read this service could not complete answers 500 as one problem document.
     *
     * <p>The documented detail says the read failed and nothing else. A driver message can quote a
     * value this service stores, so no part of the fault reaches the body.
     */
    @Test
    @DisplayName("A failing read answers 500 carrying the documented text and no fault detail")
    void aFailingReadAnswersTheDocumentedText() throws Exception {
        when(assessments.findById("0000000000683580"))
                .thenThrow(new IllegalStateException("relation \"fraud_assessment\" does not exist"));

        Map<String, Object> body = assertProblem(perform(get(ITEM_ROUTE, "0000000000683580")), 500,
                ApiProblem.INTERNAL_SERVER_ERROR, ApiProblem.ASSESSMENT_NOT_READ);

        assertFalse(String.valueOf(body).contains("fraud_assessment"),
                "no table name reaches the caller: " + body);
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

    /**
     * Asserts one result is the documented problem document.
     *
     * @param result the result to read
     * @param status the status the response carries
     * @param title  the title the body carries
     * @param detail the detail the body carries, or {@code null} to leave it to the caller
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
        if (detail != null) {
            assertEquals(detail, body.get("detail"), "the documented detail");
        }
        assertFalse(body.containsKey("path"), "no member echoes the resolved request path");
        assertFalse(body.containsKey("timestamp"), "the framework body carried a timestamp");
        return body;
    }
}

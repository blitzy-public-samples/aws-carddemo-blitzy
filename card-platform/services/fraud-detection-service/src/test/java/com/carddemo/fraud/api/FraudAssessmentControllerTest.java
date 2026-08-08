package com.carddemo.fraud.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import jakarta.servlet.ServletException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Response tests for the two read routes of {@link FraudAssessmentController}: one assessment by
 * transaction identifier, and one account's assessments newest first.
 *
 * <p>No COBOL (Common Business Oriented Language) program scores risk, so the capability under test
 * is net new; no COBOL ancestor exists.
 *
 * <p>Each test drives the controller over Hypertext Transfer Protocol (HTTP) and reads the
 * JavaScript Object Notation (JSON) body. No database, no broker and no Spring context take part.
 */
@DisplayName("Fraud assessment read routes")
final class FraudAssessmentControllerTest {

    private static final String ITEM_ROUTE = "/fraud-assessments/{transactionId}";

    private static final String COLLECTION_ROUTE = "/fraud-assessments";

    private static final String STORED_TRANSACTION = "0000000000" + "683580";

    private static final String VERDICT_FALSE_TRANSACTION = "0000000001" + "774260";

    private static final String VERDICT_TRUE_TRANSACTION = "0000000006" + "292564";

    private static final String RULE_ORDER_TRANSACTION = "0000000009" + "101861";

    private static final String REPEATED_RULE_TRANSACTION = "0000000010" + "142252";

    private static final String NO_RULES_TRANSACTION = "0000000010" + "229018";

    private static final String SECOND_TRANSACTION = "0000000017" + "874199";

    private static final String ABSENT_TRANSACTION = "0000000016" + "259484";

    private static final String ALPHANUMERIC_TRANSACTION = "ABCDEF0123456789";

    private static final String SHORT_TRANSACTION = "0000000000" + "68358";

    private static final String LONG_TRANSACTION = "0000000000" + "6835801";

    private static final String ACCOUNT = "00000000007";

    private static final String ACCOUNT_WITHOUT_ASSESSMENTS = "00000000027";

    private static final String SHORT_ACCOUNT = "0000000007";

    private static final String LONG_ACCOUNT = "00000000" + "0007";

    private static final String NON_NUMERIC_ACCOUNT = "0000000000A";

    private static final String VELOCITY = "VELOCITY";

    private static final String AMOUNT_ANOMALY = "AMOUNT_ANOMALY";

    private static final String MERCHANT_CATEGORY = "MERCHANT_CATEGORY";

    private static final int SCORE = 42;

    private static final int CLEARED_SCORE = 0;

    private static final Instant ASSESSED_AT = Instant.parse("2026-02-14T09:15:30.120Z");

    /** The six properties an assessment answer declares. */
    private static final Set<String> DECLARED_PROPERTIES = Set.of(
            "transactionId", "accountId", "riskScore", "triggeredRules", "flagged", "assessedAt");

    /**
     * Lower-case tokens no response body may carry: the four table names, the schema prefix, six
     * Structured Query Language (SQL) keywords, and the tokens a failure detail carries.
     */
    private static final List<String> WITHHELD_TOKENS = List.of(
            "fraud_assessment", "velocity_window", "processed_event", "outbox_event", "fraud.",
            "select ", "insert ", "update ", "delete ", "from ", "where ",
            "exception", "throwable", "at com.", "jdbc", "hibernate", "constraint", "sqlstate");

    private static final ObjectMapper JSON = new ObjectMapper();

    private FraudAssessmentRepository assessments;

    private MockMvc mockMvc;

    /**
     * Stands the controller up over a stubbed repository before each test.
     *
     * <p>No framework page resolver is registered, deliberately. The collection route declares
     * {@code page} and {@code size} as request parameters of its own with stated bounds, so a value
     * outside them answers 400 instead of being coerced or clamped. Registering a resolver here would
     * hide that: it would answer 200 for every value a test could send.</p>
     *
     * <p>A validator is registered so the bounds on those parameters and the pattern on the account
     * identifier are enforced, which is what turns an out-of-range value into a client error.</p>
     */
    @BeforeEach
    void standUpRoutes() {
        assessments = mock(FraudAssessmentRepository.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FraudAssessmentController(assessments))
                .setValidator(new LocalValidatorFactoryBean())
                .build();
    }

    @Test
    @DisplayName("a stored assessment answers 200 carrying the six declared properties, no seventh")
    void aStoredAssessmentAnswersTwoHundredCarryingTheSixDeclaredProperties() throws Exception {
        FraudAssessmentEntity stored =
                assessmentRow(STORED_TRANSACTION, ACCOUNT, SCORE, List.of(VELOCITY), true);
        when(assessments.findById(STORED_TRANSACTION)).thenReturn(Optional.of(stored));

        Map<String, Object> body = readObject(mockMvc.perform(get(ITEM_ROUTE, STORED_TRANSACTION))
                .andExpect(status().isOk())
                .andReturn());

        assertCarriesTheDeclaredProperties(body);
        assertEquals(DECLARED_PROPERTIES.size(), body.size(),
                "the answer carries a seventh property");

        String transactionId = assertInstanceOf(String.class, body.get("transactionId"),
                "the transaction identifier answers as a JSON number");
        assertEquals(STORED_TRANSACTION, transactionId,
                "the transaction identifier loses characters on the way out");

        String accountId = assertInstanceOf(String.class, body.get("accountId"),
                "the account identifier answers as a JSON number");
        assertEquals(ACCOUNT, accountId, "the account identifier loses its leading zeros");

        Integer riskScore = assertInstanceOf(Integer.class, body.get("riskScore"),
                "the risk score answers as text or as a fraction");
        assertEquals(SCORE, riskScore.intValue(), "the risk score differs from the stored score");

        assertInstanceOf(Boolean.class, body.get("flagged"),
                "the verdict answers as text or as a number");

        String assessedAt = assertInstanceOf(String.class, body.get("assessedAt"),
                "the assessment time answers as a count of seconds");
        assertEquals(ASSESSED_AT, Instant.parse(assessedAt),
                "the assessment time differs from the stored time");
    }

    @Test
    @DisplayName("a below-threshold verdict answers false and retains its triggered rule")
    void aBelowThresholdVerdictRetainsItsTriggeredRule() throws Exception {
        FraudAssessmentEntity stored = assessmentRow(VERDICT_FALSE_TRANSACTION, ACCOUNT, 25,
                List.of(MERCHANT_CATEGORY), false);
        when(assessments.findById(VERDICT_FALSE_TRANSACTION)).thenReturn(Optional.of(stored));

        Map<String, Object> body =
                readObject(mockMvc.perform(get(ITEM_ROUTE, VERDICT_FALSE_TRANSACTION))
                        .andExpect(status().isOk())
                        .andReturn());

        Boolean flagged = assertInstanceOf(Boolean.class, body.get("flagged"),
                "the verdict answers as text or as a number");
        assertFalse(flagged, "the below-threshold verdict changed");
        assertEquals(List.of(MERCHANT_CATEGORY), body.get("triggeredRules"),
                "the rule list differs from the stored list");
    }

    @Test
    @DisplayName("a flagged stored row with no triggered rule is refused")
    void aFlaggedStoredRowWithNoTriggeredRuleIsRefused() {
        FraudAssessmentEntity stored = assessmentRow(VERDICT_TRUE_TRANSACTION, ACCOUNT,
                CLEARED_SCORE, List.of(), true);
        when(assessments.findById(VERDICT_TRUE_TRANSACTION)).thenReturn(Optional.of(stored));

        ServletException refused = assertThrows(ServletException.class,
                () -> mockMvc.perform(get(ITEM_ROUTE, VERDICT_TRUE_TRANSACTION)));
        assertInstanceOf(IllegalArgumentException.class, refused.getCause(),
                "the corrupt row reached the response");
    }

    @Test
    @DisplayName("the rule list answers in the order the row holds, not in alphabetical order")
    void theRuleListAnswersInTheOrderTheRowHolds() throws Exception {
        FraudAssessmentEntity stored = assessmentRow(RULE_ORDER_TRANSACTION, ACCOUNT, SCORE,
                List.of(MERCHANT_CATEGORY, VELOCITY), true);
        when(assessments.findById(RULE_ORDER_TRANSACTION)).thenReturn(Optional.of(stored));

        Map<String, Object> body =
                readObject(mockMvc.perform(get(ITEM_ROUTE, RULE_ORDER_TRANSACTION))
                        .andExpect(status().isOk())
                        .andReturn());

        assertEquals(List.of(MERCHANT_CATEGORY, VELOCITY), body.get("triggeredRules"),
                "the rule list answers sorted or reordered");
    }

    @Test
    @DisplayName("a stored row naming one rule twice is refused")
    void aStoredRowNamingOneRuleTwiceIsRefused() {
        FraudAssessmentEntity stored = assessmentRow(REPEATED_RULE_TRANSACTION, ACCOUNT, SCORE,
                List.of(VELOCITY, VELOCITY), true);
        when(assessments.findById(REPEATED_RULE_TRANSACTION)).thenReturn(Optional.of(stored));

        ServletException refused = assertThrows(ServletException.class,
                () -> mockMvc.perform(get(ITEM_ROUTE, REPEATED_RULE_TRANSACTION)));
        assertInstanceOf(IllegalArgumentException.class, refused.getCause(),
                "the duplicate rule reached the response");
    }

    @Test
    @DisplayName("a row naming no rule answers an empty JSON array, neither null nor absent")
    void aRowNamingNoRuleAnswersAnEmptyArray() throws Exception {
        FraudAssessmentEntity stored =
                assessmentRow(NO_RULES_TRANSACTION, ACCOUNT, CLEARED_SCORE, List.of(), false);
        when(assessments.findById(NO_RULES_TRANSACTION)).thenReturn(Optional.of(stored));

        Map<String, Object> body = readObject(mockMvc.perform(get(ITEM_ROUTE, NO_RULES_TRANSACTION))
                .andExpect(status().isOk())
                .andReturn());

        assertTrue(body.containsKey("triggeredRules"), "the rule list is absent from the answer");
        assertNotNull(body.get("triggeredRules"), "the rule list answers null");
        List<?> rules = assertInstanceOf(List.class, body.get("triggeredRules"),
                "the rule list answers as something other than a JSON array");
        assertTrue(rules.isEmpty(), "the rule list carries an entry");
    }

    @Test
    @DisplayName("an assessment no row holds answers 404 with an empty body and throws nothing")
    void anAssessmentNoRowHoldsAnswersFourZeroFourWithAnEmptyBody() throws Exception {
        when(assessments.findById(ABSENT_TRANSACTION)).thenReturn(Optional.empty());

        MvcResult result = assertDoesNotThrow(
                () -> mockMvc.perform(get(ITEM_ROUTE, ABSENT_TRANSACTION))
                        .andExpect(status().isNotFound())
                        .andReturn(),
                "the route threw to report an assessment no row holds");

        assertNull(result.getResolvedException(),
                "an exception left the route and reached the resolver chain");
        String body = result.getResponse().getContentAsString();
        assertEquals(0, body.length(), "the 404 answer carries a body");
        assertNothingLeaks(body);
    }

    @Test
    @DisplayName("an account holding two assessments answers 200 with an array of two")
    void anAccountHoldingTwoAssessmentsAnswersAnArrayOfTwo() throws Exception {
        List<FraudAssessmentEntity> stored = List.of(
                assessmentRow(STORED_TRANSACTION, ACCOUNT, SCORE, List.of(VELOCITY), true),
                assessmentRow(SECOND_TRANSACTION, ACCOUNT, CLEARED_SCORE, List.of(), false));
        when(assessments.findByAccountIdOrderByAssessedAtDesc(eq(ACCOUNT), any()))
                .thenReturn(stored);

        List<Map<String, Object>> body = readArray(mockMvc
                .perform(get(COLLECTION_ROUTE).param("accountId", ACCOUNT))
                .andExpect(status().isOk())
                .andReturn());

        assertEquals(2, body.size(), "the answer carries a number of elements other than two");
        body.forEach(FraudAssessmentControllerTest::assertCarriesTheDeclaredProperties);
        assertEquals(STORED_TRANSACTION, body.get(0).get("transactionId"),
                "the first element names another transaction");
        assertEquals(SECOND_TRANSACTION, body.get(1).get("transactionId"),
                "the second element names another transaction");
    }

    @Test
    @DisplayName("an account holding no assessment answers 200 with an empty array, never 404")
    void anAccountHoldingNoAssessmentAnswersAnEmptyArray() throws Exception {
        when(assessments.findByAccountIdOrderByAssessedAtDesc(
                eq(ACCOUNT_WITHOUT_ASSESSMENTS), any())).thenReturn(List.of());

        MvcResult result = mockMvc
                .perform(get(COLLECTION_ROUTE).param("accountId", ACCOUNT_WITHOUT_ASSESSMENTS))
                .andExpect(status().isOk())
                .andReturn();

        assertNotEquals(404, result.getResponse().getStatus(),
                "an account holding no assessment answers 404");
        assertTrue(readArray(result).isEmpty(), "the answer carries an element");
    }

    @Test
    @DisplayName("a transaction identifier of the wrong width answers 400")
    void aTransactionIdentifierOfTheWrongWidthAnswersFourHundred() throws Exception {
        for (String malformed : List.of(SHORT_TRANSACTION, LONG_TRANSACTION)) {
            MvcResult result = mockMvc.perform(get(ITEM_ROUTE, malformed))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            assertNothingLeaks(result.getResponse().getContentAsString());
        }
    }

    @Test
    @DisplayName("an account identifier that is not eleven digits answers 400")
    void anAccountIdentifierThatIsNotElevenDigitsAnswersFourHundred() throws Exception {
        for (String malformed : List.of(SHORT_ACCOUNT, LONG_ACCOUNT, NON_NUMERIC_ACCOUNT)) {
            MvcResult result = mockMvc
                    .perform(get(COLLECTION_ROUTE).param("accountId", malformed))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            assertNothingLeaks(result.getResponse().getContentAsString());
        }
    }

    @Test
    @DisplayName("a collection request naming no account answers 400")
    void aCollectionRequestNamingNoAccountAnswersFourHundred() throws Exception {
        MvcResult result = mockMvc.perform(get(COLLECTION_ROUTE))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertNothingLeaks(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("an identifier of sixteen characters holding letters answers 404, never 400")
    void aSixteenCharacterIdentifierHoldingLettersReachesTheLookup() throws Exception {
        when(assessments.findById(ALPHANUMERIC_TRANSACTION)).thenReturn(Optional.empty());

        MvcResult result = mockMvc.perform(get(ITEM_ROUTE, ALPHANUMERIC_TRANSACTION))
                .andExpect(status().isNotFound())
                .andReturn();

        assertNotEquals(400, result.getResponse().getStatus(),
                "a digits pattern refused an identifier holding letters");
        verify(assessments).findById(ALPHANUMERIC_TRANSACTION);
        assertNothingLeaks(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("the collection route forwards the page number and page size it was given, unsorted")
    void theCollectionRouteForwardsPageNumberOneAndPageSizeThree() throws Exception {
        when(assessments.findByAccountIdOrderByAssessedAtDesc(eq(ACCOUNT), any()))
                .thenReturn(List.of());

        mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("page", "1")
                        .param("size", "3"))
                .andExpect(status().isOk());

        Pageable forwarded = capturedPage();
        assertEquals(1, forwarded.getPageNumber(), "the repository reads another page number");
        assertEquals(3, forwarded.getPageSize(), "the repository reads another page size");
        assertFalse(forwarded.getSort().isSorted(), "the forwarded page carries a sort order");
    }

    @Test
    @DisplayName("the collection route forwards page size seven, unsorted")
    void theCollectionRouteForwardsPageSizeSeven() throws Exception {
        when(assessments.findByAccountIdOrderByAssessedAtDesc(eq(ACCOUNT), any()))
                .thenReturn(List.of());

        mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("size", "7"))
                .andExpect(status().isOk());

        Pageable forwarded = capturedPage();
        assertEquals(7, forwarded.getPageSize(), "the repository reads another page size");
        assertFalse(forwarded.getSort().isSorted(), "the forwarded page carries a sort order");
    }

    @Test
    @DisplayName("naming no page and no size returns the first page at the declared default size")
    void namingNoPageAndNoSizeReturnsTheFirstPageAtTheDefaultSize() throws Exception {
        when(assessments.findByAccountIdOrderByAssessedAtDesc(eq(ACCOUNT), any()))
                .thenReturn(List.of());

        mockMvc.perform(get(COLLECTION_ROUTE).param("accountId", ACCOUNT))
                .andExpect(status().isOk());

        Pageable forwarded = capturedPage();
        assertEquals(FraudAssessmentController.FIRST_PAGE, forwarded.getPageNumber(),
                "a caller naming no page receives another page");
        assertEquals(FraudAssessmentController.DEFAULT_PAGE_SIZE, forwarded.getPageSize(),
                "a caller naming no size receives another number of rows");
    }

    @Test
    @DisplayName("a page size above the ceiling answers 400 rather than being clamped to it")
    void aPageSizeAboveTheCeilingAnswersFourHundred() throws Exception {
        int overCeiling = FraudAssessmentController.MAXIMUM_PAGE_SIZE + 1;

        MvcResult result = mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("size", String.valueOf(overCeiling)))
                .andExpect(status().isBadRequest())
                .andReturn();

        verifyNoInteractions(assessments);
        assertNothingLeaks(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a page size far above the ceiling answers 400 and returns no rows silently")
    void aPageSizeFarAboveTheCeilingAnswersFourHundred() throws Exception {
        mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("size", "5000"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assessments);
    }

    @Test
    @DisplayName("the ceiling itself is accepted and forwarded whole")
    void theCeilingItselfIsAcceptedAndForwardedWhole() throws Exception {
        when(assessments.findByAccountIdOrderByAssessedAtDesc(eq(ACCOUNT), any()))
                .thenReturn(List.of());

        mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("size",
                                String.valueOf(FraudAssessmentController.MAXIMUM_PAGE_SIZE)))
                .andExpect(status().isOk());

        assertEquals(FraudAssessmentController.MAXIMUM_PAGE_SIZE, capturedPage().getPageSize(),
                "the ceiling was not forwarded whole");
    }

    @Test
    @DisplayName("a page size of zero answers 400 rather than falling back to the default")
    void aPageSizeOfZeroAnswersFourHundred() throws Exception {
        mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("size", "0"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assessments);
    }

    @Test
    @DisplayName("a negative page number answers 400 rather than being read as the first page")
    void aNegativePageNumberAnswersFourHundred() throws Exception {
        mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assessments);
    }

    @Test
    @DisplayName("an unparsable page size answers 400 rather than falling back to the default")
    void anUnparsablePageSizeAnswersFourHundred() throws Exception {
        mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("size", "many"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assessments);
    }

    @Test
    @DisplayName("a sort order answers 400 rather than being accepted and ignored")
    void aSortOrderAnswersFourHundred() throws Exception {
        MvcResult result = mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("sort", "riskScore,asc"))
                .andExpect(status().isBadRequest())
                .andReturn();

        verifyNoInteractions(assessments);
        assertNothingLeaks(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a sort order naming the fixed column is refused too, so no caller believes it chose")
    void aSortOrderNamingTheFixedColumnIsRefusedToo() throws Exception {
        mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("sort", "assessedAt,desc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assessments);
    }

    @Test
    @DisplayName("the route declares no framework page argument, so nothing is coerced silently")
    void theRouteDeclaresNoFrameworkPageArgument() {
        List<Class<?>> parameterTypes = Arrays.stream(FraudAssessmentController.class
                        .getDeclaredMethods())
                .filter(method -> "assessmentsOfAccount".equals(method.getName()))
                .findFirst()
                .map(method -> List.<Class<?>>of(method.getParameterTypes()))
                .orElseThrow(() -> new AssertionError("the controller declares no collection route"));

        assertEquals(List.of(String.class, String.class, String.class, String.class), parameterTypes,
                "the collection route takes the account identifier and three parameters of its own,"
                        + " each read as text; a framework page argument would decide the page"
                        + " silently instead, and a number with a default would read a present-empty"
                        + " value as an omitted one");
    }

    @Test
    @DisplayName("a paging value that arrived carrying no characters is refused, never defaulted")
    void aPagingValueThatArrivedEmptyIsRefused() throws Exception {
        for (String parameter : List.of("page", "size")) {
            mockMvc.perform(get(COLLECTION_ROUTE)
                            .param("accountId", ACCOUNT)
                            .param(parameter, ""))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(assessments);
    }

    @Test
    @DisplayName("an omitted paging value takes its default and reads the first page")
    void anOmittedPagingValueTakesItsDefault() throws Exception {
        when(assessments.findByAccountIdOrderByAssessedAtDesc(eq(ACCOUNT), any()))
                .thenReturn(List.of());

        mockMvc.perform(get(COLLECTION_ROUTE).param("accountId", ACCOUNT))
                .andExpect(status().isOk());

        ArgumentCaptor<PageRequest> requested = ArgumentCaptor.forClass(PageRequest.class);
        verify(assessments)
                .findByAccountIdOrderByAssessedAtDesc(eq(ACCOUNT), requested.capture());

        assertEquals(FraudAssessmentController.FIRST_PAGE,
                requested.getValue().getPageNumber(), "the first page");
        assertEquals(FraudAssessmentController.DEFAULT_PAGE_SIZE,
                requested.getValue().getPageSize(), "the default rows one page carries");
    }

    @Test
    @DisplayName("a page number past its ceiling is refused rather than overflowing an offset")
    void aPageNumberPastItsCeilingIsRefused() throws Exception {
        mockMvc.perform(get(COLLECTION_ROUTE)
                        .param("accountId", ACCOUNT)
                        .param("page", String.valueOf(
                                FraudAssessmentController.MAXIMUM_PAGE_NUMBER + 1)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assessments);
    }

    /**
     * @param transactionId  identifier the row answers
     * @param accountId      account the row answers
     * @param riskScore      score the row answers
     * @param triggeredRules rule identifiers the row answers, in the order supplied
     * @param flagged        verdict the row answers
     * @return a row answering the six values supplied
     */
    private static FraudAssessmentEntity assessmentRow(String transactionId, String accountId,
            int riskScore, List<String> triggeredRules, boolean flagged) {
        FraudAssessmentEntity row = mock(FraudAssessmentEntity.class);
        when(row.getTransactionId()).thenReturn(transactionId);
        when(row.getAccountId()).thenReturn(accountId);
        when(row.getRiskScore()).thenReturn(riskScore);
        when(row.getTriggeredRules()).thenReturn(triggeredRules);
        when(row.isFlagged()).thenReturn(flagged);
        when(row.getAssessedAt()).thenReturn(ASSESSED_AT);
        return row;
    }

    /**
     * @return the forwarded page
     */
    private Pageable capturedPage() {
        ArgumentCaptor<Pageable> forwarded = ArgumentCaptor.forClass(Pageable.class);
        verify(assessments)
                .findByAccountIdOrderByAssessedAtDesc(eq(ACCOUNT), forwarded.capture());
        return forwarded.getValue();
    }

    /**
     * Reads one response body as an object.
     *
     * @param result the answer to read
     * @return the properties the body carries, keyed by name
     * @throws Exception if the body cannot be read as characters
     */
    private static Map<String, Object> readObject(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() { });
    }

    /**
     * Reads one response body as an array of objects.
     *
     * @param result the answer to read
     * @return one element per object, each keyed by property name
     * @throws Exception if the body cannot be read as characters
     */
    private static List<Map<String, Object>> readArray(MvcResult result) throws Exception {
        return JSON.readValue(result.getResponse().getContentAsString(),
                new TypeReference<List<Map<String, Object>>>() { });
    }

    /**
     * Asserts one body names the six declared properties and nothing else.
     *
     * @param body the properties the body carries, keyed by name
     */
    private static void assertCarriesTheDeclaredProperties(Map<String, Object> body) {
        assertEquals(DECLARED_PROPERTIES, body.keySet(),
                "the answer names properties other than the six declared");
    }

    /**
     * Asserts one body names no table, no SQL keyword and no failure detail.
     *
     * @param body the answer to read, which may hold no characters
     */
    private static void assertNothingLeaks(String body) {
        String lowered = body.toLowerCase(Locale.ROOT);
        for (String withheld : WITHHELD_TOKENS) {
            assertFalse(lowered.contains(withheld),
                    "the answer carries the withheld token " + withheld);
        }
    }
}

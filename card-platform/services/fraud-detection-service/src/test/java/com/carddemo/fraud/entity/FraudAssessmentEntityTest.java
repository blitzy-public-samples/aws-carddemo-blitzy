package com.carddemo.fraud.entity;

import com.carddemo.events.FraudFlagged;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link FraudAssessmentEntity}, the row that records how the risk rules rated
 * one authorized transaction.
 *
 * <p>No Common Business Oriented Language (COBOL) ancestor: no COBOL program scores risk, so no
 * assertion here cites a source paragraph. The bounds and the rule identifiers come from
 * {@link FraudFlagged}, which the schema document {@code fraud-flagged-v1.json} enumerates. These
 * tests read them from that class, so the two cannot drift apart.
 *
 * <p>Four invariants are covered. The score bounds, the closed rule identifier set, the absence of
 * duplicates, and the exact widths of the two identifiers. The verdict is tested independently
 * because the configured score threshold, not a non-empty rule list, determines it. The round trip
 * through {@link FraudAssessmentEntity.TriggeredRuleListConverter} is covered too.
 *
 * <p>Every test builds the entity directly and reaches no database, context or broker.
 */
class FraudAssessmentEntityTest {

    /** A well-formed sixteen-character transaction identifier. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** A well-formed eleven-digit account identifier, leading zeros kept. */
    private static final String ACCOUNT_ID = "00000000001";

    /** A fixed moment, so no test depends on the clock. */
    private static final Instant ASSESSED_AT = Instant.parse("2026-01-02T03:04:05.060Z");

    /** Every rule the published contract permits, in the order the schema enumerates them. */
    private static final List<String> ALL_RULES = List.of(
            FraudFlagged.VELOCITY_RULE,
            FraudFlagged.AMOUNT_ANOMALY_RULE,
            FraudFlagged.MERCHANT_CATEGORY_RULE);

    /**
     * Builds one row, defaulting the verdict to whether the rule list names a rule.
     *
     * @param riskScore      the score to carry
     * @param triggeredRules the rules to carry
     * @return the row
     */
    private static FraudAssessmentEntity assessment(int riskScore, List<String> triggeredRules) {
        return new FraudAssessmentEntity(TRANSACTION_ID, ACCOUNT_ID, riskScore,
                !triggeredRules.isEmpty(), triggeredRules, ASSESSED_AT);
    }

    @Test
    @DisplayName("A cleared assessment carries no rule and no flag")
    void clearedAssessmentCarriesNoRuleAndNoFlag() {
        FraudAssessmentEntity cleared = assessment(0, List.of());
        assertFalse(cleared.isFlagged(), "an assessment naming no rule is not flagged");
        assertTrue(cleared.getTriggeredRules().isEmpty(), "a cleared assessment names no rule");
        assertEquals(0, cleared.getRiskScore(), "the score supplied did not survive");
    }

    @Test
    @DisplayName("A flagged assessment keeps every rule in the order supplied")
    void flaggedAssessmentKeepsRuleOrder() {
        FraudAssessmentEntity flagged = assessment(100, ALL_RULES);
        assertTrue(flagged.isFlagged(), "an assessment naming three rules is flagged");
        assertEquals(ALL_RULES, flagged.getTriggeredRules(), "the rule order did not survive");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 50, 99, 100})
    @DisplayName("Every score inside the published bounds is accepted, including both edges")
    void scoreInsideBoundsIsAccepted(int riskScore) {
        assertDoesNotThrow(() -> assessment(riskScore, List.of()),
                "score " + riskScore + " falls inside the published bounds");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -100, 101, 1000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    @DisplayName("A score outside the published bounds is refused")
    void scoreOutsideBoundsIsRefused(int riskScore) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> assessment(riskScore, List.of()),
                "score " + riskScore + " falls outside the published bounds");
        assertTrue(refused.getMessage().contains("riskScore"),
                "the failure names the field it refused: " + refused.getMessage());
    }

    @Test
    @DisplayName("The accepted bounds are the ones the published contract declares")
    void boundsMatchThePublishedContract() {
        assertEquals(0, FraudFlagged.MINIMUM_RISK_SCORE,
                "this test assumes the published minimum is zero");
        assertEquals(100, FraudFlagged.MAXIMUM_RISK_SCORE,
                "this test assumes the published maximum is one hundred");
        assertThrows(IllegalArgumentException.class,
                () -> assessment(FraudFlagged.MINIMUM_RISK_SCORE - 1, List.of()),
                "one below the published minimum is refused");
        assertThrows(IllegalArgumentException.class,
                () -> assessment(FraudFlagged.MAXIMUM_RISK_SCORE + 1, List.of()),
                "one above the published maximum is refused");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_A_RULE", "velocity", "VELOCITY ", "", "VELOCITY,AMOUNT_ANOMALY"})
    @DisplayName("A rule the published contract does not name is refused")
    void unknownRuleIsRefused(String rule) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> assessment(10, List.of(rule)),
                "the published contract does not name " + rule);
        assertTrue(refused.getMessage().contains("triggeredRules"),
                "the failure names the field it refused: " + refused.getMessage());
    }

    @Test
    @DisplayName("A rule identifier holding the separator is refused, not split into two rules")
    void separatorBearingRuleIsRefused() {
        String joined = FraudFlagged.VELOCITY_RULE + "," + FraudFlagged.AMOUNT_ANOMALY_RULE;
        assertThrows(IllegalArgumentException.class, () -> assessment(10, List.of(joined)),
                "one identifier holding a comma is not two rules");
    }

    @Test
    @DisplayName("A repeated rule is refused")
    void repeatedRuleIsRefused() {
        List<String> repeated = List.of(FraudFlagged.VELOCITY_RULE, FraudFlagged.VELOCITY_RULE);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> assessment(10, repeated), "a rule triggers once per assessment");
        assertTrue(refused.getMessage().contains("repeats"),
                "the failure says the list repeats an entry: " + refused.getMessage());
    }

    @Test
    @DisplayName("A null rule inside the list is refused")
    void nullRuleIsRefused() {
        List<String> holdingNull = new ArrayList<>();
        holdingNull.add(FraudFlagged.VELOCITY_RULE);
        holdingNull.add(null);
        assertThrows(NullPointerException.class, () -> assessment(10, holdingNull),
                "a null entry names no rule");
    }

    @Test
    @DisplayName("A threshold verdict is stored independently of the contributing rule list")
    void flaggedWithoutRuleIsStored() {
        FraudAssessmentEntity assessment = new FraudAssessmentEntity(
                TRANSACTION_ID, ACCOUNT_ID, 10, true, List.of(), ASSESSED_AT);
        assertTrue(assessment.isFlagged(), "the threshold verdict changed");
        assertEquals(List.of(), assessment.getTriggeredRules(), "the rule list changed");
    }

    @Test
    @DisplayName("A cleared assessment may retain a rule whose score stayed below the threshold")
    void clearedAssessmentMayRetainTriggeredRules() {
        FraudAssessmentEntity cleared = assertDoesNotThrow(
                () -> new FraudAssessmentEntity(TRANSACTION_ID, ACCOUNT_ID, 30, false,
                        List.of(FraudFlagged.VELOCITY_RULE), ASSESSED_AT));
        assertFalse(cleared.isFlagged(), "the threshold verdict changed");
        assertEquals(List.of(FraudFlagged.VELOCITY_RULE), cleared.getTriggeredRules(),
                "the explanatory rule was dropped");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "000000000000001", "00000000000000001",
        "0000000000000000000000"})
    @DisplayName("A transaction identifier of the wrong width is refused")
    void wrongWidthTransactionIdIsRefused(String transactionId) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new FraudAssessmentEntity(transactionId, ACCOUNT_ID, 0, false, List.of(),
                        ASSESSED_AT),
                "a transaction identifier holds exactly sixteen characters");
        assertTrue(refused.getMessage().contains("transactionId"),
                "the failure names the field it refused: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(transactionId + "\""),
                "the failure quotes no identifier: " + refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "0000000001", "000000000001", "0000000000X", "00000000 01"})
    @DisplayName("An account identifier of the wrong width or holding a non-digit is refused")
    void malformedAccountIdIsRefused(String accountId) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new FraudAssessmentEntity(TRANSACTION_ID, accountId, 0, false, List.of(),
                        ASSESSED_AT),
                "an account identifier holds exactly eleven digits");
        assertTrue(refused.getMessage().contains("accountId"),
                "the failure names the field it refused: " + refused.getMessage());
    }

    @Test
    @DisplayName("An account identifier keeps its leading zeros")
    void accountIdKeepsLeadingZeros() {
        FraudAssessmentEntity row = new FraudAssessmentEntity(TRANSACTION_ID, "00000000042", 0,
                false, List.of(), ASSESSED_AT);
        assertEquals("00000000042", row.getAccountId(),
                "the eleven digits supplied did not survive intact");
    }

    @Test
    @DisplayName("A null argument is refused")
    void nullArgumentIsRefused() {
        assertThrows(NullPointerException.class, () -> new FraudAssessmentEntity(null, ACCOUNT_ID, 0,
                false, List.of(), ASSESSED_AT), "the transaction identifier is required");
        assertThrows(NullPointerException.class, () -> new FraudAssessmentEntity(TRANSACTION_ID,
                null, 0, false, List.of(), ASSESSED_AT), "the account identifier is required");
        assertThrows(NullPointerException.class, () -> new FraudAssessmentEntity(TRANSACTION_ID,
                ACCOUNT_ID, 0, false, null, ASSESSED_AT), "the rule list is required");
        assertThrows(NullPointerException.class, () -> new FraudAssessmentEntity(TRANSACTION_ID,
                ACCOUNT_ID, 0, false, List.of(), null), "the assessment time is required");
    }

    @Test
    @DisplayName("The converter writes a JSON array and reads it back unchanged")
    void converterRoundTripsEveryRule() {
        FraudAssessmentEntity.TriggeredRuleListConverter converter =
                new FraudAssessmentEntity.TriggeredRuleListConverter();
        String stored = converter.convertToDatabaseColumn(ALL_RULES);
        assertEquals("[\"VELOCITY\",\"AMOUNT_ANOMALY\",\"MERCHANT_CATEGORY\"]", stored,
                "the stored form is a JSON array of the identifiers in order");
        assertEquals(ALL_RULES, converter.convertToEntityAttribute(stored),
                "the rule list did not survive the round trip");
    }

    @Test
    @DisplayName("The stored form of every rule fits the column")
    void storedFormFitsTheColumn() {
        String stored = new FraudAssessmentEntity.TriggeredRuleListConverter()
                .convertToDatabaseColumn(ALL_RULES);
        assertEquals(49, stored.length(), "every identifier together spans 49 characters");
        assertTrue(stored.length() <= 64, "the stored form fits triggered_rules VARCHAR(64)");
    }

    @Test
    @DisplayName("An empty and a null rule list both store the empty JSON array")
    void emptyAndNullBothStoreEmptyArray() {
        FraudAssessmentEntity.TriggeredRuleListConverter converter =
                new FraudAssessmentEntity.TriggeredRuleListConverter();
        assertEquals("[]", converter.convertToDatabaseColumn(List.of()),
                "an empty list stores the empty JSON array");
        assertEquals("[]", converter.convertToDatabaseColumn(null),
                "a null list stores the empty JSON array");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", ""})
    @DisplayName("The empty JSON array and the empty string both read back as no rules")
    void emptyStoredFormsReadAsNoRules(String stored) {
        assertTrue(new FraudAssessmentEntity.TriggeredRuleListConverter()
                        .convertToEntityAttribute(stored).isEmpty(),
                "the stored value '" + stored + "' names no rule");
    }

    @Test
    @DisplayName("A null stored value reads back as no rules")
    void nullStoredFormReadsAsNoRules() {
        assertTrue(new FraudAssessmentEntity.TriggeredRuleListConverter()
                .convertToEntityAttribute(null).isEmpty(), "a null column names no rule");
    }

    @Test
    @DisplayName("The converter refuses an identifier outside the published set")
    void converterRefusesAnUnknownIdentifier() {
        FraudAssessmentEntity.TriggeredRuleListConverter converter =
                new FraudAssessmentEntity.TriggeredRuleListConverter();
        List<String> awkward = List.of("A,B");
        String stored = converter.convertToDatabaseColumn(awkward);
        assertThrows(IllegalArgumentException.class,
                () -> converter.convertToEntityAttribute(stored),
                "a corrupt stored identifier reached the domain");
    }

    @Test
    @DisplayName("The converter refuses a stored value that is not a JSON array")
    void converterRefusesMalformedStoredValue() {
        FraudAssessmentEntity.TriggeredRuleListConverter converter =
                new FraudAssessmentEntity.TriggeredRuleListConverter();
        for (String malformed : Arrays.asList("VELOCITY,AMOUNT_ANOMALY", "{\"rule\":\"VELOCITY\"}",
                "[\"VELOCITY\"", "not json at all")) {
            assertThrows(IllegalArgumentException.class,
                    () -> converter.convertToEntityAttribute(malformed),
                    "the stored value '" + malformed + "' is not a JSON array of strings");
        }
    }

    @Test
    @DisplayName("The representation withholds the account, the score and the verdict")
    void representationWithholdsRating() {
        // The account identifier here is deliberately not a substring of TRANSACTION_ID. The
        // default pair would make this test pass or fail for the wrong reason, because the eleven
        // characters of "00000000001" are the tail of the sixteen of "0000000000000001".
        String distinctAccountId = "00000000429";
        FraudAssessmentEntity row = new FraudAssessmentEntity(TRANSACTION_ID, distinctAccountId, 87,
                true, ALL_RULES, ASSESSED_AT);

        String rendered = row.toString();
        assertTrue(rendered.contains(TRANSACTION_ID),
                "the transaction identifier correlates the row with its event");
        assertFalse(rendered.contains(distinctAccountId), "the account identifier is withheld");
        assertFalse(rendered.contains("87"), "the risk score is withheld");
        assertFalse(rendered.contains(FraudFlagged.VELOCITY_RULE),
                "the rules that triggered are withheld");
    }
}

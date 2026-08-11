package com.carddemo.fraud.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.domain.rules.MerchantCategoryRule;
import com.carddemo.fraud.domain.rules.VelocityRule;
import com.carddemo.fraud.entity.VelocityWindowEntity;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link VelocityRule}, which totals one account's recent authorization rows and reports
 * whether the count or the total reaches its threshold.
 *
 * <p>No COBOL ancestor. COBOL expands to Common Business Oriented Language.
 *
 * <p>This class owns the truncation assertions. The source performs no rounded arithmetic store,
 * so a total truncates toward zero while half-up and floor rounding reach other values.
 */
@DisplayName("VelocityRule, the count-and-total risk rule")
class VelocityRuleTest {

    /**
     * Card token of the fixture card number, sixty-four lower-case hexadecimal characters.
     *
     * <p>Additive. No source field exists. {@code com.carddemo.cobol.PanMasker#tokenOf} writes this
     * value from the full card number {@code 4859452612877065}, and the width and case are that
     * method's.</p>
     */
    private static final String CARD_TOKEN =
            "f8da0217fb8bd2e172d427a2ef66d54656a59baa9fe8f9bc2ce9d383b90e1173";

    // Measured in the first daily transaction fixture record and in the cross-reference row that
    // names this account. Identifiers stay text, and the card appears only in its masked form.
    private static final String ACCOUNT_ID = "00000000007";
    private static final Instant OCCURRED_AT = Instant.parse("2022-06-10T19:27:53Z");
    private static final String NEGATIVE_WINDOW_TOTAL = "-919.00";

    // Values this test passes to the constructor, the span start they imply, and the column scale.
    // A stored row spans one whole bucket and carries the start of that span, so the span start the
    // rule reads with is the configured width taken back and then truncated to the bucket unit.
    private static final int LOOKBACK_MINUTES = 60;
    private static final int COUNT_THRESHOLD = 5;
    private static final Instant BOUNDARY = OCCURRED_AT.minus(Duration.ofMinutes(LOOKBACK_MINUTES))
            .truncatedTo(VelocityWindowEntity.WINDOW_BUCKET);
    private static final int SCALE = PicClause.TRAN_AMT_SCALE;

    // Values the rule declares.
    private static final int EXPECTED_ORDER = 10;
    private static final int EXPECTED_POINTS = 30;
    private static final String EXPECTED_RULE_ID = "VELOCITY";

    // Synthetic totals at scale three, which the stored column never holds, and stored totals that
    // leave the amount threshold untouched.
    private static final String SUB_CENT_TOTAL = "2499.995";
    private static final String SUB_CENT_DEBIT = "-56.775";
    private static final String NEGATIVE_RUNNING_TOTAL = "-975.775";
    private static final String CLOSING_CREDIT = "3475.77";
    private static final String SMALL_TOTAL = "10.00";
    private static final String BASE_TOTAL = "1200.00";

    // Keys the shipped configuration document publishes: the two this rule reads, the prefix they
    // share, a prefix nothing publishes, and the two other components read.
    private static final String WINDOW_MINUTES_KEY = "carddemo.fraud.risk.velocity-window-minutes";
    private static final String COUNT_KEY = "carddemo.fraud.risk.velocity-count-threshold";
    private static final String PUBLISHED_PREFIX = "carddemo.fraud.risk.";
    private static final String UNPUBLISHED_PREFIX = "carddemo.fraud.velocity.";
    private static final String FLAG_KEY = "carddemo.fraud.risk.flag-threshold";
    private static final String ANOMALY_KEY = "carddemo.fraud.risk.amount-anomaly-threshold";

    /** Name fragments an aggregate finder would carry. */
    private static final List<String> AGGREGATES =
            List.of("sum", "Sum", "avg", "Avg", "max", "Max", "min", "Min");

    @Nested
    @DisplayName("The registration contract")
    class RegistrationContract {

        @Test
        @DisplayName("Registers as a @Component at @Order(10) with one constructor and no @Autowired")
        void registersAtOrderTenWithOneConstructor() {
            Order order = VelocityRule.class.getAnnotation(Order.class);
            Constructor<?> constructor = soleConstructor();
            int modifiers = VelocityRule.class.getModifiers();
            assertNotNull(order, "@Order");
            assertAll("registration of VelocityRule",
                    () -> assertNotNull(VelocityRule.class.getAnnotation(Component.class), "marker"),
                    () -> assertEquals(EXPECTED_ORDER, order.value(), "order value"),
                    () -> assertTrue(Modifier.isPublic(modifiers), "public"),
                    () -> assertFalse(Modifier.isFinal(modifiers), "final"),
                    () -> assertTrue(RiskRule.class.isAssignableFrom(VelocityRule.class), "type"),
                    () -> assertNull(constructor.getAnnotation(Autowired.class), "wiring marker"),
                    () -> assertEquals(3, constructor.getParameterCount(), "parameters"),
                    () -> assertEquals(VelocityWindowRepository.class,
                            constructor.getParameterTypes()[0], "first parameter"));
        }

        @Test
        @DisplayName("Holds private final fields and one collaborator, and exposes two methods")
        void holdsPrivateFinalFieldsAndOneCollaborator() {
            List<Class<?>> types = fields().stream().map(Field::getType).toList();
            List<Method> declared = instanceMethods();
            String repositoryPackage = VelocityWindowRepository.class.getPackageName();
            assertAll("shape of VelocityRule",
                    () -> assertTrue(fields().stream().allMatch(f ->
                            Modifier.isPrivate(f.getModifiers())
                                    && Modifier.isFinal(f.getModifiers())), "private final"),
                    () -> assertFalse(types.contains(double.class) || types.contains(Double.class)
                            || types.contains(float.class) || types.contains(Float.class), "float"),
                    () -> assertFalse(types.contains(Clock.class)
                            || types.contains(RiskRule.class), "clock or rule"),
                    () -> assertEquals(1, types.stream()
                            .filter(t -> t == VelocityWindowRepository.class).count(), "repository"),
                    () -> assertEquals(1, types.stream()
                            .filter(t -> t.getPackageName().equals(repositoryPackage)).count(),
                            "types from the repository package"),
                    () -> assertEquals(List.of("evaluate", "ruleId"),
                            declared.stream().map(Method::getName).sorted().toList(), "methods"),
                    () -> assertTrue(declared.stream()
                            .allMatch(m -> Modifier.isPublic(m.getModifiers())), "public methods"));
        }

        @Test
        @DisplayName("Holds the identifier VELOCITY in one private static final field, and reports it")
        void holdsTheIdentifierInAClassLocalField() throws ReflectiveOperationException {
            List<Field> text = fields().stream().filter(f -> f.getType() == String.class).toList();
            assertEquals(1, text.size(), "String field count");
            Field identifier = text.get(0);
            identifier.setAccessible(true);
            int modifiers = identifier.getModifiers();
            VelocityRule rule = ruleReading(mock(VelocityWindowRepository.class));
            assertAll("the class-local identifier",
                    () -> assertTrue(Modifier.isPrivate(modifiers), "private"),
                    () -> assertTrue(Modifier.isStatic(modifiers), "static"),
                    () -> assertTrue(Modifier.isFinal(modifiers), "final"),
                    () -> assertEquals(EXPECTED_RULE_ID, identifier.get(null), "field value"),
                    () -> assertEquals(EXPECTED_RULE_ID, rule.ruleId(), "reported value"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("Refuses a span width or a count threshold below one, and an absent repository")
        void refusesAConfiguredValueBelowOne(int value) {
            VelocityWindowRepository repository = mock(VelocityWindowRepository.class);
            assertAll("arguments the constructor refuses",
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new VelocityRule(repository, value, COUNT_THRESHOLD), "width"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new VelocityRule(repository, LOOKBACK_MINUTES, value), "count"),
                    () -> assertThrows(NullPointerException.class,
                            () -> new VelocityRule(null, LOOKBACK_MINUTES, COUNT_THRESHOLD), "gap"));
        }
    }

    @Nested
    @DisplayName("Reading the span, and writing nothing")
    class ReadingTheSpan {

        @Test
        @DisplayName("Reads once with the account identifier and the span start, and writes nothing")
        void readsOnceAndWritesNothing() {
            VelocityWindowRepository repository = returning(List.of(storedRow(0, 1, SMALL_TOTAL)));
            ArgumentCaptor<String> account = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
            ruleReading(repository).evaluate(authorization());
            verify(repository, times(1))
                    .findByAccountIdAndWindowStartGreaterThanEqual(account.capture(), from.capture());
            verify(repository, never()).save(any(VelocityWindowEntity.class));
            verify(repository, never()).saveAll(any());
            verify(repository, never()).delete(any(VelocityWindowEntity.class));
            verify(repository, never()).deleteById(any(VelocityWindowEntity.VelocityWindowId.class));
            verify(repository, never()).deleteAll();
            verify(repository, never()).flush();
            assertAll("the two arguments the finder received",
                    () -> assertEquals(ACCOUNT_ID, account.getValue(), "account identifier"),
                    () -> assertEquals(PicClause.XREF_ACCT_ID_WIDTH, account.getValue().length(),
                            "identifier width"),
                    () -> assertEquals(OCCURRED_AT.minus(Duration.ofMinutes(LOOKBACK_MINUTES))
                                    .truncatedTo(VelocityWindowEntity.WINDOW_BUCKET),
                            from.getValue(), "start taken from the event instant"),
                    () -> assertEquals(Instant.parse("2022-06-10T18:00:00Z"), from.getValue(),
                            "expected start, at the bucket the configured width reaches back into"));
        }

        @Test
        @DisplayName("A one-millisecond move of the event instant leaves the span start where it was")
        void aMillisecondMoveLeavesTheSpanStartWhereItWas() {
            Instant onTheHour = Instant.parse("2022-06-10T19:00:00Z");
            Instant oneLater = onTheHour.plusMillis(1L);
            VelocityWindowEntity priorBucket = new VelocityWindowEntity(ACCOUNT_ID,
                    Instant.parse("2022-06-10T18:00:00Z"), COUNT_THRESHOLD,
                    new BigDecimal(SMALL_TOTAL), onTheHour);
            VelocityWindowRepository repository = returningForAnyStart(List.of(priorBucket));
            ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
            VelocityRule rule = ruleReading(repository);

            RiskRule.Contribution first = rule.evaluate(authorizationAt(onTheHour));
            RiskRule.Contribution second = rule.evaluate(authorizationAt(oneLater));

            verify(repository, times(2))
                    .findByAccountIdAndWindowStartGreaterThanEqual(any(), from.capture());
            List<Instant> captured = from.getAllValues();
            assertAll("two events one millisecond apart",
                    () -> assertEquals(Instant.parse("2022-06-10T18:00:00Z"), captured.get(0),
                            "span start of the earlier event"),
                    () -> assertEquals(captured.get(0), captured.get(1), "the two span starts"),
                    () -> assertEquals(first, second, "the two contributions"),
                    () -> assertTrue(first.triggered(), "the earlier verdict"),
                    () -> assertTrue(second.triggered(), "the later verdict"));
        }

        @Test
        @DisplayName("A width below one bucket still reads the bucket that holds it")
        void aWidthBelowOneBucketStillReadsTheBucketHoldingIt() {
            Instant midBucket = Instant.parse("2022-06-10T19:47:00Z");
            VelocityWindowEntity currentBucket = new VelocityWindowEntity(ACCOUNT_ID,
                    Instant.parse("2022-06-10T19:00:00Z"), COUNT_THRESHOLD,
                    new BigDecimal(SMALL_TOTAL), midBucket);
            VelocityWindowRepository repository = returningForAnyStart(List.of(currentBucket));
            ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);

            RiskRule.Contribution contribution =
                    new VelocityRule(repository, 1, COUNT_THRESHOLD).evaluate(
                            authorizationAt(midBucket));

            verify(repository, times(1))
                    .findByAccountIdAndWindowStartGreaterThanEqual(any(), from.capture());
            assertAll("a one-minute width read at 19:47",
                    () -> assertEquals(Instant.parse("2022-06-10T19:00:00Z"), from.getValue(),
                            "span start"),
                    () -> assertTrue(contribution.triggered(), "verdict"));
        }

        @Test
        @DisplayName("Two calls on one event pass the same span start and report equal contributions")
        void twoCallsAgree() {
            VelocityWindowRepository repository = returning(List.of(storedRow(0, 1, SMALL_TOTAL)));
            ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
            VelocityRule rule = ruleReading(repository);
            TransactionAuthorized event = authorization();
            RiskRule.Contribution first = rule.evaluate(event);
            RiskRule.Contribution second = rule.evaluate(event);
            verify(repository, times(2))
                    .findByAccountIdAndWindowStartGreaterThanEqual(any(), from.capture());
            List<Instant> captured = from.getAllValues();
            assertAll("two calls on one event",
                    () -> assertEquals(2, captured.size(), "captured span starts"),
                    () -> assertEquals(captured.get(0), captured.get(1), "the two starts"),
                    () -> assertEquals(BOUNDARY, captured.get(0), "the first start"),
                    () -> assertEquals(first, second, "the two contributions"));
        }
    }

    @Nested
    @DisplayName("Totalling truncates toward zero")
    class TruncationTowardZero {

        /** The derived finder this rule reads its span through. */
        private static final String RULE_FINDER = "findByAccountIdAndWindowStartGreaterThanEqual";

        /** The counting statement this rule raises a window through. */
        private static final String COUNTING_STATEMENT = "addAuthorization";

        /** The bounded retention delete domain/RetentionSweeper owns, which this rule never calls. */
        private static final String RETENTION_STATEMENT = "deleteWindowsStartedBefore";

        @Test
        @DisplayName("The repository declares this rule's finder, the counting statement and the "
                + "retention delete, and no other")
        void declaresTheFinderTheCountingStatementAndTheRetentionDelete() {
            Map<String, Method> declared = Arrays.stream(
                            VelocityWindowRepository.class.getDeclaredMethods())
                    .collect(Collectors.toMap(Method::getName, method -> method));
            Method finder = declared.get(RULE_FINDER);
            Method counter = declared.get(COUNTING_STATEMENT);
            Query countingQuery = counter.getAnnotation(Query.class);

            assertAll("the declared surface",
                    () -> assertEquals(
                            Set.of(RULE_FINDER, COUNTING_STATEMENT, RETENTION_STATEMENT),
                            declared.keySet(), "declared methods"),
                    () -> assertEquals(List.of(), AGGREGATES.stream()
                            .filter(part -> finder.getName().contains(part)).toList(), "aggregates"),
                    () -> assertNull(finder.getAnnotation(Query.class), "finder query marker"),
                    () -> assertNull(finder.getAnnotation(Modifying.class), "finder write marker"),
                    () -> assertNull(finder.getAnnotation(Transactional.class),
                            "finder boundary marker"),
                    () -> assertNotNull(counter.getAnnotation(Modifying.class), "upsert marker"),
                    () -> assertNull(counter.getAnnotation(Transactional.class),
                            "counting statement boundary marker"),
                    () -> assertNotNull(countingQuery, "upsert query"),
                    () -> assertTrue(countingQuery.nativeQuery(), "native upsert"),
                    () -> assertTrue(countingQuery.value().contains("ON CONFLICT"),
                            "conflict-safe update"),
                    () -> assertTrue(countingQuery.value().contains("authorization_count + 1"),
                            "atomic count increment"));
        }

        @Test
        @DisplayName("A synthetic sub-cent total truncates toward zero, and half-up reaches higher")
        void syntheticSubCentTotalTruncatesTowardZero() throws ReflectiveOperationException {
            BigDecimal truncated = down(SUB_CENT_TOTAL);
            BigDecimal halfUp = halfUp(SUB_CENT_TOTAL);
            BigDecimal threshold = decimalField("AMOUNT_THRESHOLD");
            VelocityWindowRepository repository = returning(List.of(subCentRow(1, SUB_CENT_TOTAL)));
            RiskRule.Contribution contribution = ruleReading(repository).evaluate(authorization());
            assertAll("one positive total at scale three",
                    () -> assertNotEquals(truncated, halfUp, "truncated against half-up"),
                    () -> assertEquals(truncated, floor(SUB_CENT_TOTAL), "truncated against floor"),
                    () -> assertTrue(halfUp.compareTo(threshold) >= 0, "half-up reaches it"),
                    () -> assertFalse(truncated.compareTo(threshold) >= 0, "truncated stays below"),
                    () -> assertFalse(contribution.triggered(), "verdict truncation carries"));
        }

        @Test
        @DisplayName("Negative stored values contribute by magnitude and cannot lower the total")
        void negativeStoredValuesContributeByMagnitude() throws ReflectiveOperationException {
            BigDecimal debitMagnitude =
                    new BigDecimal(NEGATIVE_WINDOW_TOTAL).abs()
                            .add(new BigDecimal(SUB_CENT_DEBIT).abs())
                            .setScale(SCALE, RoundingMode.DOWN);
            BigDecimal credit = new BigDecimal(CLOSING_CREDIT);
            BigDecimal threshold = decimalField("AMOUNT_THRESHOLD");
            VelocityWindowRepository repository = returning(List.of(
                    subCentRow(1, NEGATIVE_WINDOW_TOTAL), subCentRow(1, SUB_CENT_DEBIT),
                    storedRow(30, 1, CLOSING_CREDIT)));
            RiskRule.Contribution contribution = ruleReading(repository).evaluate(authorization());
            assertAll("two negative magnitudes and one credit",
                    () -> assertEquals("975.77", debitMagnitude.toPlainString(), "debit magnitude"),
                    () -> assertTrue(debitMagnitude.add(credit).compareTo(threshold) >= 0,
                            "magnitudes reach the threshold"),
                    () -> assertTrue(contribution.triggered(), "verdict truncation carries"));
        }

        @Test
        @DisplayName("Totalling truncates positive values and negative magnitudes toward zero")
        void totallingEqualsTheTruncatedMagnitudeCandidate() throws ReflectiveOperationException {
            BigDecimal opening = decimalField("ZERO_AMOUNT");
            BigDecimal positive = CobolDecimal.add(opening, new BigDecimal(SUB_CENT_TOTAL), SCALE);
            BigDecimal magnitude = CobolDecimal.add(
                    CobolDecimal.add(opening, new BigDecimal(NEGATIVE_WINDOW_TOTAL).abs(), SCALE),
                    new BigDecimal(SUB_CENT_DEBIT).abs(), SCALE);
            assertAll("the totalling the rule performs",
                    () -> assertEquals(down(SUB_CENT_TOTAL), positive, "positive value"),
                    () -> assertNotEquals(halfUp(SUB_CENT_TOTAL), positive, "positive against half-up"),
                    () -> assertEquals("2499.99", positive.toPlainString(), "positive text"),
                    () -> assertEquals("975.77", magnitude.toPlainString(), "magnitude text"));
        }

        @Test
        @DisplayName("The opening total equals a scale-two zero under equals")
        void openingTotalEqualsAScaleTwoZeroUnderEquals() throws ReflectiveOperationException {
            BigDecimal opening = decimalField("ZERO_AMOUNT");
            assertAll("the total the rule opens from",
                    () -> assertEquals(new BigDecimal("0.00"), opening, "against a scale-two zero"),
                    () -> assertEquals(SCALE, opening.scale(), "scale"),
                    () -> assertEquals("0.00", opening.toPlainString(), "plain text"));
        }
    }

    @Nested
    @DisplayName("The trigger decision and its boundary")
    class TriggerDecision {

        @Test
        @DisplayName("An empty result reports no trigger and does not throw")
        void anEmptyResultReportsNoTrigger() {
            VelocityRule rule = ruleReading(returning(List.of()));
            RiskRule.Contribution contribution =
                    assertDoesNotThrow(() -> rule.evaluate(authorization()));
            assertAll("an account holding no row in the span",
                    () -> assertFalse(contribution.triggered(), "triggered"),
                    () -> assertEquals(0, contribution.points(), "points"));
        }

        @ParameterizedTest
        @CsvSource({"1, 4, false", "1, 5, true", "1, 6, true", "2, 2, false", "3, 2, true"})
        @DisplayName("Counts every row the finder returns, and triggers at the count threshold")
        void countsEveryRowTheFinderReturns(int rows, int perRow, boolean expected) {
            List<VelocityWindowEntity> windows = IntStream.range(0, rows)
                    .mapToObj(index -> storedRow(index, perRow, SMALL_TOTAL)).toList();
            RiskRule.Contribution result =
                    ruleReading(returning(windows)).evaluate(authorization());
            assertAll(rows + " rows carrying " + perRow + " authorizations each",
                    () -> assertEquals(expected, result.triggered(), "triggered"),
                    () -> assertEquals(expected ? EXPECTED_POINTS : 0, result.points(), "points"));
        }

        @ParameterizedTest
        @CsvSource({"1300.00, true", "1299.99, false"})
        @DisplayName("Triggers when the total reaches the amount threshold, and not one cent below")
        void triggersWhenTheTotalReachesTheAmountThreshold(String second, boolean expected) {
            VelocityWindowRepository repository =
                    returning(List.of(storedRow(0, 1, BASE_TOTAL), storedRow(30, 1, second)));
            RiskRule.Contribution result = ruleReading(repository).evaluate(authorization());
            assertEquals(expected, result.triggered(), () -> BASE_TOTAL + " added to " + second);
        }
    }

    @Nested
    @DisplayName("The complete scorer's verdict")
    class CompleteScorerVerdict {

        @Test
        @DisplayName("One thirty-point rule clears and a second rule raises the score past 50")
        void configuredThresholdControlsTheFinalVerdict() {
            VelocityWindowRepository repository =
                    returning(List.of(storedRow(0, COUNT_THRESHOLD, SMALL_TOTAL)));
            when(repository.addAuthorization(eq(ACCOUNT_ID), any(), any(), any())).thenReturn(1);
            VelocityRule velocity = ruleReading(repository);

            RiskScoringService.RiskAssessment below = new RiskScoringService(
                    List.of(velocity), repository, propertiesWithFlagThreshold(50))
                    .assess(authorization());
            RiskScoringService.RiskAssessment above = new RiskScoringService(
                    List.of(velocity, new MerchantCategoryRule()), repository,
                    propertiesWithFlagThreshold(50)).assess(authorization());

            assertAll("the shipped threshold against one and two real rules",
                    () -> assertEquals(30, below.riskScore(), "one-rule score"),
                    () -> assertFalse(below.flagged(), "one-rule verdict"),
                    () -> assertEquals(55, above.riskScore(), "two-rule score"),
                    () -> assertTrue(above.flagged(), "two-rule verdict"));
        }
    }

    @Nested
    @DisplayName("The configured property keys")
    class ConfiguredPropertyKeys {

        @Test
        @DisplayName("Binds the two published risk keys, and none outside the published family")
        void bindsTheTwoPublishedRiskKeys() {
            List<String> names = placeholders().stream()
                    .map(text -> partsOf(text)[0]).toList();
            assertAll("the property keys the rule binds",
                    () -> assertEquals(List.of(WINDOW_MINUTES_KEY, COUNT_KEY), names, "names"),
                    () -> assertEquals(List.of(), names.stream()
                            .filter(name -> !name.startsWith(PUBLISHED_PREFIX)).toList(), "outside"),
                    () -> assertEquals(List.of(), names.stream()
                            .filter(name -> name.startsWith(UNPUBLISHED_PREFIX)).toList(), "unbound"),
                    () -> assertEquals(List.of(), names.stream()
                            .filter(name -> name.equals(FLAG_KEY) || name.equals(ANOMALY_KEY))
                            .toList(), "read by another component"));
        }

        @Test
        @DisplayName("Carries an explicit default on each placeholder, and carries none on a field")
        void carriesAnExplicitDefaultOnEachPlaceholder() {
            List<String> raw = placeholders();
            assertAll("where the placeholders sit, and what they default to",
                    () -> assertEquals(2, raw.size(), "annotated parameters"),
                    () -> assertTrue(raw.stream().allMatch(text -> partsOf(text).length == 2),
                            "explicit defaults"),
                    () -> assertEquals(List.of(String.valueOf(LOOKBACK_MINUTES),
                            String.valueOf(COUNT_THRESHOLD)),
                            raw.stream().map(text -> partsOf(text)[1]).toList(), "default values"),
                    () -> assertEquals(List.of(), fields().stream()
                            .filter(field -> field.getAnnotation(Value.class) != null)
                            .map(Field::getName).toList(), "annotated fields"));
        }
    }

    /** Builds the rule under test over one repository stub and the two configured values. */
    private static VelocityRule ruleReading(VelocityWindowRepository repository) {
        return new VelocityRule(repository, LOOKBACK_MINUTES, COUNT_THRESHOLD);
    }

    /** Builds checked settings carrying one flag threshold. */
    private static FraudProperties propertiesWithFlagThreshold(int threshold) {
        return new FraudProperties(
                new FraudProperties.Kafka(new FraudProperties.Kafka.Topics(
                        "transaction.authorized", "fraud.assessed", "carddemo.dead-letter",
                        ".DLT")),
                new FraudProperties.Consumer(new FraudProperties.Consumer.Retry(3, 1_000L)),
                new FraudProperties.Outbox(new FraudProperties.Outbox.Relay(
                        500L, 100, "fraud-relay", Duration.ofMinutes(2L), 20_000L), 168L),
                new FraudProperties.Retention(3_600_000L, 90, 7),
                new FraudProperties.Fraud(new FraudProperties.Fraud.Risk(
                        threshold, LOOKBACK_MINUTES, COUNT_THRESHOLD,
                        new BigDecimal("500.00"))));
    }

    /** Builds a repository stub whose only finder returns {@code rows} for the span under test. */
    private static VelocityWindowRepository returning(List<VelocityWindowEntity> rows) {
        VelocityWindowRepository repository = mock(VelocityWindowRepository.class);
        when(repository.findByAccountIdAndWindowStartGreaterThanEqual(ACCOUNT_ID, BOUNDARY))
                .thenReturn(rows);
        return repository;
    }

    /** Builds a repository stub whose only finder returns {@code rows} for any span start. */
    private static VelocityWindowRepository returningForAnyStart(List<VelocityWindowEntity> rows) {
        VelocityWindowRepository repository = mock(VelocityWindowRepository.class);
        when(repository.findByAccountIdAndWindowStartGreaterThanEqual(any(), any()))
                .thenReturn(rows);
        return repository;
    }

    /** Builds the event under test, carrying the fixed envelope instant and the fixture values. */
    private static TransactionAuthorized authorization() {
        return authorizationAt(OCCURRED_AT);
    }

    /** Builds that same event at one chosen envelope instant. */
    private static TransactionAuthorized authorizationAt(Instant occurredAt) {
        EventEnvelope stamped = EventEnvelope.of(TransactionAuthorized.EVENT_TYPE, ACCOUNT_ID);
        return new TransactionAuthorized(stamped.eventId(), stamped.eventType(),
                stamped.schemaVersion(), occurredAt, stamped.aggregateId(), "0000000000683580",
                "01", "0001", "POS TERM", "Purchase at Abshire-Lowe", new BigDecimal("504.77"),
                "800000000", "Abshire-Lowe", "North Enoshaven", "72112", "************7065", null,
                "2022-06-10 19:27:53.000000", ACCOUNT_ID, TransactionAuthorized.CURRENCY);
    }

    /** Builds one row through the entity constructor, at a bucket inside the span. */
    private static VelocityWindowEntity storedRow(int bucketMinutes, int count, String total) {
        return new VelocityWindowEntity(ACCOUNT_ID,
                BOUNDARY.plus(Duration.ofMinutes(bucketMinutes)), count, new BigDecimal(total),
                OCCURRED_AT);
    }

    /** Builds one row reporting a total at scale three, a scale the stored column never holds. */
    private static VelocityWindowEntity subCentRow(int count, String total) {
        VelocityWindowEntity row = mock(VelocityWindowEntity.class);
        when(row.getAuthorizationCount()).thenReturn(count);
        when(row.getTotalAmount()).thenReturn(new BigDecimal(total));
        return row;
    }

    /** Returns {@code text} truncated toward zero at the scale of the stored amount column. */
    private static BigDecimal down(String text) {
        return new BigDecimal(text).setScale(SCALE, RoundingMode.DOWN);
    }

    /** Returns {@code text} rounded half-up at that same scale. */
    private static BigDecimal halfUp(String text) {
        return new BigDecimal(text).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Returns {@code text} rounded toward negative infinity at that same scale. */
    private static BigDecimal floor(String text) {
        return new BigDecimal(text).setScale(SCALE, RoundingMode.FLOOR);
    }

    /** Returns the one constructor the rule declares. */
    private static Constructor<?> soleConstructor() {
        Constructor<?>[] constructors = VelocityRule.class.getDeclaredConstructors();
        assertEquals(1, constructors.length, "constructor count");
        return constructors[0];
    }

    /** Returns the placeholder text of every {@code Value} marker on that constructor. */
    private static List<String> placeholders() {
        Parameter[] parameters = soleConstructor().getParameters();
        return Arrays.stream(parameters).map(parameter -> parameter.getAnnotation(Value.class))
                .filter(marker -> marker != null).map(Value::value).toList();
    }

    /** Splits one placeholder into its property name and, when present, its default segment. */
    private static String[] partsOf(String placeholder) {
        return placeholder.substring(2, placeholder.length() - 1).split(":", 2);
    }

    /** Returns the declared, non-synthetic fields of the rule. */
    private static List<Field> fields() {
        return Arrays.stream(VelocityRule.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic()).toList();
    }

    /** Returns the declared, non-synthetic instance methods of the rule. */
    private static List<Method> instanceMethods() {
        return Arrays.stream(VelocityRule.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> !Modifier.isStatic(method.getModifiers())).toList();
    }

    /** Reads one named static decimal field of the rule. */
    private static BigDecimal decimalField(String name) throws ReflectiveOperationException {
        Field field = VelocityRule.class.getDeclaredField(name);
        field.setAccessible(true);
        return (BigDecimal) field.get(null);
    }
}

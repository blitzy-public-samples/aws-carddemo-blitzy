package com.carddemo.fraud.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.domain.RiskScoringService.RiskAssessment;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;

/**
 * Pins the scoring contract of {@link RiskScoringService} and of its nested
 * {@link RiskAssessment}. The assessment and the window row share one clock read. Each call writes
 * the window once. The score sums every contribution and stops at the upper bound, and the
 * configured threshold sets the verdict.
 *
 * <p>The CardDemo COBOL (Common Business Oriented Language) source scores no risk and counts no
 * authorization velocity, so the subject is ADDITIVE IN FULL: net new; no COBOL ancestor.
 *
 * <p>Plain JUnit 5 with Mockito stubs. No Spring context starts and no file is read. Two
 * deviations these assertions surface, the absent transaction boundary and the window update that
 * precedes evaluation, are recorded in {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("RiskScoringService, the scorer behind one fraud assessment")
class RiskScoringServiceTest {

    // The class under test, then record 1 of the daily transaction fixture. Identifiers are text.
    private static final Class<RiskScoringService> SUBJECT = RiskScoringService.class;
    private static final String ACCOUNT_ID = "00000000007";
    private static final String TRANSACTION_ID = "0000000000683580";
    private static final Instant OCCURRED_AT = Instant.parse("2022-06-10T19:27:53Z");
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";
    private static final String FIXTURE_AMOUNT = "504.77";
    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0001";
    private static final String MERCHANT_ID = "800000000";
    private static final String MASKED_CARD_NUMBER = "************7065";
    private static final String EVENT_ID = "7f1c9a2e-4b6d-4a11-9c3e-5d8f2a6b0c41";

    // The hour boundary every window write carries, then a timestamp text naming another hour.
    private static final Instant BUCKET_START = Instant.parse("2022-06-10T19:00:00Z");
    private static final String AUTHORIZED_AT_EARLIER_HOUR = "2022-06-10 03:27:53.000000";
    private static final Instant EARLIER_BUCKET_START = Instant.parse("2022-06-10T03:00:00Z");

    /** The instant a fixed clock reports, well after the 2022 event the fixture carries. */
    private static final Instant SCORED_AT = Instant.parse("2026-03-04T05:06:07.890Z");

    // Identifiers the rule stubs report, the shipped threshold, the bounds, and one written row.
    private static final String FIRST_RULE = "RULE_ONE";
    private static final String SECOND_RULE = "RULE_TWO";
    private static final String THIRD_RULE = "RULE_THREE";
    private static final List<String> ONE_RULE = List.of(FIRST_RULE);
    private static final int SHIPPED_THRESHOLD = 50;
    /** The floor the event contract declares, read from the contract and never restated. */
    private static final int LOWEST_SCORE = FraudFlagged.MINIMUM_RISK_SCORE;

    /** The ceiling the event contract declares, which the accumulated points hold at. */
    private static final int HIGHEST_SCORE = FraudFlagged.MAXIMUM_RISK_SCORE;
    private static final int ONE_ROW = 1;

    /** Window writes one assessment performs, so one stamp is written and not several. */
    private static final int ONE_WINDOW_WRITE = 1;
    private static final FraudProperties SHIPPED_SETTINGS = settingsFlaggingAt(SHIPPED_THRESHOLD);

    /**
     * Collaborator kinds the scorer holds no field of, because each belongs to the consumer.
     *
     * <p>{@code Clock} left this list when the scorer took one. It was here while the class read
     * {@code Instant.now()} directly, and a review of the delivered platform found that the one
     * value an assessment reports which no test could name. The clock now arrives through the
     * constructor, so {@code aFixedClockFixesTheMomentScored} names it. The four that remain are
     * observability, and the consumer owns all four.
     */
    private static final List<String> OUTSIDE_TYPES =
            List.of("Logger", "MeterRegistry", "Counter", "Timer");

    @Nested
    @DisplayName("The declared shape of the scorer")
    class DeclaredShape {

        /**
         * Two constructors, and only one of them is an injection point.
         *
         * <p>The class carried one constructor while it read {@code Instant.now()} directly. It now
         * takes a clock, under the shape {@code notification/domain/NotificationService} already
         * uses: a public constructor the framework calls, which supplies {@link Clock#systemUTC()},
         * and a package-private one a test hands a fixed clock to. Two constructors make the
         * injection point ambiguous, so the public one carries {@code @Autowired} and the earlier
         * assertion that no constructor carried it is withdrawn with the single-constructor shape it
         * described.
         *
         * <p>Both are selected by parameter count rather than by position.
         * {@code getDeclaredConstructors} fixes no order, so reading index zero would have passed or
         * failed on whichever the virtual machine happened to list first.
         */
        @Test
        @DisplayName("Registers as a public, non-final @Service with two constructors and no boundary")
        void registersAsAServiceTakingThreeCollaborators() {
            List<String> annotations = annotationNames(SUBJECT.getAnnotations());
            Constructor<?> injected = constructorTaking(3);
            Constructor<?> withClock = constructorTaking(4);
            List<String> parameters = Arrays.stream(injected.getParameterTypes())
                    .map(Class::getSimpleName).toList();
            List<String> withClockParameters = Arrays.stream(withClock.getParameterTypes())
                    .map(Class::getSimpleName).toList();
            assertAll("the stereotype, the modifiers and the injection point",
                    () -> assertTrue(SUBJECT.isAnnotationPresent(Service.class), "stereotype"),
                    () -> assertFalse(annotations.contains("Transactional"), "boundary"),
                    () -> assertFalse(annotations.contains("Order"), "ordering of its own"),
                    () -> assertFalse(Ordered.class.isAssignableFrom(SUBJECT), "ordering interface"),
                    () -> assertTrue(Modifier.isPublic(SUBJECT.getModifiers()), "public"),
                    () -> assertFalse(Modifier.isFinal(SUBJECT.getModifiers()), "open to a proxy"),
                    () -> assertEquals(2, SUBJECT.getDeclaredConstructors().length, "count"),
                    () -> assertEquals(List.of("List", "VelocityWindowRepository",
                            "FraudProperties"), parameters, "parameters in sequence"),
                    () -> assertEquals("java.util.List<com.carddemo.fraud.domain.RiskRule>",
                            injected.getGenericParameterTypes()[0].getTypeName(), "element type"),
                    () -> assertTrue(Modifier.isPublic(injected.getModifiers()),
                            "the framework calls the three-argument constructor"),
                    () -> assertTrue(annotationNames(injected.getAnnotations())
                            .contains("Autowired"), "the one injection point names itself"),
                    () -> assertEquals(List.of("List", "VelocityWindowRepository", "FraudProperties",
                            "Clock"), withClockParameters, "the clock arrives last"),
                    () -> assertFalse(Modifier.isPublic(withClock.getModifiers()),
                            "the clock-taking constructor is for this package alone"),
                    () -> assertFalse(annotationNames(withClock.getAnnotations())
                            .contains("Autowired"), "only one constructor is an injection point"));
        }

        @Test
        @DisplayName("Holds every field private and final, names both bounds, and declares only assess")
        void holdsEveryFieldPrivateAndFinal() throws ReflectiveOperationException {
            List<Field> fields = Arrays.stream(SUBJECT.getDeclaredFields())
                    .filter(field -> !field.isSynthetic()).toList();
            List<String> onParameters = Arrays.stream(SUBJECT.getDeclaredConstructors())
                    .map(Constructor::getParameterAnnotations).flatMap(Arrays::stream)
                    .flatMap(Arrays::stream).map(item -> item.annotationType().getSimpleName())
                    .filter("Value"::equals).toList();
            List<String> surface = Arrays.stream(SUBJECT.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .map(Method::getName).sorted().toList();
            List<Integer> bounds = new ArrayList<>();
            for (Field field : fields) {
                if (field.getType() == int.class && Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    bounds.add(field.getInt(null));
                }
            }
            assertAll("the declared members",
                    () -> assertEquals(List.of(), names(fields, field -> !Modifier.isPrivate(
                            field.getModifiers()) || !Modifier.isFinal(field.getModifiers())),
                            "not private and final"),
                    () -> assertEquals(List.of(), names(fields, field -> OUTSIDE_TYPES
                            .contains(field.getType().getSimpleName())), "outside the contract"),
                    () -> assertEquals(List.of(), names(fields, field -> annotationNames(
                            field.getAnnotations()).contains("Value")), "field bound to a property"),
                    () -> assertEquals(List.of(), onParameters, "parameter bound to a property"),
                    () -> assertTrue(bounds.contains(LOWEST_SCORE), "lower bound named"),
                    () -> assertTrue(bounds.contains(HIGHEST_SCORE), "upper bound named"),
                    () -> assertEquals(List.of("assess"), surface, "public instance methods"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 101})
        @DisplayName("Refuses an absent collaborator, and a threshold that no score could reach")
        void refusesAnAbsentCollaboratorAndAnUnreachableThreshold(int threshold) {
            VelocityWindowRepository windows = mock(VelocityWindowRepository.class);
            assertAll("the three constructor arguments",
                    () -> assertThrows(NullPointerException.class,
                            () -> new RiskScoringService(null, windows, SHIPPED_SETTINGS), "rules"),
                    () -> assertThrows(NullPointerException.class,
                            () -> new RiskScoringService(List.of(), null, SHIPPED_SETTINGS), "store"),
                    () -> assertThrows(NullPointerException.class,
                            () -> new RiskScoringService(List.of(), windows, null), "settings"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new RiskScoringService(List.of(), windows,
                                    settingsFlaggingAt(threshold)), "threshold"));
        }

        @Test
        @DisplayName("Copies the rule list, so a rule added to it afterwards never evaluates")
        void copiesTheRuleListAtConstruction() {
            TransactionAuthorized event = authorization(FIXTURE_AMOUNT);
            List<RiskRule> supplied = new ArrayList<>();
            supplied.add(triggering(FIRST_RULE, 10));
            RiskScoringService scorer =
                    new RiskScoringService(supplied, countingWindow(), SHIPPED_SETTINGS);
            RiskRule late = triggering(SECOND_RULE, 40);
            supplied.add(late);

            RiskAssessment assessment = scorer.assess(event);

            assertAll("the assessment after a later addition",
                    () -> verify(late, never()).evaluate(event),
                    () -> assertEquals(10, assessment.riskScore(), "score"),
                    () -> assertEquals(ONE_RULE, assessment.triggeredRules(), "rules"));
        }
    }

    @Nested
    @DisplayName("One instant, shared by the assessment and the window row's update stamp")
    class OneSharedInstant {

        /**
         * Asserts one instant reaches both the returned assessment and the window row's stamp.
         *
         * <p>This is the guarantee the class offers, stated as what it is. The test once claimed the
         * clock was read once, which it could not establish from outside, and the honest claim is the
         * one below: a second read would let the assessment and the row disagree, and
         * {@code assertSame} refuses that. The clock is injectable now, so the read count is
         * observable through a counting clock; that is a different property from this one, and
         * {@code aFixedClockFixesTheMomentScored} below holds the value rather than the count.
         *
         * <p>Two wall-clock readings taken around the call used to bracket the instant. That
         * established nothing about the read count, and a clock moved backward between the two
         * readings failed it while the code was correct. Nothing here reads a wall clock.
         *
         * <p>Three claims replace the bracket, all deterministic. The stamp is the same object the
         * assessment carries. The stamp is the fourth argument and the window start is the second, so
         * the two instants the row receives are not conflated: the start comes from the event and the
         * stamp does not. And the stamp is not the event's own instant, which is the realistic way a
         * stamp read at call time would be wrong. The event is dated 2022, so that last claim holds
         * under any clock adjustment a running system could see.
         */
        @Test
        @DisplayName("Hands the window row the same instant the assessment carries")
        void handsTheWindowRowTheSameInstantTheAssessmentCarries() {
            VelocityWindowRepository windows = countingWindow();
            ArgumentCaptor<Instant> windowStart = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> stamp = ArgumentCaptor.forClass(Instant.class);
            RiskScoringService scorer = scorer(windows, triggering(FIRST_RULE, 10));

            RiskAssessment assessment = scorer.assess(authorization(FIXTURE_AMOUNT));

            verify(windows, times(ONE_WINDOW_WRITE)).addAuthorization(eq(ACCOUNT_ID),
                    windowStart.capture(), any(), stamp.capture());
            assertAll("the one instant the assessment and the row share",
                    () -> assertSame(assessment.assessedAt(), stamp.getValue(),
                            "the row's stamp is the very object the assessment carries"),
                    () -> assertNotNull(assessment.assessedAt(), "the assessment is stamped"),
                    () -> assertEquals(BUCKET_START, windowStart.getValue(),
                            "the window start comes from the event, truncated to its bucket"),
                    () -> assertNotEquals(windowStart.getValue(), stamp.getValue(),
                            "the start and the stamp are two different instants, not one value"
                                    + " passed twice"),
                    () -> assertNotEquals(OCCURRED_AT, assessment.assessedAt(),
                            "the stamp is read when the assessment runs and is not the event's own"
                                    + " instant"));
        }

        @Test
        @DisplayName("Starts the window at the event time truncated, not at the hour the text names")
        void startsTheWindowAtTheTruncatedEventTime() throws ReflectiveOperationException {
            VelocityWindowRepository windows = countingWindow();
            ArgumentCaptor<Instant> bucket = ArgumentCaptor.forClass(Instant.class);

            scorer(windows, triggering(FIRST_RULE, 10))
                    .assess(authorization(FIXTURE_AMOUNT, AUTHORIZED_AT_EARLIER_HOUR));

            verify(windows).addAuthorization(eq(ACCOUNT_ID), bucket.capture(), any(), any());
            Field declared = Arrays.stream(SUBJECT.getDeclaredFields())
                    .filter(field -> field.getType() == ChronoUnit.class).findFirst().orElseThrow();
            declared.setAccessible(true);
            ChronoUnit width = (ChronoUnit) declared.get(null);
            assertAll("the bucket start of a 2022 event",
                    () -> assertEquals(BUCKET_START, bucket.getValue(), "hour boundary"),
                    () -> assertEquals(OCCURRED_AT.truncatedTo(width), bucket.getValue(),
                            "the event time under the width the class names"),
                    () -> assertNotEquals(EARLIER_BUCKET_START, bucket.getValue(),
                            "the hour the authorization text names"));
        }

        /**
         * A fixed clock fixes the moment scored, which is what taking the clock buys.
         *
         * <p>The class read {@code Instant.now()} before, so the one value every assessment and every
         * window row carries could be bracketed and never named. Nothing could assert that the row's
         * stamp was the moment the assessment ran rather than any moment at all, and nothing could
         * reproduce a run. Both hold now.
         */
        @Test
        @DisplayName("A fixed clock fixes the moment scored, on the assessment and on the row")
        void aFixedClockFixesTheMomentScored() {
            VelocityWindowRepository windows = countingWindow();
            ArgumentCaptor<Instant> stamp = ArgumentCaptor.forClass(Instant.class);
            RiskScoringService scorer = new RiskScoringService(
                    List.of(triggering(FIRST_RULE, 10)), windows, SHIPPED_SETTINGS,
                    Clock.fixed(SCORED_AT, ZoneOffset.UTC));

            RiskAssessment assessment = scorer.assess(authorization(FIXTURE_AMOUNT));

            verify(windows, times(ONE_WINDOW_WRITE))
                    .addAuthorization(eq(ACCOUNT_ID), any(), any(), stamp.capture());
            assertAll("the moment a fixed clock reports",
                    () -> assertEquals(SCORED_AT, assessment.assessedAt(), "the assessment"),
                    () -> assertEquals(SCORED_AT, stamp.getValue(), "the window row's stamp"));
        }

        /**
         * The clock is read once per assessment, which the injected clock finally makes observable.
         *
         * <p>A counting clock answers a new instant on every read, so two reads would give the
         * assessment and the row two different values. One read is what makes them one value.
         */
        @Test
        @DisplayName("The clock is read once, so a moving clock cannot split the two values")
        void theClockIsReadOncePerAssessment() {
            VelocityWindowRepository windows = countingWindow();
            ArgumentCaptor<Instant> stamp = ArgumentCaptor.forClass(Instant.class);
            CountingClock ticking = new CountingClock(SCORED_AT);
            RiskScoringService scorer = new RiskScoringService(
                    List.of(triggering(FIRST_RULE, 10)), windows, SHIPPED_SETTINGS, ticking);

            RiskAssessment assessment = scorer.assess(authorization(FIXTURE_AMOUNT));

            verify(windows, times(ONE_WINDOW_WRITE))
                    .addAuthorization(eq(ACCOUNT_ID), any(), any(), stamp.capture());
            assertAll("one assessment against a clock that moves on every read",
                    () -> assertEquals(1, ticking.reads(), "reads of the clock"),
                    () -> assertEquals(SCORED_AT, assessment.assessedAt(), "the first reading"),
                    () -> assertSame(assessment.assessedAt(), stamp.getValue(),
                            "the row carries the same object, so no second reading happened"));
        }
    }

    @Nested
    @DisplayName("The window update and its place in the call")
    class WindowUpdate {

        @Test
        @DisplayName("Counts the authorization into its window once, before any rule evaluates")
        void countsTheAuthorizationBeforeAnyRuleEvaluates() {
            TransactionAuthorized event = authorization(FIXTURE_AMOUNT);
            VelocityWindowRepository windows = countingWindow();
            RiskRule first = triggering(FIRST_RULE, 10);
            RiskRule second = triggering(SECOND_RULE, 20);

            new RiskScoringService(List.of(first, second), windows, SHIPPED_SETTINGS)
                    .assess(event);

            InOrder sequence = inOrder(windows, first, second);
            sequence.verify(windows).addAuthorization(eq(ACCOUNT_ID), any(), any(), any());
            sequence.verify(first).evaluate(event);
            sequence.verify(second).evaluate(event);
            verify(windows, times(1)).addAuthorization(eq(ACCOUNT_ID), any(), any(), any());
            verify(first).ruleId();
            verify(second).ruleId();
            verifyNoMoreInteractions(windows, first, second);
        }

        @ParameterizedTest
        @CsvSource({"504.77, 504.77", "-919.00, 919.00", "-56.77, 56.77"})
        @DisplayName("Passes the account identifier and the amount magnitude at transaction scale")
        void passesTheAccountIdentifierAndTheAmountMagnitude(String amount, String magnitude) {
            VelocityWindowRepository windows = countingWindow();
            ArgumentCaptor<BigDecimal> counted = ArgumentCaptor.forClass(BigDecimal.class);

            scorer(windows, triggering(FIRST_RULE, 10)).assess(authorization(amount));

            verify(windows).addAuthorization(eq(ACCOUNT_ID), any(), counted.capture(), any());
            assertEquals(new BigDecimal(magnitude), counted.getValue());
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 2})
        @DisplayName("Stops before any rule when the window statement reports another row count")
        void stopsBeforeAnyRuleOnAnotherRowCount(int rows) {
            TransactionAuthorized event = authorization(FIXTURE_AMOUNT);
            VelocityWindowRepository windows = mock(VelocityWindowRepository.class);
            when(windows.addAuthorization(eq(ACCOUNT_ID), any(), any(), any())).thenReturn(rows);
            RiskRule rule = triggering(FIRST_RULE, 10);
            RiskScoringService scorer = scorer(windows, rule);

            assertThrows(IllegalStateException.class, () -> scorer.assess(event));
            verify(rule, never()).evaluate(event);
        }
    }

    @Nested
    @DisplayName("Summing the contributions, clamping the total and setting the verdict")
    class Scoring {

        @Test
        @DisplayName("Clears a transaction when no rule is registered, and still writes the window")
        void clearsATransactionWhenNoRuleIsRegistered() {
            VelocityWindowRepository windows = countingWindow();

            RiskAssessment assessment = new RiskScoringService(List.of(), windows,
                    SHIPPED_SETTINGS).assess(authorization(FIXTURE_AMOUNT));

            assertAll("the assessment of an empty rule list",
                    () -> assertEquals(LOWEST_SCORE, assessment.riskScore(), "score"),
                    () -> assertEquals(List.of(), assessment.triggeredRules(), "rules"),
                    () -> assertFalse(assessment.flagged(), "verdict"),
                    () -> verify(windows, times(1))
                            .addAuthorization(eq(ACCOUNT_ID), any(), any(), any()));
        }

        @Test
        @DisplayName("Sums every contribution, skips a silent rule, and holds the total at the bound")
        void sumsEveryContributionAndHoldsTheUpperBound() {
            RiskRule silent = mock(RiskRule.class);
            when(silent.evaluate(any())).thenReturn(RiskRule.Contribution.notTriggered());
            when(silent.ruleId()).thenReturn(THIRD_RULE);
            RiskAssessment summed = scorer(countingWindow(), triggering(FIRST_RULE, 10),
                    triggering(SECOND_RULE, 25), silent).assess(authorization(FIXTURE_AMOUNT));
            RiskAssessment clamped = scorer(countingWindow(), triggering(FIRST_RULE, 60),
                    triggering(SECOND_RULE, 60)).assess(authorization(FIXTURE_AMOUNT));

            assertAll("two contributions and one silent rule, then two that pass the bound",
                    () -> assertEquals(35, summed.riskScore(), "sum"),
                    () -> assertEquals(List.of(FIRST_RULE, SECOND_RULE), summed.triggeredRules(),
                            "the silent rule is absent"),
                    () -> assertEquals(HIGHEST_SCORE, clamped.riskScore(), "upper bound"));
        }

        @ParameterizedTest
        @CsvSource({"29, true", "30, true", "31, false"})
        @DisplayName("Flags a transaction whose score reaches the configured threshold")
        void flagsAScoreThatReachesTheConfiguredThreshold(int threshold, boolean flagged) {
            RiskAssessment assessment = new RiskScoringService(
                    List.of(triggering(FIRST_RULE, 30)), countingWindow(),
                    settingsFlaggingAt(threshold)).assess(authorization(FIXTURE_AMOUNT));

            assertAll("one thirty-point rule against three thresholds",
                    () -> assertEquals(30, assessment.riskScore(), "score"),
                    () -> assertEquals(flagged, assessment.flagged(), "verdict"));
        }

        @Test
        @DisplayName("Lists the triggered rules in the order the rules were supplied")
        void listsTheTriggeredRulesInTheOrderSupplied() {
            RiskAssessment assessment = scorer(countingWindow(), triggering(THIRD_RULE, 5),
                    triggering(FIRST_RULE, 5), triggering(SECOND_RULE, 5))
                    .assess(authorization(FIXTURE_AMOUNT));

            assertEquals(List.of(THIRD_RULE, FIRST_RULE, SECOND_RULE),
                    assessment.triggeredRules());
        }
    }

    @Nested
    @DisplayName("The nested RiskAssessment record")
    class Assessment {

        @Test
        @DisplayName("Declares six components in sequence, with the verdict among them")
        void declaresSixComponentsInSequence() {
            RecordComponent[] components = RiskAssessment.class.getRecordComponents();
            List<String> types = Arrays.stream(components)
                    .map(component -> component.getType().getSimpleName()).toList();
            assertAll("the record components",
                    () -> assertEquals(6, components.length, "count"),
                    () -> assertEquals(List.of("transactionId", "accountId", "riskScore", "flagged",
                            "triggeredRules", "assessedAt"), Arrays.stream(components)
                            .map(RecordComponent::getName).toList(), "names in sequence"),
                    () -> assertEquals(List.of("String", "String", "int", "boolean", "List",
                            "Instant"), types, "types in sequence"),
                    () -> assertEquals("java.util.List<java.lang.String>",
                            components[4].getGenericType().getTypeName(), "rule element type"));
        }

        @Test
        @DisplayName("Refuses an absent identifier, an absent rule list and an absent moment")
        void refusesAnAbsentComponent() {
            assertAll("the four reference components",
                    () -> assertThrows(NullPointerException.class,
                            () -> assessment(null, ACCOUNT_ID, ONE_RULE, OCCURRED_AT), "transaction"),
                    () -> assertThrows(NullPointerException.class,
                            () -> assessment(TRANSACTION_ID, null, ONE_RULE, OCCURRED_AT), "account"),
                    () -> assertThrows(NullPointerException.class,
                            () -> assessment(TRANSACTION_ID, ACCOUNT_ID, null, OCCURRED_AT), "rules"),
                    () -> assertThrows(NullPointerException.class,
                            () -> assessment(TRANSACTION_ID, ACCOUNT_ID, ONE_RULE, null), "moment"));
        }

        @Test
        @DisplayName("Copies the rule list and hands back a list that refuses an addition")
        void copiesTheRuleListAndRefusesAnAddition() {
            List<String> supplied = new ArrayList<>();
            supplied.add(FIRST_RULE);
            RiskAssessment copied = assessment(TRANSACTION_ID, ACCOUNT_ID, supplied, OCCURRED_AT);
            supplied.add(SECOND_RULE);

            assertAll("the rule list the record hands back",
                    () -> assertEquals(ONE_RULE, copied.triggeredRules(),
                            "unchanged by the later addition"),
                    () -> assertThrows(UnsupportedOperationException.class,
                            () -> copied.triggeredRules().add(SECOND_RULE), "addition"));
        }

        @Test
        @DisplayName("Refuses a score outside the bounds and a repeated identifier, and compares "
                + "by component")
        void refusesAScoreOutsideTheBoundsAndARepeatedIdentifier() {
            IllegalArgumentException repeated = assertThrows(IllegalArgumentException.class,
                    () -> assessment(TRANSACTION_ID, ACCOUNT_ID, List.of(FIRST_RULE, FIRST_RULE),
                            OCCURRED_AT));

            assertAll("what the canonical constructor refuses and what it keeps",
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> scoring(LOWEST_SCORE - 1), "below the lower bound"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> scoring(HIGHEST_SCORE + 1), "above the upper bound"),
                    () -> assertEquals(LOWEST_SCORE, scoring(LOWEST_SCORE).riskScore(), "lower"),
                    () -> assertEquals(HIGHEST_SCORE, scoring(HIGHEST_SCORE).riskScore(), "upper"),
                    () -> assertTrue(repeated.getMessage().contains("/triggeredRules"),
                            repeated.getMessage()),
                    () -> assertEquals(scoring(40), scoring(40), "value semantics"));
        }
    }

    /** Builds a scorer over {@code rules} at the shipped threshold. */
    private static RiskScoringService scorer(VelocityWindowRepository windows, RiskRule... rules) {
        return new RiskScoringService(List.of(rules), windows, SHIPPED_SETTINGS);
    }

    /** Builds a store stub whose window statement reports the one row it writes. */
    private static VelocityWindowRepository countingWindow() {
        VelocityWindowRepository windows = mock(VelocityWindowRepository.class);
        when(windows.addAuthorization(eq(ACCOUNT_ID), any(), any(), any())).thenReturn(ONE_ROW);
        return windows;
    }

    /** Builds a rule stub that triggers for {@code points} and reports {@code identifier}. */
    private static RiskRule triggering(String identifier, int points) {
        RiskRule rule = mock(RiskRule.class);
        when(rule.evaluate(any())).thenReturn(RiskRule.Contribution.triggeredWith(points));
        when(rule.ruleId()).thenReturn(identifier);
        return rule;
    }

    /** Builds one authorized transaction from the fixture at the given amount. */
    private static TransactionAuthorized authorization(String amount) {
        return authorization(amount, AUTHORIZED_AT);
    }

    /**
     * Builds one authorized transaction at the given amount and authorization timestamp text.
     * Version 1 of the contract carries no card token, so that component is absent.
     */
    private static TransactionAuthorized authorization(String amount, String authorizedAt) {
        return new TransactionAuthorized(UUID.fromString(EVENT_ID),
                TransactionAuthorized.EVENT_TYPE, EventEnvelope.SCHEMA_VERSION, OCCURRED_AT,
                ACCOUNT_ID, TRANSACTION_ID, TYPE_CODE, CATEGORY_CODE, "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal(amount), MERCHANT_ID, "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, null, authorizedAt, ACCOUNT_ID,
                TransactionAuthorized.CURRENCY);
    }

    /**
     * Builds the bound settings for one flag threshold. The scorer reads one risk threshold and no
     * other property, so only the risk block holds a value.
     */
    private static FraudProperties settingsFlaggingAt(int threshold) {
        return new FraudProperties(null, null, null, null,
                new FraudProperties.Fraud(new FraudProperties.Fraud.Risk(threshold, 60, 5,
                        new BigDecimal("500.00"))));
    }

    /** Builds one assessment from the four reference components, at a fixed score and verdict. */
    private static RiskAssessment assessment(String transaction, String account,
            List<String> rules, Instant moment) {
        return new RiskAssessment(transaction, account, 10, false, rules, moment);
    }

    /** Builds one assessment at {@code score}, carrying one rule identifier. */
    private static RiskAssessment scoring(int score) {
        return new RiskAssessment(TRANSACTION_ID, ACCOUNT_ID, score, false, ONE_RULE, OCCURRED_AT);
    }

    /** Reads the name of each field the given test keeps. */
    private static List<String> names(List<Field> fields, Predicate<Field> kept) {
        return fields.stream().filter(kept).map(Field::getName).toList();
    }

    /** Reads the simple name of each annotation. */
    private static List<String> annotationNames(Annotation[] annotations) {
        return Arrays.stream(annotations)
                .map(annotation -> annotation.annotationType().getSimpleName()).toList();
    }

    /**
     * Returns the declared constructor taking a given number of parameters.
     *
     * <p>{@code getDeclaredConstructors} fixes no order, so a test that read index zero would pass or
     * fail on whichever the virtual machine happened to list first.
     *
     * @param parameters the parameter count to select on
     * @return the one declared constructor of that arity
     */
    private static Constructor<?> constructorTaking(int parameters) {
        return Arrays.stream(SUBJECT.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == parameters)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no declared constructor takes " + parameters + " parameters"));
    }

    /**
     * A clock that answers one second later on every read, so a second read becomes visible.
     *
     * <p>A fixed clock cannot show how many times it was read. This one can: two reads inside one
     * assessment would hand the assessment and the window row two different instants.
     */
    private static final class CountingClock extends Clock {

        /** The instant the first read answers. */
        private final Instant base;

        /** Reads taken so far, which is also the seconds added to the next answer. */
        private int reads;

        private CountingClock(Instant base) {
            this.base = base;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant answered = base.plusSeconds(reads);
            reads++;
            return answered;
        }

        /** Returns how many times this clock has been read. */
        private int reads() {
            return reads;
        }
    }
}

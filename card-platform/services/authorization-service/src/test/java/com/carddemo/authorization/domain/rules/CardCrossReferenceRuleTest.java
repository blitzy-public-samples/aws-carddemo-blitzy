package com.carddemo.authorization.domain.rules;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.events.DeclineReason;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.Order;

/**
 * Unit tests for {@link CardCrossReferenceRule}, which assigns reject code {@code 0100}.
 *
 * <p>Subject paragraph {@code 1500-A-LOOKUP-XREF} at {@code app/cbl/CBTRN02C.cbl:L380-L392}, quoted
 * below. {@code app/cbl/CBTRN02C.cbl:L382-L383} moves the card number into the key field and reads
 * the cross-reference dataset. Its {@code INVALID KEY} limb at
 * {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns the code and its text.
 *
 * <pre>{@code
 *       1500-A-LOOKUP-XREF.
 *      *    DISPLAY 'CARD NUMBER: ' DALYTRAN-CARD-NUM
 *           MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
 *           READ XREF-FILE INTO CARD-XREF-RECORD
 *              INVALID KEY
 *                MOVE 100 TO WS-VALIDATION-FAIL-REASON
 *                MOVE 'INVALID CARD NUMBER FOUND'
 *                  TO WS-VALIDATION-FAIL-REASON-DESC
 *              NOT INVALID KEY
 *      *           DISPLAY 'ACCOUNT RECORD FOUND'
 *                  CONTINUE
 *           END-READ
 *           EXIT.
 * }</pre>
 *
 * <p>Both commented lines above are reproduced nowhere, and this rule writes no log line.
 *
 * <p>A keyed read that finds no row answers with a reject reason and raises nothing. The batch
 * ancestor treats a reject as ordinary traffic: {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the job
 * with return code 4 once any record was rejected. Its abend routine at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711} serves file and write failures, and no reject reaches it.
 *
 * <p>The lookup key is the full sixteen characters. {@code XREF-CARD-NUM PIC X(16)} at
 * {@code app/cpy/CVACT03Y.cpy:L5} declares that width, and {@code DALYTRAN-CARD-NUM PIC X(16)} at
 * {@code app/cpy/CVTRA06Y.cpy:L15} matches it. {@code KEYS(16 0)} at
 * {@code app/jcl/XREFFILE.jcl:L43} makes that field the primary key, and {@code NONUNIQUEKEY} at
 * {@code app/jcl/XREFFILE.jcl:L75} governs the account index this rule never reads. The match runs
 * on text, so a leading zero counts.
 *
 * <p>Reject code {@code 0100} is reached by none of the 300 records of
 * {@code app/data/ASCII/dailytran.txt}. Every card those records carry resolves in
 * {@code app/data/ASCII/cardxref.txt}, so each scenario below builds its own input. Fixture values
 * are sliced at the copybook offsets, never read off as a gloss of the digits.
 *
 * <p>Two deliberate non-additions carry an assertion. No check-digit test:
 * {@code app/cbl/COCRDUPC.cbl:L193-L194} tests a card number for the numeric class and names
 * sixteen digits, and the subject paragraph tests no format at all. No customer read:
 * {@code XREF-CUST-ID} at {@code app/cpy/CVACT03Y.cpy:L6} arrives on the resolved row.
 *
 * <p>Three more carry none. No card status, since {@code app/jcl/POSTTRAN.jcl} allocates no card
 * dataset. No account status, since this rule reads no account. No amount, limit or expiry test,
 * which the rules after this one own. The card number stays whole here, and masking happens at the
 * serialization boundary.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
final class CardCrossReferenceRuleTest {

    /**
     * The card number record one of {@code app/data/ASCII/dailytran.txt} carries at positions
     * 263-278. Row 21 of {@code app/data/ASCII/cardxref.txt} resolves it.
     */
    private static final String FIXTURE_CARD_NUMBER = "4859452612877065";

    /**
     * The customer identifier row 21 of {@code app/data/ASCII/cardxref.txt} carries at positions
     * 17-25, the width {@code XREF-CUST-ID PIC 9(09)} declares at {@code app/cpy/CVACT03Y.cpy:L6}.
     */
    private static final String FIXTURE_CUSTOMER_ID = "000000007";

    /**
     * The account identifier row 21 of {@code app/data/ASCII/cardxref.txt} carries at positions
     * 26-36, the width {@code XREF-ACCT-ID PIC 9(11)} declares at {@code app/cpy/CVACT03Y.cpy:L7}.
     */
    private static final String FIXTURE_ACCOUNT_ID = "00000000007";

    /** Sixteen digits no row of {@code app/data/ASCII/cardxref.txt} carries. */
    private static final String UNRESOLVABLE_CARD_NUMBER = "9999999999999999";

    /**
     * {@link #FIXTURE_CARD_NUMBER} with its final digit raised by one. Sixteen digits, and the
     * industry check-digit rule turns it down. All fifty fixture cards satisfy that rule, so no
     * fixture card carries this shape.
     */
    private static final String CHECK_DIGIT_FAILING_CARD_NUMBER = "4859452612877066";

    /** Sixteen characters whose leading fifteen are zeros, and sixteen digits. */
    private static final String LEADING_ZERO_CARD_NUMBER = "0000000000000001";

    /**
     * The amount record one of {@code app/data/ASCII/dailytran.txt} carries at positions 133-143.
     * The field holds {@code 0000005047G}, whose trailing overpunch carries a positive seven, so
     * {@code DALYTRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA06Y.cpy:L10} reads 504.77.
     */
    private static final BigDecimal FIXTURE_AMOUNT = new BigDecimal("504.77");

    /**
     * The capture timestamp all 300 records of {@code app/data/ASCII/dailytran.txt} carry at
     * positions 279-304, the width {@code DALYTRAN-ORIG-TS PIC X(26)} declares at
     * {@code app/cpy/CVTRA06Y.cpy:L16}. Position 11 is a space.
     */
    private static final String FIXTURE_ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** The card-number width from {@code app/cpy/CVACT03Y.cpy:L5}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** The measured length of the text {@code app/cbl/CBTRN02C.cbl:L386} assigns. */
    private static final int REASON_TEXT_LENGTH = 25;

    /**
     * The widest text {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} holds at
     * {@code app/cbl/CBTRN02C.cbl:L182}.
     */
    private static final int REASON_TEXT_WIDTH = 76;

    /** The position in the chain this rule declares. */
    private static final int CHAIN_POSITION = 10;

    /** The only collaborator the rule takes. */
    private CardCrossReferenceRepository cardCrossReferences;

    /** The rule under test. */
    private CardCrossReferenceRule rule;

    /** Builds the collaborator and the rule before each test. */
    @BeforeEach
    void prepareRule() {
        cardCrossReferences = mock(CardCrossReferenceRepository.class);
        rule = new CardCrossReferenceRule(cardCrossReferences);
    }

    /** The {@code INVALID KEY} limb at {@code app/cbl/CBTRN02C.cbl:L385-L387}. */
    @Nested
    @DisplayName("A cross-reference miss")
    class CrossReferenceMiss {

        @Test
        @DisplayName("answers with reject code 0100 and raises nothing")
        void answersWithRejectCode() {
            when(cardCrossReferences.findByCardNumber(UNRESOLVABLE_CARD_NUMBER))
                    .thenReturn(Optional.empty());
            DeclineRule.Context context = contextFor(UNRESOLVABLE_CARD_NUMBER);

            Optional<DeclineReason> outcome = assertDoesNotThrow(
                    () -> rule.evaluate(context),
                    "a card number no row carries must answer with a reject reason, and the rule"
                            + " must raise nothing");

            assertAll(
                    () -> assertTrue(outcome.isPresent(),
                            "the answer must carry the reject reason"
                                    + " app/cbl/CBTRN02C.cbl:L385 assigns"),
                    () -> assertEquals(DeclineReason.INVALID_CARD_NUMBER, outcome.orElse(null),
                            "a keyed read that finds no row is a decline and never a failure"));
        }

        @Test
        @DisplayName("seats no row and no account identifier on the context")
        void seatsNothing() {
            when(cardCrossReferences.findByCardNumber(UNRESOLVABLE_CARD_NUMBER))
                    .thenReturn(Optional.empty());
            DeclineRule.Context context = contextFor(UNRESOLVABLE_CARD_NUMBER);

            rule.evaluate(context);

            assertAll(
                    () -> assertNull(context.getCardCrossReference(),
                            "no row resolved, so the context must carry none"),
                    () -> assertNull(context.getResolvedAccountId(),
                            "app/cbl/CBTRN02C.cbl:L394 never runs for this outcome, so no account"
                                    + " identifier exists"));
        }
    }

    /** The {@code NOT INVALID KEY} limb at {@code app/cbl/CBTRN02C.cbl:L388-L390}. */
    @Nested
    @DisplayName("A cross-reference hit")
    class CrossReferenceHit {

        @Test
        @DisplayName("declines nothing and seats the resolved row on the context")
        void seatsResolvedRow() {
            CardCrossReferenceEntity row = resolvedRow(FIXTURE_CARD_NUMBER);
            when(cardCrossReferences.findByCardNumber(FIXTURE_CARD_NUMBER))
                    .thenReturn(Optional.of(row));
            DeclineRule.Context context = contextFor(FIXTURE_CARD_NUMBER);

            Optional<DeclineReason> outcome = rule.evaluate(context);

            assertAll(
                    () -> assertEquals(Optional.empty(), outcome,
                            "a card number a row carries must decline nothing"),
                    () -> assertSame(row, context.getCardCrossReference(),
                            "the context must carry the row the repository returned"),
                    () -> assertEquals(FIXTURE_CARD_NUMBER,
                            context.getCardCrossReference().getCardNumber(),
                            "the seated row must be the row for the card number this call"
                                    + " presented"),
                    () -> assertEquals(FIXTURE_ACCOUNT_ID, context.getResolvedAccountId(),
                            "app/cbl/CBTRN02C.cbl:L394 keys the account read on the identifier"
                                    + " the resolved row carries"));
        }
    }

    /** The code and text {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns. */
    @Nested
    @DisplayName("The reject reason")
    class RejectReasonShape {

        @Test
        @DisplayName("carries 0100 as four zero-padded digits and 100 as a number")
        void carriesBothFormsOfTheCode() {
            DeclineReason answer = rejectReasonFromMiss();

            assertAll(
                    () -> assertEquals("0100", answer.code(),
                            "WS-VALIDATION-FAIL-REASON PIC 9(04) at app/cbl/CBTRN02C.cbl:L181"
                                    + " carries four digits, so the wire form is padded"),
                    () -> assertEquals(100, answer.numericCode(),
                            "app/cbl/CBTRN02C.cbl:L385 assigns 100"));
        }

        @Test
        @DisplayName("carries the text app/cbl/CBTRN02C.cbl:L386 assigns, unedited")
        void carriesTheSourceText() {
            String text = rejectReasonFromMiss().description();

            assertAll(
                    () -> assertEquals(REASON_TEXT_LENGTH, text.length(),
                            "the measured length of the source literal"),
                    () -> assertTrue(text.length() <= REASON_TEXT_WIDTH,
                            "the text must fit WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at"
                                    + " app/cbl/CBTRN02C.cbl:L182"),
                    () -> assertEquals(text.strip(), text,
                            "the source literal carries no leading or trailing space"),
                    () -> assertEquals(text.toUpperCase(Locale.ROOT), text,
                            "the source literal is upper case throughout"));
        }

        /**
         * Reads the reject reason the rule answers with for a card number no row carries.
         *
         * <p>The text and the code are read from the answer, so this file restates neither.
         *
         * @return the reject reason the rule assigned
         */
        private DeclineReason rejectReasonFromMiss() {
            when(cardCrossReferences.findByCardNumber(UNRESOLVABLE_CARD_NUMBER))
                    .thenReturn(Optional.empty());

            DeclineReason answer =
                    rule.evaluate(contextFor(UNRESOLVABLE_CARD_NUMBER)).orElse(null);

            assertNotNull(answer, "a card number no row carries must answer with a reject reason");
            return answer;
        }
    }

    /**
     * Where this rule sits, which is what the gate at {@code app/cbl/CBTRN02C.cbl:L372} decides.
     *
     * <p>What the chain then does with several rules is asserted in the parent package.
     */
    @Nested
    @DisplayName("Chain placement")
    class ChainPlacement {

        @Test
        @DisplayName("declares the segment that ends the chain at the first decline")
        void declaresTheStoppingSegment() {
            assertEquals(DeclineRule.Segment.STOP_ON_FIRST_DECLINE, rule.segment(),
                    "app/cbl/CBTRN02C.cbl:L372 runs the account lookup only while the reject"
                            + " reason holds zero");
        }

        @Test
        @DisplayName("declares position 10, which leaves room for a rule on either side")
        void declaresItsPosition() {
            Order declared = CardCrossReferenceRule.class.getAnnotation(Order.class);

            assertNotNull(declared, "the class must declare its position in the chain");
            assertEquals(CHAIN_POSITION, declared.value(),
                    "the card lookup runs first, as app/cbl/CBTRN02C.cbl:L371 performs it first");
        }
    }

    /**
     * The move at {@code app/cbl/CBTRN02C.cbl:L382}, which copies the card number into the key
     * field and edits nothing.
     */
    @Nested
    @DisplayName("The card number")
    class CardNumberPassThrough {

        @ParameterizedTest(name = "[{index}] reaches the repository unedited")
        @ValueSource(strings = {FIXTURE_CARD_NUMBER, LEADING_ZERO_CARD_NUMBER,
                CHECK_DIGIT_FAILING_CARD_NUMBER, UNRESOLVABLE_CARD_NUMBER})
        @DisplayName("reaches the repository as the context holds it")
        void reachesTheRepositoryUnedited(String cardNumber) {
            when(cardCrossReferences.findByCardNumber(cardNumber)).thenReturn(Optional.empty());
            DeclineRule.Context context = contextFor(cardNumber);

            rule.evaluate(context);

            ArgumentCaptor<String> keyed = ArgumentCaptor.forClass(String.class);
            verify(cardCrossReferences).findByCardNumber(keyed.capture());
            assertAll(
                    () -> assertEquals(context.getCardNumber(), keyed.getValue(),
                            "the rule must pad nothing, trim nothing and fold no character"),
                    () -> assertEquals(CARD_NUMBER_WIDTH, keyed.getValue().length(),
                            "all sixteen characters of app/cpy/CVACT03Y.cpy:L5 must arrive"));
        }

        @Test
        @DisplayName("keeps the leading zeros of a padded card number")
        void keepsLeadingZeros() {
            when(cardCrossReferences.findByCardNumber(LEADING_ZERO_CARD_NUMBER))
                    .thenReturn(Optional.empty());

            rule.evaluate(contextFor(LEADING_ZERO_CARD_NUMBER));

            ArgumentCaptor<String> keyed = ArgumentCaptor.forClass(String.class);
            verify(cardCrossReferences).findByCardNumber(keyed.capture());
            assertEquals(LEADING_ZERO_CARD_NUMBER, keyed.getValue(),
                    "column card_number holds text, so a padded card number must arrive with its"
                            + " zeros in place");
        }
    }

    /** The single keyed read at {@code app/cbl/CBTRN02C.cbl:L383}. */
    @Nested
    @DisplayName("Repository reach")
    class RepositoryReach {

        @Test
        @DisplayName("reads the card-number finder once and reaches no other method")
        void readsOneFinderOnce() {
            CardCrossReferenceEntity row = resolvedRow(FIXTURE_CARD_NUMBER);
            when(cardCrossReferences.findByCardNumber(FIXTURE_CARD_NUMBER))
                    .thenReturn(Optional.of(row));

            rule.evaluate(contextFor(FIXTURE_CARD_NUMBER));

            verify(cardCrossReferences, times(1)).findByCardNumber(FIXTURE_CARD_NUMBER);
            verify(cardCrossReferences, never()).findByAccountIdOrderByCardNumberAsc(anyString());
            verify(cardCrossReferences, never())
                    .findFirstByAccountIdOrderByCardNumberAsc(anyString());
            verifyNoMoreInteractions(cardCrossReferences);
        }

        @Test
        @DisplayName("reaches no account finder when nothing resolves either")
        void readsNoAccountFinderOnAMiss() {
            when(cardCrossReferences.findByCardNumber(UNRESOLVABLE_CARD_NUMBER))
                    .thenReturn(Optional.empty());

            rule.evaluate(contextFor(UNRESOLVABLE_CARD_NUMBER));

            verify(cardCrossReferences, times(1)).findByCardNumber(UNRESOLVABLE_CARD_NUMBER);
            verify(cardCrossReferences, never()).findByAccountIdOrderByCardNumberAsc(anyString());
            verify(cardCrossReferences, never())
                    .findFirstByAccountIdOrderByCardNumberAsc(anyString());
            verifyNoMoreInteractions(cardCrossReferences);
        }
    }

    /**
     * The shape of the rule itself. {@code app/cbl/CBTRN02C.cbl:L408} and
     * {@code app/cbl/CBTRN02C.cbl:L415} put {@code CONTINUE} on the approve side, so the source
     * approves unless a reject reason was assigned.
     */
    @Nested
    @DisplayName("The rule surface")
    class RuleSurface {

        @Test
        @DisplayName("exposes evaluate and segment, and neither answers with a flag")
        void exposesTwoMethods() throws NoSuchMethodException {
            Set<String> exposed = new TreeSet<>();
            for (Method method : CardCrossReferenceRule.class.getDeclaredMethods()) {
                if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                exposed.add(method.getName());
                assertNotEquals(boolean.class, method.getReturnType(),
                        "the answer must be a reject reason and never a flag");
                assertNotEquals(Boolean.class, method.getReturnType(),
                        "the answer must be a reject reason and never a flag");
            }

            assertEquals(Set.of("evaluate", "segment"), exposed,
                    "the public surface must be the two methods DeclineRule declares, so no"
                            + " differently named entry point can grow beside them");

            Method evaluation =
                    CardCrossReferenceRule.class.getMethod("evaluate", DeclineRule.Context.class);
            assertEquals("java.util.Optional<com.carddemo.events.DeclineReason>",
                    evaluation.getGenericReturnType().getTypeName(),
                    "an absent answer is an approval and a present answer is the reject reason");
        }

        @Test
        @DisplayName("takes one collaborator through one unannotated constructor")
        void takesOneCollaborator() {
            Constructor<?>[] constructors = CardCrossReferenceRule.class.getConstructors();

            assertEquals(1, constructors.length, "the rule must offer one way to build it");
            assertArrayEquals(new Class<?>[] {CardCrossReferenceRepository.class},
                    constructors[0].getParameterTypes(),
                    "one repository and nothing else, so no second table is reachable from here");
            assertEquals(0, constructors[0].getAnnotations().length,
                    "a sole constructor needs no injection annotation");
        }
    }

    /** Behaviour a reader may expect that the source does not have. */
    @Nested
    @DisplayName("Deliberate non-additions")
    class DeliberateNonAdditions {

        @Test
        @DisplayName("a card number failing the industry check-digit rule is accepted")
        void acceptsACardNumberFailingTheCheckDigitRule() {
            CardCrossReferenceEntity row = resolvedRow(CHECK_DIGIT_FAILING_CARD_NUMBER);
            when(cardCrossReferences.findByCardNumber(CHECK_DIGIT_FAILING_CARD_NUMBER))
                    .thenReturn(Optional.of(row));
            DeclineRule.Context context = contextFor(CHECK_DIGIT_FAILING_CARD_NUMBER);

            Optional<DeclineReason> outcome = rule.evaluate(context);

            assertAll(
                    () -> assertEquals(Optional.empty(), outcome,
                            "app/cbl/COCRDUPC.cbl:L193-L194 names sixteen digits and no more, so"
                                    + " sixteen digits are enough"),
                    () -> assertSame(row, context.getCardCrossReference(),
                            "the row resolved, so the context must carry it"));
        }

        @Test
        @DisplayName("the customer identifier arrives on the resolved row and is not looked up")
        void carriesTheCustomerIdentifierOnTheRow() {
            CardCrossReferenceEntity row = resolvedRow(FIXTURE_CARD_NUMBER);
            when(cardCrossReferences.findByCardNumber(FIXTURE_CARD_NUMBER))
                    .thenReturn(Optional.of(row));
            DeclineRule.Context context = contextFor(FIXTURE_CARD_NUMBER);

            rule.evaluate(context);

            assertEquals(FIXTURE_CUSTOMER_ID, context.getCardCrossReference().getCustomerId(),
                    "XREF-CUST-ID at app/cpy/CVACT03Y.cpy:L6 arrives on the row");
            verify(cardCrossReferences).findByCardNumber(FIXTURE_CARD_NUMBER);
            verifyNoMoreInteractions(cardCrossReferences);
        }
    }

    /**
     * Builds the values one authorization call carries, for one card number.
     *
     * <p>The amount and the capture timestamp hold steady across every scenario. This rule reads
     * neither.
     *
     * @param cardNumber the card number, already padded to sixteen characters
     * @return the values the rule reads
     */
    private static DeclineRule.Context contextFor(String cardNumber) {
        return new DeclineRule.Context(cardNumber, FIXTURE_AMOUNT, FIXTURE_ORIGIN_TIMESTAMP);
    }

    /**
     * Builds the cross-reference row a keyed read resolves to, carrying the identifiers row 21 of
     * {@code app/data/ASCII/cardxref.txt} holds.
     *
     * <p>The row is a stub. Its own width and digit-class contract is asserted under
     * {@code src/test/java/com/carddemo/authorization/entity}.
     *
     * @param cardNumber the card number the row carries
     * @return the resolved row
     */
    private static CardCrossReferenceEntity resolvedRow(String cardNumber) {
        CardCrossReferenceEntity row = mock(CardCrossReferenceEntity.class);
        when(row.getCardNumber()).thenReturn(cardNumber);
        when(row.getCustomerId()).thenReturn(FIXTURE_CUSTOMER_ID);
        when(row.getAccountId()).thenReturn(FIXTURE_ACCOUNT_ID);
        return row;
    }
}

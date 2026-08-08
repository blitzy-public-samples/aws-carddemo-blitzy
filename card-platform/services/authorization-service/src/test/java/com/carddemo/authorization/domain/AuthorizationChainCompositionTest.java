package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.authorization.repository.AuthorizationDecisionRepository;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.UnresolvedCardAttemptRepository;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Composition tests for the reject-reason chain {@link AuthorizationService} runs.
 *
 * <p>Subject: paragraph {@code 1500-VALIDATE-TRAN} at {@code app/cbl/CBTRN02C.cbl:L370-L378}.
 *
 * <pre>{@code
 * 1500-VALIDATE-TRAN.
 *     PERFORM 1500-A-LOOKUP-XREF.
 *     IF WS-VALIDATION-FAIL-REASON = 0
 *        PERFORM 1500-B-LOOKUP-ACCT
 *     ELSE
 *        CONTINUE
 *     END-IF
 * * ADD MORE VALIDATIONS HERE
 *     EXIT.
 * }</pre>
 *
 * <p>The gate at {@code app/cbl/CBTRN02C.cbl:L372} runs the account lookup only while the reject
 * reason still holds zero, so a card that fails lookup reaches neither later test. The comment at
 * {@code app/cbl/CBTRN02C.cbl:L377} marks where one more test joins the chain.
 *
 * <p>The two later tests sit inside paragraph {@code 1500-B-LOOKUP-ACCT}, measured at
 * {@code app/cbl/CBTRN02C.cbl:L393-L422}, with nothing between them.
 *
 * <pre>{@code
 * IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
 *   CONTINUE
 * ELSE
 *   MOVE 102 TO WS-VALIDATION-FAIL-REASON
 *   MOVE 'OVERLIMIT TRANSACTION'
 *     TO WS-VALIDATION-FAIL-REASON-DESC
 * END-IF
 * IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
 *   CONTINUE
 * ELSE
 *   MOVE 103 TO WS-VALIDATION-FAIL-REASON
 *   MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
 *     TO WS-VALIDATION-FAIL-REASON-DESC
 * END-IF
 * }</pre>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:L407-L413} closes and {@code app/cbl/CBTRN02C.cbl:L414-L420} opens
 * on the next line, with no gate and no exit. A call failing both tests carries what the second one
 * assigns.
 *
 * <p>One call carries one reject reason at most. The driver loop at
 * {@code app/cbl/CBTRN02C.cbl:L202-L219} clears the field per record at
 * {@code app/cbl/CBTRN02C.cbl:L208}, and every later assignment overwrites it.
 *
 * <p>A decline is expected traffic. {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the batch job with
 * return code 4 once any record was rejected, so the chain answers with a value and raises nothing.
 *
 * <p>Every rule below is a stub implementing {@link DeclineRule}. No production rule class takes
 * part, so what these tests exercise is the composition the injected collection produces. Reject
 * codes and reject texts are read from {@link DeclineReason} and never restated here.
 *
 * <p>This class covers the composition the injected rule collection produces: the two segments,
 * the equality boundaries, the shape of one outcome value, and the checks the source deliberately
 * does not perform. The decision path itself, with production rules and real repository reads, is
 * covered by {@code AuthorizationServiceTest} in this package.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
final class AuthorizationChainCompositionTest {

    /**
     * The full card number every call presents, from positions 263 to 278 of record one of
     * {@code app/data/ASCII/dailytran.txt}, at the width
     * {@code DALYTRAN-CARD-NUM PIC X(16)} holds at {@code app/cpy/CVTRA06Y.cpy:L15}.
     */
    private static final String CARD_NUMBER = "4859452612877065";

    /** Sixteen digits that break the industry check-digit rule for a card number. */
    private static final String CARD_NUMBER_WITH_BAD_CHECK_DIGIT = "4859452612877064";

    /**
     * The account the seated cross-reference row names, at the width
     * {@code XREF-ACCT-ID PIC 9(11)} holds at {@code app/cpy/CVACT03Y.cpy:L7}.
     */
    private static final String ACCOUNT_ID = "00000000077";

    /**
     * The capture moment every call carries, from positions 279 to 304 of record one of
     * {@code app/data/ASCII/dailytran.txt}, where all three hundred records carry this one value.
     * Width from {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}.
     */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The identifier the allocator hands back, at the width {@code DALYTRAN-ID PIC X(16)} holds.
     */
    private static final String ALLOCATED_ID = "0000001000000001";

    /** The request identity every call carries. */
    private static final String ACTOR = "user0001";

    /**
     * The caller every request below presents.
     *
     * <p>It reaches every subject. These tests compose stub rules to measure the order the chain runs
     * in, and every one of them resolves whatever account its stub chose, so an entitlement refusal
     * would stop the chain before the thing under test ran. {@code CallerEntitlementTest} measures the
     * refusal on its own.
     */
    private static final RequestCaller CALLER = RequestCaller.administrator(ACTOR);

    /**
     * The amount every call carries, from positions 133 to 143 of record one of
     * {@code app/data/ASCII/dailytran.txt}, which read {@code 0000005047G} where the trailing
     * overpunch carries a positive seven. Scale from {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10}.
     */
    private static final String FIXTURE_AMOUNT = "504.77";

    /** Characters of the capture moment the expiry test reads, from {@code (1:10)} at L414. */
    private static final int EXPIRY_COMPARISON_WIDTH = 10;

    /** Reject reasons the source assigns, counted at L385, L397, L410 and L417. */
    private static final int PUBLISHED_REJECT_REASONS = 4;

    /** Trailing digits the written card form keeps. */
    private static final int RETAINED_DIGITS = 4;

    /** Zero at the scale the two cycle accumulators hold. */
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");

    /** A credit limit no call below reaches. */
    private static final BigDecimal AMPLE_CREDIT_LIMIT = new BigDecimal("5000.00");

    /** An expiry date no capture moment below reaches. */
    private static final String EXPIRY_AFTER_CAPTURE = "2099-12-31";

    /** Store the card number resolves through. The stub rules read no repository. */
    private CardCrossReferenceRepository cardCrossReferences;

    /** Allocator of transaction identifiers. */
    private TransactionIdentifierSource identifiers;

    /** Writer of the one event a resolved call produces. */
    private OutboxWriter outboxWriter;

    /** Store of attempts that name no account. */
    private UnresolvedCardAttemptRepository unresolvedCardAttempts;

    /** Store of each decision and the identity behind it. */
    private AuthorizationDecisionRepository authorizationDecisions;

    /** The cross-reference row a stub rule seats on the context. */
    private CardCrossReferenceEntity crossReferenceRow;

    /** The credit and expiry values a stub rule seats on the context. */
    private AccountCreditSnapshotEntity accountRow;

    /** Builds the collaborators and the two resolvable rows before each test. */
    @BeforeEach
    void prepareCollaborators() {
        cardCrossReferences = mock(CardCrossReferenceRepository.class);
        identifiers = mock(TransactionIdentifierSource.class);
        outboxWriter = mock(OutboxWriter.class);
        unresolvedCardAttempts = mock(UnresolvedCardAttemptRepository.class);
        authorizationDecisions = mock(AuthorizationDecisionRepository.class);

        when(identifiers.nextIdentifier()).thenReturn(ALLOCATED_ID);

        crossReferenceRow = mock(CardCrossReferenceEntity.class);
        when(crossReferenceRow.getAccountId()).thenReturn(ACCOUNT_ID);
        when(crossReferenceRow.isFreshAt(any(), any())).thenReturn(true);

        accountRow = mock(AccountCreditSnapshotEntity.class);
        when(accountRow.getAccountId()).thenReturn(ACCOUNT_ID);
        when(accountRow.getCreditLimit()).thenReturn(AMPLE_CREDIT_LIMIT);
        when(accountRow.getAccountExpirationDate()).thenReturn(EXPIRY_AFTER_CAPTURE);
        when(accountRow.getCurrentCycleCredit()).thenReturn(ZERO_MONEY);
        when(accountRow.getCurrentCycleDebit()).thenReturn(ZERO_MONEY);
        when(accountRow.effectivePendingCycleCredit(any())).thenReturn(ZERO_MONEY);
        when(accountRow.effectivePendingCycleDebit(any())).thenReturn(ZERO_MONEY);
        when(accountRow.isFreshAt(any(), any())).thenReturn(true);
    }

    /**
     * The gate at {@code app/cbl/CBTRN02C.cbl:L372}, which ends the chain at the first decline.
     */
    @Nested
    @DisplayName("The stopping segment")
    class StoppingSegment {

        @Test
        @DisplayName("reject reason 0100 ends the chain, and the three rules after it never run")
        void theFirstRejectReasonEndsTheChain() {
            StubRule unresolvedCard = declining(DeclineRule.Segment.STOP_ON_FIRST_DECLINE,
                    DeclineReason.INVALID_CARD_NUMBER);
            StubRule secondStopping = accepting(DeclineRule.Segment.STOP_ON_FIRST_DECLINE);
            StubRule firstOverwriting = accepting(DeclineRule.Segment.LAST_DECLINE_WINS);
            StubRule secondOverwriting = accepting(DeclineRule.Segment.LAST_DECLINE_WINS);

            AuthorizationService.Outcome outcome = serviceOver(
                    List.of(unresolvedCard, secondStopping, firstOverwriting, secondOverwriting))
                    .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER), outcome.declineReason(),
                    "app/cbl/CBTRN02C.cbl:L385 assigns this reject reason");
            assertTrue(unresolvedCard.wasEvaluated(), "the first rule of the chain runs");
            assertFalse(secondStopping.wasEvaluated(),
                    "the gate at app/cbl/CBTRN02C.cbl:L372 stops the second stopping rule");
            assertFalse(firstOverwriting.wasEvaluated(), "and the first overwriting rule");
            assertFalse(secondOverwriting.wasEvaluated(), "and the second overwriting rule");
        }

        @Test
        @DisplayName("reject reason 0101 ends the chain, and the two overwriting rules never run")
        void theSecondRejectReasonEndsTheChain() {
            StubRule resolveCard = resolvingCard();
            StubRule missingAccount = declining(DeclineRule.Segment.STOP_ON_FIRST_DECLINE,
                    DeclineReason.ACCOUNT_NOT_FOUND);
            StubRule firstOverwriting = accepting(DeclineRule.Segment.LAST_DECLINE_WINS);
            StubRule secondOverwriting = accepting(DeclineRule.Segment.LAST_DECLINE_WINS);

            AuthorizationService.Outcome outcome = serviceOver(
                    List.of(resolveCard, missingAccount, firstOverwriting, secondOverwriting))
                    .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertEquals(Optional.of(DeclineReason.ACCOUNT_NOT_FOUND), outcome.declineReason(),
                    "app/cbl/CBTRN02C.cbl:L397 assigns this reject reason");
            assertTrue(missingAccount.wasEvaluated(), "the second stopping rule runs");
            assertFalse(firstOverwriting.wasEvaluated(),
                    "the gate stops the credit test at app/cbl/CBTRN02C.cbl:L407-L413");
            assertFalse(secondOverwriting.wasEvaluated(),
                    "and the expiry test at app/cbl/CBTRN02C.cbl:L414-L420");
        }
    }

    /**
     * The two sequential blocks at {@code app/cbl/CBTRN02C.cbl:L407-L413} and
     * {@code app/cbl/CBTRN02C.cbl:L414-L420}, which carry no gate between them.
     */
    @Nested
    @DisplayName("The overwriting segment")
    class OverwritingSegment {

        @Test
        @DisplayName("a call failing both blocks carries reject reason 0103, and both rules run")
        void theLastDeclineStands() {
            StubRule overLimit = declining(DeclineRule.Segment.LAST_DECLINE_WINS,
                    DeclineReason.OVER_CREDIT_LIMIT);
            StubRule expired = declining(DeclineRule.Segment.LAST_DECLINE_WINS,
                    DeclineReason.ACCOUNT_EXPIRED);

            AuthorizationService.Outcome outcome =
                    serviceOver(List.of(resolvingCard(), overLimit, expired))
                            .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                    "app/cbl/CBTRN02C.cbl:L417 assigns the reject reason that stands");
            assertEquals(1, overLimit.evaluations(),
                    "app/cbl/CBTRN02C.cbl:L413 closes without ending the paragraph");
            assertEquals(1, expired.evaluations(),
                    "so app/cbl/CBTRN02C.cbl:L414 runs on the very next line");
        }

        @Test
        @DisplayName("collection order decides, and the higher reject code does not")
        void collectionOrderDecidesAndNotTheHigherCode() {
            StubRule expired = declining(DeclineRule.Segment.LAST_DECLINE_WINS,
                    DeclineReason.ACCOUNT_EXPIRED);
            StubRule overLimit = declining(DeclineRule.Segment.LAST_DECLINE_WINS,
                    DeclineReason.OVER_CREDIT_LIMIT);

            AuthorizationService.Outcome outcome =
                    serviceOver(List.of(resolvingCard(), expired, overLimit))
                            .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome.declineReason(),
                    "the last decline in collection order stands, and 0103 does not survive");
            assertEquals(1, expired.evaluations(), "the earlier rule still ran");
        }
    }

    /**
     * Equality on the two comparisons at {@code app/cbl/CBTRN02C.cbl:L407} and
     * {@code app/cbl/CBTRN02C.cbl:L414}, both written with greater-or-equal.
     */
    @Nested
    @DisplayName("The equality boundary")
    class EqualityBoundary {

        @Test
        @DisplayName("a tested value equal to the credit limit is approved")
        void aTestedValueEqualToTheCreditLimitIsApproved() {
            when(accountRow.getCreditLimit()).thenReturn(new BigDecimal("500.00"));

            AuthorizationService.Outcome outcome =
                    serviceOver(List.of(resolvingCardAndAccount(), mirroringCreditTest()))
                            .authorize(request("500.00"), CALLER);

            assertTrue(outcome.approved(),
                    "app/cbl/CBTRN02C.cbl:L407 accepts on greater-or-equal, not on greater");
            assertEquals(Optional.empty(), outcome.declineReason(), "so no reject reason stands");
        }

        @Test
        @DisplayName("a capture date equal to the account expiry date is approved")
        void aCaptureDateEqualToTheExpiryDateIsApproved() {
            when(accountRow.getAccountExpirationDate())
                    .thenReturn(ORIGIN_TIMESTAMP.substring(0, EXPIRY_COMPARISON_WIDTH));

            AuthorizationService.Outcome outcome =
                    serviceOver(List.of(resolvingCardAndAccount(), mirroringExpiryTest()))
                            .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertTrue(outcome.approved(),
                    "app/cbl/CBTRN02C.cbl:L414 accepts on greater-or-equal, not on greater");
        }
    }

    /**
     * The extension point the comment at {@code app/cbl/CBTRN02C.cbl:L377} marks.
     *
     * <p>The chain arrives as one ordered collection, and each rule declares its own segment. These
     * tests vary the length of that collection and nothing else.
     */
    @Nested
    @DisplayName("Chain composition")
    class ChainComposition {

        @Test
        @DisplayName("a fifth rule joins the chain with no edit to the service")
        void aFifthRuleJoinsTheChain() {
            StubRule firstOverwriting = accepting(DeclineRule.Segment.LAST_DECLINE_WINS);
            StubRule secondOverwriting = accepting(DeclineRule.Segment.LAST_DECLINE_WINS);
            StubRule fifth = declining(DeclineRule.Segment.LAST_DECLINE_WINS,
                    DeclineReason.ACCOUNT_EXPIRED);

            List<DeclineRule> chain = new ArrayList<>(List.of(resolvingCard(),
                    accepting(DeclineRule.Segment.STOP_ON_FIRST_DECLINE), firstOverwriting,
                    secondOverwriting));
            chain.add(fifth);

            AuthorizationService.Outcome outcome =
                    serviceOver(chain).authorize(request(FIXTURE_AMOUNT), CALLER);

            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                    "the added rule ran last and its answer stands");
            assertEquals(1, firstOverwriting.evaluations(), "every overwriting rule ran");
            assertEquals(1, secondOverwriting.evaluations(), "every overwriting rule ran");
            assertEquals(1, fifth.evaluations(), "the added rule ran once");
        }

        @Test
        @DisplayName("an empty chain is accepted and answers with a value")
        void anEmptyChainIsAcceptedAndAnswersWithAValue() {
            AuthorizationService.Outcome outcome =
                    serviceOver(List.of()).authorize(request(FIXTURE_AMOUNT), CALLER);

            assertNotNull(outcome, "the service assumes no fixed number of rules");
            assertFalse(outcome.approved(), "no rule seated a cross-reference row");
            assertNull(outcome.accountId(), "so the outcome names no account");
        }

        @Test
        @DisplayName("one accepting rule is enough for an approval")
        void oneAcceptingRuleIsEnoughForAnApproval() {
            AuthorizationService.Outcome outcome = serviceOver(List.of(resolvingCardAndAccount()))
                    .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertTrue(outcome.approved(), "the single rule accepted");
            assertEquals(Optional.empty(), outcome.declineReason(), "so no reject reason stands");
        }

        @Test
        @DisplayName("every rule of the chain reads one shared set of values")
        void everyRuleReadsOneSharedSetOfValues() {
            StubRule stopping = accepting(DeclineRule.Segment.STOP_ON_FIRST_DECLINE);
            StubRule overwriting = accepting(DeclineRule.Segment.LAST_DECLINE_WINS);

            serviceOver(List.of(resolvingCardAndAccount(), stopping, overwriting))
                    .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertEquals(CARD_NUMBER, stopping.observedCardNumber(),
                    "the stopping rule read the card number the caller presented");
            assertEquals(CARD_NUMBER, overwriting.observedCardNumber(),
                    "and the overwriting rule read the same value");
        }
    }

    /**
     * The nested outcome record, which replaces {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
     * {@code app/cbl/CBTRN02C.cbl:L181}.
     */
    @Nested
    @DisplayName("The outcome record")
    class OutcomeRecordShape {

        @Test
        @DisplayName("an approval carrying a reject reason is refused, and the message names it")
        void anApprovalCarryingARejectReasonIsRefused() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new AuthorizationService.Outcome(true,
                            Optional.of(DeclineReason.OVER_CREDIT_LIMIT),
                            new BigDecimal(ACCOUNT_ID), ALLOCATED_ID));

            assertTrue(refused.getMessage().contains("declineReason"),
                    "the message names the component that broke the check");
        }

        @Test
        @DisplayName("a decline carrying no reject reason is refused, and the message names it")
        void aDeclineCarryingNoRejectReasonIsRefused() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new AuthorizationService.Outcome(false, Optional.empty(),
                            new BigDecimal(ACCOUNT_ID), ALLOCATED_ID));

            assertTrue(refused.getMessage().contains("declineReason"),
                    "the message names the component that broke the check");
        }

        @Test
        @DisplayName("both factory methods build a value the canonical constructor accepts")
        void bothFactoryMethodsBuildAConsistentValue() {
            AuthorizationService.Outcome approval = AuthorizationService.Outcome
                    .approved(new BigDecimal(ACCOUNT_ID), ALLOCATED_ID);
            AuthorizationService.Outcome decline = AuthorizationService.Outcome
                    .declined(DeclineReason.ACCOUNT_EXPIRED, new BigDecimal(ACCOUNT_ID),
                            ALLOCATED_ID);

            assertTrue(approval.approved(), "the approval factory marks the call approved");
            assertEquals(Optional.empty(), approval.declineReason(),
                    "and leaves the reject reason empty");
            assertFalse(decline.approved(), "the decline factory marks the call declined");
            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), decline.declineReason(),
                    "and carries the reject reason it was handed");
        }

        @Test
        @DisplayName("the account identifier carries no scale and equals the seated row")
        void theAccountIdentifierCarriesNoScale() {
            AuthorizationService.Outcome outcome = serviceOver(List.of(resolvingCardAndAccount()))
                    .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertEquals(0, outcome.accountId().scale(),
                    "XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7 carries no scale");
            assertEquals(0, outcome.accountId().compareTo(new BigDecimal(ACCOUNT_ID)),
                    "and the value equals the identifier the seated row named");
        }

        @Test
        @DisplayName("the reject reason is one optional value and never a collection")
        void theRejectReasonIsOneOptionalValue() {
            RecordComponent chosen = null;
            for (RecordComponent component
                    : AuthorizationService.Outcome.class.getRecordComponents()) {
                assertFalse(Collection.class.isAssignableFrom(component.getType()),
                        "app/cbl/CBTRN02C.cbl:L208 clears one field per record, so no component of"
                                + " the outcome is a collection: " + component.getName());
                if ("declineReason".equals(component.getName())) {
                    chosen = component;
                }
            }

            assertNotNull(chosen, "the outcome carries a declineReason component");
            assertEquals(Optional.class, chosen.getType(), "which holds one value at most");
            ParameterizedType declared = (ParameterizedType) chosen.getGenericType();
            assertEquals(DeclineReason.class, declared.getActualTypeArguments()[0],
                    "and that value is a reject reason");
        }
    }

    /**
     * A decline as a returned value, per {@code app/cbl/CBTRN02C.cbl:L229-L230}.
     *
     * <p>The batch job ends with return code 4 once any record was rejected, so a rejection is a
     * normal outcome. One resolved call writes one event.
     */
    @Nested
    @DisplayName("A decline is a value")
    class DeclineIsAValue {

        @Test
        @DisplayName("a declining chain hands back the outcome and raises nothing")
        void aDecliningChainHandsBackTheOutcome() {
            AuthorizationService.Outcome outcome = serviceOver(List.of(resolvingCard(),
                    declining(DeclineRule.Segment.LAST_DECLINE_WINS,
                            DeclineReason.OVER_CREDIT_LIMIT)))
                    .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertFalse(outcome.approved(), "one rule assigned a reject reason");
            assertEquals(DeclineReason.OVER_CREDIT_LIMIT.description(), rejectTextOf(outcome),
                    "and the reject text reaches the caller unaltered");
        }

        @Test
        @DisplayName("an approval writes one event, and no decline event")
        void anApprovalWritesOneEvent() {
            serviceOver(List.of(resolvingCardAndAccount())).authorize(request(FIXTURE_AMOUNT), CALLER);

            verify(outboxWriter, times(1)).writeAuthorized(any());
            verify(outboxWriter, never()).writeDeclined(any());
            verifyNoMoreInteractions(outboxWriter);
        }

        @Test
        @DisplayName("a decline writes one event, and no approval event")
        void aDeclineWritesOneEvent() {
            ArgumentCaptor<TransactionDeclined> written =
                    ArgumentCaptor.forClass(TransactionDeclined.class);

            serviceOver(List.of(resolvingCard(),
                    declining(DeclineRule.Segment.LAST_DECLINE_WINS,
                            DeclineReason.ACCOUNT_EXPIRED)))
                    .authorize(request(FIXTURE_AMOUNT), CALLER);

            verify(outboxWriter, times(1)).writeDeclined(written.capture());
            verify(outboxWriter, never()).writeAuthorized(any());
            verifyNoMoreInteractions(outboxWriter);
            assertFalse(written.getValue().maskedCardNumber().contains(CARD_NUMBER),
                    "and the written decline carries no Primary Account Number in full");
        }

        @Test
        @DisplayName("the chain reads the full card number, and the event carries none of it")
        void theChainReadsTheFullCardNumber() {
            StubRule resolveCard = resolvingCardAndAccount();
            ArgumentCaptor<TransactionAuthorized> written =
                    ArgumentCaptor.forClass(TransactionAuthorized.class);

            serviceOver(List.of(resolveCard)).authorize(request(FIXTURE_AMOUNT), CALLER);

            assertEquals(CARD_NUMBER, resolveCard.observedCardNumber(),
                    "the lookup at app/cbl/CBTRN02C.cbl:L382 keys on all sixteen characters");
            verify(outboxWriter).writeAuthorized(written.capture());
            String carried = written.getValue().maskedCardNumber();
            assertFalse(carried.contains(CARD_NUMBER),
                    "no written event carries the Primary Account Number in full");
            assertTrue(
                    carried.endsWith(
                            CARD_NUMBER.substring(CARD_NUMBER.length() - RETAINED_DIGITS)),
                    "the written form keeps the last four digits");
        }
    }

    /**
     * One reject reason per call, per the driver loop at {@code app/cbl/CBTRN02C.cbl:L202-L219}.
     */
    @Nested
    @DisplayName("One reject reason per call")
    class OneRejectReasonPerCall {

        @Test
        @DisplayName("an approval carries no reject reason")
        void anApprovalCarriesNoRejectReason() {
            AuthorizationService.Outcome outcome = serviceOver(List.of(resolvingCardAndAccount(),
                    mirroringCreditTest(), mirroringExpiryTest()))
                    .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertTrue(outcome.approved(), "every rule accepted");
            assertEquals(Optional.empty(), outcome.declineReason(),
                    "app/cbl/CBTRN02C.cbl:L208 leaves the field at zero for an accepted record");
            assertNull(rejectTextOf(outcome), "so no reject text is carried");
        }

        @Test
        @DisplayName("a decline carries exactly one reject reason")
        void aDeclineCarriesExactlyOneRejectReason() {
            when(accountRow.getCreditLimit()).thenReturn(new BigDecimal("100.00"));
            when(accountRow.getAccountExpirationDate()).thenReturn("2020-01-31");

            AuthorizationService.Outcome outcome = serviceOver(List.of(resolvingCardAndAccount(),
                    mirroringCreditTest(), mirroringExpiryTest()))
                    .authorize(request("100.01"), CALLER);

            assertTrue(outcome.declineReason().isPresent(), "a decline names a reject reason");
            assertEquals(DeclineReason.ACCOUNT_EXPIRED, outcome.declineReason().orElseThrow(),
                    "and it is the one app/cbl/CBTRN02C.cbl:L417 assigns last");
        }

        @Test
        @DisplayName("the published set holds four reject reasons and no fifth")
        void thePublishedSetHoldsFourRejectReasons() {
            List<String> codes = new ArrayList<>();
            for (DeclineReason reason : DeclineReason.values()) {
                codes.add(reason.code());
            }

            assertEquals(PUBLISHED_REJECT_REASONS, codes.size(),
                    "app/cbl/CBTRN02C.cbl assigns a reject reason at L385, L397, L410 and L417");
            assertEquals(List.of("0100", "0101", "0102", "0103"), codes,
                    "WS-VALIDATION-FAIL-REASON PIC 9(04) at app/cbl/CBTRN02C.cbl:L181 pads each"
                            + " code to four digits");
        }
    }

    /**
     * Tests the source does not perform, and which stay absent here.
     */
    @Nested
    @DisplayName("Deliberate non-additions")
    class DeliberateNonAdditions {

        @Test
        @DisplayName("a card number failing the industry check-digit rule is approved")
        void aCardNumberFailingTheCheckDigitRuleIsApproved() {
            StubRule resolveCard = resolvingCardAndAccount();

            AuthorizationService.Outcome outcome = serviceOver(List.of(resolveCard))
                    .authorize(request(CARD_NUMBER_WITH_BAD_CHECK_DIGIT, FIXTURE_AMOUNT), CALLER);

            assertTrue(outcome.approved(),
                    "app/cbl/COCRDUPC.cbl:L193-L194 tests sixteen digits and nothing more");
            assertEquals(CARD_NUMBER_WITH_BAD_CHECK_DIGIT, resolveCard.observedCardNumber(),
                    "the chain read the value the caller presented");
        }

        @Test
        @DisplayName("a closed account is approved, and no rule can read an account status")
        void aClosedAccountIsStillApproved() {
            for (Method accessor : AccountCreditSnapshotEntity.class.getDeclaredMethods()) {
                assertFalse(accessor.getName().contains("ActiveStatus"),
                        "ACCT-ACTIVE-STATUS PIC X(01) at app/cpy/CVACT01Y.cpy:L6 reaches no rule of"
                                + " this chain: " + accessor.getName());
            }

            AuthorizationService.Outcome outcome = serviceOver(List.of(resolvingCardAndAccount(),
                    mirroringCreditTest(), mirroringExpiryTest()))
                    .authorize(request(FIXTURE_AMOUNT), CALLER);

            assertTrue(outcome.approved(),
                    "the six datasets at app/cbl/CBTRN02C.cbl:L28-L61 omit the card file, and no"
                            + " program tests either status before posting");
        }

        @Test
        @DisplayName("the capture moment the caller sent drives the expiry test, and no clock does")
        void theCaptureMomentTheCallerSentDrivesTheExpiryTest() {
            when(accountRow.getAccountExpirationDate()).thenReturn("2020-01-31");

            AuthorizationService.Outcome outcome = serviceOver(List.of(resolvingCardAndAccount(),
                    mirroringExpiryTest())).authorize(request(FIXTURE_AMOUNT), CALLER);

            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                    "app/cbl/CBTRN02C.cbl:L414 reads DALYTRAN-ORIG-TS (1:10) and no current time");
        }
    }

    /**
     * Builds the service over the chain given and over the prepared collaborators.
     *
     * <p>No application context starts, no broker is reached and no container runs.
     *
     * @param rules the chain, in the order the service receives it
     * @return the service under test
     */
    private AuthorizationService serviceOver(List<DeclineRule> rules) {
        return new AuthorizationService(rules, cardCrossReferences, identifiers, outboxWriter,
                unresolvedCardAttempts, authorizationDecisions, new SimpleMeterRegistry(),
                immediateTransactions(), replicaPolicy(), cycleExposure());
    }

    /**
     * Runs the decision callback on the calling thread with no transaction manager.
     *
     * @return a template that executes its callback directly
     */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus(true));
            }
        };
    }

    /**
     * Supplies the replica freshness ceiling the constructor requires.
     *
     * <p>Deep stubs answer the ceiling with a value reporting itself neither negative nor zero. No
     * stub rule below reads it.
     *
     * @return configuration carrying a ceiling the constructor accepts
     */
    private static AuthorizationProperties replicaPolicy() {
        return mock(AuthorizationProperties.class, RETURNS_DEEP_STUBS);
    }

    /**
     * Supplies a reservation over a store no stub rule below reads.
     *
     * <p>Every rule in these tests is a stub that resolves whatever it was told to resolve, so none
     * reaches the credit-limit rule and none reserves anything. The collaborator still has to exist,
     * because the service holds it and bounds its lock wait on every call. The configured block is built
     * rather than deep-stubbed, so the lifetime it carries is a value this file states.
     *
     * @return the reservation the service under test holds
     */
    private static CycleExposureReservation cycleExposure() {
        AuthorizationProperties properties = mock(AuthorizationProperties.class);
        when(properties.decision()).thenReturn(
                new AuthorizationProperties.Decision(3_000L, Duration.ofMinutes(15)));
        AccountCreditSnapshotRepository snapshots = mock(AccountCreditSnapshotRepository.class);
        when(snapshots.reserveCycleExposure(any(), any(), any(), any())).thenReturn(1);
        return new CycleExposureReservation(snapshots, properties);
    }

    /**
     * Builds a valid request presenting {@link #CARD_NUMBER} and the fixture capture moment.
     *
     * @param amount the amount as text
     * @return the request
     */
    private static AuthorizationRequest request(String amount) {
        return request(CARD_NUMBER, amount);
    }

    /**
     * Builds a valid request presenting the card number given.
     *
     * <p>The merchant and description values come from record one of
     * {@code app/data/ASCII/dailytran.txt}.
     *
     * @param cardNumber the card number, sixteen digits
     * @param amount     the amount as text
     * @return the request
     */
    private static AuthorizationRequest request(String cardNumber, String amount) {
        return new AuthorizationRequest(null, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", amount, "800000000", "Abshire-Lowe", "North Enoshaven",
                "72112", cardNumber, ORIGIN_TIMESTAMP, "2022-06-10-19.27.53.410000", null);
    }

    /**
     * One rule of the chain, recording each evaluation and answering from a supplied function.
     *
     * <p>Nothing here reads a repository. A stub seats a resolved row on the context where a test
     * needs the rules after it to read one.
     */
    private static final class StubRule implements DeclineRule {

        /** Where in the chain this stub sits. */
        private final Segment segment;

        /** Answers with the reject reason this stub assigns, or with {@code null} to accept. */
        private final Function<Context, DeclineReason> answer;

        /** How often the chain evaluated this stub. */
        private int evaluations;

        /** The card number this stub read from the context on its last evaluation. */
        private String observedCardNumber;

        /**
         * @param segment where in the chain this stub sits
         * @param answer  answers with the reject reason this stub assigns, or with {@code null}
         */
        StubRule(Segment segment, Function<Context, DeclineReason> answer) {
            this.segment = segment;
            this.answer = answer;
        }

        @Override
        public Optional<DeclineReason> evaluate(Context context) {
            evaluations++;
            observedCardNumber = context.getCardNumber();
            return Optional.ofNullable(answer.apply(context));
        }

        @Override
        public Segment segment() {
            return segment;
        }

        /** @return how often the chain evaluated this stub */
        int evaluations() {
            return evaluations;
        }

        /** @return whether the chain evaluated this stub at all */
        boolean wasEvaluated() {
            return evaluations > 0;
        }

        /** @return the card number this stub read from the context, or {@code null} */
        String observedCardNumber() {
            return observedCardNumber;
        }
    }

    /**
     * Builds a stub that accepts.
     *
     * @param segment where in the chain the stub sits
     * @return the stub
     */
    private static StubRule accepting(DeclineRule.Segment segment) {
        return new StubRule(segment, context -> null);
    }

    /**
     * Builds a stub that assigns one reject reason.
     *
     * @param segment where in the chain the stub sits
     * @param reason  the reject reason the stub assigns
     * @return the stub
     */
    private static StubRule declining(DeclineRule.Segment segment, DeclineReason reason) {
        return new StubRule(segment, context -> reason);
    }

    /**
     * Builds a stopping stub that seats the cross-reference row and accepts.
     *
     * <p>Stands in for the keyed read at {@code app/cbl/CBTRN02C.cbl:L383}, which fills
     * {@code CARD-XREF-RECORD} for the statements that follow.
     *
     * @return the stub
     */
    private StubRule resolvingCard() {
        return new StubRule(DeclineRule.Segment.STOP_ON_FIRST_DECLINE, context -> {
            context.setCardCrossReference(crossReferenceRow);
            return null;
        });
    }

    /**
     * Builds a stopping stub that seats both resolved rows and accepts.
     *
     * <p>Stands in for the two keyed reads at {@code app/cbl/CBTRN02C.cbl:L383} and
     * {@code app/cbl/CBTRN02C.cbl:L395}.
     *
     * @return the stub
     */
    private StubRule resolvingCardAndAccount() {
        return new StubRule(DeclineRule.Segment.STOP_ON_FIRST_DECLINE, context -> {
            context.setCardCrossReference(crossReferenceRow);
            context.setAccountCreditSnapshot(accountRow);
            return null;
        });
    }

    /**
     * Builds an overwriting stub mirroring the credit test at
     * {@code app/cbl/CBTRN02C.cbl:L403-L407}.
     *
     * <p>The tested value is the cycle credit less the cycle debit plus the amount, and the
     * comparison accepts on greater-or-equal.
     *
     * @return the stub
     */
    private static StubRule mirroringCreditTest() {
        return new StubRule(DeclineRule.Segment.LAST_DECLINE_WINS, context -> {
            AccountCreditSnapshotEntity row = context.getAccountCreditSnapshot();
            BigDecimal tested = row.getCurrentCycleCredit()
                    .subtract(row.getCurrentCycleDebit())
                    .add(context.getAmount());

            return row.getCreditLimit().compareTo(tested) >= 0
                    ? null
                    : DeclineReason.OVER_CREDIT_LIMIT;
        });
    }

    /**
     * Builds an overwriting stub mirroring the expiry test at {@code app/cbl/CBTRN02C.cbl:L414}.
     *
     * <p>Both operands stay text, as the source compares them, and the comparison accepts on
     * greater-or-equal.
     *
     * @return the stub
     */
    private static StubRule mirroringExpiryTest() {
        return new StubRule(DeclineRule.Segment.LAST_DECLINE_WINS, context -> {
            String expiry = context.getAccountCreditSnapshot().getAccountExpirationDate();
            String capturedDate = context.getOriginTimestamp()
                    .substring(0, EXPIRY_COMPARISON_WIDTH);

            return expiry.compareTo(capturedDate) >= 0 ? null : DeclineReason.ACCOUNT_EXPIRED;
        });
    }

    /**
     * Reads the reject text one outcome carries.
     *
     * @param outcome the decision under test
     * @return the reject text, or {@code null} on an approval
     */
    private static String rejectTextOf(AuthorizationService.Outcome outcome) {
        return outcome.declineReason().map(DeclineReason::description).orElse(null);
    }
}

package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.domain.rules.AccountExistsRule;
import com.carddemo.authorization.domain.rules.AccountExpirationRule;
import com.carddemo.authorization.domain.rules.CardCrossReferenceRule;
import com.carddemo.authorization.domain.rules.CreditLimitRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.entity.UnresolvedCardAttemptEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.authorization.repository.AuthorizationDecisionRepository;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.authorization.repository.ReplicaGapRepository;
import com.carddemo.authorization.repository.UnresolvedCardAttemptRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decision tests for {@link AuthorizationService}.
 *
 * <p>The chain under test is the one paragraph {@code 1500-VALIDATE-TRAN} at
 * {@code app/cbl/CBTRN02C.cbl:L370-L378} runs, with the four reject codes
 * {@code app/cbl/CBTRN02C.cbl:L380-L420} assigns. Every expected code and text is read from the
 * {@code MOVE} that writes it.
 *
 * <p>These tests use the real rules, the real writer and a real meter registry, with the four
 * repositories stubbed. No database, no broker and no application context takes part, so what they
 * prove is the decision and the event, not the wiring.
 */
final class AuthorizationServiceTest {

    /** The card number every request below presents, sixteen digits. */
    private static final String CARD_NUMBER = "4859452612877065";

    /**
     * When this service last refreshed the two replica rows the rules read. The value only has to
     * be a fixed instant: no rule below reads it, and the freshness columns exist so a stale
     * replica can be reported and not authorized against.
     */
    private static final Instant OBSERVED_AT = Instant.parse("2026-01-01T00:00:00Z");

    /** Registry the service under test records against. */
    private SimpleMeterRegistry meters;

    /** The account the card resolves to, eleven digits with a leading zero. */
    private static final String ACCOUNT_ID = "00000000077";

    /** The capture timestamp every request below carries. */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.412000";

    /** An expiry date after the capture date, so the expiry rule accepts. */
    private static final String EXPIRY_AFTER_CAPTURE = "2099-12-31";

    /** An expiry date before the capture date, so the expiry rule declines. */
    private static final String EXPIRY_BEFORE_CAPTURE = "2020-01-31";

    /** The request identity every call below carries, recorded whole on the decision row. */
    private static final String ACTOR = "user0001";

    /**
     * The caller every request below presents.
     *
     * <p>It reaches every subject, which is what these tests want: they measure the rule chain, the
     * event and the audit row, and an entitlement refusal would stop each of them before the chain ran.
     * {@code CallerEntitlementTest} measures the refusal itself, and the nested entitlement class below
     * measures where it sits in the decision.
     */
    private static final RequestCaller CALLER = RequestCaller.administrator(ACTOR);

    /** The identifier the source allocates when the request supplies none. */
    private static final String ALLOCATED_ID = "0000001000000001";

    private CardCrossReferenceRepository cardCrossReferences;
    private AccountCreditSnapshotRepository accountSnapshots;
    private OutboxEventRepository outboxEvents;
    private UnresolvedCardAttemptRepository unresolvedCardAttempts;
    private AuthorizationDecisionRepository authorizationDecisions;
    private TransactionIdentifierSource identifiers;
    private List<AuthorizationDecisionEntity> audited;
    private List<OutboxEventEntity> written;
    private CycleExposureReservation cycleExposure;
    private ReplicaGapRepository replicaGaps;
    private AuthorizationService service;

    /** Builds the service over stubbed repositories before each test. */
    @BeforeEach
    void buildService() {
        cardCrossReferences = mock(CardCrossReferenceRepository.class);
        accountSnapshots = mock(AccountCreditSnapshotRepository.class);
        outboxEvents = mock(OutboxEventRepository.class);
        unresolvedCardAttempts = mock(UnresolvedCardAttemptRepository.class);
        authorizationDecisions = mock(AuthorizationDecisionRepository.class);
        written = new ArrayList<>();
        audited = new ArrayList<>();

        when(authorizationDecisions.save(any(AuthorizationDecisionEntity.class))).thenAnswer(call -> {
            AuthorizationDecisionEntity row = call.getArgument(0);
            audited.add(row);
            return row;
        });

        when(outboxEvents.save(any(OutboxEventEntity.class))).thenAnswer(call -> {
            OutboxEventEntity row = call.getArgument(0);
            written.add(row);
            return row;
        });

        identifiers = mock(TransactionIdentifierSource.class);
        when(identifiers.nextIdentifier()).thenReturn(ALLOCATED_ID);

        replicaGaps = mock(ReplicaGapRepository.class);
        when(replicaGaps.existsForAggregate(any())).thenReturn(false);

        when(accountSnapshots.reserveCycleExposure(any(), any(), any(), any())).thenReturn(1);
        cycleExposure =
                new CycleExposureReservation(accountSnapshots, properties());

        List<DeclineRule> rules = List.of(new CardCrossReferenceRule(cardCrossReferences),
                new AccountExistsRule(accountSnapshots), new CreditLimitRule(cycleExposure),
                new AccountExpirationRule());

        meters = new SimpleMeterRegistry();
        service = new AuthorizationService(rules, cardCrossReferences, identifiers,
                new OutboxWriter(outboxEvents), unresolvedCardAttempts, authorizationDecisions,
                meters, immediateTransactions(), properties(),
                cycleExposure, caughtUp(), replicaGaps);
    }

    /** The lock-wait bound the configured decision block carries, in milliseconds. */
    private static final long LOCK_WAIT_MS = 3_000L;

    /** How long a reservation counts, wide enough that no test below reaches its expiry. */
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    /**
     * Runs the decision callback on the calling thread with no transaction manager.
     *
     * <p>These tests assert what the service writes and what it counts, and both are observable
     * without a real transaction. What a real boundary adds is the commit, and the ordering around it
     * has its own assertions in the nested metrics class.
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
     * Supplies the typed configuration the production constructor consumes.
     *
     * @return configuration holding the replica and decision blocks and nothing else
     */
    private static AuthorizationProperties properties() {
        AuthorizationProperties properties = mock(AuthorizationProperties.class);
        when(properties.replica()).thenReturn(new AuthorizationProperties.Replica(0L));
        when(properties.decision()).thenReturn(
                new AuthorizationProperties.Decision(LOCK_WAIT_MS, RESERVATION_TTL));
        return properties;
    }

    /**
     * A replica whose streams are caught up, which is what every test but the nested usability class
     * assumes.
     *
     * @return a verdict source reporting a synchronized stream at zero lag
     */
    private static ReplicaSynchronization caughtUp() {
        return () -> ReplicaSynchronization.Verdict.synchronizedAt(0L);
    }

    /**
     * A replica whose streams are behind, which is one of the two conditions that refuse a call.
     *
     * @return a verdict source reporting a stream with records waiting
     */
    private static ReplicaSynchronization behind() {
        return () -> ReplicaSynchronization.Verdict.behind("replica-stream-behind", 7L);
    }

    /** Asserts an approval names its account, allocates an identifier and writes one event. */
    @Test
    void anApprovalWritesOneAuthorizedEventAndNamesItsAccount() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), CALLER);

        assertTrue(outcome.approved(), "the transaction fits the credit limit and the expiry date");
        assertEquals(new BigDecimal(ACCOUNT_ID), outcome.accountId(), "the approval names the resolved account");
        assertEquals(ALLOCATED_ID, outcome.transactionId(), "the service allocated an identifier");
        assertEquals(1, written.size(), "one call writes one event");
        assertEquals(TransactionAuthorized.EVENT_TYPE, written.get(0).getEventType(),
                "an approval writes the authorized event");
        assertEquals(ACCOUNT_ID, written.get(0).getAggregateId(),
                "the event is keyed on the account, so one account's events stay in order");
    }

    /**
     * Asserts the identifier every event carries is the one the sequence allocated, on both the
     * approval and the decline path.
     *
     * <p>No caller may name a transaction.
     * {@link AuthorizationRequest#TRANSACTION_ID_NOT_ACCEPTED_MESSAGE} refuses a supplied identifier
     * at the interface, so a caller cannot key its decision on a value another caller's decision
     * already used, and cannot decide one transaction twice by repeating a value it chose.
     * {@code app/cbl/COTRN02C.cbl:L444-L451} allocated by browsing the transaction file backwards
     * from high values and adding one, which two callers can interleave, and
     * {@link TransactionIdentifierSource} replaces that with a database sequence.
     *
     * <p>The domain reads no identifier from the request, which this test asserts directly: it
     * supplies one and the event still carries the allocated value.
     */
    @Test
    void everyEventCarriesTheAllocatedIdentifierAndNeverOneTheCallerNamed() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);
        written.clear();

        AuthorizationService.Outcome approved =
                service.authorize(requestWithTransactionId("9999999999999999", "504.77"), CALLER);

        assertEquals(ALLOCATED_ID, approved.transactionId(),
                "an approval reports the allocated identifier and not one the caller named");
        assertEquals(1, written.size(), "one call writes one event");
        assertTrue(written.get(0).getPayload()
                        .contains("\"transactionId\":\"" + ALLOCATED_ID + "\""),
                "the approval event carries an identifier the caller supplied");

        resolveAccount(new BigDecimal("10.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);
        written.clear();

        AuthorizationService.Outcome declined =
                service.authorize(requestWithTransactionId("9999999999999999", "504.77"), CALLER);

        assertFalse(declined.approved(), "the amount exceeds the credit limit");
        assertEquals(ALLOCATED_ID, declined.transactionId(),
                "a decline reports the allocated identifier and not one the caller named");
        assertEquals(1, written.size(), "one call writes one event");
        assertTrue(written.get(0).getPayload()
                        .contains("\"transactionId\":\"" + ALLOCATED_ID + "\""),
                "the decline event carries an identifier the caller supplied");
    }
    /**
     * Asserts every category code width the request accepts reaches the event as four digits.
     *
     * <p>{@code DALYTRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA06Y.cpy:L7} is a numeric display
     * field, so a {@code MOVE} into it fills the left with zeros. The event contract requires four
     * digits, and the request accepts one to four because
     * {@code app/cbl/COTRN02C.cbl:L330} applies the COBOL numeric class test and nothing narrower.
     */
    @Test
    void everyAcceptedCategoryCodeWidthReachesTheEventAsFourDigits() {
        for (String supplied : List.of("1", "12", "123", "1234")) {
            String expected = "0".repeat(4 - supplied.length()) + supplied;

            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);
            written.clear();

            AuthorizationService.Outcome outcome = service.authorize(
                    build(CARD_NUMBER, null, "504.77", ORIGIN_TIMESTAMP, null, supplied), CALLER);

            assertTrue(outcome.approved(),
                    "a category code of width " + supplied.length() + " was refused");
            assertEquals(1, written.size(), "one call writes one event");
            assertTrue(written.get(0).getPayload()
                            .contains("\"merchantCategoryCode\":\"" + expected + "\""),
                    "a category code of width " + supplied.length()
                            + " reached the event unpadded");
        }
    }

    /**
     * Asserts the written approval carries the card token, and that the token identifies the card
     * where the masked number cannot.
     *
     * <p>Two cards ending in the same four digits mask alike, so a consumer keyed on the masked
     * value would merge their histories. The token is what keeps them apart, and no character of
     * the card number reaches it.
     */
    @Test
    void theWrittenApprovalCarriesACardTokenThatIdentifiesTheCard() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        service.authorize(request("504.77"), CALLER);

        String payload = written.get(0).getPayload();
        String expected = com.carddemo.cobol.PanMasker.cardToken(CARD_NUMBER);

        assertTrue(payload.contains("\"cardToken\":\"" + expected + "\""),
                "the approval event carries no card token");
        assertFalse(payload.contains(CARD_NUMBER),
                "no written event carries a Primary Account Number");
        assertFalse(expected.contains(CARD_NUMBER.substring(12)),
                "the token disclosed the last four digits of the card");
    }

    /** Asserts the written approval carries the masked card number and never the full one. */
    @Test
    void theWrittenApprovalCarriesTheMaskedCardNumberAndNeverTheFullOne() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        service.authorize(request("504.77"), CALLER);

        String payload = written.get(0).getPayload();
        assertTrue(payload.contains("************7065"),
                "the event carries the last four digits behind twelve mask characters");
        assertFalse(payload.contains(CARD_NUMBER),
                "no written event carries a Primary Account Number");
    }

    /**
     * Asserts an unresolved card declines with reject code {@code 0100} and publishes exactly one
     * event, under the contract shaped for a decline that resolved no account.
     *
     * <p>AAP transformation rule T4 gives one authorization call one event, written through the outbox
     * in the transaction that recorded the decision, and it admits no exception. This outcome used to
     * be that exception: the decline was recorded twice and published nowhere, so the three consumers
     * of the authorized stream had no record that the call happened.
     *
     * <p>The event is {@code schemas/transaction-declined-v2.json}. It carries no {@code accountId},
     * because reject code {@code 0100} is assigned inside the {@code INVALID KEY} branch of
     * {@code app/cbl/CBTRN02C.cbl:L383-L387} and the short-circuit at
     * {@code app/cbl/CBTRN02C.cbl:L376-L378} stops the account read from running. It is keyed on the
     * sixteen-character transaction identifier, which is the one key naming no cardholder: trusting an
     * identifier the caller sent beside the card number would attribute one caller's declined attempt
     * to another caller's account, and minting one inside the real account key space would occupy a
     * live key.
     *
     * <p>The two durable rows and the event are this service's addition. The source captures nothing
     * on its own synchronous path: {@code app/cbl/COTRN02C.cbl:L620-L636} answers a card number the
     * cross-reference does not carry with {@code 'Card Number NOT found...'} and re-sends the screen.
     */
    @Test
    void anUnresolvedCardDeclinesAndPublishesOneTransactionKeyedEvent() {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), CALLER);

        assertFalse(outcome.approved(), "no cross-reference row carries the card number");
        assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER), outcome.declineReason(),
                "app/cbl/CBTRN02C.cbl:L385 assigns this code for an invalid key");
        assertEquals("INVALID CARD NUMBER FOUND", describe(outcome),
                "the text comes from app/cbl/CBTRN02C.cbl:L386-L387");
        assertNull(outcome.accountId(), "no account resolved, so none is named");

        assertEquals(1, written.size(),
                "one authorization call writes one outbox row, which is AAP rule T4, and this"
                        + " outcome is a decided one");
        OutboxEventEntity row = written.get(0);
        assertEquals(TransactionDeclined.EVENT_TYPE, row.getEventType(),
                "the decided outcome is a decline, so it publishes the decline contract");
        assertEquals(ALLOCATED_ID, row.getAggregateId(),
                "the message key is the transaction identifier, because no account was resolved to"
                        + " key on, and ck_outbox_event_aggregate_id admits that sixteen-character"
                        + " form");

        String payload = row.getPayload();
        assertTrue(payload.contains("\"schemaVersion\":"
                        + TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION),
                "the payload declares the version whose document permits an absent accountId: "
                        + payload);
        assertFalse(payload.contains("\"accountId\""),
                "and it carries no accountId at all, rather than a null or a substitute: " + payload);
        assertTrue(payload.contains("\"transactionId\":\"" + ALLOCATED_ID + "\""),
                "the payload names the transaction the decision applies to: " + payload);
        assertTrue(payload.contains("\"declineReasonCode\":\"0100\""),
                "the payload carries the reject code app/cbl/CBTRN02C.cbl:L385 assigns: " + payload);
        assertTrue(payload.contains("\"declineReasonDescription\":\"INVALID CARD NUMBER FOUND\""),
                "paired with the text app/cbl/CBTRN02C.cbl:L386-L387 writes: " + payload);
        assertTrue(payload.contains("\"amount\":\"504.77\""),
                "and the attempted amount as a decimal string: " + payload);
        assertTrue(payload.contains("\"maskedCardNumber\":\"************7065\""),
                "masked, so no full Primary Account Number travels: " + payload);
        assertFalse(payload.contains(CARD_NUMBER),
                "and the full card number appears nowhere in the payload: " + payload);

        ArgumentCaptor<UnresolvedCardAttemptEntity> attempt =
                ArgumentCaptor.forClass(UnresolvedCardAttemptEntity.class);
        verify(unresolvedCardAttempts).save(attempt.capture());
        assertEquals("************7065", attempt.getValue().getMaskedCardNumber(),
                "the attempt records the masked card number and never the full one");
        assertEquals("0100", attempt.getValue().getDeclineReasonCode(),
                "the attempt records the reject code the source assigns");
        assertEquals(row.getEventId(), audited.get(0).getEventId(),
                "and the decision row names the outbox row it published through, which"
                        + " ck_authorization_decision_event holds it to");
    }

    /**
     * Asserts the event of an unresolved card is keyed on the identifier the sequence allocated, so a
     * republished decline lands on the partition it landed on before.
     *
     * <p>Ordering is why the key has to be deterministic rather than merely unique. A consumer reads
     * one partition in publish order, and a relay that retries a publish has to reach the same
     * partition or the retry arrives out of order relative to the first attempt.
     * {@code repository/TransactionIdentifierSource} draws one value per call from a database
     * sequence, so two calls cannot share a key and one call cannot change its own.
     */
    @Test
    void twoUnresolvedCardsPublishOnEventKeysTheSequenceAllocated() {
        String secondIdentifier = "0000001000000002";
        when(identifiers.nextIdentifier()).thenReturn(ALLOCATED_ID, secondIdentifier);
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        service.authorize(request("504.77"), CALLER);
        service.authorize(request("11.00"), CALLER);

        assertEquals(2, written.size(), "two calls write two rows, one each");
        assertEquals(List.of(ALLOCATED_ID, secondIdentifier),
                written.stream().map(OutboxEventEntity::getAggregateId).toList(),
                "each row is keyed on the identifier its own call allocated");
        assertEquals(2, written.stream().map(OutboxEventEntity::getEventId).distinct().count(),
                "and each carries its own event identifier, which is what every consumer"
                        + " deduplicates on");
    }

    /**
     * Asserts a rule that declines without resolving an account and names another reject code is
     * refused rather than published.
     *
     * <p>{@code schemas/transaction-declined-v2.json} pins {@code declineReasonCode} to {@code 0100},
     * so a chain reaching an unresolved account with any other code would make the decision row and
     * the event disagree. The delivered chain cannot reach that state, because
     * {@code rules/CardCrossReferenceRule} is the only rule that can decline before an account is
     * resolved. A rule list assembled differently can, and the service refuses it there rather than
     * publishing the disagreement.
     */
    @Test
    void aDeclineWithoutAnAccountUnderAnotherReasonIsRefused() {
        AuthorizationService misconfigured = new AuthorizationService(
                List.of(new StoppingRuleNamingAnotherReason()), cardCrossReferences, identifiers,
                new OutboxWriter(outboxEvents), unresolvedCardAttempts, authorizationDecisions,
                new SimpleMeterRegistry(), immediateTransactions(), properties(), cycleExposure,
                caughtUp(), replicaGaps);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> misconfigured.authorize(request("504.77"), CALLER));

        assertTrue(refused.getMessage().contains("transaction-declined-v2.json"),
                "the refusal names the contract that admits one reject code: "
                        + refused.getMessage());
        assertTrue(refused.getMessage().contains(DeclineReason.ACCOUNT_NOT_FOUND.code()),
                "and the code the chain named instead: " + refused.getMessage());
        assertTrue(written.isEmpty(), "nothing is published for a state the contract refuses");
    }

    /**
     * A rule that declines before any account is resolved and names a reject code other than
     * {@code 0100}, which the delivered chain contains no example of.
     */
    private static final class StoppingRuleNamingAnotherReason implements DeclineRule {

        @Override
        public Optional<DeclineReason> evaluate(Context context) {
            return Optional.of(DeclineReason.ACCOUNT_NOT_FOUND);
        }

        @Override
        public Segment segment() {
            return Segment.STOP_ON_FIRST_DECLINE;
        }
    }

    /**
     * Asserts every outcome records the identity that asked for it.
     *
     * <p>ADDITIVE. No source program records that identity: {@code app/cpy/CVTRA05Y.cpy:L4-L18}
     * declares thirteen fields and none names a user, and the two statements at
     * {@code app/cbl/COMEN01C.cbl:L149-L150} that would have carried the signed-on identifier forward
     * are commented out. A decision that cannot be attributed cannot be revoked selectively either.
     */
    @Test
    void everyOutcomeRecordsTheIdentityThatAskedForIt() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome approval = service.authorize(request("504.77"), CALLER);

        assertEquals(1, audited.size(), "one call records one decision");
        assertEquals(approval.transactionId(), audited.get(0).getTransactionId(),
                "the audit row is keyed on the decision it attributes");
        assertEquals(AuthorizationDecisionEntity.APPROVED_OUTCOME, audited.get(0).outcome(),
                "the row records the outcome");
        assertNull(audited.get(0).getDeclineReasonCode(), "an approval carries no reject code");
        assertEquals("************7065", audited.get(0).getMaskedCardNumber(),
                "the row records the masked card number and never the full one");
        assertEquals(ACCOUNT_ID, audited.get(0).getAccountId(), "the row records the account");
        assertEquals(ACTOR, audited.get(0).getActor(),
                "the row records the identity the web layer resolved for the call");

        audited.clear();
        when(accountSnapshots.findForUpdateByAccountId(any())).thenReturn(Optional.empty());
        service.authorize(request("504.77"), CALLER);

        assertEquals(1, audited.size(), "a decline records one decision too");
        assertEquals(AuthorizationDecisionEntity.DECLINED_OUTCOME, audited.get(0).outcome(),
                "the row records the outcome");
        assertEquals(DeclineReason.ACCOUNT_NOT_FOUND.code(),
                audited.get(0).getDeclineReasonCode(), "a decline records its reject code");

        audited.clear();
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());
        service.authorize(request("504.77"), CALLER);

        assertEquals(1, audited.size(), "an unresolved card records one decision too");
        assertNull(audited.get(0).getAccountId(),
                "no account resolved, so the row names none");
        assertEquals(DeclineReason.INVALID_CARD_NUMBER.code(),
                audited.get(0).getDeclineReasonCode(),
                "the row records the reject code app/cbl/CBTRN02C.cbl:L385 assigns");
    }

    /**
     * Asserts the capture moment is tested by reject reason {@code 0103} and by nothing else.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L414-L420} approves whenever the account expiry is at or after
     * the first ten characters of the capture moment, and the comparison is lexical over ten
     * characters of text. A moment years in the past therefore approves against an account whose
     * expiry is later still, and {@code app/cbl/COTRN02C.cbl:L389-L423} applies no clock bound to the
     * value either: it validates the date and nothing more.
     */
    @Test
    void aCaptureMomentYearsOldIsTestedByTheExpiryRuleAlone() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                "2021-01-31");

        AuthorizationService.Outcome outcome = service.authorize(
                requestWithTransactionId(null, "504.77", "2020-01-01"), CALLER);

        assertTrue(outcome.approved(),
                "an expiry after the capture moment approves at app/cbl/CBTRN02C.cbl:L414-L420");
        assertEquals(1, written.size(), "an approval writes one event");
        assertEquals(1, audited.size(), "an approval records one decision");
    }

    /**
     * Asserts a capture moment after the account expiry declines with reject reason {@code 0103}.
     *
     * <p>This is the one test {@code app/cbl/CBTRN02C.cbl:L414-L420} applies to the capture moment,
     * and it compares ten characters of text.
     */
    @Test
    void aCaptureMomentAfterTheAccountExpiryDeclinesWithItsOwnCode() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                "2019-12-31");

        AuthorizationService.Outcome outcome = service.authorize(
                requestWithTransactionId(null, "504.77", "2020-01-01"), CALLER);

        assertFalse(outcome.approved(), "a capture moment after the expiry declines");
        assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                "the decline carries the reject reason app/cbl/CBTRN02C.cbl:L417 assigns");
    }

    /**
     * Asserts the chain stops at the cross-reference read, so a missing card never reaches the
     * account read.
     */
    @Test
    void aMissingCardNeverReachesTheAccountRead() {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        service.authorize(request("504.77"), CALLER);

        verify(accountSnapshots, never()).findByAccountId(any());
    }

    /** Asserts a missing account declines with reject code {@code 0101} and writes one event. */
    @Test
    void aMissingAccountDeclinesWithItsOwnCode() {
        resolveCard();
        when(accountSnapshots.findForUpdateByAccountId(any())).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), CALLER);

        assertEquals(Optional.of(DeclineReason.ACCOUNT_NOT_FOUND), outcome.declineReason(),
                "app/cbl/CBTRN02C.cbl:L397 assigns this code");
        assertEquals("ACCOUNT RECORD NOT FOUND", describe(outcome),
                "the text comes from app/cbl/CBTRN02C.cbl:L398-L399");
        assertEquals(new BigDecimal(ACCOUNT_ID), outcome.accountId(),
                "the cross-reference row named the account, so the decline names it too");
        assertEquals(1, written.size(), "one call writes one event");
        assertEquals(TransactionDeclined.EVENT_TYPE, written.get(0).getEventType(),
                "a decline writes the declined event");
    }

    /**
     * Asserts a transaction above the credit limit declines with reject code {@code 0102}.
     *
     * <p>The tested value is cycle credit minus cycle debit plus the amount, from
     * {@code app/cbl/CBTRN02C.cbl:L403-L405}, and the current balance takes no part.
     */
    @Test
    void aTransactionAboveTheCreditLimitDeclinesWithItsOwnCode() {
        resolveCard();
        resolveAccount(new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("100.01"), CALLER);

        assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome.declineReason(),
                "app/cbl/CBTRN02C.cbl:L410 assigns this code");
        assertEquals("OVERLIMIT TRANSACTION", describe(outcome),
                "the text comes from app/cbl/CBTRN02C.cbl:L411-L412");
    }

    /** Asserts a transaction exactly at the credit limit is approved. */
    @Test
    void aTransactionExactlyAtTheCreditLimitIsApproved() {
        resolveCard();
        resolveAccount(new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("100.00"), CALLER);

        assertTrue(outcome.approved(),
                "app/cbl/CBTRN02C.cbl:L407 approves on greater-or-equal, not on greater");
    }

    /** Asserts a transaction after the account expiry date declines with reject code {@code 0103}. */
    @Test
    void aTransactionAfterTheExpiryDateDeclinesWithItsOwnCode() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_BEFORE_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), CALLER);

        assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                "app/cbl/CBTRN02C.cbl:L417 assigns this code");
        assertEquals("TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
                describe(outcome),
                "the text comes from app/cbl/CBTRN02C.cbl:L418-L419");
    }

    /**
     * Asserts a transaction failing both the credit test and the expiry test reports the expiry code.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L407-L420} holds two sequential tests with no gate between them,
     * so the second assignment overwrites the first.
     */
    @Test
    void aTransactionFailingBothLaterTestsReportsTheExpiryCode() {
        resolveCard();
        resolveAccount(new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_BEFORE_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("100.01"), CALLER);

        assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                "the later assignment wins, and the earlier one does not survive");
        assertEquals(1, written.size(), "one call still writes one event");
    }

    /**
     * Asserts a refund raises the tested value, tightening the next authorization.
     *
     * <p>A negative amount reaches the cycle-debit accumulator at
     * {@code app/cbl/CBTRN02C.cbl:L551}, and the formula subtracts that accumulator. The behaviour is
     * reproduced deliberately.
     */
    @Test
    void aNegativeCycleDebitRaisesTheTestedValue() {
        resolveCard();
        resolveAccount(new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("-50.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("60.00"), CALLER);

        assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome.declineReason(),
                "zero minus minus fifty plus sixty reaches one hundred and ten, above the limit");
    }

    /**
     * Asserts the narrowed working field loses a high-order digit, turning a decline into an approval.
     *
     * <p>{@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187} holds nine digits
     * before the decimal point while its operands hold ten. The fixtures reach no such value, so this
     * case is constructed. The behaviour is reproduced deliberately and flagged for review.
     */
    @Test
    void theNarrowedWorkingFieldLosesAHighOrderDigit() {
        resolveCard();
        resolveAccount(new BigDecimal("100.00"), new BigDecimal("1000000000.00"),
                new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("50.00"), CALLER);

        assertTrue(outcome.approved(),
                "one billion and fifty stores as fifty in a nine-digit field, so the limit of one "
                        + "hundred accepts it");
    }

    /**
     * Asserts the service allocates the identifier even when the caller supplied one.
     *
     * <p>{@code api/AuthorizationRequest} refuses a supplied identifier before the decision runs, and
     * this test covers the decision itself: a value that reached it anyway is discarded rather than
     * used. An identifier a caller chooses is one a caller can repeat, and a repeated identifier
     * merges one payment with another downstream.
     */
    @Test
    void theIdentifierIsAllocatedEvenWhenTheCallerSuppliedOne() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(
                requestWithTransactionId("0000000000683580", "504.77"), CALLER);

        assertEquals(ALLOCATED_ID, outcome.transactionId(),
                "the decision ran on the allocated identifier and not on the supplied one");
    }

    /**
     * Asserts the first record of the daily-transaction fixture is authorized, against the account
     * its card resolves to.
     *
     * <p>Read from {@code app/data/ASCII/dailytran.txt} record one: card number at positions 263 to
     * 278, amount at 133 to 143 as {@code 0000005047G} whose overpunch carries a positive seven, and
     * capture timestamp at 279 to 304. {@code app/data/ASCII/cardxref.txt} resolves that card to
     * account {@code 00000000007}, and {@code app/data/ASCII/acctdata.txt} gives that account a
     * credit limit of {@code 2065.00} and an expiry date of {@code 2024-12-13}.
     *
     * <p>Both later tests accept: {@code 0.00} less {@code 0.00} plus {@code 504.77} stays under the
     * limit, and {@code 2024-12-13} is not earlier than {@code 2022-06-10}.
     */
    @Test
    void theFirstFixtureRecordIsAuthorizedAgainstTheAccountItsCardResolvesTo() {
        String fixtureAccountId = "00000000007";
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.of(
                new CardCrossReferenceEntity(CARD_NUMBER, "000000007", fixtureAccountId,
                        OBSERVED_AT)));
        when(accountSnapshots.findForUpdateByAccountId(fixtureAccountId)).thenReturn(
                Optional.of(new AccountCreditSnapshotEntity(fixtureAccountId,
                        new BigDecimal("2065.00"), "2024-12-13", new BigDecimal("0.00"),
                        new BigDecimal("0.00"), OBSERVED_AT)));

        AuthorizationService.Outcome outcome = service.authorize(
                requestWithTransactionId(null, "504.77", "2022-06-10 19:27:53.000000"), CALLER);

        assertTrue(outcome.approved(), "504.77 fits a limit of 2065.00 and an expiry of 2024-12-13");
        assertEquals(new BigDecimal(fixtureAccountId), outcome.accountId(),
                "the card resolves to account seven, not to account one");
        assertEquals(Optional.empty(), outcome.declineReason(), "no rule declined");
        assertEquals(1, written.size(), "one call writes one event");
    }

    /**
     * Asserts a card number narrower than the cross-reference key is refused before any read.
     *
     * <p>Padding a caller's value would authorize a different sixteen-character key. The request
     * contract therefore requires the full width and the domain refuses a value whose canonical
     * form is absent.</p>
     */
    @Test
    void aCardNarrowerThanTheKeyIsRefusedBeforeTheRead() {
        String storedKey = "0859452612877065";
        String shortCard = storedKey.substring(1);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.authorize(requestWithCardNumber(shortCard), CALLER));

        assertEquals(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE, refused.getMessage(),
                "the refusal uses the source-shaped missing-identifier text");
        verify(cardCrossReferences, never()).findByCardNumber(storedKey);
        verify(cardCrossReferences, never()).findByCardNumber(shortCard);
    }

    /**
     * Asserts a request naming only an account resolves its card and decides on that card.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L208} performs {@code READ-CXACAIX-FILE} on the account
     * identifier and {@code :L209} moves {@code XREF-CARD-NUM} into the field the validation then
     * uses, so the account branch resolves a card and decides exactly as the card branch would. The
     * read runs against the alternate index defined at {@code app/jcl/XREFFILE.jcl:L72-L77}, and the
     * target form takes the row in ascending card-number order so one account always resolves the
     * same card.
     */
    @Test
    void aRequestNamingOnlyAnAccountResolvesItsCardAndDecides() {
        resolveCardFromAccount();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome =
                service.authorize(requestNamingOnlyAnAccount(), CALLER);

        assertTrue(outcome.approved(), "the card the account resolved decides the request");
        assertEquals(new BigDecimal(ACCOUNT_ID), outcome.accountId(),
                "the outcome names the account the resolved row carries");
        verify(cardCrossReferences).findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID);
        assertEquals(1, written.size(), "one account-only request writes one event");
        assertEquals(PanMasker.maskCardNumber(CARD_NUMBER), audited.get(0).getMaskedCardNumber(),
                "the decision row records the resolved card, masked, and not the account");
        assertEquals(PanMasker.cardToken(CARD_NUMBER), audited.get(0).getCardToken(),
                "the decision row carries the token derived from the resolved card");
    }

    /**
     * Asserts the resolved card is the one the decision runs on, not one the caller may name, and
     * that resolving it costs one read of the cross-reference table rather than two.
     *
     * <p>The account branch has no card number of its own, so the value the rules see has to come
     * from the cross-reference row. Reading it from anywhere else would decide against a card the
     * row does not carry.
     *
     * <p>That read answers with the whole row, and {@code card_number} is the table's primary key, so
     * a keyed read of the card number it carries would return the row already in hand. The row is
     * therefore carried into the rule chain and the keyed read does not run: the assertions below are
     * that the account finder ran once, that no keyed read ran at all, and that the rules still
     * decided on the resolved card against the resolved account. The decline proves it, because
     * reaching the credit-limit rule at all requires the row to have named the account whose
     * hundred-dollar limit refuses this amount.
     */
    @Test
    void theAccountBranchDecidesOnTheCardTheCrossReferenceRowCarries() {
        resolveCardFromAccount();
        resolveAccount(new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome =
                service.authorize(requestNamingOnlyAnAccount(), CALLER);

        assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome.declineReason(),
                "the resolved card reached the credit-limit rule on the resolved account");
        verify(cardCrossReferences, times(1))
                .findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID);
        verify(cardCrossReferences, never()).findByCardNumber(any());
        assertEquals(DeclineReason.OVER_CREDIT_LIMIT.description(),
                describe(outcome), "the decline carries the source description");
    }

    /**
     * Asserts an account holding no cross-reference row is an input refusal and not a decline.
     *
     * <p>The {@code NOTFND} limb of {@code READ-CXACAIX-FILE} at
     * {@code app/cbl/COTRN02C.cbl:L591-L592} answers
     * {@value AuthorizationRequest#ACCOUNT_ID_NOT_FOUND_MESSAGE} and re-sends the screen, so nothing
     * is captured. It is not reject reason {@code 0100}: a request naming no card number has no card
     * number to record, mask or tokenize on a declined event.
     */
    @Test
    void anAccountHoldingNoCardIsRefusedRatherThanDeclined() {
        when(cardCrossReferences.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                .thenReturn(Optional.empty());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.authorize(requestNamingOnlyAnAccount(), CALLER));

        assertEquals(AuthorizationRequest.ACCOUNT_ID_NOT_FOUND_MESSAGE, refused.getMessage(),
                "the refusal carries the NOTFND text of app/cbl/COTRN02C.cbl:L591-L592");
        verify(identifiers, never()).nextIdentifier();
        assertEquals(List.of(), written, "a refused request writes no event");
        assertEquals(List.of(), audited, "a refused request records no decision");
    }

    /**
     * Asserts a request naming neither identifier still carries the {@code WHEN OTHER} refusal.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L224-L229} is the limb that answers when both fields are empty,
     * and it is the one refusal the two accepted branches leave. No cross-reference read runs,
     * because there is no identifier to read on.
     */
    @Test
    void aRequestNamingNeitherIdentifierIsRefusedBeforeAnyRead() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.authorize(requestNamingNeitherIdentifier(), CALLER));

        assertEquals(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE, refused.getMessage(),
                "the refusal carries the WHEN OTHER text of app/cbl/COTRN02C.cbl:L226");
        verify(cardCrossReferences, never()).findFirstByAccountIdOrderByCardNumberAsc(any());
        verify(cardCrossReferences, never()).findByCardNumber(any());
        assertEquals(List.of(), written, "a refused request writes no event");
        assertEquals(List.of(), audited, "a refused request records no decision");
    }

    /**
     * Asserts a request naming both identifiers is decided by the account branch.
     *
     * <p>The {@code EVALUATE TRUE} at {@code app/cbl/COTRN02C.cbl:L195} tests the account field
     * first, so a request carrying both values takes the account limb and the card limb never runs.
     * The card the cross-reference row names then reaches the rules.
     */
    @Test
    void aRequestNamingBothIdentifiersTakesTheAccountBranch() {
        resolveCardFromAccount();
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(
                build(CARD_NUMBER, ACCOUNT_ID, "504.77", ORIGIN_TIMESTAMP, null), CALLER);

        assertTrue(outcome.approved(), "the card the account resolved decides the request");
        assertEquals(new BigDecimal(ACCOUNT_ID), outcome.accountId(),
                "the outcome names the account the resolved row carries");
        verify(cardCrossReferences).findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID);
        assertEquals(1, written.size(), "one request writes one event");
    }

    /**
     * Asserts the card the cross-reference row carries replaces the card the caller named.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L209} moves {@code XREF-CARD-NUM} into {@code CARDNINI}, which
     * overwrites whatever the card field held. Every later paragraph reads the overwritten field, and
     * {@code app/cbl/COTRN02C.cbl:L462} stores it on the transaction record, so the resolved card is
     * the card the decision runs on.
     */
    @Test
    void theResolvedCardReplacesTheCardTheCallerNamed() {
        String cardTheCallerNamed = "9999999999999999";
        resolveCardFromAccount();
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(
                build(cardTheCallerNamed, ACCOUNT_ID, "504.77", ORIGIN_TIMESTAMP, null), CALLER);

        assertTrue(outcome.approved(), "the resolved card decides the request");
        assertEquals(PanMasker.maskCardNumber(CARD_NUMBER), audited.get(0).getMaskedCardNumber(),
                "the decision row records the resolved card, masked, and not the card supplied");
        verify(cardCrossReferences, never()).findByCardNumber(cardTheCallerNamed);
    }

    /**
     * Asserts a card-only request whose card resolves no account records the unresolved attempt.
     *
     * <p>The card limb of {@code app/cbl/COTRN02C.cbl:L210-L218} runs only where the account field is
     * empty, and reject reason {@code 0100} at {@code app/cbl/CBTRN02C.cbl:L385-L387} is the outcome
     * of a cross-reference read that missed.
     */
    @Test
    void aCardResolvingNoAccountRecordsTheUnresolvedAttempt() {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), CALLER);

        assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER), outcome.declineReason());
        assertNull(outcome.accountId(), "no account resolved, so the outcome names none");
        assertEquals(ALLOCATED_ID, outcome.transactionId(),
                "the decision still carries the identifier this service allocated for it");
        assertNull(audited.get(0).getAccountId(),
                "the audit row names no account either");
        assertEquals(1, written.size(),
                "and one event is still published, keyed on the transaction identifier because there"
                        + " is no account to key it on");
        assertEquals(ALLOCATED_ID, written.get(0).getAggregateId(),
                "the key is the identifier this call allocated");
    }

    /**
     * Asserts a transaction dated exactly on the expiry date is approved.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L414} compares on greater-or-equal, so equality accepts.
     */
    @Test
    void aTransactionDatedExactlyOnTheExpiryDateIsApproved() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                ORIGIN_TIMESTAMP.substring(0, 10));

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), CALLER);

        assertTrue(outcome.approved(),
                "app/cbl/CBTRN02C.cbl:L414 approves on greater-or-equal, not on greater");
    }

    /**
     * Asserts one more rule joins the chain without any edit to the service.
     *
     * <p>The rule below declares {@link DeclineRule.Segment#LAST_DECLINE_WINS} and declines, so it
     * overwrites whatever the two rules before it left standing.
     */
    @Test
    void oneMoreRuleJoinsTheChainWithoutEditingTheService() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        List<DeclineRule> rules = new ArrayList<>(
                List.of(new CardCrossReferenceRule(cardCrossReferences),
                        new AccountExistsRule(accountSnapshots), new CreditLimitRule(cycleExposure),
                        new AccountExpirationRule()));
        rules.add(new DeclineRule() {

            @Override
            public Optional<DeclineReason> evaluate(DeclineRule.Context context) {
                return Optional.of(DeclineReason.OVER_CREDIT_LIMIT);
            }

            @Override
            public DeclineRule.Segment segment() {
                return DeclineRule.Segment.LAST_DECLINE_WINS;
            }
        });
        TransactionIdentifierSource identifiers = mock(TransactionIdentifierSource.class);
        when(identifiers.nextIdentifier()).thenReturn(ALLOCATED_ID);
        AuthorizationService extended = new AuthorizationService(rules, cardCrossReferences,
                identifiers, new OutboxWriter(outboxEvents), unresolvedCardAttempts,
                authorizationDecisions, new SimpleMeterRegistry(),
                immediateTransactions(), properties(), cycleExposure, caughtUp(), replicaGaps);

        AuthorizationService.Outcome outcome = extended.authorize(request("504.77"), CALLER);

        assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome.declineReason(),
                "the added rule ran last and its answer stands");
    }

    /** Asserts a stopping rule ends the chain, so no later rule overwrites its answer. */
    @Test
    void aStoppingRuleEndsTheChainBeforeTheOverwritingSegmentRuns() {
        resolveCard();
        when(accountSnapshots.findForUpdateByAccountId(any())).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(request("100.01"), CALLER);

        assertEquals(Optional.of(DeclineReason.ACCOUNT_NOT_FOUND), outcome.declineReason(),
                "the credit test and the expiry test never ran, so neither overwrote this answer");
    }

    /** Asserts an approval naming a reject reason cannot be built. */
    @Test
    void anApprovalNamingARejectReasonCannotBeBuilt() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationService.Outcome(true,
                        Optional.of(DeclineReason.OVER_CREDIT_LIMIT), BigDecimal.ONE,
                        ALLOCATED_ID));

        assertTrue(thrown.getMessage().contains("declineReason"),
                "the message names the component that broke the check");
    }

    /** Asserts a decline naming no reject reason cannot be built. */
    @Test
    void aDeclineNamingNoRejectReasonCannotBeBuilt() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationService.Outcome(false, Optional.empty(), BigDecimal.ONE,
                        ALLOCATED_ID));

        assertTrue(thrown.getMessage().contains("declineReason"),
                "the message names the component that broke the check");
    }

    /** Asserts an approval naming no account cannot be built. */
    @Test
    void anApprovalNamingNoAccountCannotBeBuilt() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> AuthorizationService.Outcome.approved(null, ALLOCATED_ID));

        assertTrue(thrown.getMessage().contains("accountId"),
                "the message names the component that broke the check");
    }

    /** Asserts an account identifier carrying a scale cannot be built. */
    @Test
    void anAccountIdentifierCarryingAScaleCannotBeBuilt() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> AuthorizationService.Outcome.approved(new BigDecimal("7.0"), ALLOCATED_ID));

        assertTrue(thrown.getMessage().contains("scale"),
                "the message names the check the value broke");
    }

    /**
     * Names one decided outcome, with the reject reason the source assigns to it.
     *
     * <p>The five values are the whole decided outcome set of
     * {@code app/cbl/CBTRN02C.cbl:L380-L420}: the approval, and the four reject reasons.
     */
    private enum DecidedOutcome {

        /** Every rule accepted. */
        APPROVAL(null),

        /** Reject code 0100: the cross-reference does not carry the card. */
        UNRESOLVED_CARD(DeclineReason.INVALID_CARD_NUMBER),

        /** Reject code 0101: the account file does not carry the account. */
        ACCOUNT_MISSING(DeclineReason.ACCOUNT_NOT_FOUND),

        /** Reject code 0102: the credit limit refuses the amount. */
        OVER_LIMIT(DeclineReason.OVER_CREDIT_LIMIT),

        /** Reject code 0103: the account expiry has passed. */
        EXPIRED(DeclineReason.ACCOUNT_EXPIRED);

        /** The reject reason that must stand, or {@code null} on the approval. */
        private final DeclineReason reason;

        DecidedOutcome(DeclineReason reason) {
            this.reason = reason;
        }
    }

    /**
     * Asserts every decided outcome writes exactly one outbox row and names it on the decision.
     *
     * <p>This is transformation rule T4 measured across the whole outcome set rather than one branch
     * at a time. Reject code {@code 0100} is the one whose event names no account, and it is counted
     * here beside the others precisely because it once counted zero.
     *
     * @param outcomeUnderTest the outcome to arrange and measure
     */
    @ParameterizedTest
    @EnumSource(DecidedOutcome.class)
    @DisplayName("every decided outcome writes exactly one event and the decision names it")
    void everyDecidedOutcomeWritesExactlyOneEvent(DecidedOutcome outcomeUnderTest) {
        arrange(outcomeUnderTest);

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), CALLER);

        assertEquals(Optional.ofNullable(outcomeUnderTest.reason), outcome.declineReason(),
                outcomeUnderTest + " must produce the reject reason the source assigns");
        assertEquals(1, written.size(),
                outcomeUnderTest + " writes exactly one outbox row, never none and never two");
        assertEquals(1, audited.size(), outcomeUnderTest + " records exactly one decision");
        assertEquals(written.get(0).getEventId(), audited.get(0).getEventId(),
                "the decision names the event the same transaction wrote");
        assertEquals(outcomeUnderTest.reason == null
                        ? TransactionAuthorized.EVENT_TYPE : TransactionDeclined.EVENT_TYPE,
                written.get(0).getEventType(),
                "the contract follows the outcome");
    }

    /**
     * Stubs the reads that produce one decided outcome.
     *
     * @param outcomeUnderTest the outcome to arrange
     */
    private void arrange(DecidedOutcome outcomeUnderTest) {
        switch (outcomeUnderTest) {
            case APPROVAL -> {
                resolveCard();
                resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);
            }
            case UNRESOLVED_CARD ->
                    when(cardCrossReferences.findByCardNumber(CARD_NUMBER))
                            .thenReturn(Optional.empty());
            case ACCOUNT_MISSING -> {
                resolveCard();
                when(accountSnapshots.findForUpdateByAccountId(ACCOUNT_ID))
                        .thenReturn(Optional.empty());
            }
            case OVER_LIMIT -> {
                resolveCard();
                resolveAccount(new BigDecimal("100.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);
            }
            case EXPIRED -> {
                resolveCard();
                resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), EXPIRY_BEFORE_CAPTURE);
            }
        }
    }

    /**
     * Reads the reject text the decision carries.
     *
     * @param outcome the decision under test
     * @return the reject text, or {@code null} on an approval
     */
    private static String describe(AuthorizationService.Outcome outcome) {
        return outcome.declineReason().map(DeclineReason::description).orElse(null);
    }

    /**
     * Stubs the alternate-index read so {@link #ACCOUNT_ID} resolves {@link #CARD_NUMBER}.
     *
     * <p>This is {@code READ-CXACAIX-FILE} at {@code app/cbl/COTRN02C.cbl:L208}, the read the
     * account branch performs before {@code :L209} moves the card number it found into the field the
     * validation uses.
     */
    private void resolveCardFromAccount() {
        when(cardCrossReferences.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(
                        new CardCrossReferenceEntity(CARD_NUMBER, "000000011", ACCOUNT_ID,
                                OBSERVED_AT)));
    }

    /** Stubs the cross-reference read so the card resolves to {@link #ACCOUNT_ID}. */
    private void resolveCard() {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER))
                .thenReturn(Optional.of(
                        new CardCrossReferenceEntity(CARD_NUMBER, "000000011", ACCOUNT_ID,
                                OBSERVED_AT)));
    }

    /**
     * Stubs the cross-reference read with an observation moment of this test's choosing.
     *
     * @param observedAt when the replica row was last written
     */
    private void resolveCardObservedAt(Instant observedAt) {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER))
                .thenReturn(Optional.of(
                        new CardCrossReferenceEntity(CARD_NUMBER, "000000011", ACCOUNT_ID,
                                observedAt)));
    }

    /**
     * Stubs the account read with an observation moment of this test's choosing, and values every
     * rule accepts.
     *
     * @param observedAt when the replica row was last written
     */
    private void resolveAccountObservedAt(Instant observedAt) {
        AccountCreditSnapshotEntity snapshot = new AccountCreditSnapshotEntity(ACCOUNT_ID,
                new BigDecimal("5000.00"), EXPIRY_AFTER_CAPTURE, new BigDecimal("0.00"),
                new BigDecimal("0.00"), observedAt);
        when(accountSnapshots.findForUpdateByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(snapshot));
    }

    /**
     * Stubs the account read with the four values the two later rules test.
     *
     * @param creditLimit the credit limit
     * @param cycleCredit the cycle-credit accumulator
     * @param cycleDebit  the cycle-debit accumulator
     * @param expiryDate  the ten-character expiry date
     */
    private void resolveAccount(BigDecimal creditLimit, BigDecimal cycleCredit,
            BigDecimal cycleDebit, String expiryDate) {
        AccountCreditSnapshotEntity snapshot = new AccountCreditSnapshotEntity(ACCOUNT_ID,
                creditLimit, expiryDate, cycleCredit, cycleDebit, OBSERVED_AT);
        when(accountSnapshots.findForUpdateByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(snapshot));
    }

    /**
     * Builds a valid request presenting a card number, with no identifier of its own.
     *
     * @param amount the amount as text
     * @return the request
     */
    private static AuthorizationRequest request(String amount) {
        return requestWithTransactionId(null, amount);
    }

    /**
     * Builds a valid request presenting a card number.
     *
     * @param transactionId the identifier, or {@code null} to have the service allocate one
     * @param amount        the amount as text
     * @return the request
     */
    private static AuthorizationRequest requestWithTransactionId(String transactionId,
            String amount) {
        return requestWithTransactionId(transactionId, amount, ORIGIN_TIMESTAMP);
    }

    /**
     * Builds a valid request presenting a card number and a capture timestamp of its own.
     *
     * @param transactionId   the identifier, or {@code null} to have the service allocate one
     * @param amount          the amount as text
     * @param originTimestamp the twenty-six character capture timestamp
     * @return the request
     */
    private static AuthorizationRequest requestWithTransactionId(String transactionId,
            String amount, String originTimestamp) {
        return build(CARD_NUMBER, null, amount, originTimestamp, transactionId);
    }

    /**
     * Builds a valid request presenting the card number given, with no identifier of its own.
     *
     * @param cardNumber the card number as text, at any width the pattern accepts
     * @return the request
     */
    private static AuthorizationRequest requestWithCardNumber(String cardNumber) {
        return build(cardNumber, null, "504.77", ORIGIN_TIMESTAMP, null);
    }

    /** @return a valid request naming {@link #ACCOUNT_ID} and no card number */
    private static AuthorizationRequest requestNamingOnlyAnAccount() {
        return build(null, ACCOUNT_ID, "504.77", ORIGIN_TIMESTAMP, null);
    }

    /** @return a request naming neither identifier, which is the WHEN OTHER limb's input */
    private static AuthorizationRequest requestNamingNeitherIdentifier() {
        return build(null, null, "504.77", ORIGIN_TIMESTAMP, null);
    }

    /**
     * Builds a request from the values these tests vary, with the rest held at fixture values.
     *
     * <p>The merchant and description values come from record one of
     * {@code app/data/ASCII/dailytran.txt}.
     *
     * @param cardNumber      the card number, or {@code null} to name none
     * @param accountId       the account identifier, or {@code null} to name none
     * @param amount          the amount as text
     * @param originTimestamp the twenty-six character capture timestamp
     * @param transactionId   the identifier, or {@code null} to have the service allocate one
     * @return the request
     */
    private static AuthorizationRequest build(String cardNumber, String accountId, String amount,
            String originTimestamp, String transactionId) {
        return build(cardNumber, accountId, amount, originTimestamp, transactionId, "0001");
    }

    /**
     * Builds a valid request carrying the category code given.
     *
     * @param cardNumber      the card number as text, or {@code null}
     * @param accountId       the account identifier as text, or {@code null}
     * @param amount          the amount as text
     * @param originTimestamp the twenty-six character capture timestamp
     * @param transactionId   the identifier, or {@code null} to have the service allocate one
     * @param categoryCode    the category code, at any width the pattern accepts
     * @return the request
     */
    private static AuthorizationRequest build(String cardNumber, String accountId, String amount,
            String originTimestamp, String transactionId, String categoryCode) {
        return new AuthorizationRequest(transactionId, "01", categoryCode, "POS TERM",
                "Purchase at Abshire-Lowe", amount, "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", cardNumber, originTimestamp,
                "2022-06-10-19.27.53.410000", accountId);
    }

    /**
     * Holds the control that keeps this service from authorizing against a replica it knows may be
     * missing a change.
     *
     * <p>ADDITIVE. The source has no equivalent: {@code app/cbl/CBTRN02C.cbl:L382} and
     * {@code app/cbl/CBTRN02C.cbl:L395} read the cross-reference and account datasets themselves, so
     * nothing they read can be out of date. This service reads copies kept current by state-change
     * events, and a copy whose events stopped arriving keeps answering with whatever it last knew.
     *
     * <p>What is asserted here is the shape of the question. An earlier form of this control compared
     * the age of a replica row against a window, and the first test below is the case that made it
     * wrong: both producers publish on a state change and on nothing else, so an untouched card is a
     * correct copy whose last observation recedes for ever. The two conditions that do refuse follow
     * it.
     */
    @Nested
    @DisplayName("Replica usability")
    class ReplicaUsability {

        /** An observation older than any window a deployment would have configured. */
        private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

        /** Builds the service over one verdict source, with no gap standing. */
        private AuthorizationService serviceOver(ReplicaSynchronization synchronization) {
            List<DeclineRule> rules = List.of(new CardCrossReferenceRule(cardCrossReferences),
                    new AccountExistsRule(accountSnapshots), new CreditLimitRule(cycleExposure),
                    new AccountExpirationRule());
            TransactionIdentifierSource identifiers = mock(TransactionIdentifierSource.class);
            when(identifiers.nextIdentifier()).thenReturn(ALLOCATED_ID);

            return new AuthorizationService(rules, cardCrossReferences, identifiers,
                    new OutboxWriter(outboxEvents), unresolvedCardAttempts, authorizationDecisions,
                    meters, immediateTransactions(), properties(), cycleExposure, synchronization,
                    replicaGaps);
        }

        /**
         * The regression this control was rebuilt for. A card nobody has edited since the seed load
         * carries an observation as old as that load, and the platform has to keep authorizing it: the
         * card service publishes {@code CardUpdated} on a change and on nothing else, so there is no
         * event that would make the observation newer and nothing wrong with the copy.
         */
        @Test
        @DisplayName("authorizes a row untouched for years while its streams are caught up")
        void authorizesARowUntouchedForYearsWhileItsStreamsAreCaughtUp() {
            resolveCardObservedAt(LONG_AGO);
            resolveAccountObservedAt(LONG_AGO);

            AuthorizationService.Outcome outcome =
                    serviceOver(caughtUp()).authorize(requestWithCardNumber(CARD_NUMBER), CALLER);

            assertTrue(outcome.approved(),
                    "an unedited card is a correct copy, however long ago it was last written");
            assertEquals(0.0d,
                    meters.counter(AuthorizationService.FAILURES_COUNTER, "stage",
                            AuthorizationService.REPLICA_STAGE).count(),
                    "and nothing about its age is an infrastructure fault");
        }

        @Test
        @DisplayName("refuses the call when a replica stream has records waiting, and writes no event")
        void refusesTheCallWhenAReplicaStreamHasRecordsWaiting() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

            AuthorizationService.StaleReplicaException refused = assertThrows(
                    AuthorizationService.StaleReplicaException.class,
                    () -> serviceOver(behind()).authorize(requestWithCardNumber(CARD_NUMBER),
                            CALLER));

            assertEquals("replica-stream-behind", refused.getReason(),
                    "the refusal names the condition and no account");
            assertTrue(written.isEmpty(),
                    "a call this service could not answer must write no event, because an event is"
                            + " what makes a decision real to every consumer");
        }

        /**
         * The half consumer lag cannot see. A record that was delivered and could not be applied has
         * its offset advanced once its diagnostic is away, so the stream reports itself caught up while
         * that one account's copy is behind.
         */
        @Test
        @DisplayName("refuses the one account a delivery failed to apply a change for")
        void refusesTheOneAccountADeliveryFailedToApplyAChangeFor() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);
            when(replicaGaps.existsForAggregate(ACCOUNT_ID)).thenReturn(true);

            AuthorizationService.StaleReplicaException refused = assertThrows(
                    AuthorizationService.StaleReplicaException.class,
                    () -> serviceOver(caughtUp()).authorize(requestWithCardNumber(CARD_NUMBER),
                            CALLER));

            assertEquals(AuthorizationService.StaleReplicaException.UNAPPLIED_CHANGE,
                    refused.getReason(), "the refusal names the unapplied change");
            assertEquals(1.0d,
                    meters.counter(AuthorizationService.FAILURES_COUNTER, "stage",
                            AuthorizationService.REPLICA_STAGE).count(),
                    "one replica-stage fault");
            assertEquals(0.0d,
                    meters.counter(AuthorizationService.DECISION_COUNTER, "outcome",
                            DeclineReason.INVALID_CARD_NUMBER.code()).count(),
                    "a refusal is not a decline and must not be counted as one");
        }

        /**
         * A card that resolves to no account is already reject reason {@code 0100} per
         * {@code app/cbl/CBTRN02C.cbl:L385-L387}, and it read no replica value worth checking.
         * Refusing it would replace a reject reason the source defines with a service fault.
         */
        @Test
        @DisplayName("does not apply to a call whose card resolves to no account")
        void doesNotApplyToACallWhoseCardResolvesToNoAccount() {
            when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

            AuthorizationService.Outcome outcome =
                    serviceOver(behind()).authorize(requestWithCardNumber(CARD_NUMBER), CALLER);

            assertFalse(outcome.approved(), "an unresolved card is declined");
            assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER), outcome.declineReason(),
                    "and it carries reason 0100, not a refusal");
        }

        /**
         * An absent account row is reject reason {@code 0101} at
         * {@code app/cbl/CBTRN02C.cbl:L397-L399}, and that decline comes from the absence rather than
         * from any value this service holds a copy of.
         */
        @Test
        @DisplayName("does not apply to an absent account row, which keeps reason 0101")
        void doesNotApplyToAnAbsentAccountRowWhichKeepsItsOwnReason() {
            resolveCard();
            when(accountSnapshots.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

            AuthorizationService.Outcome outcome =
                    serviceOver(behind()).authorize(requestWithCardNumber(CARD_NUMBER), CALLER);

            assertEquals(Optional.of(DeclineReason.ACCOUNT_NOT_FOUND), outcome.declineReason(),
                    "an absent account row carries reason 0101, not a refusal");
            assertEquals(0.0d,
                    meters.counter(AuthorizationService.FAILURES_COUNTER, "stage",
                            AuthorizationService.REPLICA_STAGE).count(),
                    "and it is not counted as an infrastructure fault");
        }
    }

    /**
     * Holds every measurement of a decision outside the transaction that commits it.
     *
     * <p>A meter takes no part in a database transaction, so an increment inside one survives the
     * rollback that discards the decision. The reported approval count would then exceed the events the
     * outbox holds, and the difference would be invisible.
     */
    @Nested
    @DisplayName("Measurements and the commit")
    class MetricsAfterCommit {

        /** Builds the service over a boundary that rolls back instead of committing. */
        private AuthorizationService serviceOverFailingCommit() {
            List<DeclineRule> rules = List.of(new CardCrossReferenceRule(cardCrossReferences),
                    new AccountExistsRule(accountSnapshots), new CreditLimitRule(cycleExposure),
                    new AccountExpirationRule());
            TransactionIdentifierSource identifiers = mock(TransactionIdentifierSource.class);
            when(identifiers.nextIdentifier()).thenReturn(ALLOCATED_ID);

            TransactionTemplate failing = new TransactionTemplate() {
                @Override
                public <T> T execute(TransactionCallback<T> action) {
                    action.doInTransaction(new SimpleTransactionStatus(true));
                    throw new org.springframework.dao.CannotAcquireLockException("commit refused");
                }
            };

            return new AuthorizationService(rules, cardCrossReferences, identifiers,
                    new OutboxWriter(outboxEvents), unresolvedCardAttempts, authorizationDecisions,
                    meters, failing, properties(), cycleExposure, caughtUp(), replicaGaps);
        }

        @Test
        @DisplayName("counts no approval when the commit fails, and counts the persist fault")
        void countsNoApprovalWhenTheCommitFailsAndCountsThePersistFault() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

            assertThrows(org.springframework.dao.CannotAcquireLockException.class,
                    () -> serviceOverFailingCommit()
                            .authorize(requestWithCardNumber(CARD_NUMBER), CALLER));

            assertEquals(0.0d,
                    meters.counter(AuthorizationService.DECISION_COUNTER, "outcome",
                            AuthorizationService.APPROVED_OUTCOME_TAG).count(),
                    "an approval that did not commit must not be counted as one");
            assertEquals(0.0d,
                    meters.counter(AuthorizationService.EVENT_COUNTER, "eventType",
                            TransactionAuthorized.EVENT_TYPE).count(),
                    "an event row that rolled back must not be counted as written");
            assertEquals(1.0d,
                    meters.counter(AuthorizationService.FAILURES_COUNTER, "stage",
                            AuthorizationService.PERSIST_STAGE).count(),
                    "the persist stage carries the fault");
        }

        @Test
        @DisplayName("counts the approval and its event once the commit has happened")
        void countsTheApprovalAndItsEventOnceTheCommitHasHappened() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

            service.authorize(requestWithCardNumber(CARD_NUMBER), CALLER);

            assertEquals(1.0d,
                    meters.counter(AuthorizationService.DECISION_COUNTER, "outcome",
                            AuthorizationService.APPROVED_OUTCOME_TAG).count(),
                    "one committed approval");
            assertEquals(1.0d,
                    meters.counter(AuthorizationService.EVENT_COUNTER, "eventType",
                            TransactionAuthorized.EVENT_TYPE).count(),
                    "one written event");
            assertEquals(0.0d,
                    meters.counter(AuthorizationService.FAILURES_COUNTER, "stage",
                            AuthorizationService.PERSIST_STAGE).count(),
                    "and no fault");
        }

        @Test
        @DisplayName("times every call, whether it committed or not")
        void timesEveryCallWhetherItCommittedOrNot() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

            service.authorize(requestWithCardNumber(CARD_NUMBER), CALLER);
            assertThrows(org.springframework.dao.CannotAcquireLockException.class,
                    () -> serviceOverFailingCommit()
                            .authorize(requestWithCardNumber(CARD_NUMBER), CALLER));

            assertEquals(2L, meters.timer(AuthorizationService.DECISION_TIMER).count(),
                    "the timer covers the failed call as well, because a call that could not commit"
                            + " still took time and a slow failure is worth seeing");
        }
    }

    /**
     * The exposure an approval commits, and whether the next decision for the same account sees it.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl} posts each record before it validates the next: paragraph
     * {@code 2000-POST-TRANSACTION} at {@code :L424-L444} runs inside the sequential read loop and
     * paragraph {@code 2700-UPDATE-ACCOUNT} at {@code :L545-L560} has already moved both accumulators
     * by the time {@code :L403-L405} reads them again. Two transactions of 60.00 against a limit of
     * 100.00 therefore approve once and decline once, in that order.
     *
     * <p>Here the account service owns the accumulators and reports them back four asynchronous hops
     * after the decision, so without a reservation both calls approved and the limit was breached by
     * every call arriving inside that window. The store below carries the reservation forward between
     * the two calls exactly as a row would, so these tests run the real service, the real rule chain
     * and the real reservation.
     */
    @Nested
    @DisplayName("Cumulative exposure across two decisions")
    class CumulativeExposure {

        /** The reserved cycle credit the account row reports, moved by each reservation write. */
        private final java.util.concurrent.atomic.AtomicReference<BigDecimal> reservedCredit =
                new java.util.concurrent.atomic.AtomicReference<>(new BigDecimal("0.00"));

        /** The reserved cycle debit the account row reports, moved by each reservation write. */
        private final java.util.concurrent.atomic.AtomicReference<BigDecimal> reservedDebit =
                new java.util.concurrent.atomic.AtomicReference<>(new BigDecimal("0.00"));

        /** Stands the account row up so its reservation survives from one decision to the next. */
        @BeforeEach
        void carryTheReservationBetweenCalls() {
            resolveCard();
            AccountCreditSnapshotEntity row = mock(AccountCreditSnapshotEntity.class);
            when(row.getAccountId()).thenReturn(ACCOUNT_ID);
            when(row.getCreditLimit()).thenReturn(new BigDecimal("100.00"));
            when(row.getAccountExpirationDate()).thenReturn(EXPIRY_AFTER_CAPTURE);
            when(row.getCurrentCycleCredit()).thenReturn(new BigDecimal("0.00"));
            when(row.getCurrentCycleDebit()).thenReturn(new BigDecimal("0.00"));
            when(row.effectivePendingCycleCredit(any())).thenAnswer(call -> reservedCredit.get());
            when(row.effectivePendingCycleDebit(any())).thenAnswer(call -> reservedDebit.get());

            when(accountSnapshots.findForUpdateByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.of(row));
            when(accountSnapshots.reserveCycleExposure(any(), any(), any(), any()))
                    .thenAnswer(call -> {
                        reservedCredit.set(call.getArgument(1));
                        reservedDebit.set(call.getArgument(2));
                        return 1;
                    });
        }

        @Test
        @DisplayName("two calls of 60.00 against a limit of 100.00 approve once and decline once")
        void twoCallsAgainstOneLimitApproveOnceAndDeclineOnce() {
            AuthorizationService.Outcome first = service.authorize(request("60.00"), CALLER);
            AuthorizationService.Outcome second = service.authorize(request("60.00"), CALLER);

            assertTrue(first.approved(), "the first 60.00 fits a limit of 100.00");
            assertFalse(second.approved(),
                    "the second 60.00 must see the first, which is what app/cbl/CBTRN02C.cbl:L407 "
                            + "sees because :L545-L560 already rewrote the account record");
            assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), second.declineReason(),
                    "reject code 0102 at app/cbl/CBTRN02C.cbl:L403-L413 is the one that stands");
        }

        @Test
        @DisplayName("an approval reserves its amount against the cycle credit")
        void anApprovalReservesItsAmount() {
            service.authorize(request("60.00"), CALLER);

            assertEquals(new BigDecimal("60.00"), reservedCredit.get(),
                    "app/cbl/CBTRN02C.cbl:L549 adds an amount of zero or more to the cycle credit");
            assertEquals(new BigDecimal("0.00"), reservedDebit.get(),
                    "and leaves the debit accumulator alone");
        }

        @Test
        @DisplayName("a refund reserves against the cycle debit, keeping the source sign convention")
        void aRefundReservesAgainstTheCycleDebit() {
            service.authorize(request("-60.00"), CALLER);

            assertEquals(new BigDecimal("-60.00"), reservedDebit.get(),
                    "app/cbl/CBTRN02C.cbl:L551 adds a negative amount, and :L404 subtracts the "
                            + "accumulator, so a refund tightens the next authorization. Flagged in "
                            + "card-platform/docs/business-rule-flags.md and reproduced here");
        }

        @Test
        @DisplayName("a declined call reserves nothing")
        void aDeclinedCallReservesNothing() {
            AuthorizationService.Outcome declined = service.authorize(request("500.00"), CALLER);

            assertFalse(declined.approved(), "500.00 exceeds a limit of 100.00");
            assertEquals(new BigDecimal("0.00"), reservedCredit.get(),
                    "a decline commits no exposure, so it reserves none");
            verify(accountSnapshots, never())
                    .reserveCycleExposure(any(), any(), any(), any());
        }

        @Test
        @DisplayName("every decision bounds how long it waits for the account it locks")
        void everyDecisionBoundsItsLockWait() {
            service.authorize(request("60.00"), CALLER);

            verify(accountSnapshots).applyLockWaitBound(LOCK_WAIT_MS + "ms");
        }
    }

    /**
     * Which caller may authorize against the subject its request resolved to.
     *
     * <p>ADDITIVE. {@code app/cbl/CBTRN02C.cbl} authorizes a record the nightly feed supplied and has
     * no caller to entitle. The gap these tests close is that an ordinary credential could authorize
     * against any account in the platform: a request may name an account alone, and
     * {@code resolveCard} then reads that account's first card, so no card number had to be
     * known.
     *
     * <p>Where the refusal sits is as important as the refusal. It runs after the chain has resolved
     * the card and the account, because the account it compares is the one the cross-reference named,
     * and before the transaction identifier is allocated, so a refused call leaves nothing behind.
     */
    @Nested
    @DisplayName("Caller entitlement and what a refusal leaves behind")
    class CallerEntitlementInTheDecision {

        @Test
        @DisplayName("a caller owning neither subject is refused and leaves nothing behind")
        void aCallerOwningNeitherSubjectIsRefused() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

            assertThrows(CallerNotEntitledException.class,
                    () -> service.authorize(request("504.77"), ownerOfAnotherAccount()),
                    "an ordinary credential must not authorize against an account it does not own");

            assertEquals(List.of(), written, "a refused caller produces no event");
            assertEquals(List.of(), audited, "and no decision row");
            verify(identifiers, never()).nextIdentifier();
            verify(accountSnapshots, never())
                    .reserveCycleExposure(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a caller owning the resolved account authorizes it")
        void aCallerOwningTheResolvedAccountAuthorizesIt() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

            AuthorizationService.Outcome outcome =
                    service.authorize(request("504.77"), ownerOfTheResolvedAccount());

            assertTrue(outcome.approved(), "the caller owns the account the card resolved");
            assertEquals(1, written.size(), "one entitled call writes one event");
            assertEquals(ACTOR, audited.get(0).getActor(),
                    "and the decision row names the identity that asked");
        }

        @Test
        @DisplayName("a refusal counts the entitlement stage and no other")
        void aRefusalCountsTheEntitlementStage() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

            assertThrows(CallerNotEntitledException.class,
                    () -> service.authorize(request("504.77"), ownerOfAnotherAccount()));

            assertEquals(1.0d, meters.counter(AuthorizationService.FAILURES_COUNTER, "stage",
                            AuthorizationService.ENTITLEMENT_STAGE).count(),
                    "the refusal is counted where an operator can see it");
            assertEquals(0.0d, meters.counter(AuthorizationService.FAILURES_COUNTER, "stage",
                            AuthorizationService.PERSIST_STAGE).count(),
                    "and it is not reported as a datastore fault");
            assertEquals(0.0d, meters.counter(AuthorizationService.DECISION_COUNTER, "outcome",
                            AuthorizationService.APPROVED_OUTCOME_TAG).count(),
                    "nor as an outcome, because no decision was taken");
        }

        @Test
        @DisplayName("a card that resolved no account is refused too, so card existence stays hidden")
        void anUnresolvedCardIsRefusedTooForAnUnentitledCaller() {
            when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

            assertThrows(CallerNotEntitledException.class,
                    () -> service.authorize(request("504.77"), ownerOfAnotherAccount()),
                    "answering reject code 0100 here would confirm the card is unknown, and an "
                            + "approval would confirm it is known");

            assertEquals(List.of(), written, "and no decline event names a card the caller sent");
            verifyNoInteractions(unresolvedCardAttempts);
        }

        /**
         * Builds a caller entitled to the account this card resolves to.
         *
         * @return the caller
         */
        private RequestCaller ownerOfTheResolvedAccount() {
            return RequestCaller.of(ACTOR, List.of("ROLE_USER",
                    RequestCaller.ACCOUNT_SCOPE_PREFIX + ACCOUNT_ID));
        }

        /**
         * Builds a caller entitled to some other account, and to no card of this one.
         *
         * @return the caller
         */
        private RequestCaller ownerOfAnotherAccount() {
            return RequestCaller.of(ACTOR, List.of("ROLE_USER",
                    RequestCaller.ACCOUNT_SCOPE_PREFIX + "00000000008"));
        }
    }
}

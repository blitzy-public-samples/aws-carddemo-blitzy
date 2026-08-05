package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.carddemo.authorization.repository.UnresolvedCardAttemptRepository;
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

    /** The request identity every call below carries, at the width the audit column holds. */
    private static final String ACTOR = "user0001";

    /** The identifier the source allocates when the request supplies none. */
    private static final String ALLOCATED_ID = "0000001000000001";

    /**
     * A capture-moment window wide enough to hold the 2022 records of
     * {@code app/data/ASCII/dailytran.txt}.
     *
     * <p>The shipped window is a day wide, and every request below carries a fixture timestamp from
     * 2022, so the tests would otherwise measure the window rather than the rule chain. One dedicated
     * test narrows the window and asserts the refusal.
     */
    private static final OriginTimestampWindow FIXTURE_WINDOW =
            new OriginTimestampWindow(Duration.ofDays(36500).toMinutes(), 5);

    private CardCrossReferenceRepository cardCrossReferences;
    private AccountCreditSnapshotRepository accountSnapshots;
    private OutboxEventRepository outboxEvents;
    private UnresolvedCardAttemptRepository unresolvedCardAttempts;
    private AuthorizationDecisionRepository authorizationDecisions;
    private TransactionIdentifierSource identifiers;
    private List<AuthorizationDecisionEntity> audited;
    private List<OutboxEventEntity> written;
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

        List<DeclineRule> rules = List.of(new CardCrossReferenceRule(cardCrossReferences),
                new AccountExistsRule(accountSnapshots), new CreditLimitRule(),
                new AccountExpirationRule());

        meters = new SimpleMeterRegistry();
        service = new AuthorizationService(rules, cardCrossReferences, identifiers,
                new OutboxWriter(outboxEvents), unresolvedCardAttempts, authorizationDecisions,
                FIXTURE_WINDOW, meters, immediateTransactions(),
                properties(TOLERANT_STALENESS));
    }

    /**
     * A staleness ceiling wide enough that no fixture observation is ever too old.
     *
     * <p>The fixtures carry moments from 2022, so a production-shaped ceiling would refuse every
     * call and these tests would measure the freshness rule rather than the rule chain. One nested
     * class narrows the ceiling and asserts the refusal.
     */
    private static final Duration TOLERANT_STALENESS = Duration.ofDays(36_500L);

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
     * Supplies the typed replica policy the production constructor consumes.
     *
     * @param maxStaleness the ceiling {@code carddemo.replica.max-staleness} would carry
     * @return configuration holding that ceiling and nothing else
     */
    private static AuthorizationProperties properties(Duration maxStaleness) {
        AuthorizationProperties properties = mock(AuthorizationProperties.class);
        AuthorizationProperties.Replica replica = mock(AuthorizationProperties.Replica.class);
        when(replica.maxStaleness()).thenReturn(maxStaleness);
        when(properties.replica()).thenReturn(replica);
        return properties;
    }

    /** Asserts an approval names its account, allocates an identifier and writes one event. */
    @Test
    void anApprovalWritesOneAuthorizedEventAndNamesItsAccount() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), ACTOR);

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
                service.authorize(requestWithTransactionId("9999999999999999", "504.77"), ACTOR);

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
                service.authorize(requestWithTransactionId("9999999999999999", "504.77"), ACTOR);

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
                    build(CARD_NUMBER, null, "504.77", ORIGIN_TIMESTAMP, null, supplied), ACTOR);

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

        service.authorize(request("504.77"), ACTOR);

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

        service.authorize(request("504.77"), ACTOR);

        String payload = written.get(0).getPayload();
        assertTrue(payload.contains("************7065"),
                "the event carries the last four digits behind twelve mask characters");
        assertFalse(payload.contains(CARD_NUMBER),
                "no written event carries a Primary Account Number");
    }

    /**
     * Asserts an unresolved card declines with reject code {@code 0100}, records the attempt, and
     * publishes the one decline event that outcome produces.
     *
     * <p>Version one of the decline contract requires an eleven-digit account identifier, which this
     * outcome resolved none of. Version two, at {@code schemas/transaction-declined-v2.json}, keys the
     * event on the transaction identifier instead, so the attempt reaches a consumer rather than
     * ending in a table only an operator reads. One call still produces exactly one event.
     */
    @Test
    void anUnresolvedCardPublishesItsDeclineAndRecordsTheAttempt() {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), ACTOR);

        assertFalse(outcome.approved(), "no cross-reference row carries the card number");
        assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER), outcome.declineReason(),
                "app/cbl/CBTRN02C.cbl:L385 assigns this code for an invalid key");
        assertEquals("INVALID CARD NUMBER FOUND", describe(outcome),
                "the text comes from app/cbl/CBTRN02C.cbl:L386-L387");
        assertNull(outcome.accountId(), "no account resolved, so none is named");
        assertEquals(1, written.size(), "one call writes one event, this one included");
        assertEquals(TransactionDeclined.EVENT_TYPE, written.get(0).getEventType(),
                "an unresolved card writes the declined event");
        assertEquals(outcome.transactionId(), written.get(0).getAggregateId(),
                "version two keys the event on the transaction identifier, because no account "
                        + "identifier exists to key it on");
        assertTrue(written.get(0).getPayload().contains("\"schemaVersion\":2"),
                "the event names the contract version that declares no account identifier");
        assertFalse(written.get(0).getPayload().contains(CARD_NUMBER),
                "no written event carries a Primary Account Number");

        ArgumentCaptor<UnresolvedCardAttemptEntity> attempt =
                ArgumentCaptor.forClass(UnresolvedCardAttemptEntity.class);
        verify(unresolvedCardAttempts).save(attempt.capture());
        assertEquals("************7065", attempt.getValue().getMaskedCardNumber(),
                "the attempt records the masked card number and never the full one");
        assertEquals("0100", attempt.getValue().getDeclineReasonCode(),
                "the attempt records the reject code the source assigns");
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

        AuthorizationService.Outcome approval = service.authorize(request("504.77"), ACTOR);

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
        when(accountSnapshots.findByAccountId(any())).thenReturn(Optional.empty());
        service.authorize(request("504.77"), ACTOR);

        assertEquals(1, audited.size(), "a decline records one decision too");
        assertEquals(AuthorizationDecisionEntity.DECLINED_OUTCOME, audited.get(0).outcome(),
                "the row records the outcome");
        assertEquals(DeclineReason.ACCOUNT_NOT_FOUND.code(),
                audited.get(0).getDeclineReasonCode(), "a decline records its reject code");

        audited.clear();
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());
        service.authorize(request("504.77"), ACTOR);

        assertEquals(1, audited.size(), "an unresolved card records one decision too");
        assertNull(audited.get(0).getAccountId(),
                "no account resolved, so the row names none");
        assertEquals(DeclineReason.INVALID_CARD_NUMBER.code(),
                audited.get(0).getDeclineReasonCode(),
                "the row records the reject code app/cbl/CBTRN02C.cbl:L385 assigns");
    }

    /**
     * Asserts a backdated capture moment is refused before any identifier is allocated.
     *
     * <p>Reject reason {@code 0103} at {@code app/cbl/CBTRN02C.cbl:L414-L420} approves whenever the
     * account expiry is at or after the first ten characters of the capture moment, so a caller who
     * backdates that value authorizes against an account that expired years ago. The window refuses
     * the request instead, and reject reason {@code 0103} keeps its meaning.
     */
    @Test
    void aBackdatedCaptureMomentIsRefusedBeforeAnythingIsAllocated() {
        TransactionIdentifierSource identifiers = mock(TransactionIdentifierSource.class);
        when(identifiers.nextIdentifier()).thenReturn(ALLOCATED_ID);
        AuthorizationService narrowed = new AuthorizationService(
                List.of(new CardCrossReferenceRule(cardCrossReferences),
                        new AccountExistsRule(accountSnapshots), new CreditLimitRule(),
                        new AccountExpirationRule()),
                cardCrossReferences, identifiers, new OutboxWriter(outboxEvents),
                unresolvedCardAttempts, authorizationDecisions, new OriginTimestampWindow(1440, 5),
                new SimpleMeterRegistry(), immediateTransactions(),
                properties(TOLERANT_STALENESS));
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                "2021-01-31");

        assertThrows(IllegalArgumentException.class,
                () -> narrowed.authorize(requestWithTransactionId(null, "504.77",
                        "2020-01-01 00:00:00.000000"), ACTOR),
                "a capture moment six years old reached the expiry rule");

        assertEquals(List.of(), written, "a refused request writes no event");
        assertEquals(List.of(), audited, "a refused request records no decision");
    }

    /**
     * Asserts the chain stops at the cross-reference read, so a missing card never reaches the
     * account read.
     */
    @Test
    void aMissingCardNeverReachesTheAccountRead() {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        service.authorize(request("504.77"), ACTOR);

        verify(accountSnapshots, never()).findByAccountId(any());
    }

    /** Asserts a missing account declines with reject code {@code 0101} and writes one event. */
    @Test
    void aMissingAccountDeclinesWithItsOwnCode() {
        resolveCard();
        when(accountSnapshots.findByAccountId(any())).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), ACTOR);

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

        AuthorizationService.Outcome outcome = service.authorize(request("100.01"), ACTOR);

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

        AuthorizationService.Outcome outcome = service.authorize(request("100.00"), ACTOR);

        assertTrue(outcome.approved(),
                "app/cbl/CBTRN02C.cbl:L407 approves on greater-or-equal, not on greater");
    }

    /** Asserts a transaction after the account expiry date declines with reject code {@code 0103}. */
    @Test
    void aTransactionAfterTheExpiryDateDeclinesWithItsOwnCode() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_BEFORE_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), ACTOR);

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

        AuthorizationService.Outcome outcome = service.authorize(request("100.01"), ACTOR);

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

        AuthorizationService.Outcome outcome = service.authorize(request("60.00"), ACTOR);

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

        AuthorizationService.Outcome outcome = service.authorize(request("50.00"), ACTOR);

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
                requestWithTransactionId("0000000000683580", "504.77"), ACTOR);

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
        when(accountSnapshots.findByAccountId(fixtureAccountId)).thenReturn(
                Optional.of(new AccountCreditSnapshotEntity(fixtureAccountId,
                        new BigDecimal("2065.00"), "2024-12-13", new BigDecimal("0.00"),
                        new BigDecimal("0.00"), OBSERVED_AT)));

        AuthorizationService.Outcome outcome = service.authorize(
                requestWithTransactionId(null, "504.77", "2022-06-10 19:27:53.000000"), ACTOR);

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
                () -> service.authorize(requestWithCardNumber(shortCard), ACTOR));

        assertEquals(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE, refused.getMessage(),
                "the refusal uses the source-shaped missing-identifier text");
        verify(cardCrossReferences, never()).findByCardNumber(storedKey);
        verify(cardCrossReferences, never()).findByCardNumber(shortCard);
    }

    /**
     * Asserts a request naming only an account decides nothing and resolves no card.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L206-L209} reads the alternate index and moves the first card
     * number it finds into the field the validation then uses. That card is whichever one the index
     * holds first rather than one the caller presented, so reproducing the branch would let a caller
     * who guesses an account identifier authorize against another cardholder's card. The branch is
     * refused instead, and no read of the alternate index runs.
     */
    @Test
    void aRequestNamingOnlyAnAccountResolvesNoCardAndDecidesNothing() {
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.authorize(requestNamingOnlyAnAccount(), ACTOR),
                "an account identifier resolved a card, which is the path this fix closes");

        assertEquals(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE, refused.getMessage(),
                "the refusal carries the WHEN OTHER text of app/cbl/COTRN02C.cbl:L226");
        verify(cardCrossReferences, never()).findFirstByAccountIdOrderByCardNumberAsc(any());
        assertEquals(List.of(), written, "a refused request writes no event");
        assertEquals(List.of(), audited, "a refused request records no decision");
    }

    /**
     * Asserts a matching account identifier is accepted only as a check of the card lookup.
     */
    @Test
    void aMatchingAccountCrossCheckLeavesTheCardDecisionUnchanged() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(
                build(CARD_NUMBER, ACCOUNT_ID, "504.77", ORIGIN_TIMESTAMP, null), ACTOR);

        assertTrue(outcome.approved(), "the account cross-check agrees with the card lookup");
        assertEquals(new BigDecimal(ACCOUNT_ID), outcome.accountId(),
                "the outcome names the account the card cross-reference supplied");
        assertEquals(1, written.size(), "one matching request writes one event");
    }

    /**
     * Asserts a caller cannot pair a valid card with another account identifier.
     */
    @Test
    void aMismatchedAccountCrossCheckIsRefusedBeforeAnIdentifierOrEventIsAllocated() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.authorize(
                        build(CARD_NUMBER, "00000000008", "504.77", ORIGIN_TIMESTAMP, null),
                        ACTOR));

        assertEquals(AuthorizationService.ACCOUNT_CROSS_CHECK_MISMATCH_MESSAGE,
                refused.getMessage());
        verify(identifiers, never()).nextIdentifier();
        assertEquals(List.of(), written, "a mismatched cross-check writes no event");
        assertEquals(List.of(), audited, "a mismatched cross-check records no decision");
    }

    /**
     * Asserts an untrusted account value is never attached to a card that resolves no account.
     */
    @Test
    void anUnresolvedCardDoesNotAdoptTheCallerAccountCrossCheck() {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(
                build(CARD_NUMBER, "00000000008", "504.77", ORIGIN_TIMESTAMP, null), ACTOR);

        assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER), outcome.declineReason());
        assertNull(outcome.accountId(), "the caller account is not authoritative");
        assertEquals(ALLOCATED_ID, written.get(0).getAggregateId(),
                "the unresolved event is keyed on the allocated transaction identifier");
        assertNull(audited.get(0).getAccountId(),
                "the audit row does not attribute the probe to the caller account");
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

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"), ACTOR);

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
                        new AccountExistsRule(accountSnapshots), new CreditLimitRule(),
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
                authorizationDecisions, FIXTURE_WINDOW, new SimpleMeterRegistry(),
                immediateTransactions(), properties(TOLERANT_STALENESS));

        AuthorizationService.Outcome outcome = extended.authorize(request("504.77"), ACTOR);

        assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome.declineReason(),
                "the added rule ran last and its answer stands");
    }

    /** Asserts a stopping rule ends the chain, so no later rule overwrites its answer. */
    @Test
    void aStoppingRuleEndsTheChainBeforeTheOverwritingSegmentRuns() {
        resolveCard();
        when(accountSnapshots.findByAccountId(any())).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(request("100.01"), ACTOR);

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
     * Reads the reject text the decision carries.
     *
     * @param outcome the decision under test
     * @return the reject text, or {@code null} on an approval
     */
    private static String describe(AuthorizationService.Outcome outcome) {
        return outcome.declineReason().map(DeclineReason::description).orElse(null);
    }

    /** Stubs the cross-reference read so the card resolves to {@link #ACCOUNT_ID}. */
    private void resolveCard() {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER))
                .thenReturn(Optional.of(
                        new CardCrossReferenceEntity(CARD_NUMBER, "000000011", ACCOUNT_ID,
                                OBSERVED_AT)));
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
        when(accountSnapshots.findByAccountId(ACCOUNT_ID))
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
     * Holds the freshness control that keeps this service from authorizing against a replica it cannot
     * vouch for the age of.
     *
     * <p>ADDITIVE. The source has no equivalent: {@code app/cbl/CBTRN02C.cbl:L382} and
     * {@code app/cbl/CBTRN02C.cbl:L395} read the cross-reference and account datasets themselves, so
     * nothing they read can be out of date. This service reads copies kept current by state-change
     * events, and a copy whose events stopped arriving keeps answering with whatever it last knew.
     */
    @Nested
    @DisplayName("Replica freshness")
    class ReplicaFreshness {

        /** A window far shorter than the age of the fixed observation instant. */
        private static final Duration STRICT = Duration.ofMinutes(5L);

        /** Builds the service under a window that refuses the fixture observation. */
        private AuthorizationService strictService() {
            List<DeclineRule> rules = List.of(new CardCrossReferenceRule(cardCrossReferences),
                    new AccountExistsRule(accountSnapshots), new CreditLimitRule(),
                    new AccountExpirationRule());
            TransactionIdentifierSource identifiers = mock(TransactionIdentifierSource.class);
            when(identifiers.nextIdentifier()).thenReturn(ALLOCATED_ID);

            return new AuthorizationService(rules, cardCrossReferences, identifiers,
                    new OutboxWriter(outboxEvents), unresolvedCardAttempts, authorizationDecisions,
                    FIXTURE_WINDOW, meters, immediateTransactions(), properties(STRICT));
        }

        @Test
        @DisplayName("refuses the call rather than declining it, and writes no event")
        void refusesTheCallRatherThanDecliningItAndWritesNoEvent() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

            assertThrows(AuthorizationService.StaleReplicaException.class,
                    () -> strictService().authorize(requestWithCardNumber(CARD_NUMBER), ACTOR));

            assertTrue(written.isEmpty(),
                    "a call this service could not answer must write no event, because an event is"
                            + " what makes a decision real to every consumer");
        }

        /**
         * The reject-reason enumeration is closed at the four of
         * {@code app/cbl/CBTRN02C.cbl:L385-L420}, and the published contract enumerates those four. A
         * refusal reported as a decline would have to invent a fifth, and it would also be untrue: the
         * card and the account were both valid.
         */
        @Test
        @DisplayName("counts an infrastructure fault and no decline")
        void countsAnInfrastructureFaultAndNoDecline() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

            assertThrows(AuthorizationService.StaleReplicaException.class,
                    () -> strictService().authorize(requestWithCardNumber(CARD_NUMBER), ACTOR));

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
         * {@code app/cbl/CBTRN02C.cbl:L385-L387}, and it read no replica row worth checking. Holding it
         * to a freshness window would refuse a call that needs no replica to answer.
         */
        @Test
        @DisplayName("does not apply to a call whose card resolves to no account")
        void doesNotApplyToACallWhoseCardResolvesToNoAccount() {
            when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

            AuthorizationService.Outcome outcome =
                    strictService().authorize(requestWithCardNumber(CARD_NUMBER), ACTOR);

            assertFalse(outcome.approved(), "an unresolved card is declined");
            assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER), outcome.declineReason(),
                    "and it carries reason 0100, not a refusal");
        }

        /**
         * An absent account row is reject reason {@code 0101} at
         * {@code app/cbl/CBTRN02C.cbl:L397-L399}, and that decline comes from the absence rather than
         * from any value, so there is no observation to be too old. Refusing the call instead would
         * replace a reject reason the source defines with a service fault.
         */
        @Test
        @DisplayName("does not apply to an absent account row, which keeps reason 0101")
        void doesNotApplyToAnAbsentAccountRowWhichKeepsItsOwnReason() {
            resolveCard();
            when(accountSnapshots.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

            AuthorizationService.Outcome outcome =
                    strictService().authorize(requestWithCardNumber(CARD_NUMBER), ACTOR);

            assertEquals(Optional.of(DeclineReason.ACCOUNT_NOT_FOUND), outcome.declineReason(),
                    "an absent account row carries reason 0101, not a refusal");
            assertEquals(0.0d,
                    meters.counter(AuthorizationService.FAILURES_COUNTER, "stage",
                            AuthorizationService.REPLICA_STAGE).count(),
                    "and it is not counted as an infrastructure fault");
        }

        @Test
        @DisplayName("is a positive window, because a window of zero would refuse everything")
        void isAPositiveWindowBecauseZeroWouldRefuseEverything() {
            TransactionIdentifierSource identifiers = mock(TransactionIdentifierSource.class);

            assertThrows(IllegalArgumentException.class,
                    () -> new AuthorizationService(List.of(), cardCrossReferences, identifiers,
                            new OutboxWriter(outboxEvents), unresolvedCardAttempts,
                            authorizationDecisions, FIXTURE_WINDOW, meters,
                            immediateTransactions(), properties(Duration.ZERO)));
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
                    new AccountExistsRule(accountSnapshots), new CreditLimitRule(),
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
                    FIXTURE_WINDOW, meters, failing, properties(TOLERANT_STALENESS));
        }

        @Test
        @DisplayName("counts no approval when the commit fails, and counts the persist fault")
        void countsNoApprovalWhenTheCommitFailsAndCountsThePersistFault() {
            resolveCard();
            resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), EXPIRY_AFTER_CAPTURE);

            assertThrows(org.springframework.dao.CannotAcquireLockException.class,
                    () -> serviceOverFailingCommit()
                            .authorize(requestWithCardNumber(CARD_NUMBER), ACTOR));

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

            service.authorize(requestWithCardNumber(CARD_NUMBER), ACTOR);

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

            service.authorize(requestWithCardNumber(CARD_NUMBER), ACTOR);
            assertThrows(org.springframework.dao.CannotAcquireLockException.class,
                    () -> serviceOverFailingCommit()
                            .authorize(requestWithCardNumber(CARD_NUMBER), ACTOR));

            assertEquals(2L, meters.timer(AuthorizationService.DECISION_TIMER).count(),
                    "the timer covers the failed call as well, because a call that could not commit"
                            + " still took time and a slow failure is worth seeing");
        }
    }
}

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
import com.carddemo.authorization.domain.rules.AccountExistsRule;
import com.carddemo.authorization.domain.rules.AccountExpirationRule;
import com.carddemo.authorization.domain.rules.CardCrossReferenceRule;
import com.carddemo.authorization.domain.rules.CreditLimitRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.entity.UnresolvedCardAttemptEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.authorization.repository.UnresolvedCardAttemptRepository;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    /** The account the card resolves to, eleven digits with a leading zero. */
    private static final String ACCOUNT_ID = "00000000077";

    /** The capture timestamp every request below carries. */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.412000";

    /** An expiry date after the capture date, so the expiry rule accepts. */
    private static final String EXPIRY_AFTER_CAPTURE = "2099-12-31";

    /** An expiry date before the capture date, so the expiry rule declines. */
    private static final String EXPIRY_BEFORE_CAPTURE = "2020-01-31";

    /** The identifier the source allocates when the request supplies none. */
    private static final String ALLOCATED_ID = "0000001000000001";

    private CardCrossReferenceRepository cardCrossReferences;
    private AccountCreditSnapshotRepository accountSnapshots;
    private OutboxEventRepository outboxEvents;
    private UnresolvedCardAttemptRepository unresolvedCardAttempts;
    private List<OutboxEventEntity> written;
    private AuthorizationService service;

    /** Builds the service over stubbed repositories before each test. */
    @BeforeEach
    void buildService() {
        cardCrossReferences = mock(CardCrossReferenceRepository.class);
        accountSnapshots = mock(AccountCreditSnapshotRepository.class);
        outboxEvents = mock(OutboxEventRepository.class);
        unresolvedCardAttempts = mock(UnresolvedCardAttemptRepository.class);
        written = new ArrayList<>();

        when(outboxEvents.save(any(OutboxEventEntity.class))).thenAnswer(call -> {
            OutboxEventEntity row = call.getArgument(0);
            written.add(row);
            return row;
        });

        TransactionIdentifierSource identifiers = mock(TransactionIdentifierSource.class);
        when(identifiers.nextIdentifier()).thenReturn(ALLOCATED_ID);

        List<DeclineRule> rules = List.of(new CardCrossReferenceRule(cardCrossReferences),
                new AccountExistsRule(accountSnapshots), new CreditLimitRule(),
                new AccountExpirationRule());

        service = new AuthorizationService(rules, cardCrossReferences, identifiers,
                new OutboxWriter(outboxEvents), unresolvedCardAttempts, new SimpleMeterRegistry());
    }

    /** Asserts an approval names its account, allocates an identifier and writes one event. */
    @Test
    void anApprovalWritesOneAuthorizedEventAndNamesItsAccount() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"));

        assertTrue(outcome.approved(), "the transaction fits the credit limit and the expiry date");
        assertEquals(new BigDecimal(ACCOUNT_ID), outcome.accountId(), "the approval names the resolved account");
        assertEquals(ALLOCATED_ID, outcome.transactionId(), "the service allocated an identifier");
        assertEquals(1, written.size(), "one call writes one event");
        assertEquals(TransactionAuthorized.EVENT_TYPE, written.get(0).getEventType(),
                "an approval writes the authorized event");
        assertEquals(ACCOUNT_ID, written.get(0).getAggregateId(),
                "the event is keyed on the account, so one account's events stay in order");
    }

    /** Asserts the written approval carries the masked card number and never the full one. */
    @Test
    void theWrittenApprovalCarriesTheMaskedCardNumberAndNeverTheFullOne() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        service.authorize(request("504.77"));

        String payload = written.get(0).getPayload();
        assertTrue(payload.contains("************7065"),
                "the event carries the last four digits behind twelve mask characters");
        assertFalse(payload.contains(CARD_NUMBER),
                "no written event carries a Primary Account Number");
    }

    /**
     * Asserts an unresolved card declines with reject code {@code 0100}, writes no event and records
     * the attempt.
     *
     * <p>Every event contract requires an eleven-digit account identifier, and this outcome resolved
     * none, so publishing anything would name an account the read did not find.
     */
    @Test
    void anUnresolvedCardWritesNoEventAndRecordsTheAttempt() {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"));

        assertFalse(outcome.approved(), "no cross-reference row carries the card number");
        assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER), outcome.declineReason(),
                "app/cbl/CBTRN02C.cbl:L385 assigns this code for an invalid key");
        assertEquals("INVALID CARD NUMBER FOUND", describe(outcome),
                "the text comes from app/cbl/CBTRN02C.cbl:L386-L387");
        assertNull(outcome.accountId(), "no account resolved, so none is named");
        assertEquals(List.of(), written, "this outcome writes no event");

        ArgumentCaptor<UnresolvedCardAttemptEntity> attempt =
                ArgumentCaptor.forClass(UnresolvedCardAttemptEntity.class);
        verify(unresolvedCardAttempts).save(attempt.capture());
        assertEquals("************7065", attempt.getValue().getMaskedCardNumber(),
                "the attempt records the masked card number and never the full one");
        assertEquals("0100", attempt.getValue().getDeclineReasonCode(),
                "the attempt records the reject code the source assigns");
    }

    /**
     * Asserts the chain stops at the cross-reference read, so a missing card never reaches the
     * account read.
     */
    @Test
    void aMissingCardNeverReachesTheAccountRead() {
        when(cardCrossReferences.findByCardNumber(CARD_NUMBER)).thenReturn(Optional.empty());

        service.authorize(request("504.77"));

        verify(accountSnapshots, never()).findByAccountId(any());
    }

    /** Asserts a missing account declines with reject code {@code 0101} and writes one event. */
    @Test
    void aMissingAccountDeclinesWithItsOwnCode() {
        resolveCard();
        when(accountSnapshots.findByAccountId(any())).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"));

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

        AuthorizationService.Outcome outcome = service.authorize(request("100.01"));

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

        AuthorizationService.Outcome outcome = service.authorize(request("100.00"));

        assertTrue(outcome.approved(),
                "app/cbl/CBTRN02C.cbl:L407 approves on greater-or-equal, not on greater");
    }

    /** Asserts a transaction after the account expiry date declines with reject code {@code 0103}. */
    @Test
    void aTransactionAfterTheExpiryDateDeclinesWithItsOwnCode() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_BEFORE_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"));

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

        AuthorizationService.Outcome outcome = service.authorize(request("100.01"));

        assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                "the later assignment wins, and the earlier one does not survive");
        assertEquals(1, written.size(), "one call still writes one event");
    }

    /**
     * Asserts a refund raises the tested value, tightening the next authorization.
     *
     * <p>A negative amount reaches the cycle-debit accumulator at
     * {@code app/cbl/CBTRN02C.cbl:L551}, and the formula subtracts that accumulator. The behaviour is
     * reproduced deliberately and catalogued in
     * {@code card-platform/docs/business-rule-flags.md} (planned).
     */
    @Test
    void aNegativeCycleDebitRaisesTheTestedValue() {
        resolveCard();
        resolveAccount(new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("-50.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(request("60.00"));

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

        AuthorizationService.Outcome outcome = service.authorize(request("50.00"));

        assertTrue(outcome.approved(),
                "one billion and fifty stores as fifty in a nine-digit field, so the limit of one "
                        + "hundred accepts it");
    }

    /** Asserts a request that supplies its own identifier keeps it. */
    @Test
    void aRequestSupplyingItsOwnIdentifierKeepsIt() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(
                requestWithTransactionId("0000000000683580", "504.77"));

        assertEquals("0000000000683580", outcome.transactionId(),
                "the supplied identifier reaches the decision unchanged");
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
                requestWithTransactionId(null, "504.77", "2022-06-10 19:27:53.000000"));

        assertTrue(outcome.approved(), "504.77 fits a limit of 2065.00 and an expiry of 2024-12-13");
        assertEquals(new BigDecimal(fixtureAccountId), outcome.accountId(),
                "the card resolves to account seven, not to account one");
        assertEquals(Optional.empty(), outcome.declineReason(), "no rule declined");
        assertEquals(1, written.size(), "one call writes one event");
    }

    /**
     * Asserts a card number narrower than the cross-reference key is widened before the read.
     *
     * <p>The key is {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5} and the read
     * compares text, so a fifteen-digit value would miss and report a reject reason the card never
     * earned.
     */
    @Test
    void aCardNarrowerThanTheKeyIsWidenedBeforeTheRead() {
        String storedKey = "0859452612877065";
        when(cardCrossReferences.findByCardNumber(storedKey)).thenReturn(Optional.of(
                new CardCrossReferenceEntity(storedKey, "000000011", ACCOUNT_ID, OBSERVED_AT)));
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome =
                service.authorize(requestWithCardNumber(storedKey.substring(1)));

        assertTrue(outcome.approved(), "the widened value matches the sixteen-character key");
        verify(cardCrossReferences).findByCardNumber(storedKey);
        verify(cardCrossReferences, never()).findByCardNumber(storedKey.substring(1));
    }

    /**
     * Asserts a request naming only an account resolves its card through the cross-reference.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L206-L209} reads the alternate index and moves the card number
     * it finds back into the field the validation then uses.
     */
    @Test
    void aRequestNamingOnlyAnAccountResolvesItsCardThroughTheCrossReference() {
        when(cardCrossReferences.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(new CardCrossReferenceEntity(CARD_NUMBER, "000000011",
                        ACCOUNT_ID, OBSERVED_AT)));
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationService.Outcome outcome = service.authorize(requestNamingOnlyAnAccount());

        assertTrue(outcome.approved(), "the resolved card reached the chain");
        assertEquals(new BigDecimal(ACCOUNT_ID), outcome.accountId(),
                "the decision names the account the request supplied");
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

        AuthorizationService.Outcome outcome = service.authorize(request("504.77"));

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
                new SimpleMeterRegistry());

        AuthorizationService.Outcome outcome = extended.authorize(request("504.77"));

        assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome.declineReason(),
                "the added rule ran last and its answer stands");
    }

    /** Asserts a stopping rule ends the chain, so no later rule overwrites its answer. */
    @Test
    void aStoppingRuleEndsTheChainBeforeTheOverwritingSegmentRuns() {
        resolveCard();
        when(accountSnapshots.findByAccountId(any())).thenReturn(Optional.empty());

        AuthorizationService.Outcome outcome = service.authorize(request("100.01"));

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
        when(accountSnapshots.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(new AccountCreditSnapshotEntity(ACCOUNT_ID,
                        creditLimit, expiryDate, cycleCredit, cycleDebit, OBSERVED_AT)));
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
        return new AuthorizationRequest(transactionId, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", amount, "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", cardNumber, originTimestamp,
                "2022-06-10-19.27.53.410000", accountId);
    }
}

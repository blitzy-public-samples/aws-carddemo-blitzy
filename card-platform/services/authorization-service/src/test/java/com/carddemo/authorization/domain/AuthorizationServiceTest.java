package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.api.AuthorizationResponse;
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
     * replica can be reported rather than authorized against.
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

        service = new AuthorizationService(rules, identifiers, new OutboxWriter(outboxEvents),
                unresolvedCardAttempts, new SimpleMeterRegistry());
    }

    /** Asserts an approval names its account, allocates an identifier and writes one event. */
    @Test
    void anApprovalWritesOneAuthorizedEventAndNamesItsAccount() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationResponse response = service.authorize(request("504.77"));

        assertTrue(response.approved(), "the transaction fits the credit limit and the expiry date");
        assertEquals(ACCOUNT_ID, response.accountId(), "the approval names the resolved account");
        assertEquals(ALLOCATED_ID, response.transactionId(), "the service allocated an identifier");
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

        AuthorizationResponse response = service.authorize(request("504.77"));

        assertFalse(response.approved(), "no cross-reference row carries the card number");
        assertEquals(DeclineReason.INVALID_CARD_NUMBER, response.declineReasonCode(),
                "app/cbl/CBTRN02C.cbl:L385 assigns this code for an invalid key");
        assertEquals("INVALID CARD NUMBER FOUND", response.declineReasonDescription(),
                "the text comes from app/cbl/CBTRN02C.cbl:L386-L387");
        assertNull(response.accountId(), "no account resolved, so none is named");
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

        AuthorizationResponse response = service.authorize(request("504.77"));

        assertEquals(DeclineReason.ACCOUNT_NOT_FOUND, response.declineReasonCode(),
                "app/cbl/CBTRN02C.cbl:L397 assigns this code");
        assertEquals("ACCOUNT RECORD NOT FOUND", response.declineReasonDescription(),
                "the text comes from app/cbl/CBTRN02C.cbl:L398-L399");
        assertEquals(ACCOUNT_ID, response.accountId(),
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

        AuthorizationResponse response = service.authorize(request("100.01"));

        assertEquals(DeclineReason.OVER_CREDIT_LIMIT, response.declineReasonCode(),
                "app/cbl/CBTRN02C.cbl:L410 assigns this code");
        assertEquals("OVERLIMIT TRANSACTION", response.declineReasonDescription(),
                "the text comes from app/cbl/CBTRN02C.cbl:L411-L412");
    }

    /** Asserts a transaction exactly at the credit limit is approved, because the test is not strict. */
    @Test
    void aTransactionExactlyAtTheCreditLimitIsApproved() {
        resolveCard();
        resolveAccount(new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationResponse response = service.authorize(request("100.00"));

        assertTrue(response.approved(),
                "app/cbl/CBTRN02C.cbl:L407 approves on greater-or-equal, not on greater");
    }

    /** Asserts a transaction after the account expiry date declines with reject code {@code 0103}. */
    @Test
    void aTransactionAfterTheExpiryDateDeclinesWithItsOwnCode() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_BEFORE_CAPTURE);

        AuthorizationResponse response = service.authorize(request("504.77"));

        assertEquals(DeclineReason.ACCOUNT_EXPIRED, response.declineReasonCode(),
                "app/cbl/CBTRN02C.cbl:L417 assigns this code");
        assertEquals("TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
                response.declineReasonDescription(),
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

        AuthorizationResponse response = service.authorize(request("100.01"));

        assertEquals(DeclineReason.ACCOUNT_EXPIRED, response.declineReasonCode(),
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

        AuthorizationResponse response = service.authorize(request("60.00"));

        assertEquals(DeclineReason.OVER_CREDIT_LIMIT, response.declineReasonCode(),
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

        AuthorizationResponse response = service.authorize(request("50.00"));

        assertTrue(response.approved(),
                "one billion and fifty stores as fifty in a nine-digit field, so the limit of one "
                        + "hundred accepts it");
    }

    /** Asserts a request that supplies its own identifier keeps it. */
    @Test
    void aRequestSupplyingItsOwnIdentifierKeepsIt() {
        resolveCard();
        resolveAccount(new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                EXPIRY_AFTER_CAPTURE);

        AuthorizationResponse response = service.authorize(
                requestWithTransactionId("0000000000683580", "504.77"));

        assertEquals("0000000000683580", response.transactionId(),
                "the supplied identifier reaches the decision unchanged");
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
        return new AuthorizationRequest(transactionId, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", amount, "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", CARD_NUMBER, ORIGIN_TIMESTAMP,
                "2022-06-10-19.27.53.410000", null);
    }
}

package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.authorization.domain.AuthorizationService.Outcome;
import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.domain.OriginTimestampWindow;
import com.carddemo.authorization.domain.TransactionIdentifierSource;
import com.carddemo.authorization.domain.rules.AccountExistsRule;
import com.carddemo.authorization.domain.rules.AccountExpirationRule;
import com.carddemo.authorization.domain.rules.CardCrossReferenceRule;
import com.carddemo.authorization.domain.rules.CreditLimitRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.entity.UnresolvedCardAttemptEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.authorization.repository.AuthorizationDecisionRepository;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.UnresolvedCardAttemptRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.equivalence.CopybookRecordParser.AccountRecord;
import com.carddemo.equivalence.CopybookRecordParser.CardCrossReferenceRecord;
import com.carddemo.equivalence.CopybookRecordParser.DailyTransactionRecord;
import com.carddemo.events.DeclineReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compares the authorization service with {@code CBTRN02C} paragraphs 1500-A and 1500-B.
 * {@code COPAUA0C} and transaction {@code CP00} are absent from the source repository.
 * The request shape comes from {@code COTRN02C}; signon provenance comes from {@code COSGN00C}.
 * The fixture comparison uses stateless account snapshots and runs only under {@code mvn verify}.
 */
@DisplayName("Authorization decisions from CBTRN02C L370-L422")
class AuthorizationDecisionEquivalenceTest {

    private static final Instant OBSERVED_AT = Instant.EPOCH;
    private static final String SYNTHETIC_TRANSACTION_ID = "SYNTH00000000001";
    private static final String SYNTHETIC_CUSTOMER_ID = "000000001";
    private static final String SYNTHETIC_CARD_NUMBER = "0000000000000001";
    private static final String SYNTHETIC_ACCOUNT_ID = "00000000001";
    private static final String ACTOR = "equiv001";
    private static final String ABSENT_ACCOUNT_ID = "99999999999";
    private static final String ABSENT_CARD_NUMBER = "9999999999999999";
    private static final String PROCESSING_TIMESTAMP = "2022-06-10-19.27.53.000000";
    private static final String SOURCE_TIMESTAMP = "2022-06-10 19:27:53.000000";
    private static final String LATER_TIMESTAMP = "2025-01-01 00:00:00.000000";
    private static final String EQUAL_DATE = "2024-01-01";
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");
    private static final BigDecimal EQUALITY_LIMIT = new BigDecimal("100.00");
    private static final BigDecimal EQUALITY_CYCLE_CREDIT = new BigDecimal("50.00");
    private static final BigDecimal EQUALITY_AMOUNT = new BigDecimal("50.00");
    private static final BigDecimal PRECISION_CYCLE_CREDIT =
            new BigDecimal("1000000100.00");
    private static final BigDecimal PRECISION_LIMIT = new BigDecimal("500.00");
    private static final BigDecimal ONE_BILLION = BigDecimal.TEN.pow(
            PicClause.WS_TEMP_BAL_PRECISION - PicClause.WS_TEMP_BAL_SCALE);
    private static final Duration TOLERANT_STALENESS = Duration.ofDays(36_500L);
    private static final OriginTimestampWindow FIXTURE_WINDOW =
            new OriginTimestampWindow(TOLERANT_STALENESS.toMinutes(), 5L);

    private static final List<DailyTransactionRecord> FEED =
            CardDemoFixtureLoader.loadDailyTransactions();
    private static final Map<String, CardCrossReferenceRecord> FIXTURE_XREFS =
            CardDemoFixtureLoader.cardCrossReferencesByCardNumber();
    private static final Map<String, AccountRecord> FIXTURE_ACCOUNTS =
            CardDemoFixtureLoader.accountsByAccountId();

    @Nested
    @DisplayName("The stateless fixture model")
    class FixtureModel {

        @Test
        void everyFixtureDecisionMatchesTheDocumentedRule() {
            DecisionHarness harness = DecisionHarness.fromFixtures();
            List<DeclineReason> expected = new ArrayList<>(FEED.size());
            List<Outcome> actual = new ArrayList<>(FEED.size());

            for (DailyTransactionRecord record : FEED) {
                expected.add(documentedReason(record, FIXTURE_XREFS, FIXTURE_ACCOUNTS));
                actual.add(harness.authorize(requestFor(record, record.cardNumber())));
            }

            for (int index = 0; index < FEED.size(); index++) {
                assertEquals(Optional.ofNullable(expected.get(index)),
                        actual.get(index).declineReason(),
                        "fixture record " + (index + 1)
                                + " must match CBTRN02C L370-L422");
            }

            long expectedDeclines = expected.stream().filter(reason -> reason != null).count();
            long actualDeclines = actual.stream().filter(outcome -> !outcome.approved()).count();
            long expectedApprovals = FEED.size() - expectedDeclines;
            long actualApprovals = actual.stream().filter(Outcome::approved).count();

            assertEquals(expectedDeclines, actualDeclines,
                    "the decline count is derived from all fixture records under Model A");
            assertEquals(expectedApprovals, actualApprovals,
                    "every fixture record is either approved or declined once");
            assertEquals(Set.of(DeclineReason.OVER_CREDIT_LIMIT),
                    actual.stream().flatMap(outcome -> outcome.declineReason().stream())
                            .collect(java.util.stream.Collectors.toSet()),
                    "only reason 0102 is reachable from the checked-in fixtures");
        }

        @Test
        void theFirstFixtureRecordResolvesToAccountSevenAndAuthorizes() {
            DailyTransactionRecord first = FEED.get(0);
            CardCrossReferenceRecord crossReference = FIXTURE_XREFS.get(first.cardNumber());
            AccountRecord account = FIXTURE_ACCOUNTS.get(crossReference.accountId());

            assertEquals(new BigDecimal("504.77"), first.amount(),
                    "dailytran record 1 carries +504.77 at CVTRA06Y L10");
            assertEquals("00000000007", crossReference.accountId(),
                    "record 1 resolves through CVACT03Y L7");
            assertEquals(new BigDecimal("2065.00"), account.creditLimit(),
                    "account 7 carries the credit limit used at CBTRN02C L407");
            assertEquals("2024-12-13", account.expirationDate(),
                    "account 7 carries the expiry text used at CBTRN02C L414");

            Outcome outcome = DecisionHarness.fromFixtures()
                    .authorize(requestFor(first, first.cardNumber()));

            assertTrue(outcome.approved(), "record 1 passes all four source rules");
            assertEquals(new BigDecimal("7"), outcome.accountId(),
                    "the outcome names account 7 at scale zero");
        }

        @Test
        @DisplayName("ADDITIVE: a short card input is refused before the XREF lookup")
        void aShortCardInputIsRefusedBeforeTheStringLookup() {
            Map<String, CardCrossReferenceEntity> xrefs = new LinkedHashMap<>();
            xrefs.put(SYNTHETIC_CARD_NUMBER,
                    crossReference(SYNTHETIC_CARD_NUMBER, SYNTHETIC_ACCOUNT_ID));
            Map<String, AccountCreditSnapshotEntity> accounts = new LinkedHashMap<>();
            accounts.put(SYNTHETIC_ACCOUNT_ID,
                    account(SYNTHETIC_ACCOUNT_ID, EQUALITY_LIMIT, EQUAL_DATE,
                            ZERO_MONEY, ZERO_MONEY));
            DecisionHarness harness = new DecisionHarness(xrefs, accounts);

            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> harness.authorize(
                            request("1", ZERO_MONEY, EQUAL_DATE + " 00:00:00.000000")));

            assertEquals(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE, refusal.getMessage(),
                    "the synchronous surface refuses an identifier narrower than the storage key");
            verify(harness.cardCrossReferences(), never()).findByCardNumber(anyString());
        }

        @Test
        void theSnapshotCarriesNoCurrentBalanceOrStatusRuleInput() {
            Set<String> fields = java.util.Arrays.stream(
                            AccountCreditSnapshotEntity.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName)
                    .collect(java.util.stream.Collectors.toSet());

            assertFalse(fields.contains("currentBalance"),
                    "CBTRN02C L403-L405 does not read the current balance");
            assertFalse(fields.contains("activeStatus"),
                    "CBTRN02C tests no account-status field before posting");
        }
    }

    @Nested
    @DisplayName("Reason 0100 — INVALID CARD NUMBER FOUND")
    class InvalidCardNumber {

        @Test
        void aMissingCrossReferenceDeclinesWithoutAnAccountRead() {
            DecisionHarness harness = new DecisionHarness(Map.of(), Map.of());

            Outcome outcome = harness.authorize(
                    request(ABSENT_CARD_NUMBER, ZERO_MONEY, SOURCE_TIMESTAMP));

            assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER),
                    outcome.declineReason(), "CBTRN02C L385-L387 assigns reason 0100");
            assertNull(outcome.accountId(), "reason 0100 is assigned before an account resolves");
            verifyNoInteractions(harness.accountCreditSnapshots());
        }
    }

    @Nested
    @DisplayName("Reason 0101 — ACCOUNT RECORD NOT FOUND")
    class AccountNotFound {

        @Test
        void aMissingAccountDeclinesWithoutThrowing() {
            DecisionHarness harness = new DecisionHarness(
                    Map.of(SYNTHETIC_CARD_NUMBER,
                            crossReference(SYNTHETIC_CARD_NUMBER, ABSENT_ACCOUNT_ID)),
                    Map.of());

            Outcome outcome = harness.authorize(
                    request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY, SOURCE_TIMESTAMP));

            assertEquals(Optional.of(DeclineReason.ACCOUNT_NOT_FOUND),
                    outcome.declineReason(), "CBTRN02C L397-L399 assigns reason 0101");
            assertEquals(new BigDecimal(ABSENT_ACCOUNT_ID), outcome.accountId(),
                    "reason 0101 retains the resolved account identifier");
        }
    }

    @Nested
    @DisplayName("Reason 0102 — OVERLIMIT TRANSACTION")
    class OverCreditLimit {

        @Test
        void aFixtureRecordReachesTheOverlimitReason() {
            DailyTransactionRecord record = FEED.stream()
                    .filter(candidate -> documentedReason(candidate, FIXTURE_XREFS,
                            FIXTURE_ACCOUNTS) == DeclineReason.OVER_CREDIT_LIMIT)
                    .findFirst()
                    .orElseThrow();

            Outcome outcome = DecisionHarness.fromFixtures()
                    .authorize(requestFor(record, record.cardNumber()));

            assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT),
                    outcome.declineReason(), "CBTRN02C L410-L412 assigns reason 0102");
        }

        @Test
        @DisplayName("ADDITIVE: equality at ACCT-CREDIT-LIMIT >= WS-TEMP-BAL approves")
        void equalityAtTheCreditLimitApproves() {
            DecisionHarness harness = harnessWithAccount(EQUALITY_LIMIT, EQUAL_DATE,
                    EQUALITY_CYCLE_CREDIT, ZERO_MONEY);

            Outcome outcome = harness.authorize(
                    request(SYNTHETIC_CARD_NUMBER, EQUALITY_AMOUNT,
                            EQUAL_DATE + " 00:00:00.000000"));

            assertTrue(outcome.approved(),
                    "CBTRN02C L407 approves when the values are equal");
        }

        @Test
        @DisplayName("ADDITIVE: S9(09)V99 drops the high-order digit at the precision boundary")
        void theNarrowedWorkingFieldCanTurnADeclineIntoAnApproval() {
            BigDecimal raw = CobolDecimal.add(PRECISION_CYCLE_CREDIT, ZERO_MONEY,
                    PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal narrowed = CobolDecimal.truncateToPictureField(raw,
                    PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);
            DecisionHarness harness = harnessWithAccount(PRECISION_LIMIT, EQUAL_DATE,
                    PRECISION_CYCLE_CREDIT, ZERO_MONEY);

            Outcome outcome = harness.authorize(
                    request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY,
                            EQUAL_DATE + " 00:00:00.000000"));

            assertTrue(raw.abs().compareTo(ONE_BILLION) >= 0,
                    "the constructed value reaches the inaccessible precision boundary");
            assertTrue(raw.compareTo(PRECISION_LIMIT) > 0,
                    "the un-narrowed value exceeds the credit limit");
            assertTrue(narrowed.compareTo(PRECISION_LIMIT) <= 0,
                    "the S9(09)V99 value fits under the same limit");
            assertTrue(outcome.approved(),
                    "CreditLimitRule reproduces the narrowed source comparison");
        }
    }

    @Nested
    @DisplayName("Reason 0103 — TRANSACTION RECEIVED AFTER ACCT EXPIRATION")
    class AccountExpired {

        @Test
        void aLaterOriginDateDeclinesUnderTheTextComparison() {
            DecisionHarness harness = harnessWithAccount(EQUALITY_LIMIT, EQUAL_DATE,
                    ZERO_MONEY, ZERO_MONEY);

            Outcome outcome = harness.authorize(
                    request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY, LATER_TIMESTAMP));

            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED),
                    outcome.declineReason(), "CBTRN02C L417-L419 assigns reason 0103");
        }

        @Test
        @DisplayName("ADDITIVE: equal ten-character date text approves")
        void equalDateTextApproves() {
            DecisionHarness harness = harnessWithAccount(EQUALITY_LIMIT, EQUAL_DATE,
                    ZERO_MONEY, ZERO_MONEY);

            Outcome outcome = harness.authorize(
                    request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY,
                            EQUAL_DATE + " 23:59:59.999999"));

            assertTrue(outcome.approved(),
                    "CBTRN02C L414 approves equal ten-character text");
        }

        @Test
        void reason103OverwritesReason102WhenBothComparisonsFail() {
            DecisionHarness harness = harnessWithAccount(ZERO_MONEY, EQUAL_DATE,
                    EQUALITY_CYCLE_CREDIT, ZERO_MONEY);

            Outcome outcome = harness.authorize(
                    request(SYNTHETIC_CARD_NUMBER, EQUALITY_AMOUNT, LATER_TIMESTAMP));

            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED),
                    outcome.declineReason(),
                    "expected reason 0103 to overwrite reason 0102 per CBTRN02C L414-L417");
        }
    }

    @Nested
    @DisplayName("Wire reason contract")
    class WireContract {

        @Test
        void everyReasonUsesItsFourCharacterWireCodeAndVerbatimText() throws Exception {
            JsonMapper mapper = JsonMapper.builder().build();

            for (DeclineReason reason : DeclineReason.values()) {
                assertEquals(PicClause.VALIDATION_FAIL_REASON_WIDTH, reason.code().length(),
                        reason + " carries the PIC 9(04) width");
                assertEquals("\"" + reason.code() + "\"", mapper.writeValueAsString(reason),
                        reason + " serializes through its zero-padded code");
                assertSame(reason, DeclineReason.fromCode(reason.code()),
                        reason + " reads back from the same wire code");
                assertFalse(reason.description().isBlank(),
                        reason + " carries its source description");
                assertTrue(reason.description().length()
                                <= PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH,
                        reason + " fits the PIC X(76) trailer field");
            }
        }

        @Test
        void theFourRulesDeclareTheTwoSourceSegments() {
            DecisionHarness harness = DecisionHarness.fromFixtures();

            assertEquals(List.of(DeclineRule.Segment.STOP_ON_FIRST_DECLINE,
                            DeclineRule.Segment.STOP_ON_FIRST_DECLINE,
                            DeclineRule.Segment.LAST_DECLINE_WINS,
                            DeclineRule.Segment.LAST_DECLINE_WINS),
                    harness.rules().stream().map(DeclineRule::segment).toList(),
                    "reasons 0100/0101 stop and reasons 0102/0103 retain the last decline");
        }
    }

    private static DeclineReason documentedReason(DailyTransactionRecord record,
            Map<String, CardCrossReferenceRecord> crossReferences,
            Map<String, AccountRecord> accounts) {
        CardCrossReferenceRecord crossReference = crossReferences.get(record.cardNumber());
        if (crossReference == null) {
            return DeclineReason.INVALID_CARD_NUMBER;
        }
        AccountRecord account = accounts.get(crossReference.accountId());
        if (account == null) {
            return DeclineReason.ACCOUNT_NOT_FOUND;
        }

        BigDecimal cycleDifference = CobolDecimal.subtract(account.currentCycleCredit(),
                account.currentCycleDebit(), PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal computed = CobolDecimal.add(cycleDifference, record.amount(),
                PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal working = CobolDecimal.truncateToPictureField(computed,
                PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);

        DeclineReason standing = null;
        if (account.creditLimit().compareTo(working) < 0) {
            standing = DeclineReason.OVER_CREDIT_LIMIT;
        }
        String capturedDate = CopybookRecordParser.timestampDatePart(record.originTimestamp());
        if (account.expirationDate().compareTo(capturedDate) < 0) {
            standing = DeclineReason.ACCOUNT_EXPIRED;
        }
        return standing;
    }

    private static DecisionHarness harnessWithAccount(BigDecimal creditLimit, String expirationDate,
            BigDecimal cycleCredit, BigDecimal cycleDebit) {
        return new DecisionHarness(
                Map.of(SYNTHETIC_CARD_NUMBER,
                        crossReference(SYNTHETIC_CARD_NUMBER, SYNTHETIC_ACCOUNT_ID)),
                Map.of(SYNTHETIC_ACCOUNT_ID,
                        account(SYNTHETIC_ACCOUNT_ID, creditLimit, expirationDate,
                                cycleCredit, cycleDebit)));
    }

    private static AuthorizationRequest requestFor(DailyTransactionRecord record,
            String cardNumber) {
        return new AuthorizationRequest(record.transactionId(), record.typeCode(),
                record.categoryCode(), record.source(), record.description(),
                record.amount().toPlainString(), record.merchantId(), record.merchantName(),
                record.merchantCity(), record.merchantZip(), cardNumber,
                record.originTimestamp(), PROCESSING_TIMESTAMP, null);
    }

    private static AuthorizationRequest request(String cardNumber, BigDecimal amount,
            String originTimestamp) {
        return new AuthorizationRequest(SYNTHETIC_TRANSACTION_ID, "01", "0001", "POS TERM",
                "SYNTHETIC AUTHORIZATION", amount.toPlainString(), "000000001",
                "SYNTHETIC MERCHANT", "SYNTHETIC CITY", "00000", cardNumber,
                originTimestamp, PROCESSING_TIMESTAMP, null);
    }

    private static CardCrossReferenceEntity crossReference(String cardNumber, String accountId) {
        return new CardCrossReferenceEntity(cardNumber, SYNTHETIC_CUSTOMER_ID, accountId,
                OBSERVED_AT);
    }

    private static AccountCreditSnapshotEntity account(String accountId, BigDecimal creditLimit,
            String expirationDate, BigDecimal cycleCredit, BigDecimal cycleDebit) {
        return new AccountCreditSnapshotEntity(accountId, creditLimit, expirationDate,
                cycleCredit, cycleDebit, OBSERVED_AT);
    }

    private record DecisionHarness(
            AuthorizationService service,
            List<DeclineRule> rules,
            CardCrossReferenceRepository cardCrossReferences,
            AccountCreditSnapshotRepository accountCreditSnapshots) {

        private DecisionHarness(Map<String, CardCrossReferenceEntity> xrefs,
                Map<String, AccountCreditSnapshotEntity> accounts) {
            this(buildRulesAndService(xrefs, accounts));
        }

        private DecisionHarness(HarnessParts parts) {
            this(parts.service(), parts.rules(), parts.cardCrossReferences(),
                    parts.accountCreditSnapshots());
        }

        private static DecisionHarness fromFixtures() {
            Map<String, CardCrossReferenceEntity> xrefs = new LinkedHashMap<>();
            for (CardCrossReferenceRecord record : FIXTURE_XREFS.values()) {
                xrefs.put(record.cardNumber(),
                        new CardCrossReferenceEntity(record.cardNumber(), record.customerId(),
                                record.accountId(), OBSERVED_AT));
            }
            Map<String, AccountCreditSnapshotEntity> accounts = new LinkedHashMap<>();
            for (AccountRecord record : FIXTURE_ACCOUNTS.values()) {
                accounts.put(record.accountId(),
                        account(record.accountId(), record.creditLimit(),
                                record.expirationDate(), record.currentCycleCredit(),
                                record.currentCycleDebit()));
            }
            return new DecisionHarness(xrefs, accounts);
        }

        /** Runs one request with the bounded identity the decision audit row requires. */
        private Outcome authorize(AuthorizationRequest request) {
            return service.authorize(request, ACTOR);
        }

        private static HarnessParts buildRulesAndService(
                Map<String, CardCrossReferenceEntity> xrefs,
                Map<String, AccountCreditSnapshotEntity> accounts) {
            CardCrossReferenceRepository cardCrossReferences =
                    mock(CardCrossReferenceRepository.class);
            when(cardCrossReferences.findByCardNumber(anyString()))
                    .thenAnswer(invocation ->
                            Optional.ofNullable(xrefs.get(invocation.getArgument(0))));

            AccountCreditSnapshotRepository accountCreditSnapshots =
                    mock(AccountCreditSnapshotRepository.class);
            when(accountCreditSnapshots.findByAccountId(anyString()))
                    .thenAnswer(invocation ->
                            Optional.ofNullable(accounts.get(invocation.getArgument(0))));

            List<DeclineRule> rules = List.of(
                    new CardCrossReferenceRule(cardCrossReferences),
                    new AccountExistsRule(accountCreditSnapshots),
                    new CreditLimitRule(),
                    new AccountExpirationRule());

            TransactionIdentifierSource identifiers = mock(TransactionIdentifierSource.class);
            when(identifiers.nextIdentifier()).thenReturn(SYNTHETIC_TRANSACTION_ID);
            OutboxWriter outboxWriter = mock(OutboxWriter.class);
            UnresolvedCardAttemptRepository unresolved =
                    mock(UnresolvedCardAttemptRepository.class);
            when(unresolved.save(org.mockito.ArgumentMatchers.any(
                    UnresolvedCardAttemptEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            AuthorizationDecisionRepository decisions =
                    mock(AuthorizationDecisionRepository.class);
            when(decisions.save(org.mockito.ArgumentMatchers.any(
                    AuthorizationDecisionEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            AuthorizationService service = new AuthorizationService(rules, cardCrossReferences,
                    identifiers, outboxWriter, unresolved, decisions, FIXTURE_WINDOW,
                    new SimpleMeterRegistry(), immediateTransactions(),
                    properties(TOLERANT_STALENESS));
            return new HarnessParts(service, rules, cardCrossReferences,
                    accountCreditSnapshots);
        }
    }

    /** Runs the service transaction callback on the calling thread. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    /** Supplies only the replica freshness policy the service constructor reads. */
    private static AuthorizationProperties properties(Duration maxStaleness) {
        AuthorizationProperties properties = mock(AuthorizationProperties.class);
        AuthorizationProperties.Replica replica = mock(AuthorizationProperties.Replica.class);
        when(replica.maxStaleness()).thenReturn(maxStaleness);
        when(properties.replica()).thenReturn(replica);
        return properties;
    }

    private record HarnessParts(
            AuthorizationService service,
            List<DeclineRule> rules,
            CardCrossReferenceRepository cardCrossReferences,
            AccountCreditSnapshotRepository accountCreditSnapshots) {
    }
}
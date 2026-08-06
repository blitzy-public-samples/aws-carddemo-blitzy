package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.authorization.repository.AuthorizationDecisionRepository;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.authorization.repository.UnresolvedCardAttemptRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.equivalence.CopybookRecordParser.AccountRecord;
import com.carddemo.equivalence.CopybookRecordParser.CardCrossReferenceRecord;
import com.carddemo.equivalence.CopybookRecordParser.DailyTransactionRecord;
import com.carddemo.events.DeclineReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compares the authorization service against the validation section of {@code CBTRN02C}.
 *
 * <p>The service is a synthesis. {@code app/cbl/CBTRN02C.cbl:L370-L378} chains the four reject
 * reasons of {@code 1500-A-LOOKUP-XREF} at {@code app/cbl/CBTRN02C.cbl:L380-L392} and
 * {@code 1500-B-LOOKUP-ACCT} at {@code app/cbl/CBTRN02C.cbl:L393-L422};
 * {@code app/cbl/COTRN02C.cbl} supplies the request fields and {@code app/cbl/COSGN00C.cbl} the
 * actor. {@code COPAUA0C} and transaction {@code CP00} are absent from CardDemo, so no assertion
 * reads a synchronous original. The basis is the fixtures and the program text, never a run.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Authorization decisions from CBTRN02C L370-L422")
class AuthorizationDecisionEquivalenceTest {

    /** Every record of {@code app/data/ASCII/dailytran.txt}, at the width the copybook declares. */
    private static final int FIXTURE_FEED_COUNT = PicClause.DAILYTRAN_FIXTURE_RECORD_COUNT;

    /** Reject reasons no record of {@code app/data/ASCII/dailytran.txt} reaches. */
    private static final long UNREACHABLE_FROM_FIXTURES = 0L;

    /** Row of {@code /expected/posting-summary.csv} holding the stateless decline count. */
    private static final String MODEL_A_DECLINE_KEY = "model_a.declined_count";

    /** Checked-in expected outputs, shared with {@code PostingEquivalenceTest}. */
    private static final String EXPECTED_POSTING_RESOURCE = "/expected/posting-summary.csv";

    /** The one CardDemo program holding validate-and-authorize logic, read but never written. */
    private static final String VALIDATION_PROGRAM = "app/cbl/CBTRN02C.cbl";

    /** Paragraph reading the card cross-reference. */
    private static final String CROSS_REFERENCE_PARAGRAPH = "1500-A-LOOKUP-XREF";

    /** Paragraph reading the account and running both trailing comparisons. */
    private static final String ACCOUNT_PARAGRAPH = "1500-B-LOOKUP-ACCT";

    /** Selects {@code MOVE nnn TO WS-VALIDATION-FAIL-REASON} and captures the code. */
    private static final Pattern REASON_ASSIGNMENT =
            Pattern.compile("MOVE\\s+(\\d+)\\s+TO\\s+WS-VALIDATION-FAIL-REASON\\b");

    /** Selects a {@code MOVE} of a quoted literal and captures the text. */
    private static final Pattern QUOTED_MOVE = Pattern.compile("MOVE\\s+'([^']*)'");

    /**
     * Magnitude {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187} cannot hold.
     *
     * <p>Nine integer digits, from {@link PicClause#WS_TEMP_BAL_PRECISION} less
     * {@link PicClause#WS_TEMP_BAL_SCALE}.
     */
    private static final BigDecimal NARROWED_FIELD_CEILING =
            BigDecimal.TEN.pow(PicClause.WS_TEMP_BAL_PRECISION - PicClause.WS_TEMP_BAL_SCALE);

    /** Observation stamp every replica row carries, far enough back to be a fixed value. */
    private static final Instant OBSERVED_AT = Instant.EPOCH;

    /** Identifier the sequence hands out, at {@code DALYTRAN-ID PIC X(16)} width. */
    private static final String SYNTHETIC_TRANSACTION_ID = "SYNTH00000000001";

    /** Customer identifier at {@code XREF-CUST-ID PIC 9(09)} width. */
    private static final String SYNTHETIC_CUSTOMER_ID = "000000001";

    /**
     * Card number at {@code XREF-CARD-NUM PIC X(16)} width, absent from the fixtures.
     *
     * <p>The digits fail the Luhn checksum, which the non-addition case in
     * {@link FixtureModel} reads.
     */
    private static final String SYNTHETIC_CARD_NUMBER = "0000000000000001";

    /** Account identifier at {@code XREF-ACCT-ID PIC 9(11)} width. */
    private static final String SYNTHETIC_ACCOUNT_ID = "00000000001";

    /** Card number carrying one digit fewer than the storage key. */
    private static final String NARROW_CARD_INPUT = "000000000000001";

    /** Actor from {@code app/cbl/COSGN00C.cbl}, at {@code SEC-USR-ID PIC X(08)} width. */
    private static final String ACTOR = "equiv001";

    /** Account identifier no row of {@code app/data/ASCII/acctdata.txt} carries. */
    private static final String ABSENT_ACCOUNT_ID = "99999999999";

    /** Card number no row of {@code app/data/ASCII/cardxref.txt} carries. */
    private static final String ABSENT_CARD_NUMBER = "9999999999999999";

    /** {@code TRAN-PROC-TS} shape from {@link PicClause#PROCESSING_TIMESTAMP_SHAPE}. */
    private static final String PROCESSING_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /**
     * The one {@code DALYTRAN-ORIG-TS} value all fixture records carry.
     *
     * <p>Declared {@code PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}.
     */
    private static final String FEED_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** Capture moment later than {@link #SYNTHETIC_EXPIRY_DATE}. */
    private static final String LATE_TIMESTAMP = "2025-01-01 00:00:00.000000";

    /** {@code ACCT-EXPIRAION-DATE PIC X(10)} value the synthetic accounts carry. */
    private static final String SYNTHETIC_EXPIRY_DATE = "2024-01-01";

    /** Capture moment whose leading ten characters equal {@link #SYNTHETIC_EXPIRY_DATE}. */
    private static final String EXPIRY_EQUAL_TIMESTAMP = SYNTHETIC_EXPIRY_DATE + " 23:59:59.999999";

    /** Zero at {@code PIC S9(10)V99} scale. */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO
            .setScale(PicClause.ACCT_CURR_CYC_CREDIT_SCALE);

    /** Credit limit the equality cases compare against. */
    private static final BigDecimal EQUALITY_LIMIT = new BigDecimal("100.00");

    /** Cycle credit reaching {@link #EQUALITY_LIMIT} exactly with {@link #EQUALITY_AMOUNT}. */
    private static final BigDecimal EQUALITY_CYCLE_CREDIT = new BigDecimal("50.00");

    /** Amount reaching {@link #EQUALITY_LIMIT} exactly with {@link #EQUALITY_CYCLE_CREDIT}. */
    private static final BigDecimal EQUALITY_AMOUNT = new BigDecimal("50.00");

    /** Cycle credit above {@link #NARROWED_FIELD_CEILING} by one hundred. */
    private static final BigDecimal OVER_CEILING_CYCLE_CREDIT = new BigDecimal("1000000100.00");

    /** Credit limit the narrowed remainder of {@link #OVER_CEILING_CYCLE_CREDIT} fits under. */
    private static final BigDecimal NARROWING_LIMIT = new BigDecimal("500.00");

    /** Staleness window wide enough for a capture moment from the fixtures. */
    private static final Duration TOLERANT_STALENESS = Duration.ofDays(36_500L);

    /** Minutes a capture moment may sit in the future, from the service default. */
    private static final long FUTURE_MINUTES = 5L;

    /** Every record of the daily transaction feed, parsed once. */
    private static final List<DailyTransactionRecord> FEED =
            CardDemoFixtureLoader.loadDailyTransactions();

    /** Every cross-reference row, keyed by the sixteen-character card number. */
    private static final Map<String, CardCrossReferenceRecord> FIXTURE_CROSS_REFERENCES =
            CardDemoFixtureLoader.cardCrossReferencesByCardNumber();

    /** Every account row, keyed by the eleven-character account identifier. */
    private static final Map<String, AccountRecord> FIXTURE_ACCOUNTS =
            CardDemoFixtureLoader.accountsByAccountId();

    @Nested
    @DisplayName("The stateless fixture model")
    class FixtureModel {

        @Test
        @DisplayName("every fixture record matches the documented rule, and only 0102 is reached")
        void everyFixtureDecisionMatchesTheDocumentedRule() {
            DecisionHarness harness = DecisionHarness.overFixtures();
            List<DeclineReason> documented = new ArrayList<>(FEED.size());
            List<Outcome> observed = new ArrayList<>(FEED.size());

            for (DailyTransactionRecord record : FEED) {
                documented.add(documentedReason(record));
                observed.add(harness.authorize(requestFor(record)));
            }

            assertEquals(FIXTURE_FEED_COUNT, FEED.size(),
                    "the feed carries the record count CVTRA06Y declares");
            for (int index = 0; index < FEED.size(); index++) {
                assertEquals(Optional.ofNullable(documented.get(index)),
                        observed.get(index).declineReason(),
                        "dailytran.txt record " + (index + 1)
                                + " diverged from CBTRN02C L370-L422");
            }

            Map<DeclineReason, Long> reached = reasonCensus(documented);
            long declined = observed.stream().filter(outcome -> !outcome.approved()).count();
            long approved = observed.stream().filter(Outcome::approved).count();

            assertEquals(documented.stream().filter(reason -> reason != null).count(), declined,
                    "the derived decline count and the service decline count must agree");
            assertEquals(expectedModelADeclineCount(), declined,
                    "the stateless run diverged from the checked-in model_a.declined_count");
            assertEquals(FEED.size() - declined, approved,
                    "every record is approved once or declined once");
            assertEquals(Set.of(DeclineReason.OVER_CREDIT_LIMIT), reached.keySet(),
                    "only reason " + DeclineReason.OVER_CREDIT_LIMIT.code()
                            + " is reachable from the checked-in fixtures");
            assertEquals(UNREACHABLE_FROM_FIXTURES,
                    reached.getOrDefault(DeclineReason.INVALID_CARD_NUMBER,
                            UNREACHABLE_FROM_FIXTURES),
                    "every fixture card number resolves in cardxref.txt");
            assertEquals(UNREACHABLE_FROM_FIXTURES,
                    reached.getOrDefault(DeclineReason.ACCOUNT_NOT_FOUND,
                            UNREACHABLE_FROM_FIXTURES),
                    "every fixture account identifier resolves in acctdata.txt");
            assertEquals(UNREACHABLE_FROM_FIXTURES,
                    reached.getOrDefault(DeclineReason.ACCOUNT_EXPIRED,
                            UNREACHABLE_FROM_FIXTURES),
                    "every fixture expiry reaches the one capture date the feed carries");
        }

        @Test
        @DisplayName("record 1 resolves to account 00000000007 and authorizes")
        void theFirstFixtureRecordResolvesToAccountSevenAndAuthorizes() {
            DailyTransactionRecord first = FEED.get(0);
            CardCrossReferenceRecord crossReference =
                    FIXTURE_CROSS_REFERENCES.get(first.cardNumber());
            AccountRecord account = FIXTURE_ACCOUNTS.get(crossReference.accountId());

            assertEquals(new BigDecimal("504.77"), first.amount(),
                    "dailytran.txt record 1 carries +504.77 at CVTRA06Y L10");
            assertEquals(FEED_TIMESTAMP, first.originTimestamp(),
                    "dailytran.txt record 1 carries this capture moment at CVTRA06Y L16");
            assertEquals("00000000007", crossReference.accountId(),
                    "record 1 resolves through XREF-ACCT-ID at CVACT03Y L7");
            assertEquals(new BigDecimal("2065.00"), account.creditLimit(),
                    "account 7 carries the credit limit CBTRN02C L407 reads");
            assertEquals("2024-12-13", account.expirationDate(),
                    "account 7 carries the expiry text CBTRN02C L414 reads");

            Outcome outcome = DecisionHarness.overFixtures().authorize(requestFor(first));

            assertTrue(outcome.approved(), "record 1 passes all four rules of CBTRN02C L370-L422");
            assertEquals(CardDemoFixtureLoader.accountIdentifier(crossReference.accountId()),
                    outcome.accountId(),
                    "the outcome names account 7 at scale zero");
        }

        @Test
        @DisplayName("ADDITIVE: the sixteen-character key resolves and a narrower one is refused")
        void onlyTheSixteenCharacterKeyReachesTheCrossReferenceLookup() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(EQUALITY_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, ZERO_MONEY, ZERO_MONEY);

            Outcome padded = harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY,
                    EXPIRY_EQUAL_TIMESTAMP));

            assertEquals(PicClause.XREF_CARD_NUM_WIDTH, SYNTHETIC_CARD_NUMBER.length(),
                    "the resolving key holds the width KEYS(16 0) declares at XREFFILE.jcl L43");
            assertTrue(padded.approved(), "the zero-padded key resolves its cross-reference row");
            assertEquals(1L, harness.crossReferences().cardLookupCount(),
                    "the decision reads the cross-reference by card number exactly once");

            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> harness.authorize(request(NARROW_CARD_INPUT, ZERO_MONEY,
                            EXPIRY_EQUAL_TIMESTAMP)));

            assertEquals(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE, refusal.getMessage(),
                    "an identifier narrower than the storage key is refused, not padded");
            assertEquals(1L, harness.crossReferences().cardLookupCount(),
                    "the narrower identifier never reaches the VARCHAR(16) comparison, so it "
                            + "cannot report reason " + DeclineReason.INVALID_CARD_NUMBER.code());
            assertEquals(List.of(SYNTHETIC_CARD_NUMBER),
                    harness.crossReferences()
                            .findByAccountIdOrderByCardNumberAsc(SYNTHETIC_ACCOUNT_ID).stream()
                            .map(CardCrossReferenceEntity::getCardNumber)
                            .toList(),
                    "the account-keyed read answers with a list, per NONUNIQUEKEY at "
                            + "XREFFILE.jcl L75");
        }

        /**
         * Proves the three validations the source omits stay omitted.
         *
         * <p>Card numbers are tested for sixteen numeric digits only, at
         * {@code app/cbl/COCRDUPC.cbl:L193-L194}. No paragraph reads
         * {@code ACCT-ACTIVE-STATUS} at {@code app/cpy/CVACT01Y.cpy:L6}, and no card file opens.
         */
        @Test
        @DisplayName("no checksum, no card status and no account status gate the decision")
        void noChecksumNoCardStatusAndNoAccountStatusGateTheDecision() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(EQUALITY_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, ZERO_MONEY, ZERO_MONEY);

            Outcome outcome = harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY,
                    EXPIRY_EQUAL_TIMESTAMP));

            assertTrue(luhnRemainder(SYNTHETIC_CARD_NUMBER) != 0,
                    "the synthetic digits fail the Luhn checksum");
            assertTrue(outcome.approved(),
                    "COCRDUPC L193-L194 tests a card number for sixteen numeric digits and for "
                            + "nothing else, so no checksum stands between these digits and an "
                            + "approval");

            assertFalse(declaredFieldNames(CardCrossReferenceEntity.class).contains("activeStatus"),
                    "CBTRN02C opens six files at L29, L34, L40, L46, L51 and L57, and no card "
                            + "file is among them, so CARD-ACTIVE-STATUS reaches no rule");
            assertFalse(declaredFieldNames(AccountCreditSnapshotEntity.class)
                            .contains("activeStatus"),
                    "no paragraph reads ACCT-ACTIVE-STATUS at CVACT01Y L6 before posting");
            assertFalse(declaredFieldNames(AccountCreditSnapshotEntity.class)
                            .contains("currentBalance"),
                    "CBTRN02C L403-L405 computes the working balance without ACCT-CURR-BAL");
            assertEquals(Set.of("Y"), FIXTURE_ACCOUNTS.values().stream()
                            .map(AccountRecord::activeStatus)
                            .collect(Collectors.toSet()),
                    "every acctdata.txt row carries the same status, so an added status check "
                            + "would pass on the fixtures while diverging from the source");
        }
    }

    @Nested
    @DisplayName("Reason 0100 — INVALID CARD NUMBER FOUND")
    class InvalidCardNumber {

        @Test
        @DisplayName("ADDITIVE: an absent cross-reference row declines without an account read")
        void anAbsentCrossReferenceRowDeclinesWithoutAnAccountRead() {
            DecisionHarness harness = DecisionHarness.overRows(Map.of(), Map.of());

            Outcome outcome =
                    harness.authorize(request(ABSENT_CARD_NUMBER, ZERO_MONEY, FEED_TIMESTAMP));

            assertEquals(Optional.of(DeclineReason.INVALID_CARD_NUMBER), outcome.declineReason(),
                    "CBTRN02C L385-L387 assigns reason "
                            + DeclineReason.INVALID_CARD_NUMBER.code() + " on INVALID KEY");
            assertNull(outcome.accountId(),
                    "CBTRN02C L385 assigns this code before L394 reads XREF-ACCT-ID");
            assertEquals(1L, harness.crossReferences().cardLookupCount(),
                    "the miss came from the keyed read of CBTRN02C L383, not from an exception");
            assertEquals(UNREACHABLE_FROM_FIXTURES, harness.accounts().accountLookupCount(),
                    "the gate at CBTRN02C L372 keeps the account read of CBTRN02C L395 from "
                            + "running, so reason " + DeclineReason.ACCOUNT_NOT_FOUND.code()
                            + " cannot follow reason "
                            + DeclineReason.INVALID_CARD_NUMBER.code());
            assertEquals(UNREACHABLE_FROM_FIXTURES, fixtureReachCount(
                    DeclineReason.INVALID_CARD_NUMBER),
                    "no record of dailytran.txt reaches this reason");
        }
    }

    @Nested
    @DisplayName("Reason 0101 — ACCOUNT RECORD NOT FOUND")
    class AccountNotFound {

        @Test
        @DisplayName("ADDITIVE: an absent account row declines and throws nothing")
        void anAbsentAccountRowDeclinesAndThrowsNothing() {
            DecisionHarness harness = DecisionHarness.overRows(
                    Map.of(SYNTHETIC_CARD_NUMBER,
                            crossReference(SYNTHETIC_CARD_NUMBER, ABSENT_ACCOUNT_ID)),
                    Map.of());

            Outcome outcome =
                    harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY, FEED_TIMESTAMP));

            assertEquals(Optional.of(DeclineReason.ACCOUNT_NOT_FOUND), outcome.declineReason(),
                    "CBTRN02C L397-L399 assigns reason "
                            + DeclineReason.ACCOUNT_NOT_FOUND.code() + " on INVALID KEY");
            assertEquals(CardDemoFixtureLoader.accountIdentifier(ABSENT_ACCOUNT_ID),
                    outcome.accountId(),
                    "the identifier read at CBTRN02C L394 survives the decline");
            assertEquals(1L, harness.accounts().accountLookupCount(),
                    "the miss came from the keyed read of CBTRN02C L395, not from an exception");
            assertEquals(UNREACHABLE_FROM_FIXTURES,
                    fixtureReachCount(DeclineReason.ACCOUNT_NOT_FOUND),
                    "no record of dailytran.txt reaches this reason");
        }
    }

    @Nested
    @DisplayName("Reason 0102 — OVERLIMIT TRANSACTION")
    class OverCreditLimit {

        @Test
        @DisplayName("the reachable reason declines on the two cycle accumulators alone")
        void theReachableReasonDeclinesOnTheCycleAccumulatorsAlone() {
            DailyTransactionRecord record = FEED.stream()
                    .filter(candidate -> documentedReason(candidate)
                            == DeclineReason.OVER_CREDIT_LIMIT)
                    .findFirst()
                    .orElseThrow();
            AccountRecord account = FIXTURE_ACCOUNTS.get(
                    FIXTURE_CROSS_REFERENCES.get(record.cardNumber()).accountId());

            Outcome outcome = DecisionHarness.overFixtures().authorize(requestFor(record));

            assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome.declineReason(),
                    "CBTRN02C L410-L412 assigns reason "
                            + DeclineReason.OVER_CREDIT_LIMIT.code());
            assertEquals(workingBalance(ZERO_MONEY, ZERO_MONEY, record.amount()),
                    workingBalance(account.currentCycleCredit(), account.currentCycleDebit(),
                            record.amount()),
                    "the snapshot holds both accumulators at their fixture value of zero");
            assertTrue(account.currentBalance().compareTo(account.creditLimit()) < 0,
                    "the current balance sits under the limit, and CBTRN02C L403-L405 declines "
                            + "without reading it");
            assertEquals(expectedModelADeclineCount(),
                    fixtureReachCount(DeclineReason.OVER_CREDIT_LIMIT),
                    "this reason carries the whole checked-in model_a.declined_count");
        }

        @Test
        @DisplayName("ADDITIVE: ACCT-CREDIT-LIMIT >= WS-TEMP-BAL approves on equality")
        void equalityAtTheCreditLimitApproves() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(EQUALITY_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, EQUALITY_CYCLE_CREDIT, ZERO_MONEY);

            Outcome outcome = harness.authorize(request(SYNTHETIC_CARD_NUMBER, EQUALITY_AMOUNT,
                    EXPIRY_EQUAL_TIMESTAMP));

            assertEquals(EQUALITY_LIMIT,
                    workingBalance(EQUALITY_CYCLE_CREDIT, ZERO_MONEY, EQUALITY_AMOUNT),
                    "the working balance of CBTRN02C L403-L405 equals the credit limit");
            assertTrue(outcome.approved(),
                    "CBTRN02C L407 continues to L408 when the two values are equal");
        }

        @Test
        @DisplayName("ADDITIVE: S9(09)V99 drops the high-order digit above the field ceiling")
        void theNarrowedWorkingFieldTurnsADeclineIntoAnApproval() {
            BigDecimal full = CobolDecimal.add(
                    CobolDecimal.subtract(OVER_CEILING_CYCLE_CREDIT, ZERO_MONEY,
                            PicClause.WS_TEMP_BAL_SCALE),
                    ZERO_MONEY, PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal narrowed = workingBalance(OVER_CEILING_CYCLE_CREDIT, ZERO_MONEY, ZERO_MONEY);
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(NARROWING_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, OVER_CEILING_CYCLE_CREDIT, ZERO_MONEY);

            Outcome outcome = harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY,
                    EXPIRY_EQUAL_TIMESTAMP));

            assertTrue(full.compareTo(NARROWED_FIELD_CEILING) >= 0,
                    "the constructed value reaches the ceiling no fixture accumulator reaches");
            assertTrue(full.compareTo(NARROWING_LIMIT) > 0,
                    "at full width the value exceeds the credit limit");
            assertTrue(narrowed.compareTo(NARROWING_LIMIT) <= 0,
                    "at the width of CBTRN02C L187 the value fits under the same limit");
            assertTrue(outcome.approved(),
                    "the narrowing of CBTRN02C L187 turns this decline into an approval");
        }
    }

    @Nested
    @DisplayName("Reason 0103 — TRANSACTION RECEIVED AFTER ACCT EXPIRATION")
    class AccountExpired {

        @Test
        @DisplayName("ADDITIVE: the ten-character comparison is textual, never temporal")
        void aLaterCaptureDateDeclinesUnderTheTextComparison() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(EQUALITY_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, ZERO_MONEY, ZERO_MONEY);

            Outcome outcome =
                    harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY, LATE_TIMESTAMP));

            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                    "CBTRN02C L417-L419 assigns reason "
                            + DeclineReason.ACCOUNT_EXPIRED.code());
            assertEquals("VARCHAR(10)", PicClause.ACCT_EXPIRATION_DATE_COLUMN_TYPE,
                    "ACCT-EXPIRAION-DATE at CVACT01Y L11 is stored as text");
            assertTrue(SYNTHETIC_EXPIRY_DATE.compareTo(
                            capturedDate(LATE_TIMESTAMP)) < 0,
                    "the two ten-character values compare as strings at CBTRN02C L414");
            assertEquals(PicClause.ACCOUNT_EXPIRATION_COMPARISON_WIDTH,
                    capturedDate(LATE_TIMESTAMP).length(),
                    "DALYTRAN-ORIG-TS (1:10) supplies ten characters of the twenty-six at "
                            + "CVTRA06Y L16");
            assertEquals(UNREACHABLE_FROM_FIXTURES,
                    fixtureReachCount(DeclineReason.ACCOUNT_EXPIRED),
                    "no record of dailytran.txt reaches this reason");
        }

        @Test
        @DisplayName("ADDITIVE: equal ten-character date text approves")
        void equalDateTextApproves() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(EQUALITY_LIMIT,
                    SYNTHETIC_EXPIRY_DATE, ZERO_MONEY, ZERO_MONEY);

            Outcome outcome = harness.authorize(request(SYNTHETIC_CARD_NUMBER, ZERO_MONEY,
                    EXPIRY_EQUAL_TIMESTAMP));

            assertEquals(SYNTHETIC_EXPIRY_DATE, capturedDate(EXPIRY_EQUAL_TIMESTAMP),
                    "the leading ten characters equal the stored expiry text");
            assertTrue(outcome.approved(),
                    "CBTRN02C L414 continues to L415 when the two values are equal");
        }

        @Test
        @DisplayName("reason 0103 overwrites reason 0102 when both comparisons fail")
        void reasonOneZeroThreeOverwritesReasonOneZeroTwo() {
            DecisionHarness harness = DecisionHarness.overSyntheticAccount(ZERO_MONEY,
                    SYNTHETIC_EXPIRY_DATE, EQUALITY_CYCLE_CREDIT, ZERO_MONEY);

            Outcome outcome = harness.authorize(request(SYNTHETIC_CARD_NUMBER, EQUALITY_AMOUNT,
                    LATE_TIMESTAMP));

            assertTrue(workingBalance(EQUALITY_CYCLE_CREDIT, ZERO_MONEY, EQUALITY_AMOUNT)
                            .compareTo(ZERO_MONEY) > 0,
                    "the credit-limit comparison of CBTRN02C L407 fails on these values");
            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome.declineReason(),
                    "expected reason " + DeclineReason.ACCOUNT_EXPIRED.code()
                            + " to overwrite reason " + DeclineReason.OVER_CREDIT_LIMIT.code()
                            + ": CBTRN02C L413 closes the credit test and L414 opens the expiry "
                            + "test with no gate between them, so the later MOVE at L417 stands");
            assertEquals("IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)",
                    statementAfterTheCreditTest(),
                    "the expiry test is the next statement after the END-IF closing the credit "
                            + "test: a gate, an ELSE or an EXIT there would let reason "
                            + DeclineReason.OVER_CREDIT_LIMIT.code() + " survive");
        }
    }

    @Nested
    @DisplayName("Wire reason contract")
    class WireContract {

        @Test
        @DisplayName("each reason serializes as its four-character code with the source text")
        void everyReasonUsesItsFourCharacterWireCodeAndVerbatimText() {
            JsonMapper mapper = JsonMapper.builder().build();

            for (DeclineReason reason : DeclineReason.values()) {
                assertEquals(PicClause.VALIDATION_FAIL_REASON_WIDTH, reason.code().length(),
                        reason + " carries the WS-VALIDATION-FAIL-REASON PIC 9(04) width");
                assertEquals("\"" + reason.code() + "\"", mapper.writeValueAsString(reason),
                        reason + " serializes as a quoted code, never as a bare number");
                assertSame(reason, DeclineReason.fromCode(reason.code()),
                        reason + " reads back from the same wire code");
                assertFalse(reason.description().isBlank(),
                        reason + " carries the text the source MOVEs beside the code");
                assertTrue(reason.description().length()
                                <= PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH,
                        reason + " fits WS-VALIDATION-FAIL-REASON-DESC PIC X(76)");
            }

            Map<String, String> published = new TreeMap<>();
            for (DeclineReason reason : DeclineReason.values()) {
                published.put(String.valueOf(reason.numericCode()), reason.description());
            }

            assertEquals(assignmentsInValidationSection(), published,
                    "each code and text must be the pair the two validation paragraphs MOVE into "
                            + "WS-VALIDATION-TRAILER");
        }

        @Test
        @DisplayName("the chain declares one stopping segment and one overwriting segment")
        void theFourRulesDeclareTheTwoSourceSegments() {
            List<DeclineRule> rules = DecisionHarness.overFixtures().rules();

            assertEquals(List.of(DeclineRule.Segment.STOP_ON_FIRST_DECLINE,
                            DeclineRule.Segment.STOP_ON_FIRST_DECLINE,
                            DeclineRule.Segment.LAST_DECLINE_WINS,
                            DeclineRule.Segment.LAST_DECLINE_WINS),
                    rules.stream().map(DeclineRule::segment).toList(),
                    "the gate at CBTRN02C L372 stops the first two, and the ungated pair at "
                            + "CBTRN02C L407-L420 keeps the last decline");
            assertEquals(DeclineRule.Segment.values().length,
                    rules.stream().map(DeclineRule::segment).distinct().count(),
                    "the chain uses both segments the interface declares");
        }
    }

    /**
     * Answers with the reject reason {@code app/cbl/CBTRN02C.cbl:L370-L422} assigns to one record.
     *
     * <p>Both cycle accumulators hold the value the account row carries.
     * {@code AccountCreditSnapshotEntity} exposes no write, so no decision moves them.
     *
     * @param record one record of {@code app/data/ASCII/dailytran.txt}
     * @return the reject reason that stands, or {@code null} when every test passes
     */
    private static DeclineReason documentedReason(DailyTransactionRecord record) {
        CardCrossReferenceRecord crossReference =
                FIXTURE_CROSS_REFERENCES.get(record.cardNumber());
        if (crossReference == null) {
            return DeclineReason.INVALID_CARD_NUMBER;
        }
        AccountRecord account = FIXTURE_ACCOUNTS.get(crossReference.accountId());
        if (account == null) {
            return DeclineReason.ACCOUNT_NOT_FOUND;
        }

        DeclineReason standing = null;
        BigDecimal working = workingBalance(account.currentCycleCredit(),
                account.currentCycleDebit(), record.amount());
        if (account.creditLimit().compareTo(working) < 0) {
            standing = DeclineReason.OVER_CREDIT_LIMIT;
        }
        if (account.expirationDate().compareTo(capturedDate(record.originTimestamp())) < 0) {
            standing = DeclineReason.ACCOUNT_EXPIRED;
        }
        return standing;
    }

    /**
     * Computes {@code WS-TEMP-BAL} the way {@code app/cbl/CBTRN02C.cbl:L403-L405} computes it.
     *
     * <p>Two arithmetic steps at {@link PicClause#WS_TEMP_BAL_SCALE}, then the single store into
     * {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187}.
     *
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13}
     * @param cycleDebit  {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14}
     * @param amount      {@code DALYTRAN-AMT} at {@code app/cpy/CVTRA06Y.cpy:L10}
     * @return the narrowed working balance {@code app/cbl/CBTRN02C.cbl:L407} compares
     */
    private static BigDecimal workingBalance(BigDecimal cycleCredit, BigDecimal cycleDebit,
            BigDecimal amount) {
        BigDecimal difference =
                CobolDecimal.subtract(cycleCredit, cycleDebit, PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal computed =
                CobolDecimal.add(difference, amount, PicClause.WS_TEMP_BAL_SCALE);
        return CobolDecimal.truncateToPictureField(computed, PicClause.WS_TEMP_BAL_PRECISION,
                PicClause.WS_TEMP_BAL_SCALE);
    }

    /**
     * Slices the ten characters {@code app/cbl/CBTRN02C.cbl:L414} compares.
     *
     * @param originTimestamp the twenty-six character capture moment
     * @return the leading ten characters, as text
     */
    private static String capturedDate(String originTimestamp) {
        return CopybookRecordParser.timestampDatePart(originTimestamp);
    }

    /**
     * Counts how many records of the feed the documented rule assigns each reason.
     *
     * @param documented one entry per fixture record, {@code null} where the record passed
     * @return a count per reason, holding no entry for a reason the feed never reaches
     */
    private static Map<DeclineReason, Long> reasonCensus(List<DeclineReason> documented) {
        Map<DeclineReason, Long> census = new EnumMap<>(DeclineReason.class);
        for (DeclineReason reason : documented) {
            if (reason != null) {
                census.merge(reason, 1L, Long::sum);
            }
        }
        return census;
    }

    /**
     * Counts the fixture records the documented rule assigns one reason.
     *
     * @param reason the reject reason to count
     * @return the count, derived from the feed on every call
     */
    private static long fixtureReachCount(DeclineReason reason) {
        return reasonCensus(FEED.stream().map(
                AuthorizationDecisionEquivalenceTest::documentedReason).toList())
                .getOrDefault(reason, UNREACHABLE_FROM_FIXTURES);
    }

    /**
     * Reads the stateless decline count from {@code /expected/posting-summary.csv}.
     *
     * <p>The row is {@code model_a.declined_count}, keyed as
     * {@code PostingEquivalenceTest} keys it: the entity column joined to the field column.
     *
     * @return the checked-in count
     * @throws IllegalStateException when the resource holds no such row
     */
    private static long expectedModelADeclineCount() {
        Map<String, String> expected = new TreeMap<>();
        try (InputStream stream = AuthorizationDecisionEquivalenceTest.class
                .getResourceAsStream(EXPECTED_POSTING_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(EXPECTED_POSTING_RESOURCE + " is not on the "
                        + "test classpath");
            }
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList()) {
                String[] columns = line.split(",", -1);
                if (line.startsWith("#") || columns.length < 6) {
                    continue;
                }
                expected.putIfAbsent(columns[3] + "." + columns[4], columns[5]);
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + EXPECTED_POSTING_RESOURCE, unreadable);
        }

        String value = expected.get(MODEL_A_DECLINE_KEY);
        if (value == null) {
            throw new IllegalStateException(
                    EXPECTED_POSTING_RESOURCE + " holds no " + MODEL_A_DECLINE_KEY + " row");
        }
        return Long.parseLong(value);
    }

    /**
     * Reads every reject reason the two validation paragraphs assign, from the program itself.
     *
     * <p>The scan spans the label {@code 1500-A-LOOKUP-XREF.} to the {@code EXIT.} closing
     * {@code 1500-B-LOOKUP-ACCT}, so an assignment elsewhere takes no part. Each
     * {@code MOVE nnn TO WS-VALIDATION-FAIL-REASON} pairs with the quoted {@code MOVE} after it.
     *
     * @return the code of each assignment mapped to its text
     */
    private static Map<String, String> assignmentsInValidationSection() {
        Map<String, String> assignments = new TreeMap<>();
        String pending = null;
        for (String line : validationSection()) {
            Matcher code = REASON_ASSIGNMENT.matcher(line);
            if (code.find()) {
                pending = code.group(1);
                continue;
            }
            Matcher text = QUOTED_MOVE.matcher(line);
            if (pending != null && text.find()) {
                assignments.put(pending, text.group(1));
                pending = null;
            }
        }
        return assignments;
    }

    /**
     * Reads the statement following the {@code END-IF} that closes the credit-limit test.
     *
     * <p>The scan starts at the over-limit assignment, passes its text {@code MOVE}, stops at the
     * {@code END-IF} closing that test, and answers with the next statement. Comment and blank
     * lines take no part. The span is {@code app/cbl/CBTRN02C.cbl:L410-L414}.
     *
     * @return that statement, stripped of leading and trailing space
     * @throws IllegalStateException when the paragraph ends before the statement is reached
     */
    private static String statementAfterTheCreditTest() {
        List<String> section = validationSection();
        boolean creditTestClosed = false;

        for (int index = assignmentLineOf(section, DeclineReason.OVER_CREDIT_LIMIT) + 1;
                index < section.size(); index++) {
            String line = section.get(index).strip();
            if (line.isEmpty() || line.startsWith("*")) {
                continue;
            }
            if (creditTestClosed) {
                return line;
            }
            creditTestClosed = line.equals("END-IF");
        }
        throw new IllegalStateException(VALIDATION_PROGRAM + " paragraph " + ACCOUNT_PARAGRAPH
                + " ends after the credit test with no statement following it");
    }

    /**
     * Finds the line assigning one reject reason inside the validation section.
     *
     * @param section the lines of the two validation paragraphs
     * @param reason  the reject reason to locate
     * @return the index of the assignment
     * @throws IllegalStateException when the section assigns no such code
     */
    private static int assignmentLineOf(List<String> section, DeclineReason reason) {
        String assignment = String.valueOf(reason.numericCode());
        for (int index = 0; index < section.size(); index++) {
            Matcher code = REASON_ASSIGNMENT.matcher(section.get(index));
            if (code.find() && code.group(1).equals(assignment)) {
                return index;
            }
        }
        throw new IllegalStateException(
                VALIDATION_PROGRAM + " assigns no reject reason " + reason.code());
    }

    /**
     * Reads the two validation paragraphs of {@code app/cbl/CBTRN02C.cbl}.
     *
     * @return the lines from the first paragraph label to the {@code EXIT.} closing the second
     * @throws IllegalStateException when the program or either paragraph is absent
     */
    private static List<String> validationSection() {
        List<String> program = programLines();
        int start = labelLineOf(program, CROSS_REFERENCE_PARAGRAPH);
        int second = labelLineOf(program, ACCOUNT_PARAGRAPH);

        for (int index = second; index < program.size(); index++) {
            if (program.get(index).strip().equals("EXIT.")) {
                return List.copyOf(program.subList(start, index + 1));
            }
        }
        throw new IllegalStateException(
                VALIDATION_PROGRAM + " holds no EXIT closing " + ACCOUNT_PARAGRAPH);
    }

    /**
     * Finds one paragraph label.
     *
     * @param program the program lines
     * @param label   the paragraph name, without its full stop
     * @return the index of the label line
     * @throws IllegalStateException when the program holds no such label
     */
    private static int labelLineOf(List<String> program, String label) {
        for (int index = 0; index < program.size(); index++) {
            if (program.get(index).strip().equals(label + ".")) {
                return index;
            }
        }
        throw new IllegalStateException(VALIDATION_PROGRAM + " holds no paragraph " + label);
    }

    /**
     * Reads {@code app/cbl/CBTRN02C.cbl} from the repository, without writing to it.
     *
     * @return every line of the program
     * @throws IllegalStateException when no ancestor of the fixture directory holds the program
     */
    private static List<String> programLines() {
        for (Path candidate = CardDemoFixtureLoader.fixtureDirectory();
                candidate != null; candidate = candidate.getParent()) {
            Path program = candidate.resolve(VALIDATION_PROGRAM);
            if (Files.isRegularFile(program)) {
                try {
                    return Files.readAllLines(program, StandardCharsets.ISO_8859_1);
                } catch (IOException unreadable) {
                    throw new UncheckedIOException("cannot read " + program, unreadable);
                }
            }
        }
        throw new IllegalStateException("no ancestor of '"
                + CardDemoFixtureLoader.fixtureDirectory() + "' holds '" + VALIDATION_PROGRAM
                + "'");
    }

    /**
     * Computes the Luhn remainder of a card number.
     *
     * <p>No CardDemo program computes it. The value serves one assertion, that the digits an
     * approval is granted for need not satisfy it.
     *
     * @param cardNumber sixteen digits
     * @return the checksum remainder, zero when the digits satisfy the algorithm
     */
    private static int luhnRemainder(String cardNumber) {
        int total = 0;
        for (int offset = 0; offset < cardNumber.length(); offset++) {
            int digit = cardNumber.charAt(cardNumber.length() - 1 - offset) - '0';
            if (offset % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            total += digit;
        }
        return total % 10;
    }

    /**
     * Names the fields one class declares.
     *
     * @param declaring the class to read
     * @return every declared field name
     */
    private static Set<String> declaredFieldNames(Class<?> declaring) {
        Set<String> names = new LinkedHashSet<>();
        for (Field field : declaring.getDeclaredFields()) {
            names.add(field.getName());
        }
        return names;
    }

    /**
     * Builds the request one fixture record carries.
     *
     * @param record one record of {@code app/data/ASCII/dailytran.txt}
     * @return the request body, naming the card and no account identifier
     */
    private static AuthorizationRequest requestFor(DailyTransactionRecord record) {
        return new AuthorizationRequest(null, record.typeCode(), record.categoryCode(),
                record.source(), record.description(), record.amount().toPlainString(),
                record.merchantId(), record.merchantName(), record.merchantCity(),
                record.merchantZip(), record.cardNumber(), record.originTimestamp(),
                PROCESSING_TIMESTAMP, null);
    }

    /**
     * Builds a request from the three values the chain reads.
     *
     * @param cardNumber      the card number, at whatever width the case exercises
     * @param amount          the transaction amount
     * @param originTimestamp the twenty-six character capture moment
     * @return the request body, naming no account identifier
     */
    private static AuthorizationRequest request(String cardNumber, BigDecimal amount,
            String originTimestamp) {
        return new AuthorizationRequest(null, "01", "0001", "POS TERM",
                "SYNTHETIC AUTHORIZATION", amount.toPlainString(), "000000001",
                "SYNTHETIC MERCHANT", "SYNTHETIC CITY", "00000", cardNumber, originTimestamp,
                PROCESSING_TIMESTAMP, null);
    }

    /**
     * Builds one cross-reference row of {@code app/cpy/CVACT03Y.cpy}.
     *
     * @param cardNumber sixteen characters, from {@code XREF-CARD-NUM}
     * @param accountId  eleven characters, from {@code XREF-ACCT-ID}
     * @return the row
     */
    private static CardCrossReferenceEntity crossReference(String cardNumber, String accountId) {
        return new CardCrossReferenceEntity(cardNumber, SYNTHETIC_CUSTOMER_ID, accountId,
                OBSERVED_AT);
    }

    /**
     * Builds one credit snapshot of the fields {@code app/cpy/CVACT01Y.cpy} supplies.
     *
     * @param accountId      eleven characters, from {@code ACCT-ID}
     * @param creditLimit    from {@code ACCT-CREDIT-LIMIT} at {@code app/cpy/CVACT01Y.cpy:L8}
     * @param expirationDate from {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy:L11}
     * @param cycleCredit    from {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13}
     * @param cycleDebit     from {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14}
     * @return the snapshot
     */
    private static AccountCreditSnapshotEntity account(String accountId, BigDecimal creditLimit,
            String expirationDate, BigDecimal cycleCredit, BigDecimal cycleDebit) {
        return new AccountCreditSnapshotEntity(accountId, creditLimit, expirationDate, cycleCredit,
                cycleDebit, OBSERVED_AT);
    }

    /**
     * One authorization service over two in-memory replica tables.
     *
     * @param service         the service under comparison
     * @param rules           the four rules, in the order the source runs them
     * @param crossReferences the table {@code card_xref} stands in for
     * @param accounts        the table {@code account_credit_snapshot} stands in for
     */
    private record DecisionHarness(AuthorizationService service, List<DeclineRule> rules,
            CardCrossReferenceTable crossReferences, AccountCreditSnapshotTable accounts) {

        /**
         * Builds a harness over the two replica tables.
         *
         * @param crossReferenceRows rows keyed by sixteen-character card number
         * @param accountRows        rows keyed by eleven-character account identifier
         * @return the harness
         */
        private static DecisionHarness overRows(
                Map<String, CardCrossReferenceEntity> crossReferenceRows,
                Map<String, AccountCreditSnapshotEntity> accountRows) {

            CardCrossReferenceTable crossReferences =
                    new CardCrossReferenceTable(crossReferenceRows);
            AccountCreditSnapshotTable accounts = new AccountCreditSnapshotTable(accountRows);
            List<DeclineRule> rules = List.of(new CardCrossReferenceRule(crossReferences),
                    new AccountExistsRule(accounts), new CreditLimitRule(),
                    new AccountExpirationRule());

            AuthorizationService service = new AuthorizationService(rules, crossReferences,
                    new FixedIdentifierSource(),
                    new OutboxWriter(writeOnlySeam(OutboxEventRepository.class)),
                    writeOnlySeam(UnresolvedCardAttemptRepository.class),
                    writeOnlySeam(AuthorizationDecisionRepository.class),
                    new OriginTimestampWindow(TOLERANT_STALENESS.toMinutes(), FUTURE_MINUTES),
                    new SimpleMeterRegistry(), immediateTransactions(), replicaProperties());
            return new DecisionHarness(service, rules, crossReferences, accounts);
        }

        /**
         * Builds a harness loaded with every checked-in cross-reference and account row.
         *
         * @return the harness
         */
        private static DecisionHarness overFixtures() {
            Map<String, CardCrossReferenceEntity> crossReferenceRows = new LinkedHashMap<>();
            for (CardCrossReferenceRecord row : FIXTURE_CROSS_REFERENCES.values()) {
                crossReferenceRows.put(row.cardNumber(), new CardCrossReferenceEntity(
                        row.cardNumber(), row.customerId(), row.accountId(), OBSERVED_AT));
            }
            Map<String, AccountCreditSnapshotEntity> accountRows = new LinkedHashMap<>();
            for (AccountRecord row : FIXTURE_ACCOUNTS.values()) {
                accountRows.put(row.accountId(), account(row.accountId(), row.creditLimit(),
                        row.expirationDate(), row.currentCycleCredit(), row.currentCycleDebit()));
            }
            return overRows(crossReferenceRows, accountRows);
        }

        /**
         * Builds a harness holding one card resolving to one account with the given values.
         *
         * @param creditLimit    from {@code ACCT-CREDIT-LIMIT}
         * @param expirationDate from {@code ACCT-EXPIRAION-DATE}
         * @param cycleCredit    from {@code ACCT-CURR-CYC-CREDIT}
         * @param cycleDebit     from {@code ACCT-CURR-CYC-DEBIT}
         * @return the harness
         */
        private static DecisionHarness overSyntheticAccount(BigDecimal creditLimit,
                String expirationDate, BigDecimal cycleCredit, BigDecimal cycleDebit) {
            return overRows(
                    Map.of(SYNTHETIC_CARD_NUMBER,
                            crossReference(SYNTHETIC_CARD_NUMBER, SYNTHETIC_ACCOUNT_ID)),
                    Map.of(SYNTHETIC_ACCOUNT_ID, account(SYNTHETIC_ACCOUNT_ID, creditLimit,
                            expirationDate, cycleCredit, cycleDebit)));
        }

        /**
         * Runs one request under the actor a decision row records.
         *
         * @param request the request body
         * @return the decision the caller receives
         */
        private Outcome authorize(AuthorizationRequest request) {
            return service.authorize(request, ACTOR);
        }
    }

    /**
     * The {@code card_xref} replica, keyed by {@code KEYS(16 0)} at {@code XREFFILE.jcl:L43}.
     *
     * <p>The card-number read matches all sixteen characters and nothing shorter, the
     * {@code VARCHAR(16)} comparison the column performs. The account-number read answers with a
     * list, which {@code NONUNIQUEKEY} at {@code app/jcl/XREFFILE.jcl:L75} permits.
     */
    private static final class CardCrossReferenceTable implements CardCrossReferenceRepository {

        /** Rows by card number, in insertion order. */
        private final Map<String, CardCrossReferenceEntity> rows;

        /** How many times the card-number read ran. */
        private long cardLookups;

        private CardCrossReferenceTable(Map<String, CardCrossReferenceEntity> rows) {
            this.rows = new LinkedHashMap<>(rows);
        }

        /**
         * Counts the card-number reads this table served.
         *
         * @return the count since construction
         */
        private long cardLookupCount() {
            return cardLookups;
        }

        @Override
        public Optional<CardCrossReferenceEntity> findByCardNumber(String cardNumber) {
            cardLookups++;
            return Optional.ofNullable(rows.get(cardNumber));
        }

        @Override
        public List<CardCrossReferenceEntity> findByAccountIdOrderByCardNumberAsc(
                String accountId) {
            return rows.values().stream()
                    .filter(row -> row.getAccountId().equals(accountId))
                    .sorted(Comparator.comparing(CardCrossReferenceEntity::getCardNumber))
                    .toList();
        }

        @Override
        public Optional<CardCrossReferenceEntity> findFirstByAccountIdOrderByCardNumberAsc(
                String accountId) {
            return findByAccountIdOrderByCardNumberAsc(accountId).stream().findFirst();
        }

        @Override
        public int applyStateChange(String cardNumber, String customerId, String accountId,
                UUID sourceEventId, Instant sourceOccurredAt, Instant observedAt) {
            throw refusal("applyStateChange");
        }

        @Override
        public int refreshObservation(String accountId, String cardNumber,
                UUID sourceEventId, Instant sourceOccurredAt, Instant observedAt) {
            throw refusal("refreshObservation");
        }

        @Override
        public long countByObservedAtBefore(Instant cutoff) {
            throw refusal("countByObservedAtBefore");
        }

        @Override
        public <S extends CardCrossReferenceEntity> S save(S row) {
            throw refusal("save");
        }

        @Override
        public <S extends CardCrossReferenceEntity> List<S> saveAll(Iterable<S> batch) {
            throw refusal("saveAll");
        }

        @Override
        public Optional<CardCrossReferenceEntity> findById(String cardNumber) {
            throw refusal("findById");
        }

        @Override
        public boolean existsById(String cardNumber) {
            throw refusal("existsById");
        }

        @Override
        public List<CardCrossReferenceEntity> findAll() {
            throw refusal("findAll");
        }

        @Override
        public List<CardCrossReferenceEntity> findAllById(Iterable<String> cardNumbers) {
            throw refusal("findAllById");
        }

        @Override
        public long count() {
            throw refusal("count");
        }

        @Override
        public void deleteById(String cardNumber) {
            throw refusal("deleteById");
        }

        @Override
        public void delete(CardCrossReferenceEntity row) {
            throw refusal("delete");
        }

        @Override
        public void deleteAllById(Iterable<? extends String> cardNumbers) {
            throw refusal("deleteAllById");
        }

        @Override
        public void deleteAll(Iterable<? extends CardCrossReferenceEntity> batch) {
            throw refusal("deleteAll");
        }

        @Override
        public void deleteAll() {
            throw refusal("deleteAll");
        }

        private static UnsupportedOperationException refusal(String member) {
            return new UnsupportedOperationException(
                    "CardCrossReferenceRepository." + member + " takes no part in an "
                            + "authorization decision");
        }
    }

    /**
     * The {@code account_credit_snapshot} replica, keyed by the eleven characters
     * {@code ACCT-ID PIC 9(11)} holds at {@code app/cpy/CVACT01Y.cpy:L5}.
     *
     * <p>The read matches on the stored identifier text, leading zeros included, which is the
     * {@code CHAR(11)} comparison the column performs.
     */
    private static final class AccountCreditSnapshotTable
            implements AccountCreditSnapshotRepository {

        /** Rows by account identifier, in insertion order. */
        private final Map<String, AccountCreditSnapshotEntity> rows;

        /** How many times the account read ran. */
        private long accountLookups;

        private AccountCreditSnapshotTable(Map<String, AccountCreditSnapshotEntity> rows) {
            this.rows = new LinkedHashMap<>(rows);
        }

        /**
         * Counts the account reads this table served.
         *
         * @return the count since construction
         */
        private long accountLookupCount() {
            return accountLookups;
        }

        @Override
        public Optional<AccountCreditSnapshotEntity> findByAccountId(String accountId) {
            accountLookups++;
            return Optional.ofNullable(rows.get(accountId));
        }

        @Override
        public AccountCreditSnapshotEntity save(AccountCreditSnapshotEntity snapshot) {
            throw refusal("save");
        }

        @Override
        public long count() {
            throw refusal("count");
        }

        @Override
        public int applyStateChange(String accountId, BigDecimal creditLimit,
                String accountExpirationDate, BigDecimal currentCycleCredit,
                BigDecimal currentCycleDebit, UUID sourceEventId,
                Instant sourceOccurredAt, Instant observedAt) {
            throw refusal("applyStateChange");
        }

        @Override
        public long countByObservedAtBefore(Instant cutoff) {
            throw refusal("countByObservedAtBefore");
        }

        private static UnsupportedOperationException refusal(String member) {
            return new UnsupportedOperationException(
                    "AccountCreditSnapshotRepository." + member + " takes no part in an "
                            + "authorization decision");
        }
    }

    /**
     * A repository the decision only writes to, answering {@code save} with the row it was given.
     *
     * <p>The three write-side repositories record an attempt, a decision row and an outbox row.
     * This comparison reads none of them, and the service discards every return value. Any other
     * member refuses, so an unexpected call fails loudly and names itself.
     *
     * @param <R>  the repository interface
     * @param seam the repository interface to stand in for
     * @return an instance of {@code seam}
     */
    private static <R> R writeOnlySeam(Class<R> seam) {
        InvocationHandler handler = (proxy, member, arguments) -> switch (member.getName()) {
            case "save" -> arguments[0];
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> seam.getSimpleName() + "[write only]";
            default -> throw new UnsupportedOperationException(seam.getSimpleName() + "."
                    + member.getName() + " takes no part in an authorization decision");
        };
        return seam.cast(Proxy.newProxyInstance(seam.getClassLoader(), new Class<?>[] {seam},
                handler));
    }

    /**
     * The identifier sequence, answering with one fixed value.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L444-L451} browses the file backwards and adds one, which the
     * target replaces with a database sequence. Nothing here reads a database.
     */
    private static final class FixedIdentifierSource extends TransactionIdentifierSource {

        private FixedIdentifierSource() {
            super(null, "");
        }

        @Override
        public String nextIdentifier() {
            return SYNTHETIC_TRANSACTION_ID;
        }
    }

    /**
     * Runs the service's transaction callback on the calling thread.
     *
     * @return a template that opens no transaction
     */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    /**
     * Supplies the one configuration value the service constructor reads.
     *
     * @return properties carrying a replica window wide enough for a fixture capture moment
     */
    private static AuthorizationProperties replicaProperties() {
        return new AuthorizationProperties(null, null, null, null,
                new AuthorizationProperties.Replica(TOLERANT_STALENESS));
    }
}

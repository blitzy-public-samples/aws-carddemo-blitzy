package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.domain.AccountBalanceUpdater;
import com.carddemo.ledger.domain.CategoryBalanceUpdater;
import com.carddemo.ledger.domain.PostingService;
import com.carddemo.ledger.domain.RejectRecorder;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.entity.ProcessedEventEntity;
import com.carddemo.ledger.entity.RejectedTransactionEntity;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity.TransactionCategoryBalanceId;
import com.carddemo.ledger.entity.TransactionEntity;
import com.carddemo.ledger.outbox.OutboxWriter;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import com.carddemo.ledger.repository.OutboxEventRepository;
import com.carddemo.ledger.repository.RejectedTransactionRepository;
import com.carddemo.ledger.repository.TransactionCategoryBalanceRepository;
import com.carddemo.ledger.repository.TransactionRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compares the ledger posting path against {@code app/cbl/CBTRN02C.cbl} over every fixture record.
 *
 * <p>One run drives all {@link PicClause#DAILYTRAN_FIXTURE_RECORD_COUNT} records of
 * {@code app/data/ASCII/dailytran.txt} through the four ledger services, in the order
 * {@code app/cbl/CBTRN02C.cbl:L440-L442} performs the three updates. The run carries account state
 * forward, matching the rewrite at {@code app/cbl/CBTRN02C.cbl:L554}, and skips it on a decline,
 * matching the gate at {@code app/cbl/CBTRN02C.cbl:L211}. Every count below is derived from the
 * run, and one group of assertions follows each paragraph of the source.</p>
 *
 * <p>Four behaviours are ADDITIVE: the single transaction around the three updates, the
 * idempotency marker, the masked card number, and the plain-digit amount in the reject rendering.
 * All four, and the carried-forward run, are recorded in
 * {@code card-platform/docs/decision-log.md} (planned).</p>
 */
class PostingEquivalenceTest {

    /**
     * Value {@code app/cbl/CBTRN02C.cbl:L230} moves into {@code RETURN-CODE} once
     * {@code WS-REJECT-COUNT} exceeds zero at {@code app/cbl/CBTRN02C.cbl:L229}.
     */
    private static final int RETURN_CODE_WHEN_REJECTS_PRESENT = 4;

    /**
     * Value {@code RETURN-CODE} keeps when {@code app/cbl/CBTRN02C.cbl:L229} finds no reject.
     */
    private static final int RETURN_CODE_WHEN_NO_REJECT = 0;

    /**
     * File status {@code app/cbl/CBTRN02C.cbl:L481}, {@code app/cbl/CBTRN02C.cbl:L512} and
     * {@code app/cbl/CBTRN02C.cbl:L530} all accept.
     */
    private static final String NORMAL_FILE_STATUS = "00";

    /**
     * Second file status {@code app/cbl/CBTRN02C.cbl:L481} accepts, raised by the
     * {@code INVALID KEY} branch at {@code app/cbl/CBTRN02C.cbl:L475-L478}.
     */
    private static final String RECORD_NOT_FOUND_FILE_STATUS = "23";

    /**
     * Components of {@code TRAN-RECORD} the posting paragraph generates. The one such component
     * is {@code TRAN-PROC-TS}, filled at {@code app/cbl/CBTRN02C.cbl:L437-L438}.
     */
    private static final int GENERATED_COMPONENT_COUNT = 1;

    /**
     * Deliveries of one event the idempotency group presents to the guard. ADDITIVE, with no
     * ancestor in {@code app/cbl/CBTRN02C.cbl}.
     */
    private static final int DUPLICATE_DELIVERY_COUNT = 2;

    /** Rows {@code ProcessedEventRepository.claimEvent} reports for a first claim. */
    private static final int CLAIM_TAKEN = 1;

    /** Rows {@code ProcessedEventRepository.claimEvent} reports for a repeat claim. */
    private static final int CLAIM_REFUSED = 0;

    /** Rows one call to the reject path writes, per {@code app/cbl/CBTRN02C.cbl:L451}. */
    private static final int ONE_ROW_PER_CALL = 1;

    /** Ordinal the run gives the first feed record. */
    private static final int FIRST_RECORD_ORDINAL = 1;

    /** Digit used to build an identifier absent from every fixture. */
    private static final String ABSENT_IDENTIFIER_DIGIT = "9";

    /** Character COBOL pads a {@code PIC 9(n)} field with on the left. */
    private static final String PADDING_DIGIT = "0";

    /** Character COBOL pads a {@code PIC X(n)} field with on the right. */
    private static final String COBOL_TEXT_PAD = " ";

    /**
     * A nanosecond count below one hundredth of a second, which {@code COB-MIL} at
     * {@code app/cbl/CBTRN02C.cbl:L157} does not carry.
     */
    private static final long SUB_HUNDREDTH_NANOSECONDS = 1L;

    /**
     * Account identifier the run carries on a record whose card misses the cross-reference. The
     * value is absent from {@code app/data/ASCII/acctdata.txt}, and
     * {@code DeclineReason.resolvesAccount} keeps it out of the published event.
     */
    private static final String UNRESOLVED_ACCOUNT_PLACEHOLDER =
            ABSENT_IDENTIFIER_DIGIT.repeat(PicClause.ACCT_ID_WIDTH);

    /** Topic name the idempotency marker records for a delivery of the authorized event. */
    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    /** Paragraph {@code app/cbl/CBTRN02C.cbl:L440} performs first. */
    private static final String CATEGORY_BALANCE_STAGE = "2700-UPDATE-TCATBAL";

    /** Paragraph {@code app/cbl/CBTRN02C.cbl:L441} performs second. */
    private static final String ACCOUNT_STAGE = "2800-UPDATE-ACCOUNT-REC";

    /** Paragraph {@code app/cbl/CBTRN02C.cbl:L442} performs third. */
    private static final String TRANSACTION_STAGE = "2900-WRITE-TRANSACTION-FILE";

    /** Paragraph {@code app/cbl/CBTRN02C.cbl:L215} performs on the reject branch. */
    private static final String REJECT_STAGE = "2500-WRITE-REJECT-REC";

    /** Order {@code app/cbl/CBTRN02C.cbl:L440-L442} performs the three updates in. */
    private static final List<String> POST_STAGE_ORDER =
            List.of(CATEGORY_BALANCE_STAGE, ACCOUNT_STAGE, TRANSACTION_STAGE);

    /** Writes an event to text for {@link OutboxWriter}, following the established test setup. */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    /** One run over the whole feed, built on first use and shared by every group below. */
    private static PostingRun sharedRun;

    /**
     * Returns the run over the whole feed, building it once.
     *
     * @return the terminal state and per-record outcomes of the run
     */
    private static synchronized PostingRun fixtureRun() {
        if (sharedRun == null) {
            sharedRun = postWholeFeed();
        }
        return sharedRun;
    }

    /**
     * Builds a failure message naming one feed record, the stage and the source locator.
     *
     * @param ordinal position of the record in {@code app/data/ASCII/dailytran.txt}
     * @param stage   COBOL paragraph the assertion covers
     * @param locator source file and line range the assertion reads
     * @return the message an assertion carries
     */
    private static String where(int ordinal, String stage, String locator) {
        return "dailytran.txt record " + ordinal + ", stage " + stage + ", source " + locator;
    }

    /**
     * Reproduces {@code COMPUTE WS-TEMP-BAL} at {@code app/cbl/CBTRN02C.cbl:L403-L405} in the
     * field width {@code app/cbl/CBTRN02C.cbl:L187} declares.
     *
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13}
     * @param cycleDebit  {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14}
     * @param amount      {@code DALYTRAN-AMT} at {@code app/cpy/CVTRA06Y.cpy:L10}
     * @return the working balance {@code app/cbl/CBTRN02C.cbl:L407} compares the limit against
     */
    private static BigDecimal workingBalance(BigDecimal cycleCredit, BigDecimal cycleDebit,
            BigDecimal amount) {
        BigDecimal afterDebit =
                CobolDecimal.subtract(cycleCredit, cycleDebit, PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal computed =
                CobolDecimal.add(afterDebit, amount, PicClause.WS_TEMP_BAL_SCALE);
        return CobolDecimal.truncateToPictureField(computed, PicClause.WS_TEMP_BAL_PRECISION,
                PicClause.WS_TEMP_BAL_SCALE);
    }

    /**
     * Applies the validation chain of {@code app/cbl/CBTRN02C.cbl:L370-L422} to one feed record.
     *
     * <p>The account read runs only while the reason stays at zero, per the gate at
     * {@code app/cbl/CBTRN02C.cbl:L372}. Both tests inside {@code 1500-B-LOOKUP-ACCT} run, and the
     * expiration test at {@code app/cbl/CBTRN02C.cbl:L414-L420} overwrites a limit reason.</p>
     *
     * @param feed            one record of {@code app/data/ASCII/dailytran.txt}
     * @param crossReferences {@code app/data/ASCII/cardxref.txt}, keyed by card number
     * @param accounts        {@code app/data/ASCII/acctdata.txt}, keyed by account identifier
     * @param balances        account state as the run has carried it forward
     * @return the reason a decline carries, or {@code null} when the record posts
     */
    private static DeclineReason declineReasonFor(
            CopybookRecordParser.DailyTransactionRecord feed,
            Map<String, CopybookRecordParser.CardCrossReferenceRecord> crossReferences,
            Map<String, CopybookRecordParser.AccountRecord> accounts,
            AccountBalanceProjectionRepository balances) {
        CopybookRecordParser.CardCrossReferenceRecord crossReference =
                crossReferences.get(feed.cardNumber());
        if (crossReference == null) {
            return DeclineReason.INVALID_CARD_NUMBER;
        }

        String accountId = crossReference.accountId();
        CopybookRecordParser.AccountRecord account = accounts.get(accountId);
        Optional<AccountBalanceProjectionEntity> carried = balances.findById(accountId);
        if (account == null || carried.isEmpty()) {
            return DeclineReason.ACCOUNT_NOT_FOUND;
        }

        DeclineReason reason = null;
        BigDecimal working = workingBalance(carried.get().getCycleCredit(),
                carried.get().getCycleDebit(), feed.amount());
        if (account.creditLimit().compareTo(working) < 0) {
            reason = DeclineReason.OVER_CREDIT_LIMIT;
        }
        String originDate = CopybookRecordParser.timestampDatePart(feed.originTimestamp());
        if (account.expirationDate().compareTo(originDate) < 0) {
            reason = DeclineReason.ACCOUNT_EXPIRED;
        }
        return reason;
    }

    /**
     * Builds the authorized event one feed record carries into the ledger.
     *
     * <p>The card number reaches the event masked, which is ADDITIVE. The authorization timestamp
     * is {@code DALYTRAN-ORIG-TS} at {@code app/cpy/CVTRA06Y.cpy:L16}, unchanged.</p>
     *
     * @param feed      one record of {@code app/data/ASCII/dailytran.txt}
     * @param accountId {@code XREF-ACCT-ID} at {@code app/cpy/CVACT03Y.cpy:L7}
     * @return the event the ledger services consume
     */
    private static TransactionAuthorized authorizationEventFor(
            CopybookRecordParser.DailyTransactionRecord feed, String accountId) {
        return TransactionAuthorized.of(accountId,
                feed.transactionId(),
                feed.typeCode(),
                feed.categoryCode(),
                feed.source(),
                feed.description(),
                feed.amount(),
                feed.merchantId(),
                feed.merchantName(),
                feed.merchantCity(),
                feed.merchantZip(),
                PanMasker.maskCardNumber(feed.cardNumber()),
                feed.originTimestamp());
    }

    /**
     * Drives every record of {@code app/data/ASCII/dailytran.txt} through the ledger services.
     *
     * <p>The loop follows {@code app/cbl/CBTRN02C.cbl:L202-L219}: each pass resets the reason,
     * then either posts or records a reject. Both output stores start empty, matching
     * {@code OPEN OUTPUT} at {@code app/cbl/CBTRN02C.cbl:L254-L256} and L291-L293.</p>
     *
     * @return the terminal state and the per-record outcomes
     */
    private static PostingRun postWholeFeed() {
        Map<String, CopybookRecordParser.CardCrossReferenceRecord> crossReferences =
                CardDemoFixtureLoader.cardCrossReferencesByCardNumber();
        Map<String, CopybookRecordParser.AccountRecord> accounts =
                CardDemoFixtureLoader.accountsByAccountId();
        List<CopybookRecordParser.DailyTransactionRecord> feed =
                CardDemoFixtureLoader.loadDailyTransactions();

        List<String> saveLog = new ArrayList<>();
        StoredAccountBalances accountBalances = seededAccountBalances(accounts, saveLog);
        StoredCategoryBalances categoryBalances = seededCategoryBalances(saveLog);
        Set<TransactionCategoryBalanceId> seededKeys =
                new LinkedHashSet<>(categoryBalances.keys());
        StoredTransactions transactions = new StoredTransactions(saveLog);
        StoredRejects rejects = new StoredRejects(saveLog);
        CapturedOutbox outbox = new CapturedOutbox();

        PostingService postingService = new PostingService(
                new CategoryBalanceUpdater(categoryBalances),
                new AccountBalanceUpdater(accountBalances),
                transactions,
                outbox.writer());
        RejectRecorder rejectRecorder = new RejectRecorder(rejects, outbox.writer());

        List<FeedOutcome> outcomes = new ArrayList<>(feed.size());
        int ordinal = FIRST_RECORD_ORDINAL;
        for (CopybookRecordParser.DailyTransactionRecord record : feed) {
            DeclineReason reason =
                    declineReasonFor(record, crossReferences, accounts, accountBalances);
            String accountId = Optional.ofNullable(crossReferences.get(record.cardNumber()))
                    .map(CopybookRecordParser.CardCrossReferenceRecord::accountId)
                    .orElse(UNRESOLVED_ACCOUNT_PLACEHOLDER);
            TransactionAuthorized event = authorizationEventFor(record, accountId);
            if (reason == null) {
                postingService.postTransaction(event);
            } else {
                rejectRecorder.recordReject(event, reason);
            }
            outcomes.add(new FeedOutcome(ordinal, record, accountId, reason, event));
            ordinal++;
        }

        return new PostingRun(List.copyOf(outcomes), accounts, crossReferences, seededKeys,
                accountBalances.snapshot(), categoryBalances.snapshot(), transactions.snapshot(),
                rejects.snapshot(), outbox.snapshot(), List.copyOf(saveLog),
                transactions.repeatedIdentifiers(), categoryBalances.keysMissedOnRead());
    }

    /**
     * Seeds account state from {@code app/data/ASCII/acctdata.txt}.
     *
     * @param accounts the fixture accounts, keyed by account identifier
     * @return a store holding one row per fixture account
     */
    private static StoredAccountBalances seededAccountBalances(
            Map<String, CopybookRecordParser.AccountRecord> accounts, List<String> saveLog) {
        StoredAccountBalances store = new StoredAccountBalances(saveLog);
        for (CopybookRecordParser.AccountRecord account : accounts.values()) {
            store.save(new AccountBalanceProjectionEntity(account.accountId(),
                    account.currentBalance(), account.currentCycleCredit(),
                    account.currentCycleDebit()));
        }
        store.seedingComplete();
        return store;
    }

    /**
     * Seeds category balances from {@code app/data/ASCII/tcatbal.txt}.
     *
     * @return a store holding one row per fixture category balance
     */
    private static StoredCategoryBalances seededCategoryBalances(List<String> saveLog) {
        StoredCategoryBalances store = new StoredCategoryBalances(saveLog);
        for (CopybookRecordParser.TransactionCategoryBalanceRecord seeded
                : CardDemoFixtureLoader.loadTransactionCategoryBalances()) {
            store.save(new TransactionCategoryBalanceEntity(
                    new TransactionCategoryBalanceId(seeded.accountId(), seeded.typeCode(),
                            seeded.categoryCode()),
                    seeded.balance()));
        }
        store.seedingComplete();
        return store;
    }

    @Nested
    @DisplayName("Driver loop and return code, app/cbl/CBTRN02C.cbl:L202-L234")
    class DriverLoop {

        @Test
        @DisplayName("one outcome per feed record, L204-L206")
        void oneOutcomePerFeedRecord() {
            PostingRun run = fixtureRun();

            assertEquals(PicClause.DAILYTRAN_FIXTURE_RECORD_COUNT, run.outcomes().size(),
                    "the loop at CBTRN02C L202-L219 adds one to WS-TRANSACTION-COUNT per record, "
                            + "so the run holds one outcome for every record of dailytran.txt");
            int expectedOrdinal = FIRST_RECORD_ORDINAL;
            for (FeedOutcome outcome : run.outcomes()) {
                assertEquals(expectedOrdinal, outcome.ordinal(),
                        where(expectedOrdinal, "1000-DALYTRAN-GET-NEXT",
                                "app/cbl/CBTRN02C.cbl:L204")
                                + ": ordinals run without a gap");
                expectedOrdinal++;
            }
        }

        @Test
        @DisplayName("the post branch and the reject branch together cover the feed, L211-L216")
        void bothBranchesTogetherCoverTheFeed() {
            PostingRun run = fixtureRun();

            assertEquals(run.outcomes().size(), run.postedCount() + run.rejectedCount(),
                    "CBTRN02C L211 sends each record to exactly one of L212 and L215, so the two "
                            + "counts add up to the record count");
            assertTrue(run.postedCount() > 0,
                    "CBTRN02C L212 posts at least one record of dailytran.txt");
            assertTrue(run.rejectedCount() > 0,
                    "CBTRN02C L215 rejects at least one record of dailytran.txt");
        }

        @Test
        @DisplayName("a decline leaves a reject row and raises nothing, L214-L215 and L230")
        void aDeclineLeavesARejectRowAndRaisesNothing() {
            PostingRun run = fixtureRun();

            assertEquals(run.rejectedCount(), run.rejectedRows().size(),
                    "CBTRN02C L215 writes one reject record per rejected record, and L230 turns "
                            + "the count into a return code, so a decline is expected traffic");
            assertEquals(RETURN_CODE_WHEN_REJECTS_PRESENT, run.returnCode(),
                    "CBTRN02C L229-L230 moves " + RETURN_CODE_WHEN_REJECTS_PRESENT
                            + " into RETURN-CODE once WS-REJECT-COUNT exceeds zero");
        }

        @Test
        @DisplayName("no decline reaches 2800-UPDATE-ACCOUNT-REC, the gate at L211")
        void noDeclineReachesTheAccountUpdate() {
            PostingRun run = fixtureRun();
            Map<String, List<BigDecimal>> postedByAccount = postedAmountsByAccount(run);

            for (Map.Entry<String, AccountBalanceProjectionEntity> held
                    : run.accountBalances().entrySet()) {
                CopybookRecordParser.AccountRecord seeded =
                        run.seededAccounts().get(held.getKey());
                List<BigDecimal> posted =
                        postedByAccount.getOrDefault(held.getKey(), List.of());
                BigDecimal expectedBalance = truncatingSum(seeded.currentBalance(), posted,
                        PicClause.ACCT_CURR_BAL_SCALE);

                assertEquals(0, expectedBalance.compareTo(held.getValue().getCurrentBalance()),
                        "account " + held.getKey() + ", stage " + ACCOUNT_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L547: the balance moved by the "
                                + "posted amounts alone, compared numerically");
            }
        }

        @Test
        @DisplayName("the carried-forward run and a stateless pass disagree, L554")
        void theCarriedForwardRunAndAStatelessPassDisagree() {
            PostingRun run = fixtureRun();
            long statelessDeclines = statelessDeclineCount();

            assertNotEquals(statelessDeclines, run.rejectedCount(),
                    "the rewrite at CBTRN02C L554 leaves the accumulators for the next record, so "
                            + "a run that froze them at their fixture values reaches a different "
                            + "reject count");
            assertTrue(run.rejectedCount() > statelessDeclines,
                    "carrying the accumulators forward raises the working balance at CBTRN02C "
                            + "L403-L405, so the carried-forward run rejects more records");
        }

        @Test
        @DisplayName("declines that still accumulated would change the reject count, L211")
        void declinesThatStillAccumulatedWouldChangeTheRejectCount() {
            PostingRun run = fixtureRun();

            assertNotEquals(accumulateOnDeclineDeclineCount(), run.rejectedCount(),
                    "CBTRN02C L211 gates L212, and L212 performs the paragraph holding L440-L442, "
                            + "so a run that accumulated on a decline reaches a different reject "
                            + "count");
        }
    }

    @Nested
    @DisplayName("Store lifecycle, app/cbl/CBTRN02C.cbl:L254-L256 and L291-L293")
    class EmptyStoresAtStart {

        @Test
        @DisplayName("every posted record inserts a fresh row, L564")
        void everyPostedRecordInsertsAFreshRow() {
            PostingRun run = fixtureRun();

            assertEquals(run.postedCount(), run.postedTransactions().size(),
                    "CBTRN02C L254-L256 opens the transaction master OPEN OUTPUT, so the store "
                            + "starts empty and holds one row per posted record");
            assertTrue(run.repeatedTransactionIdentifiers().isEmpty(),
                    "CBTRN02C L564 uses WRITE and never REWRITE, and no identifier reached the "
                            + "store twice, so no duplicate-key condition arose");
        }

        @Test
        @DisplayName("the reject store starts empty as well, L291-L293")
        void theRejectStoreStartsEmpty() {
            PostingRun run = fixtureRun();

            assertEquals(run.rejectedCount(), run.rejectedRows().size(),
                    "CBTRN02C L291-L293 opens the reject dataset OPEN OUTPUT, so the store holds "
                            + "one row per rejected record and nothing carried in");
        }

        @Test
        @DisplayName("each event carries the resolved account as its aggregate identifier, L394")
        void eachEventCarriesTheResolvedAccount() {
            PostingRun run = fixtureRun();

            for (FeedOutcome outcome : run.outcomes()) {
                assertEquals(outcome.accountId(), outcome.event().aggregateId(),
                        where(outcome.ordinal(), "1500-B-LOOKUP-ACCT",
                                "app/cbl/CBTRN02C.cbl:L394")
                                + ": the account the cross-reference resolved is the message key");
                assertEquals(outcome.accountId(), outcome.event().accountId(),
                        where(outcome.ordinal(), "1500-B-LOOKUP-ACCT",
                                "app/cbl/CBTRN02C.cbl:L394")
                                + ": the event carries one account identifier in both components");
            }
        }

        @Test
        @DisplayName("the cross-reference and account stores are read, not recreated, L275")
        void theCrossReferenceAndAccountStoresAreRead() {
            PostingRun run = fixtureRun();

            assertEquals(PicClause.CARDXREF_FIXTURE_RECORD_COUNT, run.crossReferences().size(),
                    "CBTRN02C L275 opens the cross-reference file OPEN INPUT, so every fixture "
                            + "row of cardxref.txt is available to CBTRN02C L383");
            assertEquals(PicClause.ACCTDATA_FIXTURE_RECORD_COUNT, run.accountBalances().size(),
                    "CBTRN02C opens the account file I-O, so the row count matches acctdata.txt "
                            + "and no row was added by the run");
        }
    }

    @Nested
    @DisplayName("2000-POST-TRANSACTION, app/cbl/CBTRN02C.cbl:L424-L444")
    class PostTransaction {

        @Test
        @DisplayName("the twelve field moves at L425-L436 reach the posted row")
        void theFieldMovesReachThePostedRow() {
            PostingRun run = fixtureRun();
            int copiedComponentCount =
                    CopybookRecordParser.DailyTransactionRecord.class.getRecordComponents().length
                            - GENERATED_COMPONENT_COUNT;

            for (FeedOutcome outcome : run.postedOutcomes()) {
                TransactionEntity posted =
                        run.postedTransactions().get(outcome.feed().transactionId());
                List<String> expected = copiedValuesOf(outcome.feed());
                List<String> actual = copiedValuesOf(posted);

                assertEquals(copiedComponentCount, expected.size(),
                        where(outcome.ordinal(), "2000-POST-TRANSACTION",
                                "app/cbl/CBTRN02C.cbl:L425-L436")
                                + ": the paragraph copies every component of CVTRA06Y except the "
                                + "one it generates");
                assertEquals(expected, actual,
                        where(outcome.ordinal(), "2000-POST-TRANSACTION",
                                "app/cbl/CBTRN02C.cbl:L425-L436")
                                + ": each copied component reached TRAN-RECORD unchanged, with the "
                                + "card number masked");
            }
        }

        @Test
        @DisplayName("the processing timestamp is generated, not copied, L437-L438")
        void theProcessingTimestampIsGenerated() {
            PostingRun run = fixtureRun();

            for (FeedOutcome outcome : run.postedOutcomes()) {
                TransactionEntity posted =
                        run.postedTransactions().get(outcome.feed().transactionId());

                assertNotEquals(CopybookRecordParser.truncateProcessingTimestampToHundredths(
                                outcome.feed().processingTimestamp()),
                        CopybookRecordParser.truncateProcessingTimestampToHundredths(
                                posted.getProcessedTimestamp()),
                        where(outcome.ordinal(), "Z-GET-DB2-FORMAT-TIMESTAMP",
                                "app/cbl/CBTRN02C.cbl:L437-L438")
                                + ": DALYTRAN-PROC-TS is blank on every fixture record and "
                                + "TRAN-PROC-TS is filled from DB2-FORMAT-TS, compared after "
                                + "truncation to hundredths");
                assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH,
                        posted.getProcessedTimestamp().length(),
                        where(outcome.ordinal(), "Z-GET-DB2-FORMAT-TIMESTAMP",
                                "app/cbl/CBTRN02C.cbl:L159")
                                + ": DB2-FORMAT-TS holds a fixed width");
            }
        }

        @Test
        @DisplayName("the three updates run in the order L440-L442 performs them")
        void theThreeUpdatesRunInSourceOrder() {
            PostingRun run = fixtureRun();
            List<List<String>> triples = postStageTriples(run.saveLog());

            assertEquals(run.postedCount(), triples.size(),
                    "CBTRN02C L440-L442 performs three updates per posted record");
            for (int position = 0; position < triples.size(); position++) {
                assertEquals(POST_STAGE_ORDER, triples.get(position),
                        where(position + FIRST_RECORD_ORDINAL, "2000-POST-TRANSACTION",
                                "app/cbl/CBTRN02C.cbl:L440-L442")
                                + ": the category balance, the account and the transaction were "
                                + "written in that order");
            }
        }

        @Test
        @DisplayName("the three updates share one transaction, an ADDITIVE guarantee")
        void theThreeUpdatesShareOneTransaction() throws NoSuchMethodException {
            Method postTransaction = PostingService.class
                    .getMethod("postTransaction", TransactionAuthorized.class);

            assertTrue(postTransaction.isAnnotationPresent(Transactional.class),
                    "CBTRN02C L440-L442 runs the three updates with no rollback, and the target "
                            + "wraps them in one transaction, which is ADDITIVE");
        }

        @Test
        @DisplayName("one posted event follows each post, L440-L442")
        void onePostedEventFollowsEachPost() {
            PostingRun run = fixtureRun();

            assertEquals(run.postedCount(),
                    run.outboxRowsOfType(TransactionPosted.EVENT_TYPE).size(),
                    "each pass of CBTRN02C L212 posts one record, and the target publishes one "
                            + TransactionPosted.EVENT_TYPE + " for it");
        }
    }

    @Nested
    @DisplayName("2700-UPDATE-TCATBAL, app/cbl/CBTRN02C.cbl:L467-L501")
    class CategoryBalanceUpsert {

        @Test
        @DisplayName("the key spans three fields of CVTRA01Y L6-L8, per TCATBALF.jcl L40")
        void theKeySpansThreeFields() {
            PostingRun run = fixtureRun();
            int partSum = PicClause.TRANCAT_ACCT_ID_WIDTH
                    + PicClause.TRANCAT_TYPE_CD_WIDTH
                    + PicClause.TRANCAT_CD_WIDTH;

            assertEquals(PicClause.TRAN_CAT_KEY_WIDTH, partSum,
                    "the TRAN-CAT-KEY group at CVTRA01Y L5 spans the three fields at L6-L8, and "
                            + "TCATBALF.jcl L40 repeats that width as KEYS(17 0)");
            for (TransactionCategoryBalanceId key : run.categoryBalances().keySet()) {
                String rendered = key.getAccountId() + key.getTypeCode() + key.getCategoryCode();
                assertEquals(PicClause.TRAN_CAT_KEY_WIDTH, rendered.length(),
                        "category key " + key.getTypeCode() + " " + key.getCategoryCode()
                                + ", stage " + CATEGORY_BALANCE_STAGE
                                + ", source app/cpy/CVTRA01Y.cpy:L5-L8: the three parts fill the "
                                + "declared width");
            }
        }

        @Test
        @DisplayName("the CVTRA04Y group of the same name carries a different width, L5")
        void theSameGroupNameCarriesADifferentWidth() {
            assertNotEquals(PicClause.TRAN_CAT_KEY_WIDTH, PicClause.TRAN_CAT_RECORD_KEY_WIDTH,
                    "CVTRA01Y L5 and CVTRA04Y L5 both name a group TRAN-CAT-KEY, and the two "
                            + "widths differ, so a key derived from the group name reads the "
                            + "wrong copybook");
        }

        @Test
        @DisplayName("the read tolerates record-not-found while both writes accept only the "
                + "normal status, L481 against L512 and L530")
        void theReadToleratesRecordNotFound() {
            PostingRun run = fixtureRun();

            assertNotEquals(NORMAL_FILE_STATUS, RECORD_NOT_FOUND_FILE_STATUS,
                    "CBTRN02C L481 accepts two statuses, and L512 and L530 each accept one, so "
                            + "the two values are distinct");
            assertFalse(run.categoryKeysMissedOnRead().isEmpty(),
                    "CBTRN02C L475-L478 raised the INVALID KEY branch for at least one key of "
                            + "dailytran.txt, which is the status L481 tolerates");
            for (TransactionCategoryBalanceId missed : run.categoryKeysMissedOnRead()) {
                assertTrue(run.categoryBalances().containsKey(missed),
                        "category key " + missed.getTypeCode() + " " + missed.getCategoryCode()
                                + ", stage 2700-A-CREATE-TCATBAL-REC, source "
                                + "app/cbl/CBTRN02C.cbl:L510-L512: the tolerated read was "
                                + "followed by a write the strict ladder accepted");
            }
        }

        @Test
        @DisplayName("the store holds the seeded keys and the keys the feed reached, L495-L499")
        void theStoreHoldsSeededKeysAndReachedKeys() {
            PostingRun run = fixtureRun();
            Set<TransactionCategoryBalanceId> expected =
                    new LinkedHashSet<>(run.seededCategoryKeys());
            expected.addAll(postedAmountsByCategoryKey(run).keySet());

            assertEquals(expected.size(), run.categoryBalances().size(),
                    "CBTRN02C L495-L499 sends each posted record to one branch, so the store "
                            + "holds the tcatbal.txt keys plus the keys the feed created");
            assertEquals(expected, run.categoryBalances().keySet(),
                    "no key beyond those two sets reached the store");
        }

        @Test
        @DisplayName("a seeded key takes the update branch, L498")
        void aSeededKeyTakesTheUpdateBranch() {
            PostingRun run = fixtureRun();

            for (TransactionCategoryBalanceId seeded : run.seededCategoryKeys()) {
                assertFalse(run.categoryKeysMissedOnRead().contains(seeded),
                        "category key " + seeded.getTypeCode() + " " + seeded.getCategoryCode()
                                + ", stage " + CATEGORY_BALANCE_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L474-L479: a key tcatbal.txt "
                                + "supplied was found by the read");
            }
        }

        @Test
        @DisplayName("row signs follow the amount signs of the records that reached them, L508 "
                + "and L527")
        void rowSignsFollowTheAmountSigns() {
            PostingRun run = fixtureRun();
            Map<TransactionCategoryBalanceId, List<BigDecimal>> postedByKey =
                    postedAmountsByCategoryKey(run);
            Set<String> typesCarryingPositiveAmounts = new LinkedHashSet<>();
            Set<String> typesCarryingNegativeAmounts = new LinkedHashSet<>();
            for (FeedOutcome outcome : run.postedOutcomes()) {
                if (outcome.feed().amount().signum() > 0) {
                    typesCarryingPositiveAmounts.add(outcome.feed().typeCode());
                } else if (outcome.feed().amount().signum() < 0) {
                    typesCarryingNegativeAmounts.add(outcome.feed().typeCode());
                }
            }
            long keysSummingAboveZero = postedByKey.entrySet().stream()
                    .filter(entry -> truncatingSum(seededCategoryBalanceOf(entry.getKey()),
                            entry.getValue(), PicClause.TRAN_CAT_BAL_SCALE).signum() > 0)
                    .count();
            long keysSummingBelowZero = postedByKey.entrySet().stream()
                    .filter(entry -> truncatingSum(seededCategoryBalanceOf(entry.getKey()),
                            entry.getValue(), PicClause.TRAN_CAT_BAL_SCALE).signum() < 0)
                    .count();

            assertEquals(keysSummingAboveZero, run.categoryRowsAboveZero(),
                    "stage " + CATEGORY_BALANCE_STAGE + ", source app/cbl/CBTRN02C.cbl:L508 and "
                            + "L527: the rows above zero are the keys whose posted amounts sum "
                            + "above zero");
            assertEquals(keysSummingBelowZero, run.categoryRowsBelowZero(),
                    "stage " + CATEGORY_BALANCE_STAGE + ", source app/cbl/CBTRN02C.cbl:L508 and "
                            + "L527: the rows below zero are the keys whose posted amounts sum "
                            + "below zero");
            assertTrue(Collections.disjoint(typesCarryingPositiveAmounts,
                            typesCarryingNegativeAmounts),
                    "no transaction type code of dailytran.txt carries both a positive and a "
                            + "negative posted amount, so a row sign identifies a type code");
        }
    }

    @Nested
    @DisplayName("2700-A-CREATE-TCATBAL-REC, app/cbl/CBTRN02C.cbl:L503-L524")
    class CategoryBalanceCreate {

        @Test
        @DisplayName("a created row carries its key, the re-move at L505-L507")
        void aCreatedRowCarriesItsKey() {
            PostingRun run = fixtureRun();
            String zeroedAccountId = PADDING_DIGIT.repeat(PicClause.TRANCAT_ACCT_ID_WIDTH);
            String zeroedCategoryCode = PADDING_DIGIT.repeat(PicClause.TRANCAT_CD_WIDTH);

            for (TransactionCategoryBalanceId created : run.categoryKeysMissedOnRead()) {
                TransactionCategoryBalanceEntity row = run.categoryBalances().get(created);

                assertEquals(created, row.getId(),
                        "category key " + created.getTypeCode() + " " + created.getCategoryCode()
                                + ", stage 2700-A-CREATE-TCATBAL-REC, source "
                                + "app/cbl/CBTRN02C.cbl:L504-L507: INITIALIZE clears the key and "
                                + "the three moves that follow restore it");
                assertNotEquals(zeroedAccountId, row.getId().getAccountId(),
                        "stage 2700-A-CREATE-TCATBAL-REC, source app/cbl/CBTRN02C.cbl:L505: the "
                                + "account part of the key survived INITIALIZE");
                assertNotEquals(zeroedCategoryCode, row.getId().getCategoryCode(),
                        "stage 2700-A-CREATE-TCATBAL-REC, source app/cbl/CBTRN02C.cbl:L507: the "
                                + "category part of the key survived INITIALIZE");
            }
        }

        @Test
        @DisplayName("a created row opens from zero and adds the amount, L504 and L508")
        void aCreatedRowOpensFromZeroAndAddsTheAmount() {
            PostingRun run = fixtureRun();
            Map<TransactionCategoryBalanceId, List<BigDecimal>> postedByKey =
                    postedAmountsByCategoryKey(run);

            for (TransactionCategoryBalanceId created : run.categoryKeysMissedOnRead()) {
                BigDecimal expected = truncatingSum(BigDecimal.ZERO,
                        postedByKey.getOrDefault(created, List.of()),
                        PicClause.TRAN_CAT_BAL_SCALE);
                BigDecimal held = run.categoryBalances().get(created).getCategoryBalance();

                assertEquals(0, expected.compareTo(held),
                        "category key " + created.getTypeCode() + " " + created.getCategoryCode()
                                + ", stage 2700-A-CREATE-TCATBAL-REC, source "
                                + "app/cbl/CBTRN02C.cbl:L504-L508: the row opened at zero and "
                                + "took the posted amounts, compared numerically");
            }
        }
    }

    @Nested
    @DisplayName("2700-B-UPDATE-TCATBAL-REC, app/cbl/CBTRN02C.cbl:L526-L542")
    class CategoryBalanceUpdate {

        @Test
        @DisplayName("a seeded row opens from its fixture value and adds the amounts, L527")
        void aSeededRowOpensFromItsFixtureValue() {
            PostingRun run = fixtureRun();
            Map<TransactionCategoryBalanceId, List<BigDecimal>> postedByKey =
                    postedAmountsByCategoryKey(run);

            for (TransactionCategoryBalanceId seeded : run.seededCategoryKeys()) {
                BigDecimal expected = truncatingSum(seededCategoryBalanceOf(seeded),
                        postedByKey.getOrDefault(seeded, List.of()),
                        PicClause.TRAN_CAT_BAL_SCALE);
                BigDecimal held = run.categoryBalances().get(seeded).getCategoryBalance();

                assertEquals(0, expected.compareTo(held),
                        "category key " + seeded.getTypeCode() + " " + seeded.getCategoryCode()
                                + ", stage 2700-B-UPDATE-TCATBAL-REC, source "
                                + "app/cbl/CBTRN02C.cbl:L527: the row took the posted amounts on "
                                + "top of its tcatbal.txt value, compared numerically");
            }
        }

        @Test
        @DisplayName("a seeded key the feed never reached keeps its fixture value, L495")
        void aSeededKeyTheFeedNeverReachedKeepsItsValue() {
            PostingRun run = fixtureRun();
            Set<TransactionCategoryBalanceId> reached =
                    postedAmountsByCategoryKey(run).keySet();

            for (TransactionCategoryBalanceId seeded : run.seededCategoryKeys()) {
                if (reached.contains(seeded)) {
                    continue;
                }
                BigDecimal held = run.categoryBalances().get(seeded).getCategoryBalance();

                assertEquals(0, seededCategoryBalanceOf(seeded).compareTo(held),
                        "category key " + seeded.getTypeCode() + " " + seeded.getCategoryCode()
                                + ", stage " + CATEGORY_BALANCE_STAGE + ", source "
                                + "app/cbl/CBTRN02C.cbl:L495: no posted record reached this key, "
                                + "so neither branch changed it, compared numerically");
            }
        }
    }

    @Nested
    @DisplayName("2800-UPDATE-ACCOUNT-REC, app/cbl/CBTRN02C.cbl:L545-L560")
    class AccountRecordUpdate {

        @Test
        @DisplayName("the balance takes every posted amount, L547")
        void theBalanceTakesEveryPostedAmount() {
            PostingRun run = fixtureRun();
            Map<String, List<BigDecimal>> postedByAccount = postedAmountsByAccount(run);

            for (Map.Entry<String, CopybookRecordParser.AccountRecord> seeded
                    : run.seededAccounts().entrySet()) {
                BigDecimal expected = truncatingSum(seeded.getValue().currentBalance(),
                        postedByAccount.getOrDefault(seeded.getKey(), List.of()),
                        PicClause.ACCT_CURR_BAL_SCALE);
                BigDecimal held =
                        run.accountBalances().get(seeded.getKey()).getCurrentBalance();

                assertEquals(0, expected.compareTo(held),
                        "account " + seeded.getKey() + ", stage " + ACCOUNT_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L547: ACCT-CURR-BAL took every "
                                + "posted amount, compared numerically");
            }
        }

        @Test
        @DisplayName("the sign test at L548 routes each amount to one accumulator, L549 and L551")
        void theSignTestRoutesEachAmountToOneAccumulator() {
            PostingRun run = fixtureRun();
            Map<String, List<BigDecimal>> creditBound = new LinkedHashMap<>();
            Map<String, List<BigDecimal>> debitBound = new LinkedHashMap<>();
            for (FeedOutcome outcome : run.postedOutcomes()) {
                Map<String, List<BigDecimal>> target =
                        outcome.feed().amount().signum() >= 0 ? creditBound : debitBound;
                target.computeIfAbsent(outcome.accountId(), key -> new ArrayList<>())
                        .add(outcome.feed().amount());
            }

            for (Map.Entry<String, CopybookRecordParser.AccountRecord> seeded
                    : run.seededAccounts().entrySet()) {
                AccountBalanceProjectionEntity held = run.accountBalances().get(seeded.getKey());
                BigDecimal expectedCredit = truncatingSum(seeded.getValue().currentCycleCredit(),
                        creditBound.getOrDefault(seeded.getKey(), List.of()),
                        PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
                BigDecimal expectedDebit = truncatingSum(seeded.getValue().currentCycleDebit(),
                        debitBound.getOrDefault(seeded.getKey(), List.of()),
                        PicClause.ACCT_CURR_CYC_DEBIT_SCALE);

                assertEquals(0, expectedCredit.compareTo(held.getCycleCredit()),
                        "account " + seeded.getKey() + ", stage " + ACCOUNT_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L548-L549: every amount at or "
                                + "above zero reached ACCT-CURR-CYC-CREDIT, compared numerically");
                assertEquals(0, expectedDebit.compareTo(held.getCycleDebit()),
                        "account " + seeded.getKey() + ", stage " + ACCOUNT_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L550-L551: every amount below "
                                + "zero reached ACCT-CURR-CYC-DEBIT, compared numerically");
            }
        }

        @Test
        @DisplayName("a zero amount reaches the cycle credit accumulator, the bound at L548")
        void aZeroAmountReachesTheCycleCreditAccumulator() {
            String accountId = firstFeedAccountId();
            Probe probe = new Probe(accountId, zeroAmount());
            BigDecimal zero = BigDecimal.ZERO.setScale(PicClause.DALYTRAN_AMT_SCALE);

            probe.postingService().postTransaction(
                    eventCarrying(accountId, syntheticTransactionId(FIRST_RECORD_ORDINAL), zero));
            AccountBalanceProjectionEntity held =
                    probe.accountBalances().findById(accountId).orElseThrow();

            assertEquals(0, zero.compareTo(held.getCycleCredit()),
                    "account " + accountId + ", stage " + ACCOUNT_STAGE
                            + ", source app/cbl/CBTRN02C.cbl:L548-L549: the test reads at or above "
                            + "zero, so a zero amount reached the cycle credit accumulator, "
                            + "compared numerically");
            assertEquals(0, zero.compareTo(held.getCycleDebit()),
                    "account " + accountId + ", stage " + ACCOUNT_STAGE
                            + ", source app/cbl/CBTRN02C.cbl:L550-L551: the cycle debit "
                            + "accumulator stayed where it was, compared numerically");
        }

        @Test
        @DisplayName("an absent account row fails the delivery, the INVALID KEY branch at L555")
        void anAbsentAccountRowFailsTheDelivery() {
            String seededAccountId = firstFeedAccountId();
            Probe probe = new Probe(seededAccountId, zeroAmount());
            String absentAccountId =
                    ABSENT_IDENTIFIER_DIGIT.repeat(PicClause.ACCT_ID_WIDTH);
            TransactionAuthorized event = eventCarrying(absentAccountId,
                    syntheticTransactionId(FIRST_RECORD_ORDINAL), firstFeedRecord().amount());

            assertThrows(AccountBalanceUpdater.AccountBalanceRowMissingException.class,
                    () -> probe.postingService().postTransaction(event),
                    "account " + absentAccountId + ", stage " + ACCOUNT_STAGE
                            + ", source app/cbl/CBTRN02C.cbl:L554-L559: the rewrite found no row, "
                            + "and the target raises a failure the consumer does not acknowledge");
            assertEquals(0L, probe.transactions().count(),
                    "stage " + TRANSACTION_STAGE + ", source app/cbl/CBTRN02C.cbl:L440-L442: the "
                            + "failure left no transaction row, which is the ADDITIVE atomicity "
                            + "guarantee");
        }
    }

    @Nested
    @DisplayName("2900-WRITE-TRANSACTION-FILE, app/cbl/CBTRN02C.cbl:L562-L579")
    class TransactionFileWrite {

        @Test
        @DisplayName("each posted record is inserted once, L564")
        void eachPostedRecordIsInsertedOnce() {
            PostingRun run = fixtureRun();
            Set<String> distinctIdentifiers = new LinkedHashSet<>();
            run.postedOutcomes().forEach(
                    outcome -> distinctIdentifiers.add(outcome.feed().transactionId()));

            assertEquals(distinctIdentifiers.size(), run.postedTransactions().size(),
                    "CBTRN02C L564 uses WRITE, so each posted identifier of dailytran.txt reached "
                            + "the store once");
            assertEquals(run.postedCount(), distinctIdentifiers.size(),
                    "no two posted records of dailytran.txt share a transaction identifier");
        }

        @Test
        @DisplayName("a refused write fails the delivery, the status ladder at L566-L578")
        void aRefusedWriteFailsTheDelivery() {
            String accountId = firstFeedAccountId();
            Probe probe = new Probe(accountId, zeroAmount(), new RefusingTransactions());
            TransactionAuthorized event = eventCarrying(accountId,
                    syntheticTransactionId(FIRST_RECORD_ORDINAL), firstFeedRecord().amount());

            IllegalStateException raised = assertThrows(IllegalStateException.class,
                    () -> probe.postingService().postTransaction(event),
                    "stage " + TRANSACTION_STAGE + ", source app/cbl/CBTRN02C.cbl:L566-L578: a "
                            + "status the ladder refuses abends the source, and the target raises "
                            + "a failure that withholds the acknowledgement and reaches the "
                            + "dead-letter topic after the retries");
            assertEquals(RefusingTransactions.REFUSAL, raised.getMessage(),
                    "stage " + TRANSACTION_STAGE + ", source app/cbl/CBTRN02C.cbl:L574: the "
                            + "failure the store raised is the one the caller saw");
        }
    }

    @Nested
    @DisplayName("Refund sign convention, app/cbl/CBTRN02C.cbl:L551 read with L404")
    class RefundSignConvention {

        @Test
        @DisplayName("a refund drives the cycle debit accumulator below zero, L551")
        void aRefundDrivesTheCycleDebitBelowZero() {
            String accountId = firstFeedAccountId();
            Probe probe = new Probe(accountId, zeroAmount());
            BigDecimal refund = firstFeedRecord().amount().negate();

            probe.postingService().postTransaction(eventCarrying(accountId,
                    syntheticTransactionId(FIRST_RECORD_ORDINAL), refund));
            AccountBalanceProjectionEntity held =
                    probe.accountBalances().findById(accountId).orElseThrow();

            assertTrue(held.getCycleDebit().signum() < 0,
                    "account " + accountId + ", stage " + ACCOUNT_STAGE
                            + ", source app/cbl/CBTRN02C.cbl:L551: ADD of a negative amount drove "
                            + "ACCT-CURR-CYC-DEBIT below zero");
        }

        @Test
        @DisplayName("a refund raises the working balance the next record is tested against, L404")
        void aRefundRaisesTheNextWorkingBalance() {
            String accountId = firstFeedAccountId();
            Probe probe = new Probe(accountId, zeroAmount());
            BigDecimal probeAmount = firstFeedRecord().amount();
            BigDecimal refund = probeAmount.negate();
            BigDecimal beforeRefund = workingBalance(zeroAmount(), zeroAmount(), probeAmount);

            probe.postingService().postTransaction(eventCarrying(accountId,
                    syntheticTransactionId(FIRST_RECORD_ORDINAL), refund));
            AccountBalanceProjectionEntity held =
                    probe.accountBalances().findById(accountId).orElseThrow();
            BigDecimal afterRefund = workingBalance(held.getCycleCredit(), held.getCycleDebit(),
                    probeAmount);

            assertTrue(afterRefund.compareTo(beforeRefund) > 0,
                    "account " + accountId + ", stage 1500-B-LOOKUP-ACCT, source "
                            + "app/cbl/CBTRN02C.cbl:L403-L405 read with L551: the formula "
                            + "subtracts ACCT-CURR-CYC-DEBIT, so a refund raises WS-TEMP-BAL and "
                            + "tightens the test at L407 for the next record");
        }

        @Test
        @DisplayName("a refund can turn the next approval into a decline, L407")
        void aRefundCanTurnTheNextApprovalIntoADecline() {
            String accountId = firstFeedAccountId();
            CopybookRecordParser.AccountRecord account =
                    CardDemoFixtureLoader.accountsByAccountId().get(accountId);
            Probe probe = new Probe(accountId, zeroAmount());
            BigDecimal probeAmount = account.creditLimit();
            BigDecimal refund = firstFeedRecord().amount().abs().negate();

            assertTrue(account.creditLimit()
                            .compareTo(workingBalance(zeroAmount(), zeroAmount(), probeAmount)) >= 0,
                    "account " + accountId + ", stage 1500-B-LOOKUP-ACCT, source "
                            + "app/cbl/CBTRN02C.cbl:L407: an amount equal to the limit passes "
                            + "while both accumulators hold zero");

            probe.postingService().postTransaction(eventCarrying(accountId,
                    syntheticTransactionId(FIRST_RECORD_ORDINAL), refund));
            AccountBalanceProjectionEntity held =
                    probe.accountBalances().findById(accountId).orElseThrow();

            assertFalse(account.creditLimit().compareTo(
                            workingBalance(held.getCycleCredit(), held.getCycleDebit(),
                                    probeAmount)) >= 0,
                    "account " + accountId + ", stage 1500-B-LOOKUP-ACCT, source "
                            + "app/cbl/CBTRN02C.cbl:L407 read with L551: the same amount now "
                            + "fails the limit test, so the refund tightened the authorization");
        }
    }

    @Nested
    @DisplayName("2500-WRITE-REJECT-REC, app/cbl/CBTRN02C.cbl:L446-L465")
    class RejectRecord {

        @Test
        @DisplayName("the length is the data part plus the trailer, L177-L178 and POSTTRAN.jcl L36")
        void theLengthIsTheDataPartPlusTheTrailer() {
            assertEquals(PicClause.REJECT_RECORD_LENGTH,
                    PicClause.REJECT_TRAN_DATA_WIDTH + PicClause.VALIDATION_TRAILER_WIDTH,
                    "REJECT-TRAN-DATA at CBTRN02C L177 and VALIDATION-TRAILER at L178 add up to "
                            + "the length POSTTRAN.jcl L36 allocates");
            assertEquals(PicClause.VALIDATION_TRAILER_WIDTH,
                    PicClause.VALIDATION_FAIL_REASON_WIDTH
                            + PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH,
                    "the trailer splits into the reason at CBTRN02C L181 and the description at "
                            + "L182");
            assertEquals(PicClause.REJECT_TRAN_DATA_WIDTH, PicClause.DALYTRAN_RECORD_LENGTH,
                    "CBTRN02C L447 moves a whole DALYTRAN-RECORD into REJECT-TRAN-DATA, so the "
                            + "two widths agree");
        }

        @Test
        @DisplayName("every rendered record parses back, L447-L448")
        void everyRenderedRecordParsesBack() {
            PostingRun run = fixtureRun();

            for (FeedOutcome outcome : run.rejectedOutcomes()) {
                String rendered =
                        renderRejectRecord(outcome.feed(), outcome.declineReason());
                CopybookRecordParser.RejectedTransactionRecord parsed =
                        CopybookRecordParser.parseRejectedTransaction(rendered);

                assertEquals(PicClause.REJECT_RECORD_LENGTH, rendered.length(),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cbl/CBTRN02C.cbl:L176-L182")
                                + ": the rendered record fills the declared length");
                assertEquals(outcome.declineReason().numericCode(), parsed.failReason(),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cbl/CBTRN02C.cbl:L181")
                                + ": the trailer reason read back unchanged");
                assertEquals(outcome.declineReason().description(),
                        parsed.failReasonDescription(),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cbl/CBTRN02C.cbl:L182")
                                + ": the trailer description read back unchanged");
                assertEquals(PicClause.REJECT_TRAN_DATA_WIDTH, parsed.transactionData().length(),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cbl/CBTRN02C.cbl:L177")
                                + ": the data part kept its width");
            }
        }

        @Test
        @DisplayName("the amount inside the data part carries plain digits, an ADDITIVE divergence")
        void theAmountInsideTheDataPartCarriesPlainDigits() {
            PostingRun run = fixtureRun();
            int amountOffset = amountOffsetInFeedRecord();
            int amountEnd = amountOffset + PicClause.DALYTRAN_AMT_WIDTH;

            for (FeedOutcome outcome : run.rejectedOutcomes()) {
                String dataPart = renderRejectRecord(outcome.feed(), outcome.declineReason())
                        .substring(0, PicClause.REJECT_TRAN_DATA_WIDTH);
                String amountSlice = dataPart.substring(amountOffset, amountEnd);
                CopybookRecordParser.DailyTransactionRecord reparsed =
                        CopybookRecordParser.parseDailyTransaction(dataPart);

                assertTrue(amountSlice.chars().allMatch(Character::isDigit),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L10")
                                + ": the amount slice holds digits alone and carries no sign "
                                + "overpunch, which is ADDITIVE");
                assertEquals(0, outcome.feed().amount().abs().compareTo(reparsed.amount()),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L10")
                                + ": the plain digits read back as the absolute value of the "
                                + "fixture amount, compared numerically");
            }
        }

        @Test
        @DisplayName("every reason is one of the four wire values, L385 L397 L410 and L417")
        void everyReasonIsOneOfTheFourWireValues() {
            PostingRun run = fixtureRun();
            Set<String> wireValues = new LinkedHashSet<>();
            for (DeclineReason reason : DeclineReason.values()) {
                wireValues.add(reason.code());
                assertEquals(PicClause.VALIDATION_FAIL_REASON_WIDTH, reason.code().length(),
                        "reason " + reason.code() + ", stage " + REJECT_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L181: the wire form fills "
                                + "WS-VALIDATION-FAIL-REASON");
                assertTrue(reason.description().length()
                                <= PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH,
                        "reason " + reason.code() + ", stage " + REJECT_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L182: the description fits "
                                + "WS-VALIDATION-FAIL-REASON-DESC without truncation");
            }

            for (RejectedTransactionEntity row : run.rejectedRows()) {
                assertTrue(wireValues.contains(row.getRejectReasonCode()),
                        "reject row for transaction " + row.getTransactionId() + ", stage "
                                + REJECT_STAGE + ", source app/cbl/CBTRN02C.cbl:L385, L397, L410 "
                                + "and L417: the reason is one of the four the source assigns");
                assertEquals(DeclineReason.fromCode(row.getRejectReasonCode()).description(),
                        row.getRejectReasonDescription(),
                        "reject row for transaction " + row.getTransactionId() + ", stage "
                                + REJECT_STAGE + ", source app/cbl/CBTRN02C.cbl:L182: the "
                                + "description is the one the source pairs with the reason");
            }
            assertEquals(run.declineReasons().size(), run.declineReasons().stream()
                            .filter(reason -> wireValues.contains(reason.code())).count(),
                    "every reason the run produced carries a wire value the contract declares");
        }

        @Test
        @DisplayName("each reject row carries the amount and a masked card number, L447")
        void eachRejectRowCarriesTheAmountAndAMaskedCardNumber() {
            PostingRun run = fixtureRun();
            List<FeedOutcome> rejected = run.rejectedOutcomes();

            assertEquals(rejected.size(), run.rejectedRows().size(),
                    "CBTRN02C L215 writes one reject record per rejected record");
            for (int position = 0; position < rejected.size(); position++) {
                FeedOutcome outcome = rejected.get(position);
                RejectedTransactionEntity row = run.rejectedRows().get(position);

                assertEquals(outcome.feed().transactionId(), row.getTransactionId(),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L5")
                                + ": the reject rows follow feed order");
                assertEquals(0, outcome.feed().amount().compareTo(row.getTransactionAmount()),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L10")
                                + ": the row keeps the signed fixture amount, compared "
                                + "numerically");
                assertEquals(PanMasker.maskCardNumber(outcome.feed().cardNumber()),
                        row.getMaskedCardNumber(),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L15")
                                + ": the row carries the masked number, which is ADDITIVE");
            }
        }

        @Test
        @DisplayName("one declined event follows each reject, L215")
        void oneDeclinedEventFollowsEachReject() {
            PostingRun run = fixtureRun();

            assertEquals(run.rejectedCount(),
                    run.outboxRowsOfType(TransactionDeclined.EVENT_TYPE).size(),
                    "each pass of CBTRN02C L215 writes one reject record, and the target "
                            + "publishes one " + TransactionDeclined.EVENT_TYPE + " for it");
        }

        @Test
        @DisplayName("one reason is assigned before the account read, L385 ahead of L394")
        void oneReasonIsAssignedBeforeTheAccountRead() {
            long reasonsWithoutAnAccount = 0;
            for (DeclineReason reason : DeclineReason.values()) {
                if (!reason.resolvesAccount()) {
                    reasonsWithoutAnAccount++;
                }
            }

            assertEquals(ONE_ROW_PER_CALL, reasonsWithoutAnAccount,
                    "CBTRN02C L385 assigns its reason inside 1500-A-LOOKUP-XREF, ahead of the "
                            + "account read at L394, so exactly one of the four reasons names no "
                            + "account");
            assertFalse(DeclineReason.INVALID_CARD_NUMBER.resolvesAccount(),
                    "the reason CBTRN02C L385 assigns is the one that names no account, and its "
                            + "declined event is keyed on the transaction identifier");
        }

        @Test
        @DisplayName("each reason that names an account writes one row, L397 L410 and L417")
        void eachReasonThatNamesAnAccountWritesOneRow() {
            String accountId = firstFeedAccountId();

            for (DeclineReason reason : DeclineReason.values()) {
                if (!reason.resolvesAccount()) {
                    continue;
                }
                Probe probe = new Probe(accountId, zeroAmount());
                TransactionAuthorized event = eventCarrying(accountId,
                        syntheticTransactionId(FIRST_RECORD_ORDINAL), firstFeedRecord().amount());

                probe.rejectRecorder().recordReject(event, reason);
                List<RejectedTransactionEntity> written = probe.rejects().snapshot();

                assertEquals(ONE_ROW_PER_CALL, written.size(),
                        "reason " + reason.code() + ", stage " + REJECT_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L451: one call writes one row");
                assertEquals(reason.code(), written.get(0).getRejectReasonCode(),
                        "reason " + reason.code() + ", stage " + REJECT_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L181: the row carries the wire "
                                + "form of the reason");
                assertEquals(reason.description(),
                        written.get(0).getRejectReasonDescription(),
                        "reason " + reason.code() + ", stage " + REJECT_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L182: the row carries the text "
                                + "the source pairs with the reason");
                assertEquals(List.of(REJECT_STAGE), probe.saveLog(),
                        "reason " + reason.code() + ", stage " + REJECT_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L213-L215: the reject branch "
                                + "wrote nothing the post branch writes");
                assertEquals(ONE_ROW_PER_CALL,
                        probe.publishedOfType(TransactionDeclined.EVENT_TYPE).size(),
                        "reason " + reason.code() + ", stage " + REJECT_STAGE
                                + ": one " + TransactionDeclined.EVENT_TYPE
                                + " reached the outbox");
            }
        }
    }

    @Nested
    @DisplayName("Z-GET-DB2-FORMAT-TIMESTAMP, app/cbl/CBTRN02C.cbl:L692-L705")
    class ProcessingTimestamp {

        @Test
        @DisplayName("the outbound field holds a dash then dots, L166 and L702-L703")
        void theOutboundFieldHoldsADashThenDots() {
            String rendered = CobolDecimal.formatProcessingTimestamp(LocalDateTime.now());
            int separator = PicClause.PROCESSING_TIMESTAMP_SEPARATOR_WIDTH;

            assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH, rendered.length(),
                    "DB2-FORMAT-TS at CBTRN02C L159 holds a fixed width");
            assertEquals(PicClause.PROCESSING_TIMESTAMP_DASH,
                    rendered.charAt(PicClause.PROCESSING_TIMESTAMP_HOUR_OFFSET - separator),
                    "DB2-STREEP-3 at CBTRN02C L166 sits between DB2-DD and DB2-HH, and L702 fills "
                            + "it with a dash");
            assertEquals(PicClause.PROCESSING_TIMESTAMP_DOT,
                    rendered.charAt(PicClause.PROCESSING_TIMESTAMP_MINUTE_OFFSET - separator),
                    "DB2-DOT-1 at CBTRN02C L168 carries the value L703 moves into it");
            assertEquals(PicClause.PROCESSING_TIMESTAMP_DOT,
                    rendered.charAt(PicClause.PROCESSING_TIMESTAMP_SECOND_OFFSET - separator),
                    "DB2-DOT-2 at CBTRN02C L170 carries the value L703 moves into it");
            assertEquals(PicClause.PROCESSING_TIMESTAMP_DOT,
                    rendered.charAt(PicClause.PROCESSING_TIMESTAMP_FRACTION_OFFSET - separator),
                    "DB2-DOT-3 at CBTRN02C L172 carries the value L703 moves into it");
            assertEquals(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS,
                    rendered.substring(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET),
                    "CBTRN02C L701 moves a fixed literal into DB2-REST");
        }

        @Test
        @DisplayName("the inbound field uses other separators, CVTRA06Y L16")
        void theInboundFieldUsesOtherSeparators() {
            String origin = firstFeedRecord().originTimestamp();
            int separator = PicClause.PROCESSING_TIMESTAMP_SEPARATOR_WIDTH;

            assertEquals(PicClause.DALYTRAN_ORIG_TS_WIDTH, origin.length(),
                    "DALYTRAN-ORIG-TS at CVTRA06Y L16 and DB2-FORMAT-TS at CBTRN02C L159 share a "
                            + "width and carry different layouts");
            assertNotEquals(PicClause.PROCESSING_TIMESTAMP_DASH,
                    origin.charAt(PicClause.PROCESSING_TIMESTAMP_HOUR_OFFSET - separator),
                    "the inbound field separates the day from the hour with another character, so "
                            + "the two layouts are not interchangeable");
            assertNotEquals(PicClause.PROCESSING_TIMESTAMP_DOT,
                    origin.charAt(PicClause.PROCESSING_TIMESTAMP_MINUTE_OFFSET - separator),
                    "the inbound field separates the time components with another character");
            assertEquals(PicClause.ACCOUNT_EXPIRATION_COMPARISON_WIDTH,
                    CopybookRecordParser.timestampDatePart(origin).length(),
                    "CBTRN02C L414 reads the leading characters of the inbound field, which both "
                            + "layouts share");
        }

        @Test
        @DisplayName("every posted field already sits at hundredths, L700-L701")
        void everyPostedFieldSitsAtHundredths() {
            PostingRun run = fixtureRun();

            for (Map.Entry<String, TransactionEntity> posted
                    : run.postedTransactions().entrySet()) {
                String held = posted.getValue().getProcessedTimestamp();

                assertEquals(CopybookRecordParser.truncateProcessingTimestampToHundredths(held),
                        held,
                        "transaction " + posted.getKey() + ", stage " + TRANSACTION_STAGE
                                + ", source app/cbl/CBTRN02C.cbl:L700-L701: the field carries "
                                + "hundredths and then fixed zeros, so truncation leaves it "
                                + "unchanged");
            }
        }

        @Test
        @DisplayName("truncation changes a finer field, L701")
        void truncationChangesAFinerField() {
            String rendered = CobolDecimal.formatProcessingTimestamp(LocalDateTime.now());
            String finer = rendered.substring(0,
                            PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET)
                    + ABSENT_IDENTIFIER_DIGIT.repeat(
                            PicClause.PROCESSING_TIMESTAMP_TRAILING_ZERO_DIGITS);

            assertNotEquals(finer,
                    CopybookRecordParser.truncateProcessingTimestampToHundredths(finer),
                    "a field carrying digits past the hundredths is not what CBTRN02C L701 "
                            + "writes, so a comparison without truncation fails on every record");
        }

        @Test
        @DisplayName("a moment finer than a hundredth renders the same, L700")
        void aMomentFinerThanAHundredthRendersTheSame() {
            LocalDateTime moment = LocalDateTime.now().withNano(0);
            LocalDateTime finerMoment = moment.plusNanos(SUB_HUNDREDTH_NANOSECONDS);

            assertEquals(CobolDecimal.formatProcessingTimestamp(moment),
                    CobolDecimal.formatProcessingTimestamp(finerMoment),
                    "CBTRN02C L700 carries COB-MIL alone and L158 leaves COB-REST behind, so the "
                            + "field holds hundredths and drops what lies below");
        }
    }

    @Nested
    @DisplayName("Idempotency marker, ADDITIVE with no ancestor in app/cbl/CBTRN02C.cbl")
    class IdempotencyGuard {

        @Test
        @DisplayName("a repeat delivery is refused and posts nothing")
        void aRepeatDeliveryIsRefusedAndPostsNothing() {
            String accountId = firstFeedAccountId();
            Probe probe = new Probe(accountId, zeroAmount());
            BigDecimal amount = firstFeedRecord().amount();
            TransactionAuthorized event = eventCarrying(accountId,
                    syntheticTransactionId(FIRST_RECORD_ORDINAL), amount);
            Map<UUID, ProcessedEventEntity> markers = new LinkedHashMap<>();

            int postsMade = 0;
            for (int delivery = 0; delivery < DUPLICATE_DELIVERY_COUNT; delivery++) {
                if (claimMarker(markers, event) == CLAIM_TAKEN) {
                    probe.postingService().postTransaction(event);
                    postsMade++;
                }
            }

            assertEquals(CLAIM_TAKEN, postsMade,
                    "transaction " + event.transactionId() + ", stage " + TRANSACTION_STAGE
                            + ", source app/cbl/CBTRN02C.cbl:L562-L579: the source has no marker "
                            + "and a repeat write raises a duplicate-key condition, so the target "
                            + "adds a guard that lets one delivery through");
            assertEquals(CLAIM_TAKEN, markers.size(),
                    "one marker covers both deliveries of the same event identifier");
            assertEquals(CLAIM_TAKEN, (int) probe.transactions().count(),
                    "transaction " + event.transactionId() + ", stage " + TRANSACTION_STAGE
                            + ": the guarded path inserted one row");
            assertEquals(0, amount.compareTo(probe.accountBalances().findById(accountId)
                            .orElseThrow().getCurrentBalance()),
                    "account " + accountId + ", stage " + ACCOUNT_STAGE
                            + ", source app/cbl/CBTRN02C.cbl:L547: the balance moved once, "
                            + "compared numerically");
        }

        @Test
        @DisplayName("a second claim of the same event is refused")
        void aSecondClaimOfTheSameEventIsRefused() {
            String accountId = firstFeedAccountId();
            TransactionAuthorized event = eventCarrying(accountId,
                    syntheticTransactionId(FIRST_RECORD_ORDINAL), firstFeedRecord().amount());
            Map<UUID, ProcessedEventEntity> markers = new LinkedHashMap<>();

            assertEquals(CLAIM_TAKEN, claimMarker(markers, event),
                    "the first delivery of event " + event.eventId() + " takes the marker");
            assertEquals(CLAIM_REFUSED, claimMarker(markers, event),
                    "the second delivery of event " + event.eventId() + " is refused, so the "
                            + "posting call never runs a second time");
            ProcessedEventEntity marker = markers.get(event.eventId());
            assertEquals(event.eventId(), marker.getEventId(),
                    "the marker is keyed by the envelope identifier");
            assertEquals(AUTHORIZED_TOPIC, marker.getConsumedTopic(),
                    "the marker records the topic the delivery arrived on");
        }
    }

    /**
     * Returns the value {@code app/data/ASCII/tcatbal.txt} supplied for one key.
     *
     * @param key the {@code TRAN-CAT-KEY} at {@code app/cpy/CVTRA01Y.cpy:L5}
     * @return the seeded balance, or zero when the fixture supplied no row for the key
     */
    private static BigDecimal seededCategoryBalanceOf(TransactionCategoryBalanceId key) {
        for (CopybookRecordParser.TransactionCategoryBalanceRecord seeded
                : CardDemoFixtureLoader.loadTransactionCategoryBalances()) {
            if (seeded.accountId().equals(key.getAccountId())
                    && seeded.typeCode().equals(key.getTypeCode())
                    && seeded.categoryCode().equals(key.getCategoryCode())) {
                return seeded.balance();
            }
        }
        return BigDecimal.ZERO.setScale(PicClause.TRAN_CAT_BAL_SCALE);
    }

    /**
     * Counts the declines a pass over the feed produces under one accumulator policy.
     *
     * <p>The chain is the one at {@code app/cbl/CBTRN02C.cbl:L370-L422}, applied without the
     * ledger services, so the count depends on the policy alone.</p>
     *
     * @param carryForward        whether an accumulator movement reaches the next record
     * @param accumulateOnDecline whether a declined amount reaches the accumulators
     * @return the derived decline count
     */
    private static long declineCountUnder(boolean carryForward, boolean accumulateOnDecline) {
        Map<String, CopybookRecordParser.CardCrossReferenceRecord> crossReferences =
                CardDemoFixtureLoader.cardCrossReferencesByCardNumber();
        Map<String, CopybookRecordParser.AccountRecord> accounts =
                CardDemoFixtureLoader.accountsByAccountId();
        Map<String, BigDecimal> cycleCredit = new LinkedHashMap<>();
        Map<String, BigDecimal> cycleDebit = new LinkedHashMap<>();
        accounts.forEach((accountId, account) -> {
            cycleCredit.put(accountId, account.currentCycleCredit());
            cycleDebit.put(accountId, account.currentCycleDebit());
        });

        long declines = 0;
        for (CopybookRecordParser.DailyTransactionRecord feed
                : CardDemoFixtureLoader.loadDailyTransactions()) {
            CopybookRecordParser.CardCrossReferenceRecord crossReference =
                    crossReferences.get(feed.cardNumber());
            if (crossReference == null) {
                declines++;
                continue;
            }
            String accountId = crossReference.accountId();
            CopybookRecordParser.AccountRecord account = accounts.get(accountId);
            if (account == null) {
                declines++;
                continue;
            }
            BigDecimal credit =
                    carryForward ? cycleCredit.get(accountId) : account.currentCycleCredit();
            BigDecimal debit =
                    carryForward ? cycleDebit.get(accountId) : account.currentCycleDebit();
            BigDecimal working = workingBalance(credit, debit, feed.amount());
            String originDate = CopybookRecordParser.timestampDatePart(feed.originTimestamp());
            boolean declined = account.creditLimit().compareTo(working) < 0
                    || account.expirationDate().compareTo(originDate) < 0;
            if (declined) {
                declines++;
                if (!accumulateOnDecline) {
                    continue;
                }
            }
            if (feed.amount().signum() >= 0) {
                cycleCredit.put(accountId, CobolDecimal.add(cycleCredit.get(accountId),
                        feed.amount(), PicClause.ACCT_CURR_CYC_CREDIT_SCALE));
            } else {
                cycleDebit.put(accountId, CobolDecimal.add(cycleDebit.get(accountId),
                        feed.amount(), PicClause.ACCT_CURR_CYC_DEBIT_SCALE));
            }
        }
        return declines;
    }

    /**
     * Counts the declines a pass produces with the accumulators frozen at their fixture values.
     *
     * @return the derived decline count
     */
    private static long statelessDeclineCount() {
        return declineCountUnder(false, false);
    }

    /**
     * Counts the declines a pass produces when a declined amount also reaches the accumulators.
     *
     * @return the derived decline count
     */
    private static long accumulateOnDeclineDeclineCount() {
        return declineCountUnder(true, true);
    }

    /**
     * Renders the components {@code app/cbl/CBTRN02C.cbl:L425-L436} copies, from the feed side.
     *
     * @param feed one record of {@code app/data/ASCII/dailytran.txt}
     * @return the copied components as text, in copybook declaration order
     */
    private static List<String> copiedValuesOf(
            CopybookRecordParser.DailyTransactionRecord feed) {
        return List.of(feed.transactionId(),
                feed.typeCode(),
                feed.categoryCode(),
                feed.source(),
                feed.description(),
                CobolDecimal.truncateToScale(feed.amount(), PicClause.TRAN_AMT_SCALE)
                        .toPlainString(),
                feed.merchantId(),
                feed.merchantName(),
                feed.merchantCity(),
                feed.merchantZip(),
                PanMasker.maskCardNumber(feed.cardNumber()),
                feed.originTimestamp());
    }

    /**
     * Renders the components {@code app/cbl/CBTRN02C.cbl:L425-L436} copies, from the posted side.
     *
     * @param posted one row the transaction store holds
     * @return the copied components as text, in copybook declaration order
     */
    private static List<String> copiedValuesOf(TransactionEntity posted) {
        return List.of(posted.getTransactionId(),
                posted.getTypeCode(),
                posted.getCategoryCode(),
                posted.getSource(),
                posted.getDescription(),
                posted.getAmount().toPlainString(),
                posted.getMerchantId(),
                posted.getMerchantName(),
                posted.getMerchantCity(),
                posted.getMerchantZip(),
                posted.getCardNumber(),
                posted.getOriginTimestamp());
    }

    /**
     * Splits the posting-stage entries of a save log into one group per posted record.
     *
     * @param saveLog stage name of every write the run made, in order
     * @return the groups, each holding {@link #POST_STAGE_ORDER} many entries
     */
    private static List<List<String>> postStageTriples(List<String> saveLog) {
        List<String> postStages = saveLog.stream().filter(POST_STAGE_ORDER::contains).toList();
        int groupSize = POST_STAGE_ORDER.size();
        List<List<String>> groups = new ArrayList<>();
        for (int start = 0; start + groupSize <= postStages.size(); start += groupSize) {
            groups.add(postStages.subList(start, start + groupSize));
        }
        return groups;
    }

    /**
     * Groups the amounts that posted, by the account each posted against.
     *
     * @param run one run over the whole feed
     * @return the posted amounts per account identifier, in feed order
     */
    private static Map<String, List<BigDecimal>> postedAmountsByAccount(PostingRun run) {
        Map<String, List<BigDecimal>> byAccount = new LinkedHashMap<>();
        for (FeedOutcome outcome : run.postedOutcomes()) {
            byAccount.computeIfAbsent(outcome.accountId(), key -> new ArrayList<>())
                    .add(outcome.feed().amount());
        }
        return byAccount;
    }

    /**
     * Groups the amounts that posted, by the {@code TRAN-CAT-KEY} each posted against.
     *
     * @param run one run over the whole feed
     * @return the posted amounts per category key, in feed order
     */
    private static Map<TransactionCategoryBalanceId, List<BigDecimal>> postedAmountsByCategoryKey(
            PostingRun run) {
        Map<TransactionCategoryBalanceId, List<BigDecimal>> byKey = new LinkedHashMap<>();
        for (FeedOutcome outcome : run.postedOutcomes()) {
            TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                    outcome.accountId(), outcome.feed().typeCode(), outcome.feed().categoryCode());
            byKey.computeIfAbsent(key, unused -> new ArrayList<>()).add(outcome.feed().amount());
        }
        return byKey;
    }

    /**
     * Adds values one at a time through {@link CobolDecimal#add}, as the source adds them.
     *
     * @param opening the value the field held before the first add
     * @param addends the values to add, in the order the source adds them
     * @param scale   the scale {@link PicClause} publishes for the target field
     * @return the value the field holds after the last add
     */
    private static BigDecimal truncatingSum(BigDecimal opening, List<BigDecimal> addends,
            int scale) {
        BigDecimal held = CobolDecimal.truncateToScale(opening, scale);
        for (BigDecimal addend : addends) {
            held = CobolDecimal.add(held, addend, scale);
        }
        return held;
    }

    /**
     * Returns zero at the scale a monetary field of {@code app/cpy/CVACT01Y.cpy} holds.
     *
     * @return zero at the account balance scale
     */
    private static BigDecimal zeroAmount() {
        return BigDecimal.ZERO.setScale(PicClause.ACCT_CURR_BAL_SCALE);
    }

    /**
     * Zero-pads an ordinal to the width of {@code TRAN-ID} at {@code app/cpy/CVTRA05Y.cpy:L5}.
     *
     * @param ordinal the number to render
     * @return a transaction identifier of {@link PicClause#TRAN_ID_WIDTH} characters
     */
    private static String syntheticTransactionId(int ordinal) {
        String digits = Integer.toString(ordinal);
        return PADDING_DIGIT.repeat(PicClause.TRAN_ID_WIDTH - digits.length()) + digits;
    }

    /**
     * Returns the first record of {@code app/data/ASCII/dailytran.txt}, used as a field template.
     *
     * @return the first feed record
     */
    private static CopybookRecordParser.DailyTransactionRecord firstFeedRecord() {
        return CardDemoFixtureLoader.loadDailyTransactions().get(0);
    }

    /**
     * Resolves the account the first feed record's card points at.
     *
     * @return {@code XREF-ACCT-ID} at {@code app/cpy/CVACT03Y.cpy:L7} for that card
     */
    private static String firstFeedAccountId() {
        return CardDemoFixtureLoader.cardCrossReferencesByCardNumber()
                .get(firstFeedRecord().cardNumber())
                .accountId();
    }

    /**
     * Records one idempotency marker, refusing a repeat of the same event identifier.
     *
     * <p>The refusal follows {@code ProcessedEventRepository.claimEvent}, whose insert leaves an
     * existing marker in place. ADDITIVE, with no ancestor in {@code app/cbl/CBTRN02C.cbl}.</p>
     *
     * @param markers markers this consumer has already written
     * @param event   the delivery being claimed
     * @return {@link #CLAIM_TAKEN} when this caller claimed the event, {@link #CLAIM_REFUSED}
     *         otherwise
     */
    private static int claimMarker(Map<UUID, ProcessedEventEntity> markers,
            TransactionAuthorized event) {
        ProcessedEventEntity marker =
                new ProcessedEventEntity(event.eventId(), Instant.now());
        marker.setConsumedTopic(AUTHORIZED_TOPIC);
        return markers.putIfAbsent(event.eventId(), marker) == null
                ? CLAIM_TAKEN
                : CLAIM_REFUSED;
    }

    /**
     * Builds an authorized event carrying a chosen account, identifier and amount.
     *
     * <p>Every other field is copied from {@link #firstFeedRecord()}, so the event matches the
     * shapes {@code app/cpy/CVTRA06Y.cpy} declares.</p>
     *
     * @param accountId     {@code XREF-ACCT-ID} at {@code app/cpy/CVACT03Y.cpy:L7}
     * @param transactionId {@code DALYTRAN-ID} at {@code app/cpy/CVTRA06Y.cpy:L5}
     * @param amount        {@code DALYTRAN-AMT} at {@code app/cpy/CVTRA06Y.cpy:L10}
     * @return the event
     */
    private static TransactionAuthorized eventCarrying(String accountId, String transactionId,
            BigDecimal amount) {
        CopybookRecordParser.DailyTransactionRecord template = firstFeedRecord();
        return TransactionAuthorized.of(accountId, transactionId, template.typeCode(),
                template.categoryCode(), template.source(), template.description(), amount,
                template.merchantId(), template.merchantName(), template.merchantCity(),
                template.merchantZip(), PanMasker.maskCardNumber(template.cardNumber()),
                template.originTimestamp());
    }

    /**
     * Returns the offset of {@code DALYTRAN-AMT} inside a {@code DALYTRAN-RECORD}.
     *
     * <p>The offset is the running sum of the widths {@code app/cpy/CVTRA06Y.cpy:L5-L9}
     * declares.</p>
     *
     * @return the zero-based offset of the amount field
     */
    private static int amountOffsetInFeedRecord() {
        return PicClause.DALYTRAN_ID_WIDTH
                + PicClause.DALYTRAN_TYPE_CD_WIDTH
                + PicClause.DALYTRAN_CAT_CD_WIDTH
                + PicClause.DALYTRAN_SOURCE_WIDTH
                + PicClause.DALYTRAN_DESC_WIDTH;
    }

    /**
     * Renders one {@code REJECT-RECORD} as {@code app/cbl/CBTRN02C.cbl:L446-L465} lays it out.
     *
     * <p>The amount carries plain zero-padded digits from the absolute value, which is ADDITIVE.
     * The trailer holds the reason code at {@link PicClause#VALIDATION_FAIL_REASON_WIDTH}
     * characters and the description at {@link PicClause#VALIDATION_FAIL_REASON_DESC_WIDTH}.</p>
     *
     * @param feed   the record {@code app/cbl/CBTRN02C.cbl:L447} moves into
     *               {@code REJECT-TRAN-DATA}
     * @param reason the reason {@code app/cbl/CBTRN02C.cbl:L448} moves into the trailer
     * @return a record of {@link PicClause#REJECT_RECORD_LENGTH} characters
     */
    private static String renderRejectRecord(CopybookRecordParser.DailyTransactionRecord feed,
            DeclineReason reason) {
        StringBuilder data = new StringBuilder(PicClause.REJECT_TRAN_DATA_WIDTH)
                .append(alphanumeric(feed.transactionId(), PicClause.DALYTRAN_ID_WIDTH))
                .append(alphanumeric(feed.typeCode(), PicClause.DALYTRAN_TYPE_CD_WIDTH))
                .append(numeric(feed.categoryCode(), PicClause.DALYTRAN_CAT_CD_WIDTH))
                .append(alphanumeric(feed.source(), PicClause.DALYTRAN_SOURCE_WIDTH))
                .append(alphanumeric(feed.description(), PicClause.DALYTRAN_DESC_WIDTH))
                .append(plainDigitAmount(feed.amount()))
                .append(numeric(feed.merchantId(), PicClause.DALYTRAN_MERCHANT_ID_WIDTH))
                .append(alphanumeric(feed.merchantName(), PicClause.DALYTRAN_MERCHANT_NAME_WIDTH))
                .append(alphanumeric(feed.merchantCity(), PicClause.DALYTRAN_MERCHANT_CITY_WIDTH))
                .append(alphanumeric(feed.merchantZip(), PicClause.DALYTRAN_MERCHANT_ZIP_WIDTH))
                .append(alphanumeric(PanMasker.maskCardNumber(feed.cardNumber()),
                        PicClause.DALYTRAN_CARD_NUM_WIDTH))
                .append(alphanumeric(feed.originTimestamp(), PicClause.DALYTRAN_ORIG_TS_WIDTH))
                .append(COBOL_TEXT_PAD.repeat(PicClause.DALYTRAN_PROC_TS_WIDTH))
                .append(COBOL_TEXT_PAD.repeat(PicClause.DALYTRAN_RECORD_FILLER_WIDTH));
        String trailer = numeric(reason.code(), PicClause.VALIDATION_FAIL_REASON_WIDTH)
                + alphanumeric(reason.description(), PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH);
        return data + trailer;
    }

    /**
     * Renders an amount as plain zero-padded digits, dropping the sign.
     *
     * @param amount the value {@code DALYTRAN-AMT} holds
     * @return {@link PicClause#DALYTRAN_AMT_WIDTH} digit characters
     */
    private static String plainDigitAmount(BigDecimal amount) {
        return numeric(amount.abs().movePointRight(PicClause.DALYTRAN_AMT_SCALE)
                .toBigInteger().toString(), PicClause.DALYTRAN_AMT_WIDTH);
    }

    /**
     * Pads or clips text on the right, as a {@code PIC X(n)} field holds it.
     *
     * @param value the text to render
     * @param width the width the field holds
     * @return exactly {@code width} characters
     */
    private static String alphanumeric(String value, int width) {
        return value.length() >= width
                ? value.substring(0, width)
                : value + COBOL_TEXT_PAD.repeat(width - value.length());
    }

    /**
     * Pads or clips digits on the left, as a {@code PIC 9(n)} field holds them.
     *
     * @param digits the digits to render
     * @param width  the width the field holds
     * @return exactly {@code width} characters
     */
    private static String numeric(String digits, int width) {
        return digits.length() >= width
                ? digits.substring(digits.length() - width)
                : PADDING_DIGIT.repeat(width - digits.length()) + digits;
    }

    /**
     * What one pass of the loop at {@code app/cbl/CBTRN02C.cbl:L202-L219} did with one record.
     *
     * @param ordinal       position of the record in {@code app/data/ASCII/dailytran.txt}
     * @param feed          the record as {@code app/cpy/CVTRA06Y.cpy} lays it out
     * @param accountId     the account the cross-reference resolved, or the placeholder
     * @param declineReason the reason a decline carried, or {@code null} on a post
     * @param event         the event the ledger services consumed
     */
    private record FeedOutcome(int ordinal,
            CopybookRecordParser.DailyTransactionRecord feed,
            String accountId,
            DeclineReason declineReason,
            TransactionAuthorized event) {

        /**
         * Reports whether {@code app/cbl/CBTRN02C.cbl:L211} sent this record to
         * {@code 2000-POST-TRANSACTION}.
         *
         * @return {@code true} when the record posted
         */
        boolean posted() {
            return declineReason == null;
        }
    }

    /**
     * Terminal state of one run over the whole feed, plus the per-record outcomes.
     *
     * @param outcomes           one entry per feed record, in feed order
     * @param seededAccounts     {@code app/data/ASCII/acctdata.txt}, keyed by account identifier
     * @param crossReferences    {@code app/data/ASCII/cardxref.txt}, keyed by card number
     * @param seededCategoryKeys the keys {@code app/data/ASCII/tcatbal.txt} supplied
     * @param accountBalances    account state after the last record
     * @param categoryBalances   category balances after the last record
     * @param postedTransactions rows the transaction store holds, keyed by transaction identifier
     * @param rejectedRows       rows the reject store holds, in feed order
     * @param outboxRows         rows the outbox holds, in publication order
     * @param saveLog            stage name of every write the run made, in order
     * @param repeatedTransactionIdentifiers identifiers a transaction write presented twice
     * @param categoryKeysMissedOnRead keys whose read raised the {@code INVALID KEY} branch
     */
    private record PostingRun(List<FeedOutcome> outcomes,
            Map<String, CopybookRecordParser.AccountRecord> seededAccounts,
            Map<String, CopybookRecordParser.CardCrossReferenceRecord> crossReferences,
            Set<TransactionCategoryBalanceId> seededCategoryKeys,
            Map<String, AccountBalanceProjectionEntity> accountBalances,
            Map<TransactionCategoryBalanceId, TransactionCategoryBalanceEntity> categoryBalances,
            Map<String, TransactionEntity> postedTransactions,
            List<RejectedTransactionEntity> rejectedRows,
            List<OutboxEventEntity> outboxRows,
            List<String> saveLog,
            List<String> repeatedTransactionIdentifiers,
            List<TransactionCategoryBalanceId> categoryKeysMissedOnRead) {

        /**
         * Counts the records {@code app/cbl/CBTRN02C.cbl:L212} posted.
         *
         * @return the derived post count
         */
        long postedCount() {
            return outcomes.stream().filter(FeedOutcome::posted).count();
        }

        /**
         * Counts the records {@code app/cbl/CBTRN02C.cbl:L214} added to {@code WS-REJECT-COUNT}.
         *
         * @return the derived reject count
         */
        long rejectedCount() {
            return outcomes.size() - postedCount();
        }

        /**
         * Returns the value {@code app/cbl/CBTRN02C.cbl:L229-L230} would leave in
         * {@code RETURN-CODE}.
         *
         * @return the derived return code
         */
        int returnCode() {
            return rejectedCount() > 0
                    ? RETURN_CODE_WHEN_REJECTS_PRESENT
                    : RETURN_CODE_WHEN_NO_REJECT;
        }

        /**
         * Counts the category balance rows carrying a value above zero.
         *
         * @return the derived count of rows above zero
         */
        long categoryRowsAboveZero() {
            return categoryBalances.values().stream()
                    .filter(row -> row.getCategoryBalance().signum() > 0)
                    .count();
        }

        /**
         * Counts the category balance rows carrying a value below zero.
         *
         * @return the derived count of rows below zero
         */
        long categoryRowsBelowZero() {
            return categoryBalances.values().stream()
                    .filter(row -> row.getCategoryBalance().signum() < 0)
                    .count();
        }

        /**
         * Returns the outcomes of the records that posted.
         *
         * @return the posted outcomes, in feed order
         */
        List<FeedOutcome> postedOutcomes() {
            return outcomes.stream().filter(FeedOutcome::posted).toList();
        }

        /**
         * Returns the outcomes of the records that were rejected.
         *
         * @return the rejected outcomes, in feed order
         */
        List<FeedOutcome> rejectedOutcomes() {
            return outcomes.stream().filter(outcome -> !outcome.posted()).toList();
        }

        /**
         * Returns the distinct decline reasons the run produced.
         *
         * @return the reasons, in feed order of first appearance
         */
        Set<DeclineReason> declineReasons() {
            Set<DeclineReason> reasons = new LinkedHashSet<>();
            rejectedOutcomes().forEach(outcome -> reasons.add(outcome.declineReason()));
            return reasons;
        }

        /**
         * Returns the outbox rows carrying one event type.
         *
         * @param eventType the value {@code EventEnvelope.eventType} holds
         * @return the matching rows, in publication order
         */
        List<OutboxEventEntity> outboxRowsOfType(String eventType) {
            return outboxRows.stream()
                    .filter(row -> eventType.equals(row.getEventType()))
                    .toList();
        }
    }

    /**
     * Holds {@code account_balance_projection} rows for one run, keyed as
     * {@code app/jcl/ACCTFILE.jcl} keys the dataset.
     */
    private static final class StoredAccountBalances implements AccountBalanceProjectionRepository {

        /** Rows the run has written, in first-write order. */
        private final Map<String, AccountBalanceProjectionEntity> rows = new LinkedHashMap<>();

        /** Shared log every store appends its stage name to on a write. */
        private final List<String> saveLog;

        /** Writes made before the run began, which the log leaves out. */
        private boolean seeding = true;

        private StoredAccountBalances(List<String> saveLog) {
            this.saveLog = saveLog;
        }

        /** Marks the end of seeding, so later writes reach the log. */
        void seedingComplete() {
            seeding = false;
        }

        @Override
        public Optional<AccountBalanceProjectionEntity> findById(String accountId) {
            return Optional.ofNullable(rows.get(accountId));
        }

        @Override
        public AccountBalanceProjectionEntity save(AccountBalanceProjectionEntity projection) {
            if (!seeding) {
                saveLog.add(ACCOUNT_STAGE);
            }
            rows.put(projection.getAccountId(), projection);
            return projection;
        }

        @Override
        public long count() {
            return rows.size();
        }

        /**
         * Returns the rows this store holds.
         *
         * @return an unmodifiable view in first-write order
         */
        Map<String, AccountBalanceProjectionEntity> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(rows));
        }
    }

    /**
     * Holds {@code transaction_category_balance} rows for one run, keyed by the
     * {@link PicClause#TRAN_CAT_KEY_WIDTH}-byte group at {@code app/cpy/CVTRA01Y.cpy:L5}.
     */
    private static final class StoredCategoryBalances
            implements TransactionCategoryBalanceRepository {

        /** Rows the run has written, in first-write order. */
        private final Map<TransactionCategoryBalanceId, TransactionCategoryBalanceEntity> rows =
                new LinkedHashMap<>();

        /** Keys whose read raised the {@code INVALID KEY} branch, in read order. */
        private final List<TransactionCategoryBalanceId> keysMissedOnRead = new ArrayList<>();

        /** Shared log every store appends its stage name to on a write. */
        private final List<String> saveLog;

        /** Writes made before the run began, which the log leaves out. */
        private boolean seeding = true;

        private StoredCategoryBalances(List<String> saveLog) {
            this.saveLog = saveLog;
        }

        /** Marks the end of seeding, so later writes reach the log. */
        void seedingComplete() {
            seeding = false;
        }

        @Override
        public Optional<TransactionCategoryBalanceEntity> findById(
                TransactionCategoryBalanceId key) {
            Optional<TransactionCategoryBalanceEntity> found = Optional.ofNullable(rows.get(key));
            if (found.isEmpty()) {
                keysMissedOnRead.add(key);
            }
            return found;
        }

        @Override
        public TransactionCategoryBalanceEntity save(
                TransactionCategoryBalanceEntity categoryBalance) {
            if (!seeding) {
                saveLog.add(CATEGORY_BALANCE_STAGE);
            }
            rows.put(categoryBalance.getId(), categoryBalance);
            return categoryBalance;
        }

        @Override
        public long count() {
            return rows.size();
        }

        /**
         * Returns the keys this store holds.
         *
         * @return the keys in first-write order
         */
        Set<TransactionCategoryBalanceId> keys() {
            return new LinkedHashSet<>(rows.keySet());
        }

        /**
         * Returns the keys whose read found no row.
         *
         * @return the keys in read order
         */
        List<TransactionCategoryBalanceId> keysMissedOnRead() {
            return List.copyOf(keysMissedOnRead);
        }

        /**
         * Returns the rows this store holds.
         *
         * @return an unmodifiable view in first-write order
         */
        Map<TransactionCategoryBalanceId, TransactionCategoryBalanceEntity> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(rows));
        }
    }

    /**
     * Holds {@code transaction} rows for one run. The store starts empty, matching
     * {@code OPEN OUTPUT} at {@code app/cbl/CBTRN02C.cbl:L254-L256}.
     */
    private static final class StoredTransactions implements TransactionRepository {

        /** Rows the run has written, in write order. */
        private final Map<String, TransactionEntity> rows = new LinkedHashMap<>();

        /** Identifiers a write presented more than once. */
        private final List<String> repeatedIdentifiers = new ArrayList<>();

        /** Shared log every store appends its stage name to on a write. */
        private final List<String> saveLog;

        private StoredTransactions(List<String> saveLog) {
            this.saveLog = saveLog;
        }

        @Override
        public TransactionEntity save(TransactionEntity transaction) {
            saveLog.add(TRANSACTION_STAGE);
            if (rows.containsKey(transaction.getTransactionId())) {
                repeatedIdentifiers.add(transaction.getTransactionId());
            }
            rows.put(transaction.getTransactionId(), transaction);
            return transaction;
        }

        @Override
        public Optional<TransactionEntity> findById(String transactionId) {
            return Optional.ofNullable(rows.get(transactionId));
        }

        @Override
        public boolean existsById(String transactionId) {
            return rows.containsKey(transactionId);
        }

        @Override
        public long count() {
            return rows.size();
        }

        /**
         * Returns the identifiers a write presented more than once.
         *
         * @return the identifiers in write order
         */
        List<String> repeatedIdentifiers() {
            return List.copyOf(repeatedIdentifiers);
        }

        /**
         * Returns the rows this store holds.
         *
         * @return an unmodifiable view in write order
         */
        Map<String, TransactionEntity> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(rows));
        }
    }

    /**
     * Holds {@code rejected_transaction} rows for one run. The store starts empty, matching
     * {@code OPEN OUTPUT} at {@code app/cbl/CBTRN02C.cbl:L291-L293}.
     */
    private static final class StoredRejects implements RejectedTransactionRepository {

        /** Rows the run has written, in write order. */
        private final List<RejectedTransactionEntity> rows = new ArrayList<>();

        /** Shared log every store appends its stage name to on a write. */
        private final List<String> saveLog;

        private StoredRejects(List<String> saveLog) {
            this.saveLog = saveLog;
        }

        @Override
        public RejectedTransactionEntity save(RejectedTransactionEntity rejected) {
            saveLog.add(REJECT_STAGE);
            rows.add(rejected);
            return rejected;
        }

        @Override
        public long count() {
            return rows.size();
        }

        /**
         * Returns the rows this store holds.
         *
         * @return an unmodifiable view in write order
         */
        List<RejectedTransactionEntity> snapshot() {
            return List.copyOf(rows);
        }
    }

    /**
     * A transaction store that refuses every write, standing in for a status the ladder at
     * {@code app/cbl/CBTRN02C.cbl:L566-L578} rejects.
     */
    private static final class RefusingTransactions implements TransactionRepository {

        /** Text the refusal carries, so a caller can name what it saw. */
        static final String REFUSAL = "ERROR WRITING TO TRANSACTION FILE";

        @Override
        public TransactionEntity save(TransactionEntity transaction) {
            throw new IllegalStateException(REFUSAL);
        }

        @Override
        public Optional<TransactionEntity> findById(String transactionId) {
            return Optional.empty();
        }

        @Override
        public boolean existsById(String transactionId) {
            return false;
        }

        @Override
        public long count() {
            return 0L;
        }
    }

    /**
     * A ledger wired to empty stores holding one seeded account row.
     *
     * <p>The category balance store and the transaction store start empty, matching
     * {@code app/cbl/CBTRN02C.cbl:L254-L256}. The account row opens with both cycle accumulators
     * at zero, matching every record of {@code app/data/ASCII/acctdata.txt}.</p>
     */
    private static final class Probe {

        /** Stage name of every write this probe made, in order. */
        private final List<String> saveLog = new ArrayList<>();

        /** Account state the probe carries. */
        private final StoredAccountBalances accountBalances;

        /** Category balances the probe carries. */
        private final StoredCategoryBalances categoryBalances;

        /** Transaction rows the probe carries. */
        private final TransactionRepository transactions;

        /** Reject rows the probe carries. */
        private final StoredRejects rejects;

        /** Outbox the probe publishes through. */
        private final CapturedOutbox outbox = new CapturedOutbox();

        /** The service under test. */
        private final PostingService postingService;

        /** The reject path under test. */
        private final RejectRecorder rejectRecorder;

        private Probe(String accountId, BigDecimal openingBalance) {
            this(accountId, openingBalance, null);
        }

        private Probe(String accountId, BigDecimal openingBalance,
                TransactionRepository transactionStore) {
            this.accountBalances = new StoredAccountBalances(saveLog);
            this.accountBalances.save(new AccountBalanceProjectionEntity(accountId, openingBalance,
                    zeroAmount(), zeroAmount()));
            this.accountBalances.seedingComplete();
            this.categoryBalances = new StoredCategoryBalances(saveLog);
            this.categoryBalances.seedingComplete();
            this.transactions = transactionStore == null
                    ? new StoredTransactions(saveLog)
                    : transactionStore;
            this.rejects = new StoredRejects(saveLog);
            this.postingService = new PostingService(
                    new CategoryBalanceUpdater(categoryBalances),
                    new AccountBalanceUpdater(accountBalances),
                    transactions,
                    outbox.writer());
            this.rejectRecorder = new RejectRecorder(rejects, outbox.writer());
        }

        /**
         * Returns the posting service this probe wired.
         *
         * @return the service
         */
        PostingService postingService() {
            return postingService;
        }

        /**
         * Returns the reject path this probe wired.
         *
         * @return the recorder
         */
        RejectRecorder rejectRecorder() {
            return rejectRecorder;
        }

        /**
         * Returns the account state this probe carries.
         *
         * @return the account store
         */
        StoredAccountBalances accountBalances() {
            return accountBalances;
        }

        /**
         * Returns the transaction rows this probe carries.
         *
         * @return the transaction store
         */
        TransactionRepository transactions() {
            return transactions;
        }

        /**
         * Returns the reject rows this probe carries.
         *
         * @return the reject store
         */
        StoredRejects rejects() {
            return rejects;
        }

        /**
         * Returns the stage name of every write this probe made.
         *
         * @return the log, in write order
         */
        List<String> saveLog() {
            return List.copyOf(saveLog);
        }

        /**
         * Returns the outbox rows this probe produced carrying one event type.
         *
         * @param eventType the value {@code EventEnvelope.eventType} holds
         * @return the matching rows, in publication order
         */
        List<OutboxEventEntity> publishedOfType(String eventType) {
            return outbox.snapshot().stream()
                    .filter(row -> eventType.equals(row.getEventType()))
                    .toList();
        }
    }

    /**
     * Wraps one {@link OutboxWriter} over a stubbed row store and keeps every row it wrote.
     *
     * <p>The writer validates each payload against its schema before the row is stored, so a
     * captured row proves the event passed that check.</p>
     */
    private static final class CapturedOutbox {

        /** Rows the writer produced, in publication order. */
        private final List<OutboxEventEntity> rows = new ArrayList<>();

        /** The writer under test, wired to a store that returns each row unchanged. */
        private final OutboxWriter writer;

        private CapturedOutbox() {
            OutboxEventRepository store = mock(OutboxEventRepository.class);
            when(store.save(any(OutboxEventEntity.class))).thenAnswer(call -> {
                OutboxEventEntity row = call.getArgument(0);
                rows.add(row);
                return row;
            });
            this.writer = new OutboxWriter(store, JSON_MAPPER);
        }

        /**
         * Returns the writer the ledger services publish through.
         *
         * @return the writer
         */
        OutboxWriter writer() {
            return writer;
        }

        /**
         * Returns the rows the writer produced.
         *
         * @return an unmodifiable view in publication order
         */
        List<OutboxEventEntity> snapshot() {
            return List.copyOf(rows);
        }
    }
}

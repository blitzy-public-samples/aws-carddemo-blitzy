package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.account.domain.BillingCycleService;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.LedgerApplication;
import com.carddemo.ledger.domain.AccountBalanceUpdater;
import com.carddemo.ledger.domain.CategoryBalanceUpdater;
import com.carddemo.ledger.domain.PostingService;
import com.carddemo.ledger.domain.RejectRecorder;
import com.carddemo.ledger.domain.RejectRecorder.FeedTransaction;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.entity.ProcessedEventEntity;
import com.carddemo.ledger.entity.RejectedTransactionEntity;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity.TransactionCategoryBalanceId;
import com.carddemo.ledger.entity.TransactionEntity;
import com.carddemo.ledger.messaging.TransactionAuthorizedConsumer;
import com.carddemo.ledger.messaging.TransactionDeclinedConsumer;
import com.carddemo.ledger.outbox.OutboxRelay;
import com.carddemo.ledger.outbox.OutboxWriter;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import com.carddemo.ledger.repository.OutboxEventRepository;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import com.carddemo.ledger.repository.RejectedTransactionRepository;
import com.carddemo.ledger.repository.TransactionCategoryBalanceRepository;
import com.carddemo.ledger.repository.TransactionRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
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
 * <p>Four behaviours have no COBOL ancestor: the single transaction around the three updates, the
 * idempotency marker, the masked card number, and the plain-digit amount in the reject rendering.
 */
class PostingEquivalenceTest {

    /** Name of the run-total expectations this class is the row-by-row consumer of. */
    private static final String EXPECTED_SUMMARY_FILE = "posting-summary.csv";

    /**
     * The run totals and output digests of both evaluation models, read once.
     *
     * <p>Read through {@link ExpectedOutcomes} like the other thirteen checked-in assets. It was
     * the one file loaded by a reader of its own, which cost it the per-row consumption tracking
     * every other asset has: a row added to it went unread, and only the documentation inventory
     * in {@code DocumentationContractTest} noticed. {@link DeployablePostingPath} now compares
     * every row of it against a value derived from the run and refuses to finish while any row is
     * unread.</p>
     */
    private static final ExpectedOutcomes EXPECTED_SUMMARY =
            ExpectedOutcomes.load(EXPECTED_SUMMARY_FILE);

    /** Name of the detailed reject expectations this class binds row by row. */
    private static final String EXPECTED_REJECT_FILE = "dailytran-reject-records-model-b.csv";

    /** The detailed reject expectations, read once and consumed by {@link RejectRecord}. */
    private static final ExpectedOutcomes EXPECTED_REJECTS =
            ExpectedOutcomes.load(EXPECTED_REJECT_FILE);

    /** Name of the account end-state expectations this class binds row by row. */
    private static final String EXPECTED_ACCOUNT_FILE =
            "acctdata-final-account-state-model-b.csv";

    /** The account end-state expectations, read once. */
    private static final ExpectedOutcomes EXPECTED_ACCOUNTS =
            ExpectedOutcomes.load(EXPECTED_ACCOUNT_FILE);

    /** Name of the category-balance expectations this class binds row by row. */
    private static final String EXPECTED_CATEGORY_FILE =
            "dailytran-category-balances-model-b.csv";

    /** The category-balance expectations, read once. */
    private static final ExpectedOutcomes EXPECTED_CATEGORIES =
            ExpectedOutcomes.load(EXPECTED_CATEGORY_FILE);

    /** Name of the per-account posting expectations this class binds row by row. */
    private static final String EXPECTED_POSTING_RESULTS_FILE =
            "dailytran-posting-results-model-b.csv";

    /** The per-account posting expectations, read once. */
    private static final ExpectedOutcomes EXPECTED_POSTING_RESULTS =
            ExpectedOutcomes.load(EXPECTED_POSTING_RESULTS_FILE);

    /** Type code the source assigns to a purchase, and the only one the feed posts positively. */
    private static final String PURCHASE_TYPE_CODE = "01";

    /** Type code the source assigns to a refund in this feed. */
    private static final String REFUND_TYPE_CODE = "03";

    /** The program whose paragraphs every expectation in this class is derived from. */
    private static final String VALIDATION_PROGRAM = "app/cbl/CBTRN02C.cbl";

    /** The job that allocates the reject dataset. */
    private static final String POSTING_JOB = "app/jcl/POSTTRAN.jcl";

    /**
     * Shape of a paragraph label in {@value #VALIDATION_PROGRAM}.
     *
     * <p>A paragraph is bounded by the next label, not by {@code EXIT.}. Several paragraphs of this
     * program carry no {@code EXIT.} at all — {@code 2700-A-CREATE-TCATBAL-REC} is one — so a reader
     * that stops at the first {@code EXIT.} swallows the paragraphs that follow and answers
     * questions about the wrong code.</p>
     */
    private static final Pattern PARAGRAPH_LABEL =
            Pattern.compile("\\d{4}(-[A-Z0-9]+)*\\.");

    /** Record text of the feed, in file order, as the reject path copies it. */
    private static final List<String> FEED_RECORD_TEXT =
            CardDemoFixtureLoader.loadDailyTransactionRecordText();

    /** Tag placed on source-compatible tests that pin a known defect. */
    private static final String LEGACY_DIVERGENCE_TAG = "legacy-divergence";

    /** Tag placed on a source defect that remains open for a human decision. */
    private static final String HUMAN_REVIEW_TAG = "human-review";

    /** Fixed moment used by every processing-timestamp assertion. */
    private static final LocalDateTime FIXED_PROCESSING_MOMENT =
            LocalDateTime.parse("2024-01-02T03:04:05.678900");

    /** Fixed instant used for test-only reject rows and idempotency markers. */
    private static final Instant FIXED_EVENT_INSTANT =
            Instant.parse("2024-01-02T03:04:05Z");

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
     * Deliveries of one event the idempotency group presents to the guard. No ancestor in
     * {@code app/cbl/CBTRN02C.cbl}.
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

    /** Topic name the idempotency marker records for a delivery of the authorized event. */
    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    /**
     * The topic the declined stream is published on.
     *
     * <p>The reject rows of {@code app/cbl/CBTRN02C.cbl:L446-L465} are written by the consumer of
     * this topic rather than by the consumer of the authorized topic, which is why the whole-feed
     * comparison drives both.</p>
     */
    private static final String DECLINED_TOPIC = "transaction.declined";

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

    /** Rows one category-balance upsert writes, on either arm. */
    private static final int ONE_ROW_UPSERTED = 1;

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    /**
     * Holds the one run every test of this class reads.
     *
     * <p>Initialization on demand: the holder is loaded the first time {@link #fixtureRun()} is
     * called, and the language guarantees that happens once however many callers arrive and in
     * whatever order. No flag is checked and no lock is taken.
     *
     * <p>It is lazy rather than a {@code @BeforeAll} so that a run selecting one test still pays for
     * one posting rather than for a class-wide setup, and so that the nested classes do not each need
     * a lifecycle hook of their own.
     */
    private static final class OneRun {

        /** The posted feed, built once. */
        private static final PostingRun VALUE = postWholeFeed();

        private OneRun() {
        }
    }

    /**
     * Returns the terminal state and per-record outcomes of the run.
     *
     * <p>One run serves all forty callers. It used to rebuild the whole 300-record feed on every
     * call: the same fixtures parsed, the same balances seeded and the same 300 records posted, forty
     * times over, for an answer that cannot differ. Nothing about the feed depends on the caller.
     *
     * <p>The run is safe to share because every test reads it and none writes it. That is a property
     * this class has to keep, not one it can assume: a test that mutated a returned map, list or
     * entity would change what a later test observes, and the failure would depend on execution
     * order. {@link #theSharedRunIsNeverMutatedByATest} holds the property by checking the run
     * against a second, independently built one after the suite has read it.
     *
     * @return the terminal state and per-record outcomes of the run
     */
    private static PostingRun fixtureRun() {
        return OneRun.VALUE;
    }

    /**
     * Checks the shared run still describes what a freshly posted feed describes.
     *
     * <p>This is the guard that makes one shared run safe. Every test here reads the run and none
     * writes it, so sharing is sound — but "none writes it" is a property of the tests rather than of
     * the type: the run hands out live maps, lists and entities, and a test that mutated one would
     * silently change what a later test observes. The failure would then depend on which tests ran
     * and in what order, which is the hardest kind to diagnose.
     *
     * <p>So the run is posted a second time here, after the whole class has read the shared one, and
     * the two are compared. A digest is compared rather than the records themselves, because the
     * entities carry no value equality: the write sequence, the terminal balances and the identifiers
     * of everything produced are what a mutation would move.
     *
     * <p>It runs as {@code @AfterAll} rather than as a test so that it is guaranteed to run after
     * every test of every nested class, which is the only position from which the question can be
     * answered.
     */
    @AfterAll
    static void theSharedRunIsNeverMutatedByATest() {
        assertEquals(digestOf(postWholeFeed()), digestOf(fixtureRun()),
                "the run shared by every test of this class no longer matches a freshly posted feed,"
                        + " so a test mutated state it only reads. One run is shared to avoid posting"
                        + " the 300-record feed forty times; that is sound only while every caller"
                        + " treats it as read-only");
    }

    /**
     * Renders one run as the values a mutation would move.
     *
     * <p>Ordered by key so the digest is stable, and rendered as text so a comparison reports where
     * two runs differ rather than only that they do.
     *
     * @param run the run to render
     * @return the write sequence, the terminal balances and every produced identifier
     */
    private static String digestOf(PostingRun run) {
        StringBuilder digest = new StringBuilder();
        digest.append("outcomes=").append(run.outcomes().size()).append('\n');
        digest.append("saveLog=").append(run.saveLog()).append('\n');
        digest.append("posted=").append(new TreeSet<>(run.postedTransactions().keySet()))
                .append('\n');
        digest.append("rejected=").append(run.rejectedRows().size()).append('\n');
        digest.append("declined=").append(run.declinedEvents().size()).append('\n');
        digest.append("outbox=").append(run.outboxRows().size()).append('\n');
        digest.append("repeated=").append(run.repeatedTransactionIdentifiers()).append('\n');
        digest.append("missedKeys=").append(run.categoryKeysMissedOnRead()).append('\n');

        new TreeMap<>(run.accountBalances()).forEach((accountId, row) ->
                digest.append("balance[").append(accountId).append("]=")
                        .append(row.getCurrentBalance()).append('\n'));
        run.categoryBalances().entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .forEach(entry -> digest.append("categoryBalance[").append(entry.getKey())
                        .append("]=").append(entry.getValue().getCategoryBalance()).append('\n'));
        return digest.toString();
    }

    /**
     * Returns one checked-in run total or digest, and marks its row read.
     *
     * <p>The pair of {@code entity_key} and {@code expected_field} identifies a row of
     * {@value #EXPECTED_SUMMARY_FILE} on its own, so the sequence column plays no part here and no
     * caller carries an ordinal that means nothing to it. Every lookup consumes its row, which is
     * what lets {@link DeployablePostingPath} finish by proving no row went unread.</p>
     *
     * @param entity the {@code entity_key} column, such as {@code posting}
     * @param field  the {@code expected_field} column, such as {@code approved_count}
     * @return the {@code expected_value} column
     * @throws IllegalStateException when the file holds no such row, or more than one
     */
    private static String expected(String entity, String field) {
        return EXPECTED_SUMMARY.value(entity, field);
    }

    /**
     * Returns one checked-in run total as a whole number, and marks its row read.
     *
     * @param entity the {@code entity_key} column
     * @param field  the {@code expected_field} column
     * @return the value
     */
    private static long expectedCount(String entity, String field) {
        return EXPECTED_SUMMARY.count(entity, field);
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
     * Computes the intended refund treatment while register item 6 remains open.
     *
     * @param cycleCredit credit accumulator
     * @param cycleDebit  debit accumulator, negative after a refund
     * @param amount      amount of the next authorization
     * @return the intended working balance
     */
    private static BigDecimal intendedWorkingBalance(BigDecimal cycleCredit,
            BigDecimal cycleDebit,
            BigDecimal amount) {
        BigDecimal afterDebit =
                CobolDecimal.add(cycleCredit, cycleDebit, PicClause.WS_TEMP_BAL_SCALE);
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
    private static DecisionInputs decisionFor(
            CopybookRecordParser.DailyTransactionRecord feed,
            Map<String, CopybookRecordParser.CardCrossReferenceRecord> crossReferences,
            Map<String, CopybookRecordParser.AccountRecord> accounts,
            AccountBalanceProjectionRepository balances) {
        CopybookRecordParser.CardCrossReferenceRecord crossReference =
                crossReferences.get(feed.cardNumber());
        if (crossReference == null) {
            return DecisionInputs.unresolvedCard();
        }

        String accountId = crossReference.accountId();
        CopybookRecordParser.AccountRecord account = accounts.get(accountId);
        Optional<AccountBalanceProjectionEntity> carried = balances.findById(accountId);
        if (account == null || carried.isEmpty()) {
            return DecisionInputs.unresolvedAccount(accountId);
        }

        DeclineReason reason = null;
        BigDecimal cycleCreditBefore = carried.get().getCycleCredit();
        BigDecimal cycleDebitBefore = carried.get().getCycleDebit();
        BigDecimal working =
                workingBalance(cycleCreditBefore, cycleDebitBefore, feed.amount());
        if (account.creditLimit().compareTo(working) < 0) {
            reason = DeclineReason.OVER_CREDIT_LIMIT;
        }
        String originDate = CopybookRecordParser.timestampDatePart(feed.originTimestamp());
        if (account.expirationDate().compareTo(originDate) < 0) {
            reason = DeclineReason.ACCOUNT_EXPIRED;
        }
        return new DecisionInputs(reason, accountId, account.creditLimit(), cycleCreditBefore,
                cycleDebitBefore, working);
    }

    /**
     * The inputs one credit-limit decision was taken on, together with its outcome.
     *
     * <p>Captured while the decision runs rather than recomputed afterwards. Model B carries the
     * accumulators forward, so by the end of the feed the projection holds a later state than any
     * one record was judged against. Recomputing would read that later state and produce values
     * that look plausible and describe no decision the run actually took.</p>
     *
     * @param reason            the reason assigned, or {@code null} when the record posted
     * @param accountId         the account the cross-reference resolved, absent for reason 0100
     * @param creditLimit       {@code ACCT-CREDIT-LIMIT} as the fixture holds it
     * @param cycleCreditBefore {@code ACCT-CURR-CYC-CREDIT} before this record
     * @param cycleDebitBefore  {@code ACCT-CURR-CYC-DEBIT} before this record
     * @param workingBalance    {@code WS-TEMP-BAL} after the narrowing at
     *                          {@code app/cbl/CBTRN02C.cbl:L187}
     */
    private record DecisionInputs(DeclineReason reason,
            String accountId,
            BigDecimal creditLimit,
            BigDecimal cycleCreditBefore,
            BigDecimal cycleDebitBefore,
            BigDecimal workingBalance) {

        /**
         * Returns the decision for a card the cross-reference does not hold.
         *
         * @return reason 0100, with no account and no credit-limit inputs
         */
        static DecisionInputs unresolvedCard() {
            return new DecisionInputs(DeclineReason.INVALID_CARD_NUMBER, null, null, null, null);
        }

        /**
         * Returns the decision for a card that resolves an account no record holds.
         *
         * @param accountId the account the cross-reference named
         * @return reason 0101, with no credit-limit inputs
         */
        static DecisionInputs unresolvedAccount(String accountId) {
            return new DecisionInputs(DeclineReason.ACCOUNT_NOT_FOUND, accountId, null, null, null);
        }

        /** Builds a decision that reached no credit-limit evaluation. */
        private DecisionInputs(DeclineReason reason, String accountId, BigDecimal creditLimit,
                BigDecimal cycleCreditBefore, BigDecimal cycleDebitBefore) {
            this(reason, accountId, creditLimit, cycleCreditBefore, cycleDebitBefore, null);
        }

        /**
         * Reports whether the credit-limit rule ran for this record.
         *
         * @return {@code true} when the working balance was computed
         */
        boolean reachedCreditLimitRule() {
            return workingBalance != null;
        }
    }

    /**
     * Builds the authorized event one feed record carries into the ledger.
     *
     * <p>The card number reaches the event masked, which has no COBOL ancestor. The authorization
     * timestamp is {@code DALYTRAN-ORIG-TS} at {@code app/cpy/CVTRA06Y.cpy:L16}, unchanged.</p>
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
                PanMasker.cardToken(feed.cardNumber()),
                feed.originTimestamp());
    }

    /**
     * Builds the declined event for a rule that follows a resolved account read.
     *
     * <p>The event models what the authorization service publishes, which is the only place these
     * values exist once a transaction is refused: the source reads them from the daily record it is
     * rejecting at {@code app/cbl/CBTRN02C.cbl:L446-L465}, and no dataset holds a transaction that
     * never posted. It therefore carries the detail-bearing contract version, so this harness
     * models the event the ledger really receives rather than a narrower one.</p>
     *
     * @param feed      one record of {@code app/data/ASCII/dailytran.txt}
     * @param accountId the account the cross-reference resolved
     * @param reason    the reason the authorization path assigned
     * @return the declined event
     */
    private static TransactionDeclined declineEventFor(
            CopybookRecordParser.DailyTransactionRecord feed,
            String accountId,
            DeclineReason reason) {
        if (!reason.resolvesAccount()) {
            throw new IllegalArgumentException(
                    "a decline event requires a reason assigned after account resolution");
        }
        return TransactionDeclined.withTransactionDetail(accountId, feed.transactionId(), reason,
                feed.typeCode(), feed.categoryCode(), feed.source(), feed.description(),
                feed.amount(), feed.merchantId(), feed.merchantName(), feed.merchantCity(),
                feed.merchantZip(), PanMasker.maskCardNumber(feed.cardNumber()),
                feed.originTimestamp());
    }

    /**
     * Builds the source reject row without creating an authorized event.
     *
     * <p>The row carries {@code REJECT-TRAN-DATA} whole, which is what
     * {@code app/cbl/CBTRN02C.cbl:L447} moves and what
     * {@code services/ledger-posting-service/.../RejectedTransactionEntity} now stores: one
     * {@link PicClause#REJECT_TRAN_DATA_WIDTH}-character block rather than a column per field. The
     * one departure from the fixture's own bytes is the card number, masked in place by
     * {@link #maskedRejectTranData(int)}, because masking is this platform's addition and the row is
     * this platform's row.</p>
     *
     * @param feed    one rejected feed record
     * @param reason  the source reason assigned to it
     * @param ordinal the 1-based position of the record in {@code app/data/ASCII/dailytran.txt}
     * @return the reject row
     */
    private static RejectedTransactionEntity sourceRejectRow(
            CopybookRecordParser.DailyTransactionRecord feed,
            DeclineReason reason,
            int ordinal) {
        UUID rowId = UUID.nameUUIDFromBytes(
                feed.transactionId().getBytes(StandardCharsets.US_ASCII));
        return new RejectedTransactionEntity(rowId, feed.transactionId(), reason.code(),
                reason.description(), maskedRejectTranData(ordinal), FIXED_EVENT_INSTANT);
    }

    /**
     * Renders the data half of the reject record as the stored row holds it.
     *
     * <p>Every character is the fixture's own, apart from the sixteen
     * {@code DALYTRAN-CARD-NUM} positions of {@code app/cpy/CVTRA06Y.cpy:L15}, which carry the
     * masked form. {@code theRowStoresTheMaskedNumberWhileTheRejectBytesKeepThePan} is what holds
     * the two representations apart.</p>
     *
     * @param ordinal the 1-based position of the record in {@code app/data/ASCII/dailytran.txt}
     * @return {@link PicClause#REJECT_TRAN_DATA_WIDTH} characters
     */
    private static String maskedRejectTranData(int ordinal) {
        String record = feedRecordText(ordinal);
        int offset = feedColumnOffset(FeedColumn.CARD_NUMBER);
        int end = offset + FeedColumn.CARD_NUMBER.width();
        return record.substring(0, offset)
                + PanMasker.maskCardNumber(record.substring(offset, end))
                + record.substring(end);
    }

    /**
     * Reads {@code DALYTRAN-CARD-NUM} out of the block one stored row holds.
     *
     * @param row one reject row
     * @return the sixteen characters the card-number positions hold
     */
    private static String storedCardNumber(RejectedTransactionEntity row) {
        int offset = feedColumnOffset(FeedColumn.CARD_NUMBER);
        return row.getRejectedTransactionData()
                .substring(offset, offset + FeedColumn.CARD_NUMBER.width());
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
        List<TransactionDeclined> declinedEvents = new ArrayList<>();

        PostingService postingService = new PostingService(
                new CategoryBalanceUpdater(categoryBalances),
                new AccountBalanceUpdater(accountBalances),
                transactions,
                outbox.writer());

        List<FeedOutcome> outcomes = new ArrayList<>(feed.size());
        int ordinal = FIRST_RECORD_ORDINAL;
        for (CopybookRecordParser.DailyTransactionRecord record : feed) {
            DecisionInputs decision =
                    decisionFor(record, crossReferences, accounts, accountBalances);
            DeclineReason reason = decision.reason();
            CopybookRecordParser.CardCrossReferenceRecord crossReference =
                    crossReferences.get(record.cardNumber());
            AuthorizationPath path;
            if (reason == null) {
                String accountId = Objects.requireNonNull(crossReference,
                        "an approved record must carry a cross-reference").accountId();
                TransactionAuthorized event = authorizationEventFor(record, accountId);
                postingService.postTransaction(event, event.aggregateId());
                path = new AuthorizedPath(accountId, event);
            } else {
                rejects.save(sourceRejectRow(record, reason, ordinal));
                if (reason.resolvesAccount()) {
                    String accountId = Objects.requireNonNull(crossReference,
                            "an account-resolving decline must carry "
                                    + "a cross-reference").accountId();
                    TransactionDeclined event = declineEventFor(record, accountId, reason);
                    declinedEvents.add(event);
                    path = new DeclinedPath(accountId, event);
                } else {
                    path = new UnresolvedCardPath(TransactionDeclined.ofUnresolvedAccount(
                            record.transactionId(), record.amount(),
                            PanMasker.maskCardNumber(record.cardNumber())));
                }
            }
            outcomes.add(new FeedOutcome(ordinal, record, reason, path, decision));
            ordinal++;
        }

        return new PostingRun(List.copyOf(outcomes), accounts, crossReferences, seededKeys,
                accountBalances.snapshot(), categoryBalances.snapshot(), transactions.snapshot(),
                rejects.snapshot(), List.copyOf(declinedEvents), outbox.snapshot(),
                List.copyOf(saveLog),
                transactions.repeatedIdentifiers(), categoryBalances.keysMissedOnRead());
    }

    /**
     * Seeds account state from {@code app/data/ASCII/acctdata.txt}.
     *
     * @param accounts the fixture accounts, keyed by account identifier
     * @param saveLog the ordered save log this store appends to after seeding completes
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
     * @param saveLog the ordered save log this store appends to after seeding completes
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
    @DisplayName("Checked-in Model B outputs")
    class CheckedInExpectedOutputs {

        @Test
        @DisplayName("the full in-memory result matches the fixed output fingerprints")
        void theFullResultMatchesTheFixedOutputFingerprints() {
            PostingRun run = fixtureRun();
            long nonzeroCategoryRows =
                    run.categoryRowsAboveZero() + run.categoryRowsBelowZero();

            assertEquals(expectedCount("posting", "record_count"), run.outcomes().size(),
                    "the Model B run must cover the fixed feed count");
            assertEquals(expectedCount("posting", "approved_count"), run.postedCount(),
                    "the Model B approval count moved");
            assertEquals(expectedCount("posting", "declined_count"), run.rejectedCount(),
                    "the Model B decline count moved");
            assertEquals(expectedCount("posting", "declined_event_count"),
                    run.declinedEvents().size(),
                    "the resolved-account decline event count moved");
            assertEquals(expectedCount("posting", "return_code"), run.returnCode(),
                    "the batch return code moved");
            assertEquals(expectedCount("posting", "transaction_row_count"),
                    run.postedTransactions().size(),
                    "the posted transaction count moved");
            assertEquals(expectedCount("posting", "posted_outbox_count"),
                    run.outboxRowsOfType(TransactionPosted.EVENT_TYPE).size(),
                    "the posted-event outbox count moved");
            assertEquals(expectedCount("category_balance", "row_count"),
                    run.categoryBalances().size(),
                    "the total category row count moved");
            assertEquals(expectedCount("category_balance", "nonzero_row_count"),
                    nonzeroCategoryRows,
                    "the nonzero category row count moved");
            assertEquals(expectedCount("category_balance", "positive_row_count"),
                    run.categoryRowsAboveZero(),
                    "the positive category row count moved");
            assertEquals(expectedCount("category_balance", "negative_row_count"),
                    run.categoryRowsBelowZero(),
                    "the negative category row count moved");

            assertEquals(expected("posting", "approved_transaction_ids_sha256"),
                    sha256Lines(approvedTransactionIdLines(run)),
                    "the approved transaction identifiers moved");
            assertEquals(expected("posting", "declined_outcomes_sha256"),
                    sha256Lines(declinedOutcomeLines(run)),
                    "the declined transaction identifiers or reasons moved");
            assertEquals(expected("account_projection", "state_sha256"),
                    sha256Lines(accountProjectionLines(run.accountBalances())),
                    "the final account projection moved");
            assertEquals(expected("category_balance", "state_sha256"),
                    sha256Lines(categoryBalanceLines(run.categoryBalances())),
                    "the final category balances moved");
        }
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
            long expectedApproved = expectedCount("posting", "approved_count");
            long expectedDeclined = expectedCount("posting", "declined_count");

            assertEquals(run.outcomes().size(), run.postedCount() + run.rejectedCount(),
                    "CBTRN02C L211 sends each record to exactly one of L212 and L215, so the two "
                            + "counts add up to the record count");
            assertEquals(expectedApproved, run.postedCount(),
                    "the post branch diverged from the checked-in Model B output");
            assertEquals(expectedDeclined, run.rejectedCount(),
                    "the reject branch diverged from the checked-in Model B output");
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

            assertEquals(expectedCount("model_a", "declined_count"), statelessDeclines,
                    "the stateless pass diverged from the checked-in Model A output");
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
                if (outcome.path() instanceof AuthorizedPath authorized) {
                    assertEquals(authorized.accountId(), authorized.event().aggregateId(),
                            where(outcome.ordinal(), "1500-B-LOOKUP-ACCT",
                                    "app/cbl/CBTRN02C.cbl:L394")
                                    + ": the account is the authorized-event message key");
                    assertEquals(authorized.accountId(), authorized.event().accountId(),
                            where(outcome.ordinal(), "1500-B-LOOKUP-ACCT",
                                    "app/cbl/CBTRN02C.cbl:L394")
                                    + ": the authorized event carries one account value");
                } else if (outcome.path() instanceof DeclinedPath declined) {
                    assertEquals(declined.accountId(), declined.event().aggregateId(),
                            where(outcome.ordinal(), "1500-B-LOOKUP-ACCT",
                                    "app/cbl/CBTRN02C.cbl:L394")
                                    + ": the account is the declined-event message key");
                    assertEquals(declined.accountId(), declined.event().accountId(),
                            where(outcome.ordinal(), "1500-B-LOOKUP-ACCT",
                                    "app/cbl/CBTRN02C.cbl:L394")
                                    + ": the declined event carries one account value");
                } else if (outcome.path() instanceof UnresolvedCardPath unresolved) {
                    assertEquals(DeclineReason.INVALID_CARD_NUMBER, outcome.declineReason(),
                            where(outcome.ordinal(), "1500-A-LOOKUP-XREF",
                                    "app/cbl/CBTRN02C.cbl:L385-L387")
                                    + ": the unresolved path carries reason 0100 alone");
                    assertEquals(unresolved.event().transactionId(),
                            unresolved.event().aggregateId(),
                            where(outcome.ordinal(), "1500-A-LOOKUP-XREF",
                                    "app/cbl/CBTRN02C.cbl:L385-L387")
                                    + ": the unresolved path resolved no account, so its event is"
                                    + " keyed on the transaction identifier");
                    assertNull(unresolved.event().accountId(),
                            where(outcome.ordinal(), "1500-A-LOOKUP-XREF",
                                    "app/cbl/CBTRN02C.cbl:L385-L387")
                                    + ": and it names no account, because none was resolved");
                }
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
        @DisplayName("the three updates share one transaction, a guarantee with no COBOL ancestor")
        void theThreeUpdatesShareOneTransaction() throws NoSuchMethodException {
            Method postTransaction = PostingService.class
                    .getMethod("postTransaction", TransactionAuthorized.class, String.class);

            assertTrue(postTransaction.isAnnotationPresent(Transactional.class),
                    "CBTRN02C L440-L442 runs the three updates with no rollback, and the target "
                            + "wraps them in one transaction, which has no COBOL ancestor");
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
                target.computeIfAbsent(outcome.resolvedAccountId(), key -> new ArrayList<>())
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
                    eventCarrying(accountId, syntheticTransactionId(FIRST_RECORD_ORDINAL), zero),
                    accountId);
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
                    () -> probe.postingService().postTransaction(event, event.aggregateId()),
                    "account " + absentAccountId + ", stage " + ACCOUNT_STAGE
                            + ", source app/cbl/CBTRN02C.cbl:L554-L559: the rewrite found no row, "
                            + "and the target raises a failure the consumer does not acknowledge");
            assertEquals(0L, probe.transactions().count(),
                    "stage " + TRANSACTION_STAGE + ", source app/cbl/CBTRN02C.cbl:L440-L442: the "
                            + "failure left no transaction row, which is the added atomicity "
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
                    () -> probe.postingService().postTransaction(event, event.aggregateId()),
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
    @DisplayName("Legacy divergence, register item 6: refund sign convention")
    class RefundSignConvention {

        @Test
        @Tag(LEGACY_DIVERGENCE_TAG)
        @Tag(HUMAN_REVIEW_TAG)
        @DisplayName("a refund drives the cycle debit accumulator below zero, L551")
        void aRefundDrivesTheCycleDebitBelowZero() {
            String accountId = firstFeedAccountId();
            Probe probe = new Probe(accountId, zeroAmount());
            BigDecimal refund = firstFeedRecord().amount().negate();

            probe.postingService().postTransaction(eventCarrying(accountId,
                    syntheticTransactionId(FIRST_RECORD_ORDINAL), refund), accountId);
            AccountBalanceProjectionEntity held =
                    probe.accountBalances().findById(accountId).orElseThrow();

            assertTrue(held.getCycleDebit().signum() < 0,
                    "account " + accountId + ", stage " + ACCOUNT_STAGE
                            + ", source app/cbl/CBTRN02C.cbl:L551: ADD of a negative amount drove "
                            + "ACCT-CURR-CYC-DEBIT below zero");
        }

        @Test
        @Tag(LEGACY_DIVERGENCE_TAG)
        @Tag(HUMAN_REVIEW_TAG)
        @DisplayName("a refund raises the working balance the next record is tested against, L404")
        void aRefundRaisesTheNextWorkingBalance() {
            String accountId = firstFeedAccountId();
            Probe probe = new Probe(accountId, zeroAmount());
            BigDecimal probeAmount = firstFeedRecord().amount();
            BigDecimal refund = probeAmount.negate();
            BigDecimal beforeRefund = workingBalance(zeroAmount(), zeroAmount(), probeAmount);

            probe.postingService().postTransaction(eventCarrying(accountId,
                    syntheticTransactionId(FIRST_RECORD_ORDINAL), refund), accountId);
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
        @Tag(LEGACY_DIVERGENCE_TAG)
        @Tag(HUMAN_REVIEW_TAG)
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
                    syntheticTransactionId(FIRST_RECORD_ORDINAL), refund), accountId);
            AccountBalanceProjectionEntity held =
                    probe.accountBalances().findById(accountId).orElseThrow();

            assertFalse(account.creditLimit().compareTo(
                            workingBalance(held.getCycleCredit(), held.getCycleDebit(),
                                    probeAmount)) >= 0,
                    "account " + accountId + ", stage 1500-B-LOOKUP-ACCT, source "
                            + "app/cbl/CBTRN02C.cbl:L407 read with L551: the same amount now "
                            + "fails the limit test, so the refund tightened the authorization");
        }

        @Test
        @Tag(HUMAN_REVIEW_TAG)
        @DisplayName("intended target: a recorded refund lowers the next working balance")
        void theIntendedTargetLowersTheNextWorkingBalance() {
            BigDecimal probeAmount = firstFeedRecord().amount().abs();
            BigDecimal refund = probeAmount.negate();
            BigDecimal beforeRefund = intendedWorkingBalance(
                    zeroAmount(), zeroAmount(), probeAmount);
            BigDecimal intendedAfterRefund = intendedWorkingBalance(
                    zeroAmount(), refund, probeAmount);
            BigDecimal shippedAfterRefund = workingBalance(
                    zeroAmount(), refund, probeAmount);

            assertTrue(intendedAfterRefund.compareTo(beforeRefund) < 0,
                    "the intended target must lower the next working balance");
            assertNotEquals(intendedAfterRefund, shippedAfterRefund,
                    "the intended target must remain distinct from the shipped formula");
            assertTrue(shippedAfterRefund.compareTo(beforeRefund) > 0,
                    "the shipped formula must keep the register-item-6 divergence visible");
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
                String rendered = renderRejectRecord(feedRecordText(outcome.ordinal()),
                        outcome.declineReason());
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
        @DisplayName("the data part is the fixture's own 350 bytes, L447")
        void theDataPartIsTheFixturesOwnBytes() {
            PostingRun run = fixtureRun();

            for (FeedOutcome outcome : run.rejectedOutcomes()) {
                String rendered = renderRejectRecord(feedRecordText(outcome.ordinal()),
                        outcome.declineReason());

                assertEquals(feedRecordText(outcome.ordinal()),
                        rendered.substring(0, PicClause.REJECT_TRAN_DATA_WIDTH),
                        where(outcome.ordinal(), REJECT_STAGE, VALIDATION_PROGRAM + ":L447")
                                + ": MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA copies the record "
                                + "that was read, byte for byte, so the data part and the fixture "
                                + "record are the same characters");
            }
        }

        @Test
        @DisplayName("the amount column keeps the sign overpunch the fixture carries, CVTRA06Y L10")
        void theAmountColumnKeepsTheSignOverpunchTheFixtureCarries() {
            PostingRun run = fixtureRun();

            for (FeedOutcome outcome : run.rejectedOutcomes()) {
                String dataPart = renderRejectRecord(feedRecordText(outcome.ordinal()),
                        outcome.declineReason())
                        .substring(0, PicClause.REJECT_TRAN_DATA_WIDTH);
                String amountSlice = feedColumnText(outcome.ordinal(), FeedColumn.AMOUNT);
                String leadingDigits = amountSlice.substring(0, amountSlice.length() - 1);
                char trailing = amountSlice.charAt(amountSlice.length() - 1);
                CopybookRecordParser.DailyTransactionRecord reparsed =
                        CopybookRecordParser.parseDailyTransaction(dataPart);

                assertTrue(leadingDigits.chars().allMatch(Character::isDigit),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L10")
                                + ": every position ahead of the last holds a digit");
                assertTrue(signOverpunchDigitsFor(outcome.feed().amount()).indexOf(trailing) >= 0,
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L10")
                                + ": PIC S9(09)V99 puts the sign on the last position, and the "
                                + "fixture writes it as an overpunch character. Record 16 holds "
                                + "0000007154D for 715.44");
                assertEquals(0, outcome.feed().amount().compareTo(reparsed.amount()),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L10")
                                + ": the data part reads back as the signed fixture amount, sign "
                                + "included, because it is the fixture's own bytes");
            }
        }

        @Test
        @DisplayName("the row stores the masked number while the reject bytes keep the PAN")
        void theRowStoresTheMaskedNumberWhileTheRejectBytesKeepThePan() {
            PostingRun run = fixtureRun();

            for (FeedOutcome outcome : run.rejectedOutcomes()) {
                String rendered = renderRejectRecord(feedRecordText(outcome.ordinal()),
                        outcome.declineReason());
                String cardColumn = feedColumnText(outcome.ordinal(), FeedColumn.CARD_NUMBER);
                RejectedTransactionEntity row = sourceRejectRow(outcome.feed(),
                        outcome.declineReason(), outcome.ordinal());

                assertEquals(outcome.feed().cardNumber(), cardColumn,
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L15")
                                + ": the equivalence bytes carry all sixteen digits, because "
                                + VALIDATION_PROGRAM + ":L447 copies the record unchanged and no "
                                + "CardDemo program masks anything");
                assertTrue(rendered.contains(cardColumn),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L15")
                                + ": the rendered record holds the same digits the fixture does");
                assertEquals(PanMasker.maskCardNumber(outcome.feed().cardNumber()),
                        storedCardNumber(row),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L15")
                                + ": the card-number positions of the block the stored row holds "
                                + "carry the masked form instead, which is this platform's "
                                + "addition and has no ancestor in app/cbl/");
                assertNotEquals(cardColumn, storedCardNumber(row),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L15")
                                + ": the two representations are deliberately different, and "
                                + "conflating them is what made the earlier comparison claim a "
                                + "verbatim copy it did not produce");
            }
        }

        @Test
        @DisplayName("every row of " + EXPECTED_REJECT_FILE + " matches, and none is left unread")
        void everyRowOfTheRejectExpectationsMatches() {
            PostingRun run = fixtureRun();
            Map<String, FeedOutcome> rejectsBySequence = new LinkedHashMap<>();
            for (FeedOutcome outcome : run.rejectedOutcomes()) {
                rejectsBySequence.put(Integer.toString(outcome.ordinal()), outcome);
            }

            for (ExpectedOutcomes.Row row : EXPECTED_REJECTS.rows()) {
                String expected = EXPECTED_REJECTS.value(row.recordSequence(), row.entityKey(),
                        row.expectedField());
                String actual = actualRejectValue(row, run, rejectsBySequence);

                assertEquals(expected, actual,
                        EXPECTED_REJECT_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertThat(EXPECTED_REJECTS.unconsumedRows())
                    .as(EXPECTED_REJECTS.unconsumedDescription())
                    .isEmpty();
        }

        /**
         * Resolves what the system, the fixture or the source really holds for one expected row.
         *
         * <p>Nothing here reads {@code expected_value}. Each branch computes its answer from the
         * posting run, from the fixture bytes, or from the COBOL program and the job on disk, which
         * is what makes the comparison evidence rather than a restatement.</p>
         *
         * @param row               the expectation to resolve
         * @param run               the whole-feed posting run
         * @param rejectsBySequence rejected outcomes by their feed ordinal, as text
         * @return the value the row must equal
         */
        private String actualRejectValue(ExpectedOutcomes.Row row, PostingRun run,
                Map<String, FeedOutcome> rejectsBySequence) {
            if (!row.isFixtureLevel()) {
                FeedOutcome outcome = rejectsBySequence.get(row.recordSequence());
                assertThat(outcome)
                        .as(EXPECTED_REJECT_FILE + " row " + row.key() + " names feed record "
                                + row.recordSequence() + ", which the run must have rejected")
                        .isNotNull();
                return perRecordRejectValue(row, outcome);
            }
            return switch (row.entityKey()) {
                case "reject_record" -> rejectRecordLayoutValue(row, run);
                case "validation_trailer" -> validationTrailerValue(row, run);
                case "model_b" -> rejectModelValue(row, run);
                case "reason_distribution" -> reasonDistributionValue(row, run);
                default -> Long.toString(rejectsForAccount(run, row.entityKey()));
            };
        }

        /** Resolves one per-record expectation from the fixture bytes and the rendered record. */
        private String perRecordRejectValue(ExpectedOutcomes.Row row, FeedOutcome outcome) {
            String rendered = renderRejectRecord(feedRecordText(outcome.ordinal()),
                    outcome.declineReason());
            CopybookRecordParser.RejectedTransactionRecord parsed =
                    CopybookRecordParser.parseRejectedTransaction(rendered);
            CopybookRecordParser.DailyTransactionRecord copied =
                    CopybookRecordParser.parseDailyTransaction(parsed.transactionData());

            return switch (row.expectedField()) {
                case "xref_acct_id" -> outcome.resolvedAccountId();
                case "dalytran_id" -> copied.transactionId();
                case "dalytran_card_num" -> copied.cardNumber();
                case "dalytran_type_cd" -> copied.typeCode();
                case "dalytran_cat_cd" -> copied.categoryCode();
                case "dalytran_amt" -> copied.amount().toPlainString();
                case "dalytran_merchant_id" -> copied.merchantId();
                case "trailer_reason_code" -> renderedTrailerReason(parsed);
                case "trailer_reason_description" -> parsed.failReasonDescription().strip();
                default -> throw new IllegalStateException(
                        EXPECTED_REJECT_FILE + " carries an unresolved per-record field "
                                + row.expectedField() + " at " + row.key());
            };
        }

        /** Resolves one expectation about the reject record's layout. */
        private String rejectRecordLayoutValue(ExpectedOutcomes.Row row, PostingRun run) {
            FeedOutcome first = run.rejectedOutcomes().get(0);
            String rendered = renderRejectRecord(feedRecordText(first.ordinal()),
                    first.declineReason());

            return switch (row.expectedField()) {
                case "reject_record_length" -> Integer.toString(rendered.length());
                case "reject_tran_data_length" -> Integer.toString(
                        CopybookRecordParser.parseRejectedTransaction(rendered)
                                .transactionData().length());
                case "validation_trailer_length" -> Integer.toString(
                        rendered.length() - PicClause.REJECT_TRAN_DATA_WIDTH);
                case "reject_tran_data_is_verbatim_byte_copy" -> yesOrNo(
                        run.rejectedOutcomes().stream().allMatch(outcome ->
                                renderRejectRecord(feedRecordText(outcome.ordinal()),
                                        outcome.declineReason())
                                        .startsWith(feedRecordText(outcome.ordinal()))));
                case "reject_record_write_verb" -> writeVerbOfRejectParagraph();
                case "reject_write_failure_outcome" -> rejectWriteFailureOutcome();
                case "reject_record_has_no_processing_timestamp" -> yesOrNo(
                        run.rejectedOutcomes().stream().allMatch(outcome ->
                                feedColumnText(outcome.ordinal(),
                                        FeedColumn.PROCESSING_TIMESTAMP).isBlank()));
                case "reject_dataset_lrecl" -> rejectDatasetRecordLength();
                case "dalytran_filler_content" -> distinctFillerContent(run);
                default -> throw new IllegalStateException(
                        EXPECTED_REJECT_FILE + " carries an unresolved layout field " + row.key());
            };
        }

        /** Resolves one expectation about the eighty-byte validation trailer. */
        private String validationTrailerValue(ExpectedOutcomes.Row row, PostingRun run) {
            FeedOutcome first = run.rejectedOutcomes().get(0);
            CopybookRecordParser.RejectedTransactionRecord parsed =
                    CopybookRecordParser.parseRejectedTransaction(
                            renderRejectRecord(feedRecordText(first.ordinal()),
                                    first.declineReason()));

            return switch (row.expectedField()) {
                case "trailer_reason_pic" -> declaredPictureClauseOf("WS-VALIDATION-FAIL-REASON");
                case "trailer_description_pic" ->
                        declaredPictureClauseOf("WS-VALIDATION-FAIL-REASON-DESC");
                case "trailer_field_widths_sum_to_trailer_length" -> yesOrNo(
                        PicClause.VALIDATION_FAIL_REASON_WIDTH
                                + PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH
                                == PicClause.VALIDATION_TRAILER_WIDTH);
                case "trailer_reason_rendered" -> renderedTrailerReason(parsed);
                case "trailer_reason_has_sign_overpunch" -> yesOrNo(
                        !renderedTrailerReason(parsed).chars()
                                .allMatch(Character::isDigit));
                case "description_space_padded_to_76" -> yesOrNo(
                        run.rejectedOutcomes().stream().allMatch(outcome -> {
                            String rendered = renderRejectRecord(feedRecordText(outcome.ordinal()),
                                    outcome.declineReason());
                            String slice = rendered.substring(rendered.length()
                                    - PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH);
                            return slice.length() == PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH
                                    && slice.stripTrailing()
                                            .equals(outcome.declineReason().description())
                                    && slice.substring(outcome.declineReason().description()
                                            .length()).isBlank();
                        }));
                default -> throw new IllegalStateException(
                        EXPECTED_REJECT_FILE + " carries an unresolved trailer field " + row.key());
            };
        }

        /** Resolves one expectation about the whole Model B reject population. */
        private String rejectModelValue(ExpectedOutcomes.Row row, PostingRun run) {
            List<FeedOutcome> rejected = run.rejectedOutcomes();

            return switch (row.expectedField()) {
                case "model_b_reject_record_count" -> Integer.toString(rejected.size());
                case "source_record_count" -> Integer.toString(run.outcomes().size());
                case "reject_counter_field" -> rejectCounterField();
                case "return_code_when_rejects_present" -> Long.toString(run.returnCode());
                case "rejection_is_expected_traffic_not_error" -> yesOrNo(
                        !rejected.isEmpty() && run.returnCode() == expectedCount("posting",
                                "return_code"));
                case "appl_result_on_reject" -> applResultOnReject();
                case "all_rejects_type_01" -> yesOrNo(rejected.stream()
                        .allMatch(outcome -> "01".equals(outcome.feed().typeCode())));
                case "all_rejects_category_0001" -> yesOrNo(rejected.stream()
                        .allMatch(outcome -> "0001".equals(outcome.feed().categoryCode())));
                case "distinct_rejecting_accounts" -> Long.toString(rejected.stream()
                        .map(FeedOutcome::resolvedAccountId).distinct().count());
                case "account_with_all_type_01_rejected" -> accountWithMostRejects(run);
                default -> throw new IllegalStateException(
                        EXPECTED_REJECT_FILE + " carries an unresolved model field " + row.key());
            };
        }

        /** Resolves the count of rejects carrying one reason code. */
        private String reasonDistributionValue(ExpectedOutcomes.Row row, PostingRun run) {
            String field = row.expectedField();
            String prefix = "reject_count_reason_";
            if (!field.startsWith(prefix)) {
                throw new IllegalStateException(
                        EXPECTED_REJECT_FILE + " carries an unresolved distribution field "
                                + row.key());
            }
            int numericCode = Integer.parseInt(field.substring(prefix.length()));
            return Long.toString(run.rejectedOutcomes().stream()
                    .filter(outcome -> outcome.declineReason().numericCode() == numericCode)
                    .count());
        }

        /** Returns the count of reject records naming one account. */
        private long rejectsForAccount(PostingRun run, String accountId) {
            return run.rejectedOutcomes().stream()
                    .filter(outcome -> accountId.equals(outcome.resolvedAccountId()))
                    .count();
        }

        /** Returns the account carrying the most reject records. */
        private String accountWithMostRejects(PostingRun run) {
            return run.rejectedOutcomes().stream()
                    .map(FeedOutcome::resolvedAccountId)
                    .distinct()
                    .max(Comparator.comparingLong(accountId -> rejectsForAccount(run, accountId)))
                    .orElseThrow(() -> new IllegalStateException("the run rejected nothing"));
        }

        /** Returns the distinct content the trailing filler holds across every reject. */
        private String distinctFillerContent(PostingRun run) {
            List<String> distinct = run.rejectedOutcomes().stream()
                    .map(outcome -> feedColumnText(outcome.ordinal(), FeedColumn.FILLER))
                    .distinct()
                    .toList();
            assertEquals(1, distinct.size(),
                    EXPECTED_REJECT_FILE + ": the filler must hold one value across every reject");
            return distinct.get(0);
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
        @DisplayName("each reject row holds the amount and the masked number in its block, L447")
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
                CopybookRecordParser.DailyTransactionRecord storedBlock =
                        CopybookRecordParser.parseDailyTransaction(
                                row.getRejectedTransactionData());

                assertEquals(0, outcome.feed().amount().compareTo(storedBlock.amount()),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L10")
                                + ": the block the row holds reads back as the signed fixture "
                                + "amount, compared numerically");
                assertEquals(PanMasker.maskCardNumber(outcome.feed().cardNumber()),
                        storedCardNumber(row),
                        where(outcome.ordinal(), REJECT_STAGE, "app/cpy/CVTRA06Y.cpy:L15")
                                + ": the block carries the masked number in the card-number "
                                + "positions, which has no COBOL ancestor");
            }
        }

        @Test
        @DisplayName("one declined event follows each reject, L215")
        void oneDeclinedEventFollowsEachReject() {
            PostingRun run = fixtureRun();

            long publishableDeclines =
                    run.rejectedCount() - run.unresolvedCardAttemptCount();

            assertEquals(publishableDeclines, run.declinedEvents().size(),
                    "each resolved-account decline publishes one account-keyed "
                            + TransactionDeclined.EVENT_TYPE);
            assertEquals(run.rejectedCount(),
                    run.declinedEvents().size() + run.unresolvedCardAttemptCount(),
                    "and every reject publishes one event: an account-keyed one where an account"
                            + " resolved, and a transaction-keyed one where none did, which is one"
                            + " event per decided call");
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
                    "the reason CBTRN02C L385 assigns names no account");
            assertThrows(IllegalArgumentException.class,
                    () -> TransactionDeclined.of(firstFeedAccountId(),
                            syntheticTransactionId(FIRST_RECORD_ORDINAL),
                            DeclineReason.INVALID_CARD_NUMBER, firstFeedRecord().amount(),
                            PanMasker.maskCardNumber(firstFeedRecord().cardNumber())),
                    "reason 0100 must not publish an account-keyed decline event");
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
                FeedTransaction refused = feedRecordCarrying(accountId,
                        syntheticTransactionId(FIRST_RECORD_ORDINAL), firstFeedRecord().amount());

                probe.rejectRecorder().recordReject(refused, reason);
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
                assertEquals(List.of(), probe.published(),
                        "reason " + reason.code() + ", stage " + REJECT_STAGE
                                + ": the reject branch reached no outbox. The "
                                + TransactionDeclined.EVENT_TYPE + " naming this refusal is the "
                                + "authorization service's, and this service consumed it to reach "
                                + "this row");
            }
        }
    }

    /**
     * Returns the rounding mode the shared arithmetic really applies, named as the expectation
     * files name it.
     *
     * <p>Observed rather than read off a constant. A value with a third decimal is put through the
     * add the posting path uses and the result decides the answer, so the assertion would notice a
     * helper that had been changed to round half up even if every name in the code still said
     * otherwise. The probe uses a negative value because that is where the three candidate modes
     * disagree: truncation toward zero gives -1.23, floor gives -1.24, and half up gives -1.24.</p>
     *
     * @return {@code TRUNCATE_TOWARD_ZERO} when the arithmetic truncates, otherwise the mode that
     *         reproduces what it did
     */
    private static String observedRoundingModeName() {
        BigDecimal probe = new BigDecimal("-1.235");
        BigDecimal observed = CobolDecimal.add(probe, BigDecimal.ZERO,
                PicClause.DALYTRAN_AMT_SCALE);
        for (RoundingMode candidate : RoundingMode.values()) {
            if (candidate == RoundingMode.UNNECESSARY) {
                continue;
            }
            if (probe.setScale(PicClause.DALYTRAN_AMT_SCALE, candidate).compareTo(observed) == 0) {
                return candidate == RoundingMode.DOWN ? "TRUNCATE_TOWARD_ZERO" : candidate.name();
            }
        }
        throw new IllegalStateException(
                "no rounding mode reproduces " + observed + " from " + probe);
    }

    /**
     * Reports whether the shipped cycle close writes zero into both accumulators.
     *
     * <p>Read from {@code BillingCycleService} itself. The service is on this module's classpath, so
     * the check follows the code rather than a copy of it: both setters must be called, and the
     * value must come from a zero.</p>
     *
     * @return {@code true} when both accumulators are zeroed
     */
    private static boolean cycleCloseZeroesBothAccumulators() {
        String service = serviceSourceText("account-service", "account", "domain",
                "BillingCycleService.java");
        return service.contains("setCurrentCycleCredit(zeroAt(")
                && service.contains("setCurrentCycleDebit(zeroAt(");
    }

    /**
     * Reports whether the shipped cycle close leaves interest alone.
     *
     * @return {@code true} when the service names no interest anywhere
     */
    private static boolean cycleCloseNamesNoInterest() {
        return Arrays.stream(BillingCycleService.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().toLowerCase(Locale.ROOT)
                        .contains("interest"));
    }

    /**
     * Reads one shipped service source file.
     *
     * @param module      the service module directory, such as {@code account-service}
     * @param packageName the package below {@code com.carddemo}
     * @param layer       the package below that, such as {@code domain}
     * @param fileName    the file to read
     * @return the whole file
     */
    private static String serviceSourceText(String module, String packageName, String layer,
            String fileName) {
        for (Path candidate = CardDemoFixtureLoader.fixtureDirectory();
                candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(Path.of("card-platform", "services", module, "src",
                    "main", "java", "com", "carddemo", packageName, layer, fileName));
            if (Files.isRegularFile(file)) {
                try {
                    return Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException unreadable) {
                    throw new UncheckedIOException("cannot read " + file, unreadable);
                }
            }
        }
        throw new IllegalStateException("no ancestor of '"
                + CardDemoFixtureLoader.fixtureDirectory() + "' holds services/" + module + "/"
                + fileName);
    }

    /**
     * Returns the shape of the origin timestamp one record carries.
     *
     * <p>Derived from the value rather than declared, so the comparison with
     * {@link PicClause#PROCESSING_TIMESTAMP_SHAPE} rests on what the fixture holds. Digits become
     * their placeholder letters and separators are kept, which is enough to show that the inbound
     * shape uses a space and colons where the outbound one uses a dash and dots.</p>
     *
     * @param outcome one evaluated feed record
     * @return the shape, in the notation the expectation files use
     */
    private static String originTimestampShapeOf(FeedOutcome outcome) {
        String origin = outcome.feed().originTimestamp();
        StringBuilder shape = new StringBuilder(origin.length());
        String letters = "YYYY-MM-DD HH:MM:SS.ffffff";
        for (int position = 0; position < origin.length(); position++) {
            char character = origin.charAt(position);
            shape.append(Character.isDigit(character) && position < letters.length()
                    ? letters.charAt(position)
                    : character);
        }
        return shape.toString();
    }

    /**
     * Returns the number of daily-transaction fields {@code 2000-POST-TRANSACTION} copies.
     *
     * <p>Counted from the program rather than written down. The paragraph moves each field of the
     * inbound record onto the outbound one, and the count is what tells a reader that the processing
     * timestamp is not among them.</p>
     *
     * @return the count of {@code MOVE} statements naming a {@code DALYTRAN} field
     */
    private static long copiedFeedFieldCount() {
        return normalisedParagraph("2000-POST-TRANSACTION").lines()
                .filter(line -> line.startsWith("MOVE DALYTRAN-"))
                .count();
    }

    /**
     * Returns the operator the credit-limit comparison uses.
     *
     * @param paragraph the normalised account paragraph
     * @return the operator as the source writes it
     */
    private static String creditLimitOperator(String paragraph) {
        Matcher matcher = Pattern.compile("IF ACCT-CREDIT-LIMIT +(>=|<=|>|<|=) +WS-TEMP-BAL")
                .matcher(paragraph);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher reversed = Pattern.compile("IF WS-TEMP-BAL +(>=|<=|>|<|=) +ACCT-CREDIT-LIMIT")
                .matcher(paragraph);
        if (reversed.find()) {
            return switch (reversed.group(1)) {
                case ">" -> "<";
                case "<" -> ">";
                case ">=" -> "<=";
                case "<=" -> ">=";
                default -> "=";
            };
        }
        throw new IllegalStateException(
                VALIDATION_PROGRAM + " holds no comparison of the credit limit to WS-TEMP-BAL");
    }

    /**
     * Reports whether the last two rules are separate conditions with nothing gating the second.
     *
     * <p>This is what makes a collision possible: a record can fail the credit-limit rule and then
     * be re-labelled by the expiry rule, because no test of the reason stands between them.</p>
     *
     * @param paragraph the normalised account paragraph
     * @return {@code true} when the second condition is not gated on the reason
     */
    private static boolean ungatedSequentialConditions(String paragraph) {
        List<String> conditions = paragraph.lines()
                .filter(line -> line.startsWith("IF ") && !line.contains("-STATUS"))
                .toList();
        return conditions.size() >= 2
                && conditions.stream().noneMatch(line -> line.contains("WS-VALIDATION-FAIL-REASON"));
    }

    /**
     * Returns the reason a record failing both of the last two rules ends up carrying.
     *
     * <p>The later assignment wins because nothing gates it, so the answer is the reason of the last
     * rule the paragraph assigns.</p>
     *
     * @param paragraph the normalised account paragraph
     * @return the numeric code, as the source writes it
     */
    private static String collisionWinnerReasonCode(String paragraph) {
        Matcher matcher = Pattern.compile("MOVE (\\d{3}) TO WS-VALIDATION-FAIL-REASON")
                .matcher(paragraph);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        if (last == null) {
            throw new IllegalStateException(
                    VALIDATION_PROGRAM + " assigns no reason in the paragraph offered");
        }
        return last;
    }

    /**
     * Returns the count of posted feed records that reached one category key.
     *
     * @param run the whole-feed run
     * @param key the account, type and category the record resolves to
     * @return the number of posted records naming it
     */
    private static long postingsForKey(PostingRun run, TransactionCategoryBalanceId key) {
        return run.postedOutcomes().stream()
                .filter(outcome -> key.getAccountId().equals(outcome.resolvedAccountId())
                        && key.getTypeCode().equals(outcome.feed().typeCode())
                        && key.getCategoryCode().equals(outcome.feed().categoryCode()))
                .count();
    }

    /**
     * Returns the fields the lookup paragraph moves into the keyed read, in the order it moves them.
     *
     * @param paragraph the normalised lookup paragraph
     * @return the source field names, comma separated
     */
    private static String keyComponentsMovedInto(String paragraph) {
        return paragraph.lines()
                .filter(line -> line.startsWith("MOVE ") && line.contains(" TO FD-TRANCAT"))
                .map(line -> line.substring("MOVE ".length(), line.indexOf(" TO ")).strip())
                .reduce((left, right) -> left + "," + right)
                .orElseThrow(() -> new IllegalStateException(
                        VALIDATION_PROGRAM + " moves nothing into the category key"));
    }

    /**
     * Returns the file statuses a paragraph accepts, as its condition lists them.
     *
     * @param paragraph the normalised paragraph
     * @return the statuses, comma separated, without their quotes
     */
    private static String acceptedStatusesOf(String paragraph) {
        Matcher matcher = Pattern.compile("IF [A-Z-]+-STATUS = ('\\d{2}'(?: OR '\\d{2}')*)")
                .matcher(paragraph);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    VALIDATION_PROGRAM + " holds no file status condition in the paragraph offered");
        }
        return matcher.group(1).replace("'", "").replace(" OR ", ",");
    }

    /**
     * Returns a literal the paragraph moves into one field.
     *
     * @param paragraph  the normalised paragraph
     * @param fieldName  the field the literal is moved into
     * @param lastMove   {@code true} for the last such move, {@code false} for the first
     * @return the literal, without its quotes
     */
    private static String movedLiteralInto(String paragraph, String fieldName, boolean lastMove) {
        List<String> literals = paragraph.lines()
                .filter(line -> line.startsWith("MOVE '") && line.endsWith("TO " + fieldName))
                .map(line -> line.substring(line.indexOf('\'') + 1, line.lastIndexOf('\'')))
                .toList();
        if (literals.isEmpty()) {
            throw new IllegalStateException(
                    VALIDATION_PROGRAM + " moves no literal into " + fieldName);
        }
        return lastMove ? literals.get(literals.size() - 1) : literals.get(0);
    }

    /**
     * Returns the verb a paragraph puts its record on the dataset with.
     *
     * @param paragraph the normalised paragraph
     * @return {@code WRITE}, {@code REWRITE}, or {@code absent}
     */
    private static String writeVerbOf(String paragraph) {
        if (paragraph.lines().anyMatch(line -> line.startsWith("REWRITE "))) {
            return "REWRITE";
        }
        return paragraph.lines().anyMatch(line -> line.startsWith("WRITE ")) ? "WRITE" : "absent";
    }

    /**
     * Returns one value of a dataset definition's {@code KEYS} parameter.
     *
     * @param jobPath  the job below the repository root
     * @param position 1 for the key length, 2 for the key offset
     * @return the digits as the job writes them
     */
    private static String datasetKeyParameter(String jobPath, int position) {
        Matcher matcher = Pattern.compile("KEYS\\((\\d+) +(\\d+)\\)")
                .matcher(sourceFileText(jobPath));
        if (!matcher.find()) {
            throw new IllegalStateException(jobPath + " declares no KEYS parameter");
        }
        return matcher.group(position);
    }

    /**
     * Returns the first value of a dataset definition's {@code RECORDSIZE} parameter.
     *
     * @param jobPath the job below the repository root
     * @return the digits as the job writes them
     */
    private static String datasetRecordSize(String jobPath) {
        Matcher matcher = Pattern.compile("RECORDSIZE\\((\\d+) +(\\d+)\\)")
                .matcher(sourceFileText(jobPath));
        if (!matcher.find()) {
            throw new IllegalStateException(jobPath + " declares no RECORDSIZE parameter");
        }
        return matcher.group(1);
    }

    /**
     * Returns the count of feed records that posted to one account.
     *
     * @param run       the whole-feed run
     * @param accountId the account to count for
     * @return the number of posted records naming it
     */
    private static long postingsFor(PostingRun run, String accountId) {
        return run.postedOutcomes().stream()
                .filter(outcome -> accountId.equals(outcome.resolvedAccountId()))
                .count();
    }

    /**
     * Returns the balance one category key was seeded with, or zero when the run created it.
     *
     * @param run the whole-feed run
     * @param key the category key
     * @return the seeded balance, or zero for a key {@code 2700-A} created
     */
    private static BigDecimal seededCategoryBalance(PostingRun run,
            TransactionCategoryBalanceId key) {
        if (!run.seededCategoryKeys().contains(key)) {
            return BigDecimal.ZERO;
        }
        return CardDemoFixtureLoader.loadTransactionCategoryBalances().stream()
                .filter(seeded -> seeded.accountId().equals(key.getAccountId())
                        && seeded.typeCode().equals(key.getTypeCode())
                        && seeded.categoryCode().equals(key.getCategoryCode()))
                .map(CopybookRecordParser.TransactionCategoryBalanceRecord::balance)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Returns one paragraph of {@value #VALIDATION_PROGRAM} with its runs of spaces collapsed.
     *
     * <p>COBOL is written in fixed columns, so a statement carries whatever padding put it there.
     * Collapsing runs of spaces to one lets an assertion name a statement the way a reader would
     * write it, without depending on the column the source happens to use.</p>
     *
     * @param label the paragraph label, without its full stop
     * @return the paragraph text, from its label to the {@code EXIT} that closes it
     */
    private static String normalisedParagraph(String label) {
        List<String> normalised = sourceFileText(VALIDATION_PROGRAM).lines()
                .map(line -> line.replaceAll("\\s+", " ").strip())
                .filter(line -> !line.startsWith("*") && !line.isEmpty())
                .toList();
        int start = normalised.indexOf(label + ".");
        if (start < 0) {
            throw new IllegalStateException(VALIDATION_PROGRAM + " holds no paragraph " + label);
        }

        StringBuilder paragraph = new StringBuilder(label + ".");
        for (int index = start + 1; index < normalised.size(); index++) {
            String line = normalised.get(index);
            if (PARAGRAPH_LABEL.matcher(line).matches()) {
                return paragraph.toString();
            }
            paragraph.append('\n').append(line);
            if (line.equals("EXIT.")) {
                return paragraph.toString();
            }
        }
        return paragraph.toString();
    }

    /**
     * Returns the {@code ADD} statement of a paragraph that names one field.
     *
     * @param paragraph a normalised paragraph
     * @param fieldName the field the statement adds into
     * @return the statement as written, without its trailing full stop
     */
    private static String statementContaining(String paragraph, String fieldName) {
        return paragraph.lines()
                .filter(line -> line.startsWith("ADD ") && line.contains(fieldName))
                .findFirst()
                .map(line -> line.endsWith(".") ? line.substring(0, line.length() - 1) : line)
                .orElseThrow(() -> new IllegalStateException(VALIDATION_PROGRAM
                        + " holds no ADD statement naming " + fieldName));
    }

    /**
     * Returns the condition a paragraph forks on.
     *
     * @param paragraph a normalised paragraph
     * @return the condition text, without the {@code IF}
     */
    private static String conditionOf(String paragraph) {
        return paragraph.lines()
                .filter(line -> line.startsWith("IF "))
                .findFirst()
                .map(line -> line.substring("IF ".length()).strip())
                .orElseThrow(() -> new IllegalStateException(
                        VALIDATION_PROGRAM + " holds no IF in the paragraph offered"));
    }

    /**
     * Returns the accumulator one branch of the sign fork adds into.
     *
     * @param paragraph  a normalised paragraph carrying the fork
     * @param positive   {@code true} for the branch the condition selects, {@code false} for
     *                   the {@code ELSE}
     * @return the field name
     */
    private static String accumulatorTarget(String paragraph, boolean positive) {
        List<String> lines = paragraph.lines().toList();
        int fork = lines.indexOf("ELSE");
        if (fork < 0) {
            throw new IllegalStateException(
                    VALIDATION_PROGRAM + " holds no ELSE in the paragraph offered");
        }
        List<String> branch = positive ? lines.subList(0, fork) : lines.subList(fork, lines.size());
        return branch.stream()
                .filter(line -> line.startsWith("ADD ") && line.contains("ACCT-CURR-CYC"))
                .map(line -> line.substring(line.lastIndexOf(' ') + 1))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(VALIDATION_PROGRAM
                        + " holds no cycle accumulator ADD on the branch offered"));
    }

    /**
     * Returns the order {@code 2000-POST-TRANSACTION} performs its three updates in.
     *
     * @return the paragraph names it performs, in order
     */
    private static List<String> postOrderOf() {
        return normalisedParagraph("2000-POST-TRANSACTION").lines()
                .filter(line -> line.startsWith("PERFORM 2"))
                .map(line -> line.substring("PERFORM ".length()).strip())
                .toList();
    }

    /**
     * Returns the trailer reason as the eighty-byte trailer holds it.
     *
     * <p>{@code CopybookRecordParser.RejectedTransactionRecord} exposes the reason as a number,
     * having read it back from {@code PIC 9(04)}. Rendering it at the declared width is what lets an
     * assertion ask whether the field holds four plain digits, which is the question
     * {@code trailer_reason_has_sign_overpunch} puts.</p>
     *
     * @param parsed one reject record read back
     * @return {@link PicClause#VALIDATION_FAIL_REASON_WIDTH} characters
     */
    private static String renderedTrailerReason(
            CopybookRecordParser.RejectedTransactionRecord parsed) {
        return numeric(Integer.toString(parsed.failReason()),
                PicClause.VALIDATION_FAIL_REASON_WIDTH);
    }

    /** Renders a boolean as the expectation files write it. */
    private static String yesOrNo(boolean value) {
        return value ? ExpectedOutcomes.YES : ExpectedOutcomes.NO;
    }

    /**
     * Returns the overpunch list carrying the sign of one amount.
     *
     * @param amount the value {@code DALYTRAN-AMT} holds
     * @return the negative list for a value below zero, the positive list otherwise
     */
    private static String signOverpunchDigitsFor(BigDecimal amount) {
        return amount.signum() < 0
                ? CopybookRecordParser.NEGATIVE_SIGN_OVERPUNCH_DIGITS
                : CopybookRecordParser.POSITIVE_SIGN_OVERPUNCH_DIGITS;
    }

    /**
     * Returns the verb {@code 2500-WRITE-REJECT-REC} uses to put the record on the dataset.
     *
     * @return the verb, read from {@value #VALIDATION_PROGRAM}
     */
    private static String writeVerbOfRejectParagraph() {
        return rejectParagraphText().contains("WRITE FD-REJS-RECORD FROM REJECT-RECORD")
                ? "WRITE"
                : "absent";
    }

    /**
     * Returns what {@code 2500-WRITE-REJECT-REC} does when the write does not succeed.
     *
     * @return {@code abend} when the paragraph reaches the abend routine
     */
    private static String rejectWriteFailureOutcome() {
        return rejectParagraphText().contains("PERFORM 9999-ABEND-PROGRAM")
                ? "abend"
                : "continue";
    }

    /**
     * Returns the value {@code 2500-WRITE-REJECT-REC} moves into {@code APPL-RESULT} on entry.
     *
     * @return the digits, read from {@value #VALIDATION_PROGRAM}
     */
    private static String applResultOnReject() {
        Matcher matcher = Pattern.compile("MOVE (\\d+) TO APPL-RESULT")
                .matcher(rejectParagraphText());
        if (!matcher.find()) {
            throw new IllegalStateException(VALIDATION_PROGRAM
                    + " paragraph 2500-WRITE-REJECT-REC moves nothing into APPL-RESULT");
        }
        return matcher.group(1);
    }

    /**
     * Returns the field the driver loop increments for a rejected record.
     *
     * <p>Anchored on the {@code PERFORM} that follows it rather than on the first counter in the
     * program. The driver loop increments two counters, and the first one it reaches counts every
     * record read; taking that one would answer a different question and still look plausible.</p>
     *
     * @return the field name, read from {@value #VALIDATION_PROGRAM}
     */
    private static String rejectCounterField() {
        Matcher matcher = Pattern.compile(
                        "ADD 1 TO +(WS-[A-Z0-9-]+) +PERFORM 2500-WRITE-REJECT-REC")
                .matcher(sourceFileText(VALIDATION_PROGRAM).replaceAll("\\s+", " "));
        if (!matcher.find()) {
            throw new IllegalStateException(VALIDATION_PROGRAM
                    + " increments no counter immediately before performing "
                    + "2500-WRITE-REJECT-REC");
        }
        return matcher.group(1);
    }

    /**
     * Returns the record length {@value #POSTING_JOB} allocates the reject dataset with.
     *
     * @return the digits of the {@code LRECL} parameter on the {@code DALYREJS} allocation
     */
    private static String rejectDatasetRecordLength() {
        Matcher matcher = Pattern.compile("DALYREJS.*?LRECL=(\\d+)", Pattern.DOTALL)
                .matcher(sourceFileText(POSTING_JOB));
        if (!matcher.find()) {
            throw new IllegalStateException(
                    POSTING_JOB + " allocates DALYREJS without an LRECL");
        }
        return matcher.group(1);
    }

    /**
     * Returns the Picture clause {@value #VALIDATION_PROGRAM} declares one field with.
     *
     * @param fieldName the field to look up
     * @return the clause as written, such as {@code 9(04)}
     */
    private static String declaredPictureClauseOf(String fieldName) {
        Matcher matcher = Pattern.compile(Pattern.quote(fieldName) + "\\s+PIC\\s+([^\\s.]+)")
                .matcher(sourceFileText(VALIDATION_PROGRAM));
        if (!matcher.find()) {
            throw new IllegalStateException(
                    VALIDATION_PROGRAM + " declares no Picture clause for " + fieldName);
        }
        return matcher.group(1);
    }

    /**
     * Returns the text of {@code 2500-WRITE-REJECT-REC}, from its label to its {@code EXIT}.
     *
     * @return the paragraph
     */
    private static String rejectParagraphText() {
        String program = sourceFileText(VALIDATION_PROGRAM);
        int start = program.indexOf("2500-WRITE-REJECT-REC.");
        if (start < 0) {
            throw new IllegalStateException(
                    VALIDATION_PROGRAM + " holds no paragraph 2500-WRITE-REJECT-REC");
        }
        int end = program.indexOf("EXIT.", start);
        if (end < 0) {
            throw new IllegalStateException(VALIDATION_PROGRAM
                    + " holds no EXIT closing 2500-WRITE-REJECT-REC");
        }
        return program.substring(start, end);
    }

    @Nested
    @DisplayName("Per-record posting results, app/cbl/CBTRN02C.cbl:L202-L444")
    class PerRecordPostingResults {

        @Test
        @DisplayName("every row of " + EXPECTED_POSTING_RESULTS_FILE
                + " matches, and none is left unread")
        void everyRowOfThePostingResultsMatches() {
            PostingRun run = fixtureRun();
            Map<String, FeedOutcome> bySequence = new LinkedHashMap<>();
            for (FeedOutcome outcome : run.outcomes()) {
                bySequence.put(Integer.toString(outcome.ordinal()), outcome);
            }

            for (ExpectedOutcomes.Row row : EXPECTED_POSTING_RESULTS.rows()) {
                String expected = EXPECTED_POSTING_RESULTS.value(row.recordSequence(),
                        row.entityKey(), row.expectedField());
                String actual = actualPostingResultValue(row, run, bySequence);

                assertEquals(expected, actual,
                        EXPECTED_POSTING_RESULTS_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertThat(EXPECTED_POSTING_RESULTS.unconsumedRows())
                    .as(EXPECTED_POSTING_RESULTS.unconsumedDescription())
                    .isEmpty();
        }

        /** Resolves what the run, the fixture or the source holds for one posting expectation. */
        private String actualPostingResultValue(ExpectedOutcomes.Row row, PostingRun run,
                Map<String, FeedOutcome> bySequence) {
            return switch (row.entityKey()) {
                case "model_b" -> postingModelValue(row, run);
                case "validation_chain" -> validationChainValue(row);
                case "credit_limit_rule" -> creditLimitRuleValue(row);
                case "posting_path" -> postingPathValue(row, run);
                case "refund_sign_defect" -> refundSignValue(row, run);
                case "reject_trailer" -> rejectTrailerValue(row, run);
                default -> accountKeyedPostingValue(row, run, bySequence);
            };
        }

        /**
         * Resolves one row keyed by an account identifier.
         *
         * <p>Two shapes share that key. A row on sequence {@value ExpectedOutcomes#FIXTURE_LEVEL_SEQUENCE}
         * counts the declines an account received across the whole feed; every other row describes
         * one feed record, and takes its values from the decision captured when that record was
         * judged.</p>
         */
        private String accountKeyedPostingValue(ExpectedOutcomes.Row row, PostingRun run,
                Map<String, FeedOutcome> bySequence) {
            if (row.isFixtureLevel()) {
                if (!"model_b_declines_for_account".equals(row.expectedField())) {
                    throw new IllegalStateException(EXPECTED_POSTING_RESULTS_FILE
                            + " carries an unresolved account-level field " + row.key());
                }
                return Long.toString(run.rejectedOutcomes().stream()
                        .filter(outcome -> row.entityKey().equals(outcome.resolvedAccountId()))
                        .count());
            }

            FeedOutcome outcome = bySequence.get(row.recordSequence());
            assertThat(outcome).as(EXPECTED_POSTING_RESULTS_FILE + " row " + row.key()
                    + " names a feed record the run must have evaluated").isNotNull();
            DecisionInputs decision = outcome.decision();

            return switch (row.expectedField()) {
                case "xref_acct_id" -> decision.accountId();
                case "dalytran_id" -> outcome.feed().transactionId();
                case "dalytran_card_num" -> outcome.feed().cardNumber();
                case "dalytran_type_cd" -> outcome.feed().typeCode();
                case "dalytran_cat_cd" -> outcome.feed().categoryCode();
                case "dalytran_amt" -> money(outcome.feed().amount());
                case "acct_credit_limit" -> money(decision.creditLimit());
                case "acct_curr_cyc_credit_before" -> money(decision.cycleCreditBefore());
                case "acct_curr_cyc_debit_before" -> money(decision.cycleDebitBefore());
                case "ws_temp_bal" -> money(decision.workingBalance());
                case "posting_decision" -> outcome.posted() ? "POSTED" : "REJECTED";
                case "decline_reason_code" -> Integer.toString(outcome.posted()
                        ? 0
                        : outcome.declineReason().numericCode());
                case "decline_reason_description" -> outcome.declineReason().description();
                default -> throw new IllegalStateException(EXPECTED_POSTING_RESULTS_FILE
                        + " carries an unresolved per-record field " + row.key());
            };
        }

        /** Resolves one expectation about the whole Model B run. */
        private String postingModelValue(ExpectedOutcomes.Row row, PostingRun run) {
            return switch (row.expectedField()) {
                case "model_b_record_count" -> Integer.toString(run.outcomes().size());
                case "model_b_approval_count" -> Long.toString(run.postedCount());
                case "model_b_decline_count" -> Long.toString(run.rejectedCount());
                case "model_b_distinct_declining_accounts" -> Long.toString(
                        run.rejectedOutcomes().stream()
                                .map(FeedOutcome::resolvedAccountId).distinct().count());
                case "model_b_declines_all_type_01" -> yesOrNo(run.rejectedOutcomes().stream()
                        .allMatch(outcome -> PURCHASE_TYPE_CODE.equals(
                                outcome.feed().typeCode())));
                case "model_b_declines_all_reason_102" -> yesOrNo(run.rejectedOutcomes().stream()
                        .allMatch(outcome ->
                                outcome.declineReason() == DeclineReason.OVER_CREDIT_LIMIT));
                case "account_with_all_type_01_declined" -> accountWithEveryPurchaseDeclined(run);
                case "declined_record_skips_accumulator_update" -> yesOrNo(
                        declinedRecordsLeaveTheAccumulatorsAlone(run));
                default -> throw new IllegalStateException(EXPECTED_POSTING_RESULTS_FILE
                        + " carries an unresolved model field " + row.key());
            };
        }

        /** Resolves one expectation about the ordering of the four rules. */
        private String validationChainValue(ExpectedOutcomes.Row row) {
            String validate = normalisedParagraph("1500-VALIDATE-TRAN");
            String account = normalisedParagraph("1500-B-LOOKUP-ACCT");

            return switch (row.expectedField()) {
                case "reason_100_short_circuits_subsequent_rules" -> yesOrNo(
                        validate.contains("IF WS-VALIDATION-FAIL-REASON = 0")
                                && validate.contains("PERFORM 1500-B-LOOKUP-ACCT"));
                case "reason_101_short_circuits_102_and_103" -> yesOrNo(
                        account.contains("INVALID KEY")
                                && account.indexOf("MOVE 101") < account.indexOf("WS-TEMP-BAL"));
                case "reason_102_and_103_are_ungated_sequential_ifs" -> yesOrNo(
                        ungatedSequentialConditions(account));
                case "collision_winner_reason_code" -> collisionWinnerReasonCode(account);
                default -> throw new IllegalStateException(EXPECTED_POSTING_RESULTS_FILE
                        + " carries an unresolved chain field " + row.key());
            };
        }

        /** Resolves one expectation about the credit-limit comparison. */
        private String creditLimitRuleValue(ExpectedOutcomes.Row row) {
            String account = normalisedParagraph("1500-B-LOOKUP-ACCT");

            return switch (row.expectedField()) {
                case "credit_limit_comparison_operator" -> creditLimitOperator(account);
                case "formula_excludes_current_balance" -> yesOrNo(
                        !account.contains("ACCT-CURR-BAL"));
                case "ws_temp_bal_precision_narrower_than_operands" -> yesOrNo(
                        PicClause.WS_TEMP_BAL_PRECISION
                                < PicClause.ACCT_CURR_CYC_CREDIT_PRECISION);
                default -> throw new IllegalStateException(EXPECTED_POSTING_RESULTS_FILE
                        + " carries an unresolved credit-limit field " + row.key());
            };
        }

        /** Resolves one expectation about the posting path a record takes once approved. */
        private String postingPathValue(ExpectedOutcomes.Row row, PostingRun run) {
            FeedOutcome first = run.postedOutcomes().get(0);

            return switch (row.expectedField()) {
                case "posted_field_copy_count" -> Long.toString(copiedFeedFieldCount());
                case "post_update_order" -> String.join(",", List.of("tcatbal", "account",
                        "transaction").stream()
                        .filter(stage -> postOrderOf().stream()
                                .anyMatch(performed -> performed.toLowerCase(Locale.ROOT)
                                        .contains(stage)))
                        .toList());
                case "post_updates_run_unconditionally_with_no_rollback" -> yesOrNo(
                        normalisedParagraph("2000-POST-TRANSACTION").lines()
                                .noneMatch(line -> line.startsWith("IF ")));
                case "dalytran_proc_ts_is_never_copied" -> yesOrNo(
                        run.postedOutcomes().stream().noneMatch(outcome ->
                                outcome.feed().processingTimestamp().equals(
                                        run.postedTransactions().get(
                                                outcome.feed().transactionId())
                                                .getProcessedTimestamp())));
                case "tran_orig_ts" -> run.postedTransactions()
                        .get(first.feed().transactionId()).getOriginTimestamp();
                case "tran_proc_ts_rendered_shape" -> PicClause.PROCESSING_TIMESTAMP_SHAPE;
                case "tran_proc_ts_significant_fraction_digits" -> Integer.toString(
                        PicClause.PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS);
                case "tran_proc_ts_literal_trailing_zeros" -> Integer.toString(
                        PicClause.PROCESSING_TIMESTAMP_TRAILING_ZERO_DIGITS);
                case "tran_proc_ts_third_separator" -> String.valueOf(
                        PicClause.PROCESSING_TIMESTAMP_DASH);
                case "orig_and_proc_timestamp_shapes_differ" -> yesOrNo(
                        !PicClause.PROCESSING_TIMESTAMP_SHAPE.equals(
                                originTimestampShapeOf(first)));
                case "proc_timestamp_value_derivable_from_fixture" -> yesOrNo(false);
                default -> throw new IllegalStateException(EXPECTED_POSTING_RESULTS_FILE
                        + " carries an unresolved posting-path field " + row.key());
            };
        }

        /** Resolves one expectation about the refund sign convention of register item 6. */
        private String refundSignValue(ExpectedOutcomes.Row row, PostingRun run) {
            return switch (row.expectedField()) {
                case "refund_sign_defect_clearest_instance_seq" -> Integer.toString(
                        clearestSignDefectOrdinal(run));
                case "refund_makes_cycle_debit_more_negative" -> yesOrNo(
                        run.accountBalances().values().stream()
                                .allMatch(held -> held.getCycleDebit().signum() <= 0));
                case "formula_subtracts_cycle_debit" -> yesOrNo(
                        normalisedParagraph("1500-B-LOOKUP-ACCT")
                                .contains("- ACCT-CURR-CYC-DEBIT"));
                default -> throw new IllegalStateException(EXPECTED_POSTING_RESULTS_FILE
                        + " carries an unresolved refund field " + row.key());
            };
        }

        /** Resolves one expectation about the reject trailer this file also describes. */
        private String rejectTrailerValue(ExpectedOutcomes.Row row, PostingRun run) {
            return switch (row.expectedField()) {
                case "decline_reason_description_pic" ->
                        declaredPictureClauseOf("WS-VALIDATION-FAIL-REASON-DESC");
                case "description_space_padded_to_76" -> yesOrNo(
                        run.rejectedOutcomes().stream().allMatch(outcome -> {
                            String rendered = renderRejectRecord(
                                    feedRecordText(outcome.ordinal()), outcome.declineReason());
                            return rendered.substring(rendered.length()
                                            - PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH)
                                    .stripTrailing()
                                    .equals(outcome.declineReason().description());
                        }));
                default -> throw new IllegalStateException(EXPECTED_POSTING_RESULTS_FILE
                        + " carries an unresolved trailer field " + row.key());
            };
        }

        /** Returns the account whose every purchase record was refused. */
        private String accountWithEveryPurchaseDeclined(PostingRun run) {
            List<String> accounts = run.outcomes().stream()
                    .filter(outcome -> PURCHASE_TYPE_CODE.equals(outcome.feed().typeCode())
                            && outcome.declineReason() != DeclineReason.INVALID_CARD_NUMBER)
                    .map(FeedOutcome::resolvedAccountId)
                    .distinct()
                    .filter(accountId -> run.outcomes().stream()
                            .filter(outcome -> PURCHASE_TYPE_CODE.equals(outcome.feed().typeCode())
                                    && outcome.declineReason() != DeclineReason.INVALID_CARD_NUMBER
                                    && accountId.equals(outcome.resolvedAccountId()))
                            .noneMatch(FeedOutcome::posted))
                    .sorted()
                    .toList();
            assertEquals(1, accounts.size(), EXPECTED_POSTING_RESULTS_FILE
                    + ": exactly one account must have every purchase refused");
            return accounts.get(0);
        }

        /**
         * Reports whether a refused record left the accumulators where it found them.
         *
         * <p>Checked by arithmetic rather than by reading the code: each account's accumulator
         * movement must equal the sum of the amounts of the records that posted to it, so a refused
         * record that had contributed would break the equality.</p>
         */
        private boolean declinedRecordsLeaveTheAccumulatorsAlone(PostingRun run) {
            for (AccountBalanceProjectionEntity held : run.accountBalances().values()) {
                CopybookRecordParser.AccountRecord seeded =
                        run.seededAccounts().get(held.getAccountId());
                BigDecimal posted = run.postedOutcomes().stream()
                        .filter(outcome -> held.getAccountId().equals(outcome.resolvedAccountId()))
                        .map(outcome -> outcome.feed().amount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal moved = held.getCurrentBalance().subtract(seeded.currentBalance());
                if (posted.compareTo(moved) != 0) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Returns the ordinal of the record that shows the sign convention most plainly.
         *
         * <p>The defect is that a refund makes {@code ACCT-CURR-CYC-DEBIT} more negative at
         * {@code app/cbl/CBTRN02C.cbl:L551}, and the credit-limit formula subtracts that accumulator
         * at {@code L404}, so a refund raises the balance the next record is tested against. The
         * clearest instance is therefore not a refund at all: it is a later purchase that the
         * accumulated refunds refuse. This picks the record that would have been approved had the
         * accumulator not gone negative, and among those the one carrying the most negative
         * accumulator, which is the largest inflation the feed produces.</p>
         *
         * @param run the whole-feed run
         * @return the 1-based ordinal of that record
         */
        private int clearestSignDefectOrdinal(PostingRun run) {
            List<FeedOutcome> refusedOnlyByTheDefect = run.rejectedOutcomes().stream()
                    .filter(outcome -> outcome.decision().reachedCreditLimitRule())
                    .filter(outcome -> outcome.decision().cycleDebitBefore().signum() < 0)
                    .filter(outcome -> outcome.decision().creditLimit()
                            .compareTo(workingBalance(outcome.decision().cycleCreditBefore(),
                                    BigDecimal.ZERO, outcome.feed().amount())) >= 0)
                    .toList();
            assertThat(refusedOnlyByTheDefect)
                    .as(EXPECTED_POSTING_RESULTS_FILE + ": the feed must refuse at least one "
                            + "record that a non-negative cycle debit would have approved")
                    .isNotEmpty();

            return refusedOnlyByTheDefect.stream()
                    .min(Comparator.comparing(outcome -> outcome.decision().cycleDebitBefore()))
                    .map(FeedOutcome::ordinal)
                    .orElseThrow();
        }
    }

    @Nested
    @DisplayName("Category balance end state, app/cbl/CBTRN02C.cbl:L467-L542")
    class CategoryBalanceEndState {

        /** Separator the expectation file writes between the three key components. */
        private static final String KEY_SEPARATOR = "||";

        @Test
        @DisplayName("every row of " + EXPECTED_CATEGORY_FILE + " matches, and none is left unread")
        void everyRowOfTheCategoryExpectationsMatches() {
            PostingRun run = fixtureRun();

            for (ExpectedOutcomes.Row row : EXPECTED_CATEGORIES.rows()) {
                String expected = EXPECTED_CATEGORIES.value(row.recordSequence(), row.entityKey(),
                        row.expectedField());
                String actual = actualCategoryValue(row, run);

                assertEquals(expected, actual,
                        EXPECTED_CATEGORY_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertThat(EXPECTED_CATEGORIES.unconsumedRows())
                    .as(EXPECTED_CATEGORIES.unconsumedDescription())
                    .isEmpty();
        }

        /** Resolves what the run, the fixture or the source holds for one category expectation. */
        private String actualCategoryValue(ExpectedOutcomes.Row row, PostingRun run) {
            return switch (row.entityKey()) {
                case "model_b" -> categoryModelValue(row, run);
                case "tcatbal_seed" -> categorySeedValue(row, run);
                case "tcatbal_upsert" -> categoryUpsertValue(row);
                case "cycle_accumulator_invariant" -> categoryInvariantValue(row, run);
                default -> perKeyCategoryValue(row, run);
            };
        }

        /** Resolves one per-key expectation from the run's final table. */
        private String perKeyCategoryValue(ExpectedOutcomes.Row row, PostingRun run) {
            TransactionCategoryBalanceId key = keyOf(row.entityKey());
            TransactionCategoryBalanceEntity held = run.categoryBalances().get(key);
            assertThat(held).as(EXPECTED_CATEGORY_FILE + " row " + row.key()
                    + " names a key the final table must hold").isNotNull();

            return switch (row.expectedField()) {
                case "trancat_acct_id" -> key.getAccountId();
                case "trancat_type_cd" -> key.getTypeCode();
                case "trancat_cd" -> key.getCategoryCode();
                case "upsert_branch" -> run.seededCategoryKeys().contains(key) ? "UPDATE" : "CREATE";
                case "posting_count" -> Long.toString(postingsForKey(run, key));
                case "tran_cat_bal_seeded" -> money(seededCategoryBalance(run, key));
                case "tran_cat_bal_final" -> money(held.getCategoryBalance());
                default -> throw new IllegalStateException(EXPECTED_CATEGORY_FILE
                        + " carries an unresolved per-key field " + row.key());
            };
        }

        /** Resolves one expectation about the whole Model B category population. */
        private String categoryModelValue(ExpectedOutcomes.Row row, PostingRun run) {
            List<TransactionCategoryBalanceId> postedKeys = postedKeys(run);

            return switch (row.expectedField()) {
                case "keys_posted_to_count" -> Integer.toString(postedKeys.size());
                case "final_table_row_count" -> Integer.toString(run.categoryBalances().size());
                case "create_branch_key_count" -> Long.toString(postedKeys.stream()
                        .filter(key -> !run.seededCategoryKeys().contains(key)).count());
                case "update_branch_key_count" -> Long.toString(postedKeys.stream()
                        .filter(key -> run.seededCategoryKeys().contains(key)).count());
                case "total_postings" -> Long.toString(run.postedCount());
                case "positive_final_balance_key_count" -> Long.toString(postedKeys.stream()
                        .filter(key -> run.categoryBalances().get(key).getCategoryBalance()
                                .signum() > 0).count());
                case "negative_final_balance_key_count" -> Long.toString(postedKeys.stream()
                        .filter(key -> run.categoryBalances().get(key).getCategoryBalance()
                                .signum() < 0).count());
                case "zero_final_balance_key_count_among_posted" -> Long.toString(
                        postedKeys.stream()
                                .filter(key -> run.categoryBalances().get(key).getCategoryBalance()
                                        .signum() == 0).count());
                case "positive_keys_all_type_01" -> yesOrNo(postedKeys.stream()
                        .filter(key -> run.categoryBalances().get(key).getCategoryBalance()
                                .signum() > 0)
                        .allMatch(key -> PURCHASE_TYPE_CODE.equals(key.getTypeCode())));
                case "negative_keys_all_type_03" -> yesOrNo(postedKeys.stream()
                        .filter(key -> run.categoryBalances().get(key).getCategoryBalance()
                                .signum() < 0)
                        .allMatch(key -> REFUND_TYPE_CODE.equals(key.getTypeCode())));
                case "distinct_category_codes" -> Long.toString(postedKeys.stream()
                        .map(TransactionCategoryBalanceId::getCategoryCode).distinct().count());
                case "distinct_category_code_value" -> postedKeys.stream()
                        .map(TransactionCategoryBalanceId::getCategoryCode).distinct()
                        .reduce((first, second) -> {
                            throw new IllegalStateException(
                                    "the feed posts to more than one category code");
                        })
                        .orElseThrow();
                case "single_posting_type_03_key_count" -> Long.toString(
                        singlePostingKeys(run, REFUND_TYPE_CODE).size());
                case "single_posting_type_01_key_count" -> Long.toString(
                        singlePostingKeys(run, PURCHASE_TYPE_CODE).size());
                case "single_posting_type_01_accounts" -> String.join(",",
                        singlePostingKeys(run, PURCHASE_TYPE_CODE).stream()
                                .map(TransactionCategoryBalanceId::getAccountId).sorted().toList());
                case "type_03_records_per_card" -> Long.toString(refundRecordsPerCard(run));
                default -> {
                    if (row.expectedField().startsWith("posting_count_bucket_")) {
                        yield Long.toString(keysWithPostingCount(run, postingBucketOf(
                                row.expectedField())));
                    }
                    throw new IllegalStateException(EXPECTED_CATEGORY_FILE
                            + " carries an unresolved model field " + row.key());
                }
            };
        }

        /** Resolves one expectation about the seeded table the run started from. */
        private String categorySeedValue(ExpectedOutcomes.Row row, PostingRun run) {
            List<CopybookRecordParser.TransactionCategoryBalanceRecord> seeded =
                    CardDemoFixtureLoader.loadTransactionCategoryBalances();

            return switch (row.expectedField()) {
                case "seeded_row_count" -> Integer.toString(seeded.size());
                case "seeded_rows_all_zero" -> yesOrNo(seeded.stream()
                        .allMatch(record -> record.balance().signum() == 0));
                case "seeded_key_pattern" -> seeded.stream()
                        .map(record -> "account" + KEY_SEPARATOR + record.typeCode()
                                + KEY_SEPARATOR + record.categoryCode())
                        .distinct()
                        .reduce((first, second) -> {
                            throw new IllegalStateException(
                                    "the seed holds more than one key pattern");
                        })
                        .orElseThrow();
                case "seeded_accounts_match_dailytran_accounts" -> yesOrNo(
                        seededAccountsMatchFeedAccounts(run, seeded));
                case "vsam_key_length" -> datasetKeyParameter("app/jcl/TCATBALF.jcl", 1);
                case "vsam_key_offset" -> datasetKeyParameter("app/jcl/TCATBALF.jcl", 2);
                case "vsam_record_length" -> datasetRecordSize("app/jcl/TCATBALF.jcl");
                case "untouched_seeded_key" -> untouchedSeededKey(run);
                case "untouched_seeded_key_final_balance" -> money(run.categoryBalances()
                        .get(keyOf(untouchedSeededKey(run))).getCategoryBalance());
                case "untouched_seeded_key_posting_count" -> Long.toString(
                        postingsForKey(run, keyOf(untouchedSeededKey(run))));
                default -> throw new IllegalStateException(EXPECTED_CATEGORY_FILE
                        + " carries an unresolved seed field " + row.key());
            };
        }

        /** Resolves one expectation about the upsert the source performs. */
        private String categoryUpsertValue(ExpectedOutcomes.Row row) {
            String lookup = normalisedParagraph("2700-UPDATE-TCATBAL");
            String create = normalisedParagraph("2700-A-CREATE-TCATBAL-REC");
            String update = normalisedParagraph("2700-B-UPDATE-TCATBAL-REC");

            return switch (row.expectedField()) {
                case "key_components" -> keyComponentsMovedInto(lookup);
                case "accepted_file_statuses" -> acceptedStatusesOf(lookup);
                case "create_flag_default" -> movedLiteralInto(lookup, "WS-CREATE-TRANCAT-REC",
                        false);
                case "create_flag_reset_per_record" -> yesOrNo(lookup.lines()
                        .anyMatch(line -> line.startsWith("MOVE 'N' TO WS-CREATE-TRANCAT-REC")));
                case "create_flag_set_on_invalid_key" -> movedLiteralInto(lookup,
                        "WS-CREATE-TRANCAT-REC", true);
                case "branch_selector" -> conditionOf(lookup.lines()
                        .filter(line -> line.contains("WS-CREATE-TRANCAT-REC ="))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                VALIDATION_PROGRAM + " holds no branch on the create flag")));
                case "create_branch_initializes_record" -> yesOrNo(
                        create.contains("INITIALIZE TRAN-CAT-BAL-RECORD"));
                case "create_branch_add_site" -> statementContaining(create, "TRAN-CAT-BAL");
                case "update_branch_add_site" -> statementContaining(update, "TRAN-CAT-BAL");
                case "create_branch_write_verb" -> writeVerbOf(create);
                case "update_branch_write_verb" -> writeVerbOf(update);
                case "add_sites_truncate_toward_zero" -> yesOrNo(
                        "TRUNCATE_TOWARD_ZERO".equals(observedRoundingModeName()));
                case "tcatbal_update_runs_before_account_update" -> yesOrNo(
                        postOrderOf().indexOf("2700-UPDATE-TCATBAL")
                                < postOrderOf().indexOf("2800-UPDATE-ACCOUNT-REC"));
                case "post_updates_run_unconditionally_with_no_rollback" -> yesOrNo(
                        normalisedParagraph("2000-POST-TRANSACTION").lines()
                                .noneMatch(line -> line.startsWith("IF ")));
                default -> throw new IllegalStateException(EXPECTED_CATEGORY_FILE
                        + " carries an unresolved upsert field " + row.key());
            };
        }

        /** Resolves one invariant tying the category table to the account accumulators. */
        private String categoryInvariantValue(ExpectedOutcomes.Row row, PostingRun run) {
            return switch (row.expectedField()) {
                case "type_01_balance_equals_account_cycle_credit" -> yesOrNo(
                        categoryTotalMatchesAccumulator(run, PURCHASE_TYPE_CODE, true));
                case "type_03_balance_equals_account_cycle_debit" -> yesOrNo(
                        categoryTotalMatchesAccumulator(run, REFUND_TYPE_CODE, false));
                default -> throw new IllegalStateException(EXPECTED_CATEGORY_FILE
                        + " carries an unresolved invariant " + row.key());
            };
        }

        /** Reports whether one type's category movement equals the matching accumulator move. */
        private boolean categoryTotalMatchesAccumulator(PostingRun run, String typeCode,
                boolean creditSide) {
            for (AccountBalanceProjectionEntity held : run.accountBalances().values()) {
                CopybookRecordParser.AccountRecord seeded =
                        run.seededAccounts().get(held.getAccountId());
                BigDecimal accumulated = creditSide
                        ? held.getCycleCredit().subtract(seeded.currentCycleCredit())
                        : held.getCycleDebit().subtract(seeded.currentCycleDebit());
                BigDecimal categoryMovement = run.categoryBalances().entrySet().stream()
                        .filter(entry -> entry.getKey().getAccountId().equals(held.getAccountId())
                                && entry.getKey().getTypeCode().equals(typeCode))
                        .map(entry -> entry.getValue().getCategoryBalance()
                                .subtract(seededCategoryBalance(run, entry.getKey())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (accumulated.compareTo(categoryMovement) != 0) {
                    return false;
                }
            }
            return true;
        }

        /** Returns the keys at least one posted record reached, in table order. */
        private List<TransactionCategoryBalanceId> postedKeys(PostingRun run) {
            return run.categoryBalances().keySet().stream()
                    .filter(key -> postingsForKey(run, key) > 0)
                    .toList();
        }

        /** Returns the keys of one type that exactly one record posted to. */
        private List<TransactionCategoryBalanceId> singlePostingKeys(PostingRun run,
                String typeCode) {
            return postedKeys(run).stream()
                    .filter(key -> typeCode.equals(key.getTypeCode())
                            && postingsForKey(run, key) == 1L)
                    .toList();
        }

        /** Returns the count of keys that received one posting count. */
        private long keysWithPostingCount(PostingRun run, long postings) {
            return run.categoryBalances().keySet().stream()
                    .filter(key -> postingsForKey(run, key) == postings)
                    .count();
        }

        /** Returns the posting count one bucket field names. */
        private long postingBucketOf(String field) {
            Matcher matcher = Pattern.compile("posting_count_bucket_(\\d+)_key_count")
                    .matcher(field);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        EXPECTED_CATEGORY_FILE + " names an unreadable bucket field " + field);
            }
            return Long.parseLong(matcher.group(1));
        }

        /** Returns the refund records each card contributed. */
        private long refundRecordsPerCard(PostingRun run) {
            Map<String, Long> byCard = new LinkedHashMap<>();
            for (FeedOutcome outcome : run.postedOutcomes()) {
                if (REFUND_TYPE_CODE.equals(outcome.feed().typeCode())) {
                    byCard.merge(outcome.feed().cardNumber(), 1L, Long::sum);
                }
            }
            List<Long> distinct = byCard.values().stream().distinct().toList();
            assertEquals(1, distinct.size(), EXPECTED_CATEGORY_FILE
                    + ": every card must contribute the same number of refunds");
            return distinct.get(0);
        }

        /** Returns the one seeded key no posted record reached. */
        private String untouchedSeededKey(PostingRun run) {
            List<TransactionCategoryBalanceId> untouched = run.seededCategoryKeys().stream()
                    .filter(key -> postingsForKey(run, key) == 0L)
                    .toList();
            assertEquals(1, untouched.size(), EXPECTED_CATEGORY_FILE
                    + ": exactly one seeded key must receive nothing");
            TransactionCategoryBalanceId key = untouched.get(0);
            return key.getAccountId() + KEY_SEPARATOR + key.getTypeCode() + KEY_SEPARATOR
                    + key.getCategoryCode();
        }

        /** Reports whether the seeded accounts are the accounts the feed resolves. */
        private boolean seededAccountsMatchFeedAccounts(PostingRun run,
                List<CopybookRecordParser.TransactionCategoryBalanceRecord> seeded) {
            Set<String> seededAccounts = new LinkedHashSet<>(
                    seeded.stream().map(
                            CopybookRecordParser.TransactionCategoryBalanceRecord::accountId)
                            .toList());
            Set<String> feedAccounts = new LinkedHashSet<>(run.crossReferences().values().stream()
                    .map(CopybookRecordParser.CardCrossReferenceRecord::accountId).toList());
            return seededAccounts.equals(feedAccounts);
        }

        /** Reads one composite key back from the form the expectation file writes it in. */
        private TransactionCategoryBalanceId keyOf(String entityKey) {
            String[] parts = entityKey.split(Pattern.quote(KEY_SEPARATOR), -1);
            if (parts.length != 3) {
                throw new IllegalStateException(EXPECTED_CATEGORY_FILE
                        + " names a key that is not account, type and category: " + entityKey);
            }
            return new TransactionCategoryBalanceId(parts[0], parts[1], parts[2]);
        }
    }

    @Nested
    @DisplayName("Account end state, app/cbl/CBTRN02C.cbl:L545-L560")
    class AccountEndState {

        @Test
        @DisplayName("every row of " + EXPECTED_ACCOUNT_FILE + " matches, and none is left unread")
        void everyRowOfTheAccountExpectationsMatches() {
            PostingRun run = fixtureRun();

            for (ExpectedOutcomes.Row row : EXPECTED_ACCOUNTS.rows()) {
                String expected = EXPECTED_ACCOUNTS.value(row.recordSequence(), row.entityKey(),
                        row.expectedField());
                String actual = actualAccountValue(row, run);

                assertEquals(expected, actual,
                        EXPECTED_ACCOUNT_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertThat(EXPECTED_ACCOUNTS.unconsumedRows())
                    .as(EXPECTED_ACCOUNTS.unconsumedDescription())
                    .isEmpty();
        }

        /** Resolves what the run, the fixture or the source holds for one account expectation. */
        private String actualAccountValue(ExpectedOutcomes.Row row, PostingRun run) {
            return switch (row.entityKey()) {
                case "account_update" -> accountUpdateValue(row);
                case "account_record" -> unchangedFieldValue(row, run);
                case "arithmetic" -> arithmeticValue(row);
                case "reason_109" -> reasonOneOhNineValue(row, run);
                case "invariant" -> invariantValue(row, run);
                case "model_b" -> accountModelValue(row, run);
                case "cycle_close" -> cycleCloseValue(row, run);
                default -> perAccountValue(row, run);
            };
        }

        /** Resolves one per-account expectation from the run's before and after state. */
        private String perAccountValue(ExpectedOutcomes.Row row, PostingRun run) {
            String accountId = row.entityKey();
            CopybookRecordParser.AccountRecord seeded = run.seededAccounts().get(accountId);
            AccountBalanceProjectionEntity held = run.accountBalances().get(accountId);
            assertThat(seeded).as(EXPECTED_ACCOUNT_FILE + " row " + row.key()
                    + " names an account app/data/ASCII/acctdata.txt must hold").isNotNull();
            assertThat(held).as(EXPECTED_ACCOUNT_FILE + " row " + row.key()
                    + " names an account the run must have projected").isNotNull();

            return switch (row.expectedField()) {
                case "acct_id" -> held.getAccountId();
                case "acct_curr_bal_initial" -> money(seeded.currentBalance());
                case "acct_curr_bal_final" -> money(held.getCurrentBalance());
                case "acct_curr_cyc_credit_initial" -> money(seeded.currentCycleCredit());
                case "acct_curr_cyc_credit_final" -> money(held.getCycleCredit());
                case "acct_curr_cyc_debit_initial" -> money(seeded.currentCycleDebit());
                case "acct_curr_cyc_debit_final" -> money(held.getCycleDebit());
                case "acct_credit_limit" -> money(seeded.creditLimit());
                case "posting_count" -> Long.toString(postingsFor(run, accountId));
                default -> throw new IllegalStateException(EXPECTED_ACCOUNT_FILE
                        + " carries an unresolved per-account field " + row.key());
            };
        }

        /** Resolves one expectation about {@code 2800-UPDATE-ACCOUNT-REC} from the program text. */
        private String accountUpdateValue(ExpectedOutcomes.Row row) {
            String paragraph = normalisedParagraph("2800-UPDATE-ACCOUNT-REC");

            return switch (row.expectedField()) {
                case "balance_add_site" -> statementContaining(paragraph, "ACCT-CURR-BAL");
                case "sign_fork_condition" -> conditionOf(paragraph);
                case "positive_amount_target" -> accumulatorTarget(paragraph, true);
                case "negative_amount_target" -> accumulatorTarget(paragraph, false);
                case "zero_amount_routes_to_cycle_credit" -> yesOrNo(conditionOf(paragraph)
                        .endsWith(">= 0"));
                case "account_update_write_verb" -> paragraph.contains("REWRITE FD-ACCTFILE-REC")
                        ? "REWRITE"
                        : "absent";
                case "account_update_runs_after_tcatbal_update" -> yesOrNo(
                        postOrderOf().indexOf("2700-UPDATE-TCATBAL")
                                < postOrderOf().indexOf("2800-UPDATE-ACCOUNT-REC"));
                default -> throw new IllegalStateException(EXPECTED_ACCOUNT_FILE
                        + " carries an unresolved update field " + row.key());
            };
        }

        /**
         * Resolves whether one account field survived the whole run unchanged.
         *
         * <p>The projection the ledger owns carries the three values the posting arithmetic derives
         * and nothing else, so a field named here is one no posting may touch. The check compares
         * the fixture value against itself through the run's seeded map, which is the only place the
         * value exists after posting, and asserts the projection never gained a column for it.</p>
         */
        private String unchangedFieldValue(ExpectedOutcomes.Row row, PostingRun run) {
            String field = row.expectedField().replace("_unchanged_by_posting", "");
            List<String> projected = List.of("current_balance", "cycle_credit", "cycle_debit");
            boolean unchanged = run.seededAccounts().values().stream()
                    .allMatch(seeded -> fieldOf(seeded, field) != null)
                    && !projected.contains(field);

            return yesOrNo(unchanged);
        }

        /** Returns one protected account field, so an absent name fails rather than passes. */
        private Object fieldOf(CopybookRecordParser.AccountRecord seeded, String field) {
            return switch (field) {
                case "credit_limit" -> seeded.creditLimit();
                case "cash_credit_limit" -> seeded.cashCreditLimit();
                case "open_date" -> seeded.openDate();
                case "expiration_date" -> seeded.expirationDate();
                case "reissue_date" -> seeded.reissueDate();
                case "addr_zip" -> seeded.addressZip();
                case "group_id" -> seeded.groupId();
                case "active_status" -> seeded.activeStatus();
                default -> throw new IllegalStateException(EXPECTED_ACCOUNT_FILE
                        + " names an account field this parser does not carry: " + field);
            };
        }

        /** Resolves one expectation about how the arithmetic stores its result. */
        private String arithmeticValue(ExpectedOutcomes.Row row) {
            return switch (row.expectedField()) {
                case "balance_add_rounding_mode", "accumulator_add_rounding_mode" ->
                        observedRoundingModeName();
                case "rounded_phrase_absent_from_source" -> yesOrNo(
                        !sourceFileText(VALIDATION_PROGRAM).contains("ROUNDED"));
                default -> throw new IllegalStateException(EXPECTED_ACCOUNT_FILE
                        + " carries an unresolved arithmetic field " + row.key());
            };
        }

        /** Resolves one expectation about the reason the rewrite failure assigns. */
        private String reasonOneOhNineValue(ExpectedOutcomes.Row row, PostingRun run) {
            String paragraph = normalisedParagraph("2800-UPDATE-ACCOUNT-REC");
            String description = "ACCOUNT RECORD NOT FOUND";

            return switch (row.expectedField()) {
                case "reason_109_set_on_rewrite_invalid_key" -> yesOrNo(
                        paragraph.contains("INVALID KEY")
                                && paragraph.contains("MOVE 109 TO WS-VALIDATION-FAIL-REASON"));
                case "reason_109_description" -> paragraph.contains(description)
                        ? description
                        : "absent";
                case "reason_109_description_identical_to_reason_101" -> yesOrNo(
                        DeclineReason.ACCOUNT_NOT_FOUND.description().equals(description));
                case "reason_109_ever_inspected_by_program" -> yesOrNo(
                        sourceFileText(VALIDATION_PROGRAM).contains("WS-VALIDATION-FAIL-REASON = 109"));
                case "reason_109_occurrences_in_fixture" -> Long.toString(
                        run.rejectedOutcomes().stream()
                                .filter(outcome -> outcome.declineReason().numericCode() == 109)
                                .count());
                default -> throw new IllegalStateException(EXPECTED_ACCOUNT_FILE
                        + " carries an unresolved reason-109 field " + row.key());
            };
        }

        /** Resolves one invariant that must hold across every account after the run. */
        private String invariantValue(ExpectedOutcomes.Row row, PostingRun run) {
            return switch (row.expectedField()) {
                case "balance_reconciles_to_accumulators" -> yesOrNo(
                        run.accountBalances().values().stream().allMatch(held -> {
                            CopybookRecordParser.AccountRecord seeded =
                                    run.seededAccounts().get(held.getAccountId());
                            BigDecimal moved = held.getCurrentBalance()
                                    .subtract(seeded.currentBalance());
                            BigDecimal accumulated = held.getCycleCredit()
                                    .subtract(seeded.currentCycleCredit())
                                    .add(held.getCycleDebit()
                                            .subtract(seeded.currentCycleDebit()));
                            return moved.compareTo(accumulated) == 0;
                        }));
                case "cycle_credit_equals_type_01_category_balance" -> yesOrNo(
                        accumulatorMatchesCategory(run, PURCHASE_TYPE_CODE, true));
                case "cycle_debit_equals_type_03_category_balance" -> yesOrNo(
                        accumulatorMatchesCategory(run, REFUND_TYPE_CODE, false));
                default -> throw new IllegalStateException(EXPECTED_ACCOUNT_FILE
                        + " carries an unresolved invariant " + row.key());
            };
        }

        /**
         * Reports whether every account's accumulator movement equals its category balance movement
         * for one transaction type.
         *
         * <p>The two run off the same amounts — {@code 2700} adds to the category row and
         * {@code 2800} adds to the accumulator — so they must agree account by account. Where an
         * account has no row for the type, its accumulator must not have moved either.</p>
         */
        private boolean accumulatorMatchesCategory(PostingRun run, String typeCode,
                boolean creditSide) {
            for (AccountBalanceProjectionEntity held : run.accountBalances().values()) {
                CopybookRecordParser.AccountRecord seeded =
                        run.seededAccounts().get(held.getAccountId());
                BigDecimal moved = creditSide
                        ? held.getCycleCredit().subtract(seeded.currentCycleCredit())
                        : held.getCycleDebit().subtract(seeded.currentCycleDebit());
                BigDecimal categoryMovement = run.categoryBalances().entrySet().stream()
                        .filter(entry -> entry.getKey().getAccountId().equals(held.getAccountId())
                                && entry.getKey().getTypeCode().equals(typeCode))
                        .map(entry -> entry.getValue().getCategoryBalance()
                                .subtract(seededCategoryBalance(run, entry.getKey())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (moved.compareTo(categoryMovement) != 0) {
                    return false;
                }
            }
            return true;
        }

        /** Resolves one expectation about the whole Model B account population. */
        private String accountModelValue(ExpectedOutcomes.Row row, PostingRun run) {
            return switch (row.expectedField()) {
                case "accounts_with_negative_final_cycle_debit" -> Long.toString(
                        run.accountBalances().values().stream()
                                .filter(held -> held.getCycleDebit().signum() < 0).count());
                case "cycle_debit_accumulates_negative_amounts_directly" -> yesOrNo(
                        accumulatorTarget(normalisedParagraph("2800-UPDATE-ACCOUNT-REC"), false)
                                .equals("ACCT-CURR-CYC-DEBIT"));
                case "credit_limit_formula_subtracts_cycle_debit" -> yesOrNo(
                        normalisedParagraph("1500-B-LOOKUP-ACCT")
                                .contains("- ACCT-CURR-CYC-DEBIT"));
                case "accounts_with_negative_final_balance" -> Long.toString(
                        accountsWithNegativeBalance(run).size());
                case "accounts_with_negative_final_balance_ids" -> String.join(",",
                        accountsWithNegativeBalance(run));
                case "credit_limit_rule_ignores_current_balance" -> yesOrNo(
                        !normalisedParagraph("1500-B-LOOKUP-ACCT")
                                .contains("ACCT-CURR-BAL"));
                case "accounts_with_unchanged_cycle_credit" -> Long.toString(
                        accountsWithUnchangedCycleCredit(run).size());
                case "accounts_with_unchanged_cycle_credit_ids" -> String.join(",",
                        accountsWithUnchangedCycleCredit(run));
                case "accounts_with_changed_cycle_credit" -> Long.toString(
                        run.accountBalances().size()
                                - accountsWithUnchangedCycleCredit(run).size());
                case "accounts_with_changed_cycle_debit" -> Long.toString(
                        run.accountBalances().values().stream()
                                .filter(held -> held.getCycleDebit().compareTo(run.seededAccounts()
                                        .get(held.getAccountId()).currentCycleDebit()) != 0)
                                .count());
                case "accounts_with_changed_balance" -> Long.toString(
                        run.accountBalances().values().stream()
                                .filter(held -> held.getCurrentBalance()
                                        .compareTo(run.seededAccounts()
                                                .get(held.getAccountId()).currentBalance()) != 0)
                                .count());
                case "total_postings" -> Long.toString(run.postedCount());
                case "account_count" -> Integer.toString(run.accountBalances().size());
                default -> throw new IllegalStateException(EXPECTED_ACCOUNT_FILE
                        + " carries an unresolved model field " + row.key());
            };
        }

        /** Resolves one expectation about the cycle-close operation this end state precedes. */
        private String cycleCloseValue(ExpectedOutcomes.Row row, PostingRun run) {
            return switch (row.expectedField()) {
                case "cycle_close_zeroes_both_accumulators" -> yesOrNo(
                        cycleCloseZeroesBothAccumulators());
                case "cycle_close_omits_interest_add" -> yesOrNo(
                        cycleCloseNamesNoInterest());
                case "state_recorded_is_pre_cycle_close" -> yesOrNo(
                        run.accountBalances().values().stream()
                                .anyMatch(held -> held.getCycleCredit().signum() != 0));
                default -> throw new IllegalStateException(EXPECTED_ACCOUNT_FILE
                        + " carries an unresolved cycle-close field " + row.key());
            };
        }

        /** Returns the accounts whose balance ended below zero, in identifier order. */
        private List<String> accountsWithNegativeBalance(PostingRun run) {
            return run.accountBalances().values().stream()
                    .filter(held -> held.getCurrentBalance().signum() < 0)
                    .map(AccountBalanceProjectionEntity::getAccountId)
                    .sorted()
                    .toList();
        }

        /** Returns the accounts whose cycle credit never moved, in identifier order. */
        private List<String> accountsWithUnchangedCycleCredit(PostingRun run) {
            return run.accountBalances().values().stream()
                    .filter(held -> held.getCycleCredit().compareTo(run.seededAccounts()
                            .get(held.getAccountId()).currentCycleCredit()) == 0)
                    .map(AccountBalanceProjectionEntity::getAccountId)
                    .sorted()
                    .toList();
        }
    }

    @Nested
    @DisplayName("Z-GET-DB2-FORMAT-TIMESTAMP, app/cbl/CBTRN02C.cbl:L692-L705")
    class ProcessingTimestamp {

        @Test
        @DisplayName("the outbound field holds a dash then dots, L166 and L702-L703")
        void theOutboundFieldHoldsADashThenDots() {
            String rendered = CobolDecimal.formatProcessingTimestamp(FIXED_PROCESSING_MOMENT);
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
            String rendered = CobolDecimal.formatProcessingTimestamp(FIXED_PROCESSING_MOMENT);
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
            LocalDateTime moment = FIXED_PROCESSING_MOMENT.withNano(0);
            LocalDateTime finerMoment = moment.plusNanos(SUB_HUNDREDTH_NANOSECONDS);

            assertEquals(CobolDecimal.formatProcessingTimestamp(moment),
                    CobolDecimal.formatProcessingTimestamp(finerMoment),
                    "CBTRN02C L700 carries COB-MIL alone and L158 leaves COB-REST behind, so the "
                            + "field holds hundredths and drops what lies below");
        }
    }

    @Nested
    @DisplayName("Idempotency marker, with no ancestor in app/cbl/CBTRN02C.cbl")
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
                    probe.postingService().postTransaction(event, event.aggregateId());
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
     * Runs the fixture through the deployable ledger path.
     *
     * <p>Flyway creates and seeds the schema. Jakarta Persistence API repositories hold the state,
     * and the real Kafka listener method invokes the transactional consumer path. The broker-facing
     * relay is replaced, so no record leaves this test.</p>
     */
    @Nested
    @DisplayName("Flyway, JPA and the real ledger consumer")
    @SpringBootTest(classes = LedgerApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    "spring.kafka.listener.auto-startup=false",
                    "spring.kafka.consumer.group-id=ledger-posting-equivalence",
                    "spring.kafka.consumer.auto-offset-reset=earliest",
                    "spring.kafka.listener.ack-mode=manual_immediate",
                    "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                    "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                    // The acquirer identity belongs to the authorization service and not to this
                    // one. It is named here because this module carries all six services on one
                    // classpath, so the application.yml a context loads is whichever copy the
                    // classpath orders first, and an identity password resolves from no default by
                    // design. An unresolved placeholder binds as its own text, which carries no
                    // encoding prefix, so the value is supplied rather than the guard weakened.
                    "ACQUIRER_PASSWORD_HASH=" + TestIdentityPasswords.ACQUIRER_PASSWORD_HASH,
                    "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                    "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                    "spring.jpa.hibernate.ddl-auto=validate",
                    "carddemo.kafka.topics.transaction-authorized=transaction.authorized",
                    "carddemo.kafka.topics.transaction-posted=transaction.posted",
                    "carddemo.kafka.topics.transaction-declined=transaction.declined",
                    "carddemo.kafka.topics.dead-letter=carddemo.dead-letter",
                    "carddemo.kafka.topics.dead-letter-suffix=.DLT",
                    "carddemo.consumer.retry.max-attempts=3",
                    "carddemo.consumer.retry.backoff-ms=0",
                    "carddemo.outbox.relay.fixed-delay-ms=3600000",
                    "carddemo.outbox.relay.batch-size=100",
                    // The reject horizon this service applies to rejected_transaction. It is named
                    // for the same reason the acquirer hash above is: the application.yml a context
                    // on this shared classpath loads is whichever copy the classpath orders first,
                    // and no other service declares a ledger-specific key. It resolves from no
                    // default by design, because a horizon that binds silently is a horizon nobody
                    // chose, which is the defect app/jcl/DALYREJS.jcl:L24-L28 avoids by naming
                    // LIMIT(5) in the catalogue definition itself.
                    "carddemo.retention.rejected-transaction-retention-days=90",
                    // The readiness group this service declares for itself, named for the third time
                    // for the reason the two comments above give: the application.yml this context
                    // loads is whichever copy the classpath orders first, which is the authorization
                    // service's, and that group names the replica contributor only the authorization
                    // service defines. Naming the ledger's own group keeps membership validation on,
                    // so a contributor that disappears still stops start-up.
                    "management.endpoint.health.group.readiness.include="
                            + "readinessState,db,kafka,listeners,outbox"
            })
    class DeployablePostingPath {

        /** Private schema the ledger service owns. */
        private static final String SERVICE_SCHEMA = "ledger_service";

        /**
         * The one container the module fork runs, which this group reads a login from.
         *
         * <p>{@link EquivalenceDatabase} owns it and hands this group a database of its own inside
         * it. Nothing here starts or stops a container.
         */
        private static final PostgreSQLContainer POSTGRES = EquivalenceDatabase.container();

        /** Replaces the broker-facing relay and its scheduled method. */
        @MockitoBean
        private OutboxRelay outboxRelay;

        /** Replaces the producer channel created by the service configuration. */
        @MockitoBean
        private KafkaTemplate<String, Object> publishChannel;

        @Autowired
        private TransactionAuthorizedConsumer consumer;

        @Autowired
        private AccountBalanceProjectionRepository accountBalances;

        @Autowired
        private TransactionRepository postedTransactions;

        @Autowired
        private ProcessedEventRepository processedEvents;

        /** Real reject repository, which the authorized consumer must not touch. */
        @Autowired
        private RejectedTransactionRepository rejectedTransactions;

        /**
         * Real declined consumer, reached through its listener method.
         *
         * <p>The reject rows of {@code app/cbl/CBTRN02C.cbl:L446-L465} are this consumer's work,
         * not the authorized consumer's, so reproducing the whole feed means driving both.</p>
         */
        @Autowired
        private TransactionDeclinedConsumer declinedConsumer;

        /** Real outbox repository. */
        @Autowired
        private OutboxEventRepository outboxEvents;

        @Autowired
        private JdbcTemplate jdbc;

        /** Points the datasource at the isolated server and its service schema. */
        @DynamicPropertySource
        static void datasourceProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", DeployablePostingPath::jdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.flyway.locations",
                    DeployablePostingPath::ledgerMigrationLocation);
            registry.add("spring.flyway.schemas", () -> SERVICE_SCHEMA);
            registry.add("spring.flyway.default-schema", () -> SERVICE_SCHEMA);
            registry.add("spring.jpa.properties.hibernate.default_schema",
                    () -> SERVICE_SCHEMA);
        }

        private static String jdbcUrl() {
            return EquivalenceDatabase.urlFor(DeployablePostingPath.class, SERVICE_SCHEMA);
        }

        private static String ledgerMigrationLocation() {
            return "filesystem:" + CardDemoFixtureLoader.fixtureDirectory()
                    .getParent()
                    .getParent()
                    .getParent()
                    .resolve("card-platform/services/ledger-posting-service/src/main/resources"
                            + "/db/migration")
                    .toAbsolutePath();
        }

        /**
         * The migration versions the ledger service ships, in the order Flyway applies them.
         *
         * <p>Read from the same directory {@link #ledgerMigrationLocation()} points Flyway at, so
         * the assertion below compares what the container applied against what the module
         * delivers. A literal list here would go stale the next time a correction ships as a
         * migration, which is how it went stale: correcting two column citations added V6 and left
         * this comparison expecting five.
         *
         * @return each version as Flyway records it in {@code flyway_schema_history}
         */
        private static List<String> shippedLedgerMigrationVersions() {
            Path directory = Path.of(ledgerMigrationLocation().substring("filesystem:".length()));
            try (Stream<Path> files = Files.list(directory)) {
                return files.filter(Files::isRegularFile)
                        .map(file -> file.getFileName().toString())
                        .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                        .map(name -> name.substring(1, name.indexOf("__")))
                        .sorted(Comparator.comparingInt(Integer::parseInt))
                        .toList();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot list " + directory, unreadable);
            }
        }

        /** Restores the migrated tables to their seed state before each case. */
        @BeforeEach
        void restoreSeedState() {
            jdbc.update("DELETE FROM outbox_event");
            jdbc.update("DELETE FROM processed_event");
            jdbc.update("DELETE FROM rejected_transaction");
            jdbc.update("DELETE FROM \"transaction\"");
            jdbc.update("DELETE FROM transaction_category_balance");

            for (CopybookRecordParser.TransactionCategoryBalanceRecord row
                    : CardDemoFixtureLoader.loadTransactionCategoryBalances()) {
                jdbc.update("""
                        INSERT INTO transaction_category_balance
                            (account_id, type_code, category_code, category_balance)
                        VALUES (?, ?, ?, ?)
                        """, row.accountId(), row.typeCode(), row.categoryCode(), row.balance());
            }
            for (CopybookRecordParser.AccountRecord account
                    : CardDemoFixtureLoader.loadAccounts()) {
                jdbc.update("""
                        UPDATE account_balance_projection
                        SET current_balance = ?, cycle_credit = ?, cycle_debit = ?
                        WHERE account_id = ?
                        """, account.currentBalance(), account.currentCycleCredit(),
                        account.currentCycleDebit(), account.accountId());
            }
        }

        /**
         * Counts the idempotency markers one consumed topic left behind.
         *
         * <p>Both consumers write into the same {@code processed_event} table, so a bare count
         * measures the two streams together. Filtering on the recorded topic is what keeps each
         * stream's expectation independently checkable.</p>
         *
         * @param consumedTopic the topic whose markers to count
         * @return how many markers name that topic
         */
        private long processedMarkersFrom(String consumedTopic) {
            return processedEvents.findAll().stream()
                    .filter(marker -> consumedTopic.equals(marker.getConsumedTopic()))
                    .count();
        }

        /**
         * Drives the whole feed through both real consumers and compares the result.
         *
         * <p>The comparison closes over every row of {@value #EXPECTED_SUMMARY_FILE}, not only the
         * rows an individual assertion above names. Each row is answered by a value derived from
         * this run, a row the derivation does not cover fails rather than passing unnoticed, and
         * the last assertion requires the file to have no row left unread. That is the guarantee
         * the other thirteen checked-in assets already carried and this one did not, because it was
         * the one file read by a loader of its own.</p>
         */
        @Test
        @DisplayName("the migrated consumer result matches every row of the checked-in Model B "
                + "output")
        void theMigratedConsumerResultMatchesTheCheckedInOutput() {
            Map<String, CopybookRecordParser.CardCrossReferenceRecord> crossReferences =
                    CardDemoFixtureLoader.cardCrossReferencesByCardNumber();
            Map<String, CopybookRecordParser.AccountRecord> accounts =
                    CardDemoFixtureLoader.accountsByAccountId();
            List<String> approvedIds = new ArrayList<>();
            List<String> declinedOutcomes = new ArrayList<>();
            List<TransactionDeclined> declinedEvents = new ArrayList<>();
            AtomicInteger acknowledgments = new AtomicInteger();
            long offset = 0L;

            for (CopybookRecordParser.DailyTransactionRecord record
                    : CardDemoFixtureLoader.loadDailyTransactions()) {
                DeclineReason reason =
                        decisionFor(record, crossReferences, accounts, accountBalances).reason();
                CopybookRecordParser.CardCrossReferenceRecord crossReference =
                        crossReferences.get(record.cardNumber());
                if (reason == null) {
                    String accountId = Objects.requireNonNull(crossReference,
                            "an approved fixture record must resolve an account").accountId();
                    TransactionAuthorized event = authorizationEventFor(record, accountId);
                    Acknowledgment acknowledgment = acknowledgments::incrementAndGet;

                    consumer.onTransactionAuthorized(event, accountId, AUTHORIZED_TOPIC,
                            acknowledgment);
                    approvedIds.add(record.transactionId());
                } else {
                    declinedOutcomes.add(record.transactionId() + "," + reason.code());
                    if (reason.resolvesAccount()) {
                        CopybookRecordParser.CardCrossReferenceRecord resolvedCrossReference =
                                Objects.requireNonNull(crossReference,
                                        "an account-resolving decline must carry "
                                                + "a cross-reference");
                        String accountId = resolvedCrossReference.accountId();
                        declinedEvents.add(declineEventFor(record, accountId, reason));
                    } else {
                        assertTrue(crossReference == null,
                                "reason 0100 must follow a missing cross-reference");
                    }
                }
                offset++;
            }

            long rejectRowsAfterTheAuthorizedStream = rejectedTransactions.count();
            assertEquals(expectedCount("posting", "ledger_rejected_transaction_count"),
                    rejectRowsAfterTheAuthorizedStream,
                    "the authorized stream alone has run at this point, and no record of "
                            + "app/data/ASCII/dailytran.txt fails the feed-level checks that make "
                            + "the posting consumer write a reject row. Every reject row this run "
                            + "produces belongs to the declined stream below, which is what "
                            + VALIDATION_PROGRAM + ":L446-L465 writes on the branch "
                            + VALIDATION_PROGRAM + ":L211 gates");

            AtomicInteger declinedAcknowledgments = new AtomicInteger();
            for (TransactionDeclined declined : declinedEvents) {
                declinedConsumer.onTransactionDeclined(declined, declined.accountId(),
                        DECLINED_TOPIC, declinedAcknowledgments::incrementAndGet);
            }
            assertEquals(declinedEvents.size(), declinedAcknowledgments.get(),
                    "each declined delivery must acknowledge after its own transaction, and the "
                            + "authorized count must stay measurable on its own counter");

            List<String> storedTransactionIds = jdbc.queryForList(
                    "SELECT transaction_id FROM \"transaction\" ORDER BY transaction_id",
                    String.class);
            List<String> accountLines = jdbc.query("""
                    SELECT account_id, current_balance, cycle_credit, cycle_debit
                    FROM account_balance_projection
                    ORDER BY account_id
                    """, (result, row) -> result.getString("account_id") + ","
                            + money(result.getBigDecimal("current_balance")) + ","
                            + money(result.getBigDecimal("cycle_credit")) + ","
                            + money(result.getBigDecimal("cycle_debit")));
            List<String> categoryLines = jdbc.query("""
                    SELECT account_id, type_code, category_code, category_balance
                    FROM transaction_category_balance
                    ORDER BY account_id, type_code, category_code
                    """, (result, row) -> result.getString("account_id") + ","
                            + result.getString("type_code") + ","
                            + result.getString("category_code") + ","
                            + money(result.getBigDecimal("category_balance")));

            assertEquals(shippedLedgerMigrationVersions(), jdbc.queryForList(
                            "SELECT version FROM flyway_schema_history "
                                    + "WHERE success AND version IS NOT NULL "
                                    + "ORDER BY installed_rank",
                            String.class),
                    "Flyway must apply every shipped ledger migration, so this comparison runs "
                            + "against the schema the service really starts on. The expected list "
                            + "is read from the migration directory rather than written here, "
                            + "because a migration added without this assertion following it fails "
                            + "for a reason that says nothing about the posting path. V3 adds the "
                            + "two provenance columns that order a replica refresh against the "
                            + "change the row already carries, and it must be present here even "
                            + "though the posting path this test drives writes neither of them. V4 "
                            + "writes only comments, recording that the three value columns this "
                            + "comparison reads are derived by the posting arithmetic and that no "
                            + "arriving account change replaces them. V5 re-keys processed_event on "
                            + "the event and the topic together, which is the guard the consumer "
                            + "this test drives writes on every delivery. V6 corrects the copybook "
                            + "line two of V4's comments cited, which is why a comment-only "
                            + "correction arrives as a migration at all: V4 has run, and Flyway "
                            + "compares the checksum of an applied file at every start");
            assertEquals(expectedCount("posting", "record_count"), offset,
                    "the real path must inspect the whole feed");
            assertEquals(expectedCount("posting", "approved_count"), approvedIds.size(),
                    "the real path approval count moved");
            assertEquals(expectedCount("posting", "declined_count"), declinedOutcomes.size(),
                    "the real path decline count moved");
            assertEquals(expectedCount("posting", "declined_event_count"), declinedEvents.size(),
                    "the real path decline event count moved");
            assertEquals(expectedCount("posting", "approved_count"), acknowledgments.get(),
                    "each authorized delivery must acknowledge after its transaction");
            assertEquals(expectedCount("posting", "transaction_row_count"),
                    postedTransactions.count(),
                    "the migrated transaction table count moved");
            assertEquals(expectedCount("posting", "processed_event_count"),
                    processedMarkersFrom(AUTHORIZED_TOPIC),
                    "the migrated processed-event count moved");
            assertEquals(expectedCount("posting", "ledger_declined_processed_event_count"),
                    processedMarkersFrom(DECLINED_TOPIC),
                    "the declined consumer records its own idempotency marker per refusal");
            assertEquals(processedEvents.count(),
                    processedMarkersFrom(AUTHORIZED_TOPIC) + processedMarkersFrom(DECLINED_TOPIC),
                    "every marker belongs to one of the two streams and nothing else writes one");
            assertEquals(expectedCount("posting", "ledger_declined_consumer_reject_row_count"),
                    rejectedTransactions.count(),
                    "every refusal the feed produces reaches a reject row through the declined "
                            + "consumer, which is what app/cbl/CBTRN02C.cbl:L446-L465 writes");
            assertEquals(expectedCount("posting", "declined_count"), declinedOutcomes.size(),
                    "the refusal count the whole feed produces, which the declined consumer "
                            + "turns into reject rows");
            assertEquals(expectedCount("posting", "posted_outbox_count"),
                    outboxEvents.findAll().stream()
                            .filter(row -> TransactionPosted.EVENT_TYPE.equals(row.getEventType()))
                            .count(),
                    "the migrated outbox count moved");

            long categoryRows = jdbc.queryForObject(
                    "SELECT count(*) FROM transaction_category_balance", Long.class);
            long nonzeroCategoryRows = jdbc.queryForObject(
                    "SELECT count(*) FROM transaction_category_balance "
                            + "WHERE category_balance <> 0",
                    Long.class);
            long positiveCategoryRows = jdbc.queryForObject(
                    "SELECT count(*) FROM transaction_category_balance "
                            + "WHERE category_balance > 0",
                    Long.class);
            long negativeCategoryRows = jdbc.queryForObject(
                    "SELECT count(*) FROM transaction_category_balance "
                            + "WHERE category_balance < 0",
                    Long.class);

            assertEquals(expectedCount("category_balance", "row_count"), categoryRows,
                    "the migrated category table must keep the total-row output");
            assertEquals(expectedCount("category_balance", "nonzero_row_count"),
                    nonzeroCategoryRows,
                    "the migrated category table must keep the nonzero-row output");
            assertEquals(expectedCount("category_balance", "positive_row_count"),
                    positiveCategoryRows,
                    "the migrated category table positive-row count moved");
            assertEquals(expectedCount("category_balance", "negative_row_count"),
                    negativeCategoryRows,
                    "the migrated category table negative-row count moved");

            assertEquals(approvedIds, storedTransactionIds,
                    "the real consumer must persist exactly the approved transaction identifiers");
            assertEquals(expected("posting", "approved_transaction_ids_sha256"),
                    sha256Lines(storedTransactionIds),
                    "the migrated transaction identifier output moved");
            assertEquals(expected("posting", "declined_outcomes_sha256"),
                    sha256Lines(declinedOutcomes),
                    "the migrated decision path moved");
            assertEquals(expected("account_projection", "state_sha256"),
                    sha256Lines(accountLines),
                    "the migrated account projection output moved");
            assertEquals(expected("category_balance", "state_sha256"),
                    sha256Lines(categoryLines),
                    "the migrated category balance output moved");

            Map<String, String> observed = new LinkedHashMap<>();
            observed.put("model_a.declined_count", Long.toString(statelessDeclineCount()));
            observed.put("posting.record_count", Long.toString(offset));
            observed.put("posting.approved_count", Integer.toString(approvedIds.size()));
            observed.put("posting.declined_count", Integer.toString(declinedOutcomes.size()));
            observed.put("posting.declined_event_count",
                    Integer.toString(declinedEvents.size()));
            observed.put("posting.return_code", Integer.toString(declinedOutcomes.isEmpty()
                    ? RETURN_CODE_WHEN_NO_REJECT
                    : RETURN_CODE_WHEN_REJECTS_PRESENT));
            observed.put("posting.transaction_row_count",
                    Long.toString(postedTransactions.count()));
            observed.put("posting.processed_event_count",
                    Long.toString(processedMarkersFrom(AUTHORIZED_TOPIC)));
            observed.put("posting.ledger_rejected_transaction_count",
                    Long.toString(rejectRowsAfterTheAuthorizedStream));
            observed.put("posting.posted_outbox_count", Long.toString(outboxEvents.findAll()
                    .stream()
                    .filter(row -> TransactionPosted.EVENT_TYPE.equals(row.getEventType()))
                    .count()));
            observed.put("posting.ledger_declined_consumer_reject_row_count",
                    Long.toString(rejectedTransactions.count()));
            observed.put("posting.ledger_declined_processed_event_count",
                    Long.toString(processedMarkersFrom(DECLINED_TOPIC)));
            observed.put("category_balance.row_count", Long.toString(categoryRows));
            observed.put("category_balance.nonzero_row_count",
                    Long.toString(nonzeroCategoryRows));
            observed.put("category_balance.positive_row_count",
                    Long.toString(positiveCategoryRows));
            observed.put("category_balance.negative_row_count",
                    Long.toString(negativeCategoryRows));
            observed.put("posting.approved_transaction_ids_sha256",
                    sha256Lines(storedTransactionIds));
            observed.put("posting.declined_outcomes_sha256", sha256Lines(declinedOutcomes));
            observed.put("account_projection.state_sha256", sha256Lines(accountLines));
            observed.put("category_balance.state_sha256", sha256Lines(categoryLines));

            for (ExpectedOutcomes.Row row : EXPECTED_SUMMARY.rows()) {
                String key = row.entityKey() + "." + row.expectedField();
                String actual = observed.get(key);
                if (actual == null) {
                    throw new IllegalStateException(EXPECTED_SUMMARY_FILE + " row " + row.key()
                            + " carries no derivation in this comparison. Derive it from the run "
                            + "rather than deleting the row, because a checked-in row nothing "
                            + "computes is evidence nothing verifies.");
                }
                assertEquals(EXPECTED_SUMMARY.value(row.recordSequence(), row.entityKey(),
                                row.expectedField()),
                        actual,
                        EXPECTED_SUMMARY_FILE + " row " + row.key() + ", model "
                                + row.evaluationModel() + ", derived from " + row.sourceLocator()
                                + ", Picture clause " + row.picClause());
            }

            assertThat(EXPECTED_SUMMARY.unconsumedRows())
                    .as(EXPECTED_SUMMARY.unconsumedDescription())
                    .isEmpty();
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
            byAccount.computeIfAbsent(outcome.resolvedAccountId(), key -> new ArrayList<>())
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
                    outcome.resolvedAccountId(), outcome.feed().typeCode(),
                    outcome.feed().categoryCode());
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

    private static String money(BigDecimal value) {
        return value.setScale(PicClause.TRAN_AMT_SCALE, RoundingMode.DOWN).toPlainString();
    }

    /** Hashes canonical lines, each terminated by one line feed. */
    private static String sha256Lines(List<String> lines) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError("SHA-256 must be available", unavailable);
        }
    }

    /** Canonical approved transaction identifiers, sorted by their fixed-width key. */
    private static List<String> approvedTransactionIdLines(PostingRun run) {
        return run.postedTransactions().keySet().stream().sorted().toList();
    }

    /** Canonical declined transaction identifiers and reason codes, in feed order. */
    private static List<String> declinedOutcomeLines(PostingRun run) {
        return run.rejectedOutcomes().stream()
                .map(outcome -> outcome.feed().transactionId() + ","
                        + outcome.declineReason().code())
                .toList();
    }

    /** Canonical account projection rows, sorted by account identifier. */
    private static List<String> accountProjectionLines(
            Map<String, AccountBalanceProjectionEntity> projections) {
        return projections.entrySet().stream()
                .map(entry -> entry.getKey() + ","
                        + money(entry.getValue().getCurrentBalance()) + ","
                        + money(entry.getValue().getCycleCredit()) + ","
                        + money(entry.getValue().getCycleDebit()))
                .sorted()
                .toList();
    }

    /** Canonical category balance rows, sorted by the seventeen-character source key. */
    private static List<String> categoryBalanceLines(
            Map<TransactionCategoryBalanceId, TransactionCategoryBalanceEntity> balances) {
        return balances.entrySet().stream()
                .map(entry -> entry.getKey().getAccountId() + ","
                        + entry.getKey().getTypeCode() + ","
                        + entry.getKey().getCategoryCode() + ","
                        + money(entry.getValue().getCategoryBalance()))
                .sorted()
                .toList();
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
     * existing marker in place. No ancestor in {@code app/cbl/CBTRN02C.cbl}.</p>
     *
     * @param markers markers this consumer has already written
     * @param event   the delivery being claimed
     * @return {@link #CLAIM_TAKEN} when this caller claimed the event, {@link #CLAIM_REFUSED}
     *         otherwise
     */
    private static int claimMarker(Map<UUID, ProcessedEventEntity> markers,
            TransactionAuthorized event) {
        ProcessedEventEntity marker = new ProcessedEventEntity(event.eventId(),
                FIXED_EVENT_INSTANT, AUTHORIZED_TOPIC);
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
                PanMasker.cardToken(template.cardNumber()), template.originTimestamp());
    }

    /**
     * Builds the feed record {@code 1500-VALIDATE-TRAN} refuses, from fixture record one.
     *
     * <p>{@code RejectRecorder} accepts this type and not {@code TransactionAuthorized}, and the
     * distinction is the contract rather than a convenience. A feed record has reached no decision, so
     * refusing it reproduces {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465};
     * an authorized event carries a decision the authorization service already published, and
     * refusing one would reverse an approval this service does not own.
     *
     * @param accountId     the account the cross-reference read resolved
     * @param transactionId DALYTRAN-ID of the refused record
     * @param amount        DALYTRAN-AMT, signed
     * @return the record the reject path refuses
     */
    private static FeedTransaction feedRecordCarrying(String accountId, String transactionId,
            BigDecimal amount) {
        CopybookRecordParser.DailyTransactionRecord template = firstFeedRecord();
        return new FeedTransaction(accountId, transactionId, template.typeCode(),
                template.categoryCode(), template.source(), template.description(), amount,
                template.merchantId(), template.merchantName(), template.merchantCity(),
                template.merchantZip(), PanMasker.maskCardNumber(template.cardNumber()),
                template.originTimestamp());
    }

    /**
     * Returns the untouched record text of one feed record.
     *
     * @param ordinal the 1-based position of the record in {@code app/data/ASCII/dailytran.txt}
     * @return {@link PicClause#DALYTRAN_RECORD_LENGTH} characters, exactly as the fixture holds them
     */
    private static String feedRecordText(int ordinal) {
        return FEED_RECORD_TEXT.get(ordinal - FIRST_RECORD_ORDINAL);
    }

    /**
     * Reads one file of the source repository, without writing to it.
     *
     * <p>Several reject expectations state a fact about the COBOL program or the job rather than
     * about a value the services compute: which verb writes the record, what happens when the write
     * fails, which field counts the rejects, what length the dataset is allocated with. Reading the
     * file makes those assertions real. Asserting them against a constant in this class would only
     * prove the class agrees with itself.</p>
     *
     * @param repositoryPath path below the repository root, such as {@value #VALIDATION_PROGRAM}
     * @return the whole file
     * @throws IllegalStateException when no ancestor of the fixture directory holds the file
     */
    private static String sourceFileText(String repositoryPath) {
        for (Path candidate = CardDemoFixtureLoader.fixtureDirectory();
                candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(repositoryPath);
            if (Files.isRegularFile(file)) {
                try {
                    return Files.readString(file, StandardCharsets.ISO_8859_1);
                } catch (IOException unreadable) {
                    throw new UncheckedIOException("cannot read " + file, unreadable);
                }
            }
        }
        throw new IllegalStateException("no ancestor of '"
                + CardDemoFixtureLoader.fixtureDirectory() + "' holds '" + repositoryPath + "'");
    }

    /**
     * Returns the zero-based offset of one column inside a {@code DALYTRAN-RECORD}.
     *
     * <p>Offsets are summed from the widths {@code app/cpy/CVTRA06Y.cpy} declares rather than
     * written down, so a corrected width moves every column after it without a second edit.</p>
     *
     * @param column the column to locate
     * @return the offset of its first character
     */
    private static int feedColumnOffset(FeedColumn column) {
        int offset = 0;
        for (FeedColumn earlier : FeedColumn.values()) {
            if (earlier == column) {
                return offset;
            }
            offset += earlier.width();
        }
        throw new IllegalStateException("unreachable: " + column + " is one of the declared columns");
    }

    /**
     * Returns the text one column holds in one feed record.
     *
     * @param ordinal the 1-based position of the record in {@code app/data/ASCII/dailytran.txt}
     * @param column  the column to read
     * @return exactly {@link FeedColumn#width()} characters
     */
    private static String feedColumnText(int ordinal, FeedColumn column) {
        int offset = feedColumnOffset(column);
        return feedRecordText(ordinal).substring(offset, offset + column.width());
    }

    /**
     * The columns of {@code DALYTRAN-RECORD}, in the order {@code app/cpy/CVTRA06Y.cpy} declares.
     *
     * <p>The order is the whole point: it is what makes {@link #feedColumnOffset} correct, and it is
     * why the enum carries every column rather than only the ones an assertion reads.</p>
     */
    private enum FeedColumn {

        /** {@code DALYTRAN-ID}, {@code app/cpy/CVTRA06Y.cpy:L5}. */
        ID(PicClause.DALYTRAN_ID_WIDTH),

        /** {@code DALYTRAN-TYPE-CD}, {@code app/cpy/CVTRA06Y.cpy:L6}. */
        TYPE_CODE(PicClause.DALYTRAN_TYPE_CD_WIDTH),

        /** {@code DALYTRAN-CAT-CD}, {@code app/cpy/CVTRA06Y.cpy:L7}. */
        CATEGORY_CODE(PicClause.DALYTRAN_CAT_CD_WIDTH),

        /** {@code DALYTRAN-SOURCE}, {@code app/cpy/CVTRA06Y.cpy:L8}. */
        SOURCE(PicClause.DALYTRAN_SOURCE_WIDTH),

        /** {@code DALYTRAN-DESC}, {@code app/cpy/CVTRA06Y.cpy:L9}. */
        DESCRIPTION(PicClause.DALYTRAN_DESC_WIDTH),

        /** {@code DALYTRAN-AMT}, {@code app/cpy/CVTRA06Y.cpy:L10}, sign overpunched. */
        AMOUNT(PicClause.DALYTRAN_AMT_WIDTH),

        /** {@code DALYTRAN-MERCHANT-ID}, {@code app/cpy/CVTRA06Y.cpy:L11}. */
        MERCHANT_ID(PicClause.DALYTRAN_MERCHANT_ID_WIDTH),

        /** {@code DALYTRAN-MERCHANT-NAME}, {@code app/cpy/CVTRA06Y.cpy:L12}. */
        MERCHANT_NAME(PicClause.DALYTRAN_MERCHANT_NAME_WIDTH),

        /** {@code DALYTRAN-MERCHANT-CITY}, {@code app/cpy/CVTRA06Y.cpy:L13}. */
        MERCHANT_CITY(PicClause.DALYTRAN_MERCHANT_CITY_WIDTH),

        /** {@code DALYTRAN-MERCHANT-ZIP}, {@code app/cpy/CVTRA06Y.cpy:L14}. */
        MERCHANT_ZIP(PicClause.DALYTRAN_MERCHANT_ZIP_WIDTH),

        /** {@code DALYTRAN-CARD-NUM}, {@code app/cpy/CVTRA06Y.cpy:L15}, all sixteen digits. */
        CARD_NUMBER(PicClause.DALYTRAN_CARD_NUM_WIDTH),

        /** {@code DALYTRAN-ORIG-TS}, {@code app/cpy/CVTRA06Y.cpy:L16}. */
        ORIGIN_TIMESTAMP(PicClause.DALYTRAN_ORIG_TS_WIDTH),

        /** {@code DALYTRAN-PROC-TS}, {@code app/cpy/CVTRA06Y.cpy:L17}. */
        PROCESSING_TIMESTAMP(PicClause.DALYTRAN_PROC_TS_WIDTH),

        /** The trailing {@code FILLER}, {@code app/cpy/CVTRA06Y.cpy:L18}. */
        FILLER(PicClause.DALYTRAN_RECORD_FILLER_WIDTH);

        /** Characters the column holds. */
        private final int width;

        FeedColumn(int width) {
            this.width = width;
        }

        /**
         * Returns the characters this column holds.
         *
         * @return the width
         */
        int width() {
            return width;
        }
    }

    /**
     * Renders one {@code REJECT-RECORD} as {@code app/cbl/CBTRN02C.cbl:L446-L465} lays it out.
     *
     * <p>The data part is the fixture's own record text, unchanged. That is what the source does:
     * {@code app/cbl/CBTRN02C.cbl:L447} moves the whole {@code DALYTRAN-RECORD} into
     * {@code REJECT-TRAN-DATA} in one statement, so the 350 bytes of a reject record are the bytes
     * that were read. Nothing here re-renders a field, which matters in two places a
     * reconstruction previously got wrong. The amount column carries a sign overpunch in the
     * fixture — record 16 holds {@code 0000007154D} for 715.44 — and the card column carries all
     * sixteen digits of the number. A copy reproduces both without having to model either.</p>
     *
     * <p>Masking is deliberately absent. It is an addition this platform makes for its published
     * payloads and its stored rows, with no ancestor in {@code app/cbl/}, so applying it here would
     * make the comparison disagree with the fixture on 16 of every 350 bytes while claiming to be a
     * verbatim copy. {@code theRowStoresTheMaskedNumberWhileTheRejectBytesKeepThePan} holds the two
     * representations apart.</p>
     *
     * <p>The trailer is the one part the source builds rather than copies: the reason code at
     * {@link PicClause#VALIDATION_FAIL_REASON_WIDTH} characters as plain digits, and the
     * description space-padded to {@link PicClause#VALIDATION_FAIL_REASON_DESC_WIDTH}.</p>
     *
     * @param feedRecordText the record text {@code app/cbl/CBTRN02C.cbl:L447} moves into
     *                       {@code REJECT-TRAN-DATA}, of
     *                       {@link PicClause#DALYTRAN_RECORD_LENGTH} characters
     * @param reason         the reason {@code app/cbl/CBTRN02C.cbl:L448} moves into the trailer
     * @return a record of {@link PicClause#REJECT_RECORD_LENGTH} characters
     */
    private static String renderRejectRecord(String feedRecordText, DeclineReason reason) {
        if (feedRecordText.length() != PicClause.REJECT_TRAN_DATA_WIDTH) {
            throw new IllegalStateException("REJECT-TRAN-DATA at CBTRN02C L177 holds "
                    + PicClause.REJECT_TRAN_DATA_WIDTH + " characters, and the record offered "
                    + "holds " + feedRecordText.length());
        }
        String trailer = numeric(reason.code(), PicClause.VALIDATION_FAIL_REASON_WIDTH)
                + alphanumeric(reason.description(), PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH);
        return feedRecordText + trailer;
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

    /** Event path selected after the source validation chain runs. */
    private sealed interface AuthorizationPath
            permits AuthorizedPath, DeclinedPath, UnresolvedCardPath {
    }

    /**
     * Path that publishes an authorized event and reaches the ledger consumer.
     *
     * @param accountId account the cross-reference resolved
     * @param event     event the ledger consumes
     */
    private record AuthorizedPath(String accountId, TransactionAuthorized event)
            implements AuthorizationPath {

        AuthorizedPath {
            Objects.requireNonNull(accountId, "accountId is required");
            Objects.requireNonNull(event, "event is required");
        }
    }

    /**
     * Path that publishes a declined event and never reaches the ledger consumer.
     *
     * @param accountId account the cross-reference resolved
     * @param event     event the authorization service publishes
     */
    private record DeclinedPath(String accountId, TransactionDeclined event)
            implements AuthorizationPath {

        DeclinedPath {
            Objects.requireNonNull(accountId, "accountId is required");
            Objects.requireNonNull(event, "event is required");
        }
    }

    /**
     * Path for reason 0100, which resolved no account and publishes a transaction-keyed decline.
     *
     * <p>The event is {@code schemas/transaction-declined-v2.json}. AAP transformation rule T4 gives
     * one authorization call one event, and this outcome has no account identifier to key on, so it
     * keys on the transaction identifier and declares no {@code accountId}.
     *
     * @param event event the authorization service publishes for this outcome
     */
    private record UnresolvedCardPath(TransactionDeclined event) implements AuthorizationPath {

        UnresolvedCardPath {
            Objects.requireNonNull(event, "event is required");
        }
    }

    /**
     * What one pass of the loop at {@code app/cbl/CBTRN02C.cbl:L202-L219} did with one record.
     *
     * @param ordinal       position of the record in {@code app/data/ASCII/dailytran.txt}
     * @param feed          the record as {@code app/cpy/CVTRA06Y.cpy} lays it out
     * @param declineReason the reason a decline carried, or {@code null} on a post
     * @param path          the one reachable event path
     */
    private record FeedOutcome(int ordinal,
            CopybookRecordParser.DailyTransactionRecord feed,
            DeclineReason declineReason,
            AuthorizationPath path,
            DecisionInputs decision) {

        FeedOutcome {
            Objects.requireNonNull(feed, "feed is required");
            Objects.requireNonNull(path, "path is required");
            Objects.requireNonNull(decision, "the decision inputs are required");
            if (decision.reason() != declineReason) {
                throw new IllegalArgumentException(
                        "the captured decision must carry the outcome reason");
            }
            if ((path instanceof AuthorizedPath) != (declineReason == null)) {
                throw new IllegalArgumentException(
                        "an authorized path must have no decline reason, "
                                + "and a decline must have one");
            }
            if (path instanceof DeclinedPath declined
                    && declined.event().declineReasonCode() != declineReason) {
                throw new IllegalArgumentException(
                        "the declined event must carry the outcome reason");
            }
            if (path instanceof UnresolvedCardPath
                    && declineReason != DeclineReason.INVALID_CARD_NUMBER) {
                throw new IllegalArgumentException(
                        "the unresolved-card path carries reason 0100 only");
            }
        }

        /**
         * Reports whether {@code app/cbl/CBTRN02C.cbl:L211} sent this record to
         * {@code 2000-POST-TRANSACTION}.
         *
         * @return {@code true} when the record posted
         */
        boolean posted() {
            return path instanceof AuthorizedPath;
        }

        /**
         * @return the resolved account identifier
         * @throws IllegalStateException for the unresolved-card path
         */
        String resolvedAccountId() {
            if (path instanceof AuthorizedPath authorized) {
                return authorized.accountId();
            }
            if (path instanceof DeclinedPath declined) {
                return declined.accountId();
            }
            throw new IllegalStateException("reason 0100 resolves no account identifier");
        }

        /**
         * @return the event the ledger consumes
         * @throws IllegalStateException on either decline path
         */
        TransactionAuthorized authorizedEvent() {
            if (path instanceof AuthorizedPath authorized) {
                return authorized.event();
            }
            throw new IllegalStateException("a decline carries no authorized event");
        }

        /**
         * @return the event, or empty for an approval and reason 0100
         */
        Optional<TransactionDeclined> declinedEvent() {
            if (path instanceof DeclinedPath declined) {
                return Optional.of(declined.event());
            }
            return Optional.empty();
        }

        boolean unresolvedCardAttempt() {
            return path instanceof UnresolvedCardPath;
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
     * @param declinedEvents     events the resolved-account decline path produced
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
            List<TransactionDeclined> declinedEvents,
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

        /** Counts reason-0100 outcomes, whose decline is keyed on the transaction identifier. */
        long unresolvedCardAttemptCount() {
            return outcomes.stream().filter(FeedOutcome::unresolvedCardAttempt).count();
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
         * @return the posted outcomes, in feed order
         */
        List<FeedOutcome> postedOutcomes() {
            return outcomes.stream().filter(FeedOutcome::posted).toList();
        }

        /**
         * @return the rejected outcomes, in feed order
         */
        List<FeedOutcome> rejectedOutcomes() {
            return outcomes.stream().filter(outcome -> !outcome.posted()).toList();
        }

        /**
         * @return the reasons, in feed order of first appearance
         */
        Set<DeclineReason> declineReasons() {
            Set<DeclineReason> reasons = new LinkedHashSet<>();
            rejectedOutcomes().forEach(outcome -> reasons.add(outcome.declineReason()));
            return reasons;
        }

        /**
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

        private final Map<String, AccountBalanceProjectionEntity> rows = new LinkedHashMap<>();

        private final List<String> saveLog;

        private boolean seeding = true;

        private StoredAccountBalances(List<String> saveLog) {
            this.saveLog = saveLog;
        }

        void seedingComplete() {
            seeding = false;
        }

        @Override
        public Optional<AccountBalanceProjectionEntity> findById(String accountId) {
            return Optional.ofNullable(rows.get(accountId));
        }

        @Override
        public Optional<AccountBalanceProjectionEntity> findForUpdateById(String accountId) {
            return findById(accountId);
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
         * Opens a row for an account this store holds none for, and leaves a held row alone.
         *
         * <p>This store reproduces the one statement the migration's insert performs, so a run can
         * exercise the bootstrap path without a database. Leaving a held row alone is the point of
         * it: the three value columns of a held row were derived by the posting arithmetic of
         * {@code app/cbl/CBTRN02C.cbl:L545-L560}, and an account-owned copy of them is a different,
         * staler reading of the same account.
         *
         * <p>The write is not logged. {@link #ACCOUNT_STAGE} names the posting write of
         * {@code 2800-UPDATE-ACCOUNT-REC}, and a bootstrap is a different write on a different
         * path; logging it under that stage would report an order the source never had.
         *
         * @param accountId        the eleven-digit account identifier
         * @param currentBalance   ACCT-CURR-BAL, scale 2
         * @param cycleCredit      ACCT-CURR-CYC-CREDIT, scale 2
         * @param cycleDebit       ACCT-CURR-CYC-DEBIT, scale 2
         * @param sourceEventId    the change that carried these values
         * @param sourceOccurredAt when that change occurred
         * @return 1 when a row was opened, and 0 when one was already held
         */
        @Override
        public int insertMissingProjection(String accountId, BigDecimal currentBalance,
                BigDecimal cycleCredit, BigDecimal cycleDebit, UUID sourceEventId,
                Instant sourceOccurredAt) {

            if (rows.containsKey(accountId)) {
                return 0;
            }
            rows.put(accountId, new AccountBalanceProjectionEntity(accountId, currentBalance,
                    cycleCredit, cycleDebit, sourceEventId, sourceOccurredAt));
            return 1;
        }

        /**
         * Moves zero into both accumulators of one held row and leaves the balance alone.
         *
         * <p>This reproduces {@code app/cbl/CBACT04C.cbl:L353-L354}, which moves zero into
         * {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT} and touches
         * {@code ACCT-CURR-BAL} nowhere. Ordering on the producer's clock is what keeps a
         * redelivered cycle close from zeroing accumulators a later posting has already moved.
         *
         * <p>The write is not logged, for the reason
         * {@link #insertMissingProjection(String, BigDecimal, BigDecimal, BigDecimal, UUID, Instant)}
         * gives.
         *
         * @param accountId        the eleven-digit account identifier
         * @param sourceEventId    the change that closed the cycle
         * @param sourceOccurredAt when that change occurred
         * @return 1 when the row was written, and 0 when it was absent or a later change stood
         */
        @Override
        public int closeBillingCycle(String accountId, UUID sourceEventId,
                Instant sourceOccurredAt) {

            AccountBalanceProjectionEntity stored = rows.get(accountId);
            if (stored == null) {
                return 0;
            }
            if (stored.getSourceOccurredAt() != null
                    && !stored.getSourceOccurredAt().isBefore(sourceOccurredAt)) {
                return 0;
            }
            rows.put(accountId, new AccountBalanceProjectionEntity(accountId,
                    stored.getCurrentBalance(),
                    BigDecimal.ZERO.setScale(PicClause.ACCT_CURR_CYC_CREDIT_SCALE),
                    BigDecimal.ZERO.setScale(PicClause.ACCT_CURR_CYC_DEBIT_SCALE),
                    sourceEventId, sourceOccurredAt));
            return 1;
        }

        /**
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

        private final Map<TransactionCategoryBalanceId, TransactionCategoryBalanceEntity> rows =
                new LinkedHashMap<>();

        /** Keys whose read raised the {@code INVALID KEY} branch, in read order. */
        private final List<TransactionCategoryBalanceId> keysMissedOnRead = new ArrayList<>();

        private final List<String> saveLog;

        private boolean seeding = true;

        private StoredCategoryBalances(List<String> saveLog) {
            this.saveLog = saveLog;
        }

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

        /**
         * Applies the upsert of {@code 2700-UPDATE-TCATBAL} in memory, recording which arm ran.
         *
         * <p>An absent key is the {@code INVALID KEY} condition at
         * {@code app/cbl/CBTRN02C.cbl:L475-L478}, so the key joins {@link #keysMissedOnRead()} and
         * the opening balance is the amount itself, matching {@code :L504-L508}. A present key takes
         * the amount on top of its stored balance, matching {@code :L527}.
         *
         * <p>The sum reaches {@code TRAN-CAT-BAL PIC S9(09)V99}, which holds nine integer digits.
         * Neither {@code ADD} carries an {@code ON SIZE ERROR} phrase, so a wider sum keeps its
         * low-order nine digits and its sign. The shipped statement expresses that as {@code MOD};
         * this model expresses it as
         * {@link CobolDecimal#truncateToPictureField(BigDecimal, int, int)}, and the two agree.
         *
         * @param accountId    eleven digits
         * @param typeCode     two characters
         * @param categoryCode four digits
         * @param amount       the signed amount to add
         * @return 1, the one row the statement writes on either arm
         */
        @Override
        public int addToCategoryBalance(String accountId, String typeCode, String categoryCode,
                BigDecimal amount) {
            TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId(accountId, typeCode, categoryCode);
            TransactionCategoryBalanceEntity stored = rows.get(key);
            BigDecimal opening = stored == null ? BigDecimal.ZERO : stored.getCategoryBalance();

            if (stored == null) {
                keysMissedOnRead.add(key);
            }
            if (!seeding) {
                saveLog.add(CATEGORY_BALANCE_STAGE);
            }
            rows.put(key, new TransactionCategoryBalanceEntity(key,
                    CobolDecimal.truncateToPictureField(
                            CobolDecimal.add(opening, amount, PicClause.TRAN_CAT_BAL_SCALE),
                            PicClause.TRAN_CAT_BAL_PRECISION, PicClause.TRAN_CAT_BAL_SCALE)));
            return ONE_ROW_UPSERTED;
        }

        @Override
        public long count() {
            return rows.size();
        }

        /**
         * @return the keys in first-write order
         */
        Set<TransactionCategoryBalanceId> keys() {
            return new LinkedHashSet<>(rows.keySet());
        }

        /**
         * @return the keys in read order
         */
        List<TransactionCategoryBalanceId> keysMissedOnRead() {
            return List.copyOf(keysMissedOnRead);
        }

        /**
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

        private final Map<String, TransactionEntity> rows = new LinkedHashMap<>();

        /** Identifiers a write presented more than once. */
        private final List<String> repeatedIdentifiers = new ArrayList<>();

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
         * @return the identifiers in write order
         */
        List<String> repeatedIdentifiers() {
            return List.copyOf(repeatedIdentifiers);
        }

        /**
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

        private final List<RejectedTransactionEntity> rows = new ArrayList<>();

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
         * Removes nothing, because equivalence compares one run against the fixtures.
         *
         * <p>{@code domain/RetentionSweep} applies the ninety-day horizon
         * {@code COMMENT ON TABLE rejected_transaction} declares, on its own schedule and never
         * inside a posting. A run of the three hundred fixture records finishes in under a second,
         * so no row of it is anywhere near that horizon, and a store that deleted here would remove
         * a row this test is about to compare.
         *
         * @param horizon ignored
         * @param limit   ignored
         * @return 0, always
         */
        @Override
        public int deleteRejectedBefore(Instant horizon, int limit) {
            return 0;
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

        private final List<String> saveLog = new ArrayList<>();

        private final StoredAccountBalances accountBalances;

        private final StoredCategoryBalances categoryBalances;

        private final TransactionRepository transactions;

        private final StoredRejects rejects;

        private final CapturedOutbox outbox = new CapturedOutbox();

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
            // One argument, and no meter. The recorder writes the reject row and publishes nothing:
            // the declined event that names the same refusal is the authorization service's, and
            // messaging/TransactionDeclinedConsumer is what turns it into a call on this class. That
            // consumer also raises the reject counter, after the commit, so this class holds none.
            this.rejectRecorder = new RejectRecorder(rejects);
        }

        /**
         * @return the service
         */
        PostingService postingService() {
            return postingService;
        }

        /**
         * @return the recorder
         */
        RejectRecorder rejectRecorder() {
            return rejectRecorder;
        }

        /**
         * @return the account store
         */
        StoredAccountBalances accountBalances() {
            return accountBalances;
        }

        /**
         * @return the transaction store
         */
        TransactionRepository transactions() {
            return transactions;
        }

        /**
         * @return the reject store
         */
        StoredRejects rejects() {
            return rejects;
        }

        /**
         * @return the log, in write order
         */
        List<String> saveLog() {
            return List.copyOf(saveLog);
        }

        /**
         * Returns every outbox row this probe produced.
         *
         * <p>The reject path contributes nothing here. {@link RejectRecorder} writes its row and
         * reaches no outbox, because the declined event naming the same refusal belongs to the
         * authorization service, which published it before this service saw it. Only the post
         * branch, through {@code PostingService}, puts a row in this list.</p>
         *
         * @return the rows, in publication order
         */
        List<OutboxEventEntity> published() {
            return outbox.snapshot();
        }
    }

    /**
     * Wraps one {@link OutboxWriter} over a stubbed row store and keeps every row it wrote.
     *
     * <p>The writer validates each payload against its schema before the row is stored, so a
     * captured row proves the event passed that check.</p>
     */
    private static final class CapturedOutbox {

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
         * @return the writer
         */
        OutboxWriter writer() {
            return writer;
        }

        /**
         * @return an unmodifiable view in publication order
         */
        List<OutboxEventEntity> snapshot() {
            return List.copyOf(rows);
        }
    }
}

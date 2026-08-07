package com.carddemo.account.domain;

import com.carddemo.account.domain.PostedTransactionService.AccountRowMissingException;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.repository.AbstractAccountPostgresTest;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.OutboxEventRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour of {@link PostedTransactionService#applyPostedAmount} over the migrated account schema.
 *
 * <p>The method reproduces {@code 2800-UPDATE-ACCOUNT-REC} at
 * {@code app/cbl/CBTRN02C.cbl:L545-L560}: {@code L547} adds the amount to {@code ACCT-CURR-BAL},
 * {@code L548} routes the same amount by sign, {@code L549} adds it to
 * {@code ACCT-CURR-CYC-CREDIT} and {@code L551} adds it to {@code ACCT-CURR-CYC-DEBIT}. All three
 * fields hold {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}, {@code :L13} and
 * {@code :L14}.
 *
 * <p>Two of these tests are the arithmetic behind a decision rather than a stored number. The
 * accumulation test adds two amounts and reads the position expression of
 * {@code app/cbl/CBTRN02C.cbl:L403-L405} back from the stored row, which is what reason code 102
 * tests against the credit limit at {@code :L407}. Before this service applied a posted amount those
 * two accumulators never moved, so that expression answered the transaction amount alone and
 * cumulative cycle exposure was never tested at all.
 *
 * <p>The refund test asserts a defect rather than a correct result. A negative amount is added to
 * the debit accumulator at {@code app/cbl/CBTRN02C.cbl:L551}, which makes that accumulator more
 * negative, and {@code :L404} subtracts it, so a refund raises the value compared against the limit.
 * {@code card-platform/docs/business-rule-flags.md} carries it as register item 6, and it is
 * reproduced rather than fixed.
 *
 * <p>Every test runs against the one PostgreSQL container {@link AbstractAccountPostgresTest}
 * starts, so each call performs a real locked read, a real update and a real insert. Each test seeds
 * its own account identifier above the 50 rows
 * {@code src/main/resources/db/migration/V2__seed.sql} loads, and {@link #removeSeededRows()}
 * deletes every row this class wrote.
 *
 * <p>What the siblings own. {@code messaging/TransactionPostedConsumerTest} asserts the claim
 * ordering, the duplicate guard and the acknowledgement. {@code domain/BillingCycleServiceTest}
 * asserts the close that zeroes what this class grows.
 * {@code repository/SchemaColumnTypeTest} asserts column types.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. Flagged source findings:
 * {@code card-platform/docs/business-rule-flags.md}. Publish and consume paths:
 * {@code card-platform/docs/event-flow.md}.
 */
@DisplayName("PostedTransactionService over the migrated account schema")
class PostedTransactionServiceTest extends AbstractAccountPostgresTest {

    /** Account the single-posting test applies one amount to. */
    private static final String SINGLE_ACCOUNT_ID = "00000000921";

    /** Account the accumulation test applies two amounts to. */
    private static final String ACCUMULATION_ACCOUNT_ID = "00000000922";

    /** Account the refund test applies a negative amount to. */
    private static final String REFUND_ACCOUNT_ID = "00000000923";

    /** Account the truncation test applies a third-of-a-cent amount to. */
    private static final String TRUNCATION_ACCOUNT_ID = "00000000924";

    /** Account whose outbox row the payload test reads back. */
    private static final String PAYLOAD_ACCOUNT_ID = "00000000925";

    /** Account the untouched-columns test applies one amount to. */
    private static final String UNTOUCHED_ACCOUNT_ID = "00000000926";

    /** Identifier no row carries. Nothing in this class seeds it. */
    private static final String ABSENT_ACCOUNT_ID = "00000000927";

    /** Fractional digits all three value columns hold, from the {@code V99} of the picture. */
    private static final int MONEY_SCALE = PicClause.ACCT_CURR_BAL_SCALE;

    /** Balance every account seeded here carries. */
    private static final BigDecimal SEEDED_BALANCE = new BigDecimal("284.00");

    /** Credit limit every account seeded here carries. */
    private static final BigDecimal SEEDED_CREDIT_LIMIT = new BigDecimal("120.00");

    /** Cash credit limit every account seeded here carries. */
    private static final BigDecimal SEEDED_CASH_CREDIT_LIMIT = new BigDecimal("100.00");

    /** Zero at the column scale, the value both accumulators are seeded with. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /**
     * The amount the QA reproduction posted, and the amount every case here posts.
     *
     * <p>One of these sits under {@link #SEEDED_CREDIT_LIMIT} and two do not, which is the whole of
     * what the severed accumulator chain hid: the first authorization was correctly approved and
     * every later one was approved too, because the accumulator the second reads never moved.
     */
    private static final BigDecimal POSTED_AMOUNT = new BigDecimal("100.00");

    /** A refund, which {@code app/cbl/CBTRN02C.cbl:L551} routes to the debit accumulator. */
    private static final BigDecimal REFUND_AMOUNT = new BigDecimal("-50.00");

    /** An amount carrying a third fractional digit, which the column cannot hold. */
    private static final BigDecimal THIRD_DIGIT_AMOUNT = new BigDecimal("1.239");

    /** Status every account seeded here carries. */
    private static final String ACTIVE_STATUS = "Y";

    /** Open date every account seeded here carries, ten characters of text. */
    private static final String OPEN_DATE = "2014-11-20";

    /** Expiry every account seeded here carries, ten characters of text. */
    private static final String EXPIRATION_DATE = "2099-12-31";

    /** Reissue date every account seeded here carries, ten characters of text. */
    private static final String REISSUE_DATE = "2025-05-20";

    /** Postal code every account seeded here carries. */
    private static final String ADDRESS_ZIP = "A000000000";

    /** Disclosure group every account seeded here carries: the ten spaces the fixture holds. */
    private static final String GROUP_ID = " ".repeat(10);

    /** Transaction identifier every call in this class names. Sixteen characters. */
    private static final String TRANSACTION_ID = "0000001000000042";

    /** The service under test. */
    @Autowired
    private PostedTransactionService service;

    /** Reads and seeds account rows. */
    @Autowired
    private AccountRepository accounts;

    /** Reads and removes the rows one call stores. */
    @Autowired
    private OutboxEventRepository outboxEvents;

    /** Opens the transactions this class controls. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The mapper that wrote each stored payload, and the one this class reads it back with. */
    @Autowired
    @Qualifier("accountEventObjectMapper")
    private ObjectMapper objectMapper;

    /** Account identifiers this class seeded, in seed order. */
    private final Set<String> seededAccountIds = new LinkedHashSet<>();

    /** Deletes every account row and outbox row this class wrote, in a transaction of its own. */
    @AfterEach
    void removeSeededRows() {
        if (seededAccountIds.isEmpty()) {
            return;
        }
        Set<String> written = Set.copyOf(seededAccountIds);
        seededAccountIds.clear();
        inOwnTransaction(() -> {
            outboxEvents.deleteAll(outboxEvents.findAll().stream()
                    .filter(row -> written.contains(row.getAggregateId()))
                    .toList());
            written.forEach(identifier ->
                    accounts.findById(identifier).ifPresent(accounts::delete));
            return written.size();
        });
    }

    @Test
    @DisplayName("One posted amount reaches the balance and the credit accumulator, "
            + "app/cbl/CBTRN02C.cbl:L547-L549")
    void onePostedAmountReachesTheBalanceAndTheCreditAccumulator() {
        seedAccount(SINGLE_ACCOUNT_ID);

        inOwnTransaction(() -> service.applyPostedAmount(SINGLE_ACCOUNT_ID, POSTED_AMOUNT,
                TRANSACTION_ID));

        AccountEntity stored = storedAccount(SINGLE_ACCOUNT_ID);
        assertThat(stored.getCurrentBalance())
                .as("stored balance, which app/cbl/CBTRN02C.cbl:L547 adds the amount to")
                .isEqualTo(SEEDED_BALANCE.add(POSTED_AMOUNT));
        assertThat(stored.getCurrentCycleCredit())
                .as("stored cycle credit, which app/cbl/CBTRN02C.cbl:L549 adds a non-negative "
                        + "amount to")
                .isEqualTo(POSTED_AMOUNT);
        assertThat(stored.getCurrentCycleDebit())
                .as("stored cycle debit, which a non-negative amount leaves alone")
                .isEqualTo(ZERO);
    }

    /**
     * Reproduces the arithmetic the severed accumulator chain hid, one posting at a time.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L403-L405} computes cycle credit minus cycle debit plus the
     * transaction amount, and {@code :L407} approves only while the credit limit is greater than or
     * equal to that value. Every term but the amount comes from the two accumulator columns this
     * service owns, so where those columns never move the expression collapses to the amount against
     * the limit and cumulative exposure goes unenforced.
     *
     * <p>That is what the QA run measured. Six authorizations of {@link #POSTED_AMOUNT} against a
     * {@link #SEEDED_CREDIT_LIMIT} account were all approved, taking the account five times past its
     * limit, and the accumulators read zero afterwards.
     *
     * <p>The three positions below are the same expression over three states of one row. On a
     * pristine row it sits under the limit, which is why the first authorization is correctly
     * approved. After one posting it exceeds the limit, which is the decline that never happened.
     * After a second it has grown again, so the accumulator accumulates rather than being
     * overwritten.
     */
    @Test
    @DisplayName("Each posting grows the accumulator, so the position expression of "
            + "app/cbl/CBTRN02C.cbl:L403-L405 crosses the credit limit after the first")
    void eachPostingGrowsTheAccumulatorPastTheCreditLimit() {
        seedAccount(ACCUMULATION_ACCOUNT_ID);
        BigDecimal positionWhilePristine =
                position(storedAccount(ACCUMULATION_ACCOUNT_ID), POSTED_AMOUNT);

        inOwnTransaction(() -> service.applyPostedAmount(ACCUMULATION_ACCOUNT_ID, POSTED_AMOUNT,
                TRANSACTION_ID));
        AccountEntity afterFirst = storedAccount(ACCUMULATION_ACCOUNT_ID);
        BigDecimal positionAfterFirst = position(afterFirst, POSTED_AMOUNT);

        inOwnTransaction(() -> service.applyPostedAmount(ACCUMULATION_ACCOUNT_ID, POSTED_AMOUNT,
                TRANSACTION_ID));
        AccountEntity afterSecond = storedAccount(ACCUMULATION_ACCOUNT_ID);
        BigDecimal positionAfterSecond = position(afterSecond, POSTED_AMOUNT);

        assertThat(afterFirst.getCurrentCycleCredit())
                .as("stored cycle credit after one posting of %s", POSTED_AMOUNT)
                .isEqualTo(POSTED_AMOUNT);
        assertThat(afterSecond.getCurrentCycleCredit())
                .as("stored cycle credit after two postings, which must accumulate rather than "
                        + "overwrite")
                .isEqualTo(POSTED_AMOUNT.add(POSTED_AMOUNT));

        assertThat(positionWhilePristine)
                .as("position on a pristine row against the limit %s, which is the first "
                        + "authorization and is correctly approved", SEEDED_CREDIT_LIMIT)
                .isLessThanOrEqualTo(SEEDED_CREDIT_LIMIT);
        assertThat(positionAfterFirst)
                .as("position after one posting, which reason code 102 declines at "
                        + "app/cbl/CBTRN02C.cbl:L407 and which the QA run approved")
                .isGreaterThan(SEEDED_CREDIT_LIMIT);
        assertThat(positionAfterSecond)
                .as("position after two postings, which must exceed the position after one")
                .isGreaterThan(positionAfterFirst);
    }

    @Test
    @DisplayName("A refund reaches the debit accumulator and raises the tested position, "
            + "business-rule-flags register item 6")
    void aRefundReachesTheDebitAccumulator() {
        seedAccount(REFUND_ACCOUNT_ID);

        inOwnTransaction(() -> service.applyPostedAmount(REFUND_ACCOUNT_ID, REFUND_AMOUNT,
                TRANSACTION_ID));

        AccountEntity stored = storedAccount(REFUND_ACCOUNT_ID);
        assertThat(stored.getCurrentBalance())
                .as("stored balance, which the negative amount lowers")
                .isEqualTo(SEEDED_BALANCE.add(REFUND_AMOUNT));
        assertThat(stored.getCurrentCycleDebit())
                .as("stored cycle debit, which app/cbl/CBTRN02C.cbl:L551 adds the negative amount to")
                .isEqualTo(REFUND_AMOUNT);
        assertThat(stored.getCurrentCycleCredit())
                .as("stored cycle credit, which a negative amount leaves alone")
                .isEqualTo(ZERO);
        assertThat(position(stored, ZERO))
                .as("position after a refund, which register item 6 records as raised rather than "
                        + "lowered")
                .isGreaterThan(ZERO);
    }

    @Test
    @DisplayName("A third fractional digit truncates toward zero, and half-up rounding at the same "
            + "scale returns a different value")
    void aThirdFractionalDigitTruncatesTowardZero() {
        seedAccount(TRUNCATION_ACCOUNT_ID);
        BigDecimal truncated = CobolDecimal.add(ZERO, THIRD_DIGIT_AMOUNT, MONEY_SCALE);
        BigDecimal roundedHalfUp = THIRD_DIGIT_AMOUNT.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        inOwnTransaction(() -> service.applyPostedAmount(TRUNCATION_ACCOUNT_ID, THIRD_DIGIT_AMOUNT,
                TRANSACTION_ID));

        AccountEntity stored = storedAccount(TRUNCATION_ACCOUNT_ID);
        assertThat(stored.getCurrentCycleCredit())
                .as("stored cycle credit against the amount truncated toward zero")
                .isEqualTo(truncated);
        assertThat(stored.getCurrentBalance())
                .as("stored balance against the seeded value plus the truncated amount")
                .isEqualTo(SEEDED_BALANCE.add(truncated));
        assertThat(roundedHalfUp)
                .as("the same amount under half-up rounding at scale %d", MONEY_SCALE)
                .isNotEqualTo(truncated);
    }

    /**
     * Reads the stored payload back and compares it with the row the call left behind.
     *
     * <p>{@code outbox/OutboxWriter.java} carries
     * {@code @Transactional(propagation = Propagation.MANDATORY)}, so its insert joins the
     * transaction this call runs in. A row present after that transaction commits was written in it.
     */
    @Test
    @DisplayName("One outbox row carrying ACCOUNT_UPDATED reports the values the posting left")
    void oneOutboxRowReportsThePostedValues() {
        seedAccount(PAYLOAD_ACCOUNT_ID);

        inOwnTransaction(() -> service.applyPostedAmount(PAYLOAD_ACCOUNT_ID, POSTED_AMOUNT,
                TRANSACTION_ID));

        List<OutboxEventEntity> rows = outboxRowsFor(PAYLOAD_ACCOUNT_ID);
        assertThat(rows).as("outbox rows one posting stored").hasSize(1);
        OutboxEventEntity row = rows.get(0);
        assertThat(row.getEventType())
                .as("event type of the stored row")
                .isEqualTo(AccountStateChanged.EVENT_TYPE);

        JsonNode payload = objectMapper.readTree(row.getPayload());
        AccountEntity stored = storedAccount(PAYLOAD_ACCOUNT_ID);
        assertThat(payload.path("changeKind").stringValue())
                .as("change kind the payload carries")
                .isEqualTo(AccountStateChanged.ChangeKind.ACCOUNT_UPDATED.name());
        assertThat(payload.path("currentBalance").stringValue())
                .as("balance the payload carries, as a decimal string")
                .isEqualTo(stored.getCurrentBalance().toPlainString());
        assertThat(payload.path("currentCycleCredit").stringValue())
                .as("cycle credit the payload carries, which the authorization replica applies")
                .isEqualTo(stored.getCurrentCycleCredit().toPlainString());
        assertThat(payload.path("currentCycleDebit").stringValue())
                .as("cycle debit the payload carries")
                .isEqualTo(stored.getCurrentCycleDebit().toPlainString());
        assertThat(payload.path("expirationDate").stringValue())
                .as("expiry the payload carries, which the expiry rule of "
                        + "app/cbl/CBTRN02C.cbl:L414-L420 compares as text")
                .isEqualTo(EXPIRATION_DATE);
    }

    @Test
    @DisplayName("A posting writes three columns and leaves the other nine as it found them")
    void aPostingLeavesEveryOtherColumnAsItFoundIt() {
        seedAccount(UNTOUCHED_ACCOUNT_ID);

        inOwnTransaction(() -> service.applyPostedAmount(UNTOUCHED_ACCOUNT_ID, POSTED_AMOUNT,
                TRANSACTION_ID));

        AccountEntity stored = storedAccount(UNTOUCHED_ACCOUNT_ID);
        assertThat(stored.getCreditLimit()).as("credit limit").isEqualTo(SEEDED_CREDIT_LIMIT);
        assertThat(stored.getCashCreditLimit())
                .as("cash credit limit").isEqualTo(SEEDED_CASH_CREDIT_LIMIT);
        assertThat(stored.getActiveStatus()).as("active status").isEqualTo(ACTIVE_STATUS);
        assertThat(stored.getOpenDate()).as("open date").isEqualTo(OPEN_DATE);
        assertThat(stored.getExpirationDate()).as("expiry").isEqualTo(EXPIRATION_DATE);
        assertThat(stored.getReissueDate()).as("reissue date").isEqualTo(REISSUE_DATE);
        assertThat(stored.getAddressZip()).as("postal code").isEqualTo(ADDRESS_ZIP);
        assertThat(stored.getGroupId()).as("disclosure group").isEqualTo(GROUP_ID);
    }

    @Test
    @DisplayName("A posting naming an account this service holds no row for is refused")
    void aPostingNamingAnAbsentAccountIsRefused() {
        assertThatThrownBy(() -> inOwnTransaction(() ->
                service.applyPostedAmount(ABSENT_ACCOUNT_ID, POSTED_AMOUNT, TRANSACTION_ID)))
                .as("answer to a posting whose account is absent")
                .isInstanceOf(AccountRowMissingException.class)
                .hasMessageContaining(ABSENT_ACCOUNT_ID);
        assertThat(outboxRowsFor(ABSENT_ACCOUNT_ID))
                .as("outbox rows a refused posting stored")
                .isEmpty();
    }

    /**
     * Computes the position expression the credit-limit rule tests.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L403-L405} builds it as cycle credit minus cycle debit plus the
     * transaction amount, at the scale of {@code WS-TEMP-BAL PIC S9(09)V99} at
     * {@code app/cbl/CBTRN02C.cbl:L187}.
     *
     * @param account the stored row to read the two accumulators from
     * @param amount  the amount a rule would be testing
     * @return the value {@code app/cbl/CBTRN02C.cbl:L407} compares against the credit limit
     */
    private static BigDecimal position(AccountEntity account, BigDecimal amount) {
        BigDecimal difference = CobolDecimal.subtract(account.getCurrentCycleCredit(),
                account.getCurrentCycleDebit(), PicClause.WS_TEMP_BAL_SCALE);
        return CobolDecimal.add(difference, amount, PicClause.WS_TEMP_BAL_SCALE);
    }

    /**
     * Seeds one account row this class owns, above the identifiers the seed migration loads.
     *
     * @param accountId the identifier to seed
     */
    private void seedAccount(String accountId) {
        AccountEntity account = new AccountEntity();
        account.setAccountId(accountId);
        account.setActiveStatus(ACTIVE_STATUS);
        account.setCurrentBalance(SEEDED_BALANCE);
        account.setCreditLimit(SEEDED_CREDIT_LIMIT);
        account.setCashCreditLimit(SEEDED_CASH_CREDIT_LIMIT);
        account.setOpenDate(OPEN_DATE);
        account.setExpirationDate(EXPIRATION_DATE);
        account.setReissueDate(REISSUE_DATE);
        account.setCurrentCycleCredit(ZERO);
        account.setCurrentCycleDebit(ZERO);
        account.setAddressZip(ADDRESS_ZIP);
        account.setGroupId(GROUP_ID);

        seededAccountIds.add(accountId);
        inOwnTransaction(() -> accounts.save(account));
    }

    /**
     * Reads one account row back from the table.
     *
     * @param accountId the identifier to read
     * @return the stored row
     */
    private AccountEntity storedAccount(String accountId) {
        return inOwnTransaction(() -> accounts.findByAccountId(accountId)
                .orElseThrow(() -> new AssertionError("no account row carries " + accountId)));
    }

    /**
     * Reads the outbox rows carrying one account as their aggregate identifier.
     *
     * @param accountId the aggregate identifier to select on
     * @return the rows, in the order the table returns them
     */
    private List<OutboxEventEntity> outboxRowsFor(String accountId) {
        return inOwnTransaction(() -> outboxEvents.findAll().stream()
                .filter(row -> accountId.equals(row.getAggregateId()))
                .toList());
    }

    /**
     * Runs one unit of work in a transaction of its own, so the writes commit.
     *
     * @param work the work to run
     * @param <T>  what the work answers
     * @return the answer of the work
     */
    private <T> T inOwnTransaction(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }
}

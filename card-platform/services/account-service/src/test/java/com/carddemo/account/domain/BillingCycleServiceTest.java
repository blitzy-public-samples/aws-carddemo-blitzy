package com.carddemo.account.domain;

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
import java.util.Optional;
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

/**
 * Behaviour of {@link BillingCycleService#closeBillingCycle(String)} over the migrated account
 * schema.
 *
 * <p>The method reproduces two statements of the paragraph {@code 1050-UPDATE-ACCOUNT.} at
 * {@code app/cbl/CBACT04C.cbl:L350}. {@code app/cbl/CBACT04C.cbl:L353} moves zero to
 * {@code ACCT-CURR-CYC-CREDIT} and {@code app/cbl/CBACT04C.cbl:L354} moves zero to
 * {@code ACCT-CURR-CYC-DEBIT}. Both fields hold {@code PIC S9(10)V99}, at
 * {@code app/cpy/CVACT01Y.cpy:L13} and {@code app/cpy/CVACT01Y.cpy:L14}. The interest addition at
 * {@code app/cbl/CBACT04C.cbl:L352} is not reproduced, and one test below reads the stored balance
 * to show it.
 *
 * <p>The two accumulators feed one authorization check. {@code app/cbl/CBTRN02C.cbl:L403-L405}
 * computes cycle credit minus cycle debit plus the transaction amount into
 * {@code WS-TEMP-BAL} at {@code app/cbl/CBTRN02C.cbl:L187}, and
 * {@code app/cbl/CBTRN02C.cbl:L407} compares that working balance with the credit limit. A failed
 * comparison assigns reject reason 102 at {@code app/cbl/CBTRN02C.cbl:L410-L412}.
 *
 * <p>Every test runs against the one PostgreSQL container {@link AbstractAccountPostgresTest}
 * starts, so each close performs a real update and a real insert. Each test seeds its own account
 * identifier above the 50 rows {@code src/main/resources/db/migration/V2__seed.sql} loads, and
 * {@link #removeSeededRows()} deletes every row this class wrote.
 *
 * <p>What the siblings own. {@code domain/TransactionalObservabilityTest} asserts the cycle-close
 * meters. {@code api/BillingCycleControllerTest} asserts the endpoint and its response shape.
 * {@code repository/SchemaColumnTypeTest} asserts column types.
 * {@code outbox/OutboxRelayTest} asserts the sweep that publishes a stored row.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. Flagged source findings:
 * {@code card-platform/docs/business-rule-flags.md}. Publish and consume paths:
 * {@code card-platform/docs/event-flow.md}.
 */
@DisplayName("BillingCycleService over the migrated account schema")
class BillingCycleServiceTest extends AbstractAccountPostgresTest {

    /** Account the accumulator test closes. */
    private static final String ZEROING_ACCOUNT_ID = "00000000901";

    /** Account the balance test closes. */
    private static final String BALANCE_ACCOUNT_ID = "00000000902";

    /** Account the rounding-mode test closes. */
    private static final String TRUNCATION_ACCOUNT_ID = "00000000903";

    /** Account the repeat test closes twice. */
    private static final String REPEAT_ACCOUNT_ID = "00000000904";

    /** Account whose close the outbox test captures inside the writing transaction. */
    private static final String OUTBOX_ACCOUNT_ID = "00000000905";

    /** Account whose stored payload the wire-form test parses. */
    private static final String PAYLOAD_ACCOUNT_ID = "00000000906";

    /** Identifier no row carries. Nothing in this class seeds it. */
    private static final String ABSENT_ACCOUNT_ID = "00000000907";

    /** Account carrying grown accumulators, which the recovery test closes. */
    private static final String GROWN_ACCOUNT_ID = "00000000908";

    /** Account carrying both accumulators at zero, the position the recovery test compares with. */
    private static final String FRESH_ACCOUNT_ID = "00000000909";

    /**
     * Fractional digits both accumulator columns hold, from the {@code V99} of
     * {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L13} and
     * {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    private static final int CYCLE_SCALE = PicClause.ACCT_CURR_CYC_CREDIT_SCALE;

    /** Zero at {@link #CYCLE_SCALE}, the value a closed cycle leaves in both columns. */
    private static final BigDecimal ZERO_AT_CYCLE_SCALE = new BigDecimal("0.00");

    /** Balance every account seeded here carries, and a value no close changes. */
    private static final BigDecimal SEEDED_BALANCE = new BigDecimal("1940.00");

    /** Credit limit every account seeded here carries, apart from the two recovery accounts. */
    private static final BigDecimal SEEDED_CREDIT_LIMIT = new BigDecimal("20200.00");

    /** Cash credit limit every account seeded here carries. */
    private static final BigDecimal SEEDED_CASH_CREDIT_LIMIT = new BigDecimal("10200.00");

    /** Cycle credit an account carries before its close. */
    private static final BigDecimal SEEDED_CYCLE_CREDIT = new BigDecimal("500.00");

    /** Cycle debit an account carries before its close. */
    private static final BigDecimal SEEDED_CYCLE_DEBIT = new BigDecimal("250.00");

    /** Half a cent. Truncation and half-up rounding return different values for it. */
    private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

    /** Credit limit both accounts of the recovery test carry. */
    private static final BigDecimal RECOVERY_CREDIT_LIMIT = new BigDecimal("5000.00");

    /** Cycle credit the grown account carries, above {@link #RECOVERY_CREDIT_LIMIT}. */
    private static final BigDecimal GROWN_CYCLE_CREDIT = new BigDecimal("6000.00");

    /** Cycle debit the grown account carries. */
    private static final BigDecimal GROWN_CYCLE_DEBIT = new BigDecimal("100.00");

    /**
     * Amount the position expression adds, from {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10}.
     */
    private static final BigDecimal AUTHORIZED_AMOUNT = new BigDecimal("50.00");

    /** Status every account seeded here carries. */
    private static final String ACTIVE_STATUS = "Y";

    /** Open date every account seeded here carries, ten characters of text. */
    private static final String OPEN_DATE = "2014-11-20";

    /** Expiry every account seeded here carries, ten characters of text. */
    private static final String EXPIRATION_DATE = "2025-05-20";

    /** Reissue date every account seeded here carries, ten characters of text. */
    private static final String REISSUE_DATE = "2025-05-20";

    /** Postal code every account seeded here carries. */
    private static final String ADDRESS_ZIP = "A000000000";

    /**
     * Disclosure group every account seeded here carries: ten spaces, the one value columns 113 to
     * 122 of {@code app/data/ASCII/acctdata.txt} hold.
     */
    private static final String GROUP_ID = " ".repeat(10);

    /** Payload property carrying the cycle credit accumulator. */
    private static final String CYCLE_CREDIT_PROPERTY = "currentCycleCredit";

    /** Payload property carrying the cycle debit accumulator. */
    private static final String CYCLE_DEBIT_PROPERTY = "currentCycleDebit";

    /** Payload property carrying which mutation produced the event. */
    private static final String CHANGE_KIND_PROPERTY = "changeKind";

    /** The service under test. */
    @Autowired
    private BillingCycleService service;

    /** Reads and seeds account rows. */
    @Autowired
    private AccountRepository accounts;

    /** Reads and removes the rows one close stores. */
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

    /**
     * Deletes every account row and outbox row this class wrote, in a transaction of its own.
     *
     * <p>The seeded rows and the stored events commit, so the other test classes of the module see
     * the 50 seeded accounts and no leftover event.
     */
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
    @DisplayName("A close leaves both accumulators at zero with two fractional digits, "
            + "app/cbl/CBACT04C.cbl:L353-L354")
    void bothAccumulatorsHoldZeroAtTheColumnScale() {
        seedAccount(ZEROING_ACCOUNT_ID, SEEDED_CREDIT_LIMIT, SEEDED_CYCLE_CREDIT,
                SEEDED_CYCLE_DEBIT);

        Optional<AccountEntity> closed = service.closeBillingCycle(ZEROING_ACCOUNT_ID);

        assertThat(closed).as("answer of a close over a stored row").isPresent();
        AccountEntity stored = storedAccount(ZEROING_ACCOUNT_ID);
        assertThat(stored.getCurrentCycleCredit())
                .as("stored cycle credit, which app/cbl/CBACT04C.cbl:L353 zeroes")
                .isEqualTo(ZERO_AT_CYCLE_SCALE);
        assertThat(stored.getCurrentCycleDebit())
                .as("stored cycle debit, which app/cbl/CBACT04C.cbl:L354 zeroes")
                .isEqualTo(ZERO_AT_CYCLE_SCALE);
        assertThat(stored.getCurrentCycleCredit().scale())
                .as("fractional digits the stored cycle credit carries")
                .isEqualTo(CYCLE_SCALE);
        assertThat(stored.getCurrentCycleDebit().scale())
                .as("fractional digits the stored cycle debit carries")
                .isEqualTo(CYCLE_SCALE);
    }

    /**
     * Reads the balance back from the table after the close commits.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L352} adds accrued interest to {@code ACCT-CURR-BAL}, and the
     * two statements at {@code app/cbl/CBACT04C.cbl:L353-L354} are the only ones this platform
     * reproduces. The seeded balance is non-zero, so an applied addition changes the stored value.
     */
    @Test
    @DisplayName("A close leaves the stored balance at its seeded value, and applies no "
            + "app/cbl/CBACT04C.cbl:L352 addition")
    void theStoredBalanceIsUnchanged() {
        seedAccount(BALANCE_ACCOUNT_ID, SEEDED_CREDIT_LIMIT, SEEDED_CYCLE_CREDIT,
                SEEDED_CYCLE_DEBIT);

        service.closeBillingCycle(BALANCE_ACCOUNT_ID);

        assertThat(storedAccount(BALANCE_ACCOUNT_ID).getCurrentBalance())
                .as("stored balance read back after the close committed")
                .isEqualTo(SEEDED_BALANCE);
    }

    /**
     * Compares the stored accumulator with both rounding modes at {@link #CYCLE_SCALE}.
     *
     * <p>{@link CobolDecimal#truncateToScale(BigDecimal, int)} drops the digits past the second
     * toward zero. The {@code ROUNDED} phrase appears zero times across the 28 programs of
     * {@code app/cbl/}, among them {@code app/cbl/CBACT04C.cbl} and {@code app/cbl/CBTRN02C.cbl}.
     */
    @Test
    @DisplayName("The stored accumulator holds truncated zero, and half-up rounding at the same "
            + "scale returns a different value")
    void theStoredAccumulatorTruncatesTowardZero() {
        seedAccount(TRUNCATION_ACCOUNT_ID, SEEDED_CREDIT_LIMIT, SEEDED_CYCLE_CREDIT,
                SEEDED_CYCLE_DEBIT);
        BigDecimal truncated = CobolDecimal.truncateToScale(HALF_CENT, CYCLE_SCALE);
        BigDecimal roundedHalfUp = HALF_CENT.setScale(CYCLE_SCALE, RoundingMode.HALF_UP);

        service.closeBillingCycle(TRUNCATION_ACCOUNT_ID);

        AccountEntity stored = storedAccount(TRUNCATION_ACCOUNT_ID);
        assertThat(stored.getCurrentCycleCredit())
                .as("stored cycle credit against half a cent truncated toward zero")
                .isEqualTo(truncated);
        assertThat(stored.getCurrentCycleDebit())
                .as("stored cycle debit against half a cent truncated toward zero")
                .isEqualTo(truncated);
        assertThat(roundedHalfUp)
                .as("half a cent under half-up rounding at scale %d", CYCLE_SCALE)
                .isNotEqualTo(truncated);
    }

    @Test
    @DisplayName("A second close leaves both accumulators at zero and the stored balance at its "
            + "seeded value")
    void aSecondCloseLeavesTheRowAtTheSameValues() {
        seedAccount(REPEAT_ACCOUNT_ID, SEEDED_CREDIT_LIMIT, SEEDED_CYCLE_CREDIT,
                SEEDED_CYCLE_DEBIT);
        service.closeBillingCycle(REPEAT_ACCOUNT_ID);

        Optional<AccountEntity> secondClose = service.closeBillingCycle(REPEAT_ACCOUNT_ID);

        assertThat(secondClose).as("answer of a second close over the same row").isPresent();
        AccountEntity stored = storedAccount(REPEAT_ACCOUNT_ID);
        assertThat(stored.getCurrentCycleCredit())
                .as("stored cycle credit after two closes")
                .isEqualTo(ZERO_AT_CYCLE_SCALE);
        assertThat(stored.getCurrentCycleDebit())
                .as("stored cycle debit after two closes")
                .isEqualTo(ZERO_AT_CYCLE_SCALE);
        assertThat(stored.getCurrentBalance())
                .as("stored balance after two closes")
                .isEqualTo(SEEDED_BALANCE);
    }

    /**
     * Captures the outbox rows the writing transaction can see, then re-reads them after it
     * commits.
     *
     * <p>{@code outbox/OutboxWriter.java} carries
     * {@code @Transactional(propagation = Propagation.MANDATORY)}, so its insert joins the
     * transaction the close opened. A row visible inside that transaction and present after it
     * commits was written in it.
     */
    @Test
    @DisplayName("One outbox row carrying BILLING_CYCLE_CLOSED joins the account update in the "
            + "same transaction")
    void oneOutboxRowJoinsTheAccountUpdate() {
        seedAccount(OUTBOX_ACCOUNT_ID, SEEDED_CREDIT_LIMIT, SEEDED_CYCLE_CREDIT,
                SEEDED_CYCLE_DEBIT);

        CapturedClose captured = inOwnTransaction(() -> new CapturedClose(
                service.closeBillingCycle(OUTBOX_ACCOUNT_ID), outboxRowsFor(OUTBOX_ACCOUNT_ID)));

        assertThat(captured.result()).as("answer of the captured close").isPresent();
        assertThat(captured.outboxRows())
                .as("outbox rows the writing transaction could see")
                .hasSize(1);
        OutboxEventEntity row = captured.outboxRows().getFirst();
        assertThat(row.getEventType())
                .as("event type the stored row carries")
                .isEqualTo(AccountStateChanged.EVENT_TYPE);
        assertThat(row.getAggregateId())
                .as("aggregate identifier the stored row carries")
                .isEqualTo(OUTBOX_ACCOUNT_ID);
        assertThat(payloadOf(row).required(CHANGE_KIND_PROPERTY).stringValue())
                .as("change kind the stored payload carries")
                .isEqualTo(AccountStateChanged.ChangeKind.BILLING_CYCLE_CLOSED.name());
        assertThat(row.isPublished())
                .as("publication flag the writing transaction could see")
                .isFalse();
        assertThat(row.getPublishedAt())
                .as("publication time the writing transaction could see")
                .isNull();
        assertThat(outboxRowsFor(OUTBOX_ACCOUNT_ID))
                .as("outbox rows present after the transaction committed")
                .hasSize(1);
    }

    /**
     * Reads both zeroed accumulators out of the stored payload.
     *
     * <p>Each monetary property of {@link AccountStateChanged} travels as quoted text matching
     * {@link AccountStateChanged#MONETARY_PATTERN}, whose two fractional digits come from the
     * {@code V99} of {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L13} and
     * {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    @Test
    @DisplayName("Both zeroed accumulators travel in the stored payload as quoted two-decimal "
            + "text")
    void bothZeroedAccumulatorsTravelAsQuotedText() {
        seedAccount(PAYLOAD_ACCOUNT_ID, SEEDED_CREDIT_LIMIT, SEEDED_CYCLE_CREDIT,
                SEEDED_CYCLE_DEBIT);
        service.closeBillingCycle(PAYLOAD_ACCOUNT_ID);

        JsonNode payload = payloadOf(onlyOutboxRowFor(PAYLOAD_ACCOUNT_ID));

        JsonNode cycleCredit = payload.required(CYCLE_CREDIT_PROPERTY);
        JsonNode cycleDebit = payload.required(CYCLE_DEBIT_PROPERTY);
        assertThat(cycleCredit.isString())
                .as("JavaScript Object Notation (JSON) form of %s", CYCLE_CREDIT_PROPERTY)
                .isTrue();
        assertThat(cycleDebit.isString())
                .as("JSON form of %s", CYCLE_DEBIT_PROPERTY)
                .isTrue();
        assertThat(cycleCredit.stringValue())
                .as("text %s carries", CYCLE_CREDIT_PROPERTY)
                .isEqualTo(ZERO_AT_CYCLE_SCALE.toPlainString())
                .matches(AccountStateChanged.MONETARY_PATTERN);
        assertThat(cycleDebit.stringValue())
                .as("text %s carries", CYCLE_DEBIT_PROPERTY)
                .isEqualTo(ZERO_AT_CYCLE_SCALE.toPlainString())
                .matches(AccountStateChanged.MONETARY_PATTERN);
    }

    @Test
    @DisplayName("An identifier no row carries answers an empty Optional and adds no outbox row")
    void anAbsentIdentifierAnswersEmptyAndAddsNoOutboxRow() {
        long rowsBefore = outboxEvents.count();

        Optional<AccountEntity> closed = service.closeBillingCycle(ABSENT_ACCOUNT_ID);

        assertThat(closed).as("answer for an identifier no row carries").isEmpty();
        assertThat(outboxEvents.count())
                .as("outbox rows after a close that found nothing, %d before it", rowsBefore)
                .isEqualTo(rowsBefore);
    }

    /**
     * Compares the authorization position before and after a close with the position a fresh
     * account holds.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L403-L405} computes the position and
     * {@code app/cbl/CBTRN02C.cbl:L407} approves an amount whose position the credit limit covers.
     * The grown account carries a cycle credit above its credit limit, so its position before the
     * close exceeds that limit and earns reject reason 102 at
     * {@code app/cbl/CBTRN02C.cbl:L410-L412}. Operating guidance for the accumulators sits in
     * {@code card-platform/docs/onboarding.md}.
     */
    @Test
    @DisplayName("The app/cbl/CBTRN02C.cbl:L403-L407 position recovers to the value a fresh "
            + "account holds")
    void theCreditPositionRecoversToAFreshAccount() {
        seedAccount(GROWN_ACCOUNT_ID, RECOVERY_CREDIT_LIMIT, GROWN_CYCLE_CREDIT,
                GROWN_CYCLE_DEBIT);
        seedAccount(FRESH_ACCOUNT_ID, RECOVERY_CREDIT_LIMIT, ZERO_AT_CYCLE_SCALE,
                ZERO_AT_CYCLE_SCALE);
        BigDecimal positionBefore =
                positionOf(storedAccount(GROWN_ACCOUNT_ID), AUTHORIZED_AMOUNT);
        assertThat(positionBefore)
                .as("position of the grown account before its close")
                .isGreaterThan(RECOVERY_CREDIT_LIMIT);

        service.closeBillingCycle(GROWN_ACCOUNT_ID);

        BigDecimal positionAfter = positionOf(storedAccount(GROWN_ACCOUNT_ID), AUTHORIZED_AMOUNT);
        BigDecimal freshPosition = positionOf(storedAccount(FRESH_ACCOUNT_ID), AUTHORIZED_AMOUNT);
        assertThat(positionAfter)
                .as("position of the closed account against the position a fresh account holds")
                .isEqualByComparingTo(freshPosition);
        assertThat(RECOVERY_CREDIT_LIMIT)
                .as("credit limit against the position of the closed account")
                .isGreaterThanOrEqualTo(positionAfter);
    }

    /**
     * Stores one account row and records its identifier for removal.
     *
     * <p>The row carries {@link #SEEDED_BALANCE}, which no close changes. Its identifier sits above
     * the 50 identifiers {@code src/main/resources/db/migration/V2__seed.sql} loads.
     *
     * @param accountId   the eleven-digit identifier, matching
     *                    {@code ck_account_account_id_digits}
     * @param creditLimit the credit limit the row carries
     * @param cycleCredit the cycle credit accumulator the row carries
     * @param cycleDebit  the cycle debit accumulator the row carries
     */
    private void seedAccount(String accountId, BigDecimal creditLimit, BigDecimal cycleCredit,
            BigDecimal cycleDebit) {
        AccountEntity account = new AccountEntity();
        account.setAccountId(accountId);
        account.setActiveStatus(ACTIVE_STATUS);
        account.setCurrentBalance(SEEDED_BALANCE);
        account.setCreditLimit(creditLimit);
        account.setCashCreditLimit(SEEDED_CASH_CREDIT_LIMIT);
        account.setOpenDate(OPEN_DATE);
        account.setExpirationDate(EXPIRATION_DATE);
        account.setReissueDate(REISSUE_DATE);
        account.setCurrentCycleCredit(cycleCredit);
        account.setCurrentCycleDebit(cycleDebit);
        account.setAddressZip(ADDRESS_ZIP);
        account.setGroupId(GROUP_ID);

        seededAccountIds.add(accountId);
        accounts.save(account);
    }

    /**
     * Reads one account row back from the table.
     *
     * @param accountId the identifier to read
     * @return the stored row
     */
    private AccountEntity storedAccount(String accountId) {
        return accounts.findByAccountId(accountId)
                .orElseThrow(() -> new AssertionError("no account row carries " + accountId));
    }

    /**
     * Returns the outbox rows carrying one account as aggregate identifier.
     *
     * @param accountId the aggregate identifier to select on
     * @return the matching rows
     */
    private List<OutboxEventEntity> outboxRowsFor(String accountId) {
        return outboxEvents.findAll().stream()
                .filter(row -> accountId.equals(row.getAggregateId()))
                .toList();
    }

    /**
     * Returns the one outbox row carrying one account as aggregate identifier.
     *
     * @param accountId the aggregate identifier to select on
     * @return the single matching row
     */
    private OutboxEventEntity onlyOutboxRowFor(String accountId) {
        List<OutboxEventEntity> rows = outboxRowsFor(accountId);
        assertThat(rows).as("outbox rows carrying aggregate identifier %s", accountId).hasSize(1);
        return rows.getFirst();
    }

    /**
     * Parses the stored payload of one outbox row.
     *
     * @param row the row whose payload to read
     * @return the payload as a tree
     */
    private JsonNode payloadOf(OutboxEventEntity row) {
        return objectMapper.readTree(row.getPayload());
    }

    /**
     * Computes the working balance of {@code app/cbl/CBTRN02C.cbl:L403-L405} for one account.
     *
     * <p>Both steps run through {@link CobolDecimal} at {@link #CYCLE_SCALE}, which pins truncation
     * toward zero.
     *
     * @param account the account whose accumulators to read
     * @param amount  the transaction amount the expression adds
     * @return cycle credit minus cycle debit plus {@code amount}
     */
    private static BigDecimal positionOf(AccountEntity account, BigDecimal amount) {
        BigDecimal cycleNet = CobolDecimal.subtract(account.getCurrentCycleCredit(),
                account.getCurrentCycleDebit(), CYCLE_SCALE);
        return CobolDecimal.add(cycleNet, amount, CYCLE_SCALE);
    }

    /**
     * Runs one unit of work in a transaction this class opens and commits.
     *
     * <p>The template of {@link BillingCycleService} joins this transaction, and the outbox insert
     * joins it too.
     *
     * @param work the unit of work
     * @param <T>  what the unit of work answers
     * @return the answer of {@code work}
     */
    private <T> T inOwnTransaction(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }

    /**
     * One close and the outbox rows its own transaction could see.
     *
     * @param result     the answer of the close
     * @param outboxRows the rows carrying the closed account as aggregate identifier
     */
    private record CapturedClose(Optional<AccountEntity> result,
            List<OutboxEventEntity> outboxRows) {
    }
}

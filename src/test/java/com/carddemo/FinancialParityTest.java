package com.carddemo;

import com.carddemo.entity.Account;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.InterestCalculationService;
import com.carddemo.util.CardDemoConstants;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money-arithmetic parity gate for the COBOL&rarr;Java CardDemo migration &mdash; proves that the
 * production {@link InterestCalculationService} reproduces the legacy interest computation of COBOL
 * program {@code app/cbl/CBACT04C.cbl} <strong>exactly</strong>.
 *
 * <p>This is one of the two cross-cutting, root-level parity/safety classes mandated by the Agent
 * Action Plan. It is the preservation-critical financial gate of <strong>AAP &sect;0.6.3 and
 * &sect;0.7.1</strong> &mdash; <em>"MUST reproduce financial arithmetic exactly using
 * {@link BigDecimal} with explicit scale and {@link RoundingMode#HALF_UP}, mirroring COBOL
 * fixed-point semantics"</em> &mdash; and is listed explicitly in AAP &sect;0.2.1.3 / &sect;0.3.1 /
 * &sect;0.4.1.5 as {@code FinancialParityTest <- CBACT04C + acctdata.txt}. It lives at the
 * <strong>root</strong> of the test tree ({@code src/test/java/com/carddemo/}), not in a
 * sub-package, alongside the application bootstrap class it boots.</p>
 *
 * <h2>The COBOL authority (CBACT04C) this test pins</h2>
 * <p>The interest program iterates <strong>per transaction-category balance</strong> (NOT a single
 * account balance). For each {@code TCATBAL} row of an account it resolves an interest rate and
 * computes monthly interest, accumulates a per-account total, writes one interest transaction per
 * non-zero-rate category, then flushes the account. The verified, governing paragraphs are:</p>
 * <ul>
 *   <li><strong>{@code 1300-COMPUTE-INTEREST} [CBACT04C L462-470]:</strong>
 *       {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, then
 *       {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT}. Both accumulators are {@code PIC S9(09)V99}
 *       (scale 2).</li>
 *   <li><strong>{@code 1200-GET-INTEREST-RATE} / {@code 1200-A} [L415-460]:</strong> read
 *       {@code DISCGRP} by {@code (account group_id, tran_type_cd, tran_cat_cd)}; on file status
 *       {@code '23'} (not found) retry with group id {@code 'DEFAULT'}; if still absent the rate is
 *       zero.</li>
 *   <li><strong>{@code 1300-B-WRITE-TX} [L473-515]:</strong> when the rate is non-zero, one interest
 *       transaction is written with {@code TRAN-TYPE-CD='01'}, {@code TRAN-CAT-CD='05'} (numeric
 *       category 5), {@code TRAN-SOURCE='System'}, {@code TRAN-DESC = 'Int. for a/c ' || ACCT-ID},
 *       {@code TRAN-AMT = WS-MONTHLY-INT}, {@code TRAN-CARD-NUM = XREF-CARD-NUM}, and both
 *       {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} set to "now".</li>
 *   <li><strong>{@code 1050-UPDATE-ACCOUNT} [L350-356]:</strong>
 *       {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}, then {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} and
 *       {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT}, then {@code REWRITE}.</li>
 *   <li><strong>{@code 1400-COMPUTE-FEES} [L518-520]:</strong> an explicit {@code 'To be
 *       implemented'} stub &mdash; there is NO fee logic to port, so this test expects no fee
 *       effect.</li>
 * </ul>
 *
 * <h2>Governing precision rule (CRITICAL)</h2>
 * <p>COBOL {@code COMPUTE} without {@code ROUNDED} truncates, but <strong>AAP &sect;0.6.3 and
 * &sect;0.7.1 GOVERN</strong> and mandate {@link RoundingMode#HALF_UP} at scale 2. The production
 * service implements exactly
 * {@code balance.multiply(rate).divide(BigDecimal.valueOf(INTEREST_DIVISOR), MONEY_SCALE,
 * RoundingMode.HALF_UP)}. This test therefore asserts the <strong>HALF_UP / scale-2</strong>
 * contract, NOT COBOL truncation, and uses {@link CardDemoConstants#INTEREST_DIVISOR} (1200) and
 * {@link CardDemoConstants#MONEY_SCALE} (2) in its own expected-value helper so the expectations stay
 * in lockstep with the production constants.</p>
 *
 * <h2>Three-layer design (and why a non-zero balance must be injected)</h2>
 * <p>The Flyway seed reproduces the byte-exact ASCII fixtures: every one of the 50 {@code tcatbal}
 * category balances is {@code 0.00} (one row per account, key {@code (acctId, "01", 1)}), while
 * {@code discgrp} carries {@code (A000000000, "01", 1) = 15.00} and {@code (DEFAULT, "01", 1) =
 * 15.00}. Running the service against the raw seed therefore yields {@code 0.00} interest for every
 * account &mdash; a degenerate check that does not exercise the arithmetic. The real gate is
 * <strong>Layer&nbsp;2</strong>, which injects a known non-zero category balance. The layers are:</p>
 * <ol>
 *   <li><strong>Layer&nbsp;1 (pure arithmetic, no DB):</strong> locks in the HALF_UP / scale-2
 *       contract including the tie-break case {@code 0.1250 -> 0.13}.</li>
 *   <li><strong>Layer&nbsp;2 (service parity, injected balance):</strong> the core gate &mdash;
 *       asserts the exact interest amount, the account flush, and the full interest-transaction
 *       contract against a known balance and the seeded rate.</li>
 *   <li><strong>Layer&nbsp;2b (DEFAULT-group fallback):</strong> proves the {@code 1200-A} fallback
 *       to the {@code 'DEFAULT'} disclosure group.</li>
 *   <li><strong>Layer&nbsp;3 (genuine zero-balance seed):</strong> proves parity with COBOL on the
 *       real seeded zero-balance data.</li>
 * </ol>
 *
 * <p>The full Spring context is booted on the in-memory H2 {@code test} profile
 * ({@code application-test.yml}); Flyway applies {@code V1}&ndash;{@code V4} and
 * {@code spring.batch.job.enabled=false} prevents any job from auto-running. Every database-touching
 * test is {@link Transactional} so its mutations roll back, leaving the seeded database pristine for
 * sibling tests (no cross-test contamination). Monetary equality is always asserted with
 * {@code isEqualByComparingTo} (value, not scale).</p>
 *
 * <p>No personally identifiable information (PII) is used: no SSN, no CVV, and no password values
 * appear anywhere in this test (AAP &sect;0.6.8, &sect;0.7.1).</p>
 *
 * @see InterestCalculationService
 * @see <a href="file:app/cbl/CBACT04C.cbl">app/cbl/CBACT04C.cbl (the COBOL authority)</a>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Financial parity: InterestCalculationService vs COBOL CBACT04C")
class FinancialParityTest {

    /**
     * Run/parameter date handed to the service (COBOL {@code PARM-DATE}). Any valid 10-character date
     * works because this test deliberately does NOT assert transaction-id string parity &mdash; the
     * parity anchors are the interest <em>amounts</em> and the resulting balance, not the
     * {@code PARM-DATE + suffix} id scheme (AAP &sect;0.6.5, "DO NOT assert tran-id string parity").
     */
    private static final String RUN_DATE = "2024-01-01";

    /**
     * Transaction type code component of the per-account category key exercised by these tests
     * ({@code TRAN-TYPE-CD}). The seed places exactly one {@code tcatbal} row per account under
     * {@code (acctId, "01", 1)}, and {@code discgrp} carries a non-zero rate for {@code ("..", "01",
     * 1)}, so this is the key that drives a non-zero interest computation.
     */
    private static final String TYPE_CD = "01";

    /**
     * Transaction category code component of the per-account category key ({@code TRAN-CAT-CD}),
     * stored as the {@link Integer} {@code 1} that the composite-key columns require.
     */
    private static final Integer CAT_CD = 1;

    /**
     * A disclosure-group id that is guaranteed to have NO {@code discgrp} row, used by Layer&nbsp;2b
     * to force the COBOL {@code 1200-A} fallback to the {@code 'DEFAULT'} group. The seed only
     * defines the {@code A000000000}, {@code DEFAULT}, and {@code ZEROAPR} groups, so this value can
     * never match a keyed read.
     */
    private static final String UNKNOWN_GROUP_ID = "NOSUCHGRP";

    /**
     * The {@code 'DEFAULT'} disclosure-group id that {@code CBACT04C 1200-A-GET-DEFAULT-INT-RATE}
     * falls back to when the account's own group has no rate row.
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** The production service under test &mdash; the Java port of COBOL {@code CBACT04C}. */
    @Autowired
    private InterestCalculationService interestCalculationService;

    /** Account master access ({@code findById}, {@code save}, {@code findAll}). */
    @Autowired
    private AccountRepository accountRepository;

    /** Per-category balance access ({@code findById}, {@code findByIdAcctId}, {@code saveAndFlush}). */
    @Autowired
    private TransactionCategoryBalanceRepository tcbRepository;

    /** Disclosure-group rate access ({@code findById}); used to resolve the expected rate. */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /** Cross-reference access; used to derive the expected card number on the interest transaction. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Transaction access; used only to confirm the interest-transaction writes. */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * The {@link EntityManager} is injected so a test can {@code flush()} pending writes and
     * {@code clear()} the first-level cache, forcing subsequent reads to reload the
     * <em>database-persisted</em> state rather than the in-memory managed instances. This makes the
     * account-flush and interest-transaction assertions exercise the real persistence round-trip.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Reproduces the production monthly-interest formula <em>exactly</em> so the test's expectations
     * track the production constants. This mirrors
     * {@code InterestCalculationService}'s
     * {@code categoryBalance.multiply(rate).divide(BigDecimal.valueOf(INTEREST_DIVISOR), MONEY_SCALE,
     * RoundingMode.HALF_UP)} (CBACT04C {@code 1300-COMPUTE-INTEREST}, L462-465), honouring AAP
     * &sect;0.6.3 / &sect;0.7.1.
     *
     * @param balance the per-category balance ({@code TRAN-CAT-BAL})
     * @param rate    the resolved disclosure-group rate ({@code DIS-INT-RATE})
     * @return the monthly interest at scale {@link CardDemoConstants#MONEY_SCALE}, rounded HALF_UP
     */
    private static BigDecimal expectedMonthlyInterest(BigDecimal balance, BigDecimal rate) {
        return balance.multiply(rate)
                .divide(BigDecimal.valueOf(CardDemoConstants.INTEREST_DIVISOR),
                        CardDemoConstants.MONEY_SCALE,
                        RoundingMode.HALF_UP);
    }

    /**
     * Resolves the interest rate exactly the way {@link InterestCalculationService} does &mdash; a
     * keyed read by the account's own group followed by the {@code 'DEFAULT'} fallback &mdash; so the
     * test's expectation is computed independently of, but identically to, the production resolution
     * (CBACT04C {@code 1200-GET-INTEREST-RATE} + {@code 1200-A}, L415-460).
     *
     * @param groupId the account's disclosure/pricing group id
     * @return the resolved rate, or {@link BigDecimal#ZERO} when neither the keyed nor the default
     *         group has a row
     */
    private BigDecimal resolveExpectedRate(String groupId) {
        return disclosureGroupRepository
                .findById(new DisclosureGroup.DisclosureGroupId(groupId, TYPE_CD, CAT_CD))
                .or(() -> disclosureGroupRepository
                        .findById(new DisclosureGroup.DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD)))
                .map(DisclosureGroup::getDisIntRate)
                .orElse(BigDecimal.ZERO);
    }

    // -------------------------------------------------------------------------------------------
    // Layer 1 — pure-arithmetic precision asserts (no database). Locks in HALF_UP / scale 2.
    // -------------------------------------------------------------------------------------------

    /**
     * Canonical, non-rounding cases of {@code (balance * rate) / 1200}: exact two-decimal results that
     * any correct implementation must produce. {@code 1000.00 x 12.00 / 1200 = 10.00} and the seeded
     * {@code (01,1)} rate case {@code 1000.00 x 15.00 / 1200 = 12.50}.
     */
    @Test
    @DisplayName("Layer 1: canonical interest amounts reproduce COBOL fixed-point exactly")
    void interestFormula_reproducesCobolFixedPoint_canonical() {
        assertThat(expectedMonthlyInterest(new BigDecimal("1000.00"), new BigDecimal("12.00")))
                .as("1000.00 * 12.00 / 1200")
                .isEqualByComparingTo("10.00");

        // 15.00 is the rate the seed assigns to (group, '01', 1) for both A000000000 and DEFAULT.
        assertThat(expectedMonthlyInterest(new BigDecimal("1000.00"), new BigDecimal("15.00")))
                .as("1000.00 * 15.00 / 1200 (the seeded (01,1) rate)")
                .isEqualByComparingTo("12.50");
    }

    /**
     * The rounding contract: an exact {@code 0.1250} tie MUST round <strong>up</strong> to
     * {@code 0.13} under HALF_UP (HALF_EVEN and truncation would both give {@code 0.12}), proving the
     * governing AAP rule rather than COBOL truncation. A second, non-tie case
     * ({@code 100.00 x 1.00 / 1200 = 0.08333...}) confirms ordinary down-rounding to {@code 0.08}.
     * Both results MUST carry a fixed scale of exactly {@link CardDemoConstants#MONEY_SCALE} (2).
     */
    @Test
    @DisplayName("Layer 1: rounding is HALF_UP (0.1250 -> 0.13), scale is fixed at 2")
    void interestRounding_isHalfUp_notHalfEvenOrTruncation() {
        // 12.50 * 12.00 / 1200 = 150.0000 / 1200 = 0.1250 exactly -> HALF_UP -> 0.13.
        BigDecimal tieResult = expectedMonthlyInterest(new BigDecimal("12.50"), new BigDecimal("12.00"));
        assertThat(tieResult)
                .as("0.1250 must round UP to 0.13 under HALF_UP (HALF_EVEN/truncation give 0.12)")
                .isEqualByComparingTo("0.13");
        assertThat(tieResult.scale())
                .as("monetary scale must be fixed at MONEY_SCALE (2)")
                .isEqualTo(CardDemoConstants.MONEY_SCALE);

        // 100.00 * 1.00 / 1200 = 0.083333... -> HALF_UP at scale 2 -> 0.08 (ordinary down-rounding).
        BigDecimal nonTieResult = expectedMonthlyInterest(new BigDecimal("100.00"), new BigDecimal("1.00"));
        assertThat(nonTieResult)
                .as("0.08333... rounds to 0.08 at scale 2")
                .isEqualByComparingTo("0.08");
        assertThat(nonTieResult.scale())
                .as("monetary scale must be fixed at MONEY_SCALE (2)")
                .isEqualTo(CardDemoConstants.MONEY_SCALE);
    }

    // -------------------------------------------------------------------------------------------
    // Layer 2 — service parity with an INJECTED non-zero balance (the core arithmetic gate).
    // -------------------------------------------------------------------------------------------

    /**
     * The core money-arithmetic gate. Injects a known {@code 1000.00} category balance on a seeded
     * account whose group ({@code A000000000}) carries the seeded {@code (01,1)} rate of
     * {@code 15.00}, runs the service, and asserts the COBOL {@code CBACT04C} contract end-to-end:
     * the returned total ({@code 12.50}), the account flush ({@code curr_bal += total};
     * {@code curr_cyc_credit = curr_cyc_debit = 0.00}, {@code 1050-UPDATE-ACCOUNT}), and the single
     * interest transaction ({@code 1300-B-WRITE-TX}) with its exact type/category/source/description/
     * amount/card-number and both timestamps populated.
     *
     * <p>The method is {@link Transactional}; the service's own {@code @Transactional} joins this
     * transaction (so its writes are visible to the assertions) and everything rolls back at the end.
     * {@code flush()/clear()} is used before reloading so the assertions read the
     * database-persisted state rather than the first-level cache.</p>
     */
    @Test
    @Transactional
    @DisplayName("Layer 2: service reproduces interest for a known balance and the seeded rate")
    void serviceReproducesInterest_forKnownBalanceAndRate() {
        final Long acctId = 1L; // ids 1..50 exist in the seed; account 1 is group A000000000.

        // Load the account and capture its pre-run state. originalBal is an immutable BigDecimal
        // snapshot, so it stays correct even though the service mutates the same managed instance.
        Account account = accountRepository.findById(acctId).orElseThrow();
        final BigDecimal originalBal = account.getCurrBal();
        final String groupId = account.getGroupId();

        // Inject a KNOWN non-zero balance on the account's single (acctId, '01', 1) category row so
        // the arithmetic is actually exercised (the raw seed balance is 0.00).
        TransactionCategoryBalance.TransactionCategoryBalanceId tcbId =
                new TransactionCategoryBalance.TransactionCategoryBalanceId(acctId, TYPE_CD, CAT_CD);
        TransactionCategoryBalance tcb = tcbRepository.findById(tcbId).orElseThrow();
        tcb.setTranCatBal(new BigDecimal("1000.00"));
        tcbRepository.saveAndFlush(tcb);

        // Resolve the rate the same way the service does and GUARD it is non-zero, so the test fails
        // loudly (rather than silently passing on 0.00) if the seeded rate ever changes.
        BigDecimal rate = resolveExpectedRate(groupId);
        assertThat(rate).as("seeded interest rate for (group, '01', 1) must be > 0")
                .isGreaterThan(BigDecimal.ZERO);

        // Expected interest for this single category row (seeded rate 15.00 -> 12.50).
        BigDecimal expected = expectedMonthlyInterest(new BigDecimal("1000.00"), rate);

        // Invoke the production service (the Java port of CBACT04C's per-account slice).
        BigDecimal total = interestCalculationService.calculateInterestForAccount(acctId, RUN_DATE);

        // --- Parity assertion 1: the returned total interest matches the expected amount exactly. ---
        assertThat(total)
                .as("returned total interest must equal (balance * rate / 1200) HALF_UP scale 2")
                .isEqualByComparingTo(expected);

        // Force the pending writes to the database and drop the first-level cache so the reloads below
        // assert the persisted state (the REWRITE in 1050-UPDATE-ACCOUNT and the WRITE in 1300-B).
        entityManager.flush();
        entityManager.clear();

        // --- Parity assertion 2: the account flush (1050-UPDATE-ACCOUNT). ---
        Account reloaded = accountRepository.findById(acctId).orElseThrow();
        assertThat(reloaded.getCurrBal())
                .as("ADD WS-TOTAL-INT TO ACCT-CURR-BAL: curr_bal must increase by the interest total")
                .isEqualByComparingTo(originalBal.add(expected));
        assertThat(reloaded.getCurrCycCredit())
                .as("MOVE 0 TO ACCT-CURR-CYC-CREDIT")
                .isEqualByComparingTo("0.00");
        assertThat(reloaded.getCurrCycDebit())
                .as("MOVE 0 TO ACCT-CURR-CYC-DEBIT")
                .isEqualByComparingTo("0.00");

        // --- Parity assertion 3: the interest transaction (1300-B-WRITE-TX). ---
        // Derive the expected card number the same way the service does (1110-GET-XREF-DATA).
        var xrefPage = cardXrefRepository.findByXrefAcctId(acctId, PageRequest.of(0, 1));
        assertThat(xrefPage.hasContent()).as("seed guarantees a card cross-reference for the account").isTrue();
        String expectedCardNum = xrefPage.getContent().get(0).getXrefCardNum();

        var txContent = transactionRepository
                .findByAcctIdOrderByOrigTs(acctId, PageRequest.of(0, 10))
                .getContent();
        Transaction interestTx = txContent.stream()
                .filter(t -> CardDemoConstants.INTEREST_TRAN_SOURCE.equals(t.getSource()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected exactly one interest transaction to be written"));

        assertThat(interestTx.getTypeCd())
                .as("MOVE '01' TO TRAN-TYPE-CD").isEqualTo(CardDemoConstants.INTEREST_TRAN_TYPE_CD);
        assertThat(interestTx.getCatCd())
                .as("MOVE '05' TO TRAN-CAT-CD (numeric category 5)")
                .isEqualTo(Integer.parseInt(CardDemoConstants.INTEREST_TRAN_CAT_CD));
        assertThat(interestTx.getSource())
                .as("MOVE 'System' TO TRAN-SOURCE").isEqualTo(CardDemoConstants.INTEREST_TRAN_SOURCE);
        assertThat(interestTx.getDescription())
                .as("STRING 'Int. for a/c ' , ACCT-ID INTO TRAN-DESC")
                .isEqualTo(CardDemoConstants.INTEREST_TRAN_DESC_PREFIX + acctId);
        assertThat(interestTx.getAmt())
                .as("MOVE WS-MONTHLY-INT TO TRAN-AMT").isEqualByComparingTo(expected);
        assertThat(interestTx.getCardNum())
                .as("MOVE XREF-CARD-NUM TO TRAN-CARD-NUM").isEqualTo(expectedCardNum);
        assertThat(interestTx.getOrigTs())
                .as("MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS").isNotNull();
        assertThat(interestTx.getProcTs())
                .as("MOVE DB2-FORMAT-TS TO TRAN-PROC-TS").isNotNull();
    }

    // -------------------------------------------------------------------------------------------
    // Layer 2b — DEFAULT-group rate fallback parity (CBACT04C 1200-A-GET-DEFAULT-INT-RATE).
    // -------------------------------------------------------------------------------------------

    /**
     * Proves the COBOL {@code 1200-A} fallback: when the account's own disclosure group has no
     * {@code discgrp} row (VSAM file status {@code '23'}), the rate is resolved from the
     * {@code 'DEFAULT'} group. The account's group is reassigned to a value that has no rate row
     * ({@link #UNKNOWN_GROUP_ID}), a known {@code 1000.00} balance is injected, and the result must
     * equal {@code 1000.00 * defaultRate / 1200} &mdash; identical to Layer&nbsp;2's amount because
     * the seeded {@code DEFAULT (01,1)} rate is also {@code 15.00}, but reached via the fallback path.
     */
    @Test
    @Transactional
    @DisplayName("Layer 2b: service falls back to the DEFAULT disclosure group when the account group has no row")
    void serviceUsesDefaultGroupRate_whenAccountGroupHasNoDiscgrpRow() {
        final Long acctId = 2L; // a different seeded account; rolled back like every DB test.

        Account account = accountRepository.findById(acctId).orElseThrow();
        final BigDecimal originalBal = account.getCurrBal();

        // Reassign the account to a group that has NO discgrp row, forcing the DEFAULT fallback.
        account.setGroupId(UNKNOWN_GROUP_ID);
        accountRepository.saveAndFlush(account);

        // Guard: the keyed read for the unknown group must indeed miss (status '23' equivalent).
        assertThat(disclosureGroupRepository
                .findById(new DisclosureGroup.DisclosureGroupId(UNKNOWN_GROUP_ID, TYPE_CD, CAT_CD)))
                .as("the unknown group must have no discgrp row, so the DEFAULT fallback is exercised")
                .isEmpty();

        // Resolve the DEFAULT rate and guard it is non-zero so the arithmetic is genuinely exercised.
        BigDecimal defaultRate = disclosureGroupRepository
                .findById(new DisclosureGroup.DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD))
                .map(DisclosureGroup::getDisIntRate)
                .orElse(BigDecimal.ZERO);
        assertThat(defaultRate).as("seeded DEFAULT (01,1) rate must be > 0")
                .isGreaterThan(BigDecimal.ZERO);

        // Inject the known balance on the account's single (acctId, '01', 1) category row.
        TransactionCategoryBalance.TransactionCategoryBalanceId tcbId =
                new TransactionCategoryBalance.TransactionCategoryBalanceId(acctId, TYPE_CD, CAT_CD);
        TransactionCategoryBalance tcb = tcbRepository.findById(tcbId).orElseThrow();
        tcb.setTranCatBal(new BigDecimal("1000.00"));
        tcbRepository.saveAndFlush(tcb);

        BigDecimal expected = expectedMonthlyInterest(new BigDecimal("1000.00"), defaultRate);

        BigDecimal total = interestCalculationService.calculateInterestForAccount(acctId, RUN_DATE);

        // The interest total must equal the amount computed from the DEFAULT rate, proving the service
        // fell back exactly as CBACT04C 1200-A-GET-DEFAULT-INT-RATE does.
        assertThat(total)
                .as("interest must be computed from the DEFAULT-group rate via the 1200-A fallback")
                .isEqualByComparingTo(expected);

        // The account flush still applies on the fallback path.
        entityManager.flush();
        entityManager.clear();
        Account reloaded = accountRepository.findById(acctId).orElseThrow();
        assertThat(reloaded.getCurrBal())
                .as("curr_bal must increase by the DEFAULT-rate interest total")
                .isEqualByComparingTo(originalBal.add(expected));
    }

    // -------------------------------------------------------------------------------------------
    // Layer 3 — parity against the ACTUAL all-zero seed (matches COBOL on genuine zero input).
    // -------------------------------------------------------------------------------------------

    /**
     * Parity on the genuine seeded data: an untouched seeded account has a {@code 0.00} category
     * balance, so {@code (0.00 * rate) / 1200 = 0.00} and the service returns {@code 0.00} with the
     * account balance unchanged. This is the literal {@code acctdata.txt} parity anchor of AAP
     * &sect;0.4.1.5 &mdash; it proves the Java service reproduces {@code CBACT04C} exactly on the real
     * zero-balance seed (even though the seeded rate is non-zero, a zero balance yields zero
     * interest).
     */
    @Test
    @Transactional
    @DisplayName("Layer 3: service returns 0.00 for an untouched seeded (zero-balance) account")
    void serviceReturnsZero_forUntouchedSeededAccount_matchingCobolOnZeroBalances() {
        final Long acctId = 3L; // untouched: its seeded (acctId, '01', 1) balance stays 0.00.

        Account account = accountRepository.findById(acctId).orElseThrow();
        final BigDecimal originalBal = account.getCurrBal();

        BigDecimal total = interestCalculationService.calculateInterestForAccount(acctId, RUN_DATE);

        assertThat(total)
                .as("zero category balance must yield zero interest, matching COBOL on zero input")
                .isEqualByComparingTo("0.00");

        entityManager.flush();
        entityManager.clear();
        Account reloaded = accountRepository.findById(acctId).orElseThrow();
        assertThat(reloaded.getCurrBal())
                .as("curr_bal must be unchanged when no interest accrues")
                .isEqualByComparingTo(originalBal);
    }

    /**
     * Exercises the all-accounts iteration path ({@link InterestCalculationService#calculateInterest})
     * against the genuine all-zero seed: it must complete without error and produce no net change to
     * any account balance (every seeded category balance is {@code 0.00}, so every per-account total
     * is {@code 0.00}). The sum of all account balances before and after the run must therefore be
     * identical &mdash; a robust, seed-value-independent check of the full-run flush boundary.
     */
    @Test
    @Transactional
    @DisplayName("Layer 3: full run over all seeded accounts produces no interest and no balance change")
    void fullRun_overAllSeededAccounts_producesNoInterest() {
        BigDecimal totalBefore = sumAllAccountBalances();

        // Run the full interest calculation across every distinct tcatbal account id.
        interestCalculationService.calculateInterest(RUN_DATE);

        // Reload the persisted state and confirm the aggregate balance is unchanged (all deltas 0.00).
        entityManager.flush();
        entityManager.clear();
        BigDecimal totalAfter = sumAllAccountBalances();

        assertThat(totalAfter)
                .as("the sum of all account balances must be unchanged (every seeded category balance is 0.00)")
                .isEqualByComparingTo(totalBefore);
    }

    /**
     * Aggregates the current balance across every account, treating a {@code null} balance as zero.
     * Captured into an immutable {@link BigDecimal} so the value is a stable snapshot independent of
     * any later entity mutation.
     *
     * @return the sum of all {@code curr_bal} values at scale {@link CardDemoConstants#MONEY_SCALE}
     */
    private BigDecimal sumAllAccountBalances() {
        BigDecimal sum = BigDecimal.ZERO.setScale(CardDemoConstants.MONEY_SCALE);
        for (Account account : accountRepository.findAll()) {
            BigDecimal bal = account.getCurrBal();
            if (bal != null) {
                sum = sum.add(bal);
            }
        }
        return sum;
    }
}

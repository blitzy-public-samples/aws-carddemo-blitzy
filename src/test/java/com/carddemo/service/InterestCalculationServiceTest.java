package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.CardDemoConstants;

/**
 * Pure-Mockito unit test for {@link InterestCalculationService} &mdash; the Java port of the COBOL
 * interest-calculation batch program {@code app/cbl/CBACT04C.cbl}. This is one of the most
 * precision-critical parity targets of the migration (AAP &sect;0.6.3, &sect;0.7.1, &sect;0.7.3 #4),
 * so the tests below pin every behaviour that {@code CBACT04C} exhibits, deterministically and
 * without a Spring context or a database:
 *
 * <ul>
 *   <li><strong>Per-category fixed-point math.</strong> Monthly interest is
 *       {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} computed with {@link BigDecimal} at
 *       {@link CardDemoConstants#MONEY_SCALE scale 2} using {@link RoundingMode#HALF_UP}
 *       (CBACT04C L462-468). Both a clean case and a rounding case are asserted, the latter against
 *       the <em>exact</em> production expression to lock the arithmetic contract.</li>
 *   <li><strong>DISCGRP {@code 'DEFAULT'}-group fallback.</strong> The keyed disclosure-rate read is
 *       attempted first and, on a miss (legacy VSAM file status 23 &rarr; empty {@link Optional}),
 *       the service retries against the {@code 'DEFAULT'} group (CBACT04C
 *       {@code 1200-A-GET-DEFAULT-INT-RATE}; AAP &sect;0.7.3 #4).</li>
 *   <li><strong>Interest-transaction coding.</strong> One transaction per non-zero category, coded
 *       type {@code "01"}, category {@code 5}, source {@code "System"},
 *       description {@code "Int. for a/c " + acctId}, merchant id {@code 0}, both timestamps set
 *       (CBACT04C {@code 1300-B-WRITE-TX}, L473-500).</li>
 *   <li><strong>Account flush.</strong> The accumulated interest is added to the current balance and
 *       both cycle totals are zeroed before the account is rewritten (CBACT04C
 *       {@code 1050-UPDATE-ACCOUNT}, L350-356).</li>
 *   <li><strong>Zero-rate skip.</strong> A zero (or absent) rate posts <em>no</em> interest
 *       transaction (CBACT04C L214 {@code IF DIS-INT-RATE NOT = 0}); the account is still flushed.</li>
 *   <li><strong>Full-run orchestration.</strong> {@link InterestCalculationService#calculateInterest(String)}
 *       iterates the <em>distinct, ascending</em> account ids found in the category-balance table.</li>
 * </ul>
 *
 * <p>The interest transaction id is minted internally from the run date plus an {@code AtomicLong}
 * suffix, so {@code TranIdGenerator} is deliberately <strong>not</strong> a collaborator and is never
 * mocked. The COBOL fee paragraph {@code 1400-COMPUTE-FEES} is an explicit stub, so there is no fee
 * behaviour to assert.</p>
 *
 * <p>{@link MockitoExtension} runs with its default {@code STRICT_STUBS} strictness; every stub
 * declared in a test is exercised by that test, keeping the suite free of unnecessary-stubbing
 * violations.</p>
 *
 * @see InterestCalculationService
 * @see <a href="file:app/cbl/CBACT04C.cbl">app/cbl/CBACT04C.cbl (authoritative algorithm)</a>
 */
@ExtendWith(MockitoExtension.class)
class InterestCalculationServiceTest {

    /** Account identifier used across the single-account scenarios. */
    private static final Long ACCT_ID = 10L;

    /** Disclosure/pricing group whose keyed DISCGRP rows are present (no fallback needed). */
    private static final String GROUP_ID = "A0000001";

    /** Disclosure/pricing group with NO keyed DISCGRP row, forcing the {@code 'DEFAULT'} fallback. */
    private static final String MISSING_GROUP = "NOSUCHGRP";

    /** The {@code 'DEFAULT'} disclosure group id used by the service's fallback path. */
    private static final String DEFAULT_GROUP = "DEFAULT";

    /** Run/parameter date (COBOL {@code PARM-DATE}); becomes the interest transaction-id prefix. */
    private static final String RUN_DATE = "2023-12-31";

    /** Cross-reference card number stamped onto each interest transaction. */
    private static final String CARD_NUM = "1234567890123456";

    /** Interest transaction type code component of the composite key (COBOL {@code TRAN-TYPE-CD}). */
    private static final String TYPE_CD = CardDemoConstants.INTEREST_TRAN_TYPE_CD; // "01"

    /** Interest transaction category code as the numeric entity value (COBOL {@code '05'} &rarr; 5). */
    private static final Integer CAT_CD = Integer.parseInt(CardDemoConstants.INTEREST_TRAN_CAT_CD); // 5

    @Mock
    private TransactionCategoryBalanceRepository tcbRepository;

    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private InterestCalculationService interestCalculationService;

    // =====================================================================================
    // Fixture builders (objects only — no stubbing here, so STRICT_STUBS stays satisfied).
    // =====================================================================================

    /**
     * Builds an {@link Account} fixture with the supplied monetary state.
     *
     * @param id      the account id (primary key)
     * @param groupId the disclosure/pricing group id used to resolve interest rates
     * @param currBal the current balance (decimal string, scale 2)
     * @param cycCred the current-cycle credit total (decimal string, scale 2)
     * @param cycDeb  the current-cycle debit total (decimal string, scale 2)
     * @return a populated, transient {@code Account}
     */
    private Account buildAccount(Long id, String groupId, String currBal, String cycCred, String cycDeb) {
        Account account = new Account();
        account.setAcctId(id);
        account.setGroupId(groupId);
        account.setActiveStatus("Y");
        account.setCreditLimit(new BigDecimal("99999.99"));
        account.setCurrBal(new BigDecimal(currBal));
        account.setCurrCycCredit(new BigDecimal(cycCred));
        account.setCurrCycDebit(new BigDecimal(cycDeb));
        return account;
    }

    /**
     * Builds a {@link TransactionCategoryBalance} row for an account/type/category with a balance.
     *
     * @param acctId the owning account id
     * @param typeCd the transaction type code (2 chars)
     * @param catCd  the transaction category code
     * @param bal    the per-category running balance (decimal string)
     * @return a populated category-balance row
     */
    private TransactionCategoryBalance tcb(Long acctId, String typeCd, Integer catCd, String bal) {
        return new TransactionCategoryBalance(acctId, typeCd, catCd, new BigDecimal(bal));
    }

    /**
     * Builds the embedded composite key used to address a {@link DisclosureGroup} row.
     *
     * @param groupId the disclosure group id
     * @param typeCd  the transaction type code
     * @param catCd   the transaction category code
     * @return the composite key (value-equal to the key the service constructs)
     */
    private DisclosureGroup.DisclosureGroupId dgId(String groupId, String typeCd, Integer catCd) {
        return new DisclosureGroup.DisclosureGroupId(groupId, typeCd, catCd);
    }

    /**
     * Builds a {@link DisclosureGroup} row carrying the given annual-percentage interest rate.
     *
     * @param groupId the disclosure group id
     * @param typeCd  the transaction type code
     * @param catCd   the transaction category code
     * @param rate    the disclosure interest rate (decimal string, e.g. {@code "12.00"})
     * @return a populated disclosure-group row
     */
    private DisclosureGroup discGroup(String groupId, String typeCd, Integer catCd, String rate) {
        return new DisclosureGroup(dgId(groupId, typeCd, catCd), new BigDecimal(rate));
    }

    /**
     * Builds a single-element {@link Page} of {@link CardXref} carrying a known card number, mirroring
     * the {@code 1110-GET-XREF-DATA} lookup the service performs before posting interest.
     *
     * @param cardNum the cross-reference card number to surface
     * @param acctId  the owning account id
     * @return a non-empty page of cross-reference rows
     */
    private Page<CardXref> xrefPage(String cardNum, Long acctId) {
        return new PageImpl<>(List.of(new CardXref(cardNum, 100000001L, acctId)));
    }

    // =====================================================================================
    // Phase 2 — per-category interest math: exact HALF_UP / 1200 at scale 2 (the core parity).
    // =====================================================================================

    @Test
    @DisplayName("Clean case: 1000.00 @ 12.00% annual -> 10.00 monthly interest (scale 2)")
    void calculateInterestForAccount_cleanCase_returnsTenDollarsAtScaleTwo() {
        Account account = buildAccount(ACCT_ID, GROUP_ID, "500.00", "300.00", "120.00");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(xrefPage(CARD_NUM, ACCT_ID));
        when(tcbRepository.findByIdAcctId(ACCT_ID))
                .thenReturn(List.of(tcb(ACCT_ID, TYPE_CD, CAT_CD, "1000.00")));
        when(disclosureGroupRepository.findById(dgId(GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(discGroup(GROUP_ID, TYPE_CD, CAT_CD, "12.00")));

        BigDecimal result = interestCalculationService.calculateInterestForAccount(ACCT_ID, RUN_DATE);

        // 1000.00 * 12.00 / 1200 = 10.00, HALF_UP at scale 2.
        assertThat(result).isEqualByComparingTo("10.00");
        assertThat(result.scale()).isEqualTo(CardDemoConstants.MONEY_SCALE);
        // Exactly one non-zero category -> exactly one interest transaction written.
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Rounding case: 1234.56 @ 18.99% -> 19.54 (HALF_UP, scale 2), locked to production expression")
    void calculateInterestForAccount_roundingCase_appliesHalfUpAtScaleTwo() {
        BigDecimal catBal = new BigDecimal("1234.56");
        BigDecimal rate = new BigDecimal("18.99");

        // Compute the reference with the EXACT production expression so the contract is pinned here:
        //   (balance * rate) / 1200, scale 2, HALF_UP  ->  19.5369... -> 19.54
        BigDecimal expected = catBal.multiply(rate)
                .divide(BigDecimal.valueOf(CardDemoConstants.INTEREST_DIVISOR),
                        CardDemoConstants.MONEY_SCALE, RoundingMode.HALF_UP);
        assertThat(expected).isEqualByComparingTo("19.54"); // sanity check on the reference itself

        Account account = buildAccount(ACCT_ID, GROUP_ID, "500.00", "0.00", "0.00");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(xrefPage(CARD_NUM, ACCT_ID));
        when(tcbRepository.findByIdAcctId(ACCT_ID))
                .thenReturn(List.of(tcb(ACCT_ID, TYPE_CD, CAT_CD, catBal.toPlainString())));
        when(disclosureGroupRepository.findById(dgId(GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(discGroup(GROUP_ID, TYPE_CD, CAT_CD, rate.toPlainString())));

        BigDecimal result = interestCalculationService.calculateInterestForAccount(ACCT_ID, RUN_DATE);

        assertThat(result).isEqualByComparingTo(expected);
        assertThat(result).isEqualByComparingTo("19.54");
        assertThat(result.scale()).isEqualTo(CardDemoConstants.MONEY_SCALE);
    }

    @Test
    @DisplayName("Multiple categories: total interest is the sum of each per-category result")
    void calculateInterestForAccount_multipleCategories_sumsPerCategoryInterest() {
        Account account = buildAccount(ACCT_ID, GROUP_ID, "500.00", "0.00", "0.00");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(xrefPage(CARD_NUM, ACCT_ID));
        // Two categories: ("01",5) bal 1000.00 @ 12% = 10.00 ; ("02",5) bal 2000.00 @ 6% = 10.00.
        when(tcbRepository.findByIdAcctId(ACCT_ID)).thenReturn(List.of(
                tcb(ACCT_ID, "01", 5, "1000.00"),
                tcb(ACCT_ID, "02", 5, "2000.00")));
        when(disclosureGroupRepository.findById(dgId(GROUP_ID, "01", 5)))
                .thenReturn(Optional.of(discGroup(GROUP_ID, "01", 5, "12.00")));
        when(disclosureGroupRepository.findById(dgId(GROUP_ID, "02", 5)))
                .thenReturn(Optional.of(discGroup(GROUP_ID, "02", 5, "6.00")));

        BigDecimal result = interestCalculationService.calculateInterestForAccount(ACCT_ID, RUN_DATE);

        // 10.00 + 10.00 = 20.00.
        assertThat(result).isEqualByComparingTo("20.00");
        assertThat(result.scale()).isEqualTo(CardDemoConstants.MONEY_SCALE);
        // One interest transaction per non-zero category.
        verify(transactionRepository, times(2)).save(any(Transaction.class));
    }

    // =====================================================================================
    // Phase 3 — DISCGRP 'DEFAULT'-group fallback (AAP §0.7.3 #4 / CBACT04C 1200-A).
    // =====================================================================================

    @Test
    @DisplayName("DEFAULT fallback: keyed DISCGRP miss retries the 'DEFAULT' group; keyed read happens first")
    void calculateInterestForAccount_keyedRateMissing_fallsBackToDefaultGroup() {
        // Account belongs to a group that has NO keyed disclosure row.
        Account account = buildAccount(ACCT_ID, MISSING_GROUP, "500.00", "0.00", "0.00");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(xrefPage(CARD_NUM, ACCT_ID));
        when(tcbRepository.findByIdAcctId(ACCT_ID))
                .thenReturn(List.of(tcb(ACCT_ID, TYPE_CD, CAT_CD, "1000.00")));
        // Keyed lookup misses (legacy VSAM status 23 -> empty Optional)...
        when(disclosureGroupRepository.findById(dgId(MISSING_GROUP, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.empty());
        // ...and the service retries against the 'DEFAULT' group, which supplies the rate.
        when(disclosureGroupRepository.findById(dgId(DEFAULT_GROUP, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(discGroup(DEFAULT_GROUP, TYPE_CD, CAT_CD, "12.00")));

        BigDecimal result = interestCalculationService.calculateInterestForAccount(ACCT_ID, RUN_DATE);

        // The DEFAULT-group rate (12.00) drove the computation: 1000.00 * 12.00 / 1200 = 10.00.
        assertThat(result).isEqualByComparingTo("10.00");

        // The keyed read MUST be attempted BEFORE the DEFAULT read (CBACT04C 1200 then 1200-A).
        InOrder order = inOrder(disclosureGroupRepository);
        order.verify(disclosureGroupRepository).findById(dgId(MISSING_GROUP, TYPE_CD, CAT_CD));
        order.verify(disclosureGroupRepository).findById(dgId(DEFAULT_GROUP, TYPE_CD, CAT_CD));
    }

    // =====================================================================================
    // Phase 4 — interest transaction coding (CBACT04C 1300-B-WRITE-TX, L473-500).
    // =====================================================================================

    @Test
    @DisplayName("Interest transaction is coded 01/5/System/'Int. for a/c '+id, merchant 0, both timestamps set")
    void calculateInterestForAccount_writesInterestTransaction_withParityCoding() {
        Account account = buildAccount(ACCT_ID, GROUP_ID, "500.00", "0.00", "0.00");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(xrefPage(CARD_NUM, ACCT_ID));
        when(tcbRepository.findByIdAcctId(ACCT_ID))
                .thenReturn(List.of(tcb(ACCT_ID, TYPE_CD, CAT_CD, "1000.00")));
        when(disclosureGroupRepository.findById(dgId(GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(discGroup(GROUP_ID, TYPE_CD, CAT_CD, "12.00")));

        interestCalculationService.calculateInterestForAccount(ACCT_ID, RUN_DATE);

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        Transaction tx = txCaptor.getValue();

        // Type '01', category 5 (the legacy '05'), source 'System'.
        assertThat(tx.getTypeCd()).isEqualTo(CardDemoConstants.INTEREST_TRAN_TYPE_CD);
        assertThat(tx.getCatCd()).isEqualTo(CAT_CD);
        assertThat(tx.getSource()).isEqualTo(CardDemoConstants.INTEREST_TRAN_SOURCE);
        // Description = "Int. for a/c " (note trailing space) + account id.
        assertThat(tx.getDescription())
                .startsWith(CardDemoConstants.INTEREST_TRAN_DESC_PREFIX)
                .contains(String.valueOf(ACCT_ID));
        // Amount is the per-category interest (scale 2): 1000.00 * 12.00 / 1200 = 10.00.
        assertThat(tx.getAmt()).isEqualByComparingTo("10.00");
        assertThat(tx.getAmt().scale()).isEqualTo(CardDemoConstants.MONEY_SCALE);
        // Merchant id 0; merchant text fields blanked (never null).
        assertThat(tx.getMerchantId()).isEqualTo(0L);
        assertThat(tx.getMerchantName()).isEqualTo("");
        assertThat(tx.getMerchantCity()).isEqualTo("");
        assertThat(tx.getMerchantZip()).isEqualTo("");
        // Cross-reference card number and denormalized account id are stamped on the row.
        assertThat(tx.getCardNum()).isEqualTo(CARD_NUM);
        assertThat(tx.getAcctId()).isEqualTo(ACCT_ID);
        // Both timestamps are set (origination == processing == "now"), per L496-498.
        assertThat(tx.getOrigTs()).isNotNull();
        assertThat(tx.getProcTs()).isNotNull();
        assertThat(tx.getProcTs()).isEqualTo(tx.getOrigTs());
        // Transaction id is derived from the run date + suffix and is exactly 16 chars (PIC X(16)).
        assertThat(tx.getTranId()).isNotNull().hasSize(16);
    }

    // =====================================================================================
    // Phase 5 — account flush: balance += total interest; cycle totals zeroed (1050-UPDATE-ACCOUNT).
    // =====================================================================================

    @Test
    @DisplayName("Account flush: current balance += total interest; both cycle totals zeroed")
    void calculateInterestForAccount_flushesAccount_addsTotalAndZerosCycleTotals() {
        // Starting state: balance 500.00, cycle credit 300.00, cycle debit 120.00.
        Account account = buildAccount(ACCT_ID, GROUP_ID, "500.00", "300.00", "120.00");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(xrefPage(CARD_NUM, ACCT_ID));
        when(tcbRepository.findByIdAcctId(ACCT_ID))
                .thenReturn(List.of(tcb(ACCT_ID, TYPE_CD, CAT_CD, "1000.00")));
        when(disclosureGroupRepository.findById(dgId(GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(discGroup(GROUP_ID, TYPE_CD, CAT_CD, "12.00")));

        interestCalculationService.calculateInterestForAccount(ACCT_ID, RUN_DATE);

        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(acctCaptor.capture());
        Account saved = acctCaptor.getValue();

        // 500.00 + 10.00 = 510.00; cycle totals reset to zero (scale 2).
        assertThat(saved.getCurrBal()).isEqualByComparingTo("510.00");
        assertThat(saved.getCurrCycCredit()).isEqualByComparingTo("0.00");
        assertThat(saved.getCurrCycDebit()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Zero rate: posts NO interest transaction (CBACT04C L214) but still flushes the account")
    void calculateInterestForAccount_zeroRate_skipsInterestTransactionButStillFlushes() {
        Account account = buildAccount(ACCT_ID, GROUP_ID, "500.00", "300.00", "120.00");
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(xrefPage(CARD_NUM, ACCT_ID));
        when(tcbRepository.findByIdAcctId(ACCT_ID))
                .thenReturn(List.of(tcb(ACCT_ID, TYPE_CD, CAT_CD, "1000.00")));
        // A present-but-zero disclosure rate: the COBOL guard `IF DIS-INT-RATE NOT = 0` (L214) skips
        // interest computation entirely, so the production service writes NO interest transaction for
        // this category (it `continue`s). The account is nonetheless flushed at the account boundary.
        when(disclosureGroupRepository.findById(dgId(GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(discGroup(GROUP_ID, TYPE_CD, CAT_CD, "0.00")));

        BigDecimal result = interestCalculationService.calculateInterestForAccount(ACCT_ID, RUN_DATE);

        // No interest accrued.
        assertThat(result).isEqualByComparingTo("0.00");
        assertThat(result.scale()).isEqualTo(CardDemoConstants.MONEY_SCALE);
        // PRODUCTION BEHAVIOUR: zero-interest categories are NOT posted as transactions.
        verify(transactionRepository, never()).save(any(Transaction.class));

        // The account is still flushed: balance unchanged (+0.00), cycle totals zeroed.
        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(acctCaptor.capture());
        Account saved = acctCaptor.getValue();
        assertThat(saved.getCurrBal()).isEqualByComparingTo("500.00");
        assertThat(saved.getCurrCycCredit()).isEqualByComparingTo("0.00");
        assertThat(saved.getCurrCycDebit()).isEqualByComparingTo("0.00");
    }

    // =====================================================================================
    // Account-not-found path (1100-GET-ACCT-DATA absence -> ResourceNotFoundException / HTTP 404).
    // =====================================================================================

    @Test
    @DisplayName("Missing account -> ResourceNotFoundException; no balances read, no transactions posted")
    void calculateInterestForAccount_accountNotFound_throwsAndDoesNoWork() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interestCalculationService.calculateInterestForAccount(ACCT_ID, RUN_DATE))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Account")
                .hasMessageContaining(String.valueOf(ACCT_ID));

        verify(accountRepository).findById(ACCT_ID);
        verify(accountRepository, never()).save(any(Account.class));
        // The service aborts before touching category balances, rates, cross-references, or writes.
        verifyNoInteractions(tcbRepository, disclosureGroupRepository, cardXrefRepository, transactionRepository);
    }

    // =====================================================================================
    // Full-run orchestration: iterate the DISTINCT, ascending account ids in TransactionCategoryBalance.
    // =====================================================================================

    @Test
    @DisplayName("Full run iterates DISTINCT account ids in ascending order and flushes each account")
    void calculateInterest_fullRun_iteratesDistinctSortedAccountIds() {
        Account account10 = buildAccount(10L, GROUP_ID, "500.00", "0.00", "0.00");
        Account account20 = buildAccount(20L, GROUP_ID, "700.00", "0.00", "0.00");

        // findAll() returns rows out of order with account 10 appearing TWICE: the service must
        // collapse to the distinct set {10, 20} and process them ascending -> 10 then 20.
        when(tcbRepository.findAll()).thenReturn(List.of(
                tcb(20L, "01", 5, "1000.00"),
                tcb(10L, "01", 5, "1000.00"),
                tcb(10L, "02", 5, "1000.00")));
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account10));
        when(accountRepository.findById(20L)).thenReturn(Optional.of(account20));
        when(cardXrefRepository.findByXrefAcctId(anyLong(), any(Pageable.class)))
                .thenReturn(xrefPage(CARD_NUM, 10L));
        when(tcbRepository.findByIdAcctId(10L)).thenReturn(List.of(
                tcb(10L, "01", 5, "1000.00"),
                tcb(10L, "02", 5, "1000.00")));
        when(tcbRepository.findByIdAcctId(20L)).thenReturn(List.of(
                tcb(20L, "01", 5, "1000.00")));
        // Every keyed disclosure read is present (rate 12.00), so no DEFAULT fallback occurs.
        when(disclosureGroupRepository.findById(any()))
                .thenReturn(Optional.of(discGroup(GROUP_ID, "01", 5, "12.00")));

        interestCalculationService.calculateInterest(RUN_DATE);

        // Distinct + ascending: account 10 is processed strictly before account 20.
        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findById(10L);
        order.verify(accountRepository).findById(20L);
        // Each distinct account is flushed exactly once (no duplicate flush for the repeated id).
        verify(accountRepository, times(2)).save(any(Account.class));
        // One interest transaction per non-zero category: 2 (account 10) + 1 (account 20) = 3.
        verify(transactionRepository, times(3)).save(any(Transaction.class));
    }
}

package com.aws.carddemo.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DisclosureGroup;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DisclosureGroupRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Behavioral-parity unit tests for {@link InterestCalcJobConfig}, driving the control-break loop
 * {@link InterestCalcJobConfig#calculateInterest(String)} directly with mocked repositories (no
 * Spring context, no database) so every branch of the COBOL {@code CBACT04C}
 * {@code PROCEDURE DIVISION} is exercised deterministically.
 *
 * <p><strong>Origin (traceability):</strong> verifies the migration of
 * {@code legacy/cbl/CBACT04C.cbl} (JCL {@code legacy/jcl/INTCALC.jcl}). Each test names the COBOL
 * behavior it guards.</p>
 *
 * <p>Coverage of the migration's parity guarantees (AAP &sect;0.6.1):</p>
 * <ul>
 *   <li>Decimal fidelity &mdash; {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} is truncated (never
 *       rounded) to two decimals, including cases where {@code HALF_UP} would differ.</li>
 *   <li>{@code TRAN-ID} = 10-char {@code PARM-DATE} + 6-digit monotonic suffix that does not reset
 *       across accounts.</li>
 *   <li>Interest-transaction tagging: type {@code 01}, category {@code 5}, source {@code System},
 *       description prefix {@code "Int. for a/c "}, merchant id {@code 0}, card number from the
 *       cross-reference, and identical origin/processing timestamps.</li>
 *   <li>{@code 1200-A-GET-DEFAULT-INT-RATE} DEFAULT-group fallback when a specific disclosure row is
 *       missing, and abend when both are missing.</li>
 *   <li>Zero-rate rows are skipped entirely (no transaction, no accumulation).</li>
 *   <li>The parity-critical quirk: the FINAL account group is never written back, yet its interest
 *       transactions are still written.</li>
 *   <li>Abend-equivalents (missing account, missing cross-reference, missing job parameter) throw.</li>
 * </ul>
 */
class InterestCalcJobConfigTest {

    /** The COBOL {@code PARM-DATE} value from {@code INTCALC.jcl} ({@code PARM='2022071800'}). */
    private static final String PROC_DATE = "2022071800";

    private TransactionCategoryBalanceRepository catBalRepo;
    private AccountRepository accountRepo;
    private CardXrefRepository xrefRepo;
    private DisclosureGroupRepository discRepo;
    private TransactionRepository tranRepo;
    private InterestCalcJobConfig config;

    @BeforeEach
    void setUp() {
        catBalRepo = mock(TransactionCategoryBalanceRepository.class);
        accountRepo = mock(AccountRepository.class);
        xrefRepo = mock(CardXrefRepository.class);
        discRepo = mock(DisclosureGroupRepository.class);
        tranRepo = mock(TransactionRepository.class);
        config = new InterestCalcJobConfig(
                mock(JobRepository.class),
                mock(PlatformTransactionManager.class),
                catBalRepo, accountRepo, xrefRepo, discRepo, tranRepo);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static TransactionCategoryBalance tcb(long acctId, String typeCd, int catCd, String bal) {
        TransactionCategoryBalance t = new TransactionCategoryBalance();
        t.setAcctId(acctId);
        t.setTypeCd(typeCd);
        t.setCatCd(catCd);
        t.setBalance(new BigDecimal(bal));
        return t;
    }

    private static Account account(long acctId, String groupId, String currBal) {
        Account a = new Account();
        a.setAcctId(acctId);
        a.setGroupId(groupId);
        a.setCurrBal(new BigDecimal(currBal));
        a.setCurrCycCredit(new BigDecimal("111.11"));
        a.setCurrCycDebit(new BigDecimal("222.22"));
        return a;
    }

    private static DisclosureGroup disc(String group, String type, int cat, String rate) {
        DisclosureGroup d = new DisclosureGroup();
        d.setAcctGroupId(group);
        d.setTranTypeCd(type);
        d.setTranCatCd(cat);
        d.setIntRate(new BigDecimal(rate));
        return d;
    }

    private void stubXref(long acctId, String cardNum) {
        List<CardXref> list = new ArrayList<>();
        list.add(new CardXref(cardNum, 1L, acctId));
        when(xrefRepo.findByXrefAcctId(acctId)).thenReturn(list);
    }

    private void stubDisc(String group, String type, int cat, String rate) {
        when(discRepo.findById(new DisclosureGroup.DisclosureGroupId(group, type, cat)))
                .thenReturn(Optional.of(disc(group, type, cat, rate)));
    }

    private List<Transaction> savedTransactions() {
        ArgumentCaptor<Transaction> cap = ArgumentCaptor.forClass(Transaction.class);
        verify(tranRepo, atLeast(0)).save(cap.capture());
        return cap.getAllValues();
    }

    private List<Account> savedAccounts() {
        ArgumentCaptor<Account> cap = ArgumentCaptor.forClass(Account.class);
        verify(accountRepo, atLeast(0)).save(cap.capture());
        return cap.getAllValues();
    }

    // ---- tests ---------------------------------------------------------------------------------

    @Test
    @DisplayName("1300-COMPUTE-INTEREST truncates (1000.00 * 19.99)/1200 DOWN to 16.65, not 16.66")
    void truncationCanonicalCaseRoundsDownNotHalfUp() {
        // (1000.00 * 19.99) / 1200 = 16.6583... -> DOWN 16.65 (NOT HALF_UP 16.66)
        when(catBalRepo.streamAllByAccountKeyOrder())
                .thenAnswer(inv -> Stream.of(tcb(11L, "01", 5, "1000.00")));
        when(accountRepo.findById(11L)).thenReturn(Optional.of(account(11L, "GRP", "0.00")));
        stubXref(11L, "1234567890123456");
        stubDisc("GRP", "01", 5, "19.99");

        config.calculateInterest(PROC_DATE);

        List<Transaction> txns = savedTransactions();
        assertEquals(1, txns.size());
        assertEquals(0, txns.get(0).getTranAmt().compareTo(new BigDecimal("16.65")),
                "canonical interest must truncate to 16.65");
        assertEquals(2, txns.get(0).getTranAmt().scale(), "amount must retain scale 2");
    }

    @Test
    @DisplayName("Interest is truncated even when HALF_UP would round up: 1.6666 -> 1.66, not 1.67")
    void truncationWhenHalfUpWouldRoundUpStillRoundsDown() {
        // (100.00 * 20.00) / 1200 = 1.6666... -> DOWN 1.66 (HALF_UP would give 1.67)
        when(catBalRepo.streamAllByAccountKeyOrder())
                .thenAnswer(inv -> Stream.of(tcb(11L, "01", 5, "100.00")));
        when(accountRepo.findById(11L)).thenReturn(Optional.of(account(11L, "GRP", "0.00")));
        stubXref(11L, "1234567890123456");
        stubDisc("GRP", "01", 5, "20.00");

        config.calculateInterest(PROC_DATE);

        assertEquals(0, savedTransactions().get(0).getTranAmt().compareTo(new BigDecimal("1.66")),
                "must truncate 1.6666 down to 1.66, never up to 1.67");
    }

    @Test
    @DisplayName("TRAN-ID = 10-char PARM-DATE + 6-digit suffix; suffix is monotonic across accounts")
    void tranIdIsProcessingDatePlusSixDigitSuffixMonotonicAcrossAccounts() {
        when(catBalRepo.streamAllByAccountKeyOrder()).thenAnswer(inv -> Stream.of(
                tcb(11L, "01", 5, "1000.00"),
                tcb(22L, "01", 5, "1000.00")));
        when(accountRepo.findById(11L)).thenReturn(Optional.of(account(11L, "GRP", "0.00")));
        when(accountRepo.findById(22L)).thenReturn(Optional.of(account(22L, "GRP", "0.00")));
        stubXref(11L, "1111111111111111");
        stubXref(22L, "2222222222222222");
        stubDisc("GRP", "01", 5, "19.99");

        config.calculateInterest(PROC_DATE);

        List<Transaction> txns = savedTransactions();
        assertEquals(2, txns.size());
        assertEquals("2022071800000001", txns.get(0).getTranId());
        assertEquals("2022071800000002", txns.get(1).getTranId(),
                "suffix must be a monotonic counter that does NOT reset across accounts");
        assertEquals(16, txns.get(0).getTranId().length());
    }

    @Test
    @DisplayName("1300-B-WRITE-TX tags interest txn: type 01, cat 5, source System, desc, merchant 0, card from xref")
    void interestTransactionTaggingMatchesCobol() {
        when(catBalRepo.streamAllByAccountKeyOrder())
                .thenAnswer(inv -> Stream.of(tcb(11L, "07", 9, "1000.00")));
        when(accountRepo.findById(11L)).thenReturn(Optional.of(account(11L, "GRP", "0.00")));
        stubXref(11L, "4444333322221111");
        stubDisc("GRP", "07", 9, "19.99");

        config.calculateInterest(PROC_DATE);

        Transaction tx = savedTransactions().get(0);
        assertEquals("01", tx.getTranTypeCd());
        assertEquals(Integer.valueOf(5), tx.getTranCatCd());
        assertEquals("System", tx.getTranSource());
        assertTrue(tx.getTranDesc().startsWith("Int. for a/c "), "desc prefix");
        assertEquals("Int. for a/c 00000000011", tx.getTranDesc(), "acct id zero-padded to 11 digits");
        assertEquals(Long.valueOf(0L), tx.getMerchantId());
        assertEquals("4444333322221111", tx.getCardNum(), "card number from xref");
        assertEquals(tx.getOrigTs(), tx.getProcTs(), "orig and proc timestamps identical");
    }

    @Test
    @DisplayName("1200-A-GET-DEFAULT-INT-RATE: DEFAULT group used when specific (group,type,cat) missing")
    void defaultDisclosureGroupUsedWhenSpecificMissing() {
        when(catBalRepo.streamAllByAccountKeyOrder())
                .thenAnswer(inv -> Stream.of(tcb(11L, "01", 5, "1200.00")));
        when(accountRepo.findById(11L)).thenReturn(Optional.of(account(11L, "MISSING", "0.00")));
        stubXref(11L, "1234567890123456");
        // specific (MISSING,01,5) not found -> Optional.empty(); DEFAULT stubbed:
        when(discRepo.findById(new DisclosureGroup.DisclosureGroupId("MISSING", "01", 5)))
                .thenReturn(Optional.empty());
        stubDisc("DEFAULT", "01", 5, "12.00"); // (1200*12)/1200 = 12.00

        config.calculateInterest(PROC_DATE);

        assertEquals(0, savedTransactions().get(0).getTranAmt().compareTo(new BigDecimal("12.00")),
                "DEFAULT-group rate must be used when the specific group is missing");
    }

    @Test
    @DisplayName("Missing specific AND DEFAULT disclosure group -> abend (IllegalStateException)")
    void defaultDisclosureGroupBothMissingThrows() {
        when(catBalRepo.streamAllByAccountKeyOrder())
                .thenAnswer(inv -> Stream.of(tcb(11L, "01", 5, "1000.00")));
        when(accountRepo.findById(11L)).thenReturn(Optional.of(account(11L, "MISSING", "0.00")));
        stubXref(11L, "1234567890123456");
        when(discRepo.findById(any())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> config.calculateInterest(PROC_DATE));
    }

    @Test
    @DisplayName("Zero interest rate skips the row entirely: no transaction, no accumulation")
    void zeroRateSkipsInterestEntirelyNoTransactionNoAccumulation() {
        // Account 11 has a zero-rate row then a non-zero-rate row; account 22 forces the control
        // break so account 11 is written back. Its accumulated interest must reflect ONLY the
        // non-zero row (the zero-rate row contributes nothing).
        when(catBalRepo.streamAllByAccountKeyOrder()).thenAnswer(inv -> Stream.of(
                tcb(11L, "01", 5, "1000.00"),   // rate 0 -> skipped
                tcb(11L, "02", 5, "1200.00"),   // rate 12.00 -> 12.00 interest
                tcb(22L, "01", 5, "1000.00")));  // rate 0 -> skipped (also the final account)
        when(accountRepo.findById(11L)).thenReturn(Optional.of(account(11L, "GRP", "500.00")));
        when(accountRepo.findById(22L)).thenReturn(Optional.of(account(22L, "GRP", "0.00")));
        stubXref(11L, "1111111111111111");
        stubXref(22L, "2222222222222222");
        stubDisc("GRP", "01", 5, "0.00");   // zero rate -> skipped (acct 11 row 1, acct 22 row)
        stubDisc("GRP", "02", 5, "12.00");  // non-zero (acct 11 row 2) -> 12.00 interest

        config.calculateInterest(PROC_DATE);

        // Only the single non-zero row (acct 11 type 02) produces a transaction; both zero-rate
        // rows (acct 11 type 01, acct 22 type 01) are skipped with no transaction written.
        List<Transaction> txns = savedTransactions();
        assertEquals(1, txns.size(), "zero-rate rows write no interest transaction");
        assertEquals(0, txns.get(0).getTranAmt().compareTo(new BigDecimal("12.00")));
        // Account 11 (non-final) written back: 500.00 + 12.00 = 512.00, only the non-zero row counted.
        List<Account> accts = savedAccounts();
        assertEquals(1, accts.size(), "only the non-final account is written back");
        assertEquals(0, accts.get(0).getCurrBal().compareTo(new BigDecimal("512.00")),
                "accumulation excludes the zero-rate row");
    }

    @Test
    @DisplayName("PARITY GATE: final account is NOT written back, yet its interest transactions ARE written")
    void finalAccountNotUpdatedButItsTransactionsAreWritten() {
        // Account A (non-final) updated; Account B (final) NOT updated, yet B's interest transaction
        // is still written. This is the parity-critical CBACT04C quirk (test-before PERFORM UNTIL).
        Account a = account(11L, "GRP", "500.00");
        Account b = account(22L, "GRP", "700.00");
        when(catBalRepo.streamAllByAccountKeyOrder()).thenAnswer(inv -> Stream.of(
                tcb(11L, "01", 5, "1000.00"),   // A: (1000*12)/1200 = 10.00
                tcb(22L, "01", 5, "2000.00")));  // B: (2000*12)/1200 = 20.00
        when(accountRepo.findById(11L)).thenReturn(Optional.of(a));
        when(accountRepo.findById(22L)).thenReturn(Optional.of(b));
        stubXref(11L, "1111111111111111");
        stubXref(22L, "2222222222222222");
        stubDisc("GRP", "01", 5, "12.00");

        config.calculateInterest(PROC_DATE);

        // Two interest transactions written (BOTH accounts), amounts 10.00 and 20.00.
        List<Transaction> txns = savedTransactions();
        assertEquals(2, txns.size());
        assertEquals(0, txns.get(0).getTranAmt().compareTo(new BigDecimal("10.00")));
        assertEquals(0, txns.get(1).getTranAmt().compareTo(new BigDecimal("20.00")));

        // Only account A is saved (the final account B is NEVER written back).
        List<Account> accts = savedAccounts();
        assertEquals(1, accts.size(), "exactly one account (the non-final A) is written back");
        assertEquals(Long.valueOf(11L), accts.get(0).getAcctId(), "final account B must not be saved");
        assertEquals(0, accts.get(0).getCurrBal().compareTo(new BigDecimal("510.00")),
                "A balance = 500.00 + 10.00");
        assertEquals(0, accts.get(0).getCurrCycCredit().compareTo(BigDecimal.ZERO),
                "A cycle credit reset");
        assertEquals(0, accts.get(0).getCurrCycDebit().compareTo(BigDecimal.ZERO),
                "A cycle debit reset");

        // Final account B: untouched by write-back; its object retains original balance & cycles.
        assertEquals(0, b.getCurrBal().compareTo(new BigDecimal("700.00")),
                "final account B balance unchanged (quirk)");
        assertEquals(0, b.getCurrCycCredit().compareTo(new BigDecimal("111.11")),
                "final account B cycle credit not reset (quirk)");
        verify(accountRepo, never()).save(b);
    }

    @Test
    @DisplayName("1100-GET-ACCT-DATA: missing account -> abend (IllegalStateException)")
    void missingAccountThrows() {
        when(catBalRepo.streamAllByAccountKeyOrder())
                .thenAnswer(inv -> Stream.of(tcb(11L, "01", 5, "1000.00")));
        when(accountRepo.findById(11L)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> config.calculateInterest(PROC_DATE));
    }

    @Test
    @DisplayName("1110-GET-XREF-DATA: missing cross-reference -> abend (IllegalStateException)")
    void missingXrefThrows() {
        when(catBalRepo.streamAllByAccountKeyOrder())
                .thenAnswer(inv -> Stream.of(tcb(11L, "01", 5, "1000.00")));
        when(accountRepo.findById(11L)).thenReturn(Optional.of(account(11L, "GRP", "0.00")));
        when(xrefRepo.findByXrefAcctId(11L)).thenReturn(new ArrayList<>());

        assertThrows(IllegalStateException.class, () -> config.calculateInterest(PROC_DATE));
    }

    @Test
    @DisplayName("Missing/blank PARM-DATE job parameter -> IllegalArgumentException")
    void blankProcessingDateThrows() {
        assertThrows(IllegalArgumentException.class, () -> config.calculateInterest("  "));
        assertThrows(IllegalArgumentException.class, () -> config.calculateInterest(null));
    }

    @Test
    @DisplayName("Bean wiring: job/step have the expected names and the @StepScope tasklet runs the scan")
    void beanWiringAndTaskletExecution() throws Exception {
        // Empty category-balance scan: the tasklet delegates to calculateInterest (which processes
        // zero rows) and reports completion, exercising the @Bean factories and the tasklet lambda.
        when(catBalRepo.streamAllByAccountKeyOrder()).thenAnswer(inv -> Stream.of());

        Job job = config.interestCalcJob();
        assertEquals("interestCalcJob", job.getName(), "job name mirrors CBACT04C job");

        Step step = config.interestCalcStep();
        assertEquals("interestCalcStep", step.getName(), "single step name");

        // The lambda ignores its StepContribution/ChunkContext arguments, so nulls are sufficient
        // to drive it; it must return FINISHED after one pass.
        Tasklet tasklet = config.interestCalcTasklet(PROC_DATE);
        RepeatStatus status = tasklet.execute(null, null);
        assertEquals(RepeatStatus.FINISHED, status, "tasklet completes in a single execution");
    }
}

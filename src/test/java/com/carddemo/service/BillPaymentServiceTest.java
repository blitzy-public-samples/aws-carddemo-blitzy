package com.carddemo.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.carddemo.dto.BillPaymentRequest;
import com.carddemo.dto.BillPaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.BusinessRuleException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.TranIdGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure <strong>Mockito</strong> unit test for {@link BillPaymentService} &mdash; the Java/Spring
 * re-expression of the legacy CICS COBOL bill-payment program {@code app/cbl/COBIL00C.cbl}
 * ("Bill Payment").
 *
 * <h2>What this test pins (bill-payment parity &mdash; AAP &sect;0.4.1.3, &sect;0.6)</h2>
 * <p>{@code COBIL00C} drives a single 3270 screen that (a) displays an account's current balance and
 * available credit, and (b), on operator confirmation, pays that balance <em>in full</em> while
 * recording a fixed-coded transaction and zeroing the balance. This test mocks the repository
 * collaborators to verify, in isolation, the four behaviors that constitute 100% functional parity
 * with the source program:</p>
 * <ol>
 *   <li><strong>Available-credit math</strong> &mdash; {@code availableCredit = ACCT-CREDIT-LIMIT - ACCT-CURR-BAL},
 *       scaled to two decimal places ({@code COBIL00C} balance display).</li>
 *   <li><strong>Full-balance payment</strong> &mdash; on {@code CONFIRM = 'Y'} with a positive balance,
 *       exactly one transaction is written with the COBOL literal coding
 *       ({@code TRAN-TYPE-CD '02'}, {@code TRAN-CAT-CD 2}, {@code TRAN-SOURCE 'POS TERM'},
 *       {@code TRAN-DESC 'BILL PAYMENT - ONLINE'}, {@code TRAN-MERCHANT-ID 999999999},
 *       {@code TRAN-MERCHANT-NAME 'BILL PAYMENT'}, {@code TRAN-MERCHANT-CITY/ZIP 'N/A'}), the amount
 *       equals the full {@code ACCT-CURR-BAL}, the owning card number comes from the card cross-reference,
 *       both {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} are set, and the account balance is driven to zero
 *       ({@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}).</li>
 *   <li><strong>Nothing-to-pay rule</strong> &mdash; {@code ACCT-CURR-BAL <= ZEROS} rejects with
 *       "You have nothing to pay..." (mapped to {@link BusinessRuleException} &rarr; HTTP&nbsp;400) and
 *       posts nothing.</li>
 *   <li><strong>{@code CONFIRM = 'N'} no-op</strong> &mdash; declines the payment, posting nothing and
 *       leaving the balance unchanged (the legacy clear-screen path).</li>
 * </ol>
 *
 * <h2>Test character</h2>
 * <p>This is a <em>pure</em> unit test: {@code @ExtendWith(MockitoExtension.class)} with constructor
 * injection of mocks &mdash; <strong>no</strong> Spring context, <strong>no</strong> database, and no
 * I/O. The five collaborators declared by the production constructor
 * ({@link AccountRepository}, {@link TransactionRepository}, {@link CardXrefRepository},
 * {@link TranIdGenerator}, {@link MessageService}) are all mocked; {@code @InjectMocks} wires them in.
 * Stubbing is kept minimal per test so that Mockito's default {@code STRICT_STUBS} policy passes with
 * no {@code UnnecessaryStubbingException}.</p>
 *
 * <h2>Monetary assertions</h2>
 * <p>All money comparisons use AssertJ {@code isEqualByComparingTo} (scale-insensitive value equality)
 * rather than {@code equals}, mirroring the {@link BigDecimal#compareTo(BigDecimal)} discipline of the
 * production service; the {@code scale() == 2} of computed values is asserted explicitly to preserve the
 * {@code V99} semantics of the COBOL copybooks ({@code CVACT01Y}, {@code CVTRA05Y}).</p>
 *
 * <h2>Test data &amp; PII</h2>
 * <p>The card number {@code 4111111111111111} is the universally published Visa test PAN (not a real
 * credential). No CVV, SSN, password, or other sensitive value appears in any fixture (AAP &sect;0.6.8).</p>
 *
 * @see BillPaymentService
 * @see <a href="file:app/cbl/COBIL00C.cbl">app/cbl/COBIL00C.cbl (Bill Payment)</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillPaymentService \u2014 COBIL00C bill-payment parity (pure Mockito unit test)")
class BillPaymentServiceTest {

    // ---------------------------------------------------------------------------------------------
    // Fixed test data
    // ---------------------------------------------------------------------------------------------

    /** Account under test ({@code ACCT-ID PIC 9(11)} \u2192 {@link Long}). */
    private static final Long ACCT_ID = 10L;

    /** An account id with no record, to exercise the not-found (HTTP 404) path. */
    private static final Long MISSING_ACCT_ID = 999L;

    /** Customer id used only to satisfy the {@link CardXref} constructor; irrelevant to assertions. */
    private static final Long CUST_ID = 1L;

    /** Visa test PAN ({@code XREF-CARD-NUM}); resolved from the cross-reference and stamped on the txn. */
    private static final String CARD_NUM = "4111111111111111";

    /** Deterministic 16-char, zero-padded transaction id returned by the mocked {@link TranIdGenerator}. */
    private static final String GENERATED_TRAN_ID = "0000000000000123";

    /** {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} fixture value (scale 2). */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("500.00");

    /** {@code ACCT-CURR-BAL PIC S9(10)V99} positive fixture value (scale 2). */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("100.00");

    // ---------------------------------------------------------------------------------------------
    // Mocked collaborators (every type the production constructor declares) + class under test
    // ---------------------------------------------------------------------------------------------

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TranIdGenerator tranIdGenerator;

    /**
     * Declared so {@code @InjectMocks} can satisfy the production constructor's fifth argument. The
     * service consumes {@link MessageService} only through its {@code public static final} message
     * constants (e.g. {@link MessageService#NOTHING_TO_PAY}), so this mock is never stubbed or invoked;
     * an unused mock is permitted under {@code STRICT_STUBS} (only unused <em>stubbings</em> fail).
     */
    @Mock
    private MessageService messageService;

    @InjectMocks
    private BillPaymentService billPaymentService;

    // ---------------------------------------------------------------------------------------------
    // Fixture factories (no shared mutable state between tests)
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds an {@link Account} for {@link #ACCT_ID} with the supplied balance and credit limit.
     *
     * @param balance     {@code ACCT-CURR-BAL}
     * @param creditLimit {@code ACCT-CREDIT-LIMIT}
     * @return a detached {@link Account} fixture
     */
    private Account accountWith(BigDecimal balance, BigDecimal creditLimit) {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setCurrBal(balance);
        account.setCreditLimit(creditLimit);
        return account;
    }

    /**
     * Builds a {@link CardXref} mapping {@link #ACCT_ID} to the supplied card number, as the legacy
     * {@code READ-CXACAIX-FILE} alternate-index read would have resolved {@code XREF-CARD-NUM}.
     *
     * @param cardNum the card number ({@code XREF-CARD-NUM})
     * @return a {@link CardXref} fixture
     */
    private CardXref xrefWith(String cardNum) {
        return new CardXref(cardNum, CUST_ID, ACCT_ID);
    }

    // =============================================================================================
    // Balance inquiry: available-credit math (no posting)
    // =============================================================================================

    @Test
    @DisplayName("getBillPaymentInfo: availableCredit = creditLimit - currentBalance (scale 2); no posting")
    void getBillPaymentInfo_returnsAvailableCreditAndBalance_withoutPosting() {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(accountWith(CURRENT_BALANCE, CREDIT_LIMIT)));

        BillPaymentResponse resp = billPaymentService.getBillPaymentInfo(ACCT_ID);

        assertThat(resp.accountId()).isEqualTo(ACCT_ID);
        assertThat(resp.currentBalance()).isEqualByComparingTo("100.00");
        assertThat(resp.availableCredit()).isEqualByComparingTo("400.00");
        assertThat(resp.availableCredit().scale()).isEqualTo(2);

        // Inquiry only: nothing is posted, so neither a transaction id nor a new balance is produced.
        assertThat(resp.transactionId()).isNull();
        assertThat(resp.newBalance()).isNull();

        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("getBillPaymentInfo: unknown account -> ResourceNotFoundException (404)")
    void getBillPaymentInfo_accountNotFound_throwsResourceNotFound() {
        when(accountRepository.findById(MISSING_ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> billPaymentService.getBillPaymentInfo(MISSING_ACCT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =============================================================================================
    // Confirm = 'Y': full-balance payment (the core parity path)
    // =============================================================================================

    @Test
    @DisplayName("processBillPayment confirm=true: posts full-balance 'BILL PAYMENT - ONLINE' txn and zeroes the balance")
    void processBillPayment_confirmTrue_postsFullBalancePaymentAndZeroesBalance() {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(accountWith(CURRENT_BALANCE, CREDIT_LIMIT)));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(xrefWith(CARD_NUM))));
        when(tranIdGenerator.generateTransactionId()).thenReturn(GENERATED_TRAN_ID);
        // Echo the saved entities back, as the JPA repositories would.
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        BillPaymentResponse resp =
                billPaymentService.processBillPayment(ACCT_ID, new BillPaymentRequest(true));

        // ----- the posted transaction: exact COBIL00C coded fields (L220-L229) -----
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        Transaction posted = txCaptor.getValue();

        // Firmly documented coded fields.
        assertThat(posted.getTypeCd()).isEqualTo("02");
        assertThat(posted.getCatCd()).isEqualTo(2);
        assertThat(posted.getSource()).isEqualTo("POS TERM");
        assertThat(posted.getDescription()).isEqualTo("BILL PAYMENT - ONLINE");
        assertThat(posted.getMerchantName()).isEqualTo("BILL PAYMENT");

        // Amount is the FULL current balance, scale 2.
        assertThat(posted.getAmt()).isEqualByComparingTo("100.00");
        assertThat(posted.getAmt().scale()).isEqualTo(2);

        // Card number is resolved from the cross-reference; account id is carried through.
        assertThat(posted.getCardNum()).isEqualTo(CARD_NUM);
        assertThat(posted.getAcctId()).isEqualTo(ACCT_ID);

        // The generated transaction id is stamped on the written row.
        assertThat(posted.getTranId()).isEqualTo(GENERATED_TRAN_ID);

        // Both timestamps are set (TRAN-ORIG-TS and TRAN-PROC-TS).
        assertThat(posted.getOrigTs()).isNotNull();
        assertThat(posted.getProcTs()).isNotNull();

        // Remaining synthetic merchant fields carried verbatim for parity (production sets these).
        assertThat(posted.getMerchantId()).isEqualTo(999999999L);
        assertThat(posted.getMerchantCity()).isEqualTo("N/A");
        assertThat(posted.getMerchantZip()).isEqualTo("N/A");

        // ----- the account: balance reduced by the full payment -> zero (L234) -----
        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(acctCaptor.capture());
        assertThat(acctCaptor.getValue().getCurrBal()).isEqualByComparingTo("0.00");

        // ----- the response -----
        assertThat(resp.transactionId()).isEqualTo(GENERATED_TRAN_ID); // non-null: a payment occurred
        assertThat(resp.newBalance()).isEqualByComparingTo("0.00");
        assertThat(resp.currentBalance()).isEqualByComparingTo("100.00"); // pre-payment balance
        assertThat(resp.availableCredit()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("processBillPayment confirm=true: no card cross-reference -> ResourceNotFoundException (404); nothing posted")
    void processBillPayment_confirmTrue_noCardXref_throwsResourceNotFound() {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(accountWith(CURRENT_BALANCE, CREDIT_LIMIT)));
        Page<CardXref> emptyXref = new PageImpl<>(List.of());
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(emptyXref);

        assertThatThrownBy(
                () -> billPaymentService.processBillPayment(ACCT_ID, new BillPaymentRequest(true)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    // =============================================================================================
    // Nothing-to-pay rule: balance <= 0
    // =============================================================================================

    @ParameterizedTest(name = "balance={0} -> nothing to pay")
    @ValueSource(strings = {"0.00", "-50.00", "-0.01"})
    @DisplayName("processBillPayment confirm=true: balance <= 0 -> BusinessRuleException (400); nothing posted")
    void processBillPayment_nothingToPay_throwsBusinessRuleException(String balanceLiteral) {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(accountWith(new BigDecimal(balanceLiteral), CREDIT_LIMIT)));

        assertThatThrownBy(
                () -> billPaymentService.processBillPayment(ACCT_ID, new BillPaymentRequest(true)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(MessageService.NOTHING_TO_PAY);

        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    // =============================================================================================
    // Confirm = 'N': no-op (decline)
    // =============================================================================================

    @Test
    @DisplayName("processBillPayment confirm=false: no posting; balance unchanged (newBalance null per production contract)")
    void processBillPayment_confirmFalse_isNoOp() {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(accountWith(CURRENT_BALANCE, CREDIT_LIMIT)));

        BillPaymentResponse resp =
                billPaymentService.processBillPayment(ACCT_ID, new BillPaymentRequest(false));

        // Decline path posts nothing.
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());

        // Production returns (accountId, currentBalance, availableCredit, null, null) on confirm=false:
        // transactionId AND newBalance are both null; the pre-payment balance/credit are surfaced.
        assertThat(resp.transactionId()).isNull();
        assertThat(resp.newBalance()).isNull();
        assertThat(resp.currentBalance()).isEqualByComparingTo("100.00");
        assertThat(resp.availableCredit()).isEqualByComparingTo("400.00");
    }
}

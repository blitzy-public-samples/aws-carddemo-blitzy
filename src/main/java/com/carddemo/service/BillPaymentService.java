package com.carddemo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.BillPaymentRequest;
import com.carddemo.dto.BillPaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.BusinessRuleException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.TranIdGenerator;

/**
 * Online <strong>bill-payment</strong> service &mdash; the Spring/Java re-expression of the legacy
 * CICS COBOL program {@code app/cbl/COBIL00C.cbl} ("Bill Payment").
 *
 * <h2>Legacy provenance (REFERENCE only &mdash; never modified)</h2>
 * <p>{@code COBIL00C} drives a single 3270 screen ({@code COBIL00}) that performs two things from one
 * account id: it <em>displays</em> the account's current balance and, on operator confirmation,
 * <em>pays that balance in full</em>. The behavior this service reproduces, verbatim from the source
 * paragraph {@code PROCESS-ENTER-KEY}, is:</p>
 * <ul>
 *   <li>Read the account record ({@code READ-ACCTDAT-FILE}) and surface {@code ACCT-CURR-BAL}.</li>
 *   <li>If {@code ACCT-CURR-BAL <= ZEROS} &rarr; reject with "You have nothing to pay..."
 *       ({@code COBIL00C.cbl} L198&ndash;L205).</li>
 *   <li>On {@code CONFIRM = 'Y'}: resolve the owning card number from the card cross-reference
 *       ({@code READ-CXACAIX-FILE} &rarr; {@code XREF-CARD-NUM}), allocate the next transaction id
 *       (legacy {@code STARTBR}/{@code READPREV}/{@code ENDBR} "highest&nbsp;+&nbsp;1" browse), build a
 *       transaction with {@code TRAN-TYPE-CD='02'}, {@code TRAN-CAT-CD=2}, {@code TRAN-SOURCE='POS TERM'},
 *       {@code TRAN-DESC='BILL PAYMENT - ONLINE'}, {@code TRAN-AMT = ACCT-CURR-BAL} (the FULL balance),
 *       {@code TRAN-CARD-NUM = XREF-CARD-NUM}, {@code TRAN-MERCHANT-ID = 999999999},
 *       {@code TRAN-MERCHANT-NAME = 'BILL PAYMENT'}, {@code TRAN-MERCHANT-CITY/ZIP = 'N/A'} and both
 *       {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} set to the current timestamp; {@code WRITE} the
 *       transaction; then {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} (driving the balance
 *       to zero) and {@code REWRITE} the account ({@code COBIL00C.cbl} L208&ndash;L235).</li>
 *   <li>On {@code CONFIRM = 'N'}: do not post (the legacy clear-screen path).</li>
 *   <li>On any other {@code CONFIRM} value &rarr; "Invalid value. Valid values are (Y/N)..."
 *       ({@code COBIL00C.cbl} L185&ndash;L190).</li>
 * </ul>
 *
 * <h2>Modern translation</h2>
 * <ul>
 *   <li>The fragile {@code STARTBR}/{@code READPREV}/{@code ENDBR} next-id browse is replaced by the
 *       concurrency-safe {@link TranIdGenerator} (PostgreSQL sequence + {@code LPAD} to 16 chars), since
 *       bill payment is an <em>online write</em> path.</li>
 *   <li>The CICS {@code READ ... UPDATE} / {@code REWRITE} on the account becomes a JPA
 *       {@link AccountRepository#findById(Object)} followed by {@link AccountRepository#save(Object)};
 *       the {@code Account} {@code @Version} column provides optimistic-lock conflict detection
 *       (surfaced as HTTP&nbsp;409 by {@code GlobalExceptionHandler}) in place of the VSAM file-status
 *       check (AAP &sect;0.6.6).</li>
 *   <li>Screen messages become typed domain exceptions translated to HTTP status codes:
 *       account-not-found &rarr; {@link ResourceNotFoundException} (404), nothing-to-pay &rarr;
 *       {@link BusinessRuleException} (400), and a missing/invalid confirmation &rarr;
 *       {@link ValidationException} (400).</li>
 * </ul>
 *
 * <h2>Monetary semantics (AAP &sect;0.7.1)</h2>
 * <p>All money is {@link BigDecimal}; every computed value is scaled to {@value #MONEY_SCALE} decimal
 * places with {@link RoundingMode#HALF_UP}, and comparisons use {@link BigDecimal#compareTo(BigDecimal)}
 * (never {@code equals}, which is scale-sensitive). {@code double}/{@code float} are never used, to
 * preserve the exact base-10 semantics of the source COBOL {@code S9(10)V99}/{@code S9(09)V99} fields.</p>
 *
 * <h2>Layering &amp; PII</h2>
 * <p>This is a stateless, thread-safe {@code @Service} with constructor-injected collaborators only. It
 * holds all bill-payment business logic; controllers contain none. The card number is treated as
 * sensitive and is never logged (AAP &sect;0.6.8).</p>
 *
 * @see <a href="file:app/cbl/COBIL00C.cbl">app/cbl/COBIL00C.cbl (Bill Payment)</a>
 * @see <a href="file:app/cpy/CVTRA05Y.cpy">app/cpy/CVTRA05Y.cpy (TRAN-RECORD)</a>
 * @see <a href="file:app/cpy/CVACT01Y.cpy">app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD)</a>
 * @see <a href="file:app/cpy/CVACT03Y.cpy">app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD)</a>
 */
@Service
public class BillPaymentService {

    // ------------------------------------------------------------------------
    // Bill-payment transaction constants (exact COBOL literals from COBIL00C).
    // These are bill-payment-specific and deliberately NOT placed in the shared
    // CardDemoConstants utility.
    // ------------------------------------------------------------------------

    /** {@code MOVE '02' TO TRAN-TYPE-CD} &mdash; bill-payment transaction type ({@code COBIL00C.cbl} L220). */
    private static final String BILLPAY_TRAN_TYPE_CD = "02";

    /** {@code MOVE 2 TO TRAN-CAT-CD} &mdash; bill-payment transaction category ({@code COBIL00C.cbl} L221). */
    private static final Integer BILLPAY_TRAN_CAT_CD = 2;

    /** {@code MOVE 'POS TERM' TO TRAN-SOURCE} &mdash; transaction source ({@code COBIL00C.cbl} L222). */
    private static final String BILLPAY_TRAN_SOURCE = "POS TERM";

    /** {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC} &mdash; description ({@code COBIL00C.cbl} L223). */
    private static final String BILLPAY_TRAN_DESC = "BILL PAYMENT - ONLINE";

    /** {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME} &mdash; merchant name ({@code COBIL00C.cbl} L227). */
    private static final String BILLPAY_MERCHANT_NAME = "BILL PAYMENT";

    /**
     * {@code MOVE 999999999 TO TRAN-MERCHANT-ID} &mdash; the synthetic bill-payment merchant id
     * ({@code COBIL00C.cbl} L226). Carried through for 100% functional parity (AAP &sect;0.7.1).
     */
    private static final Long BILLPAY_MERCHANT_ID = 999999999L;

    /** {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY} &mdash; merchant city ({@code COBIL00C.cbl} L228). */
    private static final String BILLPAY_MERCHANT_CITY = "N/A";

    /** {@code MOVE 'N/A' TO TRAN-MERCHANT-ZIP} &mdash; merchant ZIP ({@code COBIL00C.cbl} L229). */
    private static final String BILLPAY_MERCHANT_ZIP = "N/A";

    /** Scale (decimal places) applied to every monetary value, mirroring the {@code V99} of the copybooks. */
    private static final int MONEY_SCALE = 2;

    // ------------------------------------------------------------------------
    // Collaborators (constructor injection; all final / immutable).
    // ------------------------------------------------------------------------

    /** Account master access: keyed read + {@code REWRITE}-equivalent save (with {@code @Version} guard). */
    private final AccountRepository accountRepository;

    /** Posted-transaction persistence: the {@code WRITE-TRANSACT-FILE} equivalent. */
    private final TransactionRepository transactionRepository;

    /** Card cross-reference access: resolves the owning {@code XREF-CARD-NUM} for an account. */
    private final CardXrefRepository cardXrefRepository;

    /** Generates the 16-character online transaction id, replacing the legacy browse-and-increment scheme. */
    private final TranIdGenerator tranIdGenerator;

    /**
     * Source of standardized, parity-preserving user-facing messages. The bill-payment messages
     * ({@link MessageService#NOTHING_TO_PAY}, {@link MessageService#INVALID_YN_VALUE}) are
     * {@code public static final} constants and are referenced statically; the bean is injected so the
     * service's message dependency is explicit and consistent with the application's DI conventions.
     */
    private final MessageService messageService;

    /**
     * Creates the bill-payment service with all required collaborators.
     *
     * @param accountRepository     account master repository (read + save)
     * @param transactionRepository posted-transaction repository (save)
     * @param cardXrefRepository    card cross-reference repository (resolve card number by account)
     * @param tranIdGenerator       online transaction-id generator (16-char, zero-padded)
     * @param messageService        standardized message source (bill-payment message constants)
     */
    public BillPaymentService(AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              CardXrefRepository cardXrefRepository,
                              TranIdGenerator tranIdGenerator,
                              MessageService messageService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.tranIdGenerator = tranIdGenerator;
        this.messageService = messageService;
    }

    /**
     * Bill-payment <strong>balance inquiry</strong> &mdash; the read-only half of {@code COBIL00C}'s
     * screen, which displays {@code ACCT-CURR-BAL} (and, by the AAP {@code dto} contract, the available
     * credit) without posting anything.
     *
     * <p>No transaction is written: {@link BillPaymentResponse#transactionId()} and
     * {@link BillPaymentResponse#newBalance()} are {@code null} (the {@code transactionId} is suppressed
     * from the JSON by {@code @JsonInclude(NON_NULL)} on the response record).</p>
     *
     * @param accountId the account identifier to inquire ({@code ACCT-ID}); must not be {@code null}
     * @return a {@link BillPaymentResponse} carrying the current balance and the available credit
     *         ({@code creditLimit - currentBalance}); {@code transactionId} and {@code newBalance} are
     *         {@code null}
     * @throws ResourceNotFoundException (HTTP&nbsp;404) if no account exists for {@code accountId},
     *         reproducing the legacy "Account ID NOT found..." path
     */
    @Transactional(readOnly = true)
    public BillPaymentResponse getBillPaymentInfo(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> ResourceNotFoundException.of("Account", accountId));

        BigDecimal currentBalance = account.getCurrBal();
        BigDecimal availableCredit = computeAvailableCredit(account, currentBalance);

        // Inquiry only: no transaction written, so transactionId and newBalance are null.
        return new BillPaymentResponse(accountId, currentBalance, availableCredit, null, null);
    }

    /**
     * Processes an online bill payment, faithfully reproducing the {@code CONFIRM} control flow of
     * {@code COBIL00C}'s {@code PROCESS-ENTER-KEY} paragraph.
     *
     * <p>Outcomes, in evaluation order:</p>
     * <ol>
     *   <li><strong>Missing confirmation</strong> ({@code request} or {@code confirm} is {@code null})
     *       &rarr; {@link ValidationException} (HTTP&nbsp;400), mirroring the legacy
     *       "Invalid value. Valid values are (Y/N)..." guard. (Bean Validation's {@code @NotNull} on the
     *       DTO normally rejects this first; the explicit check makes the service safe in isolation and
     *       for direct, non-web callers.)</li>
     *   <li><strong>Account not found</strong> &rarr; {@link ResourceNotFoundException} (HTTP&nbsp;404).</li>
     *   <li><strong>Nothing to pay</strong> ({@code currentBalance} is {@code null} or {@code <= 0})
     *       &rarr; {@link BusinessRuleException} (HTTP&nbsp;400), reproducing "You have nothing to pay...".</li>
     *   <li><strong>{@code confirm == false}</strong> &rarr; no payment is posted; the current balance and
     *       available credit are returned with {@code transactionId}/{@code newBalance} {@code null}
     *       (the legacy {@code CONFIRM = 'N'} path).</li>
     *   <li><strong>{@code confirm == true}</strong> &rarr; the full current balance is paid: a
     *       {@code 'BILL PAYMENT - ONLINE'} transaction is written, the account balance is reduced by the
     *       payment amount (to zero), and the written {@code transactionId} and post-payment
     *       {@code newBalance} are returned.</li>
     * </ol>
     *
     * <p>The {@code availableCredit} returned in every non-throwing path is computed from the
     * <em>pre-payment</em> balance ({@code creditLimit - currentBalance}), matching the value the legacy
     * screen showed the operator.</p>
     *
     * @param accountId the account identifier whose balance is being paid ({@code ACCT-ID}); must not be
     *                  {@code null}
     * @param request   the confirmation request body; its {@code confirm} flag must be non-{@code null}
     * @return a {@link BillPaymentResponse} describing the inquiry result (no payment) or the completed
     *         payment
     * @throws ValidationException       (HTTP&nbsp;400) if {@code request} or its {@code confirm} flag is
     *                                   {@code null}
     * @throws ResourceNotFoundException (HTTP&nbsp;404) if the account does not exist, or (on the
     *                                   {@code confirm == true} path) if no card cross-reference exists
     *                                   for the account
     * @throws BusinessRuleException     (HTTP&nbsp;400) if the account has nothing to pay
     *                                   ({@code currentBalance <= 0})
     */
    @Transactional
    public BillPaymentResponse processBillPayment(Long accountId, BillPaymentRequest request) {
        // 1. Confirmation must be present (Y/N decision). Mirrors the legacy invalid-confirm guard.
        if (request == null || request.confirm() == null) {
            throw new ValidationException(MessageService.INVALID_YN_VALUE);
        }

        // 2. Load the account (CICS READ ... UPDATE equivalent).
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> ResourceNotFoundException.of("Account", accountId));

        BigDecimal currentBalance = account.getCurrBal();

        // 3. Nothing-to-pay guard: ACCT-CURR-BAL <= ZEROS -> "You have nothing to pay...".
        if (currentBalance == null || currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException(MessageService.NOTHING_TO_PAY);
        }

        // Available credit shown to the user is based on the pre-payment balance.
        BigDecimal availableCredit = computeAvailableCredit(account, currentBalance);

        // 4. CONFIRM = 'N' (false): do not post; return current state (legacy clear-screen path).
        if (Boolean.FALSE.equals(request.confirm())) {
            return new BillPaymentResponse(accountId, currentBalance, availableCredit, null, null);
        }

        // 5. CONFIRM = 'Y' (true): pay the full balance.

        // 5a. Resolve the owning card number from the card cross-reference (READ-CXACAIX-FILE ->
        //     XREF-CARD-NUM). The legacy program keyed the alternate index by account id; the first
        //     cross-reference row for the account supplies the card number used on the transaction.
        String cardNum = cardXrefRepository.findByXrefAcctId(accountId, PageRequest.of(0, 1))
                .getContent().stream()
                .findFirst()
                .map(CardXref::getXrefCardNum)
                .orElseThrow(() -> ResourceNotFoundException.of("Card cross-reference for account", accountId));

        // 5b. The payment is always the FULL current balance (MOVE ACCT-CURR-BAL TO TRAN-AMT).
        BigDecimal paymentAmount = currentBalance;

        // 5c. Build the bill-payment transaction with the exact COBOL literal field values.
        Transaction tx = new Transaction();
        tx.setTranId(tranIdGenerator.generateTransactionId());
        tx.setTypeCd(BILLPAY_TRAN_TYPE_CD);
        tx.setCatCd(BILLPAY_TRAN_CAT_CD);
        tx.setSource(BILLPAY_TRAN_SOURCE);
        tx.setDescription(BILLPAY_TRAN_DESC);
        tx.setAmt(paymentAmount);
        tx.setCardNum(cardNum);
        tx.setAcctId(accountId);
        tx.setMerchantId(BILLPAY_MERCHANT_ID);
        tx.setMerchantName(BILLPAY_MERCHANT_NAME);
        tx.setMerchantCity(BILLPAY_MERCHANT_CITY);
        tx.setMerchantZip(BILLPAY_MERCHANT_ZIP);
        LocalDateTime now = LocalDateTime.now();
        tx.setOrigTs(now);
        tx.setProcTs(now);

        // 5d. WRITE the transaction (WRITE-TRANSACT-FILE).
        transactionRepository.save(tx);

        // 5e. COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (drives the balance to zero) and REWRITE.
        BigDecimal newBalance = currentBalance.subtract(paymentAmount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        account.setCurrBal(newBalance);
        accountRepository.save(account);

        // 5f/5g. Return the completed payment: pre-payment balance, available credit, written
        //        transaction id, and post-payment balance.
        return new BillPaymentResponse(accountId, currentBalance, availableCredit, tx.getTranId(), newBalance);
    }

    /**
     * Computes available credit as {@code creditLimit - currentBalance}, scaled to
     * {@value #MONEY_SCALE} places with {@link RoundingMode#HALF_UP} (AAP &sect;0.4.1.3 bill-payment
     * contract: "Available credit = credit limit &minus; current balance").
     *
     * @param account        the account supplying the credit limit ({@code ACCT-CREDIT-LIMIT})
     * @param currentBalance the (pre-payment) current balance to subtract ({@code ACCT-CURR-BAL})
     * @return the available credit as a {@link BigDecimal} of scale {@value #MONEY_SCALE}
     */
    private BigDecimal computeAvailableCredit(Account account, BigDecimal currentBalance) {
        return account.getCreditLimit()
                .subtract(currentBalance)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}

package com.carddemo.service;

import com.carddemo.dto.billpayment.BillPaymentRequest;
import com.carddemo.dto.billpayment.BillPaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.BigDecimalUtil;
import com.carddemo.util.DateConversionUtil;
import com.carddemo.util.TransactionIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Online bill-payment service &mdash; the stateless replacement for the CICS online program
 * {@code app/cbl/COBIL00C.cbl} (TRANID {@code CB00}, "Bill Payment").
 *
 * <p>COBIL00C lets a cardholder pay down an account's current balance. The legacy program, after
 * reading the account and confirming the request, posts a single payment {@code Transaction} against
 * the account's card and reduces {@code ACCT-CURR-BAL} by the paid amount. This service reproduces
 * that flow exactly, in the same order, with the same COBOL message literals, inside a single
 * {@code @Transactional} unit of work (PR-24) that mirrors the implicit CICS {@code SYNCPOINT}
 * spanning the {@code WRITE-TRANSACT-FILE} + {@code UPDATE-ACCTDAT-FILE} pair.</p>
 *
 * <h2>COBIL00C flow preserved (in order)</h2>
 * <ol>
 *   <li><b>Read the account</b> ({@code READ-ACCTDAT-FILE}). Absent &rarr;
 *       {@code AccountNotFoundException} carrying the verbatim COBIL00C message
 *       {@code "Account ID NOT found..."} (L361/L392/L425) &rarr; HTTP 404.</li>
 *   <li><b>Nothing-to-pay guard</b> ({@code IF ACCT-CURR-BAL <= ZEROS}, L198-201). A zero or
 *       negative current balance &rarr; {@code IllegalStateException}
 *       {@code "You have nothing to pay..."} &rarr; HTTP 422.</li>
 *   <li><b>Confirmation guard</b> ({@code IF CONF-PAY-YES}, L210/L236-238). When the {@code Y/N}
 *       confirmation flag is not {@code "Y"} &rarr; {@code IllegalStateException}
 *       {@code "Confirm to make a bill payment..."} &rarr; HTTP 422. (Bean validation on
 *       {@code BillPaymentRequest} has already rejected any value other than {@code Y}/{@code N}
 *       with HTTP 400, mirroring the COBIL00C {@code WHEN OTHER} "Invalid value" edit.)</li>
 *   <li><b>Resolve the card</b> ({@code READ-CXACAIX-FILE}, L211). The account's card cross-reference
 *       supplies {@code XREF-CARD-NUM}. No cross-reference row &rarr;
 *       {@code AccountNotFoundException} {@code "Account ID NOT found..."} &rarr; HTTP 404.</li>
 *   <li><b>Build and write the payment transaction</b> ({@code WRITE-TRANSACT-FILE}, L218-233) with
 *       the exact COBOL field constants (see below).</li>
 *   <li><b>Reduce the balance and rewrite the account</b>
 *       ({@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} then {@code UPDATE-ACCTDAT-FILE},
 *       L234-235), persisted with {@code saveAndFlush} so JPA {@code @Version} optimistic locking
 *       is exercised within the transaction (PR-22).</li>
 * </ol>
 *
 * <h2>Transaction field constants (COBIL00C L219-232, preserved verbatim)</h2>
 * <ul>
 *   <li>{@code TRAN-TYPE-CD = '02'} &rarr; {@code typeCd = "02"};</li>
 *   <li>{@code TRAN-CAT-CD = 2} (PIC 9(04)) &rarr; {@code categoryCd = "0002"} (fixed-width,
 *       leading zeros preserved; the pair {@code ('02','0002')} = "Electronic payment" exists in
 *       the seeded {@code transaction_categories} reference data);</li>
 *   <li>{@code TRAN-SOURCE = 'POS TERM'} &rarr; {@code source = "POS TERM"};</li>
 *   <li>{@code TRAN-DESC = 'BILL PAYMENT - ONLINE'} &rarr; {@code description = "BILL PAYMENT -
 *       ONLINE"};</li>
 *   <li>{@code TRAN-AMT = ACCT-CURR-BAL} (the legacy program <em>always</em> paid the full current
 *       balance) &rarr; for the default/{@code FULL} method the paid amount is the full
 *       pre-payment balance; see "Payment method" below;</li>
 *   <li>{@code TRAN-CARD-NUM = XREF-CARD-NUM} &rarr; {@code cardNum} from the cross-reference;</li>
 *   <li>{@code TRAN-MERCHANT-ID = 999999999} &rarr; {@code merchantId = 999999999L};</li>
 *   <li>{@code TRAN-MERCHANT-NAME = 'BILL PAYMENT'} &rarr; {@code merchantName = "BILL PAYMENT"};</li>
 *   <li>{@code TRAN-MERCHANT-CITY = 'N/A'} &rarr; {@code merchantCity = "N/A"};</li>
 *   <li>{@code TRAN-MERCHANT-ZIP = 'N/A'} &rarr; {@code merchantZip = "N/A"};</li>
 *   <li>{@code TRAN-ORIG-TS = TRAN-PROC-TS = WS-TIMESTAMP} &rarr; {@code origTimestamp ==
 *       procTimestamp} (the same instant, L231-232).</li>
 * </ul>
 *
 * <h2>Payment method (modernization detail honored from {@code BillPaymentRequest})</h2>
 * <p>The legacy COBIL00C had no payment-method concept &mdash; it always paid the full balance. The
 * {@code BillPaymentRequest} DTO adds an optional {@code paymentMethod} hint whose own contract
 * documents the rule this service implements:</p>
 * <ul>
 *   <li>{@code paymentMethod} null/blank or {@code FULL} &rarr; pay the full pre-payment balance
 *       (exact COBIL00C behavior, {@code TRAN-AMT = ACCT-CURR-BAL});</li>
 *   <li>{@code paymentMethod} {@code PARTIAL} or {@code MINIMUM} &rarr; pay the request
 *       {@code amount} (the DTO requires a positive, scale-2 amount).</li>
 * </ul>
 * <p>No new behavior beyond what the existing DTO already advertises is introduced (AAP &sect;0.7.2
 * &mdash; no feature additions).</p>
 *
 * <h2>Identifier and timestamp generation (PR-10 / PR-11)</h2>
 * <p>Although COBIL00C derived the next id by reading the last {@code TRANSACT} row and adding one,
 * this service uses the same PR-10 generation idiom as {@link TransactionService#addTransaction} so
 * online ids are unique across concurrent requests: the 16-character {@code tranId} is
 * {@code parmDate(10) + suffix(6)}, where the suffix is drawn from the PostgreSQL
 * {@code transaction_id_seq} via {@link TransactionRepository#nextTransactionIdSuffix()} and the
 * date prefix is the {@code yyyy-MM-dd} head of the 26-character DB2 timestamp (PR-11) produced by
 * {@link DateConversionUtil#nowAsDb2Timestamp()}.</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-10</b> &mdash; 16-character {@code tranId} via {@link TransactionIdGenerator#nextOnlineId(String, long)}.</li>
 *   <li><b>PR-11</b> &mdash; 26-character DB2 timestamp for {@code origTimestamp}/{@code procTimestamp}
 *       and the response {@code processedAt}.</li>
 *   <li><b>PR-16</b> &mdash; every monetary value is {@link BigDecimal} scaled to 2 with
 *       {@code HALF_UP} via {@link BigDecimalUtil}; no {@code float}/{@code double}.</li>
 *   <li><b>PR-22</b> &mdash; the account update uses {@code saveAndFlush} so {@code @Version}
 *       optimistic locking is enforced; a concurrent modification surfaces as
 *       {@code ObjectOptimisticLockingFailureException} &rarr; HTTP 409.</li>
 *   <li><b>PR-24</b> &mdash; the transaction write and the account balance update run in one
 *       {@code @Transactional} unit of work (the CICS {@code SYNCPOINT} boundary).</li>
 *   <li><b>PR-29</b> &mdash; constructor injection via Lombok {@link RequiredArgsConstructor} over
 *       {@code final} fields; no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (COBIL00C bill-payment program).
 *
 * @see com.carddemo.controller.BillPaymentController the REST adapter that exposes this service
 * @see BillPaymentRequest the request payload (account id, amount, method, Y/N confirmation)
 * @see BillPaymentResponse the response payload (tran id, balances, available credit, message)
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillPaymentService {

    /**
     * Number of leading characters of the 26-character DB2 timestamp that form the {@code yyyy-MM-dd}
     * date prefix and, in turn, the 10-character {@code parmDate} head of the generated transaction
     * id (PR-10). Matches the {@code DALYTRAN-ORIG-TS(1:10)} slice used by the COBOL programs and the
     * identical constant in {@link TransactionService}.
     */
    private static final int DATE_PREFIX_LENGTH = 10;

    /** Default/explicit "pay the full balance" method hint (the exact COBIL00C behavior). */
    private static final String METHOD_FULL = "FULL";

    /** Verbatim COBIL00C "nothing to pay" message (L201) &rarr; HTTP 422. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** Verbatim COBIL00C "confirm to pay" message (L237) &rarr; HTTP 422. */
    private static final String MSG_CONFIRM_PAYMENT = "Confirm to make a bill payment...";

    /** Verbatim COBIL00C "account not found" message (L361/L392/L425) &rarr; HTTP 404. */
    private static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** COBIL00C {@code TRAN-TYPE-CD = '02'} (L220). */
    private static final String TRAN_TYPE_CD = "02";

    /** COBIL00C {@code TRAN-CAT-CD = 2} (PIC 9(04), L221) &rarr; fixed-width {@code "0002"}. */
    private static final String TRAN_CAT_CD = "0002";

    /** COBIL00C {@code TRAN-SOURCE = 'POS TERM'} (L222). */
    private static final String TRAN_SOURCE = "POS TERM";

    /** COBIL00C {@code TRAN-DESC = 'BILL PAYMENT - ONLINE'} (L223). */
    private static final String TRAN_DESC = "BILL PAYMENT - ONLINE";

    /** COBIL00C {@code TRAN-MERCHANT-ID = 999999999} (L226). */
    private static final long TRAN_MERCHANT_ID = 999999999L;

    /** COBIL00C {@code TRAN-MERCHANT-NAME = 'BILL PAYMENT'} (L227). */
    private static final String TRAN_MERCHANT_NAME = "BILL PAYMENT";

    /** COBIL00C {@code TRAN-MERCHANT-CITY = 'N/A'} (L228). */
    private static final String TRAN_MERCHANT_CITY = "N/A";

    /** COBIL00C {@code TRAN-MERCHANT-ZIP = 'N/A'} (L229). */
    private static final String TRAN_MERCHANT_ZIP = "N/A";

    /**
     * Account master repository ({@code ACCTDAT}). Used to read the account
     * ({@code READ-ACCTDAT-FILE}) and to rewrite it with the reduced balance
     * ({@code UPDATE-ACCTDAT-FILE}) under {@code @Version} optimistic locking (PR-22).
     */
    private final AccountRepository accountRepository;

    /**
     * Card cross-reference repository ({@code CXACAIX}). Supplies the {@code XREF-CARD-NUM} for the
     * account being paid ({@code READ-CXACAIX-FILE}, COBIL00C L211).
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Transaction master repository ({@code TRANSACT}). Persists the payment transaction
     * ({@code WRITE-TRANSACT-FILE}) and supplies the monotonic online id suffix (PR-10).
     */
    private final TransactionRepository transactionRepository;

    /**
     * Generates the 16-character transaction id as {@code parmDate(10) + suffix(6)} (PR-10),
     * shared with the online transaction-add path for a consistent id format.
     */
    private final TransactionIdGenerator transactionIdGenerator;

    /**
     * Processes an online bill payment against the given account, reproducing COBIL00C.
     *
     * <p>Executes the COBIL00C flow in order: read account &rarr; nothing-to-pay guard &rarr;
     * confirmation guard &rarr; resolve card &rarr; write payment transaction &rarr; reduce balance
     * and rewrite account &mdash; all within one {@code @Transactional} unit of work (PR-24). Every
     * monetary value is {@link BigDecimal} scaled to 2 (PR-16); the account rewrite uses
     * {@code saveAndFlush} so {@code @Version} optimistic locking is enforced (PR-22).</p>
     *
     * @param acctId  the account primary key from the request path (the authoritative account id;
     *                {@code BillPaymentController} has already verified the body's
     *                {@code accountId} matches this path value)
     * @param request the validated bill-payment request (amount, optional method hint, and the
     *                {@code Y}/{@code N} confirmation flag)
     * @return a {@link BillPaymentResponse} echoing the generated transaction id, the processed
     *         amount, the previous and new balances, the resulting available credit, the 26-character
     *         DB2 {@code processedAt} timestamp, and the verbatim COBIL00C success message
     * @throws AccountNotFoundException (HTTP 404) when the account or its card cross-reference is
     *                                  absent &mdash; carries the verbatim
     *                                  {@code "Account ID NOT found..."} message
     * @throws IllegalStateException    (HTTP 422) when the balance is not positive
     *                                  ({@code "You have nothing to pay..."}) or the request is not
     *                                  confirmed ({@code "Confirm to make a bill payment..."})
     */
    @Transactional
    public BillPaymentResponse processPayment(Long acctId, BillPaymentRequest request) {
        // accountId is a non-sensitive surrogate; no PII is logged here (the card number, resolved
        // below from the cross-reference, is never logged).
        log.info("Processing bill payment for accountId={}", acctId);

        // STEP 1 — READ-ACCTDAT-FILE (COBIL00C). Absent account -> verbatim "Account ID NOT found..."
        // (-> 404). Reason code 101 is retained by AccountNotFoundException.withMessage(...).
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> AccountNotFoundException.withMessage(MSG_ACCOUNT_NOT_FOUND));

        // STEP 2 — nothing-to-pay guard (COBIL00C L198-201: IF ACCT-CURR-BAL <= ZEROS). Scale to 2
        // (PR-16) and reject a zero/negative balance with the verbatim message (-> 422).
        BigDecimal previousBalance = BigDecimalUtil.ensureScaleTwo(account.getCurrBal());
        if (!BigDecimalUtil.isPositive(previousBalance)) {
            throw new IllegalStateException(MSG_NOTHING_TO_PAY);
        }

        // STEP 3 — confirmation guard (COBIL00C L210 CONF-PAY-YES / L236-238 ELSE). Bean validation
        // has already constrained the value to exactly 'Y' or 'N'; anything other than 'Y' is an
        // unconfirmed request -> verbatim "Confirm to make a bill payment..." (-> 422).
        if (!"Y".equals(request.getConfirmation())) {
            throw new IllegalStateException(MSG_CONFIRM_PAYMENT);
        }

        // STEP 4 — READ-CXACAIX-FILE (COBIL00C L211): resolve the account's card number from the
        // cross-reference. No cross-reference row -> verbatim "Account ID NOT found..." (-> 404),
        // matching the COBIL00C READ-CXACAIX error path.
        CardXref xref = cardXrefRepository.findByAccountId(acctId).stream()
                .findFirst()
                .orElseThrow(() -> AccountNotFoundException.withMessage(MSG_ACCOUNT_NOT_FOUND));
        String cardNum = xref.getXrefCardNum();

        // Determine the amount to pay. COBIL00C always paid the full balance (TRAN-AMT =
        // ACCT-CURR-BAL); the optional paymentMethod hint preserves that as the FULL/default case
        // and lets PARTIAL/MINIMUM pay the supplied (already positive, scale-2) request amount.
        String method = request.getPaymentMethod();
        String effectiveMethod = (method == null || method.isBlank())
                ? METHOD_FULL
                : method.trim().toUpperCase();
        BigDecimal paymentAmount = METHOD_FULL.equals(effectiveMethod)
                ? previousBalance
                : BigDecimalUtil.ensureScaleTwo(request.getAmount());

        // STEP 5 — generate the id and timestamps (PR-10 / PR-11), using the same idiom as
        // TransactionService.addTransaction. origTimestamp == procTimestamp (COBIL00C L231-232).
        String origTs = DateConversionUtil.nowAsDb2Timestamp();
        String tranDatePart = origTs.length() >= DATE_PREFIX_LENGTH
                ? origTs.substring(0, DATE_PREFIX_LENGTH)
                : DateConversionUtil.todayAsIso();
        long onlineSuffix = transactionRepository.nextTransactionIdSuffix();
        String tranId = transactionIdGenerator.nextOnlineId(tranDatePart, onlineSuffix); // PR-10
        LocalDateTime ts = DateConversionUtil.fromDb2Timestamp(origTs); // PR-11

        // STEP 5 (cont.) — WRITE-TRANSACT-FILE (COBIL00C L218-233). Field constants preserved
        // verbatim from the COBOL MOVEs.
        Transaction transaction = Transaction.builder()
                .tranId(tranId)
                .typeCd(TRAN_TYPE_CD)
                .categoryCd(TRAN_CAT_CD)
                .source(TRAN_SOURCE)
                .description(TRAN_DESC)
                .amount(paymentAmount)
                .cardNum(cardNum)
                .merchantId(TRAN_MERCHANT_ID)
                .merchantName(TRAN_MERCHANT_NAME)
                .merchantCity(TRAN_MERCHANT_CITY)
                .merchantZip(TRAN_MERCHANT_ZIP)
                .origTimestamp(ts)
                .procTimestamp(ts)
                .build();
        transactionRepository.save(transaction);

        // STEP 6 — COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (COBIL00C L234) then
        // UPDATE-ACCTDAT-FILE (L235). saveAndFlush exercises @Version optimistic locking inside the
        // transaction (PR-22); a concurrent change surfaces as ObjectOptimisticLockingFailureException
        // (-> 409 via GlobalExceptionHandler).
        BigDecimal newBalance = BigDecimalUtil.scaledSubtract(previousBalance, paymentAmount);
        account.setCurrBal(newBalance);
        accountRepository.saveAndFlush(account);

        // Build the response. availableCredit = creditLimit - newBalance (AAP §0.4.1.1 / DTO @Schema).
        BigDecimal availableCredit = BigDecimalUtil.scaledSubtract(
                BigDecimalUtil.ensureScaleTwo(account.getCreditLimit()), newBalance);

        // Verbatim COBIL00C success message (L527-531): concatenating 'Payment successful. ' and
        // ' Your Transaction ID is ' yields TWO spaces after the period; the id and a trailing '.'
        // complete the message.
        String successMessage = "Payment successful.  Your Transaction ID is " + tranId + ".";

        log.info("Bill payment posted for accountId={} tranId={} newBalance={}",
                acctId, tranId, newBalance);

        return new BillPaymentResponse(
                tranId,
                acctId,
                paymentAmount,
                previousBalance,
                newBalance,
                availableCredit,
                origTs,
                successMessage);
    }
}

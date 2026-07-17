/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.common.util.IdGenerator;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.type.Money;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Online bill-payment service &mdash; the Java re-platform of the CardDemo COBOL
 * program {@code COBIL00C} (Bill Pay, CICS transaction {@code CB00}, source
 * {@code legacy/cbl/COBIL00C.cbl}). It pays an account balance <em>in full</em>
 * by posting a single bill-payment transaction and reducing the account's
 * current balance to zero, reproducing the observable behavior of the legacy
 * program without any feature expansion (AAP &sect;0.5.3).
 *
 * <h2>What the legacy program does</h2>
 * The COBOL {@code PROCESS-ENTER-KEY} paragraph (L154-L244) drives the flow:
 * it validates the account id, evaluates the confirmation flag, reads the
 * account, guards against a non-positive balance, and &mdash; only when the
 * operator confirms &mdash; resolves the card via the cross-reference, generates
 * the next transaction id with a reverse browse, writes the transaction, and
 * rewrites the account with the reduced balance. This service preserves that
 * exact control flow and evaluation order, and every caller-visible message is
 * reproduced byte-for-byte (AAP &sect;0.9.2).
 *
 * <h2>Pseudo-conversational translation (DTO-free)</h2>
 * The legacy program is pseudo-conversational: each branch either sets a
 * {@code WS-MESSAGE} and re-displays the map or posts the payment and shows a
 * success message. There is no 3270 terminal here, so every outcome is returned
 * as a transport-neutral {@link BillPaymentResult} value object rather than a
 * screen send. Precondition/validation outcomes (blank id, invalid confirm,
 * nothing to pay, "confirm to pay" prompt) are carried in the result exactly as
 * the COBOL placed them in {@code WS-MESSAGE}; a genuinely missing record (the
 * CICS {@code NOTFND} paths) surfaces as the typed
 * {@link RecordNotFoundException}.
 *
 * <h2>Monetary fidelity and atomicity</h2>
 * The payment amount and the balance reduction are computed with
 * {@link java.math.BigDecimal} at scale 2 through the {@link Money} value object
 * ({@link java.math.RoundingMode#HALF_UP}); binary floating point is never used
 * (AAP &sect;0.7.1 H3). The whole read&rarr;post&rarr;update sequence executes
 * inside a single {@link Transactional} method so that the transaction insert
 * and the balance rewrite either both commit or both roll back, reproducing the
 * integrity of the COBOL online READ-UPDATE-REWRITE cycle guarded by the
 * account entity's optimistic-lock {@code @Version} column (AAP &sect;0.7.1 H6).
 *
 * <h2>Collaborators</h2>
 * {@link AccountRepository}, {@link CardXrefRepository} and
 * {@link TransactionRepository} are constructor-injected (no field injection).
 * {@link IdGenerator} and {@link DateUtils} are stateless static utilities and
 * are invoked statically, never injected.
 *
 * <p>This class holds no mutable state and is therefore thread-safe; each call to
 * {@link #payBill(String, String)} operates only on its own local variables and
 * the injected singleton repositories.</p>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Service
public class BillPaymentService {

    // ------------------------------------------------------------------------
    // Caller-visible message contracts (COBOL WS-MESSAGE literals, verbatim).
    // These are behavioral-parity contracts (AAP 0.9.2) and must not be
    // paraphrased. They are public so tests can assert against the exact text.
    // ------------------------------------------------------------------------

    /**
     * Shown when the submitted account id is blank. Verbatim COBOL literal from
     * {@code PROCESS-ENTER-KEY} (L161): {@code 'Acct ID can NOT be empty...'}
     * (three trailing dots).
     */
    public static final String ACCT_ID_EMPTY_MESSAGE = "Acct ID can NOT be empty...";

    /**
     * Shown when the confirmation flag is neither {@code Y}/{@code y},
     * {@code N}/{@code n}, nor blank. Verbatim COBOL literal from
     * {@code PROCESS-ENTER-KEY} (L187):
     * {@code 'Invalid value. Valid values are (Y/N)...'}.
     */
    public static final String INVALID_CONFIRM_MESSAGE = "Invalid value. Valid values are (Y/N)...";

    /**
     * Shown when the account's current balance is not positive, so there is
     * nothing to pay. Verbatim COBOL literal from {@code PROCESS-ENTER-KEY}
     * (L201): {@code 'You have nothing to pay...'}.
     */
    public static final String NOTHING_TO_PAY_MESSAGE = "You have nothing to pay...";

    /**
     * Shown when the account and balance have been read but the operator has not
     * yet confirmed (the confirmation flag is blank): the balance is displayed
     * and the operator is prompted to confirm. Verbatim COBOL literal from
     * {@code PROCESS-ENTER-KEY} (L237):
     * {@code 'Confirm to make a bill payment...'}.
     */
    public static final String CONFIRM_PROMPT_MESSAGE = "Confirm to make a bill payment...";

    /**
     * Carried by the {@link RecordNotFoundException} thrown when the account does
     * not exist. Verbatim COBOL literal from {@code READ-ACCTDAT-FILE} (L361),
     * emitted on the CICS {@code DFHRESP(NOTFND)} path.
     */
    public static final String ACCOUNT_NOT_FOUND_MESSAGE = "Account ID NOT found...";

    /**
     * Carried by the {@link RecordNotFoundException} thrown when no card
     * cross-reference exists for the account. Verbatim COBOL literal from
     * {@code READ-CXACAIX-FILE} (L432): {@code 'Unable to lookup XREF AIX file...'}.
     */
    public static final String XREF_NOT_FOUND_MESSAGE = "Unable to lookup XREF AIX file...";

    /**
     * Leading fragment of the success message. Verbatim COBOL {@code STRING}
     * literal from {@code WRITE-TRANSACT-FILE} (L527):
     * {@code 'Payment successful. '} &mdash; note the single trailing space,
     * which is significant.
     */
    private static final String SUCCESS_PREFIX = "Payment successful. ";

    /**
     * Middle fragment of the success message. Verbatim COBOL {@code STRING}
     * literal from {@code WRITE-TRANSACT-FILE} (L528):
     * {@code ' Your Transaction ID is '} &mdash; note the leading and trailing
     * spaces, both significant. Concatenating {@link #SUCCESS_PREFIX} with this
     * fragment reproduces the COBOL two-space gap after {@code "successful."}.
     */
    private static final String SUCCESS_INFIX = " Your Transaction ID is ";

    // ------------------------------------------------------------------------
    // Fixed bill-payment transaction attributes (COBOL PROCESS-ENTER-KEY
    // L218-L232). Every literal is preserved exactly as the legacy program set
    // it on the TRAN-RECORD before writing.
    // ------------------------------------------------------------------------

    /** {@code MOVE '02' TO TRAN-TYPE-CD} (L220): bill-payment transaction type. */
    private static final String BILL_PAYMENT_TYPE_CD = "02";

    /** {@code MOVE 2 TO TRAN-CAT-CD} (L221): bill-payment transaction category. */
    private static final int BILL_PAYMENT_CATEGORY_CD = 2;

    /** {@code MOVE 'POS TERM' TO TRAN-SOURCE} (L222): origination source. */
    private static final String BILL_PAYMENT_SOURCE = "POS TERM";

    /** {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC} (L223): description. */
    private static final String BILL_PAYMENT_DESCRIPTION = "BILL PAYMENT - ONLINE";

    /** {@code MOVE 999999999 TO TRAN-MERCHANT-ID} (L226): synthetic merchant id. */
    private static final long BILL_PAYMENT_MERCHANT_ID = 999_999_999L;

    /** {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME} (L227): merchant name. */
    private static final String BILL_PAYMENT_MERCHANT_NAME = "BILL PAYMENT";

    /** {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY} (L228): merchant city. */
    private static final String BILL_PAYMENT_MERCHANT_CITY = "N/A";

    /** {@code MOVE 'N/A' TO TRAN-MERCHANT-ZIP} (L229): merchant ZIP. */
    private static final String BILL_PAYMENT_MERCHANT_ZIP = "N/A";

    // ------------------------------------------------------------------------
    // Injected collaborators (constructor injection only).
    // ------------------------------------------------------------------------

    private final AccountRepository accountRepository;
    private final CardXrefRepository cardXrefRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its required repositories.
     *
     * <p>Uses constructor injection so the collaborators are {@code final} and
     * the instance is fully initialized and immutable once constructed. The
     * constructor performs no work beyond field assignment (no overridable
     * method is invoked), so no partially-constructed instance escapes.</p>
     *
     * @param accountRepository     access to the {@code account} table
     *                              (COBOL {@code ACCTDAT} file); must not be {@code null}
     * @param cardXrefRepository    access to the {@code card_xref} table
     *                              (COBOL {@code CXACAIX} alternate index); must not be {@code null}
     * @param transactionRepository access to the {@code transaction} table
     *                              (COBOL {@code TRANSACT} file); must not be {@code null}
     */
    public BillPaymentService(AccountRepository accountRepository,
                              CardXrefRepository cardXrefRepository,
                              TransactionRepository transactionRepository) {
        this.accountRepository = Objects.requireNonNull(
                accountRepository, "accountRepository must not be null");
        this.cardXrefRepository = Objects.requireNonNull(
                cardXrefRepository, "cardXrefRepository must not be null");
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository must not be null");
    }

    /**
     * Pays an account balance in full, reproducing the COBOL {@code COBIL00C}
     * {@code PROCESS-ENTER-KEY} paragraph (L154-L244) exactly &mdash; including
     * its validation order and short-circuit behavior.
     *
     * <p>The whole read&rarr;post&rarr;update sequence runs inside a single
     * {@link Transactional} boundary: if any step fails (for example an
     * optimistic-lock conflict on the account rewrite, or a unique-key violation
     * on the transaction insert), both the transaction insert and the balance
     * update roll back together, preserving READ-UPDATE-REWRITE integrity
     * (AAP &sect;0.7.1 H6).</p>
     *
     * <h2>Behavior (COBOL branch &rarr; result), in evaluation order</h2>
     * <ol>
     *   <li><strong>Blank account id</strong> ({@code PROCESS-ENTER-KEY} L159-L164,
     *       {@code ACTIDINI = SPACES OR LOW-VALUES}) &rarr; a non-posting result
     *       carrying {@link #ACCT_ID_EMPTY_MESSAGE}. No account read.</li>
     *   <li><strong>Confirmation flag</strong> (the {@code EVALUATE CONFIRMI} at
     *       L173-L191):
     *       <ul>
     *         <li>{@code Y}/{@code y} &rarr; confirm to pay ({@code SET CONF-PAY-YES})
     *             and proceed to read the account.</li>
     *         <li>{@code N}/{@code n} &rarr; decline: the COBOL clears the screen
     *             ({@code CLEAR-CURRENT-SCREEN}) and sets the error flag, so
     *             nothing is read or posted. Returned as a non-posting result with
     *             an empty message. No account read.</li>
     *         <li>blank ({@code SPACES}/{@code LOW-VALUES}) &rarr; read the account
     *             and display its balance without posting.</li>
     *         <li>anything else &rarr; a non-posting result carrying
     *             {@link #INVALID_CONFIRM_MESSAGE}. No account read &mdash; this
     *             check short-circuits before the account is read, exactly as the
     *             COBOL {@code WHEN OTHER} branch does.</li>
     *       </ul></li>
     *   <li><strong>Account read</strong> ({@code READ-ACCTDAT-FILE} L343-L372,
     *       reached only for {@code Y}/{@code y} and blank) &rarr; a missing
     *       account throws {@link RecordNotFoundException} carrying
     *       {@link #ACCOUNT_NOT_FOUND_MESSAGE} (the CICS {@code DFHRESP(NOTFND)}
     *       path).</li>
     *   <li><strong>Non-positive balance</strong> ({@code PROCESS-ENTER-KEY}
     *       L198-L205, {@code ACCT-CURR-BAL &lt;= ZEROS}) &rarr; a non-posting
     *       result carrying the balance and {@link #NOTHING_TO_PAY_MESSAGE}.</li>
     *   <li><strong>Confirmed payment</strong> ({@code PROCESS-ENTER-KEY}
     *       L210-L235, {@code IF CONF-PAY-YES}) &rarr; resolve the card via the
     *       cross-reference ({@code READ-CXACAIX-FILE}; a missing cross-reference
     *       throws {@link RecordNotFoundException} carrying
     *       {@link #XREF_NOT_FOUND_MESSAGE}), generate the next transaction id by
     *       the reverse-browse max-plus-one rule, write the transaction, reduce
     *       the balance ({@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT},
     *       L234) and rewrite the account. Returned as a posted result with the
     *       success message and the generated id.</li>
     *   <li><strong>Not yet confirmed</strong> (blank flag; the {@code ELSE} at
     *       L236-L239) &rarr; a non-posting result carrying the balance and
     *       {@link #CONFIRM_PROMPT_MESSAGE}.</li>
     * </ol>
     *
     * <p>All monetary arithmetic uses {@link Money} at scale 2
     * ({@link java.math.RoundingMode#HALF_UP}); no {@code double}/{@code float} is
     * used (AAP &sect;0.7.1 H3).</p>
     *
     * @param accountId   the account id typed on the screen ({@code ACTIDINI}); a
     *                    {@code null}, empty, or all-whitespace value is treated as
     *                    blank
     * @param confirmFlag the confirmation flag ({@code CONFIRMI}); {@code null} or
     *                    all-whitespace is treated as the COBOL blank
     *                    ({@code SPACES}/{@code LOW-VALUES}) case
     * @return the outcome of the attempt; never {@code null}
     * @throws RecordNotFoundException if the account does not exist, or if a
     *                                 confirmed payment finds no card cross-reference
     *                                 for the account (the COBOL {@code NOTFND} paths)
     */
    @Transactional
    public BillPaymentResult payBill(String accountId, String confirmFlag) {
        // --- COBOL PROCESS-ENTER-KEY L159-L167: blank account id is rejected
        // before anything else, and no file is read. -------------------------
        if (accountId == null || accountId.isBlank()) {
            return BillPaymentResult.message(ACCT_ID_EMPTY_MESSAGE);
        }
        String trimmedAccountId = accountId.trim();

        // --- COBOL EVALUATE CONFIRMI (L173-L191). The confirmation flag is a
        // single character (CONFIRMI PIC X(1)); null/blank maps to the COBOL
        // SPACES/LOW-VALUES case. Deciding the action here, before the account
        // read, preserves the COBOL short-circuits: WHEN OTHER and WHEN 'N'/'n'
        // never read the account. -------------------------------------------
        String confirm = (confirmFlag == null) ? "" : confirmFlag.trim();
        boolean confirmToPay;
        if ("Y".equals(confirm) || "y".equals(confirm)) {
            // WHEN 'Y'/'y': SET CONF-PAY-YES, then PERFORM READ-ACCTDAT-FILE.
            confirmToPay = true;
        } else if ("N".equals(confirm) || "n".equals(confirm)) {
            // WHEN 'N'/'n': PERFORM CLEAR-CURRENT-SCREEN + set error flag. The
            // screen is cleared with no message and nothing is read or posted.
            return BillPaymentResult.message("");
        } else if (confirm.isEmpty()) {
            // WHEN SPACES/LOW-VALUES: read the account and display its balance.
            confirmToPay = false;
        } else {
            // WHEN OTHER: invalid confirmation value; no account read.
            return BillPaymentResult.message(INVALID_CONFIRM_MESSAGE);
        }

        // --- COBOL READ-ACCTDAT-FILE (L343-L372). ACCT-ID is numeric PIC 9(11);
        // a non-numeric key cannot match any account and is treated exactly like
        // the CICS DFHRESP(NOTFND) outcome ("Account ID NOT found..."). --------
        Account account = accountRepository.findById(parseAccountKey(trimmedAccountId))
                .orElseThrow(() -> new RecordNotFoundException(ACCOUNT_NOT_FOUND_MESSAGE));

        // Balance is normalized through Money (scale 2, HALF_UP). A null column
        // value is defensively treated as zero so the guard below still holds.
        BigDecimal currentBalanceRaw = account.getCurrBal();
        Money balance = Money.of(currentBalanceRaw == null ? BigDecimal.ZERO : currentBalanceRaw);

        // --- COBOL PROCESS-ENTER-KEY L197-L206: ACCT-CURR-BAL <= ZEROS (the id
        // is already known non-blank) => "You have nothing to pay...". No post. -
        if (!balance.isPositive()) {
            return BillPaymentResult.display(balance.toBigDecimal(), NOTHING_TO_PAY_MESSAGE);
        }

        // --- COBOL PROCESS-ENTER-KEY L208-L244. ------------------------------
        if (!confirmToPay) {
            // ELSE branch (L236-L239): balance shown, operator asked to confirm.
            return BillPaymentResult.display(balance.toBigDecimal(), CONFIRM_PROMPT_MESSAGE);
        }
        return postPayment(account, balance);
    }

    /**
     * Posts the confirmed full-balance payment, reproducing the COBOL
     * {@code IF CONF-PAY-YES} block ({@code PROCESS-ENTER-KEY} L210-L235) together
     * with {@code READ-CXACAIX-FILE}, the reverse-browse id generation, and
     * {@code WRITE-TRANSACT-FILE}/{@code UPDATE-ACCTDAT-FILE}.
     *
     * <p>Extracted as a private helper purely for readability; it runs within the
     * caller's {@link Transactional} boundary and therefore shares its atomicity
     * and optimistic-locking guarantees.</p>
     *
     * @param account the account being paid (already read, balance positive)
     * @param balance the current balance as {@link Money}; also the amount to pay
     *                (the COBOL {@code MOVE ACCT-CURR-BAL TO TRAN-AMT})
     * @return the posted result carrying the saved transaction, the reduced
     *         balance, and the success message
     * @throws RecordNotFoundException if no card cross-reference exists for the
     *                                 account ({@code READ-CXACAIX-FILE} NOTFND)
     */
    private BillPaymentResult postPayment(Account account, Money balance) {
        // COBOL READ-CXACAIX-FILE (L408-L436): resolve the card number for the
        // account via the cross-reference alternate index. The COBOL reads a
        // single record by XREF-ACCT-ID; the first cross-reference in card-number
        // order reproduces that single-record resolution.
        CardXref xref = cardXrefRepository
                .findFirstByAcctIdOrderByXrefCardNumAsc(account.getAcctId())
                .orElseThrow(() -> new RecordNotFoundException(XREF_NOT_FOUND_MESSAGE));

        // COBOL L212-L217: MOVE HIGH-VALUES -> STARTBR -> READPREV -> ENDBR ->
        // increment. Re-expressed as max-key-plus-one; an empty table yields the
        // first id "0000000000000001".
        String tranId = IdGenerator.nextTransactionId(
                transactionRepository.findMaxTranId().orElse(null));

        // COBOL L224: MOVE ACCT-CURR-BAL TO TRAN-AMT (pay the full balance).
        BigDecimal amount = balance.toBigDecimal();

        // COBOL GET-CURRENT-TIMESTAMP (L249-L267) then MOVE WS-TIMESTAMP to both
        // TRAN-ORIG-TS and TRAN-PROC-TS (L231-L232): a single 26-char timestamp.
        String timestamp = DateUtils.currentTimestamp();

        // COBOL L218-L232: build the TRAN-RECORD with the fixed bill-payment
        // attributes, then WRITE-TRANSACT-FILE (L510-L547).
        Transaction transaction = new Transaction(
                tranId,
                BILL_PAYMENT_TYPE_CD,
                BILL_PAYMENT_CATEGORY_CD,
                BILL_PAYMENT_SOURCE,
                BILL_PAYMENT_DESCRIPTION,
                amount,
                BILL_PAYMENT_MERCHANT_ID,
                BILL_PAYMENT_MERCHANT_NAME,
                BILL_PAYMENT_MERCHANT_CITY,
                BILL_PAYMENT_MERCHANT_ZIP,
                xref.getXrefCardNum(),
                timestamp,
                timestamp);
        Transaction savedTransaction = transactionRepository.save(transaction);

        // COBOL L234: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT. The
        // subtraction is centralized in Money (scale 2, HALF_UP); paying the full
        // balance yields 0.00. Then UPDATE-ACCTDAT-FILE (L377-L403) rewrites the
        // account; the entity's @Version column enforces last-writer integrity.
        BigDecimal newBalance = balance.subtract(Money.of(amount)).toBigDecimal();
        account.setCurrBal(newBalance);
        accountRepository.save(account);

        // COBOL WRITE-TRANSACT-FILE success path (L527-L531): the STRING builds
        // "Payment successful. " + " Your Transaction ID is " + TRAN-ID + "."
        // (two spaces after "successful." from the two adjacent literals).
        String message = SUCCESS_PREFIX + SUCCESS_INFIX + savedTransaction.getTranId() + ".";
        return BillPaymentResult.paid(savedTransaction, newBalance, message);
    }

    /**
     * Parses the screen account id (COBOL {@code ACTIDINI}, a numeric
     * {@code PIC 9(11)} key) into the {@link Long} primary key used by
     * {@link AccountRepository}.
     *
     * <p>A non-numeric value cannot correspond to any account, so it is mapped to
     * the same caller-visible outcome as the COBOL CICS {@code DFHRESP(NOTFND)}
     * path ("Account ID NOT found...") by throwing {@link RecordNotFoundException},
     * preserving the root cause for diagnostics.</p>
     *
     * @param accountId the trimmed, non-blank account id
     * @return the parsed account key
     * @throws RecordNotFoundException if {@code accountId} is not a valid number
     */
    private static Long parseAccountKey(String accountId) {
        try {
            return Long.valueOf(accountId);
        } catch (NumberFormatException ex) {
            throw new RecordNotFoundException(ACCOUNT_NOT_FOUND_MESSAGE, ex);
        }
    }

    /**
     * Immutable outcome of a bill-payment attempt &mdash; the transport-neutral
     * replacement for the COBOL "post the payment and show success" versus
     * "set {@code WS-MESSAGE} and re-display" decision.
     *
     * <p>Exactly two shapes are produced:</p>
     * <ul>
     *   <li><strong>Posted</strong> ({@code success == true}): the payment was
     *       written. {@code postedTransaction} is the saved {@link Transaction},
     *       {@code newBalance} is the reduced account balance ({@code 0.00} for a
     *       full-balance payment), and {@code message} is the exact COBOL success
     *       text including the generated transaction id.</li>
     *   <li><strong>Not posted</strong> ({@code success == false}): no payment was
     *       written and {@code postedTransaction} is {@code null}. This covers
     *       every non-navigating COBOL outcome &mdash; blank account id, an
     *       invalid confirmation value, a declined payment ({@code N}), a
     *       non-positive balance, and the "please confirm" prompt. When the
     *       account was read, {@code newBalance} carries the balance the COBOL
     *       moved to the display field ({@code CURBALI}); otherwise it is
     *       {@code null}. {@code message} carries the exact caller-visible text
     *       the COBOL placed in {@code WS-MESSAGE} (possibly empty for a cleared
     *       screen).</li>
     * </ul>
     *
     * <p>A genuinely missing record (the COBOL {@code NOTFND} paths for the
     * account or the cross-reference) is <em>not</em> represented here; it is
     * signalled by a {@link RecordNotFoundException} thrown from
     * {@link #payBill(String, String)}.</p>
     *
     * @param success           {@code true} when a payment was posted;
     *                          {@code false} for any non-posting outcome
     * @param postedTransaction the persisted bill-payment transaction when
     *                          {@code success} is {@code true}; otherwise {@code null}
     * @param newBalance        the account balance to display: the reduced balance
     *                          when posted, the current balance when the account
     *                          was read without posting, or {@code null} when the
     *                          account was not read
     * @param message           the exact caller-visible message; never {@code null}
     *                          (empty string for a cleared screen)
     */
    public record BillPaymentResult(boolean success,
                                    Transaction postedTransaction,
                                    BigDecimal newBalance,
                                    String message) {

        /**
         * Canonical constructor enforcing the never-null {@code message}
         * invariant (the COBOL {@code WS-MESSAGE} is always a fixed-length field,
         * blanked to spaces rather than made absent).
         *
         * @throws NullPointerException if {@code message} is {@code null}
         */
        public BillPaymentResult {
            Objects.requireNonNull(message, "message must not be null");
        }

        /**
         * Builds a successful (payment-posted) outcome.
         *
         * @param postedTransaction the persisted transaction; must not be {@code null}
         * @param newBalance        the reduced account balance; must not be {@code null}
         * @param message           the caller-visible success message
         * @return a {@code BillPaymentResult} with {@code success == true}
         */
        public static BillPaymentResult paid(Transaction postedTransaction,
                                             BigDecimal newBalance,
                                             String message) {
            return new BillPaymentResult(true,
                    Objects.requireNonNull(postedTransaction, "postedTransaction must not be null"),
                    Objects.requireNonNull(newBalance, "newBalance must not be null"),
                    message);
        }

        /**
         * Builds a non-posting outcome that still carries a balance to display
         * (the COBOL "move balance to {@code CURBALI} then set {@code WS-MESSAGE}"
         * cases: nothing-to-pay and the confirm prompt).
         *
         * @param balance the current account balance to display; must not be {@code null}
         * @param message the caller-visible message
         * @return a {@code BillPaymentResult} with {@code success == false} and no transaction
         */
        public static BillPaymentResult display(BigDecimal balance, String message) {
            return new BillPaymentResult(false, null,
                    Objects.requireNonNull(balance, "balance must not be null"), message);
        }

        /**
         * Builds a non-posting outcome with no balance to display (the COBOL
         * cases that never read the account: blank id, invalid confirm, and the
         * declined/cleared {@code N} path).
         *
         * @param message the caller-visible message (empty string for a cleared screen)
         * @return a {@code BillPaymentResult} with {@code success == false} and no transaction or balance
         */
        public static BillPaymentResult message(String message) {
            return new BillPaymentResult(false, null, null, message);
        }
    }
}

package com.carddemo.validation;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.InvalidCardException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Main transaction validator preserving the COBOL paragraph
 * {@code 1500-VALIDATE-TRAN} chain from {@code app/cbl/CBTRN02C.cbl}
 * lines L370-L422.
 *
 * <p>This class is the single entry-point for validating a daily transaction
 * against the CardDemo business rules. It orchestrates the four-step validation
 * flow producing codes 100/101/102/103, dispatching to the cross-reference and
 * account repositories for the lookups (codes 100/101) and delegating the
 * account-level money/date checks (codes 102/103) to {@link AccountValidator}:</p>
 * <ol>
 *   <li><b>Code 100</b> &mdash; Card xref lookup miss &rarr; {@link InvalidCardException}
 *       (CBTRN02C L380-L392, paragraph {@code 1500-A-LOOKUP-XREF})</li>
 *   <li><b>Code 101</b> &mdash; Account lookup miss &rarr; {@link AccountNotFoundException}
 *       (CBTRN02C L393-L399, paragraph {@code 1500-B-LOOKUP-ACCT} INVALID KEY)</li>
 *   <li><b>Code 102</b> &mdash; Overlimit transaction &rarr; {@link com.carddemo.exception.OverlimitException}
 *       (CBTRN02C L403-L413, delegated to {@link AccountValidator})</li>
 *   <li><b>Code 103</b> &mdash; Expired account &rarr; {@link com.carddemo.exception.ExpiredAccountException}
 *       (CBTRN02C L414-L420, delegated to {@link AccountValidator})</li>
 * </ol>
 *
 * <h2>COBOL source (EXACT preservation per PR-03)</h2>
 * <pre>
 *   1500-VALIDATE-TRAN.                                  (L370)
 *       PERFORM 1500-A-LOOKUP-XREF.                      (L371)
 *       IF WS-VALIDATION-FAIL-REASON = 0                 (L372)
 *          PERFORM 1500-B-LOOKUP-ACCT                    (L373)
 *       ELSE                                             (L374)
 *          CONTINUE                                      (L375)
 *       END-IF                                           (L376)
 *       EXIT.                                            (L378)
 *
 *   1500-A-LOOKUP-XREF.                                  (L380)
 *       MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM       (L382)
 *       READ XREF-FILE INTO CARD-XREF-RECORD             (L383)
 *          INVALID KEY                                   (L384)
 *            MOVE 100 TO WS-VALIDATION-FAIL-REASON       (L385)
 *            MOVE 'INVALID CARD NUMBER FOUND'            (L386)
 *              TO WS-VALIDATION-FAIL-REASON-DESC         (L387)
 *          NOT INVALID KEY                               (L388)
 *              CONTINUE                                  (L390)
 *       END-READ                                         (L391)
 *       EXIT.                                            (L392)
 *
 *   1500-B-LOOKUP-ACCT.                                  (L393)
 *       MOVE XREF-ACCT-ID TO FD-ACCT-ID                  (L394)
 *       READ ACCOUNT-FILE INTO ACCOUNT-RECORD            (L395)
 *          INVALID KEY                                   (L396)
 *            MOVE 101 TO WS-VALIDATION-FAIL-REASON       (L397)
 *            MOVE 'ACCOUNT RECORD NOT FOUND'             (L398)
 *              TO WS-VALIDATION-FAIL-REASON-DESC         (L399)
 *          NOT INVALID KEY                               (L400)
 *            [credit limit (102) + expiration (103) ...] (L401-L420)
 *       END-READ                                         (L421)
 *       EXIT.                                            (L422)
 * </pre>
 *
 * <p>The COBOL flow {@code PERFORM 1500-A-LOOKUP-XREF; IF FAIL-REASON = 0 PERFORM 1500-B}
 * is preserved by <b>throw-on-first-failure</b>: the xref miss throws
 * {@link InvalidCardException}, which short-circuits the account lookup automatically &mdash;
 * exactly as the COBOL {@code IF WS-VALIDATION-FAIL-REASON = 0} gate at L372 prevents
 * {@code 1500-B-LOOKUP-ACCT} from running once the xref read has already set reason 100.
 * Likewise an account miss throws {@link AccountNotFoundException} before the account-level
 * checks are reached.</p>
 *
 * <p><b>Preservation Rules satisfied:</b></p>
 * <ul>
 *   <li><b>PR-03</b> &mdash; Codes 100/101/102/103 with EXACT message strings
 *       carried by the exception constants:
 *       {@link InvalidCardException#COBOL_MESSAGE} ("INVALID CARD NUMBER FOUND"),
 *       {@link AccountNotFoundException#COBOL_MESSAGE} ("ACCOUNT RECORD NOT FOUND"),
 *       {@link com.carddemo.exception.OverlimitException#COBOL_MESSAGE} ("OVERLIMIT TRANSACTION"),
 *       {@link com.carddemo.exception.ExpiredAccountException#COBOL_MESSAGE}
 *       ("TRANSACTION RECEIVED AFTER ACCT EXPIRATION").
 *       The order of checks is STRICT: 100 &rarr; 101 &rarr; 102 &rarr; 103. Reordering would
 *       produce a different reason code for the same input and break parity with COBOL.</li>
 *   <li><b>PR-04, PR-05</b> &mdash; Credit-limit and expiration checks are delegated to
 *       {@link AccountValidator#validateForTransaction(Account, DailyTransaction)}; this
 *       class performs no money/date arithmetic of its own.</li>
 *   <li><b>PR-16</b> &mdash; All monetary comparisons occur in the delegate using
 *       {@code BigDecimal.compareTo()} (never {@code float}/{@code double}); this
 *       orchestrator holds no monetary state.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE namespace baseline: no {@code javax.*} imports
 *       (this orchestrator needs no persistence/validation annotations).</li>
 *   <li><b>PR-29</b> &mdash; Constructor injection via Lombok {@link RequiredArgsConstructor}
 *       over {@code final} fields. No {@code @Autowired} field injection anywhere.</li>
 * </ul>
 *
 * <p><b>Consumers:</b> {@code com.carddemo.service.TransactionService} (online transaction
 * creation, {@code POST /api/transactions}), {@code com.carddemo.batch.TransactionPostingProcessor}
 * (batch CBTRN02C-equivalent posting), and {@code com.carddemo.service.BillPaymentService}
 * (bill-payment validation). All of these obtain the same code 100/101/102/103 semantics by
 * invoking {@link #validate(DailyTransaction)}.</p>
 *
 * <p>Behavioural parity is asserted by
 * {@code src/test/java/com/carddemo/businesslogic/TransactionPostingParityTest.java}, which
 * mirrors the {@code 1500-VALIDATE-TRAN} dispatcher line-by-line.</p>
 *
 * @see AccountValidator
 * @see com.carddemo.exception.TransactionValidationException
 * @see "app/cbl/CBTRN02C.cbl L370-L422 (paragraphs 1500-VALIDATE-TRAN / 1500-A-LOOKUP-XREF / 1500-B-LOOKUP-ACCT)"
 */
@Component
@RequiredArgsConstructor
public class TransactionValidator {

    /**
     * Repository for CARD-XREF (50-byte {@code CARD-XREF-RECORD}, {@code app/cpy/CVACT03Y.cpy})
     * records. Used to resolve a card number to its owning account via the cross-reference
     * table, replacing the COBOL {@code READ XREF-FILE} keyed read on
     * {@code DALYTRAN-CARD-NUM} (the CARDXREF.AIX path). The xref card number is the
     * entity primary key, so {@link CardXrefRepository#findById(Object)} keyed by the
     * card number is the direct equivalent of COBOL's keyed {@code READ}.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Repository for ACCOUNT (300-byte {@code ACCOUNT-RECORD}, {@code app/cpy/CVACT01Y.cpy})
     * records. Used to load the account whose ID is obtained from the resolved
     * {@link CardXref}, replacing the COBOL {@code READ ACCOUNT-FILE} keyed read on
     * {@code XREF-ACCT-ID}.
     */
    private final AccountRepository accountRepository;

    /**
     * Delegate validator for account-level checks &mdash; the credit-limit check (code 102,
     * PR-04) and the expiration check (code 103, PR-05). Invoked only AFTER the xref and
     * account lookups have both succeeded, preserving the COBOL ordering in which these
     * checks live inside the {@code NOT INVALID KEY} branch of {@code 1500-B-LOOKUP-ACCT}.
     */
    private final AccountValidator accountValidator;

    /**
     * Validates the given daily transaction against the full {@code CBTRN02C}
     * {@code 1500-VALIDATE-TRAN} validation chain, throwing on the first failure.
     *
     * <p>Strict invocation order (DO NOT reorder &mdash; the error codes depend on it):</p>
     * <ol>
     *   <li>Card xref lookup (code 100) &mdash; FIRST</li>
     *   <li>Account lookup (code 101) &mdash; SECOND, only if the xref lookup succeeded</li>
     *   <li>Credit-limit check (code 102) &mdash; THIRD, delegated to {@link AccountValidator}</li>
     *   <li>Expiration check (code 103) &mdash; FOURTH, delegated to {@link AccountValidator}</li>
     * </ol>
     *
     * <p>On success (the transaction passes all four checks) the method returns normally
     * with no value, mirroring the COBOL paragraph leaving {@code WS-VALIDATION-FAIL-REASON}
     * at zero.</p>
     *
     * @param tran the candidate daily transaction; must be non-null with a non-null
     *             {@code cardNum} (used for the xref lookup) and non-null {@code amount}
     *             and {@code origTimestamp} (used by the {@link AccountValidator} delegate)
     * @throws InvalidCardException     (code 100) when the card number is not present in the
     *                                  cross-reference table
     * @throws AccountNotFoundException (code 101) when the resolved account ID does not exist
     * @throws com.carddemo.exception.OverlimitException
     *                                  (code 102) when posting the transaction would exceed
     *                                  the account credit limit
     * @throws com.carddemo.exception.ExpiredAccountException
     *                                  (code 103) when the account is expired relative to the
     *                                  transaction's origination date
     */
    public void validate(DailyTransaction tran) {
        // ============================================================
        // STEP 1: Card Xref Lookup (CBTRN02C L380-L392, code 100, PR-03)
        // ============================================================
        // COBOL:
        //   1500-A-LOOKUP-XREF.
        //     MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
        //     READ XREF-FILE INTO CARD-XREF-RECORD
        //        INVALID KEY
        //          MOVE 100 TO WS-VALIDATION-FAIL-REASON
        //          MOVE 'INVALID CARD NUMBER FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
        //
        // The CardXref entity's @Id IS the card number (xrefCardNum: VARCHAR(16)), so
        // findById(cardNum) is the direct equivalent of COBOL's keyed READ. An empty
        // Optional is the VSAM INVALID KEY (file status '23') equivalent; the lambda
        // throws InvalidCardException with the offending card number appended for
        // diagnostics while preserving the EXACT COBOL_MESSAGE prefix (PR-03). The throw
        // short-circuits STEP 2, reproducing the L372 "IF WS-VALIDATION-FAIL-REASON = 0"
        // gate that prevents 1500-B-LOOKUP-ACCT from running after a code-100 failure.
        CardXref xref = cardXrefRepository.findById(tran.getCardNum())
                .orElseThrow(() -> new InvalidCardException(tran.getCardNum()));

        // ============================================================
        // STEP 2: Account Lookup (CBTRN02C L393-L399, code 101, PR-03)
        // ============================================================
        // COBOL:
        //   1500-B-LOOKUP-ACCT.
        //     MOVE XREF-ACCT-ID TO FD-ACCT-ID
        //     READ ACCOUNT-FILE INTO ACCOUNT-RECORD
        //        INVALID KEY
        //          MOVE 101 TO WS-VALIDATION-FAIL-REASON
        //          MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
        //
        // The Account entity's @Id is acctId: Long; xref.getAccountId() supplies the
        // XREF-ACCT-ID resolved by STEP 1. An empty Optional is the INVALID KEY
        // equivalent; the lambda throws AccountNotFoundException via its Long-arg
        // diagnostic constructor (code 101). This throw short-circuits STEPS 3 & 4,
        // matching the COBOL where the account-level checks live inside the
        // NOT INVALID KEY branch and are never reached when the account read fails.
        Account account = accountRepository.findById(xref.getAccountId())
                .orElseThrow(() -> new AccountNotFoundException(xref.getAccountId()));

        // ============================================================
        // STEPS 3 & 4: Delegate credit-limit (code 102) + expiration (code 103)
        // checks to AccountValidator (CBTRN02C L401-L420, PR-04, PR-05)
        // ============================================================
        // COBOL 1500-B-LOOKUP-ACCT NOT INVALID KEY branch:
        //   COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
        //   IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE
        //   ELSE MOVE 102 ... 'OVERLIMIT TRANSACTION'
        //   IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) CONTINUE
        //   ELSE MOVE 103 ... 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
        //
        // These two independent IF...END-IF blocks (with COBOL last-writer-wins precedence,
        // where 103 overwrites 102 when both fail) are preserved EXACTLY inside the
        // delegate, which throws OverlimitException (102) or ExpiredAccountException (103).
        accountValidator.validateForTransaction(account, tran);
    }
}

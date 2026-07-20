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
package com.aws.carddemo.batch.processor;

import java.math.BigDecimal;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.exception.RejectCode;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;

/**
 * Spring Batch {@link ItemProcessor} that reproduces the <em>validation and posting core</em> of the
 * legacy COBOL batch program {@code CBTRN02C} (source {@code legacy/cbl/CBTRN02C.cbl}, formerly
 * {@code app/cbl/CBTRN02C.cbl}), triggered by {@code POSTTRAN.jcl}. It is the transform side of the
 * {@code DailyTransactionPostingJob}: for each staged {@link DailyTransaction} read by the sibling
 * {@code reader/} bean, it decides whether the record posts or is rejected and, when valid, builds
 * the candidate {@link Transaction}. This is the highest parity-risk component in the posting flow
 * (Technical Specification &sect;0.7.1 hotspots H3 &mdash; monetary fidelity &mdash; and H4 &mdash;
 * reject-code semantics).
 *
 * <h2>Responsibility boundary &mdash; validation and candidate construction ONLY</h2>
 * <p>The processor performs strictly <strong>read-only</strong> lookups
 * ({@link CardXrefRepository#findById(Object) findById} / {@link AccountRepository#findById(Object)
 * findById}), computes the working balance, assigns a {@link RejectCode} (or none), and &mdash; when
 * the record is valid &mdash; assembles the candidate {@link Transaction}. It <strong>never mutates
 * any entity and never calls a repository {@code save}</strong>. The mutating COBOL paragraphs are
 * the responsibility of the sibling {@code writer/} bean, which consumes each {@link PostingResult}
 * and performs, per record:</p>
 * <ul>
 *   <li>if rejected &mdash; {@code 2500-WRITE-REJECT-REC}: writes the 430-byte reject record (the
 *       350-byte {@code DALYTRAN} image plus an 80-byte trailer {@code {reason PIC 9(4),
 *       desc PIC X(76)}}, per {@code POSTTRAN.jcl} {@code DALYREJS DCB=(RECFM=F,LRECL=430)}) and
 *       increments the reject count (batch return code 4);</li>
 *   <li>if valid &mdash; {@code 2700-UPDATE-TCATBAL} (transaction-category-balance upsert keyed by
 *       {@code {acctId, typeCd, catCd}}), {@code 2800-UPDATE-ACCOUNT-REC} (account balance / cycle
 *       update; an I/O abend there is the writer's reason {@code 109} / return code 8), then
 *       {@code 2900-WRITE-TRANSACTION-FILE}.</li>
 * </ul>
 * <p>Reason code {@code 109} is intentionally <strong>not</strong> modeled by {@link RejectCode} and
 * is never assigned here; it can only arise from the writer's account {@code REWRITE}.</p>
 *
 * <h2>Frozen reject-code contract (do not reorder)</h2>
 * <p>The reject-code ordering and short-circuit semantics are a frozen behavioral contract mirroring
 * {@code CBTRN02C} paragraphs {@code 1500-VALIDATE-TRAN} / {@code 1500-A-LOOKUP-XREF} /
 * {@code 1500-B-LOOKUP-ACCT} (L370-L422):</p>
 * <ol>
 *   <li><strong>100 {@link RejectCode#INVALID_CARD_NUMBER}</strong> &mdash; the card cross-reference
 *       is missing (L385-L387). This <em>short-circuits</em>: the account lookup ({@code 1500-B}) is
 *       not performed, exactly as {@code 1500-VALIDATE-TRAN} only performs {@code 1500-B} while the
 *       fail reason is still zero (L372-L376).</li>
 *   <li><strong>101 {@link RejectCode#ACCOUNT_NOT_FOUND}</strong> &mdash; the account is missing
 *       (L397-L399). Because it lives in the {@code INVALID KEY} branch, a missing account blocks the
 *       over-limit and expiration checks entirely.</li>
 *   <li><strong>102 {@link RejectCode#OVER_CREDIT_LIMIT}</strong> &mdash; assigned when
 *       {@code ACCT-CREDIT-LIMIT < (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)}
 *       (L403-L413). The working balance uses the cycle credit/debit and the transaction amount,
 *       <em>not</em> the current balance.</li>
 *   <li><strong>103 {@link RejectCode#ACCOUNT_EXPIRED}</strong> &mdash; assigned when
 *       {@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)} (L414-L420).</li>
 * </ol>
 * <p>The over-limit (102) and expiration (103) checks are <strong>two independent {@code if}
 * statements, not an {@code else-if}</strong>. When both conditions fail, 103 is assigned last, so
 * {@code 103} wins (<em>last-writer-wins</em>), reproducing the two consecutive COBOL {@code IF}
 * blocks. These must not be combined, short-circuited, or separated by a {@code return}.</p>
 *
 * <h2>Monetary fidelity</h2>
 * <p>All arithmetic uses {@link BigDecimal} at scale 2; binary floating-point types are never used.
 * The working balance is computed with exact fixed-point add/subtract (no intermediate rounding,
 * matching COBOL {@code COMP-3}), and comparisons use {@link BigDecimal#compareTo(BigDecimal)}
 * (value comparison independent of scale, matching COBOL numeric comparison).</p>
 *
 * <h2>Output contract</h2>
 * <p>The processor emits exactly one {@link PostingResult} for <strong>every</strong> input record
 * &mdash; it never filters and never returns {@code null} &mdash; so the writer can route each record
 * to either the posting path or the reject path.</p>
 */
@Component
public class DailyTransactionPostingProcessor
        implements ItemProcessor<DailyTransaction, DailyTransactionPostingProcessor.PostingResult> {

    /**
     * Logger for non-sensitive diagnostics. Reject decisions are logged at {@code DEBUG} carrying only
     * the numeric reason code and the business transaction id; the card number (PAN) and any other
     * sensitive field are never logged.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DailyTransactionPostingProcessor.class);

    /** Read-only access to the card cross-reference (VSAM {@code CARDXREF}), keyed by card number. */
    private final CardXrefRepository cardXrefRepository;

    /** Read-only access to the account master (VSAM {@code ACCTFILE}), keyed by account id. */
    private final AccountRepository accountRepository;

    /**
     * Creates the processor with its collaborating repositories injected by constructor (the project
     * uses constructor injection exclusively; no field injection).
     *
     * @param cardXrefRepository repository used for the read-only card cross-reference lookup
     *                           ({@code 1500-A-LOOKUP-XREF})
     * @param accountRepository  repository used for the read-only account lookup
     *                           ({@code 1500-B-LOOKUP-ACCT})
     */
    public DailyTransactionPostingProcessor(CardXrefRepository cardXrefRepository,
                                            AccountRepository accountRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Validates a single staged daily transaction and, when valid, builds the candidate posted
     * transaction, reproducing {@code CBTRN02C} {@code 1500-VALIDATE-TRAN} (L370-L422) followed by the
     * field-copy portion of {@code 2000-POST-TRANSACTION} (L424-L444). The evaluation order and
     * short-circuit semantics are the frozen contract documented on the class.
     *
     * @param item the staged daily-transaction record to validate (never {@code null}; supplied by
     *             the reader)
     * @return a {@link PostingResult} for the record &mdash; carrying the reject code when invalid, or
     *         the candidate {@link Transaction} when valid; never {@code null}
     */
    @Override
    public PostingResult process(DailyTransaction item) {
        // MOVE 0 TO WS-VALIDATION-FAIL-REASON (MAIN L208): start with "no reject".
        RejectCode rejectCode = null;
        // XREF-ACCT-ID, resolved by 1500-A; stays null for reject 100 (no cross-reference).
        Long acctId = null;

        // ---- 1500-A-LOOKUP-XREF (L380-L392): READ XREF-FILE by DALYTRAN-CARD-NUM. ----
        Optional<CardXref> xref = cardXrefRepository.findById(item.getCardNum());
        if (xref.isEmpty()) {
            // INVALID KEY -> 100 'INVALID CARD NUMBER FOUND' (L385-L387).
            rejectCode = RejectCode.INVALID_CARD_NUMBER;
        }

        // ---- 1500-VALIDATE-TRAN (L372-L376): only perform 1500-B while the reason is still 0. ----
        // This is the 100 short-circuit: a missing cross-reference never reaches the account lookup.
        if (rejectCode == null) {
            // MOVE XREF-ACCT-ID TO FD-ACCT-ID (L394).
            acctId = xref.get().getAcctId();

            // ---- 1500-B-LOOKUP-ACCT (L393-L422): READ ACCOUNT-FILE by XREF-ACCT-ID. ----
            Optional<Account> account = accountRepository.findById(acctId);
            if (account.isEmpty()) {
                // INVALID KEY -> 101 'ACCOUNT RECORD NOT FOUND' (L397-L399); blocks 102/103.
                rejectCode = RejectCode.ACCOUNT_NOT_FOUND;
            } else {
                Account acct = account.get();

                // COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
                // (L403-L405). Exact BigDecimal (scale 2) arithmetic; NOT ACCT-CURR-BAL.
                BigDecimal tempBal = acct.getCurrCycCredit()
                        .subtract(acct.getCurrCycDebit())
                        .add(item.getTranAmt());

                // IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE ELSE 102 (L407-L413) -- INDEPENDENT IF.
                // Reject when creditLimit < tempBal (compareTo < 0), preserving the '>=' direction.
                if (acct.getCreditLimit().compareTo(tempBal) < 0) {
                    rejectCode = RejectCode.OVER_CREDIT_LIMIT;
                }

                // IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10) CONTINUE ELSE 103 (L414-L420)
                // -- INDEPENDENT IF (NOT else-if). Assigned last, so 103 wins when both 102 and 103
                // conditions hold (last-writer-wins). Textual yyyy-MM-dd comparison on the first ten
                // characters (the date portion) of the 26-char DALYTRAN-ORIG-TS.
                if (acct.getAcctExpirationDate().compareTo(item.getOrigTs().substring(0, 10)) < 0) {
                    rejectCode = RejectCode.ACCOUNT_EXPIRED;
                }
            }
        }

        // Build the candidate only for a valid record (MAIN L211-L212 -> 2000-POST-TRANSACTION);
        // rejects carry a null candidate (MAIN L213-L215 -> writer's 2500-WRITE-REJECT-REC).
        Transaction posted = (rejectCode == null) ? postTransaction(item) : null;

        if (rejectCode != null && LOG.isDebugEnabled()) {
            // Non-sensitive only: numeric reason code + business transaction id (never the card number).
            LOG.debug("Daily transaction rejected: reasonCode={} dalytranId={}",
                    rejectCode.getCode(), item.getDalytranId());
        }

        // Emit one PostingResult for EVERY record (never null): acctId is null only for reject 100.
        return new PostingResult(item, rejectCode, posted, acctId,
                item.getTypeCd(), item.getCatCd(), item.getTranAmt());
    }

    /**
     * Builds the candidate posted {@link Transaction} from a validated {@link DailyTransaction},
     * reproducing the field-copy portion of {@code 2000-POST-TRANSACTION} (L424-L444). Every
     * {@code DALYTRAN-*} field is copied straight to its {@code TRAN-*} counterpart &mdash; in
     * particular {@code DALYTRAN-ID} is copied directly to {@code TRAN-ID} with <strong>no</strong>
     * id generation (L425) &mdash; and the processing timestamp is stamped via
     * {@link DateUtils#currentTimestamp()} ({@code Z-GET-DB2-FORMAT-TIMESTAMP}, the 26-character
     * {@code uuuu-MM-dd HH:mm:ss.SSSSSS} form; L437-L438).
     *
     * <p>The candidate is assembled with the {@link Transaction} all-arguments constructor. The domain
     * type intentionally exposes only a {@code protected} no-argument constructor (reserved for the
     * JPA provider), so the public all-arguments constructor is the supported way to build a populated
     * instance from another package; each argument maps one-to-one, in declaration order, to the
     * {@code MOVE} statements of {@code 2000-POST-TRANSACTION}. Note the merchant field naming
     * difference: {@link DailyTransaction} accessors are unprefixed ({@code getMerchantId} ...) whereas
     * {@link Transaction} models them with the {@code tran} prefix ({@code tranMerchantId} ...).</p>
     *
     * @param item the validated daily-transaction record to post
     * @return the candidate {@link Transaction} to be written by the writer's
     *         {@code 2900-WRITE-TRANSACTION-FILE}
     */
    private Transaction postTransaction(DailyTransaction item) {
        return new Transaction(
                item.getDalytranId(),   // MOVE DALYTRAN-ID           TO TRAN-ID            (L425) -- no id gen
                item.getTypeCd(),       // MOVE DALYTRAN-TYPE-CD       TO TRAN-TYPE-CD       (L426)
                item.getCatCd(),        // MOVE DALYTRAN-CAT-CD        TO TRAN-CAT-CD        (L427)
                item.getTranSource(),   // MOVE DALYTRAN-SOURCE        TO TRAN-SOURCE        (L428)
                item.getTranDesc(),     // MOVE DALYTRAN-DESC          TO TRAN-DESC          (L429)
                item.getTranAmt(),      // MOVE DALYTRAN-AMT           TO TRAN-AMT           (L430)
                item.getMerchantId(),   // MOVE DALYTRAN-MERCHANT-ID   TO TRAN-MERCHANT-ID   (L431)
                item.getMerchantName(), // MOVE DALYTRAN-MERCHANT-NAME TO TRAN-MERCHANT-NAME (L432)
                item.getMerchantCity(), // MOVE DALYTRAN-MERCHANT-CITY TO TRAN-MERCHANT-CITY (L433)
                item.getMerchantZip(),  // MOVE DALYTRAN-MERCHANT-ZIP  TO TRAN-MERCHANT-ZIP  (L434)
                item.getCardNum(),      // MOVE DALYTRAN-CARD-NUM      TO TRAN-CARD-NUM      (L435)
                item.getOrigTs(),       // MOVE DALYTRAN-ORIG-TS       TO TRAN-ORIG-TS       (L436)
                DateUtils.currentTimestamp()); // Z-GET-DB2-FORMAT-TIMESTAMP -> TRAN-PROC-TS (L437-L438)
    }

    /**
     * Immutable outcome of processing a single daily transaction, emitted for every input record so
     * the sibling {@code writer/} bean (which consumes it as
     * {@code DailyTransactionPostingProcessor.PostingResult}) can route the record. A valid record
     * carries the posted candidate and a {@code null} {@link #rejectCode}; a rejected record carries
     * the {@link RejectCode} and a {@code null} {@link #posted} candidate.
     *
     * @param source     the originating 350-byte {@code DALYTRAN} record, retained so the writer can
     *                   emit the 430-byte reject image ({@code 2500-WRITE-REJECT-REC})
     * @param rejectCode the assigned reject reason, or {@code null} when the record is valid
     * @param posted     the candidate transaction to write ({@code 2900-WRITE-TRANSACTION-FILE}), or
     *                   {@code null} when the record is rejected
     * @param acctId     the resolved {@code XREF-ACCT-ID}; {@code null} only for reject 100 (no
     *                   cross-reference), otherwise populated (used as part of the {@code 2700}
     *                   transaction-category-balance key and by {@code 2800})
     * @param typeCd     the {@code DALYTRAN-TYPE-CD}, part of the {@code 2700} balance key
     * @param catCd      the {@code DALYTRAN-CAT-CD}, part of the {@code 2700} balance key
     * @param tranAmt    the {@code DALYTRAN-AMT}, used by the writer's {@code 2700}/{@code 2800}
     *                   balance arithmetic
     */
    public static record PostingResult(
            DailyTransaction source,
            RejectCode rejectCode,
            Transaction posted,
            Long acctId,
            String typeCd,
            Integer catCd,
            BigDecimal tranAmt) {

        /**
         * Indicates whether this record was rejected.
         *
         * @return {@code true} when a {@link RejectCode} was assigned (invalid record); {@code false}
         *         when the record is valid and {@link #posted} carries the candidate transaction
         */
        public boolean isRejected() {
            return rejectCode != null;
        }
    }
}

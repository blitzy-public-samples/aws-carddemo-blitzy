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

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategory;
import com.aws.carddemo.domain.TransactionType;
import com.aws.carddemo.exception.FileStatusException;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionCategoryRepository;
import com.aws.carddemo.repository.TransactionTypeRepository;

/**
 * Spring Batch {@link ItemProcessor} that reproduces the <em>per-record transform</em> of the
 * legacy COBOL batch program {@code CBTRN03C} (source {@code app/cbl/CBTRN03C.cbl}, relocated to
 * {@code legacy/cbl/CBTRN03C.cbl}; triggered by {@code TRANREPT.jcl} + {@code app/proc/TRANREPT.prc}).
 * It is the transform stage of {@code TransactionReportJob} in the parent {@code batch/} package,
 * fed by the sibling reader bean {@code transactionReportItemReader}
 * ({@code batch/reader/TransactionReportItemReader.java}, a {@code @StepScope}
 * {@code JpaPagingItemReader<Transaction>}), and it emits one {@link ReportLine} per transaction for
 * the downstream {@code writer/} stage to format and aggregate.
 *
 * <h2>Date filter and sort are performed UPSTREAM, never here</h2>
 * On the mainframe a SORT step pre-filters and orders the transaction file before {@code CBTRN03C}
 * runs: {@code INCLUDE COND=(TRAN-PROC-DT GE PARM-START-DATE AND LE PARM-END-DATE)} and
 * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} (see {@code CBTRN03C} MAIN loop L173-174 and the report
 * control break at L181). In the Spring Batch decomposition the reader already applies the exact
 * equivalent ({@code SUBSTRING(procTs,1,10) BETWEEN startDate AND endDate}, ordered by
 * {@code cardNum, tranId}). This processor therefore <strong>does not filter by date and does not
 * sort</strong>; it receives records already filtered and already ordered by card number.
 *
 * <h2>Per-record logic (CBTRN03C MAIN loop L170-206)</h2>
 * For each in-range record the processor performs, in this exact evaluation order:
 * <ol>
 *   <li><strong>Account resolution via the card cross-reference</strong>
 *       (COBOL {@code 1500-A-LOOKUP-XREF}, L484-492). Reproduces the card control break: the
 *       cross-reference is re-read only when the card number changes, exactly as the COBOL guards
 *       the read with {@code IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM} (L181).</li>
 *   <li><strong>Transaction-type description</strong> (COBOL {@code 1500-B-LOOKUP-TRANTYPE},
 *       L494-502), read for every record.</li>
 *   <li><strong>Transaction-category description</strong> (COBOL {@code 1500-C-LOOKUP-TRANCATG},
 *       L504-512), read for every record using the compound key {@code (typeCd, catCd)}.</li>
 *   <li><strong>Emit the detail line</strong> (COBOL {@code 1100-WRITE-TRANSACTION-REPORT} /
 *       {@code 1120-WRITE-DETAIL}, L361-374) as a fully-populated {@link ReportLine}.</li>
 * </ol>
 *
 * <h2>Missing reference data is a hard error (deliberate parity)</h2>
 * In {@code CBTRN03C} each of the three lookups reacts to {@code INVALID KEY} by moving file status
 * {@code 23} into {@code IO-STATUS} and performing {@code 9999-ABEND-PROGRAM}, which terminates the
 * job with a non-zero return code. This processor reproduces that behavior faithfully: a missing
 * cross-reference, transaction type, or transaction category throws a {@link FileStatusException}
 * carrying {@link FileStatusException#STATUS_RECORD_NOT_FOUND} ({@code "23"}), which surfaces as
 * batch return code 8. The descriptions are <strong>never</strong> defaulted, blanked, or swallowed;
 * doing so would silently change report content and violate behavioral parity.
 *
 * <h2>Control-break cache</h2>
 * The two mutable fields {@link #lastCardNum} and {@link #lastAccountId} mirror the COBOL working
 * storage field {@code WS-CURR-CARD-NUM} (and the account resolved into {@code XREF-ACCT-ID}); they
 * let the processor skip the cross-reference read when consecutive records share a card number, a
 * performance-faithful reproduction of the COBOL control break. Because the reader orders records by
 * card number, the cache is valid; nevertheless <strong>correctness never depends on the cache</strong>
 * &mdash; a cache miss simply re-queries the repository and yields the identical account. The
 * card&rarr;account cross-reference is stable reference data, so the account resolved for a given card
 * is invariant.
 *
 * <h2>Statefulness and threading</h2>
 * Because it holds the control-break cache, this component is <strong>stateful and not thread-safe</strong>.
 * It is intended for single-threaded, chunk-oriented processing within {@code TransactionReportJob},
 * exactly like the inherently sequential COBOL program it replaces; it must not be shared across
 * concurrent step executions.
 *
 * <h2>Explicitly out of scope (the writer's responsibility)</h2>
 * Page/account/grand-total aggregation ({@code 1110-WRITE-PAGE-TOTALS},
 * {@code 1120-WRITE-ACCOUNT-TOTALS}, {@code 1110-WRITE-GRAND-TOTALS}), page headers
 * ({@code 1120-WRITE-HEADERS}), page breaks (every {@code WS-PAGE-SIZE} lines), and the 133-byte
 * fixed-width line formatting are all performed by the sibling {@code writer/} stage. This processor
 * emits structured data only.
 *
 * @see Transaction
 * @see ReportLine
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Component
public class TransactionReportProcessor
        implements ItemProcessor<Transaction, TransactionReportProcessor.ReportLine> {

    /**
     * Repository for the card cross-reference ({@code CARDXREF} KSDS), used to resolve a card number
     * to its owning account (COBOL {@code 1500-A-LOOKUP-XREF}).
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Repository for the transaction-type reference table ({@code TRANTYPE} KSDS), used to resolve a
     * type code to its description (COBOL {@code 1500-B-LOOKUP-TRANTYPE}).
     */
    private final TransactionTypeRepository transactionTypeRepository;

    /**
     * Repository for the transaction-category reference table ({@code TRANCATG} KSDS), used to
     * resolve a {@code (type, category)} key to its description (COBOL {@code 1500-C-LOOKUP-TRANCATG}).
     */
    private final TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Card number of the most recently resolved cross-reference &mdash; the Java analog of COBOL
     * {@code WS-CURR-CARD-NUM} (initial {@code SPACES}). {@code null} until the first record is
     * processed. Part of the control-break cache described in the class Javadoc.
     */
    private String lastCardNum;

    /**
     * Account id resolved for {@link #lastCardNum} &mdash; the Java analog of the account held in
     * COBOL {@code XREF-ACCT-ID} across records that share a card number. Part of the control-break
     * cache described in the class Javadoc.
     */
    private Long lastAccountId;

    /**
     * Creates the processor with its collaborating repositories injected by constructor (no field
     * injection), reproducing the COBOL {@code CALL} linkage to the {@code CARDXREF},
     * {@code TRANTYPE}, and {@code TRANCATG} datasets as Spring-managed dependencies.
     *
     * @param cardXrefRepository            repository resolving a card number to its account
     *                                      ({@code 1500-A-LOOKUP-XREF}); must not be {@code null}
     * @param transactionTypeRepository     repository resolving a transaction-type description
     *                                      ({@code 1500-B-LOOKUP-TRANTYPE}); must not be {@code null}
     * @param transactionCategoryRepository repository resolving a transaction-category description
     *                                      ({@code 1500-C-LOOKUP-TRANCATG}); must not be {@code null}
     */
    public TransactionReportProcessor(
            CardXrefRepository cardXrefRepository,
            TransactionTypeRepository transactionTypeRepository,
            TransactionCategoryRepository transactionCategoryRepository) {
        // Direct field assignment only (no overridable method calls) keeps the constructor free of a
        // 'this-escape' under the project's -Xlint:all / failOnWarning build.
        this.cardXrefRepository = cardXrefRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.transactionCategoryRepository = transactionCategoryRepository;
    }

    /**
     * Transforms a single {@link Transaction} into a report {@link ReportLine}, reproducing the
     * {@code CBTRN03C} per-record body (MAIN loop L170-206) in its original evaluation order:
     * cross-reference (account) &rarr; transaction-type description &rarr; transaction-category
     * description &rarr; emit detail line.
     *
     * <p>A missing cross-reference, transaction type, or transaction category is a hard error that
     * throws {@link FileStatusException} (file status {@code "23"}, batch return code 8), faithfully
     * mirroring the COBOL {@code INVALID KEY} &rarr; {@code 9999-ABEND-PROGRAM} path. This method
     * always returns a non-{@code null} line (it never filters a record out) because the upstream
     * reader has already applied the date-range {@code INCLUDE} filter.</p>
     *
     * @param item the in-range transaction supplied by {@code transactionReportItemReader}, already
     *             filtered by processing date and ordered by card number; never {@code null}
     * @return a fully-populated {@link ReportLine} for the downstream writer stage
     * @throws FileStatusException if the card cross-reference, transaction type, or transaction
     *                             category cannot be found (COBOL abend parity, return code 8)
     */
    @Override
    public ReportLine process(Transaction item) {
        // --- 1500-A-LOOKUP-XREF (L484-492): resolve the account through the card cross-reference. ---
        // Card control break (MAIN loop L181): re-read the cross-reference only when the card number
        // changes; otherwise reuse the cached account, exactly as the COBOL guards the read with
        // IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM.
        final String cardNum = item.getCardNum();
        final Long accountId;
        if (cardNum != null && cardNum.equals(lastCardNum)) {
            // Cache hit: same card as the previous record; the resolved account is invariant.
            accountId = lastAccountId;
        } else {
            // Card changed (or first record): re-resolve. A missing cross-reference is the COBOL
            // INVALID KEY abend (IO-STATUS = 23 -> 9999-ABEND-PROGRAM), surfaced as return code 8.
            final Optional<CardXref> xrefOpt = cardXrefRepository.findById(cardNum);
            final CardXref xref = xrefOpt.orElseThrow(() -> new FileStatusException(
                    FileStatusException.STATUS_RECORD_NOT_FOUND,
                    "Cross-reference not found for card " + cardNum));
            accountId = xref.getAcctId();
            lastCardNum = cardNum;
            lastAccountId = accountId;
        }

        // --- 1500-B-LOOKUP-TRANTYPE (L494-502): resolve the transaction-type description. ---
        // Read for every record. Missing type = COBOL INVALID KEY abend (return code 8); never
        // defaulted or blanked.
        final String typeCd = item.getTypeCd();
        final Optional<TransactionType> typeOpt = transactionTypeRepository.findById(typeCd);
        final TransactionType type = typeOpt.orElseThrow(() -> new FileStatusException(
                FileStatusException.STATUS_RECORD_NOT_FOUND,
                "Transaction type not found for code " + typeCd));
        final String typeDesc = type.getTypeDesc();

        // --- 1500-C-LOOKUP-TRANCATG (L504-512): resolve the transaction-category description. ---
        // Read for every record using the compound key FD-TRAN-CAT-KEY = (TRAN-TYPE-CD, TRAN-CAT-CD).
        // Missing category = COBOL INVALID KEY abend (return code 8); never defaulted or blanked.
        final Integer catCd = item.getCatCd();
        final TransactionCategory.TransactionCategoryId categoryId =
                new TransactionCategory.TransactionCategoryId(typeCd, catCd);
        final Optional<TransactionCategory> categoryOpt =
                transactionCategoryRepository.findById(categoryId);
        final TransactionCategory category = categoryOpt.orElseThrow(() -> new FileStatusException(
                FileStatusException.STATUS_RECORD_NOT_FOUND,
                "Transaction category not found for key {type=" + typeCd + ", category=" + catCd + "}"));
        final String catDesc = category.getCatTypeDesc();

        // --- 1100-WRITE-TRANSACTION-REPORT / 1120-WRITE-DETAIL (L361-374): emit one detail line. ---
        // Carries every field the writer needs to format the 133-byte line and to perform the card
        // control break plus account/grand totals. The amount is passed through unchanged (a straight
        // COBOL MOVE TRAN-AMT TO TRAN-REPORT-AMT), preserving its DECIMAL(11,2) scale-2 value.
        return new ReportLine(
                item.getTranId(),
                accountId,
                cardNum,
                typeCd,
                typeDesc,
                catCd,
                catDesc,
                item.getTranSource(),
                item.getTranAmt());
    }

    /**
     * Immutable detail line emitted for a single transaction, the structured analog of the COBOL
     * {@code TRANSACTION-DETAIL-REPORT} record populated by {@code 1120-WRITE-DETAIL} (L361-374). The
     * sibling {@code writer/} stage consumes it as {@code TransactionReportProcessor.ReportLine} to
     * format the fixed-width report line and to compute card/account/grand totals.
     *
     * <p>{@code cardNum} is carried so the writer can perform the card control break and account-total
     * subtotals (COBOL {@code 1120-WRITE-ACCOUNT-TOTALS}); {@code accountId} is the account resolved
     * from the card cross-reference ({@code XREF-ACCT-ID}).</p>
     *
     * @param tranId    the 16-character transaction identifier (COBOL {@code TRAN-ID})
     * @param accountId the account id resolved via the card cross-reference (COBOL {@code XREF-ACCT-ID})
     * @param cardNum   the card number the transaction belongs to (COBOL {@code TRAN-CARD-NUM}),
     *                  retained so the writer can perform the card control break
     * @param typeCd    the transaction-type code (COBOL {@code TRAN-TYPE-CD})
     * @param typeDesc  the transaction-type description (COBOL {@code TRAN-TYPE-DESC})
     * @param catCd     the transaction-category code (COBOL {@code TRAN-CAT-CD})
     * @param catDesc   the transaction-category description (COBOL {@code TRAN-CAT-TYPE-DESC})
     * @param source    the transaction origination source (COBOL {@code TRAN-SOURCE})
     * @param amount    the signed transaction amount as a {@link BigDecimal} at scale 2
     *                  (COBOL {@code TRAN-AMT}, {@code PIC S9(9)V99 COMP-3})
     */
    public static record ReportLine(
            String tranId,
            Long accountId,
            String cardNum,
            String typeCd,
            String typeDesc,
            Integer catCd,
            String catDesc,
            String source,
            BigDecimal amount) {
    }
}

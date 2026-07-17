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
import java.math.RoundingMode;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DisclosureGroup;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.exception.FileStatusException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DisclosureGroupRepository;

/**
 * Spring Batch {@link ItemProcessor} that reproduces the per-row interest computation of the
 * legacy batch program {@code legacy/cbl/CBACT04C.cbl} (source {@code app/cbl/CBACT04C.cbl}),
 * the CardDemo interest calculator triggered by {@code INTCALC.jcl}
 * ({@code EXEC PGM=CBACT04C,PARM='2022071800'}). It is the transform side of
 * {@code InterestCalculationJob} and is fed by the sibling {@code reader/} bean
 * {@code transactionCategoryBalanceItemReader}, which streams
 * {@link TransactionCategoryBalance} rows ordered by
 * {@code (id.acctId, id.typeCd, id.catCd)} so that all category balances for one account arrive
 * contiguously (the input ordering the legacy account control break relies on).
 *
 * <h2>Legacy lineage (CBACT04C)</h2>
 * The COBOL program walks the transaction-category-balance file
 * ({@code TCATBAL-FILE}) and, for each category balance, looks up the account's disclosure-group
 * interest rate, computes a monthly interest amount, writes an interest {@link Transaction}
 * (type {@code 01}, category {@code 05}), and accumulates a per-account interest total that, on an
 * <em>account control break</em>, is added to the account balance while the cycle credit/debit are
 * reset ({@code 1050-UPDATE-ACCOUNT}). This processor implements only the <strong>per-row</strong>
 * portion of that flow: for each qualifying category-balance row it emits exactly one interest
 * transaction wrapped in an {@link InterestResult}. The per-account accumulation and the
 * {@code 1050-UPDATE-ACCOUNT} rewrite (add the accumulated interest to {@code ACCT-CURR-BAL}, zero
 * the cycle credit/debit, and persist the account) are the <strong>writer's</strong>
 * responsibility on account control break; this processor <strong>never mutates the account</strong>.
 * It reads the account only to obtain its disclosure-group id and reads the cross-reference only to
 * obtain the card number for the emitted transaction. {@code 1400-COMPUTE-FEES} is a documented
 * no-op in the legacy program and is therefore not implemented here.
 *
 * <p>Per-row block-to-paragraph mapping: account resolution mirrors {@code 1100-GET-ACCT-DATA};
 * cross-reference resolution mirrors {@code 1110-GET-XREF-DATA}; the rate lookup with its DEFAULT
 * fallback mirrors {@code 1200-GET-INTEREST-RATE} / {@code 1200-A-GET-DEFAULT-INT-RATE}; the
 * computation mirrors {@code 1300-COMPUTE-INTEREST}; and the transaction assembly mirrors
 * {@code 1300-B-WRITE-TX}. The evaluation order account &rarr; cross-reference &rarr; rate
 * (with DEFAULT fallback) &rarr; zero-rate guard &rarr; compute &rarr; build &rarr; emit is preserved
 * exactly.</p>
 *
 * <h2>Parity-critical interest formula</h2>
 * The legacy statement {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 * (CBACT04C {@code 1300-COMPUTE-INTEREST}, L464-465), where {@code WS-MONTHLY-INT} is
 * {@code PIC S9(09)V99}, is reproduced as
 * {@code tranCatBal.multiply(intRate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)}.
 * All arithmetic uses {@link BigDecimal} at scale&nbsp;2; {@code double}/{@code float} are never used.
 *
 * <p><strong>Rounding &mdash; documented parity deviation.</strong> The COBOL {@code COMPUTE} carries
 * no {@code ROUNDED} phrase and therefore <em>truncates</em> the result to scale&nbsp;2, whereas this
 * implementation applies {@link RoundingMode#HALF_UP}. This is the project-wide monetary rounding
 * standard (Technical Specification &sect;0.4.2 Money value object and &sect;0.7.1 H3); the
 * deviation is intentional and recorded in {@code docs/decision-log.md}.</p>
 *
 * <p><strong>Timestamp determinism note.</strong> The legacy program stamps
 * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} with the current DB2-format timestamp
 * ({@code Z-GET-DB2-FORMAT-TIMESTAMP}). This implementation uses {@link DateUtils#currentTimestamp()}
 * (mask {@code uuuu-MM-dd HH:mm:ss.SSSSSS}) for both, matching the common utility contract. A
 * {@code parmDate}-derived <em>deterministic</em> timestamp would make golden-file comparisons
 * byte-stable; that determinism trade-off is documented in {@code docs/decision-log.md}. Both
 * timestamps are set to the same value, as the COBOL does.</p>
 *
 * <h2>Step scope and the transaction-id suffix counter</h2>
 * The 10-character {@code parmDate} (for example {@code 2022071800}) is a late-bound job parameter,
 * so the bean is {@link StepScope step-scoped}: a singleton could not resolve
 * {@code #{jobParameters['parmDate']}}. Step scoping additionally guarantees a fresh instance per
 * step execution, which resets the mutable {@link #tranIdSuffix} counter to {@code 0} at the start
 * of every run &mdash; the exact behavior of the COBOL {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0}.
 * The suffix is incremented <em>before</em> each id is built and zero-padded to six digits, so the
 * emitted {@code TRAN-ID} is {@code parmDate}(10) + suffix(6) = 16 characters, reproducing
 * {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID}. Because Spring Batch
 * chunk processing is single-threaded by default (matching the legacy sequential read loop), the
 * counter needs no synchronization.
 *
 * <p><strong>Per-row resolution.</strong> The COBOL resolves the account and cross-reference once per
 * account (on control break) and caches them across the account's category rows. This processor,
 * being strictly per-row, re-resolves them for every row. The result is identical &mdash; every row
 * of a given account resolves to the same account (hence the same group id) and the same first
 * cross-reference (hence the same card number) &mdash; so this is a performance characteristic of the
 * per-row decomposition, not a behavioral change.</p>
 *
 * @see TransactionCategoryBalance
 * @see DisclosureGroup
 * @see Transaction
 * @see ItemProcessor
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Component
@StepScope
public class InterestCalculationProcessor
        implements ItemProcessor<TransactionCategoryBalance, InterestCalculationProcessor.InterestResult> {

    /** Diagnostic logger. Never logs PII; only computed interest, ids, and key codes at debug level. */
    private static final Logger log = LoggerFactory.getLogger(InterestCalculationProcessor.class);

    /**
     * Fallback disclosure-group id used when no rate row exists for the account's own group,
     * mirroring the COBOL {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} in
     * {@code 1200-GET-INTEREST-RATE} when the primary read returns {@code FILE STATUS '23'}.
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Interest transaction type code ({@code MOVE '01' TO TRAN-TYPE-CD}). */
    private static final String INTEREST_TRAN_TYPE_CD = "01";

    /**
     * Interest transaction category code. The COBOL {@code MOVE '05' TO TRAN-CAT-CD} sets the
     * numeric category {@code 0005}; the {@link Transaction} entity models {@code TRAN-CAT-CD}
     * ({@code PIC 9(04)}) as an {@link Integer}, so this is {@code 5}.
     */
    private static final Integer INTEREST_TRAN_CAT_CD = 5;

    /** Interest transaction source ({@code MOVE 'System' TO TRAN-SOURCE}). */
    private static final String INTEREST_TRAN_SOURCE = "System";

    /**
     * Literal description prefix ({@code STRING 'Int. for a/c ', ACCT-ID ... INTO TRAN-DESC}). The
     * trailing space is significant and preserved verbatim.
     */
    private static final String TRAN_DESC_PREFIX = "Int. for a/c ";

    /** Merchant id for a system-generated interest transaction ({@code MOVE 0 TO TRAN-MERCHANT-ID}). */
    private static final Long INTEREST_MERCHANT_ID = 0L;

    /**
     * Value written to the merchant name/city/zip fields. The COBOL moves {@code SPACES}; the
     * fixed-width external contract is reconstituted downstream by the writer/codec, so an empty
     * string is the faithful in-memory representation of "no merchant".
     */
    private static final String SPACES = "";

    /** Zero-padded six-digit format for the transaction-id suffix ({@code WS-TRANID-SUFFIX PIC 9(06)}). */
    private static final String TRAN_ID_SUFFIX_FORMAT = "%06d";

    /** Repository for {@code 1100-GET-ACCT-DATA} account lookups. */
    private final AccountRepository accountRepository;

    /** Repository for {@code 1110-GET-XREF-DATA} card cross-reference lookups. */
    private final CardXrefRepository cardXrefRepository;

    /** Repository for {@code 1200-GET-INTEREST-RATE} disclosure-group lookups. */
    private final DisclosureGroupRepository disclosureGroupRepository;

    /**
     * The 10-character run date parameter (the {@code INTCALC.jcl} {@code PARM}, for example
     * {@code 2022071800}) forming the high-order portion of every emitted {@code TRAN-ID}. Late-bound
     * from the {@code parmDate} job parameter; hence the {@link StepScope} on this bean.
     */
    private final String parmDate;

    /**
     * Per-run monotonically increasing transaction-id suffix, mirroring COBOL
     * {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0}. Reset to {@code 0} for each step execution by
     * {@link StepScope}; incremented before each id is built. Single-threaded chunk processing makes
     * it safe without synchronization.
     */
    private long tranIdSuffix = 0L;

    /**
     * Creates a step-scoped interest processor.
     *
     * <p>Dependencies are supplied by constructor injection (never field injection) and held as
     * {@code final}. The {@code parmDate} argument is late-bound from the step's job parameters via
     * SpEL, so it resolves only within a running step &mdash; the reason this bean is
     * {@link StepScope step-scoped}.</p>
     *
     * @param accountRepository        repository used to resolve the account (for its disclosure-group
     *                                 id); never {@code null}
     * @param cardXrefRepository       repository used to resolve the account's card cross-reference
     *                                 (for the interest transaction's card number); never {@code null}
     * @param disclosureGroupRepository repository used to resolve the interest rate, with a
     *                                 {@code DEFAULT}-group fallback; never {@code null}
     * @param parmDate                 the 10-character run date (the {@code INTCALC.jcl} {@code PARM}),
     *                                 late-bound from the {@code parmDate} job parameter
     */
    public InterestCalculationProcessor(
            AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            DisclosureGroupRepository disclosureGroupRepository,
            @Value("#{jobParameters['parmDate']}") String parmDate) {
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.disclosureGroupRepository = disclosureGroupRepository;
        this.parmDate = parmDate;
    }

    /**
     * Transforms one transaction-category-balance row into an interest {@link Transaction}, wrapped
     * in an {@link InterestResult} that also carries the account id and the computed interest for the
     * writer's per-account accumulation.
     *
     * <p>Reproduces the per-row body of the CBACT04C main loop. The steps and their legacy
     * paragraphs are:</p>
     * <ol>
     *   <li>Extract the composite key ({@code TRANCAT-ACCT-ID}, {@code TRANCAT-TYPE-CD},
     *       {@code TRANCAT-CD}) and the running balance ({@code TRAN-CAT-BAL}).</li>
     *   <li>{@code 1100-GET-ACCT-DATA} &mdash; read the account; a missing account is a hard file
     *       error in the legacy program (it abends), reproduced here as a
     *       {@link FileStatusException} (mapped to batch return code&nbsp;8).</li>
     *   <li>{@code 1110-GET-XREF-DATA} &mdash; read the card cross-reference by account id; a missing
     *       cross-reference is likewise reproduced as a {@link FileStatusException} to preserve the
     *       legacy abend outcome.</li>
     *   <li>{@code 1200-GET-INTEREST-RATE} / {@code 1200-A} &mdash; read the disclosure group for the
     *       account's own group; on miss ({@code FILE STATUS '23'}) retry with the literal
     *       {@code DEFAULT} group. If neither exists, return {@code null} to skip the row (documented
     *       edge choice; see below).</li>
     *   <li>Zero-rate guard ({@code IF DIS-INT-RATE NOT = 0}) &mdash; a zero rate produces no interest
     *       transaction, so return {@code null} (Spring Batch filters the item).</li>
     *   <li>{@code 1300-COMPUTE-INTEREST} &mdash; compute the monthly interest with the parity-critical
     *       formula.</li>
     *   <li>{@code 1300-B-WRITE-TX} &mdash; assemble the interest transaction.</li>
     *   <li>Emit the {@link InterestResult}.</li>
     * </ol>
     *
     * <p><strong>Disclosure-group miss (documented edge choice).</strong> When neither the account's
     * own group nor the {@code DEFAULT} group has a rate row for the {@code (type, category)} pair,
     * the strict legacy behavior is to abend (the {@code 1200-A} read status is not {@code '00'}).
     * This implementation instead returns {@code null} (skip, emit nothing) so that no interest is
     * fabricated from an absent rate; the reference data seeds a {@code DEFAULT} row for every active
     * {@code (type, category)}, so this branch is not expected at runtime. The choice is recorded in
     * {@code docs/decision-log.md}.</p>
     *
     * <p>A {@code null} return has one of two parity-preserving meanings, both matching the COBOL: a
     * zero interest rate (no transaction computed/written) or an absent rate row (skip).</p>
     *
     * @param item the transaction-category-balance row to process; never {@code null}
     * @return an {@link InterestResult} carrying the interest transaction, the computed monthly
     *         interest, and the account id; or {@code null} to filter the row (zero or absent rate)
     * @throws FileStatusException if the account or its card cross-reference cannot be found
     *                             (reproducing the legacy hard-file-error abend, batch RC&nbsp;8)
     */
    @Override
    public InterestResult process(TransactionCategoryBalance item) {
        // ---- Extract the composite key + running balance (rows arrive ordered by account) ----
        final TransactionCategoryBalance.TransactionCategoryBalanceId key = item.getId();
        final Long acctId = key.getAcctId();
        final String typeCd = key.getTypeCd();
        final Integer catCd = key.getCatCd();
        final BigDecimal tranCatBal = item.getBal();

        // ---- 1100-GET-ACCT-DATA: resolve the account to obtain its disclosure-group id. ----
        // A missing account is a hard file error in CBACT04C (INVALID KEY -> abend); reproduce as
        // FileStatusException so the caller-visible outcome (batch RC 8) is preserved.
        final Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new FileStatusException(
                        FileStatusException.STATUS_RECORD_NOT_FOUND,
                        "Account not found for interest calc: " + acctId));
        final String groupId = account.getGroupId();

        // ---- 1110-GET-XREF-DATA: resolve the card cross-reference by account id for the card number.
        // The legacy program reads XREF via its alternate account-id key; a miss abends, so treat a
        // missing cross-reference as a FileStatusException to preserve that outcome.
        final CardXref xref = cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(acctId)
                .orElseThrow(() -> new FileStatusException(
                        FileStatusException.STATUS_RECORD_NOT_FOUND,
                        "Card cross-reference not found for interest calc, account: " + acctId));
        final String cardNum = xref.getXrefCardNum();

        // ---- 1200-GET-INTEREST-RATE (+ 1200-A DEFAULT fallback) ----
        // Primary lookup on the account's own disclosure group; on miss, retry the DEFAULT group.
        Optional<DisclosureGroup> disclosureGroup = disclosureGroupRepository.findById(
                new DisclosureGroup.DisclosureGroupId(groupId, typeCd, catCd));
        if (disclosureGroup.isEmpty()) {
            disclosureGroup = disclosureGroupRepository.findById(
                    new DisclosureGroup.DisclosureGroupId(DEFAULT_GROUP_ID, typeCd, catCd));
        }
        if (disclosureGroup.isEmpty()) {
            // Neither the specific group nor DEFAULT has a rate row: skip rather than fabricate
            // interest (documented deviation from the legacy abend; see method Javadoc).
            log.debug("No disclosure-group rate (specific or DEFAULT) for account {} type {} cat {}; skipping row",
                    acctId, typeCd, catCd);
            return null;
        }
        final BigDecimal intRate = disclosureGroup.get().getIntRate();

        // ---- Zero-rate guard: COBOL "IF DIS-INT-RATE NOT = 0" gates 1300; a zero rate emits nothing.
        if (intRate.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // ---- 1300-COMPUTE-INTEREST: (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 at scale 2. ----
        // Rounding: HALF_UP per docs/decision-log.md (COBOL COMPUTE truncates; AAP standardizes HALF_UP).
        final BigDecimal monthlyInterest = tranCatBal
                .multiply(intRate)
                .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);

        // ---- 1300-B-WRITE-TX: assemble the interest transaction. ----
        // COBOL: ADD 1 TO WS-TRANID-SUFFIX, then STRING PARM-DATE + WS-TRANID-SUFFIX INTO TRAN-ID.
        tranIdSuffix += 1L;
        final String tranId = parmDate + String.format(TRAN_ID_SUFFIX_FORMAT, tranIdSuffix);
        // TRAN-ORIG-TS and TRAN-PROC-TS are set to the same current timestamp, as the COBOL does.
        final String timestamp = DateUtils.currentTimestamp();
        final Transaction interestTransaction = new Transaction(
                tranId,                                 // TRAN-ID  (parmDate[10] + suffix[6] = 16)
                INTEREST_TRAN_TYPE_CD,                  // TRAN-TYPE-CD = '01'
                INTEREST_TRAN_CAT_CD,                   // TRAN-CAT-CD  = 0005
                INTEREST_TRAN_SOURCE,                   // TRAN-SOURCE  = 'System'
                TRAN_DESC_PREFIX + acctId,              // TRAN-DESC = 'Int. for a/c ' + ACCT-ID (9(11) in COBOL)
                monthlyInterest,                        // TRAN-AMT
                INTEREST_MERCHANT_ID,                   // TRAN-MERCHANT-ID = 0
                SPACES,                                 // TRAN-MERCHANT-NAME = SPACES
                SPACES,                                 // TRAN-MERCHANT-CITY = SPACES
                SPACES,                                 // TRAN-MERCHANT-ZIP  = SPACES
                cardNum,                                // TRAN-CARD-NUM = XREF-CARD-NUM
                timestamp,                              // TRAN-ORIG-TS
                timestamp);                             // TRAN-PROC-TS

        log.debug("Computed interest {} for account {} (type {}, cat {}) -> tranId {}",
                monthlyInterest, acctId, typeCd, catCd, tranId);

        // ---- Emit for the writer's per-account accumulation + 1050-UPDATE-ACCOUNT on control break.
        return new InterestResult(interestTransaction, monthlyInterest, acctId);
    }

    /**
     * Output of {@link InterestCalculationProcessor#process(TransactionCategoryBalance)}, consumed by
     * the sibling {@code writer/} bean as {@code InterestCalculationProcessor.InterestResult}.
     *
     * <p>Beyond the interest {@link Transaction} to be written, this record carries the
     * {@code monthlyInterest} and {@code acctId} so the writer can reproduce the legacy per-account
     * accumulation ({@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT}) and, on account control break, the
     * {@code 1050-UPDATE-ACCOUNT} step (add the accumulated total to {@code ACCT-CURR-BAL} and zero
     * the cycle credit/debit). Keeping the account mutation out of the processor keeps this component
     * side-effect free and independently testable.</p>
     *
     * @param interestTransaction the interest transaction to be persisted (type {@code 01},
     *                            category {@code 05}); never {@code null}
     * @param monthlyInterest     the computed monthly interest (scale&nbsp;2) for per-account
     *                            accumulation; never {@code null}
     * @param acctId              the account id this interest belongs to, the control-break key for
     *                            the writer; never {@code null}
     */
    public static record InterestResult(
            Transaction interestTransaction,
            BigDecimal monthlyInterest,
            Long acctId) {
    }
}

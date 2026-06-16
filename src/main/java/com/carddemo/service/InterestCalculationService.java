package com.carddemo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Spring service that ports the legacy COBOL batch program {@code CBACT04C}
 * ({@code app/cbl/CBACT04C.cbl}) &mdash; the CardDemo <strong>interest calculator</strong> &mdash;
 * to Java with 100% functional and arithmetic parity (AAP &sect;0.6.3, &sect;0.7.1).
 *
 * <h2>What the legacy program does</h2>
 * <p>{@code CBACT04C} reads the {@code TCATBALF} (transaction-category-balance) VSAM dataset
 * sequentially, <em>grouped by account id</em>, and for every account it:</p>
 * <ol>
 *   <li>loads the {@code ACCOUNT} master record and the {@code CARDXREF} cross-reference record
 *       (the latter keyed by account id) &mdash; paragraphs {@code 1100-GET-ACCT-DATA} and
 *       {@code 1110-GET-XREF-DATA};</li>
 *   <li>for each per-category balance row, resolves the disclosure-group interest rate from the
 *       {@code DISCGRP} dataset &mdash; keyed by {@code (ACCT-GROUP-ID, TRAN-TYPE-CD, TRAN-CAT-CD)}
 *       and falling back to the {@code 'DEFAULT'} group when the keyed read misses (VSAM file
 *       status {@code 23}) &mdash; paragraphs {@code 1200-GET-INTEREST-RATE} /
 *       {@code 1200-A-GET-DEFAULT-INT-RATE};</li>
 *   <li><strong>only when the rate is non-zero</strong> ({@code IF DIS-INT-RATE NOT = 0}, L214),
 *       computes monthly interest {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, accumulates it into
 *       a per-account total, and writes one interest {@code TRANSACT} row &mdash; paragraphs
 *       {@code 1300-COMPUTE-INTEREST} / {@code 1300-B-WRITE-TX};</li>
 *   <li>on the account boundary (and at end-of-file), flushes the account by adding the accumulated
 *       interest to {@code ACCT-CURR-BAL}, zeroing both cycle totals
 *       ({@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT}) and rewriting it &mdash;
 *       paragraph {@code 1050-UPDATE-ACCOUNT} (L350-356).</li>
 * </ol>
 * <p>The fee paragraph {@code 1400-COMPUTE-FEES} is an explicit {@code 'To be implemented'} stub
 * (L518-520); there is therefore <strong>no fee logic</strong> in this service.</p>
 *
 * <h2>Java re-expression strategy</h2>
 * <p>Rather than reproduce the COBOL's streaming account-boundary detection, this service models the
 * natural unit of work as <strong>one account fully processed per call</strong>
 * ({@link #calculateInterestForAccount(Long, String)}). That method inherently performs the COBOL
 * account flush at the end, which is cleaner and makes the service directly reusable by the batch
 * job ({@code batch/InterestCalculationJobConfig}, created separately) on a per-item basis.
 * {@link #calculateInterest(String)} is a convenience full-run orchestration that reproduces the
 * COBOL iteration order by driving from the category-balance table.</p>
 *
 * <h2>Parity-critical arithmetic (AAP &sect;0.6.3, &sect;0.7.1)</h2>
 * <p>All monetary computation uses {@link BigDecimal} exclusively &mdash; never {@code double} or
 * {@code float} &mdash; with an explicit scale of {@link CardDemoConstants#MONEY_SCALE} (2) and
 * {@link RoundingMode#HALF_UP}, dividing by {@link CardDemoConstants#INTEREST_DIVISOR} (1200, i.e.
 * annual-percent &divide; 100 &divide; 12 months). This mirrors COBOL fixed-point semantics and is
 * the anchor validated by {@code FinancialParityTest}.</p>
 *
 * <h2>Layering and dependencies</h2>
 * <p>Strict layered architecture (AAP &sect;0.3.2): this service orchestrates repositories only,
 * via constructor injection, and contains no web/controller types. It deliberately imports
 * <strong>no</strong> Spring Batch types and nothing from {@code com.carddemo.batch}, so the batch
 * job can depend on this service without creating a circular dependency.</p>
 *
 * @see <a href="file:app/cbl/CBACT04C.cbl">app/cbl/CBACT04C.cbl (authoritative algorithm)</a>
 * @see TransactionCategoryBalance
 * @see DisclosureGroup
 * @see Account
 * @see Transaction
 * @see CardXref
 */
@Service
public class InterestCalculationService {

    private static final Logger log = LoggerFactory.getLogger(InterestCalculationService.class);

    /**
     * Total width of a generated interest transaction id, in characters. Mirrors the legacy
     * {@code TRAN-ID PIC X(16)} field (and the COBOL {@code STRING PARM-DATE WS-TRANID-SUFFIX}
     * that fills it). MUST remain exactly 16.
     */
    private static final int TRAN_ID_WIDTH = 16;

    /**
     * Width of the run-date prefix portion of a generated interest transaction id. Eight digits
     * accommodate a {@code yyyyMMdd} date (the run date with non-digit separators stripped).
     */
    private static final int DATE_PREFIX_WIDTH = 8;

    /**
     * Width of the incrementing suffix portion of a generated interest transaction id
     * ({@code TRAN_ID_WIDTH - DATE_PREFIX_WIDTH}). The suffix is zero-padded to this width.
     */
    private static final int SUFFIX_WIDTH = TRAN_ID_WIDTH - DATE_PREFIX_WIDTH;

    /**
     * Modulus that keeps the suffix within {@link #SUFFIX_WIDTH} digits (10<sup>8</sup>). Purely
     * defensive: the realistic per-run interest-transaction count is in the hundreds, far below this
     * bound, so wrap-around never occurs for the seeded dataset.
     */
    private static final long SUFFIX_MODULUS = 100_000_000L;

    /**
     * Disclosure-group id used as the rate fallback when an account's specific group has no matching
     * {@code DISCGRP} row &mdash; the COBOL {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} of
     * paragraph {@code 1200-GET-INTEREST-RATE} (L437).
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * Logical resource type used when raising {@link ResourceNotFoundException} for a missing
     * account, producing the message {@code "Account not found with id: <id>"}.
     */
    private static final String RESOURCE_ACCOUNT = "Account";

    private final TransactionCategoryBalanceRepository tcbRepository;
    private final DisclosureGroupRepository disclosureGroupRepository;
    private final AccountRepository accountRepository;
    private final CardXrefRepository cardXrefRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Per-instance, monotonically increasing suffix counter used to mint unique interest transaction
     * ids within a run. Reproduces the COBOL {@code WS-TRANID-SUFFIX PIC 9(06)} working-storage
     * counter ({@code ADD 1 TO WS-TRANID-SUFFIX}, L474). It is reset at the start of a full run via
     * {@link #calculateInterest(String)} so each run produces a contiguous suffix sequence; when
     * {@link #calculateInterestForAccount(Long, String)} is invoked standalone (e.g. by the batch job
     * per item, or by tests) it simply keeps incrementing, which still guarantees uniqueness.
     *
     * <p>An {@link AtomicLong} is used so id generation remains correct even if a future caller
     * invokes the per-account method concurrently across threads.</p>
     */
    private final AtomicLong tranIdSuffix = new AtomicLong(0L);

    /**
     * Constructs the service with all collaborating repositories (constructor injection; no field
     * injection, per the strict-layering rule of AAP &sect;0.3.2).
     *
     * @param tcbRepository             access to {@code transaction_category_balance}
     *                                  (legacy {@code TCATBALF}); supplies the per-category balances
     *                                  iterated by the interest algorithm
     * @param disclosureGroupRepository access to {@code disclosure_group} (legacy {@code DISCGRP});
     *                                  supplies interest rates with {@code 'DEFAULT'} fallback
     * @param accountRepository         access to {@code accounts} (legacy {@code ACCTFILE}); loads
     *                                  and rewrites the account master
     * @param cardXrefRepository        access to {@code card_xref} (legacy {@code CARDXREF}); resolves
     *                                  the card number stamped onto each interest transaction
     * @param transactionRepository     access to {@code transactions} (legacy {@code TRANSACT});
     *                                  persists the interest transaction rows
     */
    public InterestCalculationService(TransactionCategoryBalanceRepository tcbRepository,
                                      DisclosureGroupRepository disclosureGroupRepository,
                                      AccountRepository accountRepository,
                                      CardXrefRepository cardXrefRepository,
                                      TransactionRepository transactionRepository) {
        this.tcbRepository = tcbRepository;
        this.disclosureGroupRepository = disclosureGroupRepository;
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Full-run interest calculation &mdash; the convenience orchestration that reproduces the overall
     * iteration semantics of {@code CBACT04C}'s mainline ({@code PERFORM UNTIL END-OF-FILE}, L188-222).
     *
     * <p>The COBOL program iterates the {@code TCATBALF} dataset, so it only ever touches accounts
     * that have at least one category-balance row; accounts without any {@code TCATBAL} rows are never
     * loaded and their cycle totals are never zeroed. To preserve that behavior exactly, this method
     * drives iteration from the category-balance table (NOT from {@code accountRepository.findAll()},
     * which would incorrectly flush accounts that have no category rows):</p>
     * <ol>
     *   <li>collect the <strong>distinct</strong> account ids present in
     *       {@code transaction_category_balance}, sorted ascending to mirror the keyed sequential read
     *       order of the VSAM dataset;</li>
     *   <li>delegate to {@link #calculateInterestForAccount(Long, String)} for each.</li>
     * </ol>
     *
     * <p>The transaction-id suffix counter is reset at the start so the run produces a contiguous
     * suffix sequence (mirroring {@code WS-TRANID-SUFFIX} starting from zero each execution).</p>
     *
     * @param runDate the run/parameter date (the COBOL {@code PARM-DATE PIC X(10)}, e.g.
     *                {@code "2024-01-31"}); used as the prefix of every generated interest
     *                transaction id
     */
    @Transactional
    public void calculateInterest(String runDate) {
        log.info("Starting interest calculation run for runDate={}", runDate);

        // Reset the per-run suffix so transaction ids start fresh (COBOL WS-TRANID-SUFFIX = 0).
        tranIdSuffix.set(0L);

        // Drive iteration from TCATBAL (not all accounts): distinct, sorted account ids. This mirrors
        // CBACT04C reading the TCATBALF dataset in key order and is the parity-correct iteration set.
        List<Long> accountIds = tcbRepository.findAll().stream()
                .map(row -> row.getId().getAcctId())
                .distinct()
                .sorted()
                .toList();

        log.info("Interest calculation will process {} account(s) with category balances", accountIds.size());

        for (Long acctId : accountIds) {
            calculateInterestForAccount(acctId, runDate);
        }

        log.info("Completed interest calculation run for runDate={}", runDate);
    }

    /**
     * Calculates and posts interest for a single account &mdash; the primary, self-contained unit of
     * work shared with the batch job. This reproduces the per-account slice of {@code CBACT04C}: the
     * account/xref load, the per-category rate resolution and interest computation, the interest
     * transaction writes, and the final account flush ({@code 1050-UPDATE-ACCOUNT}).
     *
     * <p>The method is {@link Transactional}, so the reads (category balances, disclosure rates,
     * account, cross-reference) and the writes (interest transactions and the account update) all
     * commit as one unit of work &mdash; mirroring the COBOL program's per-account
     * {@code REWRITE}-and-continue flush boundary.</p>
     *
     * <h3>Algorithm (per CBACT04C)</h3>
     * <ol>
     *   <li>Load the account ({@code 1100-GET-ACCT-DATA}); a missing account raises
     *       {@link ResourceNotFoundException} (HTTP 404 via the global handler).</li>
     *   <li>Resolve the cross-reference card number for the account ({@code 1110-GET-XREF-DATA}).</li>
     *   <li>For each category-balance row (iterated in a deterministic {@code (type_cd, cat_cd)}
     *       order): resolve the disclosure rate (with {@code 'DEFAULT'} fallback) and, only when the
     *       rate is non-zero, compute {@code balance * rate / 1200} (scale 2, HALF_UP), accumulate it
     *       into the running total, and write one interest transaction.</li>
     *   <li>Flush the account: add the accumulated interest to the current balance, zero both cycle
     *       totals, and save ({@code 1050-UPDATE-ACCOUNT}).</li>
     * </ol>
     *
     * @param acctId  the account identifier to process; must exist in {@code accounts}
     * @param runDate the run/parameter date used as the interest transaction-id prefix (COBOL
     *                {@code PARM-DATE})
     * @return the total interest accrued and posted to the account in this call (scale 2); never
     *         {@code null}. Returns {@code 0.00} when the account has no category rows or no non-zero
     *         rates apply.
     * @throws ResourceNotFoundException if no account exists for {@code acctId}
     */
    @Transactional
    public BigDecimal calculateInterestForAccount(Long acctId, String runDate) {
        // 1100-GET-ACCT-DATA: load the account master; absence is a hard error (HTTP 404).
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE_ACCOUNT, acctId));

        // 1110-GET-XREF-DATA: resolve the card number cross-referenced to this account. Defensive
        // empty-string fallback if no xref exists (the seed guarantees one per account).
        String cardNum = resolveCardNumber(acctId);

        // Fetch the per-category balance rows and iterate them in a stable (type_cd, cat_cd) order so
        // the run is deterministic. The total is order-independent (a sum), but a stable order keeps
        // interest-transaction id assignment reproducible.
        List<TransactionCategoryBalance> rows = new ArrayList<>(tcbRepository.findByIdAcctId(acctId));
        rows.sort(Comparator
                .comparing((TransactionCategoryBalance row) -> row.getId().getTypeCd())
                .thenComparing(row -> row.getId().getCatCd()));

        // WS-TOTAL-INT = 0 at the account boundary (L200). Initialised as a scale-2 zero so the
        // accumulator, the returned total, and the balance addition all carry NUMERIC(12,2) scale.
        BigDecimal totalInterest = BigDecimal.ZERO.setScale(CardDemoConstants.MONEY_SCALE);

        for (TransactionCategoryBalance row : rows) {
            String typeCd = row.getId().getTypeCd();
            Integer catCd = row.getId().getCatCd();

            // 1200-GET-INTEREST-RATE (+ 1200-A DEFAULT fallback): rate keyed by the account's group.
            BigDecimal rate = resolveInterestRate(account.getGroupId(), typeCd, catCd);

            // IF DIS-INT-RATE NOT = 0 (L214): skip zero/absent rates entirely (no interest, no tx).
            if (rate.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            // 1300-COMPUTE-INTEREST (L464-465): monthly interest = (balance * rate) / 1200.
            // BigDecimal only, scale 2, HALF_UP, divisor 1200 (AAP §0.6.3 / §0.7.1 — mandatory form).
            BigDecimal categoryBalance = row.getTranCatBal();
            BigDecimal monthlyInterest = categoryBalance
                    .multiply(rate)
                    .divide(BigDecimal.valueOf(CardDemoConstants.INTEREST_DIVISOR),
                            CardDemoConstants.MONEY_SCALE,
                            RoundingMode.HALF_UP);

            // ADD WS-MONTHLY-INT TO WS-TOTAL-INT (L467).
            totalInterest = totalInterest.add(monthlyInterest);

            // 1300-B-WRITE-TX (L473-500): one interest transaction per non-zero category.
            writeInterestTransaction(account, cardNum, monthlyInterest, runDate);
        }

        // 1050-UPDATE-ACCOUNT (L350-356): flush the accumulated interest and zero the cycle totals.
        flushAccount(account, totalInterest);

        log.debug("Posted interest of {} to account {}", totalInterest, acctId);
        return totalInterest;
    }

    /**
     * Resolves the disclosure-group interest rate for a category, reproducing the COBOL
     * {@code 1200-GET-INTEREST-RATE} read plus its {@code 1200-A-GET-DEFAULT-INT-RATE} fallback
     * (CBACT04C L415-460).
     *
     * <p>The {@code DISCGRP} dataset is first read by the account's own group id; a not-found
     * condition (VSAM file status {@code 23}, modelled here as an empty {@link Optional}) triggers a
     * retry against the {@code 'DEFAULT'} group. A row that <em>is</em> found is used as-is &mdash;
     * the COBOL never falls back on a successful read &mdash; so a present row whose rate is
     * {@code null}/zero yields a zero rate (and therefore no interest), without consulting
     * {@code 'DEFAULT'}. When neither the keyed nor the default read finds a row, the rate is
     * {@link BigDecimal#ZERO}.</p>
     *
     * @param groupId the account's disclosure/pricing group id ({@code ACCT-GROUP-ID}); may be
     *                {@code null}/blank, in which case the keyed read simply misses and the
     *                {@code 'DEFAULT'} fallback applies
     * @param typeCd  the transaction type code component of the key ({@code TRAN-TYPE-CD})
     * @param catCd   the transaction category code component of the key ({@code TRAN-CAT-CD})
     * @return the resolved interest rate; never {@code null} (zero when no applicable rate exists)
     */
    private BigDecimal resolveInterestRate(String groupId, String typeCd, Integer catCd) {
        // 1200-GET-INTEREST-RATE: keyed read by the account's own disclosure group.
        Optional<DisclosureGroup> primary = disclosureGroupRepository.findById(
                new DisclosureGroup.DisclosureGroupId(groupId, typeCd, catCd));
        if (primary.isPresent()) {
            // A successful read is authoritative — the COBOL does not fall back here.
            BigDecimal rate = primary.get().getDisIntRate();
            return (rate != null) ? rate : BigDecimal.ZERO;
        }

        // 1200-A-GET-DEFAULT-INT-RATE: status 23 (not found) → retry against the 'DEFAULT' group.
        Optional<DisclosureGroup> fallback = disclosureGroupRepository.findById(
                new DisclosureGroup.DisclosureGroupId(DEFAULT_GROUP_ID, typeCd, catCd));
        if (fallback.isPresent()) {
            BigDecimal rate = fallback.get().getDisIntRate();
            return (rate != null) ? rate : BigDecimal.ZERO;
        }

        // Neither the keyed nor the default group has a row: no interest applies.
        return BigDecimal.ZERO;
    }

    /**
     * Resolves the cross-reference card number for an account, reproducing the COBOL
     * {@code 1110-GET-XREF-DATA} read of {@code CARDXREF} keyed by account id (CBACT04C L393-413).
     *
     * <p>The repository exposes a paginated {@code findByXrefAcctId}; the first matching row's card
     * number is used. The seed data guarantees a cross-reference per account, so the empty branch is
     * a defensive fallback: it logs a warning and returns an empty string (so interest is still
     * computed and the balance still updated, matching the COBOL intent of never failing the run on a
     * missing card number), rather than aborting.</p>
     *
     * @param acctId the owning account identifier
     * @return the 16-character cross-reference card number, or an empty string if none exists
     */
    private String resolveCardNumber(Long acctId) {
        Page<CardXref> xrefPage = cardXrefRepository.findByXrefAcctId(acctId, PageRequest.of(0, 1));
        if (xrefPage.hasContent()) {
            return xrefPage.getContent().get(0).getXrefCardNum();
        }
        log.warn("No card cross-reference found for account {}; interest transaction(s) will carry an "
                + "empty card number", acctId);
        return "";
    }

    /**
     * Builds and persists one interest transaction, reproducing the field-by-field assignment of the
     * COBOL {@code 1300-B-WRITE-TX} paragraph (CBACT04C L473-500).
     *
     * <p>Field mapping: type code {@code '01'}, category {@code '05'} (the 2-char COBOL literal,
     * parsed to the {@link Integer} {@code 5} that {@code Transaction.catCd} requires), source
     * {@code 'System'}, description {@code "Int. for a/c " + acctId} (the constant prefix preserves
     * its trailing space, mirroring {@code STRING 'Int. for a/c ' , ACCT-ID}), amount = the computed
     * monthly interest, merchant id {@code 0}, merchant name/city/zip empty strings (COBOL
     * {@code MOVE SPACES}, rendered as {@code ""} to honour the NOT-NULL columns), the cross-reference
     * card number, the denormalized owning account id, and both the origination and processing
     * timestamps set to the current instant.</p>
     *
     * @param account         the account the interest is being posted for (supplies the id used in
     *                        the description and the denormalized {@code acct_id})
     * @param cardNum         the cross-reference card number to stamp on the transaction
     * @param monthlyInterest the computed interest amount for this category (scale 2)
     * @param runDate         the run date used to derive the 16-character transaction id
     */
    private void writeInterestTransaction(Account account,
                                          String cardNum,
                                          BigDecimal monthlyInterest,
                                          String runDate) {
        Transaction tx = new Transaction();

        // TRAN-ID: run date + incrementing suffix, exactly 16 chars (see buildInterestTranId).
        tx.setTranId(buildInterestTranId(runDate));

        // MOVE '01' TO TRAN-TYPE-CD (L482).
        tx.setTypeCd(CardDemoConstants.INTEREST_TRAN_TYPE_CD);

        // MOVE '05' TO TRAN-CAT-CD (L483): the constant is the 2-char String "05"; Transaction.catCd
        // is an Integer, so parse the literal to its numeric category value 5.
        tx.setCatCd(Integer.parseInt(CardDemoConstants.INTEREST_TRAN_CAT_CD));

        // MOVE 'System' TO TRAN-SOURCE (L484).
        tx.setSource(CardDemoConstants.INTEREST_TRAN_SOURCE);

        // STRING 'Int. for a/c ' , ACCT-ID INTO TRAN-DESC (L485-489): prefix keeps its trailing space.
        tx.setDescription(CardDemoConstants.INTEREST_TRAN_DESC_PREFIX + account.getAcctId());

        // MOVE WS-MONTHLY-INT TO TRAN-AMT (L490).
        tx.setAmt(monthlyInterest);

        // MOVE 0 TO TRAN-MERCHANT-ID (L491).
        tx.setMerchantId(0L);

        // MOVE SPACES TO TRAN-MERCHANT-NAME/CITY/ZIP (L492-494): empty strings, never null.
        tx.setMerchantName("");
        tx.setMerchantCity("");
        tx.setMerchantZip("");

        // MOVE XREF-CARD-NUM TO TRAN-CARD-NUM (L495).
        tx.setCardNum(cardNum);

        // Denormalized owning account id (backs the (acct_id, orig_ts) browse index; not in the
        // copybook but required by the relational model).
        tx.setAcctId(account.getAcctId());

        // MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS and TRAN-PROC-TS (L496-498): both set to "now".
        LocalDateTime now = LocalDateTime.now();
        tx.setOrigTs(now);
        tx.setProcTs(now);

        transactionRepository.save(tx);
    }

    /**
     * Applies the per-account flush, reproducing the COBOL {@code 1050-UPDATE-ACCOUNT} paragraph
     * (CBACT04C L350-356): add the accumulated interest to the current balance, zero both
     * current-cycle totals, and rewrite (save) the account.
     *
     * <p>The cycle totals are set to a scale-2 zero ({@code 0.00}) to match the {@code NUMERIC(12,2)}
     * columns. {@link BigDecimal} is immutable, so a single zero instance is safely shared between the
     * two setters. The save flows through the optimistic-locking-aware {@code AccountRepository},
     * incrementing the entity {@code @Version}.</p>
     *
     * @param account       the account to flush (mutated in place, then saved)
     * @param totalInterest the total interest accrued for the account in this run (scale 2)
     */
    private void flushAccount(Account account, BigDecimal totalInterest) {
        // ADD WS-TOTAL-INT TO ACCT-CURR-BAL (L352). Treat a null balance as zero defensively.
        BigDecimal currentBalance = (account.getCurrBal() != null)
                ? account.getCurrBal()
                : BigDecimal.ZERO.setScale(CardDemoConstants.MONEY_SCALE);
        account.setCurrBal(currentBalance.add(totalInterest));

        // MOVE 0 TO ACCT-CURR-CYC-CREDIT / ACCT-CURR-CYC-DEBIT (L353-354): scale-2 zero.
        BigDecimal zeroMoney = BigDecimal.ZERO.setScale(CardDemoConstants.MONEY_SCALE);
        account.setCurrCycCredit(zeroMoney);
        account.setCurrCycDebit(zeroMoney);

        // REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD (L356).
        accountRepository.save(account);
    }

    /**
     * Mints a 16-character interest transaction id from the run date and an incrementing suffix,
     * reproducing the intent of the COBOL {@code STRING PARM-DATE WS-TRANID-SUFFIX INTO TRAN-ID}
     * (CBACT04C L474-480) while guaranteeing the exact {@code TRAN-ID PIC X(16)} width (AAP
     * &sect;0.6.5).
     *
     * <p>Composition (always exactly {@value #TRAN_ID_WIDTH} characters):</p>
     * <ul>
     *   <li><strong>Prefix ({@value #DATE_PREFIX_WIDTH} chars):</strong> the run date with all
     *       non-digit characters stripped (e.g. {@code "2024-01-31"} &rarr; {@code "20240131"}),
     *       truncated to the leading 8 digits if longer, or right-padded with {@code '0'} if shorter
     *       (including a {@code null}/blank run date).</li>
     *   <li><strong>Suffix ({@value #SUFFIX_WIDTH} chars):</strong> a per-run, monotonically
     *       increasing counter, zero-padded. The counter is reset by {@link #calculateInterest(String)}
     *       at the start of a full run; standalone per-account calls keep incrementing it.</li>
     * </ul>
     *
     * <p>This guarantees the three hard requirements: derived from the run date plus a suffix, exactly
     * 16 characters, and unique within a run (the suffix differentiates rows; the date prefix
     * differentiates runs). The exact id string is not a parity anchor &mdash; {@code FinancialParityTest}
     * validates the interest <em>amounts</em> and resulting balance &mdash; but it is always a valid,
     * unique, 16-character id.</p>
     *
     * @param runDate the run/parameter date (COBOL {@code PARM-DATE}); may be {@code null}
     * @return a unique, exactly-16-character interest transaction id
     */
    private String buildInterestTranId(String runDate) {
        // ADD 1 TO WS-TRANID-SUFFIX (L474): obtain the next suffix value for this run.
        long suffix = tranIdSuffix.incrementAndGet();

        // Strip non-digits from the run date to form the date prefix (e.g. "2024-01-31" -> "20240131").
        String dateDigits = (runDate == null) ? "" : runDate.replaceAll("\\D", "");

        // Normalise the prefix to exactly DATE_PREFIX_WIDTH characters (truncate-or-right-pad-with-0).
        StringBuilder datePrefix = new StringBuilder(
                dateDigits.length() > DATE_PREFIX_WIDTH
                        ? dateDigits.substring(0, DATE_PREFIX_WIDTH)
                        : dateDigits);
        while (datePrefix.length() < DATE_PREFIX_WIDTH) {
            datePrefix.append('0');
        }

        // Zero-pad the suffix to SUFFIX_WIDTH digits; the modulus keeps it within that width.
        String suffixStr = String.format("%0" + SUFFIX_WIDTH + "d", suffix % SUFFIX_MODULUS);

        // datePrefix (8) + suffixStr (8) == 16. The final guard is a defensive width assertion.
        String tranId = datePrefix.append(suffixStr).toString();
        return (tranId.length() > TRAN_ID_WIDTH) ? tranId.substring(0, TRAN_ID_WIDTH) : tranId;
    }
}

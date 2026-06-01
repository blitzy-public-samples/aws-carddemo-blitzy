package com.carddemo.service;

import com.carddemo.batch.StatementHtmlBuilder;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.BigDecimalUtil;
import com.carddemo.util.CardNumberMasker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Statement business service replacing the orchestration logic of the COBOL batch program
 * {@code app/cbl/CBSTM03A.CBL} (a batch statement generator &mdash; NOT a CICS online program).
 *
 * <p>It provides reusable statement-build logic consumed by the Spring Batch statement-generation
 * job (through {@code StatementGenerationTasklet}) and by any future online API for on-demand
 * statement display.</p>
 *
 * <h2>COBOL source mapping</h2>
 * <ul>
 *   <li>{@code 1000-MAINLINE} (CBSTM03A.CBL) &rarr; {@link #buildAllStatements()} iterates every
 *       {@link CardXref} row; {@link #buildStatementForXref(CardXref)} processes one card.</li>
 *   <li>{@code 2000-CUSTFILE-GET} &rarr; {@link CustomerRepository#findById(Object)} keyed read of
 *       the owning customer.</li>
 *   <li>{@code 3000-ACCTFILE-GET} &rarr; {@link AccountRepository#findById(Object)} keyed read of
 *       the owning account.</li>
 *   <li>{@code 4000-TRNXFILE-GET} (and the {@code WS-TRNX-TABLE} cache) &rarr;
 *       {@link TransactionRepository#findByCardNum(String)} fetch of the card's transactions and
 *       the {@code WS-TOTAL-AMT} running total.</li>
 *   <li>{@code 5000-CREATE-STATEMENT} &rarr; {@link #renderPlainText} reproduces the 80-column
 *       {@code FD-STMTFILE-REC} layout ({@code ST-LINE0} through {@code ST-LINE15}).</li>
 *   <li>{@code 5100/5200/6000} HTML emission &rarr; delegated to
 *       {@link com.carddemo.batch.StatementHtmlBuilder#renderFullStatement} which preserves the
 *       COBOL HTML byte-for-byte (PR-09).</li>
 * </ul>
 *
 * <h2>Preservation rules honored</h2>
 * <ul>
 *   <li><b>PR-09</b> &mdash; this service emits NO HTML of its own; the entire HTML document is
 *       produced by {@link com.carddemo.batch.StatementHtmlBuilder}. There are intentionally no
 *       markup string literals here.</li>
 *   <li><b>PR-16</b> &mdash; the running total is a {@link BigDecimal} normalized to scale 2 via
 *       {@link BigDecimalUtil#ensureScaleTwo(BigDecimal)}; no {@code float}/{@code double} is
 *       ever used for money, and the edited money fields mirror the COBOL {@code PIC} clauses.</li>
 *   <li><b>PR-20</b> &mdash; the customer SSN is never read or emitted; only name, mailing
 *       address, FICO score, account id and balance, and transactions appear on a statement.</li>
 *   <li><b>PR-24</b> &mdash; the public build methods run inside a read-only
 *       {@link Transactional} scope, mirroring the implicit CICS unit-of-work / {@code SYNCPOINT}
 *       semantics and enabling Hibernate read-only optimizations.</li>
 *   <li><b>PR-29</b> &mdash; collaborators are injected by constructor over {@code final} fields
 *       (Lombok {@link RequiredArgsConstructor}); no field injection.</li>
 * </ul>
 *
 * <p>This service performs no writes: transactions and master data are read solely to compile the
 * statement. A missing customer or account for an existing cross-reference indicates a data
 * integrity violation and raises {@link IllegalStateException}.</p>
 *
 * @see com.carddemo.batch.StatementHtmlBuilder
 * @see CardXref
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementService {

    // ----------------------------------------------------------------------------------------
    // Fixed-width layout constants — mirror CBSTM03A STATEMENT-LINES (FD-STMTFILE-REC PIC X(80)).
    // Every emitted plain-text line is exactly LINE_WIDTH characters, matching the COBOL records.
    // ----------------------------------------------------------------------------------------

    /** Width of {@code FD-STMTFILE-REC} (COBOL {@code PIC X(80)}); every plain-text line is 80. */
    private static final int LINE_WIDTH = 80;
    /** Width of {@code ST-NAME} (CBSTM03A.CBL L91, {@code PIC X(75)}). */
    private static final int NAME_WIDTH = 75;
    /** Width of {@code ST-ADD1}/{@code ST-ADD2} (CBSTM03A.CBL L94/L97, {@code PIC X(50)}). */
    private static final int ADDR_WIDTH = 50;
    /** Width of the "Basic Details" label fields ({@code ST-LINE7/8/9} prefix, {@code PIC X(20)}). */
    private static final int LABEL_WIDTH = 20;
    /** Width of the alphanumeric account-id / FICO target fields ({@code PIC X(20)}). */
    private static final int VALUE20_WIDTH = 20;
    /** Width of the edited money fields {@code 9(9).99-} / {@code Z(9).99-} (13 characters). */
    private static final int MONEY_WIDTH = 13;
    /** Width of {@code ST-TRANID} (CBSTM03A.CBL L133, {@code PIC X(16)}). */
    private static final int TRANID_WIDTH = 16;
    /** Width of {@code ST-TRANDT} (CBSTM03A.CBL L135, {@code PIC X(49)}). */
    private static final int TRANDT_WIDTH = 49;

    /** {@code ST-LINE0} literal (CBSTM03A.CBL L88), 18 characters. */
    private static final String START_BANNER_TEXT = "START OF STATEMENT";
    /** {@code ST-LINE15} literal (CBSTM03A.CBL L145), 16 characters. */
    private static final String END_BANNER_TEXT = "END OF STATEMENT";
    /** {@code ST-LINE5/10/12} rule line: {@code FILLER ALL '-' PIC X(80)} (CBSTM03A.CBL L102). */
    private static final String RULE_LINE = "-".repeat(LINE_WIDTH);

    // ----------------------------------------------------------------------------------------
    // Collaborators — constructor-injected over final fields (PR-29).
    // ----------------------------------------------------------------------------------------

    /** Enumerates card cross-references ({@code 1000-MAINLINE} XREF loop driver). */
    private final CardXrefRepository cardXrefRepository;
    /** Loads the owning customer ({@code 2000-CUSTFILE-GET}). */
    private final CustomerRepository customerRepository;
    /** Loads the owning account ({@code 3000-ACCTFILE-GET}). */
    private final AccountRepository accountRepository;
    /** Fetches the card's transactions ({@code 4000-TRNXFILE-GET}). */
    private final TransactionRepository transactionRepository;
    /** Renders the HTML statement byte-for-byte (PR-09); this service emits no HTML itself. */
    private final StatementHtmlBuilder statementHtmlBuilder;

    /**
     * Immutable result of compiling one customer statement.
     *
     * <p>Carries both rendered representations &mdash; the 80-column plain-text statement
     * ({@code FD-STMTFILE-REC} equivalent) and the HTML document ({@code FD-HTMLFILE-REC}
     * equivalent) &mdash; alongside the structured fields a caller may need without re-parsing
     * the rendered text. The SSN is deliberately absent (PR-20).</p>
     *
     * @param customerName    the customer's display name, "First Middle Last" with single-space
     *                        separators and blank parts skipped
     * @param acctId          the statement account id ({@code ACCT-ID})
     * @param currentBalance  the current account balance ({@code ACCT-CURR-BAL}), scale 2
     * @param ficoScore       the customer FICO credit score ({@code CUST-FICO-CREDIT-SCORE})
     * @param transactions    the transactions included on the statement (in repository order)
     * @param totalAmount     the running total of transaction amounts ({@code WS-TOTAL-AMT}),
     *                        normalized to scale 2 (PR-16)
     * @param plainTextOutput the 80-column plain-text statement
     * @param htmlOutput      the HTML statement rendered by {@link StatementHtmlBuilder} (PR-09)
     */
    public record StatementResult(
            String customerName,
            Long acctId,
            BigDecimal currentBalance,
            Integer ficoScore,
            List<Transaction> transactions,
            BigDecimal totalAmount,
            String plainTextOutput,
            String htmlOutput) {
    }

    /**
     * Builds the complete statement for a single card cross-reference &mdash; the Java port of one
     * iteration of CBSTM03A {@code 1000-MAINLINE}.
     *
     * <p>Resolves the owning customer and account, loads the card's transactions, computes the
     * running total, and renders both the plain-text and HTML representations. Runs read-only
     * (PR-24): no entity is modified.</p>
     *
     * @param xref the card cross-reference to build a statement for (must not be {@code null})
     * @return the compiled {@link StatementResult}
     * @throws IllegalStateException if the customer or account referenced by {@code xref} does not
     *                               exist (a data-integrity violation; mirrors the COBOL
     *                               {@code DFHRESP(NOTFND)} abort path for a dangling reference)
     */
    @Transactional(readOnly = true)
    public StatementResult buildStatementForXref(CardXref xref) {
        // F9: never log the full PAN — emit only a masked, correlation-safe form.
        log.debug("Building statement for xref custId={} acctId={} card={}",
                xref.getCustId(), xref.getAccountId(),
                CardNumberMasker.mask(xref.getXrefCardNum()));

        // 1. Read Customer — CBSTM03A 2000-CUSTFILE-GET (keyed read by XREF-CUST-ID).
        Customer customer = customerRepository.findById(xref.getCustId())
                .orElseThrow(() -> customerNotFound(xref));

        // 2. Read Account — CBSTM03A 3000-ACCTFILE-GET (keyed read by XREF-ACCT-ID).
        Account account = accountRepository.findById(xref.getAccountId())
                .orElseThrow(() -> accountNotFound(xref));

        // 3. Read Transactions for this card — CBSTM03A 4000-TRNXFILE-GET
        //    (replaces the WS-TRNX-TABLE in-memory cache with a DB-backed lookup).
        List<Transaction> transactions =
                transactionRepository.findByCardNum(xref.getXrefCardNum());

        // 4-7. Compute the running total and render both representations. Shared with the
        //      batch path (buildAllStatements) so PR-09/PR-16 rendering is identical.
        return assembleStatement(xref, customer, account, transactions);
    }

    /**
     * Assembles one {@link StatementResult} from already-resolved master and transaction data
     * &mdash; the shared tail (steps 4&ndash;7 of CBSTM03A {@code 1000-MAINLINE}) used by both
     * {@link #buildStatementForXref(CardXref)} (which resolves the data by keyed read) and
     * {@link #buildAllStatements()} (which resolves it from prefetched maps). Centralizing the
     * render path guarantees byte-for-byte parity (PR-09) regardless of how the inputs were
     * fetched.
     *
     * @param xref         the cross-reference being rendered (used only for the masked log key)
     * @param customer     the resolved owning customer (never {@code null})
     * @param account      the resolved owning account (never {@code null})
     * @param transactions the card's transactions (never {@code null}; possibly empty)
     * @return the compiled {@link StatementResult}
     */
    private StatementResult assembleStatement(CardXref xref, Customer customer, Account account,
                                              List<Transaction> transactions) {
        // 4. Compute the running total — mirrors the WS-TOTAL-AMT accumulator (PR-16, scale 2).
        BigDecimal total = transactions.stream()
                .map(Transaction::getAmount)
                .map(amt -> amt != null ? amt : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        total = BigDecimalUtil.ensureScaleTwo(total);

        // 5. Build the display name — CBSTM03A STRING first ' ' middle ' ' last (5000).
        String customerName = buildCustomerName(customer);

        // 6. Render the 80-column plain-text statement — CBSTM03A FD-STMTFILE-REC layout.
        String plainText = renderPlainText(customer, account, transactions, total, customerName);

        // 7. Render the HTML statement — delegated to preserve PR-09 byte-for-byte fidelity.
        String html = statementHtmlBuilder.renderFullStatement(customer, account, transactions);

        // F9: masked card key only — never the full PAN.
        log.debug("Statement built: acctId={} card={} transactions={} total={}",
                account.getAcctId(), CardNumberMasker.mask(xref.getXrefCardNum()),
                transactions.size(), total);

        return new StatementResult(
                customerName,
                account.getAcctId(),
                account.getCurrBal(),
                customer.getFicoScore(),
                transactions,
                total,
                plainText,
                html);
    }

    /**
     * Builds the data-integrity {@link IllegalStateException} for a cross-reference whose
     * customer is missing. F9: the card number is masked (last four only) so neither the log
     * nor the client-facing error payload exposes a full PAN.
     */
    private static IllegalStateException customerNotFound(CardXref xref) {
        return new IllegalStateException(
                "Customer not found for cross-reference: custId=" + xref.getCustId()
                        + " card=" + CardNumberMasker.mask(xref.getXrefCardNum()));
    }

    /**
     * Builds the data-integrity {@link IllegalStateException} for a cross-reference whose
     * account is missing. F9: the card number is masked (last four only).
     */
    private static IllegalStateException accountNotFound(CardXref xref) {
        return new IllegalStateException(
                "Account not found for cross-reference: acctId=" + xref.getAccountId()
                        + " card=" + CardNumberMasker.mask(xref.getXrefCardNum()));
    }

    /**
     * Builds statements for every card cross-reference &mdash; the Java port of the full CBSTM03A
     * {@code 1000-MAINLINE} loop. Used by the batch statement-generation job.
     *
     * <p>Enumerates all {@link CardXref} rows (the COBOL XREF browse) and compiles one statement
     * per card. Runs read-only (PR-24).</p>
     *
     * <p><b>F10</b> &mdash; master and transaction data are prefetched in three bulk queries
     * ({@code findAll()} for customers, accounts, and transactions) into in-memory lookup maps,
     * then each cross-reference is resolved against those maps. This replaces the previous
     * 3&times;N per-row keyed reads (an N+1 pattern) with a constant handful of queries for the
     * whole run; the rendered output is byte-for-byte unchanged.</p>
     *
     * @return one {@link StatementResult} per cross-reference, in repository iteration order
     * @throws IllegalStateException if any cross-reference points at a missing customer or account
     */
    @Transactional(readOnly = true)
    public List<StatementResult> buildAllStatements() {
        List<CardXref> allXrefs = cardXrefRepository.findAll();
        log.info("Building statements for all accounts: {} cross-reference(s)", allXrefs.size());

        // F10: prefetch master/transaction data in a few bulk queries instead of issuing
        //      three keyed reads per cross-reference (the prior 3xN N+1 pattern).
        Map<Long, Customer> customersById = customerRepository.findAll().stream()
                .collect(Collectors.toMap(Customer::getCustId, Function.identity()));
        Map<Long, Account> accountsById = accountRepository.findAll().stream()
                .collect(Collectors.toMap(Account::getAcctId, Function.identity()));
        Map<String, List<Transaction>> transactionsByCardNum = transactionRepository.findAll().stream()
                .filter(t -> t.getCardNum() != null)
                .collect(Collectors.groupingBy(Transaction::getCardNum));

        // Resolve each cross-reference from the prefetched maps, preserving the COBOL
        // data-integrity abort (IllegalStateException) when a customer or account is missing.
        List<StatementResult> results = new ArrayList<>(allXrefs.size());
        for (CardXref xref : allXrefs) {
            Customer customer = customersById.get(xref.getCustId());
            if (customer == null) {
                throw customerNotFound(xref);
            }
            Account account = accountsById.get(xref.getAccountId());
            if (account == null) {
                throw accountNotFound(xref);
            }
            List<Transaction> transactions =
                    transactionsByCardNum.getOrDefault(xref.getXrefCardNum(), List.of());
            results.add(assembleStatement(xref, customer, account, transactions));
        }
        return results;
    }

    // ----------------------------------------------------------------------------------------
    // Private rendering helpers — plain-text statement (CBSTM03A 5000-CREATE-STATEMENT).
    // No HTML is produced here; HTML is delegated to StatementHtmlBuilder (PR-09).
    // ----------------------------------------------------------------------------------------

    /**
     * Concatenates the customer's first, middle, and last names with single-space separators,
     * skipping any {@code null} or blank part. Mirrors the COBOL {@code STRING CUST-FIRST-NAME
     * DELIMITED BY ' ' ... CUST-LAST-NAME} (CBSTM03A.CBL L462-L469) in its modern form: e.g.
     * first {@code "John"}, middle {@code "Q"}, last {@code "Smith"} yields {@code "John Q Smith"};
     * a blank middle name yields {@code "John Smith"}.
     *
     * @param customer the customer whose name parts are joined (must not be {@code null})
     * @return the single-spaced display name (never {@code null}; possibly empty)
     */
    private String buildCustomerName(Customer customer) {
        return joinWithSpace(
                customer.getFirstName(),
                customer.getMiddleName(),
                customer.getLastName());
    }

    /**
     * Renders the 80-column plain-text statement, reproducing the CBSTM03A {@code FD-STMTFILE-REC}
     * write sequence ({@code ST-LINE0} through {@code ST-LINE15}) exactly in section order. Every
     * returned line is {@link #LINE_WIDTH} characters wide and terminated by a single newline.
     *
     * @param customer     the statement customer (name/address/FICO source)
     * @param account      the statement account (id/balance source)
     * @param transactions the transactions to list
     * @param total        the running total (already scale 2)
     * @param customerName  the pre-built display name (reused for the name line)
     * @return the multi-line plain-text statement
     */
    private String renderPlainText(Customer customer,
                                   Account account,
                                   List<Transaction> transactions,
                                   BigDecimal total,
                                   String customerName) {
        StringBuilder out = new StringBuilder(1024);

        // ST-LINE0 — START OF STATEMENT banner: 31 '*' + literal(18) + 31 '*'.
        appendLine(out, "*".repeat(31) + START_BANNER_TEXT + "*".repeat(31));
        // ST-LINE1 — customer name in a 75-char field followed by 5 trailing spaces.
        appendLine(out, field(customerName, NAME_WIDTH) + spaces(LINE_WIDTH - NAME_WIDTH));
        // ST-LINE2 / ST-LINE3 — address lines 1 and 2 in 50-char fields + 30 trailing spaces.
        appendLine(out, field(customer.getAddrLine1(), ADDR_WIDTH) + spaces(LINE_WIDTH - ADDR_WIDTH));
        appendLine(out, field(customer.getAddrLine2(), ADDR_WIDTH) + spaces(LINE_WIDTH - ADDR_WIDTH));
        // ST-LINE4 — composed address line 3 (line3 + state + country + zip) across the full 80.
        appendLine(out, field(buildAddressLine3(customer), LINE_WIDTH));
        // ST-LINE5 — rule line.
        appendLine(out, RULE_LINE);
        // ST-LINE6 — centered "Basic Details" (33 spaces + 14-char field + 33 spaces).
        appendLine(out, spaces(33) + field("Basic Details", 14) + spaces(33));
        // ST-LINE5 — rule line (written again by the COBOL).
        appendLine(out, RULE_LINE);
        // ST-LINE7 — Account ID label + 20-char account value + 40 trailing spaces.
        appendLine(out, basicLabel("Account ID")
                + formatAcctId20(account.getAcctId())
                + spaces(LINE_WIDTH - LABEL_WIDTH - VALUE20_WIDTH));
        // ST-LINE8 — Current Balance label + 13-char edited money + 47 trailing spaces.
        appendLine(out, basicLabel("Current Balance")
                + formatStCurrBal(account.getCurrBal())
                + spaces(LINE_WIDTH - LABEL_WIDTH - MONEY_WIDTH));
        // ST-LINE9 — FICO Score label + 20-char value + 40 trailing spaces.
        appendLine(out, basicLabel("FICO Score")
                + formatFico20(customer.getFicoScore())
                + spaces(LINE_WIDTH - LABEL_WIDTH - VALUE20_WIDTH));
        // ST-LINE10 — rule line.
        appendLine(out, RULE_LINE);
        // ST-LINE11 — centered "TRANSACTION SUMMARY" (30 spaces + 20-char field + 30 spaces).
        appendLine(out, spaces(30) + field("TRANSACTION SUMMARY", 20) + spaces(30));
        // ST-LINE12 — rule line.
        appendLine(out, RULE_LINE);
        // ST-LINE13 — column headers: "Tran ID"(16) + "Tran Details"(51) + "  Tran Amount"(13).
        appendLine(out, field("Tran ID", TRANID_WIDTH)
                + field("Tran Details", 51)
                + "  Tran Amount");
        // ST-LINE12 — rule line separating headers from rows.
        appendLine(out, RULE_LINE);
        // ST-LINE14 — one row per transaction: id(16) + ' ' + details(49) + '$' + amount(13).
        for (Transaction tx : transactions) {
            appendLine(out, field(tx.getTranId(), TRANID_WIDTH)
                    + " "
                    + field(tx.getDescription(), TRANDT_WIDTH)
                    + "$"
                    + formatStTranAmt(tx.getAmount()));
        }
        // ST-LINE12 — rule line before the total.
        appendLine(out, RULE_LINE);
        // ST-LINE14A — "Total EXP:"(10) + 56 spaces + '$' + edited total(13).
        appendLine(out, field("Total EXP:", 10)
                + spaces(56)
                + "$"
                + formatStTranAmt(total));
        // ST-LINE15 — END OF STATEMENT banner: 32 '*' + literal(16) + 32 '*'.
        appendLine(out, "*".repeat(32) + END_BANNER_TEXT + "*".repeat(32));

        return out.toString();
    }

    /**
     * Composes address line 3 from {@code CUST-ADDR-LINE-3}, {@code CUST-ADDR-STATE-CD},
     * {@code CUST-ADDR-COUNTRY-CD}, and {@code CUST-ADDR-ZIP} joined by single spaces, mirroring
     * the COBOL {@code STRING ... DELIMITED BY ' '} statement in {@code 5000-CREATE-STATEMENT}
     * (CBSTM03A.CBL L472-L481), skipping {@code null}/blank parts.
     *
     * @param customer the customer whose address fields are joined
     * @return the composed third address line (never {@code null}; possibly empty)
     */
    private String buildAddressLine3(Customer customer) {
        return joinWithSpace(
                customer.getAddrLine3(),
                customer.getStateCd(),
                customer.getCountryCd(),
                customer.getZipCd());
    }

    /**
     * Joins the given parts with single-space separators, skipping any {@code null} or
     * blank-only part and trimming each retained part. Shared by {@link #buildCustomerName}
     * and {@link #buildAddressLine3}.
     *
     * @param parts the parts to join (may contain {@code null} or blank entries)
     * @return the single-spaced concatenation (never {@code null}; possibly empty)
     */
    private static String joinWithSpace(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part.trim());
            }
        }
        return sb.toString();
    }

    /**
     * Builds a 20-character "Basic Details" label: the text left-justified in 19 characters
     * followed by a {@code ':'} at position 20, matching the COBOL literals
     * {@code 'Account ID         :'}, {@code 'Current Balance    :'}, and
     * {@code 'FICO Score         :'} ({@code PIC X(20)}).
     *
     * @param text the label text (e.g. {@code "Account ID"})
     * @return the 20-character label ending in {@code ':'}
     */
    private static String basicLabel(String text) {
        return field(text, LABEL_WIDTH - 1) + ":";
    }

    /**
     * Formats an account id as the COBOL {@code MOVE ACCT-ID (PIC 9(11)) TO ST-ACCT-ID
     * (PIC X(20))} does: the 11-digit value with leading zeros, left-justified and space-padded
     * to 20 characters (e.g. {@code 1} renders as {@code "00000000001"} followed by 9 spaces).
     *
     * @param acctId the account id (treated as {@code 0} when {@code null})
     * @return the 20-character account-id field
     */
    private static String formatAcctId20(Long acctId) {
        long id = (acctId == null) ? 0L : acctId;
        return field(String.format("%011d", id), VALUE20_WIDTH);
    }

    /**
     * Formats a FICO score as the COBOL {@code MOVE CUST-FICO-CREDIT-SCORE (PIC 9(03)) TO
     * ST-FICO-SCORE (PIC X(20))} does: 3 digits with leading zeros, left-justified and
     * space-padded to 20 characters (e.g. {@code 750} renders as {@code "750"} followed by
     * 17 spaces).
     *
     * @param fico the FICO score (treated as {@code 0} when {@code null})
     * @return the 20-character FICO field
     */
    private static String formatFico20(Integer fico) {
        int score = (fico == null) ? 0 : fico;
        return field(String.format("%03d", score), VALUE20_WIDTH);
    }

    /**
     * Formats a balance as the COBOL edited field {@code ST-CURR-BAL PIC 9(9).99-} (13 characters):
     * 9 integer digits with leading zeros, a decimal point, 2 fraction digits, and a trailing sign
     * position (a space when the value is non-negative, {@code '-'} when negative). Scale-2
     * normalization is applied first (PR-16); a {@code null} value formats as zero.
     *
     * @param value the monetary value
     * @return the 13-character edited balance field
     */
    private static String formatStCurrBal(BigDecimal value) {
        BigDecimal scaled = BigDecimalUtil.ensureScaleTwo(value);
        boolean negative = scaled.signum() < 0;
        BigDecimal abs = scaled.abs();
        long intPart = abs.longValue();
        long cents = abs.movePointRight(BigDecimalUtil.SCALE_TWO).longValue() % 100L;
        return String.format("%09d.%02d", intPart, cents) + (negative ? '-' : ' ');
    }

    /**
     * Formats an amount as the COBOL edited field {@code ST-TRANAMT PIC Z(9).99-} (13 characters):
     * 9 integer positions with leading-zero <em>suppression</em> (blanks; an all-zero integer is
     * 9 blanks), a decimal point, 2 fraction digits, and a trailing sign position (space when
     * non-negative, {@code '-'} when negative). Scale-2 normalization is applied first (PR-16);
     * a {@code null} value formats as zero.
     *
     * @param value the monetary value
     * @return the 13-character edited amount field
     */
    private static String formatStTranAmt(BigDecimal value) {
        BigDecimal scaled = BigDecimalUtil.ensureScaleTwo(value);
        boolean negative = scaled.signum() < 0;
        BigDecimal abs = scaled.abs();
        long intPart = abs.longValue();
        long cents = abs.movePointRight(BigDecimalUtil.SCALE_TWO).longValue() % 100L;
        String intStr = (intPart == 0L) ? "" : Long.toString(intPart);
        return String.format("%9s.%02d", intStr, cents) + (negative ? '-' : ' ');
    }

    /**
     * Returns {@code content} left-justified within exactly {@code width} characters: truncated
     * when longer, right-padded with spaces when shorter. A {@code null} {@code content} is
     * treated as empty. Reproduces the COBOL fixed-width {@code MOVE ... TO} alphanumeric field
     * semantics used throughout the {@code STATEMENT-LINES} layout.
     *
     * @param content the content to place in the field (may be {@code null})
     * @param width   the exact field width (must be {@code >= 0})
     * @return a string of exactly {@code width} characters
     */
    private static String field(String content, int width) {
        String value = (content == null) ? "" : content;
        if (value.length() == width) {
            return value;
        }
        if (value.length() > width) {
            return value.substring(0, width);
        }
        return value + spaces(width - value.length());
    }

    /**
     * Returns a string of {@code count} spaces (empty when {@code count <= 0}). Reproduces the
     * COBOL {@code FILLER VALUE SPACES} padding used to fill each {@code ST-LINE} to 80 columns.
     *
     * @param count the number of spaces
     * @return a string of {@code count} spaces
     */
    private static String spaces(int count) {
        return count <= 0 ? "" : " ".repeat(count);
    }

    /**
     * Appends {@code line} followed by a single newline to {@code out}, modeling one COBOL
     * {@code WRITE FD-STMTFILE-REC} of an 80-byte record.
     *
     * @param out  the accumulating buffer
     * @param line the 80-character line content
     */
    private static void appendLine(StringBuilder out, String line) {
        out.append(line).append('\n');
    }
}

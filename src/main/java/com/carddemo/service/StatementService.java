package com.carddemo.service;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.CardDemoConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Account-statement generation service &mdash; the Java re-expression of the legacy COBOL batch
 * program {@code CBSTM03A} ({@code app/cbl/CBSTM03A.CBL}).
 *
 * <h2>COBOL lineage</h2>
 * <p>{@code CBSTM03A} ("Print Account Statements from Transaction data in two formats: 1/plain text
 * and 2/HTML") walked the card cross-reference, and for each record read the owning customer (by
 * {@code XREF-CUST-ID}) and account (by {@code XREF-ACCT-ID}), emitted a statement header
 * ({@code 5000-CREATE-STATEMENT}), then accumulated that account's transactions
 * ({@code 4000-TRNXFILE-GET} summing {@code TRNX-AMT} into {@code WS-TOTAL-AMT}) and wrote the total.
 * Its companion subroutine {@code CBSTM03B} ({@code app/cbl/CBSTM03B.CBL}) was a pure VSAM file-I/O
 * dispatcher (open/read/close of the TRNX/XREF/CUST/ACCT files); it has <strong>no Java analog</strong>
 * &mdash; its file operations are replaced here by the injected Spring Data repositories.</p>
 *
 * <p>In this migration a statement is generated <strong>per account</strong>: the (future) Spring
 * Batch job {@code batch/StatementCreationJobConfig} iterates accounts / cross-references and invokes
 * {@link #generateStatement(Long, LocalDate, LocalDate)} once per account. This service therefore
 * deliberately re-expresses only the per-account statement assembly and rendering of {@code CBSTM03A},
 * not its file-walking control block (TIOT) inspection, which was a mainframe diagnostic with no
 * functional bearing on statement content.</p>
 *
 * <h2>Output formats</h2>
 * <p>For each account the service renders the statement in both legacy formats &mdash; a fixed-layout
 * <strong>plain-text</strong> document (mirroring {@code ST-LINE0..ST-LINE15}) and a complete
 * <strong>HTML</strong> document (mirroring the {@code HTML-LINES} 88-level constant lines) &mdash; and
 * writes them to the directory configured by {@code report.output.path}. The two rendering routines are
 * exposed as the public {@link #buildTextStatement} / {@link #buildHtmlStatement} builders so that the
 * batch job and unit tests can assert on (or consume) the rendered content without touching the file
 * system.</p>
 *
 * <h2>Cross-cutting rules (AAP &sect;0.6 / &sect;0.7)</h2>
 * <ul>
 *   <li><strong>Money.</strong> The statement total is accumulated as a {@link BigDecimal} at
 *       {@link CardDemoConstants#MONEY_SCALE} (scale&nbsp;2) with {@link RoundingMode#HALF_UP},
 *       reproducing the COBOL {@code WS-TOTAL-AMT} fixed-point arithmetic exactly. {@code double} /
 *       {@code float} are never used.</li>
 *   <li><strong>PII suppression (AAP &sect;0.6.8).</strong> The statement legitimately shows the
 *       customer name, address, FICO score and account balance (all present on the legacy statement),
 *       but it MUST NOT include the customer SSN or any card CVV and MUST NOT log them. Accordingly this
 *       service never reads {@code Customer#getSsn()} / {@code getGovtIssuedId()} nor any card CVV, and
 *       its log statements carry only the non-sensitive account id and file paths.</li>
 *   <li><strong>Strict layering.</strong> The service orchestrates repositories only via constructor
 *       injection; it contains no controller/web types and imports <em>no</em>
 *       {@code com.carddemo.batch.*} or Spring Batch type, so it can be shared by the batch job without
 *       creating a dependency cycle.</li>
 *   <li><strong>Determinism.</strong> Transactions are processed in origination-timestamp order (the
 *       repository returns them {@code OrderByOrigTs}) and files are written with a fixed
 *       {@code \n} line separator and overwrite semantics, so repeated runs produce identical output.</li>
 * </ul>
 *
 * <p>This bean is stateless and therefore thread-safe: the injected repositories are thread-safe Spring
 * proxies, the configured output path is an immutable {@link String}, and all rendering helpers operate
 * solely on their arguments and method-local state.</p>
 *
 * @see <a href="file:app/cbl/CBSTM03A.CBL">app/cbl/CBSTM03A.CBL (statement text + HTML layout)</a>
 * @see <a href="file:app/cbl/CBSTM03B.CBL">app/cbl/CBSTM03B.CBL (VSAM I/O subroutine, replaced by repositories)</a>
 */
@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    /**
     * Platform-independent newline used for both rendered formats. A literal {@code "\n"} (rather than
     * {@link System#lineSeparator()}) is chosen deliberately so the generated statements are
     * byte-for-byte deterministic across operating systems, which keeps the parity unit tests stable.
     */
    private static final String NL = "\n";

    /** Width (in characters) of the legacy fixed-width statement record / separator rules. */
    private static final int LINE_WIDTH = 80;

    /** Maximum width of the transaction-description column in the text layout ({@code ST-TRANDT PIC X(49)}). */
    private static final int TEXT_DESC_WIDTH = 49;

    // --- injected collaborators (all constructor-injected; no field injection) ---

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardXrefRepository cardXrefRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Filesystem directory into which the generated {@code .txt} and {@code .html} statements are
     * written. Bound from the {@code report.output.path} configuration key (the same key consumed by
     * {@code ReportService} and the statement batch job).
     */
    private final String reportOutputPath;

    /**
     * Creates the statement service with all collaborators supplied by the Spring container.
     *
     * @param accountRepository     repository for account master records ({@code ACCTFILE} read)
     * @param customerRepository    repository for customer master records ({@code CUSTFILE} read)
     * @param cardXrefRepository    repository for the card&rarr;customer&rarr;account cross-reference
     *                              ({@code XREFFILE} browse) used to resolve the customer for an account
     * @param transactionRepository repository for posted transactions ({@code TRNXFILE} read), returned
     *                              ordered by origination timestamp
     * @param reportOutputPath      output directory for generated statement files, bound from
     *                              {@code report.output.path}
     */
    public StatementService(AccountRepository accountRepository,
                            CustomerRepository customerRepository,
                            CardXrefRepository cardXrefRepository,
                            TransactionRepository transactionRepository,
                            @Value("${report.output.path}") String reportOutputPath) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionRepository = transactionRepository;
        this.reportOutputPath = reportOutputPath;
    }

    // ------------------------------------------------------------------------
    // Public orchestration API
    // ------------------------------------------------------------------------

    /**
     * Generates the statement for a single account covering <em>all</em> of its transactions and writes
     * both the text and HTML files to {@code report.output.path}.
     *
     * <p>Convenience overload equivalent to {@link #generateStatement(Long, LocalDate, LocalDate)
     * generateStatement(acctId, null, null)} (no reporting-period filter).</p>
     *
     * @param acctId the account whose statement is generated; must exist
     * @throws ResourceNotFoundException if the account, its cross-reference, or its customer is missing
     * @throws UncheckedIOException      if the output files cannot be written
     */
    @Transactional(readOnly = true)
    public void generateStatement(Long acctId) {
        generateStatement(acctId, null, null);
    }

    /**
     * Generates the statement for a single account, optionally bounded to a reporting period, and writes
     * both the plain-text and HTML statement files to {@code report.output.path}.
     *
     * <p>This is the Java equivalent of one iteration of the {@code CBSTM03A} main loop
     * ({@code 1000-MAINLINE}): it assembles the account, its customer (resolved via the cross-reference,
     * exactly as the COBOL read {@code CUSTFILE} by {@code XREF-CUST-ID}), and the account's
     * transactions; computes the running total ({@code WS-TOTAL-AMT}); renders both formats; and writes
     * the two files.</p>
     *
     * <h3>Reporting-period convention</h3>
     * <p>{@code startDate} and {@code endDate} are an <strong>inclusive</strong> {@code [start, end]}
     * filter on each transaction's origination date ({@code origTs.toLocalDate()}). Either bound may be
     * {@code null}: a {@code null} {@code startDate} imposes no lower bound and a {@code null}
     * {@code endDate} imposes no upper bound, so passing {@code (null, null)} selects every transaction
     * for the account (mirroring the legacy program, which had no period filter). Transactions whose
     * origination timestamp is {@code null} are included only when no period bound is supplied (they
     * cannot be meaningfully placed inside a bounded period).</p>
     *
     * <h3>Output files</h3>
     * <p>The directory is created if absent. Two files are written, overwriting any prior run
     * deterministically: {@code statement_<acctId>_<start>_<end>.txt} and the matching {@code .html};
     * a {@code null} bound is rendered as the literal {@code ALL} in the file name.</p>
     *
     * @param acctId    the account whose statement is generated; must exist
     * @param startDate inclusive lower bound on transaction origination date, or {@code null} for no
     *                  lower bound
     * @param endDate   inclusive upper bound on transaction origination date, or {@code null} for no
     *                  upper bound
     * @throws ResourceNotFoundException if the account, its cross-reference, or its customer is missing
     * @throws UncheckedIOException      if the output files cannot be written
     */
    @Transactional(readOnly = true)
    public void generateStatement(Long acctId, LocalDate startDate, LocalDate endDate) {
        Account account = loadAccount(acctId);
        Customer customer = loadCustomerForAccount(acctId);
        List<Transaction> txns = loadTransactions(acctId, startDate, endDate);
        BigDecimal total = computeTotal(txns);

        String textStatement = buildTextStatement(account, customer, txns, total);
        String htmlStatement = buildHtmlStatement(account, customer, txns, total);

        writeStatementFiles(acctId, startDate, endDate, textStatement, htmlStatement);
    }

    // ------------------------------------------------------------------------
    // Data assembly (replaces the CBSTM03B file-I/O dispatcher)
    // ------------------------------------------------------------------------

    /**
     * Loads the account master record (COBOL {@code 3000-ACCTFILE-GET}).
     *
     * @param acctId the account identifier
     * @return the account
     * @throws ResourceNotFoundException if no account exists for {@code acctId}
     */
    private Account loadAccount(Long acctId) {
        return accountRepository.findById(acctId)
                .orElseThrow(() -> ResourceNotFoundException.of("Account", acctId));
    }

    /**
     * Resolves and loads the customer that owns the account, via the card cross-reference.
     *
     * <p>Mirrors the COBOL flow which read the cross-reference and then read {@code CUSTFILE} keyed by
     * {@code XREF-CUST-ID}. The cross-reference is fetched with a single-row page request (the legacy
     * browse returned cross-reference rows for the account; any row resolves the same owning customer),
     * and the customer is then loaded by that id.</p>
     *
     * @param acctId the account identifier
     * @return the owning customer
     * @throws ResourceNotFoundException if the account has no cross-reference, or the referenced customer
     *                                   does not exist
     */
    private Customer loadCustomerForAccount(Long acctId) {
        List<CardXref> xrefs = cardXrefRepository
                .findByXrefAcctId(acctId, PageRequest.of(0, 1))
                .getContent();
        if (xrefs.isEmpty()) {
            throw ResourceNotFoundException.of("CardXref for account", acctId);
        }
        Long custId = xrefs.get(0).getXrefCustId();
        return customerRepository.findById(custId)
                .orElseThrow(() -> ResourceNotFoundException.of("Customer", custId));
    }

    /**
     * Loads the account's transactions in origination-timestamp order and applies the optional
     * reporting-period filter in memory.
     *
     * <p>The repository returns the rows ordered by {@code origTs} (replacing the legacy
     * {@code TRANSACT.AIX} browse); {@link Pageable#unpaged()} retrieves them all. When a period bound is
     * supplied, the list is filtered to transactions whose origination date falls within the inclusive
     * {@code [startDate, endDate]} window (see {@link #generateStatement(Long, LocalDate, LocalDate)} for
     * the {@code null}-bound semantics).</p>
     *
     * @param acctId    the owning account identifier
     * @param startDate inclusive lower bound, or {@code null} for none
     * @param endDate   inclusive upper bound, or {@code null} for none
     * @return the (possibly filtered) transactions, ordered by origination timestamp; never {@code null}
     */
    private List<Transaction> loadTransactions(Long acctId, LocalDate startDate, LocalDate endDate) {
        List<Transaction> all = transactionRepository
                .findByAcctIdOrderByOrigTs(acctId, Pageable.unpaged())
                .getContent();

        if (startDate == null && endDate == null) {
            return all;
        }

        List<Transaction> filtered = new ArrayList<>();
        for (Transaction t : all) {
            LocalDate origDate = (t.getOrigTs() == null) ? null : t.getOrigTs().toLocalDate();
            if (origDate == null) {
                // An undated transaction cannot be placed inside a bounded reporting period.
                continue;
            }
            if (startDate != null && origDate.isBefore(startDate)) {
                continue;
            }
            if (endDate != null && origDate.isAfter(endDate)) {
                continue;
            }
            filtered.add(t);
        }
        return filtered;
    }

    /**
     * Computes the statement total as a {@link BigDecimal}, reproducing the COBOL {@code WS-TOTAL-AMT}
     * accumulation ({@code ADD TRNX-AMT TO WS-TOTAL-AMT}).
     *
     * <p>The total is summed at {@link CardDemoConstants#MONEY_SCALE} (scale&nbsp;2) using
     * {@link RoundingMode#HALF_UP}, matching the fixed-point semantics of the source. Transactions (or
     * amounts) that are {@code null} contribute nothing. This method is exposed publicly so the batch
     * job and parity tests can verify the total independently of file rendering.</p>
     *
     * @param txns the transactions to total; {@code null} is treated as an empty list
     * @return the total transaction amount at scale&nbsp;2; {@code 0.00} when there are no amounts
     */
    public BigDecimal computeTotal(List<Transaction> txns) {
        BigDecimal total = BigDecimal.ZERO.setScale(CardDemoConstants.MONEY_SCALE, RoundingMode.HALF_UP);
        if (txns == null) {
            return total;
        }
        for (Transaction t : txns) {
            BigDecimal amt = (t == null) ? null : t.getAmt();
            if (amt != null) {
                total = total.add(amt);
            }
        }
        return total.setScale(CardDemoConstants.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------------
    // Public renderers (filesystem-free; consumable by the batch job and tests)
    // ------------------------------------------------------------------------

    /**
     * Renders the fixed-layout <strong>plain-text</strong> statement, faithfully reproducing the
     * {@code CBSTM03A} statement lines {@code ST-LINE0..ST-LINE15}.
     *
     * <p>The produced document, in order, contains: the {@code START OF STATEMENT} banner; the customer
     * name; the three address lines; a rule; the centered {@code Basic Details} heading; a rule; the
     * account id, current balance and FICO score; a rule; the centered {@code TRANSACTION SUMMARY}
     * heading; a rule; the {@code Tran ID / Tran Details / Tran Amount} column header; a rule; one row
     * per transaction; a rule; the {@code Total EXP} line; and the {@code END OF STATEMENT} banner.</p>
     *
     * <p>Monetary values reproduce the legacy COBOL edit masks: the current balance uses
     * {@code PIC 9(9).99-} (zero-padded nine-digit integer part, two decimals, trailing sign) and the
     * transaction amounts and total use {@code PIC Z(9).99-} (zero-suppressed integer part, two
     * decimals, trailing sign). The exact column padding is cosmetic, but every printed value and its
     * two-decimal scale is faithful to the source.</p>
     *
     * @param account  the account (provides id and current balance); must not be {@code null}
     * @param customer the customer (provides name, address and FICO score); must not be {@code null}
     * @param txns     the transactions to list, in origination order; must not be {@code null}
     * @param total    the precomputed statement total (see {@link #computeTotal(List)}); must not be
     *                 {@code null}
     * @return the complete plain-text statement
     */
    public String buildTextStatement(Account account, Customer customer,
                                     List<Transaction> txns, BigDecimal total) {
        StringBuilder sb = new StringBuilder(1024);

        // ST-LINE0 : start-of-statement banner.
        sb.append(bannerStart()).append(NL);

        // ST-LINE1 : customer name (FIRST + ' ' + MIDDLE + ' ' + LAST, blanks collapsed).
        sb.append(buildCustomerName(customer)).append(NL);
        // ST-LINE2..ST-LINE4 : address lines (line 3 = addr3 + state + country + zip).
        sb.append(trimToEmpty(customer.getAddrLine1())).append(NL);
        sb.append(trimToEmpty(customer.getAddrLine2())).append(NL);
        sb.append(buildAddressLine3(customer)).append(NL);

        // ST-LINE5 / ST-LINE6 / ST-LINE5 : rule, Basic Details heading, rule.
        sb.append(rule()).append(NL);
        sb.append(centered("Basic Details")).append(NL);
        sb.append(rule()).append(NL);

        // ST-LINE7..ST-LINE9 : account id, current balance (9(9).99-), FICO score.
        sb.append(padRight("Account ID", 19)).append(':')
                .append(account.getAcctId()).append(NL);
        sb.append(padRight("Current Balance", 19)).append(':')
                .append(formatBalance(account.getCurrBal())).append(NL);
        sb.append(padRight("FICO Score", 19)).append(':')
                .append(formatFico(customer.getFicoCreditScore())).append(NL);

        // ST-LINE10 / ST-LINE11 / ST-LINE12 : rule, TRANSACTION SUMMARY heading, rule.
        sb.append(rule()).append(NL);
        sb.append(centered("TRANSACTION SUMMARY")).append(NL);
        sb.append(rule()).append(NL);

        // ST-LINE13 : column header row, then ST-LINE12 rule.
        sb.append(padRight("Tran ID", 16))
                .append(padRight("Tran Details", 51))
                .append("  Tran Amount").append(NL);
        sb.append(rule()).append(NL);

        // ST-LINE14 : one detail row per transaction (6000-WRITE-TRANS).
        for (Transaction t : txns) {
            sb.append(padRight(safe(t.getTranId()), 16))
                    .append(' ')
                    .append(padRight(truncate(t.getDescription(), TEXT_DESC_WIDTH), TEXT_DESC_WIDTH))
                    .append('$')
                    .append(formatAmount(t.getAmt()))
                    .append(NL);
        }

        // ST-LINE12 / ST-LINE14A / ST-LINE15 : rule, Total EXP line, end-of-statement banner.
        sb.append(rule()).append(NL);
        sb.append("Total EXP:").append(spaces(56)).append('$')
                .append(formatAmount(total)).append(NL);
        sb.append(bannerEnd()).append(NL);

        return sb.toString();
    }

    /**
     * Renders the complete <strong>HTML</strong> statement document, faithfully reproducing the
     * {@code CBSTM03A} {@code HTML-LINES} 88-level constant lines and the
     * {@code 5100}/{@code 5200}/{@code 6000} write paragraphs.
     *
     * <p>The document is a single centered table containing: a header band with the
     * {@code Statement for Account Number} heading and the {@code Bank of XYZ} address block; a customer
     * band (name and three address lines); a {@code Basic Details} band (account id, current balance,
     * FICO score); a {@code Transaction Summary} band with a three-column header
     * ({@code Tran ID}&nbsp;25%, {@code Tran Details}&nbsp;55%, {@code Amount}&nbsp;20%); one row per
     * transaction; and an {@code End of Statement} band. The markup is produced by concatenating string
     * literals (mirroring the COBOL constant lines) with no templating engine, keeping the output
     * deterministic.</p>
     *
     * <p>Dynamic free-text fields (customer name, address lines and transaction description) are
     * HTML-escaped to prevent any embedded markup from breaking the document. As in the source program,
     * the HTML statement lists per-transaction amounts but does <em>not</em> render a grand-total row;
     * the {@code total} parameter is accepted for signature symmetry with
     * {@link #buildTextStatement(Account, Customer, List, BigDecimal)} (and possible future use) and is
     * intentionally not emitted here.</p>
     *
     * @param account  the account (provides id and current balance); must not be {@code null}
     * @param customer the customer (provides name, address and FICO score); must not be {@code null}
     * @param txns     the transactions to list, in origination order; must not be {@code null}
     * @param total    the precomputed statement total; accepted for API symmetry, not rendered (see
     *                 above)
     * @return the complete HTML statement document
     */
    public String buildHtmlStatement(Account account, Customer customer,
                                     List<Transaction> txns, BigDecimal total) {
        StringBuilder sb = new StringBuilder(2048);

        // 5100-WRITE-HTML-HEADER : document head + opening table + account-number / bank header bands.
        sb.append("<!DOCTYPE html>").append(NL);
        sb.append("<html lang=\"en\">").append(NL);
        sb.append("<head>").append(NL);
        sb.append("<meta charset=\"utf-8\">").append(NL);
        sb.append("<title>HTML Table Layout</title>").append(NL);
        sb.append("</head>").append(NL);
        sb.append("<body style=\"margin:0px;\">").append(NL);
        sb.append("<table align=\"center\" frame=\"box\" "
                + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">").append(NL);

        sb.append("<tr>").append(NL);
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">").append(NL);
        sb.append("<h3>Statement for Account Number: ").append(account.getAcctId()).append("</h3>").append(NL);
        sb.append("</td>").append(NL);
        sb.append("</tr>").append(NL);

        sb.append("<tr>").append(NL);
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">").append(NL);
        sb.append("<p style=\"font-size:16px\">Bank of XYZ</p>").append(NL);
        sb.append("<p>410 Terry Ave N</p>").append(NL);
        sb.append("<p>Seattle WA 99999</p>").append(NL);
        sb.append("</td>").append(NL);
        sb.append("</tr>").append(NL);

        // 5200-WRITE-HTML-NMADBS : customer name + address band.
        sb.append("<tr>").append(NL);
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">").append(NL);
        sb.append("<p style=\"font-size:16px\">").append(htmlEscape(buildCustomerName(customer))).append("</p>").append(NL);
        sb.append("<p>").append(htmlEscape(trimToEmpty(customer.getAddrLine1()))).append("</p>").append(NL);
        sb.append("<p>").append(htmlEscape(trimToEmpty(customer.getAddrLine2()))).append("</p>").append(NL);
        sb.append("<p>").append(htmlEscape(buildAddressLine3(customer))).append("</p>").append(NL);
        sb.append("</td>").append(NL);
        sb.append("</tr>").append(NL);

        // Basic Details heading band.
        sb.append("<tr>").append(NL);
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">").append(NL);
        sb.append("<p style=\"font-size:16px\">Basic Details</p>").append(NL);
        sb.append("</td>").append(NL);
        sb.append("</tr>").append(NL);

        // Basic Details values band (account id, current balance, FICO score).
        sb.append("<tr>").append(NL);
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">").append(NL);
        sb.append("<p>").append(padRight("Account ID", 19)).append(": ")
                .append(account.getAcctId()).append("</p>").append(NL);
        sb.append("<p>").append(padRight("Current Balance", 19)).append(": ")
                .append(formatBalance(account.getCurrBal())).append("</p>").append(NL);
        sb.append("<p>").append(padRight("FICO Score", 19)).append(": ")
                .append(formatFico(customer.getFicoCreditScore())).append("</p>").append(NL);
        sb.append("</td>").append(NL);
        sb.append("</tr>").append(NL);

        // Transaction Summary heading band.
        sb.append("<tr>").append(NL);
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">").append(NL);
        sb.append("<p style=\"font-size:16px\">Transaction Summary</p>").append(NL);
        sb.append("</td>").append(NL);
        sb.append("</tr>").append(NL);

        // Transaction Summary column-header row (25% / 55% / 20%).
        sb.append("<tr>").append(NL);
        sb.append("<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">").append(NL);
        sb.append("<p style=\"font-size:16px\">Tran ID</p>").append(NL);
        sb.append("</td>").append(NL);
        sb.append("<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">").append(NL);
        sb.append("<p style=\"font-size:16px\">Tran Details</p>").append(NL);
        sb.append("</td>").append(NL);
        sb.append("<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">").append(NL);
        sb.append("<p style=\"font-size:16px\">Amount</p>").append(NL);
        sb.append("</td>").append(NL);
        sb.append("</tr>").append(NL);

        // 6000-WRITE-TRANS : one row per transaction.
        for (Transaction t : txns) {
            sb.append("<tr>").append(NL);
            sb.append("<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">").append(NL);
            sb.append("<p>").append(htmlEscape(safe(t.getTranId()))).append("</p>").append(NL);
            sb.append("</td>").append(NL);
            sb.append("<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">").append(NL);
            sb.append("<p>").append(htmlEscape(truncate(t.getDescription(), TEXT_DESC_WIDTH))).append("</p>").append(NL);
            sb.append("</td>").append(NL);
            sb.append("<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">").append(NL);
            sb.append("<p>").append(formatAmount(t.getAmt())).append("</p>").append(NL);
            sb.append("</td>").append(NL);
            sb.append("</tr>").append(NL);
        }

        // 4000-TRNXFILE-GET (HTML tail) : End of Statement band + close table/body/html.
        sb.append("<tr>").append(NL);
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">").append(NL);
        sb.append("<h3>End of Statement</h3>").append(NL);
        sb.append("</td>").append(NL);
        sb.append("</tr>").append(NL);
        sb.append("</table>").append(NL);
        sb.append("</body>").append(NL);
        sb.append("</html>").append(NL);

        return sb.toString();
    }

    // ------------------------------------------------------------------------
    // File output
    // ------------------------------------------------------------------------

    /**
     * Writes the rendered text and HTML statements to {@code report.output.path}.
     *
     * <p>The output directory is created if it does not exist. Two files are written with overwrite
     * (truncate) semantics so a re-run is deterministic: {@code statement_<acctId>_<start>_<end>.txt}
     * and the matching {@code .html}; a {@code null} period bound is rendered as the literal
     * {@code ALL}. A checked {@link IOException} is never allowed to escape the public API &mdash; it is
     * wrapped in an unchecked {@link UncheckedIOException} carrying a clear, non-sensitive message
     * (account id and output path only).</p>
     *
     * @param acctId        the account id (used only in the file name and log message; non-sensitive)
     * @param startDate     the lower period bound, or {@code null}
     * @param endDate       the upper period bound, or {@code null}
     * @param textStatement the rendered text statement
     * @param htmlStatement the rendered HTML statement
     * @throws UncheckedIOException if the directory cannot be created or either file cannot be written
     */
    private void writeStatementFiles(Long acctId, LocalDate startDate, LocalDate endDate,
                                     String textStatement, String htmlStatement) {
        try {
            Path dir = Paths.get(reportOutputPath);
            Files.createDirectories(dir);
            String period = (startDate == null ? "ALL" : startDate.toString())
                    + "_" + (endDate == null ? "ALL" : endDate.toString());
            Path textFile = dir.resolve("statement_" + acctId + "_" + period + ".txt");
            Path htmlFile = dir.resolve("statement_" + acctId + "_" + period + ".html");
            Files.writeString(textFile, textStatement);
            Files.writeString(htmlFile, htmlStatement);
            if (log.isInfoEnabled()) {
                log.info("Generated statement for account {} -> {}, {}", acctId, textFile, htmlFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to write statement files for account " + acctId
                            + " to directory '" + reportOutputPath + "'", e);
        }
    }

    // ------------------------------------------------------------------------
    // Rendering helpers (pure functions; no I/O, no sensitive-field access)
    // ------------------------------------------------------------------------

    /**
     * Builds the customer display name as {@code FIRST + ' ' + MIDDLE + ' ' + LAST}, trimming each part
     * and collapsing the separators when a part is blank (mirrors the COBOL {@code STRING ... DELIMITED
     * BY ' '} construction in {@code 5000-CREATE-STATEMENT}).
     *
     * @param customer the customer
     * @return the assembled name; never {@code null}
     */
    private static String buildCustomerName(Customer customer) {
        return joinWithSpace(customer.getFirstName(), customer.getMiddleName(), customer.getLastName());
    }

    /**
     * Builds the third address line as {@code ADDR-LINE-3 + STATE + COUNTRY + ZIP}, trimming each part
     * and collapsing blanks (mirrors the COBOL {@code STRING ... DELIMITED BY ' '} construction).
     *
     * @param customer the customer
     * @return the assembled address line; never {@code null}
     */
    private static String buildAddressLine3(Customer customer) {
        return joinWithSpace(customer.getAddrLine3(), customer.getAddrStateCd(),
                customer.getAddrCountryCd(), customer.getAddrZip());
    }

    /**
     * Joins the supplied parts with a single space, ignoring {@code null}/blank parts after trimming.
     *
     * @param parts the parts to join
     * @return the space-joined, blank-collapsed result; never {@code null}
     */
    private static String joinWithSpace(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String t = trimToEmpty(p);
            if (!t.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }

    /**
     * Formats a balance using the legacy {@code PIC 9(9).99-} edit mask: a nine-digit zero-padded
     * integer part, a decimal point, two decimal places, and a trailing sign ({@code '-'} when
     * negative, otherwise a space). The value is first scaled to two decimals with
     * {@link RoundingMode#HALF_UP}.
     *
     * @param value the balance ({@code null} is treated as zero)
     * @return the masked balance string
     */
    private static String formatBalance(BigDecimal value) {
        BigDecimal v = (value == null ? BigDecimal.ZERO : value)
                .setScale(CardDemoConstants.MONEY_SCALE, RoundingMode.HALF_UP);
        boolean negative = v.signum() < 0;
        String plain = v.abs().toPlainString();
        int dot = plain.indexOf('.');
        String intPart = (dot < 0) ? plain : plain.substring(0, dot);
        String fracPart = (dot < 0) ? "00" : plain.substring(dot + 1);
        StringBuilder sb = new StringBuilder(13);
        for (int i = intPart.length(); i < 9; i++) {
            sb.append('0');
        }
        sb.append(intPart).append('.').append(fracPart).append(negative ? '-' : ' ');
        return sb.toString();
    }

    /**
     * Formats a transaction amount or total using the legacy {@code PIC Z(9).99-} edit mask: a
     * zero-suppressed integer part, a decimal point, two decimal places, and a trailing sign
     * ({@code '-'} when negative, otherwise a space). The value is first scaled to two decimals with
     * {@link RoundingMode#HALF_UP}.
     *
     * @param value the amount ({@code null} is treated as zero)
     * @return the masked amount string
     */
    private static String formatAmount(BigDecimal value) {
        BigDecimal v = (value == null ? BigDecimal.ZERO : value)
                .setScale(CardDemoConstants.MONEY_SCALE, RoundingMode.HALF_UP);
        boolean negative = v.signum() < 0;
        return v.abs().toPlainString() + (negative ? '-' : ' ');
    }

    /**
     * Formats the FICO score for display.
     *
     * @param fico the score ({@code null} renders as an empty string)
     * @return the score as text
     */
    private static String formatFico(Integer fico) {
        return (fico == null) ? "" : fico.toString();
    }

    /**
     * @return the {@code START OF STATEMENT} banner ({@code ST-LINE0}), 80 characters wide.
     */
    private static String bannerStart() {
        return "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);
    }

    /**
     * @return the {@code END OF STATEMENT} banner ({@code ST-LINE15}), 80 characters wide.
     */
    private static String bannerEnd() {
        return "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);
    }

    /**
     * @return an 80-character {@code '-'} separator rule ({@code ST-LINE5}/{@code 10}/{@code 12}).
     */
    private static String rule() {
        return "-".repeat(LINE_WIDTH);
    }

    /**
     * Centers {@code text} within {@link #LINE_WIDTH} columns (used for the {@code Basic Details} and
     * {@code TRANSACTION SUMMARY} headings). Text at least as wide as the line is returned unchanged.
     *
     * @param text the heading text
     * @return the centered, space-padded line
     */
    private static String centered(String text) {
        int len = text.length();
        if (len >= LINE_WIDTH) {
            return text;
        }
        int left = (LINE_WIDTH - len) / 2;
        int right = LINE_WIDTH - len - left;
        return spaces(left) + text + spaces(right);
    }

    /**
     * Right-pads {@code s} with spaces to {@code width}; strings already at least {@code width} long are
     * returned unchanged (never truncated). {@code null} is treated as empty.
     *
     * @param s     the source string
     * @param width the target width
     * @return the right-padded string
     */
    private static String padRight(String s, int width) {
        String v = safe(s);
        return (v.length() >= width) ? v : v + spaces(width - v.length());
    }

    /**
     * @param n the number of spaces
     * @return a string of {@code n} spaces (empty when {@code n <= 0})
     */
    private static String spaces(int n) {
        return (n <= 0) ? "" : " ".repeat(n);
    }

    /**
     * Truncates {@code s} to at most {@code max} characters (the legacy {@code ST-TRANDT PIC X(49)}
     * field width for the description column). {@code null} is treated as empty.
     *
     * @param s   the source string
     * @param max the maximum length
     * @return the truncated string
     */
    private static String truncate(String s, int max) {
        String v = safe(s);
        return (v.length() <= max) ? v : v.substring(0, max);
    }

    /**
     * @param s the source string
     * @return {@code s}, or an empty string when {@code s} is {@code null}
     */
    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * @param s the source string
     * @return {@code s} trimmed, or an empty string when {@code s} is {@code null}
     */
    private static String trimToEmpty(String s) {
        return (s == null) ? "" : s.trim();
    }

    /**
     * Minimally HTML-escapes the five markup-significant characters ({@code & < > " '}) so dynamic
     * free-text fields cannot break the generated document.
     *
     * @param s the source text ({@code null} renders as an empty string)
     * @return the escaped text
     */
    private static String htmlEscape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(ch);
                    break;
            }
        }
        return sb.toString();
    }
}

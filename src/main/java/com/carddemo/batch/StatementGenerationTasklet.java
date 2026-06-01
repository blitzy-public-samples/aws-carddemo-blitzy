package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.util.BatchOutputPathResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Spring Batch {@link Tasklet} that orchestrates customer-statement generation, the Java port of
 * the {@code MAINLINE} logic of the COBOL batch program {@code app/cbl/CBSTM03A.CBL}
 * (CardDemo_v1.0-15-g27d6c6f-68, paragraphs {@code 1000-MAINLINE} L316-L329 and
 * {@code 5000-CREATE-STATEMENT} L458-L505).
 *
 * <p><strong>Origin &mdash; what this replaces.</strong> {@code CBSTM03A} is the statement-creation
 * program of the CREASTMT batch flow ({@code app/jcl/CREASTMT.JCL}). Its {@code 1000-MAINLINE}
 * paragraph walks the card cross-reference file ({@code XREFFILE}) front to back; for each
 * cross-reference it reads the owning customer ({@code 2000-CUSTFILE-GET}) and account
 * ({@code 3000-ACCTFILE-GET}), emits the statement scaffolding ({@code 5000-CREATE-STATEMENT}),
 * resets the running total ({@code MOVE ZERO TO WS-TOTAL-AMT}), then reads and lists the card's
 * transactions ({@code 4000-TRNXFILE-GET}, accumulating {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at
 * L429). The program emits two artifacts per statement: a plain-text record stream
 * ({@code STMT-FILE}, RECFM=FB LRECL=80) and an HTML document ({@code HTML-FILE}, RECFM=FB
 * LRECL=100).</p>
 *
 * <p><strong>What this tasklet does.</strong> It reproduces that orchestration against the relational
 * model (AAP &sect;0.6.7), delegating data access to {@link StatementIoSubroutine} (the
 * {@code CBSTM03B} I/O subroutine port) and HTML rendering to {@link StatementHtmlBuilder} (the
 * byte-for-byte {@code CBSTM03A} HTML-emission port, PR-09). Concretely it:</p>
 * <ol>
 *   <li>resolves the output directory from the optional {@code outputDir} job parameter
 *       (default subdirectory {@code statements}) <em>under a configured base directory</em> via
 *       {@link BatchOutputPathResolver} &mdash; rejecting absolute paths and {@code ..} traversal
 *       (CP4 path-traversal guard) &mdash; and ensures it exists;</li>
 *   <li><strong>streams</strong> the cross-reference file one bounded page at a time in
 *       {@code (custId, accountId, xrefCardNum)} order
 *       ({@link StatementIoSubroutine#findXrefsForStatements(org.springframework.data.domain.Pageable)}),
 *       so the {@code XREFFILE} sequential driver is reproduced without materializing the whole
 *       file in memory (CP4 batch-streaming requirement);</li>
 *   <li>performs a <strong>control-break</strong> on {@code (custId, accountId)}: because the page
 *       order makes every card of one {@code (customer, account)} pair contiguous, the card numbers
 *       are accumulated until the key changes, at which point the completed group is emitted &mdash;
 *       memory is bounded to the cards of the single group in flight, not the whole table;</li>
 *   <li>for each {@code (customer, account)} group, looks up the customer and account once, fetches
 *       that group's transactions <strong>per card</strong>
 *       ({@link StatementIoSubroutine#findTransactionsByCardNumber(String)}) rather than pre-loading
 *       the whole {@code TRNXFILE}, tranId-sorts the aggregate, computes the {@link BigDecimal}
 *       running total (scale 2, {@link RoundingMode#HALF_UP}; PR-16), and emits both an HTML
 *       statement (delegated to {@link StatementHtmlBuilder}) and a sibling plain-text statement.</li>
 * </ol>
 *
 * <p><strong>One statement per (customer, account).</strong> Although {@code XREFFILE} is keyed by
 * card, {@code CBSTM03A} produces a single statement per customer/account with every owned card's
 * transactions aggregated; this tasklet therefore de-duplicates the card-keyed cross-references
 * into {@code (customer, account)} groups before emitting (AAP transformation note for CBSTM03A).</p>
 *
 * <p><strong>HTML parity (PR-09).</strong> The HTML body is produced exclusively by
 * {@link StatementHtmlBuilder#renderFullStatement(Customer, Account, List)} so the byte-for-byte
 * fidelity verified by {@code StatementGenerationParityTest} is preserved here unchanged. Note the
 * builder computes everything it needs from the customer, account and ordered transaction list; the
 * running total is a plain-text-only concern (the COBOL HTML emission has no grand-total row, only
 * an "End of Statement" footer), so the {@code BigDecimal} total computed below is passed to the
 * plain-text renderer only.</p>
 *
 * <p><strong>Read-only unit of work (PR-24).</strong> {@link #execute} is annotated
 * {@code @Transactional(propagation = REQUIRES_NEW, readOnly = true)} so the entire statement run
 * executes inside one read-only transaction boundary &mdash; the Spring counterpart of the implicit
 * CICS/VSAM read snapshot. The Spring {@code org.springframework.transaction.annotation.Transactional}
 * annotation is used in preference to {@code jakarta.transaction.Transactional} for its
 * {@code readOnly} / {@code propagation} attribute support (PR-28).</p>
 *
 * <p><strong>Lock ordering (PR-23).</strong> Per-statement reads are issued through
 * {@link StatementIoSubroutine} in the canonical order CUSTOMER &rarr; ACCOUNT &rarr; CARD &rarr;
 * TRANSACTION (the documented VSAM lock-acquisition convention), matching the call order in
 * {@link #emitStatement}.</p>
 *
 * <p><strong>Money (PR-16).</strong> Every monetary value is a {@link BigDecimal} at scale 2 with
 * {@link RoundingMode#HALF_UP}; {@code float}/{@code double} are never used. {@link BigDecimal}
 * comparisons (where needed) use {@code compareTo}, never {@code equals}.</p>
 *
 * <p><strong>Injection &amp; threading (PR-29).</strong> This {@code @Component} is a stateless
 * singleton; its two collaborators are supplied through the Lombok-generated constructor over
 * {@code final} fields (no {@code @Autowired} field injection). All mutable state
 * ({@code Map}s, {@code List}s, accumulators) is local to a single {@link #execute} invocation, so
 * concurrent batch executions never interfere.</p>
 *
 * @see StatementIoSubroutine
 * @see StatementHtmlBuilder
 * @see com.carddemo.entity.CardXref
 * @see com.carddemo.entity.Customer
 * @see com.carddemo.entity.Account
 * @see com.carddemo.entity.Transaction
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StatementGenerationTasklet implements Tasklet {

    /**
     * Default output <em>subdirectory</em> (relative to the {@link BatchOutputPathResolver} base)
     * when the {@code outputDir} job parameter is not supplied. The value is a plain subpath, never
     * an absolute path, so it always resolves safely under the configured base directory.
     */
    private static final String DEFAULT_OUTPUT_SUBDIR = "statements";

    /** Job-parameter key selecting the directory (subpath under the base) statement files go to. */
    private static final String PARAM_OUTPUT_DIR = "outputDir";

    /**
     * Page size (100) for the streaming cross-reference reader. Bounds the number of
     * {@link CardXref} rows held in memory at once while walking {@code XREFFILE} for the
     * control-break; large enough to amortize round-trips, small enough to bound heap (CP4).
     */
    private static final int XREF_PAGE_SIZE = 100;

    /** Scale (2) applied to every monetary {@link BigDecimal} (PR-16). */
    private static final int MONEY_SCALE = 2;

    /** Width (65) of the plain-text statement banner / divider rules. */
    private static final int PLAIN_WIDTH = 65;

    /** Inner width (63) available between the two {@code '*'} banner edges. */
    private static final int BANNER_INNER_WIDTH = PLAIN_WIDTH - 2;

    /**
     * I/O helper (CBSTM03B port) wrapping the four JPA repositories. Supplies the ordered xref /
     * transaction pre-loads and the keyed customer / account reads used by {@link #emitStatement}.
     */
    private final StatementIoSubroutine ioSubroutine;

    /**
     * HTML statement renderer (CBSTM03A {@code 5100/5200/6000} HTML paragraphs, PR-09 byte-for-byte).
     * Invoked once per emitted statement via
     * {@link StatementHtmlBuilder#renderFullStatement(Customer, Account, List)}.
     */
    private final StatementHtmlBuilder htmlBuilder;

    /**
     * Resolves the job-parameter output directory <em>under a configured base directory</em>,
     * rejecting absolute paths and {@code ..} traversal (CP4 path-traversal guard). The statement
     * files are written only beneath {@link BatchOutputPathResolver#getBaseDir()}.
     */
    private final BatchOutputPathResolver pathResolver;

    /**
     * Orchestrates the full statement run &mdash; the Java port of {@code CBSTM03A}
     * {@code 1000-MAINLINE} (L316-L329).
     *
     * <p><strong>Streaming control-break (CP4).</strong> Rather than pre-loading the whole
     * {@code XREFFILE} and {@code TRNXFILE} into memory, this walks the cross-references one bounded
     * page at a time in {@code (custId, accountId, xrefCardNum)} order and performs a control-break
     * on {@code (custId, accountId)}: the contiguous cards of each {@code (customer, account)} pair
     * are accumulated, and when the key changes the completed group is emitted as one HTML statement
     * and one plain-text statement (its transactions fetched per card on demand). The number of
     * statements emitted is reported to Spring Batch via
     * {@link StepContribution#incrementWriteCount(long)} so it appears in the step's write-count
     * metric. Heap use is bounded by one page of cross-references plus the single group in flight,
     * not by the table cardinality.</p>
     *
     * <p>Runs inside a single read-only {@code REQUIRES_NEW} transaction (PR-24). Any failure while
     * emitting a statement is logged with its {@code (customer, account)} context and re-thrown so
     * Spring Batch marks the step failed and the job is restartable from the failed chunk/step.</p>
     *
     * @param contribution the step contribution used to record the emitted-statement write count
     * @param chunkContext the chunk context; its step context exposes the job parameters
     *                     (read-only access to {@code outputDir})
     * @return {@link RepeatStatus#FINISHED} &mdash; a tasklet performs its whole unit of work in a
     *         single invocation
     * @throws Exception if the output directory cannot be created or a statement file cannot be
     *                   written (the failure is propagated to fail the batch step)
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext)
            throws Exception {
        log.info("StatementGenerationTasklet starting");

        // Resolve + validate the output directory under the configured base (CP4 path-traversal
        // guard): BatchOutputPathResolver rejects absolute paths and ".." traversal and normalizes
        // the requested subpath beneath its base directory. Default subdirectory: "statements".
        // StepContext.getJobParameters() returns Map<String,Object>, so the value is read as String.
        Object outputDirParam = chunkContext.getStepContext()
                .getJobParameters()
                .getOrDefault(PARAM_OUTPUT_DIR, DEFAULT_OUTPUT_SUBDIR);
        String requestedDir = outputDirParam != null
                ? outputDirParam.toString() : DEFAULT_OUTPUT_SUBDIR;
        Path outputPath = pathResolver.resolve(requestedDir);
        Files.createDirectories(outputPath);
        log.info("Statement output directory: {}", outputPath.toAbsolutePath());

        // === Stream cross-references one bounded page at a time in (custId, accountId, xrefCardNum)
        //     order and emit one statement per (customer, account) via a CONTROL BREAK (CP4
        //     streaming). CBSTM03A emits a single statement per customer/account; because the page
        //     order makes every card of one (customer, account) contiguous, we accumulate cards
        //     until the key changes, then emit the completed group. Memory is bounded to one page
        //     plus the single group in flight — the whole XREFFILE/TRNXFILE is never materialized. ===
        long emittedCount = 0L;
        int pageNumber = 0;
        boolean morePages = true;
        StatementContext current = null;
        long xrefCount = 0L;

        while (morePages) {
            Page<CardXref> page =
                    ioSubroutine.findXrefsForStatements(PageRequest.of(pageNumber, XREF_PAGE_SIZE));
            for (CardXref xref : page.getContent()) {
                xrefCount++;
                Long custId = xref.getCustId();
                Long acctId = xref.getAccountId();
                String cardNum = xref.getXrefCardNum();

                // Control break: a change in (custId, accountId) closes the current group.
                if (current == null || !current.matches(custId, acctId)) {
                    if (current != null) {
                        emittedCount += emitStatementOrRethrow(current, outputPath);
                    }
                    current = new StatementContext(custId, acctId);
                }
                if (cardNum != null) {
                    current.cardNumbers.add(cardNum);
                }
            }
            morePages = page.hasNext();
            pageNumber++;
        }
        // Emit the final group (the last control-break partition) once the stream is exhausted.
        if (current != null) {
            emittedCount += emitStatementOrRethrow(current, outputPath);
        }

        log.info("StatementGenerationTasklet completed \u2014 streamed {} cross-reference(s), "
                + "emitted {} statement(s)", xrefCount, emittedCount);
        contribution.incrementWriteCount(emittedCount);
        return RepeatStatus.FINISHED;
    }

    /**
     * Emits the statement for a completed control-break group, translating the checked
     * {@link IOException} from {@link #emitStatement} into a propagated failure that fails (and
     * thus makes restartable) the batch step &mdash; mirroring {@code CBSTM03A}'s abend-on-I/O
     * behavior rather than silently skipping. Returns the write-count delta (1 when a statement was
     * emitted, 0 when the group was skipped because its customer or account was not found).
     *
     * @param ctx        the completed statement context (customer id, account id, owned cards)
     * @param outputPath the validated output directory (already created)
     * @return {@code 1} if a statement was emitted, {@code 0} if it was skipped
     * @throws IOException if the statement files cannot be written (propagated to fail the step)
     */
    private long emitStatementOrRethrow(StatementContext ctx, Path outputPath) throws IOException {
        try {
            return emitStatement(ctx, outputPath) ? 1L : 0L;
        } catch (IOException e) {
            log.error("Failed to emit statement for customer={} account={}: {}",
                    ctx.custId, ctx.acctId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Emits the HTML and plain-text statements for a single {@code (customer, account)} context
     * &mdash; the Java port of {@code CBSTM03A} {@code 5000-CREATE-STATEMENT} (L458-L505) together
     * with the per-card transaction-listing loop of {@code 4000-TRNXFILE-GET} (L416-L437).
     *
     * <p>Reads the customer then the account (PR-23 lock order CUSTOMER &rarr; ACCOUNT). A missing
     * customer or account reproduces the COBOL {@code INVALID KEY} ({@code '23'}) skip: the
     * statement is not emitted and a warning is logged (returns {@code false}). Otherwise it
     * aggregates the transactions across every card of the account, accumulates the
     * {@link BigDecimal} total (scale 2, {@link RoundingMode#HALF_UP}), sorts the aggregate by
     * {@code tranId} ascending (the {@code 1000-TRNXFILE-GET} sequence), renders the HTML via
     * {@link StatementHtmlBuilder} and the plain text locally, and writes both files.</p>
     *
     * <p>Output files are named {@code statement-acct-NNNNNNNNNNN.html} and
     * {@code statement-acct-NNNNNNNNNNN.txt}, where {@code NNNNNNNNNNN} is the 11-digit zero-padded
     * account id (matching the COBOL {@code ACCT-ID PIC 9(11)} width).</p>
     *
     * @param ctx        the statement context (customer id, account id, owned card numbers)
     * @param outputDir  the directory the two statement files are written to (already created)
     * @return {@code true} if a statement was emitted; {@code false} if it was skipped because the
     *         customer or account could not be found
     * @throws IOException if either statement file cannot be written
     */
    private boolean emitStatement(
            StatementContext ctx,
            Path outputDir) throws IOException {

        // PR-23 lock order: CUSTOMER first.
        Customer customer = ioSubroutine.findCustomer(ctx.custId).orElse(null);
        if (customer == null) {
            log.warn("Skipping statement \u2014 customer {} not found", ctx.custId);
            return false;
        }

        // PR-23 lock order: ACCOUNT second.
        Account account = ioSubroutine.findAccount(ctx.acctId).orElse(null);
        if (account == null) {
            log.warn("Skipping statement \u2014 account {} not found", ctx.acctId);
            return false;
        }

        // Aggregate transactions across every card belonging to this customer/account and
        // accumulate the running total (CBSTM03A 4000-TRNXFILE-GET: ADD TRNX-AMT TO WS-TOTAL-AMT).
        // Transactions are fetched PER CARD on demand (CARD link of the PR-23 lock order) instead
        // of from a pre-loaded whole-file map, so only the current group's rows are held (CP4).
        List<Transaction> aggregated = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        for (String cardNum : ctx.cardNumbers) {
            List<Transaction> cardTxs = ioSubroutine.findTransactionsByCardNumber(cardNum);
            aggregated.addAll(cardTxs);
            for (Transaction tx : cardTxs) {
                totalAmount = totalAmount.add(nullSafeBd(tx.getAmount()));
            }
        }
        totalAmount = totalAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // Sort the aggregate by tranId ascending (preserves the CBSTM03A 1000-TRNXFILE-GET order).
        aggregated.sort(Comparator.comparing(Transaction::getTranId,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        long acctId = account.getAcctId() != null ? account.getAcctId() : 0L;

        // === EMIT HTML STATEMENT (PR-09 byte-for-byte via StatementHtmlBuilder). ===
        // NOTE: renderFullStatement takes (Customer, Account, List<Transaction>) — it computes
        // everything it renders from the ordered transaction list; there is no total argument.
        String htmlContent = htmlBuilder.renderFullStatement(customer, account, aggregated);
        Path htmlFile = outputDir.resolve(
                String.format(Locale.ROOT, "statement-acct-%011d.html", acctId));
        Files.writeString(htmlFile, htmlContent, StandardCharsets.UTF_8);
        log.debug("Emitted HTML statement: {}", htmlFile);

        // === EMIT PLAIN-TEXT STATEMENT (CBSTM03A STMT-FILE sibling format). ===
        String txtContent = renderPlainText(customer, account, aggregated, totalAmount);
        Path txtFile = outputDir.resolve(
                String.format(Locale.ROOT, "statement-acct-%011d.txt", acctId));
        Files.writeString(txtFile, txtContent, StandardCharsets.UTF_8);
        log.debug("Emitted plain-text statement: {}", txtFile);

        return true;
    }

    /**
     * Renders the plain-text statement &mdash; the sibling of the HTML document and the Java
     * counterpart of the {@code CBSTM03A} {@code STMT-FILE} record stream written by
     * {@code 5000-CREATE-STATEMENT} (L458-L505) and the {@code 4000-TRNXFILE-GET} transaction
     * listing / total (L416-L437).
     *
     * <p>The layout reproduces the COBOL statement structure (banner, account number, customer
     * name, three address lines, FICO score, current balance, a ruled transaction table, and the
     * accumulated total) using the working-storage field widths as a guide: {@code ST-ACCT-ID}
     * {@code PIC X(20)} fed from {@code ACCT-ID PIC 9(11)} (here the 11-digit zero-padded account),
     * {@code ST-TRANID PIC X(16)}, {@code ST-TRANDT PIC X(49)} (truncated to the column width), and
     * the {@code ST-CURR-BAL} / {@code ST-TRANAMT} / {@code ST-TOTAL-TRAMT} {@code PIC Z(9).99-}
     * trailing-sign money edits reproduced by {@link #formatAmount(BigDecimal)}. The COBOL
     * fixed-block {@code LRECL=80} padding is intentionally dropped (the lines are newline
     * terminated) for readability, as documented in the migration mapping; the {@code STMT-FILE}
     * format is the approximate sibling to the parity-tested HTML output.</p>
     *
     * <p>Every monetary value is a {@link BigDecimal} at scale 2 with {@link RoundingMode#HALF_UP}
     * (PR-16). All numeric formatting uses {@link Locale#ROOT} so the decimal point and digits are
     * locale-independent, and lines are terminated with an explicit {@code '\n'} so output is
     * deterministic across platforms.</p>
     *
     * @param customer     the statement customer (non-{@code null})
     * @param account      the statement account (non-{@code null})
     * @param transactions the tranId-ordered transactions to list (non-{@code null}; may be empty)
     * @param totalAmount  the accumulated transaction total (scale 2, {@link RoundingMode#HALF_UP})
     * @return the fully assembled plain-text statement as a single {@link String}
     */
    private String renderPlainText(
            Customer customer,
            Account account,
            List<Transaction> transactions,
            BigDecimal totalAmount) {

        StringBuilder sb = new StringBuilder(8192);
        String divider = "*".repeat(PLAIN_WIDTH);
        String rule = "-".repeat(PLAIN_WIDTH);
        long acctId = account.getAcctId() != null ? account.getAcctId() : 0L;
        int fico = customer.getFicoScore() != null ? customer.getFicoScore() : 0;

        // Banner — CBSTM03A ST-LINE0 "START OF STATEMENT".
        sb.append(divider).append('\n');
        sb.append('*').append(centerPad("START OF STATEMENT", BANNER_INNER_WIDTH)).append("*\n");
        sb.append(divider).append('\n');

        // Account / customer / address / FICO / balance block — CBSTM03A ST-LINE1..ST-LINE9.
        sb.append(String.format(Locale.ROOT, "Account Number  : %011d", acctId)).append('\n');
        sb.append("Customer Name   : ").append(buildCustomerName(customer)).append('\n');
        sb.append("Address         : ").append(nullSafe(customer.getAddrLine1())).append('\n');
        sb.append("                  ").append(nullSafe(customer.getAddrLine2())).append('\n');
        sb.append("                  ").append(buildAddressLine3(customer)).append('\n');
        sb.append(String.format(Locale.ROOT, "FICO Score      : %d", fico)).append('\n');
        sb.append("Current Balance : ")
                .append(formatAmount(nullSafeBd(account.getCurrBal()))).append('\n');

        // Transaction table — CBSTM03A ST-LINE10..ST-LINE14 rows.
        sb.append(rule).append('\n');
        sb.append("TranID            Description                          Amount").append('\n');
        sb.append(rule).append('\n');
        for (Transaction tx : transactions) {
            sb.append(String.format(Locale.ROOT, "%-16s  %-37s  %10s",
                    truncate(nullSafe(tx.getTranId()), 16),
                    truncate(nullSafe(tx.getDescription()), 37),
                    formatAmount(nullSafeBd(tx.getAmount())))).append('\n');
        }

        // Total — CBSTM03A ST-LINE14A ST-TOTAL-TRAMT.
        sb.append(rule).append('\n');
        sb.append("Total:                                            ")
                .append(formatAmount(totalAmount)).append('\n');

        // Banner — CBSTM03A ST-LINE15 "END OF STATEMENT".
        sb.append(divider).append('\n');
        sb.append('*').append(centerPad("END OF STATEMENT", BANNER_INNER_WIDTH)).append("*\n");
        sb.append(divider).append('\n');

        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------------------------------------

    /**
     * Assembles the customer display name from the first / middle / last name parts, joining the
     * non-empty trimmed tokens with a single space &mdash; the plain-text analogue of the
     * {@code CBSTM03A} {@code 5000-CREATE-STATEMENT} name {@code STRING} (L462-L469).
     *
     * @param c the customer (non-{@code null})
     * @return the assembled name (possibly empty if all parts are blank)
     */
    private static String buildCustomerName(Customer c) {
        return joinNonEmpty(" ",
                nullSafeTrim(c.getFirstName()),
                nullSafeTrim(c.getMiddleName()),
                nullSafeTrim(c.getLastName()));
    }

    /**
     * Assembles the third address line from line-3, state, country and ZIP, joining the non-empty
     * trimmed tokens with a single space &mdash; the plain-text analogue of the {@code CBSTM03A}
     * {@code 5000-CREATE-STATEMENT} address {@code STRING} (L472-L481).
     *
     * <p>Uses the verified {@link Customer} accessors {@code getStateCd()}, {@code getCountryCd()}
     * and {@code getZipCd()} (the entity fields are {@code stateCd} / {@code countryCd} /
     * {@code zipCd}).</p>
     *
     * @param c the customer (non-{@code null})
     * @return the assembled address-line-3 (possibly empty if all parts are blank)
     */
    private static String buildAddressLine3(Customer c) {
        return joinNonEmpty(" ",
                nullSafeTrim(c.getAddrLine3()),
                nullSafeTrim(c.getStateCd()),
                nullSafeTrim(c.getCountryCd()),
                nullSafeTrim(c.getZipCd()));
    }

    /**
     * Joins the supplied parts with {@code sep}, skipping any {@code null} or empty token (so no
     * leading, trailing, or doubled separators are produced).
     *
     * @param sep   the separator inserted between non-empty tokens
     * @param parts the tokens to join
     * @return the joined string (empty when every token is {@code null}/empty)
     */
    private static String joinNonEmpty(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /**
     * Null-safe string: returns the input, or {@code ""} when {@code null}.
     *
     * @param s the value (may be {@code null})
     * @return {@code s}, or {@code ""} when {@code s} is {@code null}
     */
    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Null-safe trim: returns the trimmed input, or {@code ""} when {@code null}.
     *
     * @param s the value (may be {@code null})
     * @return {@code s.trim()}, or {@code ""} when {@code s} is {@code null}
     */
    private static String nullSafeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Null-safe money: returns the input, or {@code BigDecimal.ZERO} at scale 2 when {@code null}
     * (PR-16).
     *
     * @param b the value (may be {@code null})
     * @return {@code b}, or zero (scale 2, {@link RoundingMode#HALF_UP}) when {@code b} is
     *         {@code null}
     */
    private static BigDecimal nullSafeBd(BigDecimal b) {
        return b == null ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP) : b;
    }

    /**
     * Formats a money value to scale 2 with {@link RoundingMode#HALF_UP} and a COBOL-style trailing
     * sign (a trailing space when non-negative, {@code '-'} when negative) &mdash; the plain-text
     * analogue of the {@code PIC Z(9).99-} / {@code PIC 9(9).99-} edits used for {@code ST-CURR-BAL},
     * {@code ST-TRANAMT} and {@code ST-TOTAL-TRAMT}. Numeric formatting uses {@link Locale#ROOT} so
     * the decimal separator is always {@code '.'}.
     *
     * @param v the money value (may be {@code null}; treated as zero)
     * @return the formatted, sign-suffixed amount
     */
    private static String formatAmount(BigDecimal v) {
        BigDecimal scaled = nullSafeBd(v).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return String.format(Locale.ROOT, "%9.2f", scaled.abs())
                + (scaled.signum() < 0 ? "-" : " ");
    }

    /**
     * Truncates a string to at most {@code max} characters (no-op when shorter or equal).
     *
     * @param s   the value (may be {@code null})
     * @param max the maximum length
     * @return the truncated string, or {@code ""} when {@code s} is {@code null}
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Centers {@code s} within {@code width} columns by padding with spaces on both sides; returns
     * the value unchanged when it is already at least {@code width} wide. Any odd remainder favors
     * the right side (matching the banner spacing used by the statement header/footer).
     *
     * @param s     the value to center (treated as {@code ""} when {@code null})
     * @param width the total field width
     * @return the centered, space-padded string
     */
    private static String centerPad(String s, int width) {
        String value = s == null ? "" : s;
        int total = width - value.length();
        if (total <= 0) {
            return value;
        }
        int left = total / 2;
        int right = total - left;
        return " ".repeat(left) + value + " ".repeat(right);
    }

    // ---------------------------------------------------------------------------------------------
    // Inner value types
    // ---------------------------------------------------------------------------------------------

    /**
     * Mutable per-statement accumulator collecting the card numbers belonging to a single
     * {@code (customer, account)} pair while the cross-references are streamed. One instance becomes
     * one emitted statement. Because the paged cross-reference reader returns rows in
     * {@code (custId, accountId, xrefCardNum)} order, every card for one pair arrives contiguously,
     * so a single accumulator at a time suffices for the control-break (no whole-file map needed).
     */
    private static final class StatementContext {

        /** The customer id this statement is for. */
        private final Long custId;

        /** The account id this statement is for. */
        private final Long acctId;

        /** The card numbers owned by this customer/account, in cross-reference scan order. */
        private final List<String> cardNumbers = new ArrayList<>();

        StatementContext(Long custId, Long acctId) {
            this.custId = custId;
            this.acctId = acctId;
        }

        /**
         * Returns {@code true} when the supplied {@code (custId, acctId)} pair equals this
         * context's key &mdash; the control-break "same group" test. Uses {@link Objects#equals}
         * for null-safe value comparison so a {@code null} component (an orphaned cross-reference)
         * is handled without throwing.
         *
         * @param otherCustId the customer id of the cross-reference being examined
         * @param otherAcctId the account id of the cross-reference being examined
         * @return {@code true} if both components match this context's key
         */
        private boolean matches(Long otherCustId, Long otherAcctId) {
            return Objects.equals(custId, otherCustId) && Objects.equals(acctId, otherAcctId);
        }
    }
}

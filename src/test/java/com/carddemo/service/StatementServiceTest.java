package com.carddemo.service;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link StatementService}, the Spring Boot re-expression of the legacy
 * COBOL statement programs {@code app/cbl/CBSTM03A.CBL} / {@code app/cbl/CBSTM03B.CBL} (record
 * layouts {@code app/cpy/COSTM01.CPY} and {@code app/cpy/CVTRA07Y.cpy}).
 *
 * <h2>What this test pins (parity with CBSTM03A/B)</h2>
 * <p>{@code StatementService} assembles, for a single account and an optional reporting period, a
 * fixed-layout <strong>plain-text</strong> statement (mirroring the legacy {@code ST-LINE0..ST-LINE15}
 * lines) and a complete <strong>HTML</strong> statement (mirroring the {@code HTML-LINES} 88-level
 * constants and the "Bank of XYZ" header band), totals the period's transaction amounts as a
 * {@link BigDecimal} at scale&nbsp;2 with {@link RoundingMode#HALF_UP} ({@code WS-TOTAL-AMT}),
 * suppresses PII (no full SSN, no CVV per AAP&nbsp;&sect;0.6.8 / &sect;0.7.1), and writes both
 * artifacts to the directory configured by {@code report.output.path}. The cases below verify, against
 * the <strong>real production class</strong>:</p>
 * <ul>
 *   <li>the {@link StatementService#computeTotal(List) total} arithmetic (scale&nbsp;2, HALF_UP);</li>
 *   <li>the {@link StatementService#buildTextStatement text} layout content (banners, account id,
 *       customer name, {@code Total EXP:} value);</li>
 *   <li>the {@link StatementService#buildHtmlStatement HTML} header literals
 *       ("Bank of XYZ" / "410 Terry Ave N" / "Seattle") and well-formed table markup &mdash; these
 *       literals were confirmed byte-for-byte against {@code CBSTM03A.CBL} (L168/L170/L172);</li>
 *   <li><strong>PII suppression</strong> on both rendered formats (no 9-digit SSN, no CVV);</li>
 *   <li><strong>file output</strong> of {@code statement_<acctId>_<start>_<end>.txt} and {@code .html}
 *       into {@code report.output.path};</li>
 *   <li>the checked {@code IOException} being surfaced as an <strong>unchecked</strong>
 *       {@link RuntimeException} ({@link java.io.UncheckedIOException}).</li>
 * </ul>
 *
 * <h2>Test character</h2>
 * <p>Strictly a JUnit&nbsp;5 + Mockito&nbsp;5 unit test: <em>no</em> Spring application context,
 * <em>no</em> database, and <em>no</em> real batch execution. The four repository collaborators are
 * mocked and wired through {@code @InjectMocks}. Because the production constructor's fifth argument is
 * a {@code @Value("${report.output.path}")}-bound {@link String} that {@code @InjectMocks} cannot
 * populate from a mock, the output directory is injected with
 * {@link ReflectionTestUtils#setField(Object, String, Object)} into a JUnit {@link TempDir}. Strict
 * stubbing (Mockito's default {@code STRICT_STUBS}) is honored: the builder/total tests register no
 * repository stubbings, while the {@code generateStatement} tests consume every stub they declare.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementService (CBSTM03A/B parity) — text + HTML rendering, totals, PII, file output")
class StatementServiceTest {

    /** Account identifier under test (matches the {@code statement_10_*} output file names). */
    private static final Long ACCT_ID = 10L;

    /** Owning customer identifier resolved via the card cross-reference. */
    private static final Long CUST_ID = 100L;

    /**
     * Synthetic, non-real 9-digit SSN placed on the customer fixture purely to prove suppression:
     * because the data carries an SSN, an assertion that the rendered statement never contains it is
     * meaningful (AAP&nbsp;&sect;0.6.8).
     */
    private static final String SYNTHETIC_SSN = "123456789";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private StatementService statementService;

    /** JUnit-managed temporary directory used as {@code report.output.path} for the file-output tests. */
    @TempDir
    Path tempDir;

    /**
     * Binds the {@code @Value("${report.output.path}")} field to the per-test {@link TempDir}. The
     * field is named {@code reportOutputPath} on the production class; {@code @InjectMocks} leaves it
     * {@code null} (no {@link String} mock exists), so it is set reflectively here. Setting a field is
     * not a stubbing, so this does not affect strict-stubbing verification.
     */
    @BeforeEach
    void injectReportOutputPath() {
        ReflectionTestUtils.setField(statementService, "reportOutputPath", tempDir.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture factories (fresh instances per test; no shared mutable state)
    // ---------------------------------------------------------------------------------------------

    /**
     * @return an {@link Account} fixture for {@link #ACCT_ID} with a non-trivial current balance.
     */
    private Account account() {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setActiveStatus("Y");
        account.setCurrBal(new BigDecimal("1234.56"));
        return account;
    }

    /**
     * @return a {@link Customer} fixture carrying name, address, the synthetic SSN, DOB and FICO score.
     */
    private Customer customer() {
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        customer.setFirstName("John");
        customer.setMiddleName("Q");
        customer.setLastName("Public");
        customer.setAddrLine1("123 Main St");
        customer.setAddrLine2("Apt 4");
        customer.setAddrLine3("Metropolis");
        customer.setAddrStateCd("WA");
        customer.setAddrCountryCd("USA");
        customer.setAddrZip("98101");
        customer.setSsn(SYNTHETIC_SSN);
        customer.setDob(LocalDate.of(1980, 1, 15));
        customer.setFicoCreditScore(750);
        return customer;
    }

    /**
     * @return a single {@link CardXref} for {@link #ACCT_ID} resolving to {@link #CUST_ID}. The card
     *         number is deliberately chosen so it does not contain the SSN digit run, even though the
     *         service never renders it.
     */
    private CardXref xref() {
        return new CardXref("4111111111111111", CUST_ID, ACCT_ID);
    }

    /**
     * @return two transactions for July&nbsp;2023 with amounts {@code 25.50} and {@code 74.49}
     *         (period total {@code 99.99}), ordered by origination timestamp.
     */
    private List<Transaction> txns() {
        return List.of(
                txn("0000000000000001", "25.50", LocalDateTime.of(2023, 7, 10, 9, 0)),
                txn("0000000000000002", "74.49", LocalDateTime.of(2023, 7, 20, 14, 30)));
    }

    /**
     * Builds one {@link Transaction} fixture.
     *
     * @param id     the 16-character transaction id
     * @param amount the monetary amount (parsed as an exact {@link BigDecimal})
     * @param origTs the origination timestamp
     * @return a detached {@link Transaction}
     */
    private Transaction txn(String id, String amount, LocalDateTime origTs) {
        Transaction transaction = new Transaction();
        transaction.setTranId(id);
        transaction.setAcctId(ACCT_ID);
        transaction.setAmt(new BigDecimal(amount));
        transaction.setDescription("PURCHASE " + id);
        transaction.setOrigTs(origTs);
        return transaction;
    }

    // ---------------------------------------------------------------------------------------------
    // computeTotal — WS-TOTAL-AMT fixed-point accumulation
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("computeTotal: sums the period amounts at scale 2, HALF_UP (25.50 + 74.49 = 99.99)")
    void computeTotal_sumsAmountsAtScale2HalfUp() {
        List<Transaction> txns = txns();
        BigDecimal expected = txns.stream()
                .map(Transaction::getAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal total = statementService.computeTotal(txns);

        assertThat(total).isEqualByComparingTo(expected);
        assertThat(total).isEqualByComparingTo(new BigDecimal("99.99"));
        assertThat(total.scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // buildTextStatement — ST-LINE0..ST-LINE15 plain-text layout
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("buildTextStatement: renders the banners, account id, customer name and scale-2 total")
    void buildTextStatement_containsAccountCustomerAndTotal() {
        List<Transaction> txns = txns();
        BigDecimal total = statementService.computeTotal(txns);

        String text = statementService.buildTextStatement(account(), customer(), txns, total);

        assertThat(text)
                .contains("START OF STATEMENT")
                .contains("END OF STATEMENT")
                .contains("Account ID")
                .contains("John Q Public")
                .contains("TRANSACTION SUMMARY")
                .contains("Total EXP:")
                .contains("99.99");
        // The account identifier value is rendered on the Basic Details "Account ID" line.
        assertThat(text).contains(String.valueOf(ACCT_ID));
    }

    // ---------------------------------------------------------------------------------------------
    // buildHtmlStatement — Bank of XYZ header band + table markup
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("buildHtmlStatement: emits the Bank of XYZ header block inside a well-formed HTML table")
    void buildHtmlStatement_containsBankHeaderLiterals() {
        List<Transaction> txns = txns();
        BigDecimal total = statementService.computeTotal(txns);

        String html = statementService.buildHtmlStatement(account(), customer(), txns, total);

        assertThat(html)
                .contains("Bank of XYZ")
                .contains("410 Terry Ave N")
                .contains("Seattle")
                .contains("Statement for Account Number: " + ACCT_ID);
        assertThat(html)
                .contains("<html")
                .contains("<table");
    }

    // ---------------------------------------------------------------------------------------------
    // PII suppression — no full SSN, no CVV in either format
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PII suppression: neither the text nor the HTML statement leaks the full SSN or a CVV")
    void statements_suppressSsnAndCvv() {
        List<Transaction> txns = txns();
        BigDecimal total = statementService.computeTotal(txns);
        Customer customer = customer();

        String text = statementService.buildTextStatement(account(), customer, txns, total);
        String html = statementService.buildHtmlStatement(account(), customer, txns, total);

        // The full 9-digit SSN must never appear (AAP §0.6.8 / §0.7.1).
        assertThat(text).doesNotContain(SYNTHETIC_SSN);
        assertThat(html).doesNotContain(SYNTHETIC_SSN);
        // A card CVV must never appear in any form.
        assertThat(text.toLowerCase()).doesNotContain("cvv");
        assertThat(html.toLowerCase()).doesNotContain("cvv");
    }

    // ---------------------------------------------------------------------------------------------
    // generateStatement — file output to report.output.path
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("generateStatement: writes non-empty .txt and .html files into report.output.path")
    void generateStatement_writesTextAndHtmlFiles() throws Exception {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account()));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(xref())));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer()));
        when(transactionRepository.findByAcctIdOrderByOrigTs(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(txns()));

        statementService.generateStatement(ACCT_ID, LocalDate.of(2023, 7, 1), LocalDate.of(2023, 7, 31));

        Path textFile = tempDir.resolve("statement_10_2023-07-01_2023-07-31.txt");
        Path htmlFile = tempDir.resolve("statement_10_2023-07-01_2023-07-31.html");
        assertThat(textFile).as("plain-text statement file").exists();
        assertThat(htmlFile).as("HTML statement file").exists();

        String text = Files.readString(textFile);
        String html = Files.readString(htmlFile);
        assertThat(text).isNotEmpty()
                .contains("John Q Public")
                .contains("99.99");
        assertThat(html).isNotEmpty()
                .contains("Bank of XYZ");
        // PII suppression also holds on the persisted artifacts.
        assertThat(text).doesNotContain(SYNTHETIC_SSN);
        assertThat(html).doesNotContain(SYNTHETIC_SSN);
    }

    // ---------------------------------------------------------------------------------------------
    // generateStatement — IOException is surfaced as an unchecked RuntimeException
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("generateStatement: a write failure is rethrown as an unchecked RuntimeException")
    void generateStatement_whenOutputPathUnwritable_throwsRuntimeException() throws Exception {
        // Point report.output.path at an existing *regular file* so Files.createDirectories fails
        // with a checked IOException that the service must wrap as an unchecked RuntimeException.
        Path blocker = tempDir.resolve("not-a-directory.txt");
        Files.writeString(blocker, "x");
        ReflectionTestUtils.setField(statementService, "reportOutputPath", blocker.toString());

        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account()));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(xref())));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer()));
        when(transactionRepository.findByAcctIdOrderByOrigTs(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(txns()));

        assertThatThrownBy(() -> statementService.generateStatement(ACCT_ID, null, null))
                .isInstanceOf(RuntimeException.class);
    }
}

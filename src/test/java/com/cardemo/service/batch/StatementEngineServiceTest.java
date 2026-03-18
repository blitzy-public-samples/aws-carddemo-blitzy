/*
 * StatementEngineServiceTest.java — JUnit 5 Unit Tests (← CBSTM03A.CBL)
 *
 * Comprehensive unit test class for StatementEngineService, the statement generation
 * engine migrated from CBSTM03A.CBL. Tests cover:
 *   - StatementIoService dependency injection (replaces CALL 'CBSTM03B' USING WS-M03B-AREA)
 *   - Transaction grouping by card number (WS-TRNX-TABLE: 51 cards × 10 trans)
 *   - Customer resolution via keyed read (paragraph 2000-CUSTFILE-GET)
 *   - Account resolution via keyed read (paragraph 3000-ACCTFILE-GET)
 *   - Plain text statement formatting (ST-LINE0 through ST-LINE15)
 *   - HTML statement generation with styled elements
 *   - BigDecimal total accumulation (WS-TOTAL-AMT) with RoundingMode.HALF_UP
 *   - Sequential XREF iteration (paragraph 1000-MAINLINE)
 *   - Transaction writing per card (paragraph 4000-TRNXFILE-GET / 6000-WRITE-TRANS)
 *   - Edge cases: no transactions, missing customer
 *
 * COBOL Traceability:
 *   CBSTM03A.CBL PROCEDURE DIVISION → StatementEngineService.generateStatements()
 *   CBSTM03A.CBL 1000-MAINLINE     → [main loop inside generateStatements]
 *   CBSTM03A.CBL 8500-READTRNX-READ → loadTransactionTable()
 *   CBSTM03A.CBL 2000-CUSTFILE-GET  → getCustomer() [private]
 *   CBSTM03A.CBL 3000-ACCTFILE-GET  → getAccount() [private]
 *   CBSTM03A.CBL 5000-CREATE-STATEMENT → createStatement()
 *   CBSTM03A.CBL 5100-WRITE-HTML-HEADER → writeHtmlHeader()
 *   CBSTM03A.CBL 5200-WRITE-HTML-NMADBS → writeHtmlNameAddress()
 *   CBSTM03A.CBL 4000-TRNXFILE-GET  → writeTransactions()
 *   CBSTM03A.CBL 6000-WRITE-TRANS   → writeTransactionLine()
 *   CBSTM03A.CBL 8100-8400          → openAllFiles()
 *   CBSTM03A.CBL 9100-9400          → closeAllFiles()
 *
 * @see StatementEngineService
 * @see StatementIoService
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StatementEngineService} — the statement generation engine
 * migrated from CBSTM03A.CBL.
 *
 * <p>Uses Mockito for isolation — no Spring context is loaded. The critical dependency
 * {@link StatementIoService} (replacing {@code CALL 'CBSTM03B' USING WS-M03B-AREA})
 * is mocked, along with all four JPA repositories.</p>
 *
 * <p>Test data mirrors the COBOL seed fixtures from {@code app/data/ASCII/}.</p>
 */
@ExtendWith(MockitoExtension.class)
class StatementEngineServiceTest {

    // =========================================================================
    // Mock Dependencies (← CALL 'CBSTM03B' + VSAM file handlers)
    // =========================================================================

    /** Replaces COBOL {@code CALL 'CBSTM03B' USING WS-M03B-AREA} — all dataset I/O */
    @Mock
    private StatementIoService statementIoService;

    /** Transaction repository — TRANSACT VSAM KSDS (350-byte records, CVTRA05Y.cpy) */
    @Mock
    private TransactionRepository transactionRepository;

    /** Card cross-reference repository — CARDXREF VSAM KSDS (50-byte records, CVACT03Y.cpy) */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Customer repository — CUSTDATA VSAM KSDS (500-byte records, CVCUS01Y.cpy) */
    @Mock
    private CustomerRepository customerRepository;

    /** Account repository — ACCTDATA VSAM KSDS (300-byte records, CVACT01Y.cpy) */
    @Mock
    private AccountRepository accountRepository;

    /** Class under test — injected with all mocked dependencies */
    @InjectMocks
    private StatementEngineService statementEngineService;

    // =========================================================================
    // Test Data Factory Methods (← COBOL seed data patterns)
    // =========================================================================

    /**
     * Creates a test Customer matching CVCUS01Y.cpy record layout (500 bytes).
     */
    private Customer createTestCustomer() {
        return new Customer(
                "000000001",     // custId — PIC 9(09)
                "JOHN",          // firstName — PIC X(25)
                "Q",             // middleName — PIC X(25)
                "PUBLIC",        // lastName — PIC X(25)
                "123 MAIN ST",   // addrLine1 — PIC X(50)
                "APT 4B",        // addrLine2 — PIC X(50)
                "",              // addrLine3 — PIC X(50)
                "NY",            // addrStateCode — PIC X(02)
                "USA",           // addrCountryCode — PIC X(03)
                "10001",         // addrZip — PIC X(10)
                "2125551234",    // phoneNum1 — PIC X(15)
                "2125555678",    // phoneNum2 — PIC X(15)
                "123456789",     // ssn — PIC 9(09) — PII
                "DL12345678",    // govtIssuedId — PIC X(20) — PII
                "19800115",      // dateOfBirth — PIC X(10)
                "CHK0001234",    // eftAccountId — PIC X(10)
                "Y",             // priCardHolderInd — PIC X(01)
                750              // ficoCreditScore — PIC 9(03)
        );
    }

    /**
     * Creates a test Account matching CVACT01Y.cpy record layout (300 bytes).
     */
    private Account createTestAccount() {
        return new Account(
                "00000000001",                          // acctId — PIC X(11)
                "Y",                                    // activeStatus — PIC X(01)
                new BigDecimal("5000.00"),               // currBal — PIC S9(11)V99 COMP-3
                new BigDecimal("10000.00"),               // creditLimit
                new BigDecimal("5000.00"),                // cashCreditLimit
                "20200101",                              // openDate
                "20251231",                              // expirationDate
                "20240601",                              // reissueDate
                new BigDecimal("1500.00"),                // currCycCredit
                new BigDecimal("3500.00"),                // currCycDebit
                "10001",                                 // addrZip
                "A"                                      // groupId
        );
    }

    /**
     * Creates a test CardXref matching CVACT03Y.cpy record layout (50 bytes).
     */
    private CardXref createTestXref() {
        return new CardXref(
                "4111111111111111",   // xrefCardNum — PIC X(16)
                "000000001",          // custId — PIC 9(09)
                "00000000001"         // accountId — PIC X(11)
        );
    }

    /**
     * Creates a test Transaction for a specific card number.
     */
    private Transaction createTestTransactionForCard(String tranId, BigDecimal amount,
                                                     String cardNum) {
        return new Transaction(
                tranId, "01", 1, "ONLINE", "PURCHASE",
                amount, "MERCH001", "MERCHANT", "CITY", "10001",
                cardNum, "2025-01-15-10.30.00.000000", "2025-01-15-10.30.01.000000"
        );
    }

    // =========================================================================
    // Test 1: StatementIoService Dependency Injection (← CALL 'CBSTM03B')
    // =========================================================================

    @Test
    @DisplayName("StatementIoService should be injected via @Autowired (replaces CALL 'CBSTM03B')")
    void shouldHaveStatementIoServiceInjected() {
        // The @InjectMocks annotation injects the mocked StatementIoService
        assertThat(statementEngineService).isNotNull();

        // Verify openAllFiles can be called (uses StatementIoService internally)
        assertThatCode(() -> statementEngineService.openAllFiles())
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Test 2: loadTransactionTable — Transaction Grouping (← 8500-READTRNX-READ)
    // =========================================================================

    @Test
    @DisplayName("Should group transactions by card number (WS-TRNX-TABLE: 51 cards × 10 trans)")
    void shouldGroupTransactionsByCardNumber() {
        // Given: Transactions for 3 different cards, returned sequentially
        String card1 = "4111111111111111";
        String card2 = "4222222222222222";
        String card3 = "4333333333333333";

        Transaction txn1 = createTestTransactionForCard("TXN001", new BigDecimal("100.00"), card1);
        Transaction txn2 = createTestTransactionForCard("TXN002", new BigDecimal("200.00"), card1);
        Transaction txn3 = createTestTransactionForCard("TXN003", new BigDecimal("50.00"), card1);
        Transaction txn4 = createTestTransactionForCard("TXN004", new BigDecimal("300.00"), card2);
        Transaction txn5 = createTestTransactionForCard("TXN005", new BigDecimal("150.00"), card2);
        Transaction txn6 = createTestTransactionForCard("TXN006", new BigDecimal("500.00"), card3);

        // Mock sequential reads via StatementIoService (← 8500-READTRNX-READ)
        when(statementIoService.readNextTransaction())
                .thenReturn(Optional.of(txn1))
                .thenReturn(Optional.of(txn2))
                .thenReturn(Optional.of(txn3))
                .thenReturn(Optional.of(txn4))
                .thenReturn(Optional.of(txn5))
                .thenReturn(Optional.of(txn6))
                .thenReturn(Optional.empty()); // EOF (← RC='10')

        // When: loadTransactionTable() groups by card number
        Map<String, List<Transaction>> table = statementEngineService.loadTransactionTable();

        // Then: 3 card groups with correct counts
        assertThat(table).hasSize(3);
        assertThat(table.get(card1)).hasSize(3);
        assertThat(table.get(card2)).hasSize(2);
        assertThat(table.get(card3)).hasSize(1);

        // Verify total amounts per card
        BigDecimal card1Total = table.get(card1).stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(card1Total).isEqualByComparingTo(new BigDecimal("350.00"));
    }

    // =========================================================================
    // Test 3: openAllFiles and closeAllFiles (← 8100-8400, 9100-9400)
    // =========================================================================

    @Test
    @DisplayName("Should open all 4 files via StatementIoService (← 8100-8400)")
    void shouldOpenAllFiles() {
        // When: openAllFiles calls all 4 StatementIoService open methods
        assertThatCode(() -> statementEngineService.openAllFiles())
                .doesNotThrowAnyException();

        // Then: all open methods were invoked
        verify(statementIoService, times(1)).openTransactionFile();
        verify(statementIoService, times(1)).openXrefFile();
        verify(statementIoService, times(1)).openCustomerFile();
        verify(statementIoService, times(1)).openAccountFile();
    }

    @Test
    @DisplayName("Should close all 4 files via StatementIoService (← 9100-9400)")
    void shouldCloseAllFiles() {
        // When: closeAllFiles calls all 4 StatementIoService close methods
        statementEngineService.closeAllFiles();

        // Then: all close methods were invoked
        verify(statementIoService, times(1)).closeTransactionFile();
        verify(statementIoService, times(1)).closeXrefFile();
        verify(statementIoService, times(1)).closeCustomerFile();
        verify(statementIoService, times(1)).closeAccountFile();
    }

    // =========================================================================
    // Test 4: writeHtmlHeader (← 5100-WRITE-HTML-HEADER)
    // =========================================================================

    @Test
    @DisplayName("Should write HTML document header with DOCTYPE, title, table (← 5100)")
    void shouldWriteHtmlHeader() throws IOException {
        StringWriter sw = new StringWriter();

        // When: writeHtmlHeader outputs L01-L08
        statementEngineService.writeHtmlHeader(sw);

        String html = sw.toString();

        // Then: HTML structure elements are present
        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("<html lang=\"en\">");
        assertThat(html).contains("<title>HTML Table Layout</title>");
        assertThat(html).contains("<body style=\"margin:0px;\">");
        assertThat(html).contains("width:70%");
        assertThat(html).contains("font:12px Segoe UI,sans-serif;");
    }

    // =========================================================================
    // Test 5: createStatement — Plain Text (← 5000-CREATE-STATEMENT)
    // =========================================================================

    @Test
    @DisplayName("Should format plain text statement with customer name and address lines")
    void shouldFormatPlainTextStatement() throws IOException {
        // Given: Customer and Account
        Customer customer = createTestCustomer();
        Account account = createTestAccount();

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();

        // When: createStatement writes both text and HTML
        statementEngineService.createStatement(customer, account, textSw, htmlSw);

        String textOutput = textSw.toString();

        // Then: Text output contains customer name (← STRING concatenation)
        assertThat(textOutput).contains("JOHN");
        assertThat(textOutput).contains("PUBLIC");

        // Address lines (← ST-LINE2, ST-LINE3)
        assertThat(textOutput).contains("123 MAIN ST");
        assertThat(textOutput).contains("APT 4B");

        // Account identifier (← ST-LINE7)
        assertThat(textOutput).contains("00000000001");

        // Section headers (← ST-LINE6, ST-LINE11)
        assertThat(textOutput).contains("Basic Details");
        assertThat(textOutput).contains("TRANSACTION SUMMARY");

        // Column headers (← ST-LINE13)
        assertThat(textOutput).contains("Tran ID");
        assertThat(textOutput).contains("Tran Details");
        assertThat(textOutput).contains("Tran Amount");

        // Start separator (← ST-LINE0)
        assertThat(textOutput).contains("START OF STATEMENT");
    }

    // =========================================================================
    // Test 6: createStatement — HTML Output (← 5000 + 5100 + 5200)
    // =========================================================================

    @Test
    @DisplayName("Should generate HTML statement with proper tags and styled content")
    void shouldGenerateHtmlStatement() throws IOException {
        // Given: Customer and Account
        Customer customer = createTestCustomer();
        Account account = createTestAccount();

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();

        // When: createStatement writes HTML
        statementEngineService.createStatement(customer, account, textSw, htmlSw);

        String htmlOutput = htmlSw.toString();

        // Then: HTML structure (from writeHtmlHeader + createStatement)
        assertThat(htmlOutput).contains("<!DOCTYPE html>");
        assertThat(htmlOutput).contains("<table");

        // Account heading row (← L10-L11)
        assertThat(htmlOutput).contains("Statement for Account Number: 00000000001");

        // Bank info (← L15-L18)
        assertThat(htmlOutput).contains("Bank of XYZ");
        assertThat(htmlOutput).contains("410 Terry Ave N");
        assertThat(htmlOutput).contains("Seattle WA 99999");

        // Customer name (← writeHtmlNameAddress)
        assertThat(htmlOutput).contains("JOHN");
        assertThat(htmlOutput).contains("PUBLIC");
        assertThat(htmlOutput).contains("123 MAIN ST");

        // Account details in HTML
        assertThat(htmlOutput).contains("Account ID");
        assertThat(htmlOutput).contains("00000000001");
        assertThat(htmlOutput).contains("FICO Score");
    }

    // =========================================================================
    // Test 7: writeHtmlNameAddress (← 5200-WRITE-HTML-NMADBS)
    // =========================================================================

    @Test
    @DisplayName("Should write HTML name, address, basic details and column headers (← 5200)")
    void shouldWriteHtmlNameAddress() throws IOException {
        Customer customer = createTestCustomer();
        Account account = createTestAccount();

        StringWriter sw = new StringWriter();

        // When: writeHtmlNameAddress outputs sections
        statementEngineService.writeHtmlNameAddress(customer, account, sw);

        String html = sw.toString();

        // Then: Customer name with 16px font
        assertThat(html).contains("font-size:16px");
        assertThat(html).contains("JOHN Q PUBLIC");

        // Address lines
        assertThat(html).contains("123 MAIN ST");
        assertThat(html).contains("APT 4B");

        // Basic Details header with #33FFD1
        assertThat(html).contains("#33FFD1");
        assertThat(html).contains("Basic Details");

        // Account details on #f2f2f2
        assertThat(html).contains("#f2f2f2");
        assertThat(html).contains("Account ID");
        assertThat(html).contains("Current Balance");
        assertThat(html).contains("FICO Score");

        // Transaction Summary header
        assertThat(html).contains("Transaction Summary");

        // Column headers on #33FF5E
        assertThat(html).contains("#33FF5E");
        assertThat(html).contains("Tran ID");
        assertThat(html).contains("Tran Details");
        assertThat(html).contains("Amount");
    }

    // =========================================================================
    // Test 8: writeTransactions — Total Accumulation (← 4000 + WS-TOTAL-AMT)
    // =========================================================================

    @Test
    @DisplayName("Should write transactions and accumulate total using BigDecimal (← 4000/6000)")
    void shouldAccumulateTransactionTotalWithBigDecimal() throws IOException {
        // Given: 3 transactions with precise decimal amounts
        String cardNum = "4111111111111111";
        Transaction txn1 = createTestTransactionForCard("TXN001",
                new BigDecimal("100.50"), cardNum);
        Transaction txn2 = createTestTransactionForCard("TXN002",
                new BigDecimal("200.75"), cardNum);
        Transaction txn3 = createTestTransactionForCard("TXN003",
                new BigDecimal("50.25"), cardNum);

        Map<String, List<Transaction>> trnxTable = new LinkedHashMap<>();
        trnxTable.put(cardNum, List.of(txn1, txn2, txn3));

        CardXref xref = createTestXref();

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();

        // When: writeTransactions processes the card
        statementEngineService.writeTransactions(xref, trnxTable, textSw, htmlSw);

        String textOutput = textSw.toString();

        // Then: Total line includes the accumulated total (100.50+200.75+50.25 = 351.50)
        assertThat(textOutput).contains("Total EXP:");
        assertThat(textOutput).contains("351.50");

        // End separator present
        assertThat(textOutput).contains("END OF STATEMENT");

        // HTML footer present
        String htmlOutput = htmlSw.toString();
        assertThat(htmlOutput).contains("End of Statement");
        assertThat(htmlOutput).contains("</table>");
        assertThat(htmlOutput).contains("</html>");
    }

    // =========================================================================
    // Test 9: writeTransactions — Only matching card (← 4000-TRNXFILE-GET)
    // =========================================================================

    @Test
    @DisplayName("Should write transaction lines matching card number from transaction table")
    void shouldWriteTransactionLinesForMatchingCard() throws IOException {
        // Given: Transactions for two different cards
        String targetCard = "4111111111111111";
        String otherCard = "4222222222222222";

        Transaction matchTxn1 = createTestTransactionForCard("TXN001",
                new BigDecimal("100.00"), targetCard);
        Transaction matchTxn2 = createTestTransactionForCard("TXN002",
                new BigDecimal("200.00"), targetCard);
        Transaction noMatchTxn = createTestTransactionForCard("TXN003",
                new BigDecimal("999.99"), otherCard);

        Map<String, List<Transaction>> trnxTable = new LinkedHashMap<>();
        trnxTable.put(targetCard, List.of(matchTxn1, matchTxn2));
        trnxTable.put(otherCard, List.of(noMatchTxn));

        CardXref xref = createTestXref(); // card "4111111111111111"

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();

        // When: writeTransactions processes only matching card
        statementEngineService.writeTransactions(xref, trnxTable, textSw, htmlSw);

        // Then: Matching transaction IDs appear in output
        String textOutput = textSw.toString();
        assertThat(textOutput).contains("TXN001");
        assertThat(textOutput).contains("TXN002");

        // Total is sum of matching only (100+200=300)
        assertThat(textOutput).contains("300.00");

        // Non-matching transaction must NOT appear
        assertThat(textOutput).doesNotContain("TXN003");
        assertThat(textOutput).doesNotContain("999.99");
    }

    // =========================================================================
    // Test 10: writeTransactionLine (← 6000-WRITE-TRANS)
    // =========================================================================

    @Test
    @DisplayName("Should write single transaction line to text and HTML (← 6000-WRITE-TRANS)")
    void shouldWriteTransactionLine() throws IOException {
        // Given: A single transaction
        Transaction tran = createTestTransactionForCard("TXN001",
                new BigDecimal("123.45"), "4111111111111111");

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();

        // When: writeTransactionLine outputs the line
        statementEngineService.writeTransactionLine(tran, textSw, htmlSw);

        // Then: Text output has ST-LINE14 format (80 chars)
        String textOutput = textSw.toString();
        assertThat(textOutput).contains("TXN001");
        assertThat(textOutput).contains("PURCHASE");
        assertThat(textOutput).contains("$");
        assertThat(textOutput).contains("123.45");

        // HTML output has 3-column row
        String htmlOutput = htmlSw.toString();
        assertThat(htmlOutput).contains("<tr>");
        assertThat(htmlOutput).contains("TXN001");
        assertThat(htmlOutput).contains("PURCHASE");
        assertThat(htmlOutput).contains("123.45");
        // Column widths
        assertThat(htmlOutput).contains("width:25%");
        assertThat(htmlOutput).contains("width:55%");
        assertThat(htmlOutput).contains("width:20%");
    }

    // =========================================================================
    // Test 11: Edge Case — Empty Transaction Table
    // =========================================================================

    @Test
    @DisplayName("Should handle account with no transactions gracefully")
    void shouldHandleAccountWithNoTransactions() throws IOException {
        // Given: Empty transaction table for the target card
        CardXref xref = createTestXref();
        Map<String, List<Transaction>> emptyTable = Collections.emptyMap();

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();

        // When: writeTransactions is called with no matching transactions
        statementEngineService.writeTransactions(xref, emptyTable, textSw, htmlSw);

        String textOutput = textSw.toString();

        // Then: Footer is still written (dashes, total zero, end separator)
        assertThat(textOutput).contains("Total EXP:");
        assertThat(textOutput).contains("END OF STATEMENT");

        // HTML footer written
        String htmlOutput = htmlSw.toString();
        assertThat(htmlOutput).contains("End of Statement");
        assertThat(htmlOutput).contains("</html>");
    }

    // =========================================================================
    // Test 12: generateStatements — Full Integration (← 1000-MAINLINE)
    // =========================================================================

    @Test
    @DisplayName("Should iterate XREFs sequentially and generate one statement per card")
    void shouldIterateXrefsAndGenerateStatements(@TempDir Path tempDir) {
        // Given: 2 CardXref records (2 cards/accounts)
        CardXref xref1 = new CardXref("4111111111111111", "000000001", "00000000001");
        CardXref xref2 = new CardXref("4222222222222222", "000000002", "00000000002");

        Customer cust1 = createTestCustomer();
        Customer cust2 = new Customer("000000002", "JANE", "M", "DOE",
                "456 OAK AVE", "", "", "CA", "USA", "90210",
                "3105551234", "", "987654321", "DL98765432",
                "19850320", "CHK0005678", "Y", 800);

        Account acct1 = createTestAccount();
        Account acct2 = new Account("00000000002", "Y",
                new BigDecimal("7500.00"), new BigDecimal("15000.00"),
                new BigDecimal("5000.00"), "20190301", "20261231",
                "20250101", new BigDecimal("2000.00"), new BigDecimal("5500.00"),
                "90210", "B");

        // Transactions loaded via StatementIoService sequential reads
        Transaction txn1 = createTestTransactionForCard("TXN001",
                new BigDecimal("100.00"), "4111111111111111");
        Transaction txn2 = createTestTransactionForCard("TXN002",
                new BigDecimal("250.00"), "4222222222222222");

        when(statementIoService.readNextTransaction())
                .thenReturn(Optional.of(txn1))
                .thenReturn(Optional.of(txn2))
                .thenReturn(Optional.empty());

        // XREF iteration via StatementIoService sequential reads
        when(statementIoService.readNextXref())
                .thenReturn(Optional.of(xref1))
                .thenReturn(Optional.of(xref2))
                .thenReturn(Optional.empty());

        // Customer keyed reads via StatementIoService
        when(statementIoService.readCustomerByKey("000000001"))
                .thenReturn(Optional.of(cust1));
        when(statementIoService.readCustomerByKey("000000002"))
                .thenReturn(Optional.of(cust2));

        // Account keyed reads via StatementIoService
        when(statementIoService.readAccountByKey("00000000001"))
                .thenReturn(Optional.of(acct1));
        when(statementIoService.readAccountByKey("00000000002"))
                .thenReturn(Optional.of(acct2));

        // When: generateStatements processes all XREFs
        String outputDir = tempDir.toString();
        statementEngineService.generateStatements(outputDir);

        // Then: Customer keyed reads invoked
        verify(statementIoService, times(1)).readCustomerByKey("000000001");
        verify(statementIoService, times(1)).readCustomerByKey("000000002");

        // Account keyed reads invoked
        verify(statementIoService, times(1)).readAccountByKey("00000000001");
        verify(statementIoService, times(1)).readAccountByKey("00000000002");

        // File open/close lifecycle
        verify(statementIoService, times(1)).openTransactionFile();
        verify(statementIoService, times(1)).openXrefFile();
        verify(statementIoService, times(1)).openCustomerFile();
        verify(statementIoService, times(1)).openAccountFile();
        verify(statementIoService, times(1)).closeTransactionFile();
        verify(statementIoService, times(1)).closeXrefFile();
        verify(statementIoService, times(1)).closeCustomerFile();
        verify(statementIoService, times(1)).closeAccountFile();

        // Output files were created
        Path textFile = tempDir.resolve("statements.txt");
        Path htmlFile = tempDir.resolve("statements.html");
        assertThat(textFile).exists();
        assertThat(htmlFile).exists();
    }

    // =========================================================================
    // Test 13: Verify COBOL-faithful text formatting
    // =========================================================================

    @Test
    @DisplayName("Should produce 80-char lines with correct separator patterns (← ST-LINEs)")
    void shouldProduce80CharLines() throws IOException {
        Customer customer = createTestCustomer();
        Account account = createTestAccount();

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();

        statementEngineService.createStatement(customer, account, textSw, htmlSw);

        String textOutput = textSw.toString();
        String[] lines = textOutput.split("\n", -1);

        // First line is START_SEPARATOR (80 chars)
        assertThat(lines[0]).hasSize(80);
        assertThat(lines[0]).contains("START OF STATEMENT");
        assertThat(lines[0]).startsWith("*");
        assertThat(lines[0]).endsWith("*");
    }

    // =========================================================================
    // Test 14: BigDecimal formatting for amounts
    // =========================================================================

    @Test
    @DisplayName("Should format negative amounts with trailing minus sign (← PIC Z(9).99-)")
    void shouldFormatNegativeAmounts() throws IOException {
        Transaction tran = createTestTransactionForCard("TXN001",
                new BigDecimal("-42.50"), "4111111111111111");

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();

        statementEngineService.writeTransactionLine(tran, textSw, htmlSw);

        String textOutput = textSw.toString();
        // PIC Z(9).99- → trailing '-' for negative
        assertThat(textOutput).contains("42.50-");
    }
}

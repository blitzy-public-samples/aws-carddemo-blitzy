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
 *   CBSTM03A.CBL 1000-MAINLINE     → processMainline()
 *   CBSTM03A.CBL 8500-READTRNX-READ → loadTransactionTable()
 *   CBSTM03A.CBL 2000-CUSTFILE-GET  → getCustomer()
 *   CBSTM03A.CBL 3000-ACCTFILE-GET  → getAccount()
 *   CBSTM03A.CBL 5000-CREATE-STATEMENT → createStatement()
 *   CBSTM03A.CBL 6000-WRITE-TRANS   → writeTransactionLine()
 *
 * @see StatementEngineService
 * @see StatementIoService
 */
package com.cardemo.service.batch;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
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
     *
     * <p>COBOL fields: CUST-ID(9), CUST-FIRST-NAME(25), CUST-MIDDLE-NAME(25),
     * CUST-LAST-NAME(25), CUST-ADDR-LINE-1(50), etc.</p>
     *
     * @return Customer with representative test data
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
     *
     * <p>All monetary fields use BigDecimal — no floating-point (COBOL COMP-3 parity).</p>
     *
     * @return Account with representative test data
     */
    private Account createTestAccount() {
        return new Account(
                "00000000001",                          // acctId — PIC X(11)
                "Y",                                    // activeStatus — PIC X(01)
                new BigDecimal("5000.00"),               // currBal — PIC S9(11)V99 COMP-3
                new BigDecimal("10000.00"),               // creditLimit — PIC S9(11)V99 COMP-3
                new BigDecimal("5000.00"),                // cashCreditLimit — PIC S9(11)V99 COMP-3
                "20200101",                              // openDate — PIC X(10)
                "20251231",                              // expirationDate — PIC X(10)
                "20240601",                              // reissueDate — PIC X(10)
                new BigDecimal("1500.00"),                // currCycCredit — PIC S9(11)V99 COMP-3
                new BigDecimal("3500.00"),                // currCycDebit — PIC S9(11)V99 COMP-3
                "10001",                                 // addrZip — PIC X(10)
                "A"                                      // groupId — PIC X(10)
        );
    }

    /**
     * Creates a test CardXref matching CVACT03Y.cpy record layout (50 bytes).
     *
     * <p>Links card number → customer → account for statement generation.</p>
     *
     * @return CardXref with representative test data
     */
    private CardXref createTestXref() {
        return new CardXref(
                "4111111111111111",   // xrefCardNum — PIC X(16)
                "000000001",          // custId — PIC 9(09)
                "00000000001"         // accountId — PIC X(11)
        );
    }

    /**
     * Creates a test Transaction matching CVTRA05Y.cpy record layout (350 bytes).
     *
     * @param tranId  Transaction identifier
     * @param amount  Transaction amount (BigDecimal — COMP-3 parity)
     * @return Transaction with given ID and amount, default other fields
     */
    private Transaction createTestTransaction(String tranId, BigDecimal amount) {
        return new Transaction(
                tranId,                     // tranId — PIC X(16)
                "01",                       // typeCode — PIC X(02)
                1,                          // categoryCode — PIC 9(04)
                "ONLINE",                   // source — PIC X(10)
                "TEST PURCHASE",            // description — PIC X(100)
                amount,                     // amount — PIC S9(9)V99 COMP-3
                "MERCH001",                 // merchantId — PIC X(09)
                "TEST MERCHANT",            // merchantName — PIC X(50)
                "NEW YORK",                 // merchantCity — PIC X(30)
                "10001",                    // merchantZip — PIC X(10)
                "4111111111111111",          // cardNum — PIC X(16)
                "2025-01-15-10.30.00.000000", // origTimestamp — 26-char ISO-8601
                "2025-01-15-10.30.01.000000"  // procTimestamp — 26-char ISO-8601
        );
    }

    /**
     * Creates a test Transaction for a specific card number.
     *
     * @param tranId  Transaction identifier
     * @param amount  Transaction amount
     * @param cardNum Card number (16 chars)
     * @return Transaction bound to the specified card
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

    /**
     * Verifies StatementIoService is injected via constructor (replaces
     * {@code CALL 'CBSTM03B' USING WS-M03B-AREA} in COBOL).
     *
     * <p>The COBOL program CBSTM03A calls CBSTM03B as a subroutine for all file I/O.
     * In Java, this dependency is injected via {@code @Autowired} constructor.</p>
     */
    @Test
    @DisplayName("StatementIoService should be injected via @Autowired (replaces CALL 'CBSTM03B')")
    void shouldHaveStatementIoServiceInjected() {
        // The @InjectMocks annotation injects the mocked StatementIoService
        // Verify the service is accessible and not null by invoking a benign method
        assertThat(statementEngineService).isNotNull();

        // Verify the class can be called without NullPointerException on dependencies
        assertThatCode(() -> statementEngineService.dispatchFileOperation("TRNXFILE"))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Test 2: Transaction Grouping by Card Number (← WS-TRNX-TABLE)
    // =========================================================================

    /**
     * Verifies transaction grouping by card number, replacing the COBOL
     * WS-TRNX-TABLE (51 cards × 10 transactions per card).
     *
     * <p>COBOL structure:</p>
     * <pre>{@code
     * 01  WS-TRNX-TABLE.
     *     05  WS-CARD-REC OCCURS 51 TIMES.
     *         10  WS-CARD-NUM     PIC X(16).
     *         10  WS-TRAN-REC OCCURS 10 TIMES.
     * }</pre>
     *
     * <p>Java uses {@code Map<String, List<Transaction>>} instead of fixed-size 2D array.</p>
     */
    @Test
    @DisplayName("Should group transactions by card number (WS-TRNX-TABLE: 51 cards × 10 trans)")
    void shouldGroupTransactionsByCardNumber() {
        // Given: Transactions for 3 different cards
        String card1 = "4111111111111111";
        String card2 = "4222222222222222";
        String card3 = "4333333333333333";

        List<Transaction> allTxns = List.of(
                createTestTransactionForCard("TXN001", new BigDecimal("100.00"), card1),
                createTestTransactionForCard("TXN002", new BigDecimal("200.00"), card1),
                createTestTransactionForCard("TXN003", new BigDecimal("50.00"), card1),
                createTestTransactionForCard("TXN004", new BigDecimal("300.00"), card2),
                createTestTransactionForCard("TXN005", new BigDecimal("150.00"), card2),
                createTestTransactionForCard("TXN006", new BigDecimal("500.00"), card3)
        );

        when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(allTxns);

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
    // Test 3: Customer Resolution (← 2000-CUSTFILE-GET)
    // =========================================================================

    /**
     * Verifies customer lookup via repository keyed read, translating
     * CBSTM03A.CBL paragraph 2000-CUSTFILE-GET.
     *
     * <p>COBOL pattern: {@code EXEC CICS READ FILE('CUSTFILE') INTO(CUSTOMER-RECORD)
     * RIDFLD(XREF-CUST-ID)}</p>
     */
    @Test
    @DisplayName("Should resolve customer via repository keyed read (← 2000-CUSTFILE-GET)")
    void shouldResolveCustomerViaStatementIoService() {
        // Given: Customer exists for the keyed lookup
        Customer testCustomer = createTestCustomer();
        when(customerRepository.findById("000000001")).thenReturn(Optional.of(testCustomer));

        // When: getCustomer is called with the XREF-CUST-ID
        Optional<Customer> result = statementEngineService.getCustomer("000000001");

        // Then: Customer is resolved successfully
        assertThat(result).isPresent();
        assertThat(result.get().getFirstName()).isEqualTo("JOHN");
        assertThat(result.get().getLastName()).isEqualTo("PUBLIC");
        assertThat(result.get().getAddrLine1()).isEqualTo("123 MAIN ST");

        // Verify repository was called with exact key
        verify(customerRepository, times(1)).findById("000000001");
    }

    // =========================================================================
    // Test 4: Account Resolution (← 3000-ACCTFILE-GET)
    // =========================================================================

    /**
     * Verifies account lookup via repository keyed read, translating
     * CBSTM03A.CBL paragraph 3000-ACCTFILE-GET.
     *
     * <p>COBOL pattern: {@code EXEC CICS READ FILE('ACCTFILE') INTO(ACCOUNT-RECORD)
     * RIDFLD(XREF-ACCT-ID)}</p>
     */
    @Test
    @DisplayName("Should resolve account via repository keyed read (← 3000-ACCTFILE-GET)")
    void shouldResolveAccountViaStatementIoService() {
        // Given: Account exists for the keyed lookup
        Account testAccount = createTestAccount();
        when(accountRepository.findById("00000000001")).thenReturn(Optional.of(testAccount));

        // When: getAccount is called with the XREF-ACCT-ID
        Optional<Account> result = statementEngineService.getAccount("00000000001");

        // Then: Account is resolved successfully with BigDecimal balance
        assertThat(result).isPresent();
        assertThat(result.get().getCurrBal()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(result.get().getCreditLimit()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(result.get().getActiveStatus()).isEqualTo("Y");

        // Verify repository was called with exact key
        verify(accountRepository, times(1)).findById("00000000001");
    }

    // =========================================================================
    // Test 5: Plain Text Statement Formatting (← 5000-CREATE-STATEMENT)
    // =========================================================================

    /**
     * Verifies plain text statement output contains customer name, address,
     * account details, translating CBSTM03A.CBL paragraph 5000-CREATE-STATEMENT.
     *
     * <p>COBOL STRING operation for name formatting:</p>
     * <pre>{@code
     * STRING CUST-FIRST-NAME DELIMITED BY '  '
     *        ' ' DELIMITED SIZE
     *        CUST-MIDDLE-NAME DELIMITED BY '  '
     *        ' ' DELIMITED SIZE
     *        CUST-LAST-NAME DELIMITED BY '  '
     *        INTO ST-CUSTNAME
     * }</pre>
     */
    @Test
    @DisplayName("Should format plain text statement with customer name and address")
    void shouldFormatPlainTextStatement() throws IOException {
        // Given: Customer, Account, and CardXref
        Customer customer = createTestCustomer();
        Account account = createTestAccount();
        CardXref xref = createTestXref();

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();
        try (BufferedWriter textWriter = new BufferedWriter(textSw);
             BufferedWriter htmlWriter = new BufferedWriter(htmlSw)) {

            // When: createStatement generates the formatted output
            statementEngineService.createStatement(customer, account, xref,
                    textWriter, htmlWriter);
            textWriter.flush();
        }

        String textOutput = textSw.toString();

        // Then: Text output contains customer name (STRING concatenation)
        // COBOL: STRING CUST-FIRST-NAME ' ' CUST-MIDDLE-NAME ' ' CUST-LAST-NAME
        assertThat(textOutput).contains("JOHN");
        assertThat(textOutput).contains("PUBLIC");

        // Address lines
        assertThat(textOutput).contains("123 MAIN ST");
        assertThat(textOutput).contains("APT 4B");

        // Account identifier
        assertThat(textOutput).contains("00000000001");

        // Bank name (from CBSTM03A constants)
        assertThat(textOutput).contains(StatementEngineService.BANK_NAME);
    }

    // =========================================================================
    // Test 6: HTML Statement Output (← HTML-L01 through HTML-L80)
    // =========================================================================

    /**
     * Verifies HTML statement output contains proper tags and styled elements,
     * translating CBSTM03A.CBL HTML-L01 through HTML-L80.
     */
    @Test
    @DisplayName("Should generate HTML statement with proper tags and styled content")
    void shouldGenerateHtmlStatement() throws IOException {
        // Given: Customer, Account, and CardXref
        Customer customer = createTestCustomer();
        Account account = createTestAccount();
        CardXref xref = createTestXref();

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();
        try (BufferedWriter textWriter = new BufferedWriter(textSw);
             BufferedWriter htmlWriter = new BufferedWriter(htmlSw)) {

            // When: createStatement generates HTML output
            statementEngineService.createStatement(customer, account, xref,
                    textWriter, htmlWriter);
            htmlWriter.flush();
        }

        String htmlOutput = htmlSw.toString();

        // Then: HTML output has structure and styling
        assertThat(htmlOutput).contains("<table");
        assertThat(htmlOutput).contains("<tr");
        assertThat(htmlOutput).contains("<td");

        // Customer name appears in HTML
        assertThat(htmlOutput).contains("JOHN");
        assertThat(htmlOutput).contains("PUBLIC");

        // Address data in HTML
        assertThat(htmlOutput).contains("123 MAIN ST");

        // Account ID in HTML
        assertThat(htmlOutput).contains("00000000001");
    }

    // =========================================================================
    // Test 7: BigDecimal Total Accumulation (← WS-TOTAL-AMT)
    // =========================================================================

    /**
     * Verifies transaction total uses BigDecimal (not floating-point),
     * preserving COBOL COMP-3 packed decimal semantics.
     *
     * <p>COBOL: {@code ADD WS-TRNX-AMT TO WS-TOTAL-AMT} — exact decimal accumulation.</p>
     */
    @Test
    @DisplayName("Should accumulate transaction total using BigDecimal with RoundingMode.HALF_UP")
    void shouldAccumulateTransactionTotalWithBigDecimal() throws IOException {
        // Given: 3 transactions with precise decimal amounts
        String cardNum = "4111111111111111";
        Transaction txn1 = createTestTransactionForCard("TXN001",
                new BigDecimal("100.50"), cardNum);
        Transaction txn2 = createTestTransactionForCard("TXN002",
                new BigDecimal("200.75"), cardNum);
        Transaction txn3 = createTestTransactionForCard("TXN003",
                new BigDecimal("50.25"), cardNum);

        Map<String, List<Transaction>> trnxTable = Map.of(
                cardNum, List.of(txn1, txn2, txn3)
        );

        CardXref xref = createTestXref();

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();
        try (BufferedWriter textWriter = new BufferedWriter(textSw);
             BufferedWriter htmlWriter = new BufferedWriter(htmlSw)) {

            // When: writeTransactions accumulates the total
            BigDecimal total = statementEngineService.writeTransactions(
                    xref, trnxTable, textWriter, htmlWriter);
            textWriter.flush();
            htmlWriter.flush();

            // Then: Total = 100.50 + 200.75 + 50.25 = 351.50 (exact BigDecimal)
            assertThat(total).isEqualByComparingTo(new BigDecimal("351.50"));

            // Verify no floating-point imprecision: the scale must be exact
            assertThat(total.setScale(2, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(new BigDecimal("351.50"));
        }
    }

    // =========================================================================
    // Test 8: Sequential XREF Iteration (← 1000-MAINLINE)
    // =========================================================================

    /**
     * Verifies the main loop iterates all XREF records and generates one
     * statement per card, translating CBSTM03A.CBL paragraph 1000-MAINLINE.
     *
     * <p>COBOL pattern:</p>
     * <pre>{@code
     * PERFORM 1000-XREFFILE-GET-NEXT
     * PERFORM UNTIL WS-FL-XREFEOF
     *     PERFORM 2000-CUSTFILE-GET
     *     PERFORM 3000-ACCTFILE-GET
     *     PERFORM 5000-CREATE-STATEMENT
     *     PERFORM 1000-XREFFILE-GET-NEXT
     * END-PERFORM
     * }</pre>
     */
    @Test
    @DisplayName("Should iterate XREFs sequentially and generate one statement per card")
    void shouldIterateXrefsAndGenerateStatements(@TempDir Path tempDir) throws IOException {
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

        // Stub repository returns
        when(cardXrefRepository.findAll()).thenReturn(List.of(xref1, xref2));
        when(customerRepository.findById("000000001")).thenReturn(Optional.of(cust1));
        when(customerRepository.findById("000000002")).thenReturn(Optional.of(cust2));
        when(accountRepository.findById("00000000001")).thenReturn(Optional.of(acct1));
        when(accountRepository.findById("00000000002")).thenReturn(Optional.of(acct2));

        // Transactions for both cards
        List<Transaction> allTxns = List.of(
                createTestTransactionForCard("TXN001", new BigDecimal("100.00"),
                        "4111111111111111"),
                createTestTransactionForCard("TXN002", new BigDecimal("250.00"),
                        "4222222222222222")
        );
        when(transactionRepository.findAllByOrderByTranIdAsc()).thenReturn(allTxns);

        // When: generateStatements processes all XREFs
        String outputDir = tempDir.toString();
        statementEngineService.generateStatements(outputDir);

        // Then: Both customers were looked up
        verify(customerRepository, times(1)).findById("000000001");
        verify(customerRepository, times(1)).findById("000000002");

        // Both accounts were looked up
        verify(accountRepository, times(1)).findById("00000000001");
        verify(accountRepository, times(1)).findById("00000000002");

        // Output files were created
        Path textFile = tempDir.resolve("statements.txt");
        Path htmlFile = tempDir.resolve("statements.html");
        assertThat(textFile).exists();
        assertThat(htmlFile).exists();

        // Both customer names appear in the text output
        String textContent = Files.readString(textFile);
        assertThat(textContent).contains("JOHN");
        assertThat(textContent).contains("JANE");
    }

    // =========================================================================
    // Test 9: Transaction Writing per Card (← 4000-TRNXFILE-GET / 6000-WRITE-TRANS)
    // =========================================================================

    /**
     * Verifies that only transactions matching the current card's number are
     * written to the statement, translating paragraphs 4000-TRNXFILE-GET
     * and 6000-WRITE-TRANS from CBSTM03A.CBL.
     */
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

        Map<String, List<Transaction>> trnxTable = Map.of(
                targetCard, List.of(matchTxn1, matchTxn2),
                otherCard, List.of(noMatchTxn)
        );

        CardXref xref = createTestXref(); // card "4111111111111111"

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();
        try (BufferedWriter textWriter = new BufferedWriter(textSw);
             BufferedWriter htmlWriter = new BufferedWriter(htmlSw)) {

            // When: writeTransactions processes only matching card
            BigDecimal total = statementEngineService.writeTransactions(
                    xref, trnxTable, textWriter, htmlWriter);
            textWriter.flush();
            htmlWriter.flush();

            // Then: Total is sum of matching transactions only (100 + 200 = 300)
            assertThat(total).isEqualByComparingTo(new BigDecimal("300.00"));

            // Matching transaction IDs appear in output
            String textOutput = textSw.toString();
            assertThat(textOutput).contains("TXN001");
            assertThat(textOutput).contains("TXN002");

            // Non-matching transaction must NOT appear
            assertThat(textOutput).doesNotContain("999.99");
        }
    }

    // =========================================================================
    // Test 10: Edge Case — Account with No Transactions
    // =========================================================================

    /**
     * Verifies graceful handling when a card has no transactions in the table.
     * The statement should be generated with zero total and an informational notice.
     */
    @Test
    @DisplayName("Should handle account with no transactions gracefully")
    void shouldHandleAccountWithNoTransactions() throws IOException {
        // Given: Empty transaction table for the target card
        CardXref xref = createTestXref();
        Map<String, List<Transaction>> emptyTable = Collections.emptyMap();

        StringWriter textSw = new StringWriter();
        StringWriter htmlSw = new StringWriter();
        try (BufferedWriter textWriter = new BufferedWriter(textSw);
             BufferedWriter htmlWriter = new BufferedWriter(htmlSw)) {

            // When: writeTransactions is called with no matching transactions
            BigDecimal total = statementEngineService.writeTransactions(
                    xref, emptyTable, textWriter, htmlWriter);
            textWriter.flush();
            htmlWriter.flush();

            // Then: Total is zero
            assertThat(total).isEqualByComparingTo(BigDecimal.ZERO);

            // Output contains informational notice
            String textOutput = textSw.toString();
            assertThat(textOutput).contains("No transactions for this period");
        }
    }

    // =========================================================================
    // Test 11: Edge Case — Missing Customer Record
    // =========================================================================

    /**
     * Verifies graceful handling when customer lookup returns empty Optional.
     * The service should handle this case without throwing an exception.
     *
     * <p>COBOL equivalent: CUSTFILE-STATUS = '23' (record not found).</p>
     */
    @Test
    @DisplayName("Should handle missing customer record gracefully")
    void shouldHandleMissingCustomer() {
        // Given: Customer does not exist
        when(customerRepository.findById(anyString())).thenReturn(Optional.empty());

        // When: getCustomer is called
        Optional<Customer> result = statementEngineService.getCustomer("999999999");

        // Then: Returns empty Optional without exception
        assertThat(result).isEmpty();
    }
}

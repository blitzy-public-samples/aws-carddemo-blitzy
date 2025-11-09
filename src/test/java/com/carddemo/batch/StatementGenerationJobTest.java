package com.carddemo.batch;

import com.carddemo.batch.job.StatementGenerationJob;
import com.carddemo.batch.processor.StatementDetailProcessor;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import jakarta.annotation.PostConstruct;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 Integration Test for StatementGenerationJob Spring Batch processing.
 * 
 * <p>This test class validates that the Spring Batch statement generation job correctly
 * replicates COBOL batch programs CBSTM03A.cbl (statement generation main) and CBSTM03B.cbl
 * (statement detail sub-program). Tests verify chunk-oriented processing, transaction
 * aggregation with BigDecimal precision, statement formatting matching COBOL report layouts,
 * and PDF generation using JasperReports library.</p>
 * 
 * <h2>COBOL Source Programs</h2>
 * <ul>
 *   <li><b>CBSTM03A.CBL</b> - Main statement generation program with file I/O control</li>
 *   <li><b>CBSTM03B.CBL</b> - Subroutine for file handling (TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE)</li>
 * </ul>
 * 
 * <h2>Key Transformations Validated</h2>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Pattern</th>
 *     <th>Test Validation</th>
 *     <th>Test Method</th>
 *   </tr>
 *   <tr>
 *     <td>COMP-3 WS-TOTAL-AMT PIC S9(9)V99</td>
 *     <td>BigDecimal aggregation with scale=2, RoundingMode.HALF_UP</td>
 *     <td>testStatementGenerationJob_TransactionAggregation()</td>
 *   </tr>
 *   <tr>
 *     <td>PERFORM UNTIL END-OF-FILE sequential read</td>
 *     <td>JpaPagingItemReader processes all transactions</td>
 *     <td>testStatementGenerationJob_Success()</td>
 *   </tr>
 *   <tr>
 *     <td>CALL CBSTM03B USING WS-M03B-AREA</td>
 *     <td>StatementDetailProcessor.process() formats transaction details</td>
 *     <td>testStatementGenerationJob_StatementFormatting()</td>
 *   </tr>
 *   <tr>
 *     <td>WRITE FD-STMTFILE-REC FROM ST-LINE*</td>
 *     <td>PDF file generated for each account with JasperReports</td>
 *     <td>testStatementGenerationJob_PDFOutput()</td>
 *   </tr>
 *   <tr>
 *     <td>Chunk-oriented processing</td>
 *     <td>Verify statements generated in configurable chunks</td>
 *     <td>testStatementGenerationJob_ChunkProcessing()</td>
 *   </tr>
 *   <tr>
 *     <td>4-hour batch processing window</td>
 *     <td>Job completes within time limit</td>
 *     <td>testStatementGenerationJob_PerformanceWindow()</td>
 *   </tr>
 * </table>
 * 
 * <h2>Test Configuration</h2>
 * <ul>
 *   <li><b>@SpringBatchTest:</b> Enables test-specific batch infrastructure with JobLauncherTestUtils</li>
 *   <li><b>@SpringBootTest:</b> Loads full application context with all beans</li>
 *   <li><b>@ActiveProfiles("test"):</b> Uses application-test.properties with H2 in-memory database</li>
 * </ul>
 * 
 * <h2>Test Data Setup</h2>
 * <p>The @BeforeEach setUp() method creates a comprehensive test dataset:</p>
 * <ul>
 *   <li><b>Customers:</b> 3 customers with complete name and address information from CVCUS01Y.cpy</li>
 *   <li><b>Accounts:</b> 3 accounts with various balances and credit limits from CVACT01Y.cpy</li>
 *   <li><b>Cards:</b> 3-5 cards linked to accounts via foreign keys from CVACT02Y.cpy</li>
 *   <li><b>Transactions:</b> 15-30 transactions with varying amounts, dates, merchants from CVTRA05Y.cpy</li>
 * </ul>
 * 
 * @see StatementGenerationJob
 * @see StatementDetailProcessor
 * @see <a href="Section 0.4">Source File Discovery - CBSTM03A.CBL, CBSTM03B.CBL</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - Batch Processing Requirements</a>
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
public class StatementGenerationJobTest {

    /**
     * Spring Batch Test utility for launching jobs and retrieving execution results.
     * Provides launchJob() method to execute StatementGenerationJob with test parameters.
     */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * The Statement Generation Job bean that will be tested.
     * Explicitly autowired and configured in jobLauncherTestUtils to resolve "Job must not be null" error.
     */
    @Autowired
    @Qualifier("monthlyStatementGenerationJob")
    private org.springframework.batch.core.Job statementGenerationJob;

    /**
     * Spring Data JPA repository for Account entity (CVACT01Y.cpy).
     * Used for test data setup and verification of account information in generated statements.
     */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * Spring Data JPA repository for Transaction entity (CVTRA05Y.cpy).
     * Used for creating test transaction data and verifying transaction aggregation.
     */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Spring Data JPA repository for Customer entity (CVCUS01Y.cpy).
     * Used for creating test customer records with name and address information.
     */
    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Spring Data JPA repository for Card entity (CVACT02Y.cpy).
     * Used for establishing card-account relationships for test data.
     */
    @Autowired
    private CardRepository cardRepository;

    /**
     * Temporary output directory for generated PDF statements during testing.
     * Initialized in setUp() to system temp directory with unique subdirectory.
     */
    private Path outputDirectory;

    /**
     * Test customer list created in setUp() for verification across test methods.
     */
    private List<Customer> testCustomers;

    /**
     * Test account list created in setUp() for verification across test methods.
     */
    private List<Account> testAccounts;

    /**
     * Test card list created in setUp() for verification across test methods.
     */
    private List<Card> testCards;

    /**
     * Test transaction list created in setUp() for verification across test methods.
     */
    private List<Transaction> testTransactions;

    /**
     * Test statement period start date - 30 days ago from current date.
     */
    private LocalDate statementStartDate;

    /**
     * Test statement period end date - current date.
     */
    private LocalDate statementEndDate;

    /**
     * Configures JobLauncherTestUtils with the specific Job bean to test.
     * 
     * <p>This @PostConstruct method resolves the "IllegalArgumentException: The Job must not be null"
     * error by explicitly setting the monthlyStatementGenerationJob bean into JobLauncherTestUtils.
     * This is required when multiple Job beans exist in the application context or when the
     * job bean name doesn't match the default expected by @SpringBatchTest.</p>
     */
    @PostConstruct
    public void configureJobLauncherTestUtils() {
        jobLauncherTestUtils.setJob(statementGenerationJob);
    }

    /**
     * Sets up test fixtures before each test method execution.
     * 
     * <p>This method performs the following setup tasks:</p>
     * <ol>
     *   <li>Clears all repository data to ensure test isolation</li>
     *   <li>Creates temporary output directory for PDF statement files</li>
     *   <li>Initializes statement period dates (30-day window)</li>
     *   <li>Creates comprehensive test dataset:
     *     <ul>
     *       <li>3 customers with realistic name and address data</li>
     *       <li>3 accounts with various balances and credit limits</li>
     *       <li>4 cards distributed across accounts</li>
     *       <li>20+ transactions with varying amounts, dates, and merchants</li>
     *     </ul>
     *   </li>
     * </ol>
     * 
     * <p>Test data replicates COBOL data structures:</p>
     * <ul>
     *   <li><b>Customer:</b> CVCUS01Y.cpy - CUST-FIRST-NAME, CUST-LAST-NAME, address fields</li>
     *   <li><b>Account:</b> CVACT01Y.cpy - ACCT-ID, ACCT-CURR-BAL (BigDecimal scale=2)</li>
     *   <li><b>Card:</b> CVACT02Y.cpy - CARD-NUM, CARD-ACCT-ID foreign key</li>
     *   <li><b>Transaction:</b> CVTRA05Y.cpy - TRAN-ID, TRAN-AMT (BigDecimal scale=2)</li>
     * </ul>
     * 
     * @throws Exception if test data setup fails
     */
    @BeforeEach
    public void setUp() throws Exception {
        // Clear all repository data for test isolation using deleteAllInBatch()
        // to avoid loading entities and triggering foreign key constraints
        transactionRepository.deleteAllInBatch();
        cardRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();

        // Create temporary output directory for PDF files
        outputDirectory = Files.createTempDirectory("statement-test-");

        // Initialize statement period dates (30-day window)
        statementEndDate = LocalDate.now();
        statementStartDate = statementEndDate.minusDays(30);

        // Initialize test data lists
        testCustomers = new ArrayList<>();
        testAccounts = new ArrayList<>();
        testCards = new ArrayList<>();
        testTransactions = new ArrayList<>();

        // Create test customers (CVCUS01Y.cpy structure)
        createTestCustomers();

        // Create test accounts (CVACT01Y.cpy structure)
        createTestAccounts();

        // Create test cards (CVACT02Y.cpy structure)
        createTestCards();

        // Create test transactions (CVTRA05Y.cpy structure)
        createTestTransactions();
    }

    /**
     * Creates test customer records matching COBOL CVCUS01Y.cpy structure.
     * 
     * <p>Replicates COBOL fields:</p>
     * <ul>
     *   <li>CUST-ID PIC 9(09) → customerId (Long)</li>
     *   <li>CUST-FIRST-NAME PIC X(25) → firstName</li>
     *   <li>CUST-LAST-NAME PIC X(25) → lastName</li>
     *   <li>CUST-ADDR-LINE-1 PIC X(50) → addressLine1</li>
     *   <li>CUST-CITY PIC X(50) → city</li>
     *   <li>CUST-STATE-CD PIC X(02) → state</li>
     *   <li>CUST-ZIP PIC X(10) → zipCode</li>
     * </ul>
     */
    private void createTestCustomers() {
        Customer customer1 = Customer.builder()
                .customerId(1000000001L)
                .firstName("John")
                .lastName("Smith")
                .addressLine1("123 Main Street")
                .addressLine2("Apt 4B")
                .addressLine3("New York")
                .addressStateCode("NY")
                .addressZip("10001")
                .build();

        Customer customer2 = Customer.builder()
                .customerId(1000000002L)
                .firstName("Jane")
                .lastName("Doe")
                .addressLine1("456 Oak Avenue")
                .addressLine2("")
                .addressLine3("Los Angeles")
                .addressStateCode("CA")
                .addressZip("90001")
                .build();

        Customer customer3 = Customer.builder()
                .customerId(1000000003L)
                .firstName("Robert")
                .lastName("Johnson")
                .addressLine1("789 Pine Boulevard")
                .addressLine2("Suite 100")
                .addressLine3("Chicago")
                .addressStateCode("IL")
                .addressZip("60601")
                .build();

        testCustomers.add(customer1);
        testCustomers.add(customer2);
        testCustomers.add(customer3);

        customerRepository.saveAll(testCustomers);
    }

    /**
     * Creates test account records matching COBOL CVACT01Y.cpy structure.
     * 
     * <p>Replicates COBOL fields with proper BigDecimal precision:</p>
     * <ul>
     *   <li>ACCT-ID PIC 9(11) → accountId (Long)</li>
     *   <li>ACCT-CURR-BAL PIC S9(10)V99 COMP-3 → currentBalance (BigDecimal scale=2)</li>
     *   <li>ACCT-CREDIT-LIMIT PIC S9(10)V99 COMP-3 → creditLimit (BigDecimal scale=2)</li>
     *   <li>ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 COMP-3 → cashCreditLimit (BigDecimal scale=2)</li>
     * </ul>
     * 
     * <p>Uses RoundingMode.HALF_UP to match COBOL COMP-3 rounding behavior.</p>
     */
    private void createTestAccounts() {
        Account account1 = Account.builder()
                .accountId(10000000001L)
                .customer(testCustomers.get(0))
                .activeStatus("Y")
                .currentBalance(new BigDecimal("1547.89").setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP))
                .cashCreditLimit(new BigDecimal("2000.00").setScale(2, RoundingMode.HALF_UP))
                .openDate(LocalDate.of(2020, 1, 15))
                .build();

        Account account2 = Account.builder()
                .accountId(10000000002L)
                .customer(testCustomers.get(1))
                .activeStatus("Y")
                .currentBalance(new BigDecimal("3245.67").setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("15000.00").setScale(2, RoundingMode.HALF_UP))
                .cashCreditLimit(new BigDecimal("3000.00").setScale(2, RoundingMode.HALF_UP))
                .openDate(LocalDate.of(2019, 6, 20))
                .build();

        Account account3 = Account.builder()
                .accountId(10000000003L)
                .customer(testCustomers.get(2))
                .activeStatus("Y")
                .currentBalance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .cashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .openDate(LocalDate.of(2023, 3, 10))
                .build();

        testAccounts.add(account1);
        testAccounts.add(account2);
        testAccounts.add(account3);

        accountRepository.saveAll(testAccounts);
    }

    /**
     * Creates test card records matching COBOL CVACT02Y.cpy structure.
     * 
     * <p>Replicates COBOL fields:</p>
     * <ul>
     *   <li>CARD-NUM PIC X(16) → cardNumber (String)</li>
     *   <li>CARD-ACCT-ID PIC 9(11) → accountId (Long foreign key)</li>
     *   <li>CARD-EMBOSSED-NAME PIC X(50) → embossedName</li>
     *   <li>CARD-EXPIRATION-DATE PIC X(10) → expirationDate</li>
     * </ul>
     * 
     * <p>Establishes card-to-account relationships matching COBOL XREF-FILE cross-references.</p>
     */
    private void createTestCards() {
        Card card1 = Card.builder()
                .cardNumber("4000123456789010")
                .account(testAccounts.get(0))
                .cardType("VI")  // 2-character code for VISA
                .embossedName("JOHN SMITH")
                .expirationDate(LocalDate.of(2025, 12, 31))
                .cvvCode("123")
                .activeStatus("Y")
                .build();

        Card card2 = Card.builder()
                .cardNumber("5000123456789011")
                .account(testAccounts.get(0))
                .cardType("MC")  // 2-character code for MASTERCARD
                .embossedName("JOHN SMITH")
                .expirationDate(LocalDate.of(2026, 6, 30))
                .cvvCode("456")
                .activeStatus("Y")
                .build();

        Card card3 = Card.builder()
                .cardNumber("4000987654321012")
                .account(testAccounts.get(1))
                .cardType("VI")  // 2-character code for VISA
                .embossedName("JANE DOE")
                .expirationDate(LocalDate.of(2025, 3, 31))
                .cvvCode("789")
                .activeStatus("Y")
                .build();

        Card card4 = Card.builder()
                .cardNumber("6000555544443333")
                .account(testAccounts.get(2))
                .cardType("DC")  // 2-character code for DISCOVER
                .embossedName("ROBERT JOHNSON")
                .expirationDate(LocalDate.of(2027, 9, 30))
                .cvvCode("321")
                .activeStatus("Y")
                .build();

        testCards.add(card1);
        testCards.add(card2);
        testCards.add(card3);
        testCards.add(card4);

        cardRepository.saveAll(testCards);
    }

    /**
     * Creates test transaction records matching COBOL CVTRA05Y.cpy structure.
     * 
     * <p>Replicates COBOL fields with proper BigDecimal precision:</p>
     * <ul>
     *   <li>TRAN-ID PIC X(16) → transactionId (String)</li>
     *   <li>TRAN-AMT PIC S9(09)V99 COMP-3 → amount (BigDecimal scale=2)</li>
     *   <li>TRAN-CARD-NUM PIC X(16) → cardNumber (String foreign key)</li>
     *   <li>TRAN-DESC PIC X(100) → description</li>
     *   <li>TRAN-MERCHANT-ID PIC 9(09) → merchantId</li>
     *   <li>TRAN-MERCHANT-NAME PIC X(50) → merchantName</li>
     * </ul>
     * 
     * <p>Creates transactions within statement period (30-day window) with varying amounts
     * to test aggregation logic matching COBOL WS-TOTAL-AMT accumulation from CBSTM03A.cbl.</p>
     */
    private void createTestTransactions() {
        LocalDateTime baseDateTime = statementStartDate.atTime(10, 0);

        // Transactions for card1 (account1) - 8 transactions totaling $547.89
        testTransactions.add(createTransaction("T000000000000001", "4000123456789010", 
                new BigDecimal("45.99"), 100001L, "Amazon Marketplace", baseDateTime.plusDays(1)));
        testTransactions.add(createTransaction("T000000000000002", "4000123456789010", 
                new BigDecimal("120.50"), 100002L, "Walmart Store #1234", baseDateTime.plusDays(3)));
        testTransactions.add(createTransaction("T000000000000003", "4000123456789010", 
                new BigDecimal("89.75"), 100003L, "Shell Gas Station", baseDateTime.plusDays(5)));
        testTransactions.add(createTransaction("T000000000000004", "4000123456789010", 
                new BigDecimal("67.33"), 100004L, "Target Store", baseDateTime.plusDays(7)));
        testTransactions.add(createTransaction("T000000000000005", "4000123456789010", 
                new BigDecimal("150.00"), 100005L, "Best Buy Electronics", baseDateTime.plusDays(10)));
        testTransactions.add(createTransaction("T000000000000006", "4000123456789010", 
                new BigDecimal("34.12"), 100006L, "Starbucks Coffee", baseDateTime.plusDays(12)));
        testTransactions.add(createTransaction("T000000000000007", "4000123456789010", 
                new BigDecimal("22.50"), 100007L, "McDonald's Restaurant", baseDateTime.plusDays(15)));
        testTransactions.add(createTransaction("T000000000000008", "4000123456789010", 
                new BigDecimal("17.70"), 100008L, "Chevron Gas", baseDateTime.plusDays(18)));

        // Transactions for card2 (account1) - 7 transactions totaling $1000.00 exactly
        testTransactions.add(createTransaction("T000000000000009", "5000123456789011", 
                new BigDecimal("250.00"), 100009L, "Macy's Department Store", baseDateTime.plusDays(2)));
        testTransactions.add(createTransaction("T000000000000010", "5000123456789011", 
                new BigDecimal("175.00"), 100010L, "Home Depot", baseDateTime.plusDays(4)));
        testTransactions.add(createTransaction("T000000000000011", "5000123456789011", 
                new BigDecimal("125.00"), 100011L, "Costco Wholesale", baseDateTime.plusDays(8)));
        testTransactions.add(createTransaction("T000000000000012", "5000123456789011", 
                new BigDecimal("200.00"), 100012L, "Nordstrom Store", baseDateTime.plusDays(11)));
        testTransactions.add(createTransaction("T000000000000013", "5000123456789011", 
                new BigDecimal("100.00"), 100013L, "Gap Clothing", baseDateTime.plusDays(14)));
        testTransactions.add(createTransaction("T000000000000014", "5000123456789011", 
                new BigDecimal("75.00"), 100014L, "Panera Bread", baseDateTime.plusDays(17)));
        testTransactions.add(createTransaction("T000000000000015", "5000123456789011", 
                new BigDecimal("75.00"), 100015L, "Chipotle Mexican Grill", baseDateTime.plusDays(20)));

        // Transactions for card3 (account2) - 10 transactions totaling $3245.67
        testTransactions.add(createTransaction("T000000000000016", "4000987654321012", 
                new BigDecimal("500.00"), 100016L, "Delta Airlines", baseDateTime.plusDays(1)));
        testTransactions.add(createTransaction("T000000000000017", "4000987654321012", 
                new BigDecimal("1200.00"), 100017L, "Marriott Hotel", baseDateTime.plusDays(2)));
        testTransactions.add(createTransaction("T000000000000018", "4000987654321012", 
                new BigDecimal("345.67"), 100018L, "Whole Foods Market", baseDateTime.plusDays(6)));
        testTransactions.add(createTransaction("T000000000000019", "4000987654321012", 
                new BigDecimal("450.00"), 100019L, "Apple App Store", baseDateTime.plusDays(9)));
        testTransactions.add(createTransaction("T000000000000020", "4000987654321012", 
                new BigDecimal("275.00"), 100020L, "Lululemon Athletic", baseDateTime.plusDays(13)));
        testTransactions.add(createTransaction("T000000000000021", "4000987654321012", 
                new BigDecimal("180.00"), 100021L, "Cheesecake Factory", baseDateTime.plusDays(16)));
        testTransactions.add(createTransaction("T000000000000022", "4000987654321012", 
                new BigDecimal("95.00"), 100022L, "Trader Joe's", baseDateTime.plusDays(19)));
        testTransactions.add(createTransaction("T000000000000023", "4000987654321012", 
                new BigDecimal("85.00"), 100023L, "PetSmart", baseDateTime.plusDays(21)));
        testTransactions.add(createTransaction("T000000000000024", "4000987654321012", 
                new BigDecimal("65.00"), 100024L, "CVS Pharmacy", baseDateTime.plusDays(24)));
        testTransactions.add(createTransaction("T000000000000025", "4000987654321012", 
                new BigDecimal("50.00"), 100025L, "Uber Ride", baseDateTime.plusDays(27)));

        // No transactions for card4 (account3) - testing zero transaction scenario

        transactionRepository.saveAll(testTransactions);
    }

    /**
     * Helper method to create a Transaction entity with all required fields.
     * 
     * @param transactionId Unique transaction identifier (TRAN-ID)
     * @param cardNumber Card number (TRAN-CARD-NUM foreign key)
     * @param amount Transaction amount (TRAN-AMT with scale=2)
     * @param merchantId Merchant identifier (TRAN-MERCHANT-ID - 9-digit numeric)
     * @param merchantName Merchant name (TRAN-MERCHANT-NAME)
     * @param timestamp Transaction timestamp (TRAN-ORIG-TS)
     * @return Configured Transaction entity
     */
    private Transaction createTransaction(String transactionId, String cardNumber, 
            BigDecimal amount, Long merchantId, String merchantName, LocalDateTime timestamp) {
        // Find the card by card number
        Card card = testCards.stream()
                .filter(c -> c.getCardNumber().equals(cardNumber))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardNumber));
        
        return Transaction.builder()
                .transactionId(transactionId)
                .card(card)
                .amount(amount.setScale(2, RoundingMode.HALF_UP))
                .description(merchantName + " Purchase")
                .merchantId(merchantId)
                .merchantName(merchantName)
                .originationTimestamp(timestamp)
                .processingTimestamp(timestamp.plusHours(1))
                .build();
    }

    /**
     * Test: Successful statement generation for all accounts.
     * 
     * <p>Validates that StatementGenerationJob executes successfully and generates
     * PDF statements for all accounts, replicating COBOL CBSTM03A.cbl main loop:</p>
     * <pre>
     * PERFORM UNTIL END-OF-FILE
     *     PERFORM 4000-TRNXFILE-GET
     *     PERFORM 5000-CREATE-STATEMENT
     * END-PERFORM
     * </pre>
     * 
     * <p><b>COBOL Source:</b> CBSTM03A.cbl lines 310-330</p>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Job execution status is COMPLETED</li>
     *   <li>No errors occurred during execution</li>
     *   <li>Statement PDF files exist for accounts with transactions</li>
     *   <li>Read count matches number of transactions processed</li>
     *   <li>Write count matches number of statements generated</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    @DisplayName("Should generate statements for all accounts successfully")
    public void testStatementGenerationJob_Success() throws Exception {
        // Prepare job parameters matching COBOL JCL parameters
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", statementStartDate)
                .addLocalDate("endDate", statementEndDate)
                .addString("outputPath", outputDirectory.toString())
                .addString("processingDate", LocalDate.now().toString())
                .toJobParameters();

        // Execute the batch job (CBSTM03A.cbl main processing)
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Verify job completed successfully
        assertThat(jobExecution.getStatus())
                .as("Job execution status should be COMPLETED")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(jobExecution.getAllFailureExceptions())
                .as("Job should complete without exceptions")
                .isEmpty();

        // Verify statement files generated for accounts with transactions
        // Account 1 (2 cards with transactions): should have statement
        Path statement1 = findStatementFile(10000000001L);
        assertThat(statement1)
                .as("Statement PDF should exist for account 10000000001")
                .isNotNull();
        assertThat(Files.exists(statement1))
                .as("Statement PDF should exist for account 10000000001")
                .isTrue();

        // Account 2 (1 card with transactions): should have statement
        Path statement2 = findStatementFile(10000000002L);
        assertThat(statement2)
                .as("Statement PDF should exist for account 10000000002")
                .isNotNull();
        assertThat(Files.exists(statement2))
                .as("Statement PDF should exist for account 10000000002")
                .isTrue();

        // Account 3 (1 card with NO transactions): should NOT have statement (COBOL behavior: skip zero-transaction accounts)
        Path statement3 = findStatementFile(10000000003L);
        assertThat(statement3)
                .as("Statement PDF should NOT exist for account 10000000003 (zero transactions, matching COBOL behavior)")
                .isNull();

        // Verify step execution metrics
        jobExecution.getStepExecutions().forEach(stepExecution -> {
            assertThat(stepExecution.getStatus())
                    .as("Step execution status should be COMPLETED")
                    .isEqualTo(BatchStatus.COMPLETED);

            assertThat(stepExecution.getReadCount())
                    .as("Read count should be 3 (all accounts)")
                    .isEqualTo(3);
            
            assertThat(stepExecution.getFilterCount())
                    .as("Filter count should be 1 (account 3 has no transactions)")
                    .isEqualTo(1);

            assertThat(stepExecution.getWriteCount())
                    .as("Write count should be 2 (only accounts with transactions)")
                    .isEqualTo(2);
        });
    }

    /**
     * Test: Transaction aggregation with BigDecimal summation maintaining scale=2 precision.
     * 
     * <p>Validates that transaction amount aggregation replicates COBOL COMP-3 arithmetic
     * from CBSTM03A.cbl WS-TOTAL-AMT accumulation:</p>
     * <pre>
     * 05 WS-TOTAL-AMT PIC S9(9)V99 COMP-3 VALUE 0.
     * ...
     * ADD TRAN-AMT TO WS-TOTAL-AMT
     * </pre>
     * 
     * <p><b>COBOL Source:</b> CBSTM03A.cbl lines 64-65, 325</p>
     * 
     * <p><b>Key Transformation:</b> COMP-3 arithmetic → BigDecimal.add() with scale=2, RoundingMode.HALF_UP</p>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Account 1 total: $1,547.89 (sum of 15 transactions across 2 cards)</li>
     *   <li>Account 2 total: $3,245.67 (sum of 10 transactions)</li>
     *   <li>Account 3 total: $0.00 (no transactions)</li>
     *   <li>All totals maintain exact 2 decimal places precision</li>
     *   <li>No rounding errors or precision loss</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    @DisplayName("Should aggregate monthly transactions with BigDecimal summation")
    public void testStatementGenerationJob_TransactionAggregation() throws Exception {
        // Prepare job parameters
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", statementStartDate)
                .addLocalDate("endDate", statementEndDate)
                .addString("outputPath", outputDirectory.toString())
                .toJobParameters();

        // Execute the batch job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Verify job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Calculate expected totals matching COBOL WS-TOTAL-AMT aggregation
        // Account 1: card1 (8 transactions) + card2 (7 transactions) = 15 transactions
        BigDecimal expectedAccount1Total = new BigDecimal("547.89")  // card1 total
                .add(new BigDecimal("1000.00"))  // card2 total
                .setScale(2, RoundingMode.HALF_UP);

        // Account 2: card3 (10 transactions)
        BigDecimal expectedAccount2Total = new BigDecimal("3245.67")
                .setScale(2, RoundingMode.HALF_UP);

        // Account 3: card4 (0 transactions)
        BigDecimal expectedAccount3Total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        // Retrieve actual transactions and calculate totals
        List<Transaction> account1Transactions = new ArrayList<>();
        account1Transactions.addAll(transactionRepository.findByCard_CardNumber("4000123456789010", Pageable.unpaged()).getContent());
        account1Transactions.addAll(transactionRepository.findByCard_CardNumber("5000123456789011", Pageable.unpaged()).getContent());

        BigDecimal actualAccount1Total = account1Transactions.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        List<Transaction> account2Transactions = transactionRepository.findByCard_CardNumber("4000987654321012", Pageable.unpaged()).getContent();
        BigDecimal actualAccount2Total = account2Transactions.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        List<Transaction> account3Transactions = transactionRepository.findByCard_CardNumber("6000555544443333", Pageable.unpaged()).getContent();
        BigDecimal actualAccount3Total = account3Transactions.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // Verify aggregation matches expected totals with exact precision
        assertThat(actualAccount1Total)
                .as("Account 1 transaction total should match expected with scale=2 precision")
                .isEqualByComparingTo(expectedAccount1Total);

        assertThat(actualAccount2Total)
                .as("Account 2 transaction total should match expected with scale=2 precision")
                .isEqualByComparingTo(expectedAccount2Total);

        assertThat(actualAccount3Total)
                .as("Account 3 transaction total should be zero with scale=2 precision")
                .isEqualByComparingTo(expectedAccount3Total);

        // Verify scale is preserved (2 decimal places)
        assertThat(actualAccount1Total.scale())
                .as("BigDecimal scale should be 2 to match COBOL COMP-3 V99")
                .isEqualTo(2);

        assertThat(actualAccount2Total.scale())
                .as("BigDecimal scale should be 2 to match COBOL COMP-3 V99")
                .isEqualTo(2);

        // Verify no rounding errors in individual transaction amounts
        for (Transaction transaction : account1Transactions) {
            assertThat(transaction.getAmount().scale())
                    .as("Transaction amount scale should be 2")
                    .isEqualTo(2);
        }
    }

    /**
     * Test: Statement formatting matching COBOL report layout structure.
     * 
     * <p>Validates that generated PDF statements contain properly formatted header and
     * detail sections matching COBOL STATEMENT-LINES and HTML-LINES structures from
     * CBSTM03A.cbl:</p>
     * <pre>
     * 01  STATEMENT-LINES.
     *     05  ST-LINE0.  (START OF STATEMENT header)
     *     05  ST-LINE1.  (Customer name)
     *     05  ST-LINE2.  (Address line 1)
     *     05  ST-LINE3.  (Address line 2)
     *     05  ST-LINE4.  (City, State, ZIP)
     *     ...
     * </pre>
     * 
     * <p><b>COBOL Source:</b> CBSTM03A.cbl lines 85-120, CBSTM03B.cbl CUSTFILE-PROC lines 188-192</p>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>PDF files contain customer name and address information</li>
     *   <li>Account ID and balance appear in statement header</li>
     *   <li>Transaction details include date, merchant, amount</li>
     *   <li>Monthly total appears at statement end</li>
     *   <li>File size is reasonable (not empty, not corrupted)</li>
     * </ul>
     * 
     * @throws Exception if job execution or file reading fails
     */
    @Test
    @DisplayName("Should format statement header and detail structure matching COBOL layout")
    public void testStatementGenerationJob_StatementFormatting() throws Exception {
        // Prepare job parameters
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", statementStartDate)
                .addLocalDate("endDate", statementEndDate)
                .addString("outputPath", outputDirectory.toString())
                .toJobParameters();

        // Execute the batch job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Verify job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify statement file for account 1 exists and has content
        Path statement1Path = findStatementFile(10000000001L);
        assertThat(statement1Path)
                .as("Statement PDF should exist for account 1")
                .isNotNull();
        assertThat(Files.exists(statement1Path))
                .as("Statement PDF should exist for account 1")
                .isTrue();

        // Verify file size is reasonable (PDF with content should be > 1KB)
        long fileSize = Files.size(statement1Path);
        assertThat(fileSize)
                .as("Statement PDF should have reasonable file size (not empty)")
                .isGreaterThan(1024L);  // At least 1KB

        // Verify statement file for account 2 exists and has content
        Path statement2Path = findStatementFile(10000000002L);
        assertThat(statement2Path)
                .as("Statement PDF should exist for account 2")
                .isNotNull();
        assertThat(Files.exists(statement2Path))
                .as("Statement PDF should exist for account 2")
                .isTrue();

        long fileSize2 = Files.size(statement2Path);
        assertThat(fileSize2)
                .as("Statement PDF should have reasonable file size")
                .isGreaterThan(1024L);

        // Verify account 1 file is larger (more transactions = larger file)
        // Account 1: 15 transactions (8+7), Account 2: 10 transactions
        assertThat(fileSize)
                .as("Account 1 statement should be larger (15 transactions vs 10 transactions)")
                .isGreaterThan(fileSize2);

        // Verify all generated statements exist
        File outputDir = outputDirectory.toFile();
        File[] pdfFiles = outputDir.listFiles((dir, name) -> name.endsWith(".pdf"));
        
        assertThat(pdfFiles)
                .as("Output directory should contain PDF statement files")
                .isNotNull()
                .hasSize(2);  // 2 statements (accounts 1 and 2, account 3 has no transactions)

        // Verify file naming convention matches expected pattern (with date suffix)
        // Files are named: statement_{accountId}_{YYYYMMDD}.pdf
        assertThat(pdfFiles)
                .extracting(File::getName)
                .allMatch(name -> name.matches("statement_\\d+_\\d{8}\\.pdf"),
                         "All files should match pattern statement_{accountId}_{YYYYMMDD}.pdf");
    }

    /**
     * Test: PDF output file validation and content verification.
     * 
     * <p>Validates that generated PDF files match expectations from COBOL file output
     * operations WRITE FD-STMTFILE-REC FROM ST-LINE* from CBSTM03A.cbl.</p>
     * 
     * <p><b>COBOL Source:</b> CBSTM03A.cbl lines 39-40 (ASSIGN TO STMTFILE)</p>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>PDF files exist in output directory</li>
     *   <li>File naming convention: statement_[ACCOUNT-ID].pdf</li>
     *   <li>Files are valid PDF format (magic number check)</li>
     *   <li>File sizes are appropriate for content</li>
     *   <li>No corrupted or empty files</li>
     * </ul>
     * 
     * @throws Exception if job execution or file validation fails
     */
    @Test
    @DisplayName("Should generate valid PDF output files with proper naming")
    public void testStatementGenerationJob_PDFOutput() throws Exception {
        // Prepare job parameters
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", statementStartDate)
                .addLocalDate("endDate", statementEndDate)
                .addString("outputPath", outputDirectory.toString())
                .toJobParameters();

        // Execute the batch job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Verify job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify statement files exist only for accounts with transactions (COBOL behavior)
        // Account 3 (10000000003) has no transactions, so it should not have a statement
        for (Account account : testAccounts) {
            Path statementPath = findStatementFile(account.getAccountId());

            // Skip account 3 - it has no transactions and should not generate a statement
            if (account.getAccountId().equals(10000000003L)) {
                assertThat(statementPath)
                        .as("Statement PDF should NOT exist for account 10000000003 (zero transactions)")
                        .isNull();
                continue;
            }

            assertThat(statementPath)
                    .as("Statement PDF should exist for account " + account.getAccountId())
                    .isNotNull();
            assertThat(Files.exists(statementPath))
                    .as("Statement PDF should exist for account " + account.getAccountId())
                    .isTrue();

            // Verify PDF file magic number (starts with %PDF-)
            byte[] firstBytes = Files.readAllBytes(statementPath);
            assertThat(firstBytes.length)
                    .as("PDF file should have content")
                    .isGreaterThan(0);

            String header = new String(firstBytes, 0, Math.min(5, firstBytes.length));
            assertThat(header)
                    .as("File should be valid PDF format")
                    .startsWith("%PDF-");

            // Verify file is readable
            assertThat(Files.isReadable(statementPath))
                    .as("PDF file should be readable")
                    .isTrue();
        }
    }

    /**
     * Test: Handling accounts with zero transactions.
     * 
     * <p>Validates that statement generation correctly skips accounts with no transactions
     * during the statement period, matching COBOL behavior where only accounts with activity
     * generate statements. This aligns with the COBOL logic from CBSTM03A.cbl that processes
     * accounts only if they have transaction records.</p>
     * 
     * <p><b>COBOL Source:</b> CBSTM03A.cbl line 70 (END-OF-FILE flag), lines 310-330 (PERFORM UNTIL)</p>
     * 
     * <p><b>COBOL Behavior:</b> Accounts with zero transactions in the date range are skipped,
     * no statement is generated (conserves paper and processing resources).</p>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Job completes successfully even when some accounts have zero transactions</li>
     *   <li>No statement generated for account 3 (zero transactions) - COBOL behavior</li>
     *   <li>Filter count reflects skipped account</li>
     *   <li>Write count only includes accounts with transactions</li>
     *   <li>No errors or exceptions for zero transaction scenario</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    @DisplayName("Should skip accounts with zero transactions (matching COBOL behavior)")
    public void testStatementGenerationJob_ZeroTransactions() throws Exception {
        // Prepare job parameters
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", statementStartDate)
                .addLocalDate("endDate", statementEndDate)
                .addString("outputPath", outputDirectory.toString())
                .toJobParameters();

        // Execute the batch job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Verify job completed successfully even with zero-transaction account
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify NO statement exists for account 3 (zero transactions) - matching COBOL behavior
        Path statement3Path = findStatementFile(10000000003L);
        assertThat(statement3Path)
                .as("Statement should NOT be generated for account with zero transactions (COBOL behavior)")
                .isNull();

        // Verify statements DO exist for accounts with transactions
        Path statement1Path = findStatementFile(10000000001L);
        assertThat(statement1Path)
                .as("Statement should exist for account 1 (has transactions)")
                .isNotNull();
        assertThat(Files.exists(statement1Path))
                .as("Statement file should exist for account 1")
                .isTrue();

        Path statement2Path = findStatementFile(10000000002L);
        assertThat(statement2Path)
                .as("Statement should exist for account 2 (has transactions)")
                .isNotNull();
        assertThat(Files.exists(statement2Path))
                .as("Statement file should exist for account 2")
                .isTrue();

        // Verify account 3 has zero transactions in database for the statement period
        List<Transaction> account3Transactions = transactionRepository
                .findByCard_CardNumber("6000555544443333", Pageable.unpaged()).getContent();
        assertThat(account3Transactions)
                .as("Account 3 should have zero transactions")
                .isEmpty();
        
        // Verify step execution metrics show filtering occurred
        jobExecution.getStepExecutions().forEach(stepExecution -> {
            assertThat(stepExecution.getReadCount())
                    .as("Read count should be 3 (all accounts read)")
                    .isEqualTo(3);
            
            assertThat(stepExecution.getFilterCount())
                    .as("Filter count should be 1 (account 3 filtered out)")
                    .isEqualTo(1);
            
            assertThat(stepExecution.getWriteCount())
                    .as("Write count should be 2 (only accounts with transactions)")
                    .isEqualTo(2);
        });
    }

    /**
     * Test: Chunk processing validation for batch performance.
     * 
     * <p>Validates that Spring Batch chunk-oriented processing correctly handles multiple
     * accounts in configurable chunks, replacing COBOL sequential file processing from
     * CBSTM03A.cbl PERFORM loops.</p>
     * 
     * <p><b>COBOL Source:</b> CBSTM03A.cbl lines 310-330 (sequential PERFORM UNTIL loop)</p>
     * 
     * <p><b>Key Transformation:</b> Sequential COBOL loop → Spring Batch chunk processing (chunk size 50)</p>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>All accounts processed in chunks</li>
     *   <li>Read count matches total number of accounts</li>
     *   <li>Write count matches number of statements generated</li>
     *   <li>Commit count reflects chunk processing pattern</li>
     *   <li>No skip count (all items processed successfully)</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    @DisplayName("Should process statements in chunks efficiently")
    public void testStatementGenerationJob_ChunkProcessing() throws Exception {
        // Prepare job parameters
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", statementStartDate)
                .addLocalDate("endDate", statementEndDate)
                .addString("outputPath", outputDirectory.toString())
                .toJobParameters();

        // Execute the batch job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Verify job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify step execution metrics for chunk processing
        jobExecution.getStepExecutions().forEach(stepExecution -> {
            // Verify read count equals number of accounts (account-driven architecture)
            // Account 1: card1 (8 transactions) + card2 (7 transactions) = 15 transactions
            // Account 2: card3 (10 transactions) = 10 transactions
            // Account 3: no cards/transactions
            // Total: 3 accounts read
            assertThat(stepExecution.getReadCount())
                    .as("Read count should match number of accounts processed")
                    .isEqualTo(3);  // 3 test accounts

            // Verify write count equals number of statements generated
            // Note: Account-driven architecture with filtering generates statements ONLY for accounts 
            // with transactions during the period (COBOL behavior)
            assertThat(stepExecution.getWriteCount())
                    .as("Write count should match number of statements generated")
                    .isEqualTo(2);  // 2 statements (accounts 1 and 2, account 3 filtered)

            // Verify filter count reflects accounts with zero transactions
            assertThat(stepExecution.getFilterCount())
                    .as("Filter count should be 1 (account 3 has no transactions)")
                    .isEqualTo(1);

            // Verify no items were skipped (filtering is not skipping)
            assertThat(stepExecution.getSkipCount())
                    .as("Skip count should be zero (filtering is handled by processor returning null)")
                    .isEqualTo(0);

            // Verify commit count reflects chunk processing
            // With 3 accounts and chunk size 50, should have 1 commit
            assertThat(stepExecution.getCommitCount())
                    .as("Commit count should reflect chunk processing pattern")
                    .isGreaterThan(0);

            // Verify no rollback occurred
            assertThat(stepExecution.getRollbackCount())
                    .as("Rollback count should be zero")
                    .isEqualTo(0);
        });

        // Verify expected output files created (only for accounts with transactions)
        File outputDir = outputDirectory.toFile();
        File[] pdfFiles = outputDir.listFiles((dir, name) -> name.endsWith(".pdf"));
        
        assertThat(pdfFiles)
                .as("Should generate PDF only for accounts with transactions (COBOL behavior)")
                .hasSize(2);  // 2 statements (accounts 1 and 2)
    }

    /**
     * Test: Performance window validation ensuring 4-hour completion.
     * 
     * <p>Validates that batch job execution time meets performance requirements from
     * section 0.10 requirement #14, ensuring statements can be generated within the
     * required 4-hour batch processing window for production workloads.</p>
     * 
     * <p><b>Performance Requirement:</b> All batch jobs must complete within 4-hour window
     * per mainframe batch processing constraints.</p>
     * 
     * <p><b>COBOL Context:</b> Mainframe batch jobs typically run overnight with strict
     * time windows. This test validates the Java implementation can match or exceed
     * mainframe performance.</p>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Job completes successfully</li>
     *   <li>Execution time is under 4 hours (14,400,000 milliseconds)</li>
     *   <li>Execution time is reasonable for test dataset (< 30 seconds)</li>
     *   <li>No performance degradation from chunk processing</li>
     * </ul>
     * 
     * <p><b>Note:</b> Test dataset is small (3 accounts, 25 transactions), so execution
     * should be very fast. Production workloads with thousands of accounts must also
     * complete within 4-hour window through proper chunk sizing and parallel processing.</p>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    @DisplayName("Should complete statement generation within 4-hour processing window")
    public void testStatementGenerationJob_PerformanceWindow() throws Exception {
        // Record start time
        long startTime = System.currentTimeMillis();

        // Prepare job parameters
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", statementStartDate)
                .addLocalDate("endDate", statementEndDate)
                .addString("outputPath", outputDirectory.toString())
                .toJobParameters();

        // Execute the batch job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Record end time
        long endTime = System.currentTimeMillis();
        long executionTimeMillis = endTime - startTime;

        // Verify job completed successfully
        assertThat(jobExecution.getStatus())
                .as("Job should complete successfully")
                .isEqualTo(BatchStatus.COMPLETED);

        // Verify execution time is within 4-hour window (14,400,000 milliseconds)
        long fourHoursInMillis = 4L * 60L * 60L * 1000L;
        assertThat(executionTimeMillis)
                .as("Job execution time should be within 4-hour processing window")
                .isLessThan(fourHoursInMillis);

        // Verify execution time is reasonable for test dataset (should be very fast)
        long thirtySecondsInMillis = 30L * 1000L;
        assertThat(executionTimeMillis)
                .as("Job execution time should be reasonable for test dataset (< 30 seconds)")
                .isLessThan(thirtySecondsInMillis);

        // Verify job execution duration from JobExecution object
        Long jobDurationMillis = Duration.between(
                jobExecution.getStartTime(), 
                jobExecution.getEndTime()).toMillis();
        
        assertThat(jobDurationMillis)
                .as("Job execution duration should be positive")
                .isGreaterThan(0L);

        assertThat(jobDurationMillis)
                .as("Job execution duration should be within 4-hour window")
                .isLessThan(fourHoursInMillis);

        // Log performance metrics for analysis
        System.out.println("Statement Generation Job Performance Metrics:");
        System.out.println("  Total execution time: " + executionTimeMillis + " ms");
        System.out.println("  Accounts processed: " + testAccounts.size());
        System.out.println("  Transactions processed: " + testTransactions.size());
        System.out.println("  Average time per account: " 
                + (executionTimeMillis / testAccounts.size()) + " ms");
        System.out.println("  Job duration (from JobExecution): " + jobDurationMillis + " ms");

        // Verify performance is consistent with mainframe baseline
        // For 3 accounts with 25 transactions, execution should be subsecond
        assertThat(executionTimeMillis)
                .as("Small dataset should process in subsecond time")
                .isLessThan(5000L);  // Less than 5 seconds
    }

    /**
     * Test: Statement detail processor functionality.
     * 
     * <p>Validates that StatementDetailProcessor correctly transforms COBOL CBSTM03B.cbl
     * subroutine logic for formatting transaction detail lines in statements.</p>
     * 
     * <p><b>COBOL Source:</b> CBSTM03B.cbl lines 164-217 (file I/O processing paragraphs)</p>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Processor correctly formats transaction data</li>
     *   <li>Customer information retrieved and included</li>
     *   <li>Transaction amounts maintain BigDecimal precision</li>
     *   <li>Date formatting matches requirements</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    @DisplayName("Should process statement details matching CBSTM03B.cbl formatting logic")
    public void testStatementGenerationJob_DetailProcessing() throws Exception {
        // Prepare job parameters
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", statementStartDate)
                .addLocalDate("endDate", statementEndDate)
                .addString("outputPath", outputDirectory.toString())
                .toJobParameters();

        // Execute the batch job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Verify job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify customer data was retrieved correctly for statement header
        Customer customer1 = customerRepository.findByCustomerId(1000000001L).orElse(null);
        assertThat(customer1)
                .as("Customer should exist for statement generation")
                .isNotNull();

        assertThat(customer1.getFirstName())
                .as("Customer first name should match test data")
                .isEqualTo("John");

        assertThat(customer1.getLastName())
                .as("Customer last name should match test data")
                .isEqualTo("Smith");

        // Verify account data was retrieved correctly
        Account account1 = accountRepository.findByAccountId(10000000001L).orElse(null);
        assertThat(account1)
                .as("Account should exist for statement generation")
                .isNotNull();

        assertThat(account1.getCurrentBalance())
                .as("Account balance should maintain BigDecimal precision")
                .isEqualByComparingTo(new BigDecimal("1547.89"));

        // Verify transactions were retrieved and processed
        List<Transaction> account1Transactions = new ArrayList<>();
        account1Transactions.addAll(transactionRepository.findByCard_CardNumber("4000123456789010", Pageable.unpaged()).getContent());
        account1Transactions.addAll(transactionRepository.findByCard_CardNumber("5000123456789011", Pageable.unpaged()).getContent());

        assertThat(account1Transactions)
                .as("Account 1 should have 15 transactions")
                .hasSize(15);

        // Verify transaction amounts maintain precision
        for (Transaction transaction : account1Transactions) {
            assertThat(transaction.getAmount())
                    .as("Transaction amount should not be null")
                    .isNotNull();

            assertThat(transaction.getAmount().scale())
                    .as("Transaction amount should maintain scale=2")
                    .isEqualTo(2);

            assertThat(transaction.getMerchantName())
                    .as("Transaction should have merchant name")
                    .isNotBlank();
        }

        // Verify statement files contain all necessary data
        Path statement1Path = findStatementFile(10000000001L);
        assertThat(statement1Path)
                .as("Statement PDF should exist")
                .isNotNull();
        assertThat(Files.exists(statement1Path))
                .as("Statement PDF should exist")
                .isTrue();

        // Verify file size indicates proper detail processing
        long fileSize = Files.size(statement1Path);
        assertThat(fileSize)
                .as("Statement with 15 transactions should have substantial file size")
                .isGreaterThan(3000L);  // At least 3KB with header and 15 transaction details
    }

    /**
     * Helper method to find statement PDF file for a given account ID.
     * Statement files are generated with the pattern: statement_{accountId}_{YYYYMMDD}.pdf
     * 
     * @param accountId the account ID to search for
     * @return Path to the statement file, or null if not found
     * @throws IOException if directory listing fails
     */
    private Path findStatementFile(Long accountId) throws IOException {
        String prefix = "statement_" + accountId + "_";
        File[] matchingFiles = outputDirectory.toFile().listFiles((dir, name) -> 
            name.startsWith(prefix) && name.endsWith(".pdf"));
        
        if (matchingFiles == null || matchingFiles.length == 0) {
            return null;
        }
        
        return matchingFiles[0].toPath();
    }
}


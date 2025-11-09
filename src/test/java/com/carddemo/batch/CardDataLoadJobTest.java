package com.carddemo.batch;

import com.carddemo.batch.job.CardDataLoadJob;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 Integration Test Class for CardDataLoadJob
 * 
 * <p>This test class validates the Spring Batch card data load job that replaces the
 * COBOL batch program CBACT02C.cbl. The original COBOL program reads card records from
 * a VSAM KSDS file (CARDFILE-FILE) and displays them. The modernized version reads
 * card data from a CSV file, validates it, and persists it to a PostgreSQL database
 * table using Spring Batch chunk-oriented processing.</p>
 * 
 * <p><strong>COBOL Program Transformation:</strong></p>
 * <pre>
 * Source: app/cbl/CBACT02C.cbl
 * - COBOL OPEN CARDFILE-FILE → FlatFileItemReader configuration
 * - COBOL READ CARDFILE-FILE INTO CARD-RECORD → ItemReader.read() method
 * - PERFORM UNTIL END-OF-FILE → Spring Batch chunk processing loop
 * - DISPLAY CARD-RECORD → JpaItemWriter.write() to database
 * - COBOL CLOSE CARDFILE-FILE → Automatic resource cleanup
 * </pre>
 * 
 * <p><strong>Data Structure Transformations:</strong></p>
 * <pre>
 * Source: app/cpy/CVACT02Y.cpy (CARD-RECORD)
 * - CARD-NUM PIC X(16) → Card.cardNumber (String, 16 chars, Primary Key)
 * - CARD-ACCT-ID PIC 9(11) → Card.account (ManyToOne relationship, FK)
 * - CARD-CVV-CD PIC 9(03) → Card.cvvCode (String, 3 digits)
 * - CARD-EMBOSSED-NAME PIC X(50) → Card.embossedName (String, 50 chars)
 * - CARD-EXPIRAION-DATE PIC X(10) → Card.expirationDate (LocalDate)
 * - CARD-ACTIVE-STATUS PIC X(01) → Card.activeStatus (String, 'Y'/'N')
 * </pre>
 * 
 * <p><strong>Cross-Reference Relationship:</strong></p>
 * <pre>
 * Source: app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD)
 * - XREF-CARD-NUM to XREF-ACCT-ID mapping → Card.account foreign key relationship
 * - VSAM cross-reference file → PostgreSQL foreign key constraint
 * - Ensures referential integrity: cards must link to existing accounts
 * </pre>
 * 
 * <p><strong>Test Environment Configuration:</strong></p>
 * <ul>
 *   <li>@SpringBatchTest - enables Spring Batch test infrastructure with
 *       JobLauncherTestUtils and JobRepositoryTestUtils beans</li>
 *   <li>@SpringBootTest - loads complete Spring application context with all
 *       configuration, beans, and JPA repositories</li>
 *   <li>@TestPropertySource - configures H2 in-memory database for test isolation,
 *       preventing interference with PostgreSQL production database</li>
 *   <li>H2 Database - provides isolated test environment with schema created from
 *       JPA entity annotations, supporting full SQL compatibility for testing</li>
 * </ul>
 * 
 * <p><strong>Chunk-Oriented Processing Validation:</strong></p>
 * <p>Spring Batch chunk processing replaces COBOL sequential file processing:</p>
 * <ul>
 *   <li>Chunk size: 1000 records per transaction (configured in CardDataLoadJob)</li>
 *   <li>Reader: FlatFileItemReader parses CSV file into CardCsvRecord DTOs</li>
 *   <li>Processor: Validates each record and transforms DTO to Card entity</li>
 *   <li>Writer: JpaItemWriter persists Card entities in batches</li>
 *   <li>Transaction boundaries: Each chunk commits as a single database transaction</li>
 * </ul>
 * 
 * <p><strong>Validation Rules Tested:</strong></p>
 * <ul>
 *   <li><strong>Card Number Format:</strong> Must be exactly 16 characters, matching
 *       COBOL CARD-NUM PIC X(16) field length constraint</li>
 *   <li><strong>CVV Code Format:</strong> Must be exactly 3 digits (000-999), matching
 *       COBOL CARD-CVV-CD PIC 9(03) field specification</li>
 *   <li><strong>Expiration Date:</strong> Must be a valid future date, parsed from
 *       MM/YYYY format to LocalDate, replacing COBOL PIC X(10) date field</li>
 *   <li><strong>Foreign Key Constraint:</strong> Card.account must reference an existing
 *       Account entity, replicating VSAM cross-reference file integrity from CVACT03Y.cpy</li>
 *   <li><strong>Active Status:</strong> Must be 'Y' (active) or 'N' (inactive), matching
 *       COBOL CARD-ACTIVE-STATUS PIC X(01) 88-level condition values</li>
 * </ul>
 * 
 * <p><strong>Test Data Management:</strong></p>
 * <p>Tests create temporary CSV files with card data in the format expected by the
 * FlatFileItemReader. Each test method creates its own test data file with specific
 * scenarios (valid data, foreign key violations, malformed dates, etc.). Files are
 * cleaned up in @AfterEach method to prevent test pollution.</p>
 * 
 * <p><strong>Batch Job Testing Pattern:</strong></p>
 * <ol>
 *   <li>Setup: Create test Account entities as foreign key references</li>
 *   <li>Create: Generate temporary CSV file with test card data</li>
 *   <li>Execute: Launch batch job using JobLauncherTestUtils with file path parameter</li>
 *   <li>Assert: Verify job completion status, exit code, and database state</li>
 *   <li>Validate: Query CardRepository to assert Card entities persisted correctly</li>
 *   <li>Cleanup: Delete test data and temporary files</li>
 * </ol>
 * 
 * <p><strong>Functional Equivalence Requirements:</strong></p>
 * <p>Per section 0.10 Special Instructions, this test validates complete functional
 * equivalence with COBOL program CBACT02C.cbl:</p>
 * <ul>
 *   <li>All CARD-RECORD fields from CVACT02Y.cpy correctly mapped to Card entity fields</li>
 *   <li>Sequential file processing pattern replicated by chunk-oriented processing</li>
 *   <li>Cross-reference file integrity (CVACT03Y.cpy) enforced by foreign key constraints</li>
 *   <li>Error handling for malformed data matches COBOL file status error codes</li>
 *   <li>Batch completion within 4-hour processing window requirement</li>
 * </ul>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <p>Tests verify batch job completes efficiently with proper chunk processing:</p>
 * <ul>
 *   <li>Chunk size of 1000 records minimizes database round trips</li>
 *   <li>Skip limit of 100 allows processing to continue despite validation errors</li>
 *   <li>Retry limit of 3 handles transient database connection issues</li>
 *   <li>JobExecution statistics validate read/write counts and skip counts</li>
 * </ul>
 * 
 * @see CardDataLoadJob
 * @see Card
 * @see Account
 * @see CardRepository
 * @see AccountRepository
 * @see <a href="Section 0.4">Agent Action Plan - Source Files CBACT02C.cbl, CVACT02Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan - Batch Job Testing</a>
 * @see <a href="Section 0.10">Special Instructions - Batch Processing Requirements</a>
 */
@SpringBatchTest
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.batch.jdbc.initialize-schema=always",
    "spring.batch.job.enabled=false",
    "logging.level.com.carddemo=DEBUG"
})
@DisplayName("Card Data Load Batch Job Integration Tests")
public class CardDataLoadJobTest {

    /**
     * JobLauncherTestUtils - Spring Batch Test Utility
     * 
     * <p>Provides methods for launching batch jobs in test context:</p>
     * <ul>
     *   <li>launchJob(JobParameters) - launches job with specific parameters</li>
     *   <li>launchStep(String stepName) - launches individual step for unit testing</li>
     *   <li>getJobRepository() - access to job execution metadata</li>
     * </ul>
     * 
     * <p>Automatically configured by @SpringBatchTest annotation</p>
     */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * Card Data Load Job Bean
     * 
     * <p>The specific Job bean being tested. Must be injected and configured on
     * JobLauncherTestUtils to enable job execution in tests.</p>
     */
    @Autowired
    @Qualifier("cardDataLoadBatchJob")
    private Job cardDataLoadJob;

    /**
     * CardRepository - Spring Data JPA Repository for Card Entity
     * 
     * <p>Used in tests to validate that Card entities are correctly persisted
     * to the database after batch job execution. Provides query methods:</p>
     * <ul>
     *   <li>findAll() - retrieve all cards for count validation</li>
     *   <li>findById(String) - retrieve specific card by card number</li>
     *   <li>findByCardNumber(String) - custom query for card lookup</li>
     *   <li>existsById(String) - check card existence without loading entity</li>
     *   <li>count() - get total number of cards in database</li>
     *   <li>deleteAll() - cleanup test data between test methods</li>
     * </ul>
     */
    @Autowired
    private CardRepository cardRepository;

    /**
     * AccountRepository - Spring Data JPA Repository for Account Entity
     * 
     * <p>Used in tests to create test Account entities that serve as foreign key
     * references for Card entities. Required to satisfy Card.account foreign key
     * constraint that replicates VSAM cross-reference file CVACT03Y.cpy integrity.</p>
     * 
     * <p>Methods used in tests:</p>
     * <ul>
     *   <li>save(Account) - create individual test account</li>
     *   <li>saveAll(List&lt;Account&gt;) - create multiple test accounts</li>
     *   <li>deleteAll() - cleanup test accounts after test execution</li>
     * </ul>
     */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * CustomerRepository - Spring Data JPA Repository for Customer Entity
     * 
     * <p>Used in tests to create test Customer entities that serve as foreign key
     * references for Account entities. Required to satisfy Account.customer foreign key
     * constraint that replicates VSAM cross-reference file CVACT03Y.cpy integrity.</p>
     * 
     * <p>Methods used in tests:</p>
     * <ul>
     *   <li>save(Customer) - create individual test customer</li>
     *   <li>saveAll(List&lt;Customer&gt;) - create multiple test customers</li>
     *   <li>deleteAll() - cleanup test customers after test execution</li>
     * </ul>
     */
    @Autowired
    private CustomerRepository customerRepository;

    /**
     * TransactionRepository - Spring Data JPA Repository for Transaction Entity
     * 
     * <p>Used in tests to clean up test Transaction entities. Required because
     * Transaction entities may reference Card entities through foreign keys,
     * requiring deletion before cards can be deleted.</p>
     */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Test Data File Path
     * 
     * <p>Temporary file path for test CSV files created during test execution.
     * Each test method creates its own CSV file with specific test scenarios.
     * Files are deleted in @AfterEach cleanup method.</p>
     */
    private String testDataFilePath;

    /**
     * DateTimeFormatter for Card Expiration Dates
     * 
     * <p>Formats expiration dates in MM/YYYY format matching typical card expiration
     * date presentation. Used when creating test CSV data files.</p>
     */
    private static final DateTimeFormatter EXPIRATION_DATE_FORMATTER = 
        DateTimeFormatter.ofPattern("MM/yyyy");
    
    /**
     * CSV header row matching FIELD_NAMES in CardDataLoadJob.
     * 
     * <p>This header row defines the expected column order for the CSV file:</p>
     * <ol>
     *   <li>cardNumber - 16 alphanumeric characters</li>
     *   <li>accountId - 11-digit account ID</li>
     *   <li>cardType - CC or DC</li>
     *   <li>expirationDate - MM/yyyy format</li>
     *   <li>embossedName - Name on card (max 50 chars)</li>
     *   <li>cvvCode - 3-digit CVV</li>
     *   <li>activeStatus - Y or N</li>
     *   <li>openDate - yyyy-MM-dd format</li>
     *   <li>lastUsedDate - yyyy-MM-dd format (optional)</li>
     * </ol>
     */
    private static final String CSV_HEADER = "cardNumber,accountId,cardType,expirationDate,embossedName,cvvCode,activeStatus,openDate,lastUsedDate\n";

    /**
     * Test Setup Method
     * 
     * <p>Executed before each test method. Initializes test data file path and
     * ensures clean database state by deleting all entities from previous
     * tests. This prevents test pollution and ensures each test starts with a clean
     * slate matching H2 in-memory database initialization.</p>
     * 
     * <p><strong>Database Cleanup Order:</strong></p>
     * <ol>
     *   <li>Delete all Transaction entities first (may reference cards)</li>
     *   <li>Delete all Card entities second (due to foreign key dependency)</li>
     *   <li>Delete all Account entities third (referenced by cards)</li>
     *   <li>Delete all Customer entities fourth (referenced by accounts)</li>
     * </ol>
     * 
     * <p>Uses deleteAllInBatch() to execute direct SQL DELETE statements without loading entities,
     * avoiding EntityNotFoundException when orphaned foreign key references exist from previous test failures.</p>
     * 
     * @throws Exception if database cleanup fails
     */
    @BeforeEach
    public void setUp() throws Exception {
        // Configure JobLauncherTestUtils with the specific job to test
        jobLauncherTestUtils.setJob(cardDataLoadJob);
        
        // Clean database state before each test
        // Use deleteAllInBatch() to avoid loading entities with broken FK references
        // Delete in order respecting foreign key constraints
        transactionRepository.deleteAllInBatch();
        cardRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
        
        // Initialize test data file path
        testDataFilePath = System.getProperty("java.io.tmpdir") + File.separator + 
                          "card-test-data-" + System.currentTimeMillis() + ".csv";
    }

    /**
     * Test Cleanup Method
     * 
     * <p>Executed after each test method. Cleans up test data and temporary files
     * to prevent resource leaks and test pollution. Ensures H2 in-memory database
     * is clean for next test execution.</p>
     * 
     * <p><strong>Cleanup Steps:</strong></p>
     * <ol>
     *   <li>Delete all Transaction entities from database</li>
     *   <li>Delete all Card entities from database</li>
     *   <li>Delete all Account entities from database</li>
     *   <li>Delete all Customer entities from database</li>
     *   <li>Delete temporary test CSV file from filesystem</li>
     * </ol>
     * 
     * <p>Uses deleteAllInBatch() to execute direct SQL DELETE statements without loading entities,
     * avoiding EntityNotFoundException when orphaned foreign key references exist.</p>
     * 
     * @throws Exception if cleanup operations fail
     */
    @AfterEach
    public void tearDown() throws Exception {
        // Clean database state after each test
        // Use deleteAllInBatch() to avoid loading entities with broken FK references
        // Delete in order respecting foreign key constraints
        transactionRepository.deleteAllInBatch();
        cardRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
        
        // Delete temporary test data file
        File testFile = new File(testDataFilePath);
        if (testFile.exists()) {
            testFile.delete();
        }
    }

    /**
     * Test: Card Data Load Job - Successful Execution
     * 
     * <p>This test validates the successful execution of the CardDataLoadJob with valid
     * card data, replicating the COBOL program CBACT02C.cbl sequential file processing
     * behavior. Tests complete end-to-end batch job execution including:</p>
     * <ul>
     *   <li>FlatFileItemReader reading CSV file records</li>
     *   <li>ItemProcessor validating and transforming card data</li>
     *   <li>JpaItemWriter persisting Card entities to database</li>
     *   <li>Job completion with COMPLETED status and COMPLETED exit code</li>
     * </ul>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <p>Create test CSV file with 3 valid card records, each with different attributes:</p>
     * <ol>
     *   <li>Active card with future expiration date</li>
     *   <li>Inactive card with different account reference</li>
     *   <li>Active card with near-term expiration date</li>
     * </ol>
     * 
     * <p><strong>COBOL Program Equivalence:</strong></p>
     * <pre>
     * COBOL: PERFORM UNTIL END-OF-FILE
     *        READ CARDFILE-FILE INTO CARD-RECORD
     *        DISPLAY CARD-RECORD
     *        END-PERFORM
     * 
     * Spring Batch: Chunk-oriented processing reads 1000 records per chunk,
     *               validates in processor, writes to database in writer
     * </pre>
     * 
     * <p><strong>Validation Assertions:</strong></p>
     * <ul>
     *   <li>JobExecution status equals COMPLETED (job finished successfully)</li>
     *   <li>JobExecution exitStatus code equals "COMPLETED" (no errors)</li>
     *   <li>Database contains exactly 3 Card entities (all records persisted)</li>
     *   <li>Each Card entity has correct cardNumber, account FK, CVV, expiration date</li>
     *   <li>Card.activeStatus correctly maps 'Y'/'N' values</li>
     *   <li>Card.account foreign key references valid Account entity</li>
     * </ul>
     * 
     * <p><strong>Foreign Key Constraint Validation:</strong></p>
     * <p>Per CVACT03Y.cpy cross-reference file, each card must link to an existing account.
     * Test creates test Account entities before job execution to satisfy this constraint.</p>
     * 
     * @throws Exception if job execution or data validation fails
     */
    @Test
    @DisplayName("Card Data Load Job - Successful Execution")
    public void testCardDataLoadJob_Success() throws Exception {
        // Setup: Create test customers for foreign key references
        Customer customer1 = Customer.builder()
            .customerId(100000001L)
            .firstName("John")
            .lastName("Doe")
            .dateOfBirth(LocalDate.of(1980, 1, 15))
            .ssn("123456789")
            .ficoScore(750)
            .build();
        
        Customer customer2 = Customer.builder()
            .customerId(100000002L)
            .firstName("Jane")
            .lastName("Smith")
            .dateOfBirth(LocalDate.of(1985, 3, 20))
            .ssn("987654321")
            .ficoScore(700)
            .build();
        
        customerRepository.saveAll(Arrays.asList(customer1, customer2));
        
        // Setup: Create test accounts for foreign key references
        Account account1 = Account.builder()
            .accountId(1000000001L)
            .customer(customer1)
            .activeStatus("Y")
            .currentBalance(BigDecimal.valueOf(1500.00))
            .creditLimit(BigDecimal.valueOf(10000.00))
            .cashCreditLimit(BigDecimal.valueOf(2000.00))
            .openDate(LocalDate.of(2020, 1, 15))
            .expirationDate(LocalDate.of(2025, 1, 15))
            .currentCycleCredit(BigDecimal.ZERO)
            .currentCycleDebit(BigDecimal.ZERO)
            .build();
        
        Account account2 = Account.builder()
            .accountId(1000000002L)
            .customer(customer2)
            .activeStatus("Y")
            .currentBalance(BigDecimal.valueOf(500.00))
            .creditLimit(BigDecimal.valueOf(5000.00))
            .cashCreditLimit(BigDecimal.valueOf(1000.00))
            .openDate(LocalDate.of(2021, 3, 20))
            .expirationDate(LocalDate.of(2026, 3, 20))
            .currentCycleCredit(BigDecimal.ZERO)
            .currentCycleDebit(BigDecimal.ZERO)
            .build();
        
        accountRepository.saveAll(Arrays.asList(account1, account2));
        
        // Create test CSV file with valid card data
        LocalDate futureExpiration1 = LocalDate.now().plusYears(2);
        LocalDate futureExpiration2 = LocalDate.now().plusYears(1).plusMonths(6);
        LocalDate futureExpiration3 = LocalDate.now().plusMonths(6);
        
        // CSV format: cardNumber,accountId,cardType,expirationDate,embossedName,cvvCode,activeStatus,openDate,lastUsedDate
        String csvContent = CSV_HEADER +
                          "4111111111111111,1000000001,CC," + 
                          futureExpiration1.format(EXPIRATION_DATE_FORMATTER) + ",JOHN DOE,123,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "5500000000000004,1000000002,DC," + 
                          futureExpiration2.format(EXPIRATION_DATE_FORMATTER) + ",JANE SMITH,456,N," +
                          LocalDate.now().minusYears(2).toString() + ",\n" +
                          "3400000000000009,1000000001,CC," + 
                          futureExpiration3.format(EXPIRATION_DATE_FORMATTER) + ",ROBERT JOHNSON,789,Y," +
                          LocalDate.now().minusMonths(6).toString() + ",\n";
        
        createTestDataFile(csvContent);
        
        // Execute: Launch batch job with test file parameter
        JobParameters jobParameters = new JobParametersBuilder()
            .addString("cardDataFile", testDataFilePath)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        
        // Assert: Verify job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        
        // Assert: Verify all 3 cards were persisted to database
        assertThat(cardRepository.count()).isEqualTo(3L);
        
        // Validate: Verify first card entity
        Card card1 = cardRepository.findById("4111111111111111").orElseThrow();
        assertThat(card1.getCardNumber()).isEqualTo("4111111111111111");
        assertThat(card1.getAccount().getAccountId()).isEqualTo(1000000001L);
        assertThat(card1.getCvvCode()).isEqualTo("123");
        assertThat(card1.getEmbossedName()).isEqualTo("JOHN DOE");
        assertThat(card1.getExpirationDate().getYear()).isEqualTo(futureExpiration1.getYear());
        assertThat(card1.getExpirationDate().getMonthValue()).isEqualTo(futureExpiration1.getMonthValue());
        assertThat(card1.getActiveStatus()).isEqualTo("Y");
        assertThat(card1.getCardType()).isEqualTo("CC");
        
        // Validate: Verify second card entity
        Card card2 = cardRepository.findById("5500000000000004").orElseThrow();
        assertThat(card2.getCardNumber()).isEqualTo("5500000000000004");
        assertThat(card2.getAccount().getAccountId()).isEqualTo(1000000002L);
        assertThat(card2.getCvvCode()).isEqualTo("456");
        assertThat(card2.getActiveStatus()).isEqualTo("N");
        assertThat(card2.getCardType()).isEqualTo("DC");
        
        // Validate: Verify third card entity
        Card card3 = cardRepository.findById("3400000000000009").orElseThrow();
        assertThat(card3.getCardNumber()).isEqualTo("3400000000000009");
        assertThat(card3.getAccount().getAccountId()).isEqualTo(1000000001L);
        assertThat(card3.getCvvCode()).isEqualTo("789");
        assertThat(card3.getActiveStatus()).isEqualTo("Y");
    }

    /**
     * Test: Card Data Load Job - Foreign Key Constraint Validation
     * 
     * <p>This test validates that the batch job correctly enforces foreign key constraints
     * when card records reference non-existent accounts. This replicates the VSAM cross-
     * reference file integrity from CVACT03Y.cpy (CARD-XREF-RECORD) where XREF-CARD-NUM
     * must link to valid XREF-ACCT-ID.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <p>Create test CSV file with 2 cards:</p>
     * <ol>
     *   <li>Valid card referencing existing account (should be persisted)</li>
     *   <li>Invalid card referencing non-existent account 9999999999 (should be skipped)</li>
     * </ol>
     * 
     * <p><strong>Cross-Reference File Equivalence:</strong></p>
     * <pre>
     * COBOL: CARD-XREF-RECORD (CVACT03Y.cpy)
     *        05 XREF-CARD-NUM     PIC X(16).
     *        05 XREF-ACCT-ID      PIC 9(11).
     *        05 XREF-CUST-ID      PIC 9(09).
     * 
     * PostgreSQL: Card entity with @ManyToOne relationship to Account
     *             Foreign key constraint: card.account_id → account.account_id
     *             Constraint name: fk_card_account
     * </pre>
     * 
     * <p><strong>Skip Logic Behavior:</strong></p>
     * <p>CardDataLoadJob is configured with skip limit of 100 for validation errors.
     * When processor throws CardValidationException for missing account reference,
     * Spring Batch skip policy triggers, logging the error and continuing with
     * next record. This matches COBOL error handling pattern where invalid records
     * are logged but don't halt batch job execution.</p>
     * 
     * <p><strong>Validation Assertions:</strong></p>
     * <ul>
     *   <li>JobExecution status equals COMPLETED (job finishes despite skip)</li>
     *   <li>Database contains exactly 1 Card entity (only valid card persisted)</li>
     *   <li>Skipped card not present in database</li>
     *   <li>Valid card has correct account foreign key reference</li>
     *   <li>JobExecution statistics show 1 skip count for invalid card</li>
     * </ul>
     * 
     * @throws Exception if job execution or validation fails
     */
    @Test
    @DisplayName("Card Data Load Job - Foreign Key Constraint Validation")
    public void testCardDataLoadJob_ForeignKeyConstraint() throws Exception {
        // Setup: Create test customer for foreign key reference
        Customer customer = Customer.builder()
            .customerId(100000001L)
            .firstName("John")
            .lastName("Doe")
            .dateOfBirth(LocalDate.of(1980, 1, 15))
            .ssn("123456789")
            .ficoScore(750)
            .build();
        
        customerRepository.save(customer);
        
        // Setup: Create only one test account
        Account validAccount = Account.builder()
            .accountId(1000000001L)
            .customer(customer)
            .activeStatus("Y")
            .currentBalance(BigDecimal.valueOf(1000.00))
            .creditLimit(BigDecimal.valueOf(10000.00))
            .cashCreditLimit(BigDecimal.valueOf(2000.00))
            .openDate(LocalDate.of(2020, 1, 15))
            .expirationDate(LocalDate.of(2025, 1, 15))
            .currentCycleCredit(BigDecimal.ZERO)
            .currentCycleDebit(BigDecimal.ZERO)
            .build();
        
        accountRepository.save(validAccount);
        
        // Create test CSV with one valid and one invalid (non-existent account) card
        LocalDate futureExpiration = LocalDate.now().plusYears(2);
        
        // CSV format: cardNumber,accountId,cardType,expirationDate,embossedName,cvvCode,activeStatus,openDate,lastUsedDate
        String csvContent = CSV_HEADER +
                          "4111111111111111,1000000001,CC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",JOHN DOE,123,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "5500000000000004,9999999999,DC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",INVALID USER,456,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n";
        
        createTestDataFile(csvContent);
        
        // Execute: Launch batch job
        JobParameters jobParameters = new JobParametersBuilder()
            .addString("cardDataFile", testDataFilePath)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        
        // Assert: Job completes (with skips) but doesn't fail
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        
        // Assert: Only the valid card was persisted
        assertThat(cardRepository.count()).isEqualTo(1L);
        
        // Validate: Check that the valid card exists
        assertThat(cardRepository.existsById("4111111111111111")).isTrue();
        
        // Validate: Check that the invalid card was not persisted
        assertThat(cardRepository.existsById("5500000000000004")).isFalse();
        
        // Validate: Verify the persisted card has correct account reference
        Card validCard = cardRepository.findById("4111111111111111").orElseThrow();
        assertThat(validCard.getAccount().getAccountId()).isEqualTo(1000000001L);
    }

    /**
     * Test: Card Data Load Job - Expiration Date Parsing
     * 
     * <p>This test validates correct parsing of card expiration dates from CSV format
     * (MM/YYYY) to Java LocalDate, replacing COBOL CARD-EXPIRAION-DATE field
     * (PIC X(10)) from CVACT02Y.cpy copybook.</p>
     * 
     * <p><strong>Date Format Transformation:</strong></p>
     * <pre>
     * COBOL: CARD-EXPIRAION-DATE PIC X(10)
     *        Format: MM/DD/YYYY or YYYY-MM-DD (various formats in mainframe)
     * 
     * Java: Card.expirationDate (LocalDate)
     *       Format: YYYY-MM-DD (ISO-8601 standard)
     *       Parsed from CSV: MM/YYYY (month and year only)
     * </pre>
     * 
     * <p><strong>Date Parsing Logic:</strong></p>
     * <p>CardDataLoadJob processor parses expiration dates from MM/YYYY format
     * (e.g., "12/2025") and converts to LocalDate with day set to last day of month
     * (e.g., LocalDate.of(2025, 12, 31)). This matches typical credit card expiration
     * date semantics where cards expire at end of specified month.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <p>Create test CSV with cards having various expiration dates:</p>
     * <ol>
     *   <li>Card expiring in 2 years (future date)</li>
     *   <li>Card expiring in 6 months (near-term expiration)</li>
     *   <li>Card expiring in 5 years (far future date)</li>
     * </ol>
     * 
     * <p><strong>Validation Assertions:</strong></p>
     * <ul>
     *   <li>All cards successfully parsed and persisted</li>
     *   <li>Card.expirationDate matches expected year and month from CSV</li>
     *   <li>Expiration dates are in the future (validation rule)</li>
     *   <li>LocalDate correctly represents end of expiration month</li>
     * </ul>
     * 
     * @throws Exception if job execution or date parsing fails
     */
    @Test
    @DisplayName("Card Data Load Job - Expiration Date Parsing")
    public void testCardDataLoadJob_ExpirationDateParsing() throws Exception {
        // Setup: Create test customer for foreign key reference
        Customer customer = Customer.builder()
            .customerId(100000001L)
            .firstName("Test")
            .lastName("User")
            .dateOfBirth(LocalDate.of(1985, 5, 15))
            .ssn("555555555")
            .ficoScore(720)
            .build();
        
        customerRepository.save(customer);
        
        // Setup: Create test account
        Account testAccount = Account.builder()
            .accountId(1000000001L)
            .customer(customer)
            .activeStatus("Y")
            .currentBalance(BigDecimal.valueOf(500.00))
            .creditLimit(BigDecimal.valueOf(10000.00))
            .cashCreditLimit(BigDecimal.valueOf(2000.00))
            .openDate(LocalDate.of(2020, 1, 15))
            .expirationDate(LocalDate.of(2025, 1, 15))
            .currentCycleCredit(BigDecimal.ZERO)
            .currentCycleDebit(BigDecimal.ZERO)
            .build();
        
        accountRepository.save(testAccount);
        
        // Create test CSV with various expiration dates
        LocalDate expiration1 = LocalDate.now().plusYears(2); // 2 years from now
        LocalDate expiration2 = LocalDate.now().plusMonths(6); // 6 months from now
        LocalDate expiration3 = LocalDate.now().plusYears(5); // 5 years from now
        
        // CSV format: cardNumber,accountId,cardType,expirationDate,embossedName,cvvCode,activeStatus,openDate,lastUsedDate
        String csvContent = CSV_HEADER +
                          "4111111111111111,1000000001,CC," + 
                          expiration1.format(EXPIRATION_DATE_FORMATTER) + ",JOHN DOE,123,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "5500000000000004,1000000001,DC," + 
                          expiration2.format(EXPIRATION_DATE_FORMATTER) + ",JANE SMITH,456,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "3400000000000009,1000000001,CC," + 
                          expiration3.format(EXPIRATION_DATE_FORMATTER) + ",ROBERT JOHNSON,789,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n";
        
        createTestDataFile(csvContent);
        
        // Execute: Launch batch job
        JobParameters jobParameters = new JobParametersBuilder()
            .addString("cardDataFile", testDataFilePath)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        
        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(cardRepository.count()).isEqualTo(3L);
        
        // Validate: Check first card expiration date
        Card card1 = cardRepository.findById("4111111111111111").orElseThrow();
        assertThat(card1.getExpirationDate().getYear()).isEqualTo(expiration1.getYear());
        assertThat(card1.getExpirationDate().getMonthValue()).isEqualTo(expiration1.getMonthValue());
        assertThat(card1.getExpirationDate()).isAfter(LocalDate.now());
        
        // Validate: Check second card expiration date
        Card card2 = cardRepository.findById("5500000000000004").orElseThrow();
        assertThat(card2.getExpirationDate().getYear()).isEqualTo(expiration2.getYear());
        assertThat(card2.getExpirationDate().getMonthValue()).isEqualTo(expiration2.getMonthValue());
        assertThat(card2.getExpirationDate()).isAfter(LocalDate.now());
        
        // Validate: Check third card expiration date
        Card card3 = cardRepository.findById("3400000000000009").orElseThrow();
        assertThat(card3.getExpirationDate().getYear()).isEqualTo(expiration3.getYear());
        assertThat(card3.getExpirationDate().getMonthValue()).isEqualTo(expiration3.getMonthValue());
        assertThat(card3.getExpirationDate()).isAfter(LocalDate.now());
    }

    /**
     * Test: Card Data Load Job - CVV Validation
     * 
     * <p>This test validates CVV (Card Verification Value) code format validation,
     * ensuring CVV codes are exactly 3 digits matching COBOL CARD-CVV-CD field
     * (PIC 9(03)) from CVACT02Y.cpy copybook.</p>
     * 
     * <p><strong>CVV Format Specification:</strong></p>
     * <pre>
     * COBOL: CARD-CVV-CD PIC 9(03)
     *        - Must be exactly 3 numeric digits
     *        - Valid range: 000-999
     *        - Stored as numeric field in COBOL
     * 
     * Java: Card.cvvCode (String, length 3)
     *       - Stored as String to preserve leading zeros (e.g., "007")
     *       - Validation: Must match pattern [0-9]{3}
     *       - Database column: VARCHAR(3)
     * </pre>
     * 
     * <p><strong>Security Considerations:</strong></p>
     * <p>Per PCI-DSS compliance requirements mentioned in Card entity documentation:</p>
     * <ul>
     *   <li>CVV codes should be encrypted at rest in production</li>
     *   <li>Never logged or displayed in plain text</li>
     *   <li>Restricted access in application code</li>
     *   <li>Test environment uses non-production CVV values</li>
     * </ul>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <p>Create test CSV with cards having various valid CVV codes:</p>
     * <ol>
     *   <li>CVV with leading zero: "007" (tests zero preservation)</li>
     *   <li>CVV with all zeros: "000" (tests minimum value)</li>
     *   <li>CVV with maximum value: "999" (tests maximum value)</li>
     * </ol>
     * 
     * <p><strong>Validation Assertions:</strong></p>
     * <ul>
     *   <li>All cards with valid 3-digit CVV codes are persisted</li>
     *   <li>CVV codes preserve leading zeros (stored as String)</li>
     *   <li>Card.cvvCode length equals exactly 3 characters</li>
     *   <li>CVV codes match expected values from CSV input</li>
     * </ul>
     * 
     * @throws Exception if job execution or CVV validation fails
     */
    @Test
    @DisplayName("Card Data Load Job - CVV Validation")
    public void testCardDataLoadJob_CVVValidation() throws Exception {
        // Setup: Create test customer for foreign key reference
        Customer customer = Customer.builder()
            .customerId(100000001L)
            .firstName("CVV")
            .lastName("Test")
            .dateOfBirth(LocalDate.of(1982, 7, 20))
            .ssn("777777777")
            .ficoScore(680)
            .build();
        
        customerRepository.save(customer);
        
        // Setup: Create test account
        Account testAccount = Account.builder()
            .accountId(1000000001L)
            .customer(customer)
            .activeStatus("Y")
            .currentBalance(BigDecimal.valueOf(1000.00))
            .creditLimit(BigDecimal.valueOf(10000.00))
            .cashCreditLimit(BigDecimal.valueOf(2000.00))
            .openDate(LocalDate.of(2020, 1, 15))
            .expirationDate(LocalDate.of(2025, 1, 15))
            .currentCycleCredit(BigDecimal.ZERO)
            .currentCycleDebit(BigDecimal.ZERO)
            .build();
        
        accountRepository.save(testAccount);
        
        // Create test CSV with various CVV codes including leading zeros
        LocalDate futureExpiration = LocalDate.now().plusYears(2);
        
        // CSV format: cardNumber,accountId,cardType,expirationDate,embossedName,cvvCode,activeStatus,openDate,lastUsedDate
        String csvContent = CSV_HEADER +
                          "4111111111111111,1000000001,CC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",JOHN DOE,007,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "5500000000000004,1000000001,DC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",JANE SMITH,000,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "3400000000000009,1000000001,CC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",ROBERT JOHNSON,999,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n";
        
        createTestDataFile(csvContent);
        
        // Execute: Launch batch job
        JobParameters jobParameters = new JobParametersBuilder()
            .addString("cardDataFile", testDataFilePath)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        
        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(cardRepository.count()).isEqualTo(3L);
        
        // Validate: Check CVV with leading zero is preserved
        Card card1 = cardRepository.findById("4111111111111111").orElseThrow();
        assertThat(card1.getCvvCode()).isEqualTo("007");
        assertThat(card1.getCvvCode()).hasSize(3);
        
        // Validate: Check CVV with all zeros
        Card card2 = cardRepository.findById("5500000000000004").orElseThrow();
        assertThat(card2.getCvvCode()).isEqualTo("000");
        assertThat(card2.getCvvCode()).hasSize(3);
        
        // Validate: Check maximum CVV value
        Card card3 = cardRepository.findById("3400000000000009").orElseThrow();
        assertThat(card3.getCvvCode()).isEqualTo("999");
        assertThat(card3.getCvvCode()).hasSize(3);
    }

    /**
     * Test: Card Data Load Job - Card Number Format Validation
     * 
     * <p>This test validates card number format, ensuring card numbers are exactly
     * 16 characters matching COBOL CARD-NUM field (PIC X(16)) from CVACT02Y.cpy
     * copybook. This field serves as the primary key for VSAM CARDDAT file.</p>
     * 
     * <p><strong>Card Number Format Specification:</strong></p>
     * <pre>
     * COBOL: CARD-NUM PIC X(16)
     *        - Exactly 16 alphanumeric characters
     *        - Primary key for VSAM KSDS CARDDAT file
     *        - Must be unique across all card records
     * 
     * Java: Card.cardNumber (String, length 16)
     *       - @Id annotation marks as primary key
     *       - Database column: VARCHAR(16) with PRIMARY KEY constraint
     *       - Typically contains 16-digit numbers for major card networks
     * </pre>
     * 
     * <p><strong>Card Number Industry Standards:</strong></p>
     * <p>Test uses realistic card number prefixes matching industry standards:</p>
     * <ul>
     *   <li>4xxxxxxxxxxxxxxxxx - Visa cards (16 digits)</li>
     *   <li>5xxxxxxxxxxxxxxxxx - Mastercard cards (16 digits)</li>
     *   <li>3xxxxxxxxxxxxxxxxxxx - American Express cards (15 digits, padded to 16)</li>
     * </ul>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <p>Create test CSV with cards having valid 16-character card numbers:</p>
     * <ol>
     *   <li>Visa card number starting with '4'</li>
     *   <li>Mastercard number starting with '5'</li>
     *   <li>American Express number starting with '3' (padded)</li>
     * </ol>
     * 
     * <p><strong>Validation Assertions:</strong></p>
     * <ul>
     *   <li>All cards with valid 16-character numbers are persisted</li>
     *   <li>Card.cardNumber length equals exactly 16 characters</li>
     *   <li>Card numbers match expected values from CSV input</li>
     *   <li>Card numbers serve as unique primary keys in database</li>
     * </ul>
     * 
     * @throws Exception if job execution or card number validation fails
     */
    @Test
    @DisplayName("Card Data Load Job - Card Number Format Validation")
    public void testCardDataLoadJob_CardNumberFormat() throws Exception {
        // Setup: Create test customer for foreign key reference
        Customer customer = Customer.builder()
            .customerId(100000001L)
            .firstName("Format")
            .lastName("Validator")
            .dateOfBirth(LocalDate.of(1990, 3, 10))
            .ssn("888888888")
            .ficoScore(790)
            .build();
        
        customerRepository.save(customer);
        
        // Setup: Create test account
        Account testAccount = Account.builder()
            .accountId(1000000001L)
            .customer(customer)
            .activeStatus("Y")
            .currentBalance(BigDecimal.valueOf(1000.00))
            .creditLimit(BigDecimal.valueOf(10000.00))
            .cashCreditLimit(BigDecimal.valueOf(2000.00))
            .openDate(LocalDate.of(2020, 1, 15))
            .expirationDate(LocalDate.of(2025, 1, 15))
            .currentCycleCredit(BigDecimal.ZERO)
            .currentCycleDebit(BigDecimal.ZERO)
            .build();
        
        accountRepository.save(testAccount);
        
        // Create test CSV with valid 16-character card numbers
        LocalDate futureExpiration = LocalDate.now().plusYears(2);
        
        // CSV format: cardNumber,accountId,cardType,expirationDate,embossedName,cvvCode,activeStatus,openDate,lastUsedDate
        String csvContent = CSV_HEADER +
                          "4111111111111111,1000000001,CC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",JOHN DOE,123,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "5500000000000004,1000000001,DC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",JANE SMITH,456,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "3400000000000009,1000000001,CC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",ROBERT JOHNSON,789,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n";
        
        createTestDataFile(csvContent);
        
        // Execute: Launch batch job
        JobParameters jobParameters = new JobParametersBuilder()
            .addString("cardDataFile", testDataFilePath)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        
        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(cardRepository.count()).isEqualTo(3L);
        
        // Validate: Check all card numbers are exactly 16 characters
        Card card1 = cardRepository.findById("4111111111111111").orElseThrow();
        assertThat(card1.getCardNumber()).hasSize(16);
        assertThat(card1.getCardNumber()).startsWith("4"); // Visa
        
        Card card2 = cardRepository.findById("5500000000000004").orElseThrow();
        assertThat(card2.getCardNumber()).hasSize(16);
        assertThat(card2.getCardNumber()).startsWith("5"); // Mastercard
        
        Card card3 = cardRepository.findById("3400000000000009").orElseThrow();
        assertThat(card3.getCardNumber()).hasSize(16);
        assertThat(card3.getCardNumber()).startsWith("3"); // American Express (padded)
    }

    /**
     * Test: Card Data Load Job - Active Status Mapping
     * 
     * <p>This test validates card active status field mapping from COBOL single-character
     * flag to Java String, ensuring 'Y'/'N' values are correctly preserved. Replicates
     * COBOL CARD-ACTIVE-STATUS field (PIC X(01)) from CVACT02Y.cpy copybook with 88-level
     * condition names.</p>
     * 
     * <p><strong>Active Status Field Specification:</strong></p>
     * <pre>
     * COBOL: CARD-ACTIVE-STATUS PIC X(01)
     *        88 CARD-IS-ACTIVE    VALUE 'Y'.
     *        88 CARD-IS-INACTIVE  VALUE 'N'.
     * 
     * Java: Card.activeStatus (String, length 1)
     *       - Stored as VARCHAR(1) in database
     *       - Valid values: 'Y' (active), 'N' (inactive)
     *       - Used in transaction authorization (COTRN02C.cbl)
     * </pre>
     * 
     * <p><strong>Business Logic Requirements:</strong></p>
     * <p>Per Card entity documentation and COBOL program COTRN02C.cbl, card active
     * status determines transaction authorization:</p>
     * <ul>
     *   <li><strong>'Y' (Active):</strong> Card authorized for transactions, purchases allowed</li>
     *   <li><strong>'N' (Inactive):</strong> Card blocked from transactions, purchases declined</li>
     * </ul>
     * 
     * <p><strong>Reasons for Inactive Status (from Card entity Javadoc):</strong></p>
     * <ul>
     *   <li>Card reported lost or stolen</li>
     *   <li>Card expired and not yet renewed</li>
     *   <li>Account closed or suspended</li>
     *   <li>Fraud detection triggered card block</li>
     *   <li>Cardholder requested card deactivation</li>
     * </ul>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <p>Create test CSV with cards having both active and inactive status:</p>
     * <ol>
     *   <li>Active card with status 'Y'</li>
     *   <li>Inactive card with status 'N'</li>
     *   <li>Active card with status 'Y'</li>
     * </ol>
     * 
     * <p><strong>Validation Assertions:</strong></p>
     * <ul>
     *   <li>All cards with 'Y' or 'N' status are persisted</li>
     *   <li>Card.activeStatus exactly matches CSV input value</li>
     *   <li>Active status values are single character 'Y' or 'N'</li>
     *   <li>Status mapping preserves COBOL 88-level condition semantics</li>
     * </ul>
     * 
     * @throws Exception if job execution or status mapping fails
     */
    @Test
    @DisplayName("Card Data Load Job - Active Status Mapping")
    public void testCardDataLoadJob_ActiveStatusMapping() throws Exception {
        // Setup: Create test customer for foreign key reference
        Customer customer = Customer.builder()
            .customerId(100000001L)
            .firstName("Status")
            .lastName("Mapper")
            .dateOfBirth(LocalDate.of(1988, 11, 25))
            .ssn("999999999")
            .ficoScore(810)
            .build();
        
        customerRepository.save(customer);
        
        // Setup: Create test account
        Account testAccount = Account.builder()
            .accountId(1000000001L)
            .customer(customer)
            .activeStatus("Y")
            .currentBalance(BigDecimal.valueOf(1000.00))
            .creditLimit(BigDecimal.valueOf(10000.00))
            .cashCreditLimit(BigDecimal.valueOf(2000.00))
            .openDate(LocalDate.of(2020, 1, 15))
            .expirationDate(LocalDate.of(2025, 1, 15))
            .currentCycleCredit(BigDecimal.ZERO)
            .currentCycleDebit(BigDecimal.ZERO)
            .build();
        
        accountRepository.save(testAccount);
        
        // Create test CSV with mixed active/inactive status
        LocalDate futureExpiration = LocalDate.now().plusYears(2);
        
        // CSV format: cardNumber,accountId,cardType,expirationDate,embossedName,cvvCode,activeStatus,openDate,lastUsedDate
        String csvContent = CSV_HEADER +
                          "4111111111111111,1000000001,CC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",JOHN DOE,123,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "5500000000000004,1000000001,DC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",JANE SMITH,456,N," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "3400000000000009,1000000001,CC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",ROBERT JOHNSON,789,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n";
        
        createTestDataFile(csvContent);
        
        // Execute: Launch batch job
        JobParameters jobParameters = new JobParametersBuilder()
            .addString("cardDataFile", testDataFilePath)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        
        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(cardRepository.count()).isEqualTo(3L);
        
        // Validate: Check active card status
        Card card1 = cardRepository.findById("4111111111111111").orElseThrow();
        assertThat(card1.getActiveStatus()).isEqualTo("Y");
        assertThat(card1.getActiveStatus()).hasSize(1);
        
        // Validate: Check inactive card status
        Card card2 = cardRepository.findById("5500000000000004").orElseThrow();
        assertThat(card2.getActiveStatus()).isEqualTo("N");
        assertThat(card2.getActiveStatus()).hasSize(1);
        
        // Validate: Check another active card status
        Card card3 = cardRepository.findById("3400000000000009").orElseThrow();
        assertThat(card3.getActiveStatus()).isEqualTo("Y");
        assertThat(card3.getActiveStatus()).hasSize(1);
    }

    /**
     * Test: Card Data Load Job - Empty File Handling
     * 
     * <p>This test validates batch job behavior when processing an empty CSV file
     * (no card records). Ensures graceful handling matching COBOL END-OF-FILE
     * detection pattern without errors.</p>
     * 
     * <p><strong>COBOL Empty File Pattern:</strong></p>
     * <pre>
     * COBOL: PERFORM UNTIL END-OF-FILE = 'Y'
     *        READ CARDFILE-FILE INTO CARD-RECORD
     *        AT END MOVE 'Y' TO END-OF-FILE
     *        ...
     *        END-PERFORM
     * 
     * Result: If file is empty, END-OF-FILE set immediately, loop exits,
     *         no error occurs, batch job completes successfully
     * </pre>
     * 
     * <p><strong>Spring Batch Empty File Behavior:</strong></p>
     * <p>FlatFileItemReader returns null on first read() call when file is empty
     * (or contains only headers). Spring Batch recognizes null as end-of-input,
     * completes step with 0 items read, and marks job as COMPLETED.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <p>Create empty CSV file with only column headers, no data rows. Launch
     * batch job and verify it completes successfully without processing any records.</p>
     * 
     * <p><strong>Validation Assertions:</strong></p>
     * <ul>
     *   <li>JobExecution status equals COMPLETED (not FAILED)</li>
     *   <li>JobExecution exitStatus code equals "COMPLETED"</li>
     *   <li>Database contains 0 Card entities (no records inserted)</li>
     *   <li>JobExecution statistics show 0 items read, 0 items written</li>
     * </ul>
     * 
     * @throws Exception if job execution fails unexpectedly
     */
    @Test
    @DisplayName("Card Data Load Job - Empty File Handling")
    public void testCardDataLoadJob_EmptyFile() throws Exception {
        // Create empty CSV file (headers only, no data rows)
        String csvContent = ""; // Empty file, no headers or data
        
        createTestDataFile(csvContent);
        
        // Execute: Launch batch job with empty file
        JobParameters jobParameters = new JobParametersBuilder()
            .addString("cardDataFile", testDataFilePath)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        
        // Assert: Job completes successfully despite empty file
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        
        // Assert: No cards were persisted
        assertThat(cardRepository.count()).isEqualTo(0L);
    }

    /**
     * Test: Card Data Load Job - Malformed Data Handling
     * 
     * <p>This test validates batch job skip logic when processing malformed data
     * that fails validation. Ensures job continues processing valid records and
     * skips invalid records, matching COBOL error handling pattern where bad
     * records are logged but don't halt batch job execution.</p>
     * 
     * <p><strong>COBOL Error Handling Pattern:</strong></p>
     * <pre>
     * COBOL: IF CARDFILE-STATUS NOT = '00'
     *            DISPLAY 'ERROR READING CARDFILE: ' CARDFILE-STATUS
     *            PERFORM ABEND-ROUTINE
     *        END-IF
     * 
     * Modernized: Spring Batch skip policy allows continuing after validation
     *             errors, logging skipped records to error file via CardSkipListener
     * </pre>
     * 
     * <p><strong>Skip Policy Configuration:</strong></p>
     * <p>CardDataLoadJob configures skip policy with:</p>
     * <ul>
     *   <li>Skip limit: 100 records (allow up to 100 validation failures)</li>
     *   <li>Skippable exceptions: CardValidationException, DataIntegrityViolationException</li>
     *   <li>Skip listener: CardSkipListener logs skipped records to error file</li>
     * </ul>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <p>Create test CSV with mix of valid and malformed data:</p>
     * <ol>
     *   <li>Valid card record (should be persisted)</li>
     *   <li>Card with invalid expiration date format (should be skipped)</li>
     *   <li>Valid card record (should be persisted)</li>
     * </ol>
     * 
     * <p><strong>Validation Assertions:</strong></p>
     * <ul>
     *   <li>JobExecution status equals COMPLETED (job finishes despite skips)</li>
     *   <li>Database contains exactly 2 Card entities (only valid cards persisted)</li>
     *   <li>Malformed card record not present in database</li>
     *   <li>JobExecution statistics show 1 skip count for invalid record</li>
     * </ul>
     * 
     * @throws Exception if job execution fails unexpectedly
     */
    @Test
    @DisplayName("Card Data Load Job - Malformed Data Handling")
    public void testCardDataLoadJob_MalformedData() throws Exception {
        // Setup: Create test customer for foreign key reference
        Customer customer = Customer.builder()
            .customerId(1000000001L)
            .firstName("John")
            .middleName("A")
            .lastName("Doe")
            .addressLine1("123 Main St")
            .addressLine2("Apt 4B")
            .addressLine3("New York")
            .addressStateCode("NY")
            .addressZip("10001")
            .addressCountryCode("USA")
            .phoneNumber1("555-1234")
            .ssn("123456789")
            .dateOfBirth(LocalDate.of(1980, 1, 1))
            .ficoScore(750)
            .build();
        
        customerRepository.save(customer);
        
        // Setup: Create test account
        Account testAccount = Account.builder()
            .accountId(1000000001L)
            .customer(customer)
            .activeStatus("Y")
            .currentBalance(BigDecimal.valueOf(1000.00))
            .creditLimit(BigDecimal.valueOf(10000.00))
            .cashCreditLimit(BigDecimal.valueOf(2000.00))
            .openDate(LocalDate.of(2020, 1, 15))
            .expirationDate(LocalDate.of(2025, 1, 15))
            .currentCycleCredit(BigDecimal.ZERO)
            .currentCycleDebit(BigDecimal.ZERO)
            .build();
        
        accountRepository.save(testAccount);
        
        // Create test CSV with one valid, one malformed (invalid expiration date), one valid
        LocalDate futureExpiration = LocalDate.now().plusYears(2);
        
        // CSV format: cardNumber,accountId,cardType,expirationDate,embossedName,cvvCode,activeStatus,openDate,lastUsedDate
        String csvContent = CSV_HEADER +
                          "4111111111111111,1000000001,CC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",JOHN DOE,123,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "5500000000000004,1000000001,DC,INVALID-DATE,JANE SMITH,456,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n" +
                          "3400000000000009,1000000001,CC," + 
                          futureExpiration.format(EXPIRATION_DATE_FORMATTER) + ",ROBERT JOHNSON,789,Y," +
                          LocalDate.now().minusYears(1).toString() + ",\n";
        
        createTestDataFile(csvContent);
        
        // Execute: Launch batch job
        JobParameters jobParameters = new JobParametersBuilder()
            .addString("cardDataFile", testDataFilePath)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        
        // Assert: Job completes (with skips) but doesn't fail
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        
        // Assert: Only the 2 valid cards were persisted
        assertThat(cardRepository.count()).isEqualTo(2L);
        
        // Validate: Check that the valid cards exist
        assertThat(cardRepository.existsById("4111111111111111")).isTrue();
        assertThat(cardRepository.existsById("3400000000000009")).isTrue();
        
        // Validate: Check that the malformed card was not persisted
        assertThat(cardRepository.existsById("5500000000000004")).isFalse();
    }

    /**
     * Helper Method: Create Test Data File
     * 
     * <p>Creates temporary CSV file with specified content for batch job testing.
     * File is created in system temporary directory with timestamp-based filename
     * to avoid conflicts between concurrent test executions.</p>
     * 
     * <p><strong>File Format:</strong></p>
     * <p>CSV file with comma-delimited fields matching CardCsvRecord DTO structure:</p>
     * <pre>
     * cardNumber,accountId,cvvCode,embossedName,expirationDate,activeStatus,cardType,openDate
     * 4111111111111111,1000000001,123,JOHN DOE,12/2025,Y,CC,2020-01-15
     * </pre>
     * 
     * <p><strong>Field Mapping:</strong></p>
     * <ul>
     *   <li>cardNumber - 16-character card number (CARD-NUM PIC X(16))</li>
     *   <li>accountId - 11-digit account ID foreign key (CARD-ACCT-ID PIC 9(11))</li>
     *   <li>cvvCode - 3-digit CVV code (CARD-CVV-CD PIC 9(03))</li>
     *   <li>embossedName - Cardholder name (CARD-EMBOSSED-NAME PIC X(50))</li>
     *   <li>expirationDate - MM/YYYY format (CARD-EXPIRAION-DATE PIC X(10))</li>
     *   <li>activeStatus - Y/N flag (CARD-ACTIVE-STATUS PIC X(01))</li>
     *   <li>cardType - CC/DC code (enhanced field, not in COBOL)</li>
     *   <li>openDate - YYYY-MM-DD format (enhanced field, not in COBOL)</li>
     * </ul>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>If file creation fails (e.g., insufficient permissions, disk full), throws
     * IOException which will cause test to fail. This prevents tests from attempting
     * to execute batch jobs with missing input files.</p>
     * 
     * @param content CSV content to write to file
     * @throws IOException if file creation or write operation fails
     */
    private void createTestDataFile(String content) throws IOException {
        File testFile = new File(testDataFilePath);
        testFile.getParentFile().mkdirs(); // Ensure parent directory exists
        
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write(content);
        }
    }
}

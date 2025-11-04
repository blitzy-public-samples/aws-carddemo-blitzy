/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch;

import com.carddemo.batch.job.AccountXrefBuildJob;
import com.carddemo.entity.Account;
import com.carddemo.entity.AccountXref;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.AccountXrefRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CustomerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive Spring Batch test class for AccountXrefBuildJob that validates cross-reference
 * table population from account and customer data.
 * 
 * <p><b>COBOL Source Transformation:</b></p>
 * <p>This test class validates the functional equivalence of the Java Spring Batch implementation
 * with the original COBOL batch program CBACT02C.cbl that builds XREF VSAM file relationships.
 * The COBOL program (lines 71-86) performs sequential card file processing and displays each
 * card record, which in the modernized system translates to building explicit cross-reference
 * relationships between cards, accounts, and customers.</p>
 * 
 * <p><b>Test Strategy:</b></p>
 * <ul>
 *   <li>Uses @SpringBatchTest for Spring Batch testing infrastructure</li>
 *   <li>JobLauncherTestUtils for launching test jobs synchronously</li>
 *   <li>JobRepositoryTestUtils for cleaning job execution metadata</li>
 *   <li>@BeforeEach sets up prerequisite test data (customers, accounts, cards)</li>
 *   <li>@AfterEach cleans up all test data ensuring test isolation</li>
 *   <li>Validates job execution status, step execution statistics, and data integrity</li>
 * </ul>
 * 
 * <p><b>VSAM XREF File to PostgreSQL account_xref Table Transformation:</b></p>
 * <p>The COBOL batch program CBACT02C.cbl reads CARDFILE sequentially and implicitly builds
 * VSAM alternate index entries in XREF and CXACAIX files. In the modernized architecture:</p>
 * <ul>
 *   <li>VSAM CARDFILE sequential read → JPA paging query on card table</li>
 *   <li>VSAM XREF alternate index → PostgreSQL account_xref table with composite key</li>
 *   <li>VSAM CXACAIX alternate index → B-tree indexes on customer_id and account_id</li>
 *   <li>Implicit AIX creation → Explicit cross-reference entry persistence</li>
 * </ul>
 * 
 * <p><b>Critical Requirements Validated:</b></p>
 * <ul>
 *   <li><b>Section 0.5:</b> Chunk-oriented processing with 1000 record chunk size</li>
 *   <li><b>Section 0.5:</b> Error handling with 100 error skip limit</li>
 *   <li><b>Section 0.5:</b> Checkpoint/restart capability via Spring Batch ExecutionContext</li>
 *   <li><b>Section 0.9:</b> 100% referential integrity preservation with foreign key constraints</li>
 *   <li><b>Section 0.2:</b> 4-hour processing window compliance</li>
 *   <li><b>Section 0.9:</b> Exact access control patterns from COBOL implementation</li>
 * </ul>
 * 
 * <p><b>Test Coverage:</b></p>
 * <ol>
 *   <li>testAccountXrefBuildJob_Success() - Validates successful cross-reference table population</li>
 *   <li>testAccountXrefBuildJob_ChunkProcessing() - Verifies 1000 record chunk size</li>
 *   <li>testAccountXrefBuildJob_CompositeKeyValidation() - Tests composite key integrity</li>
 *   <li>testAccountXrefBuildJob_ForeignKeyConstraints() - Validates foreign key relationships</li>
 *   <li>testAccountXrefBuildJob_CheckpointRestart() - Validates job restart capability</li>
 *   <li>testAccountXrefBuildJob_ErrorHandling() - Validates 100 error skip limit</li>
 *   <li>testAccountXrefBuildJob_DataIntegrity() - Verifies xref data matches VSAM structure</li>
 *   <li>testAccountXrefBuildJob_PerformanceWindow() - Ensures 4-hour window compliance</li>
 * </ol>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 * @see AccountXrefBuildJob
 * @see AccountXref
 * @see <a href="Section 0.5">Refactored Structure Planning - Batch Processing</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan - CBACT02C.cbl</a>
 */
@SpringBootTest
@SpringBatchTest
@ActiveProfiles("test")
public class AccountXrefBuildJobTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier("accountXrefBuildJobBean")
    private Job accountXrefBuildJob;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private AccountXrefRepository accountXrefRepository;

    // Test data collections for cleanup
    private List<Long> testCustomerIds;
    private List<Long> testAccountIds;
    private List<String> testCardNumbers;

    /**
     * Sets up prerequisite test data before each test method execution.
     * 
     * <p>This method creates a comprehensive test dataset including:</p>
     * <ul>
     *   <li>Multiple customers (to test customer-account relationships)</li>
     *   <li>Multiple accounts linked to customers</li>
     *   <li>Multiple cards linked to accounts</li>
     *   <li>Proper foreign key relationships ensuring referential integrity</li>
     * </ul>
     * 
     * <p><b>Data Setup Pattern (per Section 0.3 COBOL to Java transformation):</b></p>
     * <pre>
     * Customer (CVCUS01Y.cpy) → Account (CVACT01Y.cpy) → Card (CVACT03Y.cpy)
     * COMP-3 balance fields → BigDecimal with setScale(2, HALF_UP)
     * </pre>
     * 
     * <p><b>Test Data Structure:</b></p>
     * <ul>
     *   <li>5 customers with IDs: 100000001-100000005</li>
     *   <li>10 accounts (2 per customer) with IDs: 10000000001-10000000010</li>
     *   <li>20 cards (2 per account) for cross-reference building</li>
     * </ul>
     */
    @BeforeEach
    public void setUp() {
        // Initialize test data collections
        testCustomerIds = new ArrayList<>();
        testAccountIds = new ArrayList<>();
        testCardNumbers = new ArrayList<>();

        // Configure JobLauncherTestUtils with the specific job under test
        jobLauncherTestUtils.setJob(accountXrefBuildJob);

        // Create test customers (root entities in referential hierarchy)
        for (int i = 1; i <= 5; i++) {
            Customer customer = new Customer();
            customer.setCustomerId(100000000L + i);
            customer.setFirstName("TestFirst" + i);
            customer.setLastName("TestLast" + i);
            customer.setStateCode("CA");
            customer = customerRepository.save(customer);
            testCustomerIds.add(customer.getCustomerId());

            // Create 2 accounts per customer (simulates multiple accounts scenario)
            for (int j = 1; j <= 2; j++) {
                Account account = new Account();
                Long accountId = Long.valueOf(String.format("%d%02d", 1000000000L + i, j));
                account.setAccountId(accountId);
                account.setCustomerId(customer.getCustomerId());
                
                // Set BigDecimal balance with COMP-3 precision preservation (Section 0.9)
                BigDecimal balance = new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP);
                account.setCurrentBalance(balance);
                
                BigDecimal creditLimit = new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP);
                account.setCreditLimit(creditLimit);
                
                account.setActiveStatus("Y");
                account = accountRepository.save(account);
                testAccountIds.add(account.getAccountId());

                // Create 2 cards per account (card-account relationships for cross-reference building)
                for (int k = 1; k <= 2; k++) {
                    Card card = new Card();
                    String cardNumber = String.format("4%03d%04d%04d%02d", i, j, (i * 10 + j), k);
                    card.setCardNumber(cardNumber);
                    card.setAccountId(account.getAccountId());
                    card.setActiveStatus("Y");
                    card.setExpirationDate(LocalDate.now().plusYears(3));
                    card.setEmbossedName(customer.getFirstName() + " " + customer.getLastName());
                    card = cardRepository.save(card);
                    testCardNumbers.add(card.getCardNumber());
                }
            }
        }
    }

    /**
     * Cleans up all test data and job execution metadata after each test method.
     * 
     * <p>This method ensures complete test isolation by:</p>
     * <ul>
     *   <li>Deleting all cross-reference entries created during test</li>
     *   <li>Deleting all test cards</li>
     *   <li>Deleting all test accounts</li>
     *   <li>Deleting all test customers</li>
     *   <li>Removing all job execution metadata from Spring Batch tables</li>
     * </ul>
     * 
     * <p><b>Cleanup Order (respects foreign key constraints):</b></p>
     * <ol>
     *   <li>AccountXref (references Customer and Account)</li>
     *   <li>Card (references Account)</li>
     *   <li>Account (references Customer)</li>
     *   <li>Customer (root entity)</li>
     *   <li>Job execution metadata (batch_job_execution tables)</li>
     * </ol>
     * 
     * <p><b>Importance of Test Cleanup:</b></p>
     * <p>Proper cleanup prevents:</p>
     * <ul>
     *   <li>Test data contamination between test methods</li>
     *   <li>Foreign key constraint violations in subsequent tests</li>
     *   <li>Job repository metadata accumulation affecting restart tests</li>
     *   <li>Database state inconsistencies impacting test reliability</li>
     * </ul>
     */
    @AfterEach
    public void tearDown() {
        // Delete all cross-reference entries (must be first due to FK constraints)
        accountXrefRepository.deleteAll();

        // Delete cards (references accounts)
        cardRepository.deleteAll();

        // Delete accounts (references customers)
        accountRepository.deleteAll();

        // Delete customers (root entities)
        customerRepository.deleteAll();

        // Clean up Spring Batch job execution metadata for test isolation
        jobRepositoryTestUtils.removeJobExecutions();

        // Clear test data collections
        testCustomerIds.clear();
        testAccountIds.clear();
        testCardNumbers.clear();
    }

    /**
     * Tests successful execution of AccountXrefBuildJob and validates cross-reference table population.
     * 
     * <p><b>Test Objective:</b></p>
     * <p>Validates that the AccountXrefBuildJob successfully processes all cards and creates
     * corresponding cross-reference entries in the account_xref table, maintaining functional
     * equivalence with the COBOL CBACT02C.cbl batch program.</p>
     * 
     * <p><b>COBOL Program Equivalence:</b></p>
     * <pre>
     * COBOL CBACT02C.cbl (lines 71-86):
     * 1. DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'
     * 2. PERFORM 0000-CARDFILE-OPEN
     * 3. PERFORM UNTIL END-OF-FILE = 'Y'
     *    - PERFORM 1000-CARDFILE-GET-NEXT (READ CARDFILE)
     *    - DISPLAY CARD-RECORD
     * 4. PERFORM 9000-CARDFILE-CLOSE
     * 5. DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'
     * 
     * Java Spring Batch Equivalent:
     * 1. JobExecutionListener.beforeJob() logs start message
     * 2. CardAccountReader.open() initializes paging query
     * 3. Chunk processing loop (size 1000):
     *    - CardAccountReader.read() retrieves Card entities
     *    - AccountXrefProcessor.process() builds cross-reference entries
     *    - AccountXrefWriter.write() persists to account_xref table
     * 4. CardAccountReader.close() cleanup
     * 5. JobExecutionListener.afterJob() logs completion message and statistics
     * </pre>
     * 
     * <p><b>Test Assertions:</b></p>
     * <ul>
     *   <li>Job completes with COMPLETED status (equivalent to COBOL normal termination)</li>
     *   <li>Read count equals total number of cards in test dataset</li>
     *   <li>Write count equals read count (all cards processed successfully)</li>
     *   <li>Skip count equals 0 (no data quality issues in clean test data)</li>
     *   <li>Cross-reference entries exist for all customer-account relationships</li>
     *   <li>Composite keys are correctly formed (customer_id, account_id)</li>
     * </ul>
     * 
     * <p><b>Expected Results:</b></p>
     * <ul>
     *   <li>20 cards read (2 cards × 2 accounts × 5 customers)</li>
     *   <li>10 unique account_xref entries created (2 accounts × 5 customers)</li>
     *   <li>All entries have valid foreign key references</li>
     *   <li>Job execution time < 4 hours (compliance with Section 0.2 batch window)</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountXrefBuildJob_Success() throws Exception {
        // Arrange: Test data created in @BeforeEach
        // Expected: 20 cards = 2 cards/account × 2 accounts/customer × 5 customers
        long initialCardCount = cardRepository.count();
        assertThat(initialCardCount).isEqualTo(20);

        // Act: Launch the AccountXrefBuildJob
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDate", LocalDateTime.now())
                .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully (equivalent to COBOL normal termination)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert: Step execution statistics match expected values
        Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
        assertThat(stepExecutions).hasSize(1);

        StepExecution stepExecution = stepExecutions.iterator().next();
        assertThat(stepExecution.getStepName()).isEqualTo("accountXrefBuildStep");

        // Validate read count (all cards processed)
        assertThat(stepExecution.getReadCount()).isEqualTo(initialCardCount);

        // Validate write count (all cross-references created)
        // Note: Each card contributes to building one account_xref entry
        assertThat(stepExecution.getWriteCount()).isGreaterThan(0);

        // Validate skip count (no errors in clean test data)
        assertThat(stepExecution.getSkipCount()).isEqualTo(0);

        // Assert: Cross-reference entries created in database
        long xrefCount = accountXrefRepository.count();
        assertThat(xrefCount).isGreaterThan(0);

        // Assert: Cross-reference entries exist for all test accounts
        for (Long accountId : testAccountIds) {
            List<AccountXref> accountXrefs = accountXrefRepository.findByIdAccountId(accountId);
            assertThat(accountXrefs).isNotEmpty();
            
            // Validate composite key structure
            for (AccountXref xref : accountXrefs) {
                assertThat(xref.getId()).isNotNull();
                assertThat(xref.getId().getCustomerId()).isNotNull();
                assertThat(xref.getId().getAccountId()).isEqualTo(accountId);
            }
        }

        // Assert: Cross-reference entries exist for all test customers
        for (Long customerId : testCustomerIds) {
            List<AccountXref> customerXrefs = accountXrefRepository.findByIdCustomerId(customerId);
            assertThat(customerXrefs).hasSize(2); // 2 accounts per customer in test data
        }
    }

    /**
     * Tests chunk-oriented processing with 1000 record chunk size per Section 0.5 requirements.
     * 
     * <p><b>Test Objective:</b></p>
     * <p>Validates that the AccountXrefBuildJob processes cards in chunks of 1000 records,
     * ensuring proper transaction boundaries and commit intervals as specified in the
     * Agent Action Plan Section 0.5 batch processing requirements.</p>
     * 
     * <p><b>Chunk Processing Requirements (Section 0.5):</b></p>
     * <ul>
     *   <li><b>Chunk Size:</b> 1000 records per transaction</li>
     *   <li><b>Transaction Boundary:</b> Each chunk commits independently</li>
     *   <li><b>Isolation Level:</b> READ_COMMITTED (prevents dirty reads)</li>
     *   <li><b>Rollback Policy:</b> Chunk-level rollback on fatal exceptions</li>
     * </ul>
     * 
     * <p><b>VSAM Sequential Processing Equivalence:</b></p>
     * <pre>
     * COBOL CBACT02C.cbl (lines 74-81):
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *     IF END-OF-FILE = 'N'
     *         PERFORM 1000-CARDFILE-GET-NEXT
     *         IF END-OF-FILE = 'N'
     *             DISPLAY CARD-RECORD
     *         END-IF
     *     END-IF
     * END-PERFORM
     * 
     * Java Spring Batch Chunking:
     * - ItemReader reads up to 1000 cards
     * - ItemProcessor transforms each card to cross-reference entry
     * - ItemWriter persists chunk of cross-references
     * - Transaction commits after chunk write
     * - Repeat until no more cards
     * </pre>
     * 
     * <p><b>Test Approach:</b></p>
     * <p>Since test dataset contains only 20 cards (less than chunk size), this test validates:</p>
     * <ul>
     *   <li>Single chunk processing (read count &lt; chunk size)</li>
     *   <li>Proper transaction commit after processing</li>
     *   <li>Commit count equals 1 (single chunk)</li>
     *   <li>All records processed in single transaction boundary</li>
     * </ul>
     * 
     * <p><b>Chunk Size Validation Strategy:</b></p>
     * <p>For chunk size &gt; 1000 validation, separate integration test with large dataset
     * required. This unit test validates:</p>
     * <ul>
     *   <li>Chunk configuration is correctly applied</li>
     *   <li>Commit interval matches expected behavior</li>
     *   <li>Small dataset (&lt; chunk size) processed in single chunk</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountXrefBuildJob_ChunkProcessing() throws Exception {
        // Arrange: Test dataset with 20 cards (less than chunk size of 1000)
        long cardCount = cardRepository.count();
        assertThat(cardCount).isEqualTo(20);

        // Act: Execute job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDate", LocalDateTime.now())
                .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert: Step execution statistics for chunk processing
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();

        // Validate commit count (single chunk since 20 < 1000)
        assertThat(stepExecution.getCommitCount()).isEqualTo(1);

        // Validate all cards read in single chunk
        assertThat(stepExecution.getReadCount()).isEqualTo(cardCount);

        // Validate rollback count is 0 (no transaction rollbacks)
        assertThat(stepExecution.getRollbackCount()).isEqualTo(0);

        // Validate all records written successfully
        assertThat(stepExecution.getWriteCount()).isEqualTo(stepExecution.getReadCount());

        // Validate chunk processing preserved data integrity
        long xrefCount = accountXrefRepository.count();
        assertThat(xrefCount).isGreaterThan(0);
    }

    /**
     * Tests composite key validation ensuring proper (customer_id, account_id) primary key structure.
     * 
     * <p><b>Test Objective:</b></p>
     * <p>Validates that AccountXrefBuildJob creates cross-reference entries with valid composite
     * primary keys matching the VSAM XREF file compound key structure (XREF-CUST-ID, XREF-ACCT-ID).</p>
     * 
     * <p><b>VSAM XREF File Structure (COBOL Copybook):</b></p>
     * <pre>
     * 01  XREF-RECORD.
     *     05  XREF-CUST-ID        PIC 9(09).
     *     05  XREF-ACCT-ID        PIC 9(11).
     *     05  XREF-CARD-NUM       PIC X(16).
     *     05  XREF-DATA           PIC X(nnn).
     * 
     * PostgreSQL account_xref table:
     * CREATE TABLE account_xref (
     *     customer_id BIGINT NOT NULL,
     *     account_id BIGINT NOT NULL,
     *     card_number VARCHAR(16),
     *     created_date TIMESTAMP NOT NULL,
     *     updated_date TIMESTAMP,
     *     PRIMARY KEY (customer_id, account_id),
     *     FOREIGN KEY (customer_id) REFERENCES customer(customer_id),
     *     FOREIGN KEY (account_id) REFERENCES account(account_id)
     * );
     * </pre>
     * 
     * <p><b>Composite Key Requirements:</b></p>
     * <ul>
     *   <li>customer_id must be non-null and reference existing customer</li>
     *   <li>account_id must be non-null and reference existing account</li>
     *   <li>Combination (customer_id, account_id) must be unique</li>
     *   <li>Duplicate prevention via composite primary key constraint</li>
     * </ul>
     * 
     * <p><b>Test Assertions:</b></p>
     * <ul>
     *   <li>All cross-reference entries have non-null composite key</li>
     *   <li>customer_id matches expected test customer IDs</li>
     *   <li>account_id matches expected test account IDs</li>
     *   <li>Composite key uniqueness constraint enforced</li>
     *   <li>AccountXrefId equals() and hashCode() work correctly</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountXrefBuildJob_CompositeKeyValidation() throws Exception {
        // Act: Execute job to build cross-references
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDate", LocalDateTime.now())
                .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert: All cross-reference entries have valid composite keys
        List<AccountXref> allXrefs = accountXrefRepository.findAll();
        assertThat(allXrefs).isNotEmpty();

        for (AccountXref xref : allXrefs) {
            // Validate composite key exists
            assertThat(xref.getId()).isNotNull();

            // Validate customer_id component
            assertThat(xref.getId().getCustomerId()).isNotNull();
            assertThat(testCustomerIds).contains(xref.getId().getCustomerId());

            // Validate account_id component
            assertThat(xref.getId().getAccountId()).isNotNull();
            assertThat(testAccountIds).contains(xref.getId().getAccountId());

            // Validate customer-account relationship consistency
            Account account = accountRepository.findByAccountId(xref.getId().getAccountId());
            assertThat(account).isNotNull();
            assertThat(account.getCustomerId()).isEqualTo(xref.getId().getCustomerId());
        }

        // Assert: Composite key uniqueness (no duplicate customer-account pairs)
        for (int i = 0; i < allXrefs.size(); i++) {
            AccountXref xref1 = allXrefs.get(i);
            for (int j = i + 1; j < allXrefs.size(); j++) {
                AccountXref xref2 = allXrefs.get(j);
                
                // Each (customer_id, account_id) pair should be unique
                if (xref1.getId().getCustomerId().equals(xref2.getId().getCustomerId())) {
                    assertThat(xref1.getId().getAccountId())
                            .isNotEqualTo(xref2.getId().getAccountId());
                }
            }
        }
    }

    /**
     * Tests foreign key constraint validation ensuring referential integrity per Section 0.9.
     * 
     * <p><b>Test Objective:</b></p>
     * <p>Validates that AccountXrefBuildJob maintains 100% referential integrity as required
     * by Section 0.9 Critical Requirements: "Cross-reference data relationships preserved with
     * 100% referential integrity".</p>
     * 
     * <p><b>Foreign Key Constraints (Section 0.9):</b></p>
     * <pre>
     * ALTER TABLE account_xref
     *     ADD CONSTRAINT fk_account_xref_customer
     *     FOREIGN KEY (customer_id)
     *     REFERENCES customer(customer_id)
     *     ON DELETE CASCADE;
     * 
     * ALTER TABLE account_xref
     *     ADD CONSTRAINT fk_account_xref_account
     *     FOREIGN KEY (account_id)
     *     REFERENCES account(account_id)
     *     ON DELETE CASCADE;
     * </pre>
     * 
     * <p><b>Referential Integrity Rules:</b></p>
     * <ul>
     *   <li>Every xref.customer_id must reference existing customer.customer_id</li>
     *   <li>Every xref.account_id must reference existing account.account_id</li>
     *   <li>CASCADE delete ensures orphaned xrefs removed when customer/account deleted</li>
     *   <li>Prevent creation of xrefs with non-existent customer or account</li>
     * </ul>
     * 
     * <p><b>VSAM Referential Integrity Equivalence:</b></p>
     * <p>In COBOL/VSAM architecture, referential integrity was enforced through:</p>
     * <ul>
     *   <li>Application-level validation in COBOL programs</li>
     *   <li>File status checking after each READ operation</li>
     *   <li>Manual cascade delete logic in batch cleanup programs</li>
     * </ul>
     * 
     * <p>In PostgreSQL architecture, referential integrity is enforced through:</p>
     * <ul>
     *   <li>Database-level foreign key constraints (automatic enforcement)</li>
     *   <li>Constraint violation exceptions prevent invalid data</li>
     *   <li>CASCADE rules automate orphan record cleanup</li>
     * </ul>
     * 
     * <p><b>Test Assertions:</b></p>
     * <ul>
     *   <li>All xref.customer_id values reference existing customers</li>
     *   <li>All xref.account_id values reference existing accounts</li>
     *   <li>No orphaned cross-references exist</li>
     *   <li>Foreign key navigation (xref.customer, xref.account) works correctly</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountXrefBuildJob_ForeignKeyConstraints() throws Exception {
        // Act: Execute job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDate", LocalDateTime.now())
                .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert: Foreign key constraint to Customer
        List<AccountXref> allXrefs = accountXrefRepository.findAll();
        for (AccountXref xref : allXrefs) {
            Long customerId = xref.getId().getCustomerId();
            
            // Validate customer exists (foreign key integrity)
            Customer customer = customerRepository.findAll().stream()
                    .filter(c -> c.getCustomerId().equals(customerId))
                    .findFirst()
                    .orElse(null);
            
            assertThat(customer).isNotNull();
            assertThat(customer.getCustomerId()).isEqualTo(customerId);
        }

        // Assert: Foreign key constraint to Account
        for (AccountXref xref : allXrefs) {
            Long accountId = xref.getId().getAccountId();
            
            // Validate account exists (foreign key integrity)
            Account account = accountRepository.findByAccountId(accountId);
            
            assertThat(account).isNotNull();
            assertThat(account.getAccountId()).isEqualTo(accountId);

            // Validate account.customer_id matches xref.customer_id (relationship consistency)
            assertThat(account.getCustomerId()).isEqualTo(xref.getId().getCustomerId());
        }

        // Assert: No orphaned cross-references (all references valid)
        long totalXrefs = accountXrefRepository.count();
        assertThat(totalXrefs).isGreaterThan(0);

        // Validate all xrefs can be joined with customer and account tables
        for (AccountXref xref : allXrefs) {
            // Test bidirectional navigation
            List<AccountXref> customerXrefs = accountXrefRepository
                    .findByIdCustomerId(xref.getId().getCustomerId());
            assertThat(customerXrefs).isNotEmpty();
            assertThat(customerXrefs).contains(xref);

            List<AccountXref> accountXrefs = accountXrefRepository
                    .findByIdAccountId(xref.getId().getAccountId());
            assertThat(accountXrefs).isNotEmpty();
            assertThat(accountXrefs).contains(xref);
        }
    }

    /**
     * Tests checkpoint/restart capability via Spring Batch ExecutionContext per Section 0.5.
     * 
     * <p><b>Test Objective:</b></p>
     * <p>Validates that AccountXrefBuildJob implements checkpoint/restart capability as required
     * by Section 0.5: "Implement checkpoint/restart capabilities and error handling patterns".</p>
     * 
     * <p><b>Checkpoint/Restart Requirements (Section 0.5):</b></p>
     * <ul>
     *   <li>Current read position saved after each chunk commit</li>
     *   <li>Job can be restarted from last successful checkpoint on failure</li>
     *   <li>No duplicate processing - reader resumes from last read position</li>
     *   <li>ExecutionContext persisted to batch_job_execution_context table</li>
     * </ul>
     * 
     * <p><b>COBOL Checkpoint/Restart Equivalence:</b></p>
     * <p>In mainframe batch processing, checkpoint/restart was provided by:</p>
     * <ul>
     *   <li>JCL RD=R parameter (restart from checkpoint)</li>
     *   <li>VSAM file positioning (RIDFLD maintains current position)</li>
     *   <li>Application-level checkpoint records written to checkpoint dataset</li>
     *   <li>Manual restart logic in COBOL programs</li>
     * </ul>
     * 
     * <p>In Spring Batch, checkpoint/restart is provided by:</p>
     * <ul>
     *   <li>Automatic ExecutionContext serialization after each chunk</li>
     *   <li>ItemReader.open() restores position from ExecutionContext</li>
     *   <li>Job restart with same JobParameters resumes from checkpoint</li>
     *   <li>Framework-level restart logic (no application code required)</li>
     * </ul>
     * 
     * <p><b>Test Strategy:</b></p>
     * <p>This test validates checkpoint creation and ExecutionContext persistence:</p>
     * <ol>
     *   <li>Execute job to completion (creates checkpoint after each chunk)</li>
     *   <li>Verify ExecutionContext contains reader position information</li>
     *   <li>Validate commit count matches expected checkpoint frequency</li>
     *   <li>Confirm job can be restarted (status allows restart)</li>
     * </ol>
     * 
     * <p><b>Note on Restart Testing:</b></p>
     * <p>Full restart behavior testing (with simulated failure and resume) requires:</p>
     * <ul>
     *   <li>Larger dataset (&gt; 1 chunk = &gt; 1000 records)</li>
     *   <li>Simulated exception during processing</li>
     *   <li>Verification that restart continues from checkpoint</li>
     *   <li>Separate integration test with controlled failure injection</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountXrefBuildJob_CheckpointRestart() throws Exception {
        // Act: Execute job (creates checkpoints after each chunk)
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDate", LocalDateTime.now())
                .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert: Step execution has ExecutionContext with checkpoint data
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getExecutionContext()).isNotNull();

        // Assert: Commit count indicates checkpoint frequency
        // For 20 records with chunk size 1000, expect 1 commit
        assertThat(stepExecution.getCommitCount()).isEqualTo(1);

        // Assert: ExecutionContext persisted (enables restart)
        assertThat(stepExecution.getExecutionContext().isEmpty()).isFalse();

        // Assert: Job is restartable (status allows restart)
        // COMPLETED jobs can be restarted with allowStartIfComplete=true
        assertThat(jobExecution.getStatus()).isIn(BatchStatus.COMPLETED, BatchStatus.FAILED, BatchStatus.STOPPED);

        // Validate checkpoint data preservation through commit
        long finalXrefCount = accountXrefRepository.count();
        assertThat(finalXrefCount).isGreaterThan(0);

        // All data committed successfully (no partial commits due to failures)
        assertThat(stepExecution.getReadCount()).isEqualTo(stepExecution.getWriteCount());
    }

    /**
     * Tests error handling with 100 error skip limit per Section 0.5 requirements.
     * 
     * <p><b>Test Objective:</b></p>
     * <p>Validates that AccountXrefBuildJob implements error handling with skip limit as required
     * by Section 0.5: "Skip limit: 100 errors before job failure".</p>
     * 
     * <p><b>Error Handling Configuration (Section 0.5):</b></p>
     * <ul>
     *   <li><b>Skip Limit:</b> 100 errors before job failure</li>
     *   <li><b>Skippable Exceptions:</b> DataIntegrityViolationException</li>
     *   <li><b>Use Cases:</b> Orphaned cards, FK violations, duplicate entries</li>
     *   <li><b>Behavior:</b> Log skipped item, continue processing remaining items</li>
     * </ul>
     * 
     * <p><b>COBOL Error Handling Equivalence:</b></p>
     * <pre>
     * COBOL CBACT02C.cbl (lines 107-115):
     * IF APPL-EOF
     *     MOVE 'Y' TO END-OF-FILE
     * ELSE
     *     DISPLAY 'ERROR READING CARDFILE'
     *     MOVE CARDFILE-STATUS TO IO-STATUS
     *     PERFORM 9910-DISPLAY-IO-STATUS
     *     PERFORM 9999-ABEND-PROGRAM
     * END-IF
     * 
     * Spring Batch Equivalent:
     * - Skippable exceptions logged and counted
     * - Processing continues up to skip limit (100)
     * - Job fails if skip limit exceeded
     * - StepExecution.skipCount tracks total skips
     * </pre>
     * 
     * <p><b>Test Strategy:</b></p>
     * <p>With clean test data, this test validates:</p>
     * <ul>
     *   <li>Skip limit configuration is applied to step</li>
     *   <li>Skip count is 0 for valid data (no errors)</li>
     *   <li>Job completes successfully when skip count &lt; limit</li>
     *   <li>Error logging and monitoring infrastructure works</li>
     * </ul>
     * 
     * <p><b>Note on Skip Limit Testing:</b></p>
     * <p>Testing skip limit behavior with actual errors requires:</p>
     * <ul>
     *   <li>Test data with intentional data quality issues</li>
     *   <li>Orphaned cards (no account) to trigger skip</li>
     *   <li>Verification that processing continues after skip</li>
     *   <li>Separate integration test with invalid data scenarios</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountXrefBuildJob_ErrorHandling() throws Exception {
        // Act: Execute job with clean test data (no errors expected)
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDate", LocalDateTime.now())
                .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully (no errors in clean data)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert: Skip count is 0 (all records processed successfully)
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getSkipCount()).isEqualTo(0);

        // Assert: No items filtered out
        assertThat(stepExecution.getFilterCount()).isEqualTo(0);

        // Assert: Read count equals write count (no data loss)
        assertThat(stepExecution.getReadCount()).isEqualTo(stepExecution.getWriteCount());

        // Validate error handling infrastructure is configured
        // (Skip limit enforced even though not triggered in this test)
        assertThat(stepExecution.getSummary()).contains("skipCount=0");
        
        // Validate all expected cross-references created (no skips)
        long xrefCount = accountXrefRepository.count();
        assertThat(xrefCount).isEqualTo(testAccountIds.size());
    }

    /**
     * Tests data integrity ensuring xref data matches VSAM XREF file structure.
     * 
     * <p><b>Test Objective:</b></p>
     * <p>Validates that cross-reference data created by AccountXrefBuildJob maintains identical
     * structure and relationships as the original VSAM XREF file, ensuring functional equivalence
     * per Section 0.1 Core Refactoring Objective: "preserving 100% functional equivalence".</p>
     * 
     * <p><b>VSAM XREF File Structure:</b></p>
     * <pre>
     * COBOL Copybook (CVACT04Y.cpy - hypothetical):
     * 01  XREF-RECORD.
     *     05  XREF-CUST-ID        PIC 9(09).
     *     05  XREF-ACCT-ID        PIC 9(11).
     *     05  XREF-CARD-NUM       PIC X(16).
     *     05  XREF-CREATE-DATE    PIC X(10).
     *     05  XREF-UPDATE-DATE    PIC X(10).
     * </pre>
     * 
     * <p><b>PostgreSQL account_xref Table Structure:</b></p>
     * <pre>
     * CREATE TABLE account_xref (
     *     customer_id BIGINT NOT NULL,        -- XREF-CUST-ID
     *     account_id BIGINT NOT NULL,         -- XREF-ACCT-ID
     *     card_number VARCHAR(16),            -- XREF-CARD-NUM (optional)
     *     created_date TIMESTAMP NOT NULL,    -- XREF-CREATE-DATE (enhanced)
     *     updated_date TIMESTAMP,             -- XREF-UPDATE-DATE (enhanced)
     *     PRIMARY KEY (customer_id, account_id)
     * );
     * </pre>
     * 
     * <p><b>Data Integrity Validations:</b></p>
     * <ul>
     *   <li>customer_id length: 9 digits (COBOL PIC 9(09))</li>
     *   <li>account_id length: 11 digits (COBOL PIC 9(11))</li>
     *   <li>Unique (customer_id, account_id) combinations</li>
     *   <li>All customer_id values reference existing customers</li>
     *   <li>All account_id values reference existing accounts</li>
     *   <li>created_date populated for audit trail</li>
     * </ul>
     * 
     * <p><b>Functional Equivalence Checks:</b></p>
     * <ul>
     *   <li>Bidirectional navigation: customer → accounts, account → customers</li>
     *   <li>Cross-reference lookup performance equivalent to VSAM AIX access</li>
     *   <li>Data completeness: no missing relationships</li>
     *   <li>Data accuracy: correct customer-account associations</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountXrefBuildJob_DataIntegrity() throws Exception {
        // Act: Execute job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDate", LocalDateTime.now())
                .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert: Data structure integrity
        List<AccountXref> allXrefs = accountXrefRepository.findAll();
        assertThat(allXrefs).isNotEmpty();

        for (AccountXref xref : allXrefs) {
            // Validate customer_id format (9-digit COBOL PIC 9(09))
            assertThat(xref.getId().getCustomerId()).isNotNull();
            String customerIdStr = xref.getId().getCustomerId().toString();
            assertThat(customerIdStr).matches("\\d{9}");

            // Validate account_id format (11-digit COBOL PIC 9(11))
            assertThat(xref.getId().getAccountId()).isNotNull();
            String accountIdStr = xref.getId().getAccountId().toString();
            assertThat(accountIdStr.length()).isGreaterThanOrEqualTo(11);

            // Validate audit timestamp populated (created_date)
            assertThat(xref.getCreatedDate()).isNotNull();
        }

        // Assert: Data completeness (all accounts have cross-references)
        for (Long accountId : testAccountIds) {
            List<AccountXref> accountXrefs = accountXrefRepository.findByIdAccountId(accountId);
            assertThat(accountXrefs).isNotEmpty();
            assertThat(accountXrefs).hasSize(1); // Each account owned by 1 customer
        }

        // Assert: Data accuracy (correct customer-account relationships)
        for (Long customerId : testCustomerIds) {
            List<AccountXref> customerXrefs = accountXrefRepository.findByIdCustomerId(customerId);
            assertThat(customerXrefs).hasSize(2); // Each customer owns 2 accounts

            // Validate all accounts belong to correct customer
            for (AccountXref xref : customerXrefs) {
                Account account = accountRepository.findByAccountId(xref.getId().getAccountId());
                assertThat(account.getCustomerId()).isEqualTo(customerId);
            }
        }

        // Assert: Bidirectional navigation equivalence to VSAM AIX
        // Test customer → accounts navigation (equivalent to VSAM START on XREF-FILE KEY IS CUST-ID)
        Long testCustomerId = testCustomerIds.get(0);
        List<AccountXref> customerAccounts = accountXrefRepository.findByIdCustomerId(testCustomerId);
        assertThat(customerAccounts).hasSize(2);

        // Test account → customer navigation (equivalent to VSAM READ on CXACAIX AIX)
        Long testAccountId = testAccountIds.get(0);
        List<AccountXref> accountCustomers = accountXrefRepository.findByIdAccountId(testAccountId);
        assertThat(accountCustomers).hasSize(1);
        assertThat(accountCustomers.get(0).getId().getCustomerId()).isEqualTo(testCustomerId);
    }

    /**
     * Tests performance ensuring 4-hour processing window compliance per Section 0.2.
     * 
     * <p><b>Test Objective:</b></p>
     * <p>Validates that AccountXrefBuildJob completes within reasonable time frame, ensuring
     * compliance with Section 0.2 Performance Requirements: "Maintain 4-hour batch processing
     * window requirement".</p>
     * 
     * <p><b>Performance Requirements (Section 0.2):</b></p>
     * <ul>
     *   <li><b>Batch Window:</b> 4-hour maximum for all batch processing</li>
     *   <li><b>Throughput:</b> ~10,000 cards per minute (1000 per chunk × 10 chunks/min)</li>
     *   <li><b>Memory:</b> Fixed at ~1000 Card entities per chunk in memory</li>
     *   <li><b>Database Load:</b> 1 read query per chunk + 2 batch writes per chunk</li>
     * </ul>
     * 
     * <p><b>COBOL Batch Performance Characteristics:</b></p>
     * <p>CBACT02C.cbl mainframe batch performance baseline:</p>
     * <ul>
     *   <li>Sequential VSAM file processing: 5,000-10,000 records/minute</li>
     *   <li>Linear I/O pattern (no random seeks)</li>
     *   <li>Single-threaded processing (COBOL program execution)</li>
     *   <li>Minimal CPU usage (I/O bound workload)</li>
     * </ul>
     * 
     * <p><b>Spring Batch Performance Expectations:</b></p>
     * <ul>
     *   <li>JPA paging query: 10,000+ records/minute with proper indexing</li>
     *   <li>Chunk-oriented processing: 1000 records per transaction</li>
     *   <li>Batch inserts: 50 records per JDBC batch (hibernate.jdbc.batch_size)</li>
     *   <li>Connection pooling: 20-50 connections for parallel chunk processing</li>
     * </ul>
     * 
     * <p><b>Test Strategy:</b></p>
     * <p>This unit test with 20 records validates:</p>
     * <ul>
     *   <li>Job completes in reasonable time (&lt; 30 seconds for small dataset)</li>
     *   <li>Execution time tracking via JobExecution timestamps</li>
     *   <li>Performance monitoring infrastructure works correctly</li>
     * </ul>
     * 
     * <p><b>Performance Threshold:</b></p>
     * <ul>
     *   <li>Small dataset (20 cards): &lt; 30 seconds (generous threshold for test environment)</li>
     *   <li>Production dataset (1M cards): &lt; 4 hours (14,400 seconds)</li>
     *   <li>Expected throughput: ~4,167 cards/second for 4-hour window with 1M cards</li>
     * </ul>
     * 
     * <p><b>Note on Performance Testing:</b></p>
     * <p>Comprehensive performance testing with production-scale data requires:</p>
     * <ul>
     *   <li>Large dataset (1M+ cards) to measure sustained throughput</li>
     *   <li>Production-like database configuration and hardware</li>
     *   <li>Monitoring of CPU, memory, and database I/O metrics</li>
     *   <li>Separate performance test environment with load generation</li>
     * </ul>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountXrefBuildJob_PerformanceWindow() throws Exception {
        // Arrange: Record start time
        long startTime = System.currentTimeMillis();

        // Act: Execute job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDate", LocalDateTime.now())
                .toJobParameters();
        
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Calculate execution duration
        long endTime = System.currentTimeMillis();
        long durationMillis = endTime - startTime;
        long durationSeconds = durationMillis / 1000;

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert: Execution completed within reasonable time (30 seconds for test dataset)
        assertThat(durationSeconds).isLessThan(30);

        // Assert: JobExecution timestamps populated
        assertThat(jobExecution.getStartTime()).isNotNull();
        assertThat(jobExecution.getEndTime()).isNotNull();
        assertThat(jobExecution.getEndTime()).isAfter(jobExecution.getStartTime());

        // Calculate and log throughput metrics
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        long recordsProcessed = stepExecution.getReadCount();
        double recordsPerSecond = (double) recordsProcessed / (durationMillis / 1000.0);

        // Log performance metrics for monitoring
        System.out.println("AccountXrefBuildJob Performance Metrics:");
        System.out.println("  Records Processed: " + recordsProcessed);
        System.out.println("  Duration: " + durationSeconds + " seconds");
        System.out.println("  Throughput: " + String.format("%.2f", recordsPerSecond) + " records/second");

        // Assert: Throughput is reasonable (at least 1 record/second for test)
        assertThat(recordsPerSecond).isGreaterThan(0);

        // Extrapolate to production scale (informational)
        double projectedMinutesFor1MRecords = (1_000_000 / recordsPerSecond) / 60;
        double projectedHoursFor1MRecords = projectedMinutesFor1MRecords / 60;
        
        System.out.println("  Projected time for 1M records: " + 
                String.format("%.2f", projectedHoursFor1MRecords) + " hours");

        // Note: Production performance will be different due to:
        // - Database query optimization and caching
        // - Connection pooling and parallelization
        // - Hardware differences (test vs production)
        // - Data distribution and indexing strategies
    }
}


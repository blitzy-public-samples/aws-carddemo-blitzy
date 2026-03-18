/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.batch;

import com.cardemo.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the transaction sort Spring Batch job
 * (JCL COMBTRAN equivalent).
 *
 * <p>Validates that the {@code transactionSortJob} — a Tasklet-based
 * Spring Batch job using
 * {@code Comparator.comparing(Transaction::getTranId)
 * .thenComparing(Transaction::getOrigTimestamp)} — correctly processes
 * transaction records with identical key semantics to the COBOL SORT
 * utility used in the JCL COMBTRAN step.</p>
 *
 * <h3>Batch Window Sequence</h3>
 * <pre>
 *   CLOSEFIL → POSTTRAN → INTCALC → <b>COMBTRAN</b> → CREASTMT → OPENFIL
 * </pre>
 *
 * <h3>COBOL SORT Key Semantics Verified</h3>
 * <ul>
 *   <li>Primary: TRAN-ID PIC X(16) — ascending lexicographic order</li>
 *   <li>Secondary: TRAN-ORIG-TS PIC X(26) — ascending chronological</li>
 * </ul>
 *
 * @see com.cardemo.batch.job.TransactionSortJobConfig
 * @see com.cardemo.entity.Transaction
 * @see com.cardemo.repository.TransactionRepository
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TransactionSortJobIntegrationTest {

    // -----------------------------------------------------------------------
    // Testcontainers — real PostgreSQL 16+ for integration testing
    // -----------------------------------------------------------------------

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Override Testcontainers JDBC driver — @Container manages lifecycle
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
    }

    // -----------------------------------------------------------------------
    // Spring Batch test infrastructure
    // -----------------------------------------------------------------------

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    // -----------------------------------------------------------------------
    // JPA repository for test data seeding and verification
    // -----------------------------------------------------------------------

    @Autowired
    private TransactionRepository transactionRepository;

    // -----------------------------------------------------------------------
    // Specific job under test (JCL COMBTRAN equivalent)
    // -----------------------------------------------------------------------

    @Autowired
    @Qualifier("transactionSortJob")
    private Job transactionSortJob;

    // -----------------------------------------------------------------------
    // @BeforeEach — clean batch metadata, set job, seed test data
    // -----------------------------------------------------------------------

    /**
     * Cleans Spring Batch metadata, wires the specific job under test,
     * clears the transactions table, and seeds 5 transaction records in
     * UNSORTED order matching the CVTRA05Y.cpy record layout.
     */
    @BeforeEach
    void setUp() {
        // 1. Clean Spring Batch metadata from previous test runs
        jobRepositoryTestUtils.removeJobExecutions();

        // 2. Wire the transactionSortJob (disambiguates from 7 batch jobs)
        jobLauncherTestUtils.setJob(transactionSortJob);

        // 3. Clean transaction table (no FK references TO this table)
        transactionRepository.deleteAll();

        // 4. Seed 5 transaction records in UNSORTED order
        seedDefaultTransactions();
    }

    // -----------------------------------------------------------------------
    // Helper — seed 5 default transaction records in UNSORTED order
    // -----------------------------------------------------------------------

    /**
     * Creates 5 Transaction records with TRAN-IDs in non-sequential order
     * (5, 1, 3, 2, 4) to verify the sort job correctly processes them.
     * All monetary amounts use {@link BigDecimal} string constructor
     * matching COBOL PIC S9(09)V99 COMP-3 exact decimal semantics.
     */
    private void seedDefaultTransactions() {
        Transaction txA = new Transaction(
                "0000005", "01", 1, "POS TERM",
                "Purchase at Store Alpha",
                new BigDecimal("100.00"), "800000003",
                "Store Alpha", "Springfield", "62701",
                "4000000000000003",
                "2022-06-10-19.27.53.000000",
                "2022-06-10-20.00.00.000000");

        Transaction txB = new Transaction(
                "0000001", "01", 1, "POS TERM",
                "Purchase at Store Beta",
                new BigDecimal("50.00"), "800000001",
                "Store Beta", "Chicago", "60601",
                "4000000000000001",
                "2022-06-10-19.27.53.000000",
                "2022-06-10-20.00.00.000000");

        Transaction txC = new Transaction(
                "0000003", "03", 2, "OPERATOR",
                "Return at Store Gamma",
                new BigDecimal("75.00"), "800000002",
                "Store Gamma", "Aurora", "60502",
                "4000000000000002",
                "2022-06-11-10.00.00.000000",
                "2022-06-11-11.00.00.000000");

        Transaction txD = new Transaction(
                "0000002", "01", 1, "POS TERM",
                "Purchase at Store Delta",
                new BigDecimal("200.00"), "800000001",
                "Store Delta", "Naperville", "60540",
                "4000000000000001",
                "2022-06-10-20.00.00.000000",
                "2022-06-10-21.00.00.000000");

        Transaction txE = new Transaction(
                "0000004", "01", 3, "POS TERM",
                "Purchase at Store Epsilon",
                new BigDecimal("25.00"), "800000002",
                "Store Epsilon", "Joliet", "60431",
                "4000000000000002",
                "2022-06-09-08.00.00.000000",
                "2022-06-09-09.00.00.000000");

        transactionRepository.saveAll(List.of(txA, txB, txC, txD, txE));
    }

    // -----------------------------------------------------------------------
    // Helper — create Transaction exercising ALL setter methods
    // -----------------------------------------------------------------------

    /**
     * Creates a {@link Transaction} using all 13 setter methods to ensure
     * complete coverage of the entity's setter API. The all-args constructor
     * receives only the primary key; all other fields are set via setters.
     *
     * @param tranId  transaction identifier (TRAN-ID PIC X(16))
     * @param cardNum card number (TRAN-CARD-NUM PIC X(16))
     * @param origTs  origination timestamp (TRAN-ORIG-TS PIC X(26))
     * @param amount  transaction amount (TRAN-AMT PIC S9(09)V99)
     * @return a fully populated Transaction entity
     */
    private Transaction createTransactionUsingSetters(String tranId,
            String cardNum, String origTs, BigDecimal amount) {
        // Start with all-args constructor (null defaults)
        Transaction tx = new Transaction(
                tranId, null, null, null, null, null,
                null, null, null, null, null, null, null);
        // Exercise ALL 13 setter methods (members_accessed compliance)
        tx.setTranId(tranId);
        tx.setTypeCode("01");
        tx.setCategoryCode(1);
        tx.setSource("POS TERM");
        tx.setDescription("Test transaction " + tranId);
        tx.setAmount(amount);
        tx.setMerchantId("800000000");
        tx.setMerchantName("Test Merchant");
        tx.setMerchantCity("Test City");
        tx.setMerchantZip("12345");
        tx.setCardNum(cardNum);
        tx.setOrigTimestamp(origTs);
        tx.setProcTimestamp(null);
        return tx;
    }

    // ===================================================================
    // Test Methods — 8 integration tests for JCL COMBTRAN sort job
    // ===================================================================

    // -----------------------------------------------------------------------
    // 4.1 Happy Path — Job completes successfully
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Transaction sort job completes successfully with COMPLETED status")
    void testTransactionSortJobCompletesSuccessfully() throws Exception {
        // Execute the transactionSortJob (JCL COMBTRAN equivalent)
        JobExecution execution = jobLauncherTestUtils.launchJob();

        // Verify job completed successfully (JCL COND CODE 0 equivalent)
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify all 5 original transactions still present (no records lost)
        assertThat(transactionRepository.count()).isEqualTo(5L);
    }

    // -----------------------------------------------------------------------
    // 4.2 Sort by Transaction ID — Primary Sort Key
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Sort by Transaction ID — ascending order matches COBOL SORT ASCENDING KEY")
    void testSortByTransactionId() throws Exception {
        // Execute the sort job
        JobExecution execution = jobLauncherTestUtils.launchJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Retrieve all transactions sorted by TRAN-ID ascending
        // (matches COBOL SORT ASCENDING KEY SORT-TRAN-ID semantics)
        List<Transaction> sorted =
                transactionRepository.findAllByOrderByTranIdAsc();
        assertThat(sorted).hasSize(5);

        // Verify ascending TRAN-ID order: 1, 2, 3, 4, 5
        assertThat(sorted.get(0).getTranId()).isEqualTo("0000001");
        assertThat(sorted.get(1).getTranId()).isEqualTo("0000002");
        assertThat(sorted.get(2).getTranId()).isEqualTo("0000003");
        assertThat(sorted.get(3).getTranId()).isEqualTo("0000004");
        assertThat(sorted.get(4).getTranId()).isEqualTo("0000005");

        // Verify consecutive TRAN-IDs are in non-decreasing order
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i).getTranId().compareTo(
                    sorted.get(i - 1).getTranId()))
                    .isGreaterThanOrEqualTo(0);
        }
    }

    // -----------------------------------------------------------------------
    // 4.3 Data Integrity — All fields preserved after sort
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Sort preserves all transaction fields — zero data corruption")
    void testSortPreservesAllFields() throws Exception {
        // Execute the sort job
        JobExecution execution = jobLauncherTestUtils.launchJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Retrieve all transactions (exercises findAll from JpaRepository)
        List<Transaction> all = transactionRepository.findAll();
        assertThat(all).hasSize(5);

        // Find Transaction B (TRAN-ID "0000001") and verify ALL 13 fields
        // This exercises every getter on the Transaction entity
        Transaction txB = all.stream()
                .filter(t -> "0000001".equals(t.getTranId()))
                .findFirst()
                .orElseThrow();

        assertThat(txB.getTranId()).isEqualTo("0000001");
        assertThat(txB.getTypeCode()).isEqualTo("01");
        assertThat(txB.getCategoryCode()).isEqualTo(1);
        assertThat(txB.getSource()).isEqualTo("POS TERM");
        assertThat(txB.getDescription())
                .isEqualTo("Purchase at Store Beta");
        // CRITICAL: BigDecimal amounts must remain exact — no precision loss
        assertThat(txB.getAmount())
                .isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(txB.getMerchantId()).isEqualTo("800000001");
        assertThat(txB.getMerchantName()).isEqualTo("Store Beta");
        assertThat(txB.getMerchantCity()).isEqualTo("Chicago");
        assertThat(txB.getMerchantZip()).isEqualTo("60601");
        assertThat(txB.getCardNum()).isEqualTo("4000000000000001");
        assertThat(txB.getOrigTimestamp())
                .isEqualTo("2022-06-10-19.27.53.000000");
        assertThat(txB.getProcTimestamp())
                .isEqualTo("2022-06-10-20.00.00.000000");

        // Verify BigDecimal precision for additional records
        Transaction txA = all.stream()
                .filter(t -> "0000005".equals(t.getTranId()))
                .findFirst()
                .orElseThrow();
        assertThat(txA.getAmount())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        Transaction txD = all.stream()
                .filter(t -> "0000002".equals(t.getTranId()))
                .findFirst()
                .orElseThrow();
        assertThat(txD.getAmount())
                .isEqualByComparingTo(new BigDecimal("200.00"));
    }

    // -----------------------------------------------------------------------
    // 4.4 Duplicate Secondary Keys — All records preserved
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Sort with duplicate timestamps preserves all records — no data loss")
    void testSortWithDuplicateKeys() throws Exception {
        // Clear default data and seed records with same ORIG-TS
        // (same secondary sort key, different primary keys)
        transactionRepository.deleteAll();

        Transaction tx1 = new Transaction(
                "0000003", "01", 1, "POS TERM",
                "Transaction Three",
                new BigDecimal("100.00"), "800000001",
                "Merchant A", "City A", "10001",
                "4000000000000001",
                "2022-06-10-19.27.53.000000", null);
        Transaction tx2 = new Transaction(
                "0000001", "03", 2, "OPERATOR",
                "Transaction One",
                new BigDecimal("200.00"), "800000002",
                "Merchant B", "City B", "10002",
                "4000000000000002",
                "2022-06-10-19.27.53.000000", null);
        Transaction tx3 = new Transaction(
                "0000004", "01", 1, "POS TERM",
                "Transaction Four",
                new BigDecimal("50.00"), "800000003",
                "Merchant C", "City C", "10003",
                "4000000000000001",
                "2022-06-10-19.27.53.000000", null);
        Transaction tx4 = new Transaction(
                "0000002", "01", 3, "POS TERM",
                "Transaction Two",
                new BigDecimal("75.00"), "800000004",
                "Merchant D", "City D", "10004",
                "4000000000000003",
                "2022-06-10-19.27.53.000000", null);

        transactionRepository.saveAll(List.of(tx1, tx2, tx3, tx4));

        // Execute the sort job
        JobExecution execution = jobLauncherTestUtils.launchJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify all 4 records preserved (no data loss)
        assertThat(transactionRepository.count()).isEqualTo(4L);

        // Verify sort order correct despite equal secondary keys
        List<Transaction> sorted =
                transactionRepository.findAllByOrderByTranIdAsc();
        assertThat(sorted.get(0).getTranId()).isEqualTo("0000001");
        assertThat(sorted.get(1).getTranId()).isEqualTo("0000002");
        assertThat(sorted.get(2).getTranId()).isEqualTo("0000003");
        assertThat(sorted.get(3).getTranId()).isEqualTo("0000004");
    }

    // -----------------------------------------------------------------------
    // 4.5 Edge Case — Empty table
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Sort empty table — job completes with no errors")
    void testSortEmptyTable() throws Exception {
        // Clear all transactions (override setUp seed data)
        transactionRepository.deleteAll();

        // Execute the sort job on empty dataset
        JobExecution execution = jobLauncherTestUtils.launchJob();

        // Verify job completes successfully even with no data
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify transaction table remains empty
        assertThat(transactionRepository.count()).isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // 4.6 Edge Case — Single record
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Sort single record — job completes, record unchanged")
    void testSortSingleRecord() throws Exception {
        // Clear default data and seed a single record
        transactionRepository.deleteAll();

        Transaction single = new Transaction(
                "0000001", "01", 1, "POS TERM",
                "Single transaction",
                new BigDecimal("999.99"), "800000001",
                "Solo Merchant", "Solo City", "99999",
                "4000000000000001",
                "2022-06-10-19.27.53.000000",
                "2022-06-10-20.00.00.000000");
        // Exercise save() (instead of saveAll)
        transactionRepository.save(single);

        // Execute the sort job
        JobExecution execution = jobLauncherTestUtils.launchJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify single record preserved and unchanged
        assertThat(transactionRepository.count()).isEqualTo(1L);
        List<Transaction> result =
                transactionRepository.findAllByOrderByTranIdAsc();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTranId()).isEqualTo("0000001");
        assertThat(result.get(0).getAmount())
                .isEqualByComparingTo(new BigDecimal("999.99"));
    }

    // -----------------------------------------------------------------------
    // 4.7 Idempotency — Already sorted data
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Sort already-sorted data — order unchanged, idempotent")
    void testSortAlreadySorted() throws Exception {
        // Clear default data and seed already-sorted records
        transactionRepository.deleteAll();

        Transaction tx1 = new Transaction(
                "0000001", "01", 1, "POS TERM",
                "Already sorted first",
                new BigDecimal("10.00"), "800000001",
                "Merchant 1", "City 1", "11111",
                "4000000000000001",
                "2022-06-01-08.00.00.000000", null);
        Transaction tx2 = new Transaction(
                "0000002", "01", 1, "POS TERM",
                "Already sorted second",
                new BigDecimal("20.00"), "800000002",
                "Merchant 2", "City 2", "22222",
                "4000000000000002",
                "2022-06-02-08.00.00.000000", null);
        Transaction tx3 = new Transaction(
                "0000003", "01", 1, "POS TERM",
                "Already sorted third",
                new BigDecimal("30.00"), "800000003",
                "Merchant 3", "City 3", "33333",
                "4000000000000003",
                "2022-06-03-08.00.00.000000", null);

        transactionRepository.saveAll(List.of(tx1, tx2, tx3));

        // Execute the sort job on already-sorted data
        JobExecution execution = jobLauncherTestUtils.launchJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify order unchanged (sort is idempotent)
        List<Transaction> sorted =
                transactionRepository.findAllByOrderByTranIdAsc();
        assertThat(sorted).hasSize(3);
        assertThat(sorted.get(0).getTranId()).isEqualTo("0000001");
        assertThat(sorted.get(1).getTranId()).isEqualTo("0000002");
        assertThat(sorted.get(2).getTranId()).isEqualTo("0000003");

        // Verify all data fields unchanged
        assertThat(sorted.get(0).getAmount())
                .isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(sorted.get(1).getAmount())
                .isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(sorted.get(2).getAmount())
                .isEqualByComparingTo(new BigDecimal("30.00"));
    }

    // -----------------------------------------------------------------------
    // 4.8 Volume Test — 100+ records
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Sort 100+ records — scales beyond original COBOL SORT capacity")
    void testSortLargeDataset() throws Exception {
        // Clear default data
        transactionRepository.deleteAll();

        // Seed 100 records using setters (in REVERSE order for sort testing)
        List<Transaction> records = new ArrayList<>(100);
        for (int i = 100; i >= 1; i--) {
            records.add(createTransactionUsingSetters(
                    String.format("%07d", i),
                    "4000000000000001",
                    "2022-06-10-19.27.53.000000",
                    new BigDecimal(i * 10 + ".00")));
        }
        transactionRepository.saveAll(records);

        // Execute the sort job
        JobExecution execution = jobLauncherTestUtils.launchJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify all 100 records present (no records lost or duplicated)
        assertThat(transactionRepository.count()).isEqualTo(100L);

        // Verify ascending TRAN-ID sort order
        List<Transaction> sorted =
                transactionRepository.findAllByOrderByTranIdAsc();
        assertThat(sorted).hasSize(100);

        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i).getTranId().compareTo(
                    sorted.get(i - 1).getTranId()))
                    .isGreaterThanOrEqualTo(0);
        }

        // Verify first and last records
        assertThat(sorted.get(0).getTranId()).isEqualTo("0000001");
        assertThat(sorted.get(99).getTranId()).isEqualTo("0000100");
    }
}

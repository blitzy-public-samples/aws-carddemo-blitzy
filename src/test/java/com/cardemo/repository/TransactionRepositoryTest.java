package com.cardemo.repository;

import com.cardemo.entity.Transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TransactionRepository} validating CRUD operations,
 * AIX-equivalent chronological queries, card-based filtering, browse-last ID generation,
 * pagination, and BigDecimal amount precision against a real PostgreSQL 16 database
 * using Testcontainers.
 *
 * <p>Tests verify VSAM TRANSACT KSDS access patterns from:
 * <ul>
 *   <li>COTRN00C.cbl — Transaction List: STARTBR/READNEXT browse with PF7/PF8 pagination</li>
 *   <li>COTRN01C.cbl — Transaction View: EXEC CICS READ DATASET('TRANSACT') RIDFLD(WS-TRAN-ID)</li>
 *   <li>COTRN02C.cbl — Transaction Add: WRITE with browse-last ID generation</li>
 *   <li>CBTRN02C.cbl — Batch Daily Posting: validation, XREF lookup, account checks</li>
 * </ul>
 *
 * <p>COBOL Source Context (CVTRA05Y.cpy — 350-byte TRAN-RECORD):
 * <pre>
 *   TRAN-ID              PIC X(16)        → tranId VARCHAR(16) PK
 *   TRAN-TYPE-CD         PIC X(02)        → typeCode VARCHAR(2)
 *   TRAN-CAT-CD          PIC 9(04)        → categoryCode INTEGER
 *   TRAN-SOURCE          PIC X(10)        → source VARCHAR(10)
 *   TRAN-DESC            PIC X(100)       → description VARCHAR(100)
 *   TRAN-AMT             PIC S9(09)V99    → amount NUMERIC(11,2) / BigDecimal
 *   TRAN-MERCHANT-ID     PIC 9(09)        → merchantId VARCHAR(9)
 *   TRAN-MERCHANT-NAME   PIC X(50)        → merchantName VARCHAR(50)
 *   TRAN-MERCHANT-CITY   PIC X(50)        → merchantCity VARCHAR(50)
 *   TRAN-MERCHANT-ZIP    PIC X(10)        → merchantZip VARCHAR(10)
 *   TRAN-CARD-NUM        PIC X(16)        → cardNum VARCHAR(16)
 *   TRAN-ORIG-TS         PIC X(26)        → origTimestamp VARCHAR(26) [AIX indexed]
 *   TRAN-PROC-TS         PIC X(26)        → procTimestamp VARCHAR(26)
 *   FILLER               PIC X(20)        → (not mapped)
 * </pre>
 *
 * <p>Critical AIX mapping: {@code idx_transaction_orig_ts} on
 * {@code transactions(tran_orig_ts)} replicates the VSAM Alternate Index at
 * AXRKP=304, KEYLEN=26, enabling chronological browse queries.
 *
 * <p>All monetary values use {@link BigDecimal} with {@code scale=2} matching
 * COBOL {@code PIC S9(09)V99} (signed, 9 integer digits, 2 decimal places).
 * No {@code double} or {@code float} is used anywhere — per AAP Rule 0.7.4.
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionRepositoryTest {

    /**
     * PostgreSQL 16 container managed by Testcontainers JUnit 5 extension.
     * Started once before the first test; stopped after the last test completes.
     * Uses postgres:16-alpine Docker image matching the production target database.
     * Flyway migrations (V1__create_schema.sql, V2__create_indexes.sql, V100__seed_data.sql)
     * execute automatically at Spring context initialization.
     */
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    /**
     * Injects Testcontainers-managed PostgreSQL connection properties into Spring
     * DataSource, overriding the Testcontainers JDBC URL in application-test.yml
     * to use the explicit container instance managed by {@code @Container}.
     * Also overrides the driver-class-name from the Testcontainers JDBC driver
     * to the standard PostgreSQL driver for correct connection handling.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private TransactionRepository transactionRepository;

    /** Card number for primary test card (3 transactions: txn1, txn2, txn4). */
    private static final String CARD_NUM_PRIMARY = "0500024453765740";

    /** Card number for secondary test card (1 transaction: txn3). */
    private static final String CARD_NUM_SECONDARY = "0683586198171516";

    /**
     * Clears all transaction records and inserts four test transactions before each test.
     * Test data is designed to exercise AIX chronological queries, card-based filtering,
     * and browse-last ID generation:
     * <ul>
     *   <li>Txn1: ID=0000000000000001, Jan 2024, card1, amount=125.50</li>
     *   <li>Txn2: ID=0000000000000002, Feb 2024, card1, amount=250.75</li>
     *   <li>Txn3: ID=0000000000000003, Jan 2024, card2, amount=50.00</li>
     *   <li>Txn4: ID=0000000000000010, Mar 2024, card1, amount=75.25 (highest ID)</li>
     * </ul>
     * All amounts use {@code new BigDecimal("...")} string constructor exclusively.
     */
    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();

        // Txn 1: January 2024, primary card, typeCode "01", category 1
        // Maps to online transaction entry via COTRN02C.cbl
        Transaction txn1 = new Transaction(
                "0000000000000001", "01", 1, "ONLINE",
                "Purchase at Store A", new BigDecimal("125.50"),
                "000000001", "Store A", "New York", "10001",
                CARD_NUM_PRIMARY,
                "2024-01-15-10.30.00.000000", "2024-01-15-10.30.01.000000"
        );

        // Txn 2: February 2024, primary card, typeCode "02", category 1
        // Later date for AIX range query boundary testing
        Transaction txn2 = new Transaction(
                "0000000000000002", "02", 1, "ONLINE",
                "Purchase at Store B", new BigDecimal("250.75"),
                "000000002", "Store B", "Los Angeles", "90001",
                CARD_NUM_PRIMARY,
                "2024-02-20-14.45.30.000000", "2024-02-20-14.45.31.000000"
        );

        // Txn 3: January 2024, secondary card, typeCode "01", category 2
        // Different card for card-based filter testing
        Transaction txn3 = new Transaction(
                "0000000000000003", "01", 2, "BATCH",
                "Refund at Store C", new BigDecimal("50.00"),
                "000000003", "Store C", "Chicago", "60601",
                CARD_NUM_SECONDARY,
                "2024-01-20-09.15.00.000000", "2024-01-20-09.15.01.000000"
        );

        // Txn 4: March 2024, primary card, typeCode "03", category 1
        // Highest tranId ("0000000000000010") for browse-last ID generation test
        Transaction txn4 = new Transaction(
                "0000000000000010", "03", 1, "ONLINE",
                "Purchase at Store D", new BigDecimal("75.25"),
                "000000004", "Store D", "Houston", "77001",
                CARD_NUM_PRIMARY,
                "2024-03-01-08.00.00.000000", "2024-03-01-08.00.01.000000"
        );

        transactionRepository.saveAll(List.of(txn1, txn2, txn3, txn4));
    }

    // ========================================================================
    // CRUD Operation Tests
    // ========================================================================

    /**
     * Verifies findById retrieves a transaction by its 16-character TRAN-ID primary key,
     * with all 13 entity fields correctly populated.
     * Maps to COTRN01C.cbl: {@code EXEC CICS READ DATASET('TRANSACT') RIDFLD(WS-TRAN-ID)}.
     */
    @Test
    @DisplayName("findById returns transaction by 16-char TRAN-ID primary key")
    void findById_returnsByTranId() {
        Optional<Transaction> result = transactionRepository.findById("0000000000000001");

        assertThat(result.isPresent()).isTrue();
        Transaction txn = result.get();
        assertThat(txn.getTranId()).isEqualTo("0000000000000001");
        assertThat(txn.getTypeCode()).isEqualTo("01");
        assertThat(txn.getCategoryCode()).isEqualTo(1);
        assertThat(txn.getSource()).isEqualTo("ONLINE");
        assertThat(txn.getDescription()).isEqualTo("Purchase at Store A");
        assertThat(txn.getAmount()).isEqualByComparingTo(new BigDecimal("125.50"));
        assertThat(txn.getMerchantId()).isEqualTo("000000001");
        assertThat(txn.getMerchantName()).isEqualTo("Store A");
        assertThat(txn.getMerchantCity()).isEqualTo("New York");
        assertThat(txn.getMerchantZip()).isEqualTo("10001");
        assertThat(txn.getCardNum()).isEqualTo(CARD_NUM_PRIMARY);
        assertThat(txn.getOrigTimestamp()).isEqualTo("2024-01-15-10.30.00.000000");
        assertThat(txn.getProcTimestamp()).isEqualTo("2024-01-15-10.30.01.000000");
    }

    /**
     * Verifies findById returns {@link Optional#empty()} when the requested
     * TRAN-ID does not exist, mapping to CICS RESP(NOTFND) / FILE STATUS '23'.
     */
    @Test
    @DisplayName("findById returns empty for non-existent transaction")
    void findById_returnsEmptyForNonExistent() {
        Optional<Transaction> result = transactionRepository.findById("9999999999999999");

        assertThat(result.isEmpty()).isTrue();
    }

    /**
     * Verifies save persists a new transaction with all fields correctly set via setters,
     * including exact BigDecimal amount precision. Exercises all 13 entity setters to
     * confirm mutability and JPA flush/commit roundtrip.
     * Maps to COTRN02C.cbl: {@code EXEC CICS WRITE DATASET('TRANSACT') FROM(TRAN-RECORD)}.
     */
    @Test
    @DisplayName("save persists new transaction with BigDecimal amount")
    void save_persistsWithBigDecimalAmount() {
        // Create base transaction via all-args constructor
        Transaction txn = new Transaction(
                "0000000000000050", "01", 1, "ONLINE",
                "Initial description", new BigDecimal("0.00"),
                "000000050", "Initial Store", "Portland", "97201",
                "1234567890123456",
                "2024-05-01-12.00.00.000000", "2024-05-01-12.00.01.000000"
        );

        // Exercise all setters to verify state mutability before persistence
        txn.setTranId("0000000000000055");
        txn.setTypeCode("05");
        txn.setCategoryCode(3);
        txn.setSource("BATCH");
        txn.setDescription("Verified purchase via setter");
        txn.setAmount(new BigDecimal("333.33"));
        txn.setMerchantId("000000100");
        txn.setMerchantName("Setter Store");
        txn.setMerchantCity("Seattle");
        txn.setMerchantZip("98101");
        txn.setCardNum("9876543210987654");
        txn.setOrigTimestamp("2024-05-15-14.30.00.000000");
        txn.setProcTimestamp("2024-05-15-14.30.01.000000");

        Transaction saved = transactionRepository.save(txn);
        assertThat(saved.getTranId()).isEqualTo("0000000000000055");

        // Verify roundtrip: retrieve from database and check all setter-applied values
        Optional<Transaction> found = transactionRepository.findById("0000000000000055");
        assertThat(found).isPresent();
        Transaction retrieved = found.get();
        assertThat(retrieved.getTypeCode()).isEqualTo("05");
        assertThat(retrieved.getCategoryCode()).isEqualTo(3);
        assertThat(retrieved.getSource()).isEqualTo("BATCH");
        assertThat(retrieved.getDescription()).isEqualTo("Verified purchase via setter");
        assertThat(retrieved.getAmount()).isEqualByComparingTo(new BigDecimal("333.33"));
        assertThat(retrieved.getMerchantId()).isEqualTo("000000100");
        assertThat(retrieved.getMerchantName()).isEqualTo("Setter Store");
        assertThat(retrieved.getMerchantCity()).isEqualTo("Seattle");
        assertThat(retrieved.getMerchantZip()).isEqualTo("98101");
        assertThat(retrieved.getCardNum()).isEqualTo("9876543210987654");
        assertThat(retrieved.getOrigTimestamp()).isEqualTo("2024-05-15-14.30.00.000000");
        assertThat(retrieved.getProcTimestamp()).isEqualTo("2024-05-15-14.30.01.000000");
        assertThat(transactionRepository.count()).isEqualTo(5);
    }

    /**
     * Verifies deleteById removes a transaction from the database.
     * Maps to CICS DELETE operation on the TRANSACT dataset.
     */
    @Test
    @DisplayName("delete removes transaction by ID")
    void delete_removesById() {
        assertThat(transactionRepository.findById("0000000000000001").isPresent()).isTrue();

        transactionRepository.deleteById("0000000000000001");

        assertThat(transactionRepository.findById("0000000000000001").isEmpty()).isTrue();
        assertThat(transactionRepository.count()).isEqualTo(3);
    }

    // ========================================================================
    // AIX-Equivalent Chronological Query Tests (CRITICAL)
    // ========================================================================

    /**
     * Verifies findByOrigTimestampBetween returns transactions within a specified
     * date range, replicating the VSAM Alternate Index (AIX) on TRAN-ORIG-TS
     * at position 304, length 26 bytes. Uses the
     * {@code idx_transaction_orig_ts} database index.
     *
     * <p>January 2024 range should match txn1 (Jan 15) and txn3 (Jan 20),
     * but NOT txn2 (Feb 20) or txn4 (Mar 01).
     */
    @Test
    @DisplayName("findByOrigTimestampBetween returns transactions in date range — AIX equivalent")
    void findByOrigTimestampBetween_returnsTransactionsInDateRange() {
        List<Transaction> results = transactionRepository.findByOrigTimestampBetween(
                "2024-01-01-00.00.00.000000",
                "2024-01-31-23.59.59.999999"
        );

        assertThat(results).hasSize(2);
        assertThat(results).extracting(Transaction::getTranId)
                .containsExactlyInAnyOrder("0000000000000001", "0000000000000003");
    }

    /**
     * Verifies findByOrigTimestampBetween returns an empty list when no
     * transactions fall within the specified range — boundary case for
     * the AIX chronological query.
     */
    @Test
    @DisplayName("findByOrigTimestampBetween returns empty for range with no transactions")
    void findByOrigTimestampBetween_returnsEmptyForNoMatch() {
        List<Transaction> results = transactionRepository.findByOrigTimestampBetween(
                "2025-01-01-00.00.00.000000",
                "2025-12-31-23.59.59.999999"
        );

        assertThat(results).isEmpty();
    }

    /**
     * Verifies that ISO-8601 timestamps with microsecond precision
     * ({@code YYYY-MM-DD-HH.MM.SS.mmmmmm}, 26 characters) persist and
     * retrieve with byte-exact fidelity. Maps to TRAN-ORIG-TS and TRAN-PROC-TS
     * PIC X(26) columns stored as VARCHAR(26) in PostgreSQL.
     */
    @Test
    @DisplayName("ISO-8601 timestamp format with microseconds persists correctly")
    void isoTimestampWithMicroseconds_persistsCorrectly() {
        String origTs = "2024-06-15-16.30.45.123456";
        String procTs = "2024-06-15-16.30.46.654321";

        Transaction txn = new Transaction(
                "0000000000000088", "01", 1, "ONLINE",
                "Timestamp precision test", new BigDecimal("10.00"),
                "000000088", "TS Store", "Boston", "02101",
                "1111222233334444", origTs, procTs
        );
        transactionRepository.save(txn);

        Optional<Transaction> found = transactionRepository.findById("0000000000000088");
        assertThat(found).isPresent();
        assertThat(found.get().getOrigTimestamp()).isEqualTo(origTs);
        assertThat(found.get().getOrigTimestamp()).hasSize(26);
        assertThat(found.get().getProcTimestamp()).isEqualTo(procTs);
        assertThat(found.get().getProcTimestamp()).hasSize(26);
    }

    // ========================================================================
    // Card-Based Filtering Tests
    // ========================================================================

    /**
     * Verifies findByCardNum returns a paginated result set for a specific
     * card number. Maps to COTRN00C.cbl card-based transaction browse using
     * the {@code idx_transaction_card_num} index.
     *
     * <p>Primary card (CARD_NUM_PRIMARY) has 3 transactions (txn1, txn2, txn4).
     */
    @Test
    @DisplayName("findByCardNum returns paginated transactions for a specific card")
    void findByCardNum_returnsPaginatedForSpecificCard() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Transaction> page = transactionRepository.findByCardNum(CARD_NUM_PRIMARY, pageable);

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent()).extracting(Transaction::getCardNum)
                .containsOnly(CARD_NUM_PRIMARY);
    }

    /**
     * Verifies findByCardNum returns an empty page when no transactions
     * exist for the given card number.
     */
    @Test
    @DisplayName("findByCardNum returns empty page for card with no transactions")
    void findByCardNum_returnsEmptyPageForNoMatch() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Transaction> page = transactionRepository.findByCardNum("9999999999999999", pageable);

        assertThat(page.getTotalElements()).isEqualTo(0);
        assertThat(page.getContent()).isEmpty();
    }

    // ========================================================================
    // Browse-Last for ID Generation Tests (CRITICAL)
    // ========================================================================

    /**
     * Verifies findFirstByOrderByTranIdDesc returns the transaction with the
     * highest (lexicographically last) TRAN-ID. Replicates the COBOL browse-last
     * technique for transaction ID generation from COTRN02C.cbl:
     * {@code STARTBR → READPREV} to find the maximum TRAN-ID, then increment.
     *
     * <p>Per AAP Section 0.7.4: "Transaction ID generation must replicate the
     * COBOL browse-last technique: read the last transaction by key, increment,
     * and assign."
     *
     * <p>With test data containing IDs "0000000000000001" through "0000000000000010",
     * this should return the transaction with tranId="0000000000000010".
     */
    @Test
    @DisplayName("findFirstByOrderByTranIdDesc returns transaction with highest ID — browse-last for ID generation")
    void findFirstByOrderByTranIdDesc_returnsHighestId() {
        Optional<Transaction> result = transactionRepository.findFirstByOrderByTranIdDesc();

        assertThat(result).isPresent();
        assertThat(result.get().getTranId()).isEqualTo("0000000000000010");
    }

    /**
     * Verifies findFirstByOrderByTranIdDesc returns {@link Optional#empty()}
     * when no transactions exist in the table — initial state for ID generation.
     */
    @Test
    @DisplayName("findFirstByOrderByTranIdDesc returns empty when no transactions exist")
    void findFirstByOrderByTranIdDesc_returnsEmptyWhenNoData() {
        transactionRepository.deleteAll();

        Optional<Transaction> result = transactionRepository.findFirstByOrderByTranIdDesc();

        assertThat(result).isEmpty();
    }

    // ========================================================================
    // BigDecimal Amount Precision Tests
    // ========================================================================

    /**
     * Verifies BigDecimal amount preserves exact scale=2 for the maximum
     * representable value under PIC S9(09)V99: 999,999,999.99.
     * The PostgreSQL NUMERIC(11,2) column must store and retrieve this
     * boundary value with no precision loss.
     */
    @Test
    @DisplayName("BigDecimal amount preserves exact scale=2 for PIC S9(09)V99")
    void bigDecimalAmount_preservesExactScale2() {
        Transaction txn = new Transaction(
                "0000000000000077", "01", 1, "ONLINE",
                "Maximum amount test", new BigDecimal("999999999.99"),
                "000000077", "Max Store", "Dallas", "75201",
                "1234567890123456",
                "2024-07-01-10.00.00.000000", "2024-07-01-10.00.01.000000"
        );
        transactionRepository.save(txn);

        Optional<Transaction> found = transactionRepository.findById("0000000000000077");
        assertThat(found).isPresent();
        assertThat(found.get().getAmount()).isEqualByComparingTo(new BigDecimal("999999999.99"));
        assertThat(found.get().getAmount().scale()).isEqualTo(2);
    }

    /**
     * Verifies negative BigDecimal amounts persist correctly for the signed
     * PIC S9(09)V99 field. COBOL sign-overpunch encoding supports negative
     * values (e.g., credit/refund transactions), which must be faithfully
     * represented as negative BigDecimal values in PostgreSQL.
     */
    @Test
    @DisplayName("negative BigDecimal amount persists correctly for signed S9(09)V99")
    void negativeBigDecimalAmount_persistsCorrectly() {
        Transaction txn = new Transaction(
                "0000000000000066", "01", 1, "ONLINE",
                "Negative amount test", new BigDecimal("-500.25"),
                "000000066", "Neg Store", "Miami", "33101",
                "1234567890123456",
                "2024-08-01-10.00.00.000000", "2024-08-01-10.00.01.000000"
        );
        transactionRepository.save(txn);

        Optional<Transaction> found = transactionRepository.findById("0000000000000066");
        assertThat(found).isPresent();
        assertThat(found.get().getAmount()).isEqualByComparingTo(new BigDecimal("-500.25"));
        assertThat(found.get().getAmount().signum()).isEqualTo(-1);
    }

    // ========================================================================
    // Pagination Tests
    // ========================================================================

    /**
     * Verifies findAll with Pageable supports paginated transaction listing,
     * mapping to COTRN00C.cbl STARTBR/READNEXT with PF7/PF8 navigation
     * (10 transactions per page in the COBOL program; test uses page size 2
     * for boundary verification with 4 total records).
     */
    @Test
    @DisplayName("findAll with Pageable supports paginated transaction listing")
    void findAll_supportsPagination() {
        Page<Transaction> page = transactionRepository.findAll(PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }
}

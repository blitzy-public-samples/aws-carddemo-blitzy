/*
 * TransactionAddServiceTest.java — JUnit 5 unit tests for TransactionAddService
 *
 * Maps COBOL program COTRN02C.cbl paragraphs to Java service method tests.
 * Tests cover browse-last ID generation, input validation (amount range, date
 * format), XREF/account validation, and ISO-8601 timestamp handling.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.cardemo.service.online;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.common.util.DateConversionUtil;
import com.cardemo.common.util.DateConversionUtil.DateValidationResult;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionAddService} — the online transaction add
 * service translated from COTRN02C.cbl. Uses Mockito mocking for repository
 * and context dependencies, and {@code mockStatic} for the static-only
 * {@link DateConversionUtil} helper.
 *
 * <p>Each test method maps to one or more COBOL paragraphs:
 * COPY-LAST-TRAN-DATA, VALIDATE-INPUT-KEY-FIELDS, VALIDATE-INPUT-DATA-FIELDS,
 * ADD-TRANSACTION, WRITE-TRANSACT-FILE, READ-CXACAIX-FILE, READ-CCXREF-FILE.
 */
@ExtendWith(MockitoExtension.class)
class TransactionAddServiceTest {

    /* ------------------------------------------------------------------ */
    /*  Mocks and system-under-test                                       */
    /* ------------------------------------------------------------------ */

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private CardDemoContext cardDemoContext;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query advisoryLockQuery;

    @InjectMocks
    private TransactionAddService transactionAddService;

    /* ------------------------------------------------------------------ */
    /*  Constants mirroring COTRN02C layout and COBOL field sizes          */
    /* ------------------------------------------------------------------ */

    /** Valid 11-char account ID matching PIC 9(11). */
    private static final String VALID_ACCOUNT_ID = "00000000001";

    /** Valid 16-char card number matching PIC X(16). */
    private static final String VALID_CARD_NUM = "1234567890123456";

    /**
     * ISO-8601 timestamp with microsecond precision (26 chars):
     * {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} — per AAP section 0.7.4.
     */
    private static final String VALID_TIMESTAMP =
            "2025-06-15-10.30.00.000000";

    /** Date format string matching TransactionAddService.DATE_FORMAT. */
    private static final String DATE_FORMAT = "YYYY-MM-DD";

    /** Expected length of the ISO-8601 timestamp with microsecond precision. */
    private static final int TIMESTAMP_LENGTH = 26;

    /** Transaction ID length — 16-char zero-padded per TRAN-ID PIC X(16). */
    private static final int TRAN_ID_LENGTH = 16;

    /* ------------------------------------------------------------------ */
    /*  Test fixtures                                                      */
    /* ------------------------------------------------------------------ */

    private CardXref testCardXref;
    private Transaction lastTransaction;

    @BeforeEach
    void setUp() {
        // Cross-reference fixture: card → account mapping
        testCardXref = new CardXref(VALID_CARD_NUM, "000000001",
                VALID_ACCOUNT_ID);

        // Last transaction fixture for browse-last ID generation tests
        lastTransaction = new Transaction(
                "0000000000000050", "01", 5001, "ONLINE",
                "Previous test transaction",
                new BigDecimal("250.00").setScale(2, RoundingMode.HALF_UP),
                "123456789", "Test Merchant", "Test City", "12345",
                VALID_CARD_NUM, VALID_TIMESTAMP, VALID_TIMESTAMP);

        // Manually inject the EntityManager mock because @InjectMocks uses
        // constructor injection for the 3-arg constructor and does not
        // subsequently perform field injection for the @PersistenceContext
        // annotated entityManager field.
        ReflectionTestUtils.setField(transactionAddService,
                "entityManager", entityManager);

        // Mock the PostgreSQL advisory lock used by addTransactionRecord()
        // to serialise concurrent transaction ID generation. In unit tests
        // the EntityManager is mocked, so the native query is a no-op.
        // Lenient stubs: not every test calls addTransactionRecord(), so
        // strict Mockito would flag these as UnnecessaryStubbing.
        org.mockito.Mockito.lenient().when(entityManager.createNativeQuery(
                eq("SELECT pg_advisory_xact_lock(:lockKey)")))
                .thenReturn(advisoryLockQuery);
        org.mockito.Mockito.lenient().when(
                advisoryLockQuery.setParameter(eq("lockKey"), anyLong()))
                .thenReturn(advisoryLockQuery);
        org.mockito.Mockito.lenient().when(
                advisoryLockQuery.getSingleResult()).thenReturn(null);
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Creates a fully-valid {@link TransactionAddService.TransactionAddRequest}
     * with the given amount. All 11 required data fields are populated so that
     * Phase-1 blank checks in {@code validateInputDataFields} pass.
     */
    private TransactionAddService.TransactionAddRequest createValidRequest(
            String amount) {
        return new TransactionAddService.TransactionAddRequest(
                VALID_ACCOUNT_ID,      // accountId  — PIC X(11)
                VALID_CARD_NUM,        // cardNum    — PIC X(16)
                "01",                  // typeCode   — PIC X(2)
                "5001",                // categoryCode — PIC X(4)
                "ONLINE",              // source     — PIC X(10)
                "Test purchase desc",  // description — PIC X(60)
                amount,                // amount     — PIC X(12)
                "2025-06-15",          // origDate   — PIC X(10)
                "2025-06-15",          // procDate   — PIC X(10)
                "123456789",           // merchantId — PIC X(9)
                "Test Merchant",       // merchantName — PIC X(30)
                "Test City",           // merchantCity — PIC X(25)
                "12345",               // merchantZip  — PIC X(10)
                "Y"                    // confirm    — PIC X(1)
        );
    }

    /** Returns a {@link DateValidationResult} representing a valid date. */
    private DateValidationResult validDateResult() {
        // SEVERITY_OK=0, MSG_VALID=0 (private in DateConversionUtil)
        return new DateValidationResult(0, 0, "Valid", "", DATE_FORMAT, true);
    }

    /**
     * Returns a {@link DateValidationResult} representing an invalid date
     * with a non-suppressed message code (not {@code MSG_UNSUPP_RANGE=2513}).
     */
    private DateValidationResult invalidDateResult(String testedDate) {
        // SEVERITY_ERROR=3, messageCode=1 (any code != MSG_UNSUPP_RANGE 2513)
        return new DateValidationResult(
                3, 1, "Invalid date format or value",
                testedDate, DATE_FORMAT, false);
    }

    /* ================================================================== */
    /*  TEST 1 — Browse-Last ID Generation                                */
    /*  Maps: COPY-LAST-TRAN-DATA paragraph                               */
    /*  STARTBR at HIGH-VALUES → READPREV → get last TRAN-ID → add 1     */
    /* ================================================================== */

    @Test
    @DisplayName("Browse-last ID generation increments last TRAN-ID"
            + " — maps COPY-LAST-TRAN-DATA")
    void testBrowseLastIdGeneration() {
        // Given: existing transaction with ID "0000000000000050"
        when(transactionRepository.findFirstByOrderByTranIdDesc())
                .thenReturn(Optional.of(lastTransaction));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Transaction with null tranId — will be assigned by copyLastTranData
        Transaction newTran = new Transaction(
                null, "01", 5001, "ONLINE", "New transaction",
                new BigDecimal("100.00").setScale(2, RoundingMode.HALF_UP),
                "123456789", "Test Merchant", "Test City", "12345",
                VALID_CARD_NUM, null, null);

        try (MockedStatic<DateConversionUtil> dateMock =
                     mockStatic(DateConversionUtil.class)) {
            dateMock.when(DateConversionUtil::getCurrentTimestamp)
                    .thenReturn(VALID_TIMESTAMP);

            // When: addTransactionRecord is called (invokes copyLastTranData)
            Transaction result =
                    transactionAddService.addTransactionRecord(newTran);

            // Then: ID incremented from 50 → 51, zero-padded to 16 chars
            assertThat(result.getTranId())
                    .isEqualTo("0000000000000051")
                    .hasSize(TRAN_ID_LENGTH);
        }
    }

    /* ================================================================== */
    /*  TEST 2 — Browse-Last ID Generation: Empty Table                   */
    /*  Maps: COPY-LAST-TRAN-DATA with empty TRANSACT dataset             */
    /* ================================================================== */

    @Test
    @DisplayName("Browse-last returns first ID for empty table"
            + " — maps COPY-LAST-TRAN-DATA empty dataset")
    void testBrowseLastIdGeneration_EmptyTable() {
        // Given: no existing transactions (empty table)
        when(transactionRepository.findFirstByOrderByTranIdDesc())
                .thenReturn(Optional.empty());
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Transaction with null tranId — will be assigned by copyLastTranData
        Transaction newTran = new Transaction(
                null, "01", 5001, "ONLINE", "First transaction",
                new BigDecimal("50.00").setScale(2, RoundingMode.HALF_UP),
                "123456789", "Test Merchant", "Test City", "12345",
                VALID_CARD_NUM, null, null);

        try (MockedStatic<DateConversionUtil> dateMock =
                     mockStatic(DateConversionUtil.class)) {
            dateMock.when(DateConversionUtil::getCurrentTimestamp)
                    .thenReturn(VALID_TIMESTAMP);

            // When
            Transaction result =
                    transactionAddService.addTransactionRecord(newTran);

            // Then: first-ever transaction ID is "0000000000000001"
            assertThat(result.getTranId())
                    .isEqualTo("0000000000000001")
                    .hasSize(TRAN_ID_LENGTH);
        }
    }

    /* ================================================================== */
    /*  TEST 3 — Validate Input Key Fields: Valid Account and Card        */
    /*  Maps: VALIDATE-INPUT-KEY-FIELDS → READ-CXACAIX-FILE              */
    /* ================================================================== */

    @Test
    @DisplayName("Valid account ID passes key field validation"
            + " — maps VALIDATE-INPUT-KEY-FIELDS + READ-CXACAIX-FILE")
    void testValidateInputKeyFields_ValidAccountAndCard() {
        // Given: cross-reference exists for the account
        when(cardXrefRepository.findByAccountId(VALID_ACCOUNT_ID))
                .thenReturn(List.of(testCardXref));

        // When: validate with valid account (card is secondary — else-if path)
        List<String> errors = transactionAddService
                .validateInputKeyFields(VALID_ACCOUNT_ID, VALID_CARD_NUM);

        // Then: no validation errors
        assertThat(errors).isEmpty();
    }

    /* ================================================================== */
    /*  TEST 4 — Validate Input Key Fields: Invalid XREF                  */
    /*  Maps: READ-CXACAIX-FILE → NOTFND condition                        */
    /* ================================================================== */

    @Test
    @DisplayName("Missing XREF fails key field validation"
            + " — maps READ-CXACAIX-FILE NOTFND")
    void testValidateInputKeyFields_InvalidXref() {
        // Given: no cross-reference record for account "00000000999"
        when(cardXrefRepository.findByAccountId("00000000999"))
                .thenReturn(List.of());

        // When: validate with an account that has no XREF entry
        List<String> errors = transactionAddService
                .validateInputKeyFields("00000000999", null);

        // Then: error list is non-empty and contains cross-reference message
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).containsIgnoringCase("not found");
    }

    /* ================================================================== */
    /*  TEST 5 — Validate Input Data Fields: Valid Amount                  */
    /*  Maps: VALIDATE-INPUT-DATA-FIELDS amount range check               */
    /*  PIC S9(09)V99 → range -99999999.99 to 99999999.99                */
    /* ================================================================== */

    @Test
    @DisplayName("Valid amounts pass data field validation"
            + " — maps VALIDATE-INPUT-DATA-FIELDS PIC S9(09)V99 range")
    void testValidateInputDataFields_ValidAmount() {
        try (MockedStatic<DateConversionUtil> dateMock =
                     mockStatic(DateConversionUtil.class)) {
            // Stub date validation so it never blocks the amount checks
            dateMock.when(() -> DateConversionUtil
                            .validateDate(anyString(), anyString()))
                    .thenReturn(validDateResult());

            // Boundary and representative valid amounts
            for (String amount : List.of(
                    "100.00", "-500.50", "99999999.99", "-99999999.99")) {
                TransactionAddService.TransactionAddRequest request =
                        createValidRequest(amount);

                List<String> errors = transactionAddService
                        .validateInputDataFields(request);

                assertThat(errors)
                        .as("Amount %s should produce no validation errors",
                                amount)
                        .isEmpty();
            }
        }
    }

    /* ================================================================== */
    /*  TEST 6 — Validate Input Data Fields: Amount Out of Range          */
    /*  Maps: VALIDATE-INPUT-DATA-FIELDS PIC S9(09)V99 overflow           */
    /* ================================================================== */

    @Test
    @DisplayName("Out-of-range amount fails validation"
            + " — maps VALIDATE-INPUT-DATA-FIELDS PIC S9(09)V99 overflow")
    void testValidateInputDataFields_AmountOutOfRange() {
        try (MockedStatic<DateConversionUtil> dateMock =
                     mockStatic(DateConversionUtil.class)) {
            dateMock.when(() -> DateConversionUtil
                            .validateDate(anyString(), anyString()))
                    .thenReturn(validDateResult());

            // Amount exceeds MAX_AMOUNT (99999999.99)
            TransactionAddService.TransactionAddRequest request =
                    createValidRequest("100000000.00");

            List<String> errors = transactionAddService
                    .validateInputDataFields(request);

            // Then: at least one error mentioning "out of range"
            assertThat(errors).isNotEmpty();
            assertThat(errors).anyMatch(e -> e.contains("out of range"));
        }
    }

    /* ================================================================== */
    /*  TEST 7 — Validate Input Data Fields: Valid Date                   */
    /*  Maps: DateConversionUtil.validateDate success path                 */
    /* ================================================================== */

    @Test
    @DisplayName("Valid date passes data field validation"
            + " — maps DateConversionUtil.validateDate success")
    void testValidateInputDataFields_ValidDate() {
        try (MockedStatic<DateConversionUtil> dateMock =
                     mockStatic(DateConversionUtil.class)) {
            // Both origDate and procDate are "2025-06-15" → valid
            dateMock.when(() -> DateConversionUtil
                            .validateDate("2025-06-15", DATE_FORMAT))
                    .thenReturn(validDateResult());

            TransactionAddService.TransactionAddRequest request =
                    createValidRequest("100.00");

            List<String> errors = transactionAddService
                    .validateInputDataFields(request);

            // Then: no errors — dates are valid
            assertThat(errors).isEmpty();
        }
    }

    /* ================================================================== */
    /*  TEST 8 — Validate Input Data Fields: Invalid Date                 */
    /*  Maps: DateConversionUtil.validateDate failure path                 */
    /* ================================================================== */

    @Test
    @DisplayName("Invalid date fails data field validation"
            + " — maps DateConversionUtil.validateDate failure")
    void testValidateInputDataFields_InvalidDate() {
        try (MockedStatic<DateConversionUtil> dateMock =
                     mockStatic(DateConversionUtil.class)) {
            // origDate "2025-13-40" is invalid; procDate "2025-06-15" is valid
            dateMock.when(() -> DateConversionUtil
                            .validateDate("2025-13-40", DATE_FORMAT))
                    .thenReturn(invalidDateResult("2025-13-40"));
            dateMock.when(() -> DateConversionUtil
                            .validateDate("2025-06-15", DATE_FORMAT))
                    .thenReturn(validDateResult());

            TransactionAddService.TransactionAddRequest request =
                    new TransactionAddService.TransactionAddRequest(
                            VALID_ACCOUNT_ID, VALID_CARD_NUM,
                            "01", "5001", "ONLINE",
                            "Test purchase desc", "100.00",
                            "2025-13-40",  // ← invalid origDate
                            "2025-06-15",  // ← valid procDate
                            "123456789", "Test Merchant",
                            "Test City", "12345", "Y");

            List<String> errors = transactionAddService
                    .validateInputDataFields(request);

            // Then: at least one date validation error
            assertThat(errors).isNotEmpty();
            assertThat(errors).anyMatch(e ->
                    e.toLowerCase().contains("date")
                            || e.toLowerCase().contains("invalid"));
        }
    }

    /* ================================================================== */
    /*  TEST 9 — Add Transaction: Success (full flow)                     */
    /*  Maps: ADD-TRANSACTION → processEnterKey → addTransactionRecord    */
    /*        → WRITE-TRANSACT-FILE                                       */
    /* ================================================================== */

    @Test
    @DisplayName("Successful transaction add through full flow"
            + " — maps ADD-TRANSACTION → WRITE-TRANSACT-FILE")
    void testAddTransaction_Success() {
        // Given: valid XREF, browse-last returns existing transaction
        when(cardXrefRepository.findByAccountId(VALID_ACCOUNT_ID))
                .thenReturn(List.of(testCardXref));
        when(transactionRepository.findFirstByOrderByTranIdDesc())
                .thenReturn(Optional.of(lastTransaction));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        try (MockedStatic<DateConversionUtil> dateMock =
                     mockStatic(DateConversionUtil.class)) {
            dateMock.when(() -> DateConversionUtil
                            .validateDate(anyString(), anyString()))
                    .thenReturn(validDateResult());
            dateMock.when(DateConversionUtil::getCurrentTimestamp)
                    .thenReturn(VALID_TIMESTAMP);

            TransactionAddService.TransactionAddRequest request =
                    createValidRequest("100.00");

            // When: full add transaction via processEnterKey
            Transaction result =
                    transactionAddService.processEnterKey(request);

            // Then: save was called
            verify(transactionRepository).save(any(Transaction.class));

            // Then: origTimestamp set in ISO-8601 format (26 chars)
            assertThat(result).isNotNull();
            assertThat(result.getOrigTimestamp())
                    .isEqualTo(VALID_TIMESTAMP)
                    .hasSize(TIMESTAMP_LENGTH);
            assertThat(result.getProcTimestamp())
                    .isEqualTo(VALID_TIMESTAMP)
                    .hasSize(TIMESTAMP_LENGTH);

            // Then: transaction ID was generated (incremented from 50 → 51)
            assertThat(result.getTranId())
                    .isEqualTo("0000000000000051")
                    .hasSize(TRAN_ID_LENGTH);

            // Then: BigDecimal amount with scale=2 per PIC S9(09)V99
            assertThat(result.getAmount())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(result.getAmount().scale()).isEqualTo(2);
        }
    }

    /* ================================================================== */
    /*  TEST 10 — Write Transact File: Success                            */
    /*  Maps: WRITE-TRANSACT-FILE → EXEC CICS WRITE DATASET              */
    /* ================================================================== */

    @Test
    @DisplayName("Write transact file persists transaction correctly"
            + " — maps WRITE-TRANSACT-FILE EXEC CICS WRITE")
    void testWriteTransactFile_Success() {
        // Given: a fully-populated transaction to persist
        Transaction transaction = new Transaction(
                "0000000000000099", "01", 5001, "ONLINE", "Write test",
                new BigDecimal("75.50").setScale(2, RoundingMode.HALF_UP),
                "987654321", "Persist Merchant", "Persist City", "54321",
                VALID_CARD_NUM, VALID_TIMESTAMP, VALID_TIMESTAMP);

        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When: write the transaction record
        Transaction saved = transactionAddService
                .writeTransactFile(transaction);

        // Then: repository.save was invoked and returned correct entity
        verify(transactionRepository).save(any(Transaction.class));
        assertThat(saved.getTranId()).isEqualTo("0000000000000099");
        assertThat(saved.getAmount())
                .isEqualByComparingTo(new BigDecimal("75.50"));
        assertThat(saved.getSource()).isEqualTo("ONLINE");
        assertThat(saved.getCardNum()).isEqualTo(VALID_CARD_NUM);
    }

    /* ================================================================== */
    /*  TEST 11 — Add Transaction: XREF Validation Fails                  */
    /*  Maps: VALIDATE-INPUT-KEY-FIELDS → XREF not found → no write      */
    /* ================================================================== */

    @Test
    @DisplayName("XREF validation failure prevents transaction write"
            + " — maps VALIDATE-INPUT-KEY-FIELDS XREF NOTFND → no WRITE")
    void testAddTransaction_XrefValidationFails() {
        // Given: no cross-reference for the account — XREF lookup fails
        when(cardXrefRepository.findByAccountId("00000000999"))
                .thenReturn(List.of());

        TransactionAddService.TransactionAddRequest request =
                new TransactionAddService.TransactionAddRequest(
                        "00000000999", null,  // invalid account, no card
                        "01", "5001", "ONLINE",
                        "Test purchase", "100.00",
                        "2025-06-15", "2025-06-15",
                        "123456789", "Test Merchant",
                        "Test City", "12345", "Y");

        // When / Then: ValidationException thrown, save never called
        assertThatThrownBy(() ->
                transactionAddService.processEnterKey(request))
                .isInstanceOf(ValidationException.class);

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /* ================================================================== */
    /*  TEST 12 — Read CXACAIX File: Success                              */
    /*  Maps: READ-CXACAIX-FILE → AIX cross-reference lookup              */
    /* ================================================================== */

    @Test
    @DisplayName("CXACAIX read returns cross-reference for valid account"
            + " — maps READ-CXACAIX-FILE AIX lookup")
    void testReadCxacaixFile_Success() {
        // Given: cross-reference exists
        when(cardXrefRepository.findByAccountId(VALID_ACCOUNT_ID))
                .thenReturn(List.of(testCardXref));

        // When: read the CXACAIX alternate index
        CardXref result =
                transactionAddService.readCxacaixFile(VALID_ACCOUNT_ID);

        // Then: returned XREF matches the fixture
        assertThat(result).isNotNull();
        assertThat(result.getXrefCardNum()).isEqualTo(VALID_CARD_NUM);
        assertThat(result.getAccountId()).isEqualTo(VALID_ACCOUNT_ID);
    }

    /* ================================================================== */
    /*  TEST 13 — Read CCXREF File: Success                               */
    /*  Maps: READ-CCXREF-FILE → card number cross-reference lookup       */
    /* ================================================================== */

    @Test
    @DisplayName("CCXREF read returns cross-reference for valid card number"
            + " — maps READ-CCXREF-FILE lookup")
    void testReadCcxrefFile_Success() {
        // Given: cross-reference exists for the card number
        when(cardXrefRepository.findByXrefCardNum(VALID_CARD_NUM))
                .thenReturn(Optional.of(testCardXref));

        // When: read the CCXREF file by card number
        CardXref result =
                transactionAddService.readCcxrefFile(VALID_CARD_NUM);

        // Then: returned XREF matches
        assertThat(result).isNotNull();
        assertThat(result.getXrefCardNum()).isEqualTo(VALID_CARD_NUM);
        assertThat(result.getAccountId()).isEqualTo(VALID_ACCOUNT_ID);
    }
}
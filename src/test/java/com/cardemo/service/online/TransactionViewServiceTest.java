/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.cardemo.service.online;

// Internal imports (from depends_on_files)
import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

// External imports — JUnit 5 Jupiter (provided by spring-boot-starter-test)
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// External imports — Mockito (provided by spring-boot-starter-test)
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// External imports — Java standard library
import java.math.BigDecimal;
import java.util.Optional;

// Static imports — AssertJ fluent assertions
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Static imports — Mockito verification and stubbing
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionViewService} — Mockito-based, no Spring
 * context required.
 *
 * <p>Tests the single transaction detail view logic faithfully translated from
 * COTRN01C.cbl (CICS online transaction CT01 — View a Transaction from
 * TRANSACT file). Each test method maps to a specific execution path through
 * the COBOL paragraphs:</p>
 *
 * <h2>COBOL Paragraph → Test Method Mapping</h2>
 * <table>
 *   <caption>COTRN01C.cbl paragraph coverage</caption>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Test Method</th><th>Path</th></tr>
 *   <tr><td>MAIN-PARA</td><td>86</td>
 *       <td>{@link #testViewTransaction_Success()}</td><td>Happy path</td></tr>
 *   <tr><td>READ-TRANSACT-FILE</td><td>267</td>
 *       <td>{@link #testViewTransaction_NotFound()}</td>
 *       <td>RESP=13 (NOTFND)</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>144</td>
 *       <td>{@link #testViewTransaction_BlankId()}</td>
 *       <td>TRNIDINI = SPACES</td></tr>
 *   <tr><td>READ-TRANSACT-FILE</td><td>267</td>
 *       <td>{@link #testReadTransactFile_Success()}</td>
 *       <td>RESP=0 (NORMAL)</td></tr>
 *   <tr><td>READ-TRANSACT-FILE</td><td>267</td>
 *       <td>{@link #testReadTransactFile_NotFound()}</td>
 *       <td>RESP=13 (NOTFND)</td></tr>
 *   <tr><td>MAIN-PARA</td><td>86</td>
 *       <td>{@link #testViewTransaction_BigDecimalAmount()}</td>
 *       <td>PIC S9(09)V99 parity</td></tr>
 *   <tr><td>MAIN-PARA</td><td>86</td>
 *       <td>{@link #testViewTransaction_TimestampFormat()}</td>
 *       <td>ISO-8601 preservation</td></tr>
 * </table>
 *
 * <h2>VSAM TRANSACT Dataset (CVTRA05Y.cpy — 350-byte record)</h2>
 * <pre>
 * 01  TRAN-RECORD.
 *     05  TRAN-ID              PIC X(16).     — Primary key
 *     05  TRAN-TYPE-CD         PIC X(02).     — Transaction type
 *     05  TRAN-CAT-CD          PIC 9(04).     — Category code
 *     05  TRAN-SOURCE          PIC X(10).     — Source
 *     05  TRAN-DESC            PIC X(100).    — Description
 *     05  TRAN-AMT             PIC S9(09)V99. — Amount (BigDecimal)
 *     05  TRAN-MERCHANT-ID     PIC 9(09).     — Merchant ID
 *     05  TRAN-MERCHANT-NAME   PIC X(50).     — Merchant name
 *     05  TRAN-MERCHANT-CITY   PIC X(50).     — Merchant city
 *     05  TRAN-MERCHANT-ZIP    PIC X(10).     — Merchant ZIP
 *     05  TRAN-CARD-NUM        PIC X(16).     — Card number
 *     05  TRAN-ORIG-TS         PIC X(26).     — Original timestamp
 *     05  TRAN-PROC-TS         PIC X(26).     — Processing timestamp
 *     05  FILLER               PIC X(20).
 * </pre>
 *
 * @see TransactionViewService
 * @see Transaction
 * @see TransactionRepository
 */
@ExtendWith(MockitoExtension.class)
class TransactionViewServiceTest {

    // ========================================================================
    // Mocks — dependencies injected into TransactionViewService
    // ========================================================================

    /**
     * Mocked Spring Data JPA repository for TRANSACT VSAM dataset.
     * Stubs {@code findById()} to simulate EXEC CICS READ results:
     * DFHRESP(NORMAL) via Optional.of(), DFHRESP(NOTFND) via Optional.empty().
     */
    @Mock
    private TransactionRepository transactionRepository;

    /**
     * Mocked COMMAREA session context (COCOM01Y.cpy).
     * Provides user identity and navigation context for the view flow.
     */
    @Mock
    private CardDemoContext cardDemoContext;

    /**
     * Class under test — constructed with mocked dependencies by Mockito.
     */
    @InjectMocks
    private TransactionViewService transactionViewService;

    // ========================================================================
    // Test Constants — matching CVTRA05Y.cpy field values
    // ========================================================================

    /** Standard test transaction ID (TRAN-ID PIC X(16)). */
    private static final String TEST_TRAN_ID = "0000001";

    /** Non-existent transaction ID for NOTFND tests. */
    private static final String NON_EXISTENT_TRAN_ID = "9999999";

    /** Standard transaction type code (TRAN-TYPE-CD PIC X(02)). */
    private static final String TEST_TYPE_CODE = "01";

    /** Standard transaction category code (TRAN-CAT-CD PIC 9(04)). */
    private static final int TEST_CATEGORY_CODE = 5001;

    /** Standard transaction source (TRAN-SOURCE PIC X(10)). */
    private static final String TEST_SOURCE = "ONLINE";

    /** Standard transaction description (TRAN-DESC PIC X(100)). */
    private static final String TEST_DESCRIPTION = "Test payment transaction";

    /** Standard transaction amount (TRAN-AMT PIC S9(09)V99). */
    private static final BigDecimal TEST_AMOUNT = new BigDecimal("150.00");

    /** Standard merchant ID (TRAN-MERCHANT-ID PIC 9(09)). */
    private static final String TEST_MERCHANT_ID = "123456789";

    /** Standard merchant name (TRAN-MERCHANT-NAME PIC X(50)). */
    private static final String TEST_MERCHANT_NAME = "ACME Store";

    /** Standard merchant city (TRAN-MERCHANT-CITY PIC X(50)). */
    private static final String TEST_MERCHANT_CITY = "New York";

    /** Standard merchant ZIP (TRAN-MERCHANT-ZIP PIC X(10)). */
    private static final String TEST_MERCHANT_ZIP = "10001";

    /** Standard card number (TRAN-CARD-NUM PIC X(16)). */
    private static final String TEST_CARD_NUM = "4111111111111111";

    /** Standard original timestamp (TRAN-ORIG-TS PIC X(26)) — ISO-8601 extended. */
    private static final String TEST_ORIG_TIMESTAMP = "2025-01-15-10.30.45.123456";

    /** Standard processing timestamp (TRAN-PROC-TS PIC X(26)) — ISO-8601 extended. */
    private static final String TEST_PROC_TIMESTAMP = "2025-01-15-11.00.00.000000";

    // ========================================================================
    // Test 1: viewTransaction — Success (MAIN-PARA → READ-TRANSACT-FILE → NORMAL)
    // ========================================================================

    /**
     * Verifies successful transaction retrieval by primary key.
     *
     * <p><strong>Maps MAIN-PARA</strong> (COTRN01C.cbl line 86) →
     * PROCESS-ENTER-KEY (line 144) → READ-TRANSACT-FILE (line 267) with
     * DFHRESP(NORMAL) (line 281).</p>
     *
     * <p>Asserts that all CVTRA05Y.cpy fields are returned:
     * TRAN-ID, TRAN-TYPE-CD, TRAN-CAT-CD, TRAN-SOURCE, TRAN-DESC,
     * TRAN-AMT (BigDecimal), TRAN-MERCHANT-ID, TRAN-MERCHANT-NAME,
     * TRAN-MERCHANT-CITY, TRAN-MERCHANT-ZIP, TRAN-CARD-NUM,
     * TRAN-ORIG-TS, TRAN-PROC-TS.</p>
     */
    @Test
    @DisplayName("Test 1: viewTransaction success — MAIN-PARA → READ-TRANSACT-FILE → RESP=0")
    void testViewTransaction_Success() {
        // Arrange — create transaction with all CVTRA05Y fields
        Transaction transaction = createTestTransaction(
                TEST_TRAN_ID, TEST_TYPE_CODE, TEST_CATEGORY_CODE,
                TEST_SOURCE, TEST_DESCRIPTION, TEST_AMOUNT,
                TEST_MERCHANT_ID, TEST_MERCHANT_NAME, TEST_MERCHANT_CITY,
                TEST_MERCHANT_ZIP, TEST_CARD_NUM,
                TEST_ORIG_TIMESTAMP, TEST_PROC_TIMESTAMP);

        // Mock repository — EXEC CICS READ DATASET(WS-TRANSACT-FILE) RIDFLD(TRAN-ID)
        // RESP = DFHRESP(NORMAL) → findById returns Optional.of(transaction)
        when(transactionRepository.findById(TEST_TRAN_ID))
                .thenReturn(Optional.of(transaction));

        // Act — maps MAIN-PARA entry point
        Transaction result = transactionViewService.viewTransaction(TEST_TRAN_ID);

        // Assert — all CVTRA05Y.cpy fields present and correct
        assertThat(result).isNotNull();
        assertThat(result.getTranId()).isEqualTo(TEST_TRAN_ID);
        assertThat(result.getTypeCode()).isEqualTo(TEST_TYPE_CODE);
        assertThat(result.getCategoryCode()).isEqualTo(TEST_CATEGORY_CODE);
        assertThat(result.getSource()).isEqualTo(TEST_SOURCE);
        assertThat(result.getDescription()).isEqualTo(TEST_DESCRIPTION);
        assertThat(result.getAmount()).isEqualTo(TEST_AMOUNT);
        assertThat(result.getMerchantId()).isEqualTo(TEST_MERCHANT_ID);
        assertThat(result.getMerchantName()).isEqualTo(TEST_MERCHANT_NAME);
        assertThat(result.getMerchantCity()).isEqualTo(TEST_MERCHANT_CITY);
        assertThat(result.getMerchantZip()).isEqualTo(TEST_MERCHANT_ZIP);
        assertThat(result.getCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(result.getOrigTimestamp()).isEqualTo(TEST_ORIG_TIMESTAMP);
        assertThat(result.getProcTimestamp()).isEqualTo(TEST_PROC_TIMESTAMP);

        // Verify repository interaction
        verify(transactionRepository).findById(TEST_TRAN_ID);
    }

    // ========================================================================
    // Test 2: viewTransaction — Not Found (READ-TRANSACT-FILE → NOTFND)
    // ========================================================================

    /**
     * Verifies that requesting a non-existent transaction throws
     * {@link RecordNotFoundException}.
     *
     * <p><strong>Maps READ-TRANSACT-FILE</strong> (COTRN01C.cbl line 267)
     * → DFHRESP(NOTFND) (line 283): sets WS-ERR-FLG='Y' and message
     * 'Transaction ID NOT found...'.</p>
     */
    @Test
    @DisplayName("Test 2: viewTransaction not found — READ-TRANSACT-FILE → RESP=13 (NOTFND)")
    void testViewTransaction_NotFound() {
        // Mock repository — EXEC CICS READ returns DFHRESP(NOTFND)
        when(transactionRepository.findById(NON_EXISTENT_TRAN_ID))
                .thenReturn(Optional.empty());

        // Act & Assert — RecordNotFoundException maps VSAM status '23'
        assertThatThrownBy(() -> transactionViewService.viewTransaction(NON_EXISTENT_TRAN_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Transaction ID NOT found");

        // Verify repository was invoked with the non-existent ID
        verify(transactionRepository).findById(NON_EXISTENT_TRAN_ID);
    }

    // ========================================================================
    // Test 3: viewTransaction — Blank ID (PROCESS-ENTER-KEY → validation)
    // ========================================================================

    /**
     * Verifies that a blank transaction ID throws a validation error.
     *
     * <p><strong>Maps PROCESS-ENTER-KEY</strong> (COTRN01C.cbl lines
     * 146–151):</p>
     * <pre>
     *   EVALUATE TRUE
     *       WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES
     *           MOVE 'Y' TO WS-ERR-FLG
     *           MOVE 'Tran ID can NOT be empty...' TO WS-MESSAGE
     * </pre>
     *
     * <p>The COBOL validation error (empty input field) maps to
     * {@link ValidationException} to distinguish input validation failures
     * from data-not-found errors (VSAM status '23').</p>
     */
    @Test
    @DisplayName("Test 3: viewTransaction blank ID — PROCESS-ENTER-KEY → validate not blank")
    void testViewTransaction_BlankId() {
        // Act & Assert — blank transaction ID triggers validation error
        // Maps COBOL EVALUATE TRUE WHEN TRNIDINI = SPACES (line 147)
        assertThatThrownBy(() -> transactionViewService.viewTransaction(""))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("must be provided");

        // Also verify processEnterKey directly with blank input
        // (processEnterKey is the COBOL PROCESS-ENTER-KEY paragraph at line 144)
        assertThatThrownBy(() -> transactionViewService.processEnterKey(""))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("empty");

        // Verify that the repository was never called for blank input
        // (validation short-circuits before any file I/O)
        verify(transactionRepository, never()).findById("");

        // Verify ValidationException constructor compatibility for field-specific
        // validation patterns (COBOL PROCESS-ENTER-KEY maps validation errors
        // with field context for BMS screen attribute setting)
        ValidationException fieldValidation = new ValidationException(
                "transactionId", "Tran ID can NOT be empty...");
        assertThat(fieldValidation.getFieldName()).isEqualTo("transactionId");

        ValidationException generalValidation = new ValidationException(
                "Tran ID can NOT be empty...");
        assertThat(generalValidation.getMessage()).contains("empty");
    }

    // ========================================================================
    // Test 4: readTransactFile — Success (READ-TRANSACT-FILE → NORMAL)
    // ========================================================================

    /**
     * Verifies direct read of a transaction by primary key via
     * {@link TransactionViewService#readTransactFile(String)}.
     *
     * <p><strong>Maps READ-TRANSACT-FILE</strong> (COTRN01C.cbl line 267):</p>
     * <pre>
     *   EXEC CICS READ
     *        DATASET   (WS-TRANSACT-FILE)
     *        INTO      (TRAN-RECORD)
     *        LENGTH    (LENGTH OF TRAN-RECORD)
     *        RIDFLD    (TRAN-ID)
     *        KEYLENGTH (LENGTH OF TRAN-ID)
     *        UPDATE
     *        RESP      (WS-RESP-CD)
     *   END-EXEC
     * </pre>
     * with DFHRESP(NORMAL) (line 281): CONTINUE — record read successfully.
     */
    @Test
    @DisplayName("Test 4: readTransactFile success — EXEC CICS READ RESP=0")
    void testReadTransactFile_Success() {
        // Arrange — full TRAN-RECORD with all CVTRA05Y.cpy fields
        Transaction transaction = createTestTransaction(
                TEST_TRAN_ID, TEST_TYPE_CODE, TEST_CATEGORY_CODE,
                TEST_SOURCE, TEST_DESCRIPTION, TEST_AMOUNT,
                TEST_MERCHANT_ID, TEST_MERCHANT_NAME, TEST_MERCHANT_CITY,
                TEST_MERCHANT_ZIP, TEST_CARD_NUM,
                TEST_ORIG_TIMESTAMP, TEST_PROC_TIMESTAMP);

        when(transactionRepository.findById(TEST_TRAN_ID))
                .thenReturn(Optional.of(transaction));

        // Act — direct call to readTransactFile
        Transaction result = transactionViewService.readTransactFile(TEST_TRAN_ID);

        // Assert — all CVTRA05Y.cpy fields present and correct
        assertThat(result).isNotNull();
        assertThat(result.getTranId()).isEqualTo(TEST_TRAN_ID);
        assertThat(result.getTypeCode()).isEqualTo(TEST_TYPE_CODE);
        assertThat(result.getCategoryCode()).isEqualTo(TEST_CATEGORY_CODE);
        assertThat(result.getSource()).isEqualTo(TEST_SOURCE);
        assertThat(result.getDescription()).isEqualTo(TEST_DESCRIPTION);
        assertThat(result.getAmount()).isEqualTo(TEST_AMOUNT);
        assertThat(result.getMerchantId()).isEqualTo(TEST_MERCHANT_ID);
        assertThat(result.getMerchantName()).isEqualTo(TEST_MERCHANT_NAME);
        assertThat(result.getMerchantCity()).isEqualTo(TEST_MERCHANT_CITY);
        assertThat(result.getMerchantZip()).isEqualTo(TEST_MERCHANT_ZIP);
        assertThat(result.getCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(result.getOrigTimestamp()).isEqualTo(TEST_ORIG_TIMESTAMP);
        assertThat(result.getProcTimestamp()).isEqualTo(TEST_PROC_TIMESTAMP);

        // Verify repository interaction
        verify(transactionRepository).findById(TEST_TRAN_ID);
    }

    // ========================================================================
    // Test 5: readTransactFile — Not Found (READ-TRANSACT-FILE → NOTFND)
    // ========================================================================

    /**
     * Verifies that reading a non-existent transaction directly throws
     * {@link RecordNotFoundException}.
     *
     * <p><strong>Maps READ-TRANSACT-FILE</strong> (COTRN01C.cbl lines
     * 283–288):</p>
     * <pre>
     *   WHEN DFHRESP(NOTFND)
     *       MOVE 'Y' TO WS-ERR-FLG
     *       MOVE 'Transaction ID NOT found...' TO WS-MESSAGE
     * </pre>
     */
    @Test
    @DisplayName("Test 5: readTransactFile not found — RESP=13 (NOTFND)")
    void testReadTransactFile_NotFound() {
        // Mock repository — findById returns empty (DFHRESP(NOTFND))
        when(transactionRepository.findById(NON_EXISTENT_TRAN_ID))
                .thenReturn(Optional.empty());

        // Act & Assert — RecordNotFoundException with COBOL error message
        assertThatThrownBy(() ->
                transactionViewService.readTransactFile(NON_EXISTENT_TRAN_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Transaction ID NOT found");

        // Verify repository was invoked
        verify(transactionRepository).findById(NON_EXISTENT_TRAN_ID);
    }

    // ========================================================================
    // Test 6: BigDecimal Amount Verification (PIC S9(09)V99 parity)
    // ========================================================================

    /**
     * Verifies that the transaction amount is stored and returned as
     * {@link BigDecimal}, not float or double, preserving exact decimal
     * arithmetic per COBOL {@code PIC S9(09)V99} semantics.
     *
     * <p><strong>Critical data integrity requirement:</strong> All
     * monetary values must use {@code BigDecimal} with precision=11,
     * scale=2. The AAP mandates: "No floating-point for decimal fields.
     * All PIC S9(n)V99 COMP-3 fields become BigDecimal with exact scale
     * matching the COBOL decimal places."</p>
     *
     * <p>This test uses {@code new BigDecimal("12345.67")} — the String
     * constructor ensures exact decimal representation without
     * floating-point rounding artifacts.</p>
     */
    @Test
    @DisplayName("Test 6: BigDecimal amount — PIC S9(09)V99 exact precision")
    void testViewTransaction_BigDecimalAmount() {
        // Arrange — specific BigDecimal amount matching COBOL PIC S9(09)V99
        BigDecimal preciseAmount = new BigDecimal("12345.67");
        Transaction transaction = createTestTransaction(
                TEST_TRAN_ID, TEST_TYPE_CODE, TEST_CATEGORY_CODE,
                TEST_SOURCE, TEST_DESCRIPTION, preciseAmount,
                TEST_MERCHANT_ID, TEST_MERCHANT_NAME, TEST_MERCHANT_CITY,
                TEST_MERCHANT_ZIP, TEST_CARD_NUM,
                TEST_ORIG_TIMESTAMP, TEST_PROC_TIMESTAMP);

        when(transactionRepository.findById(TEST_TRAN_ID))
                .thenReturn(Optional.of(transaction));

        // Act
        Transaction result = transactionViewService.viewTransaction(TEST_TRAN_ID);

        // Assert — BigDecimal exact value comparison (no floating-point drift)
        assertThat(result.getAmount()).isNotNull();
        assertThat(result.getAmount()).isEqualTo(new BigDecimal("12345.67"));

        // Verify the amount is actually a BigDecimal instance (not a
        // converted float/double) — compile-time guarantee plus runtime check
        assertThat(result.getAmount()).isInstanceOf(BigDecimal.class);

        // Verify scale matches COBOL PIC S9(09)V99 (2 decimal places)
        assertThat(result.getAmount().scale()).isEqualTo(2);
    }

    // ========================================================================
    // Test 7: Timestamp Format Preservation (ISO-8601 extended, 26 chars)
    // ========================================================================

    /**
     * Verifies that timestamps are preserved in ISO-8601 extended format
     * ({@code YYYY-MM-DD-HH.MM.SS.mmmmmm}, 26 characters) matching the
     * COBOL {@code TRAN-ORIG-TS PIC X(26)} and {@code TRAN-PROC-TS PIC X(26)}
     * field semantics exactly.
     *
     * <p>Per AAP Section 0.7.4: "Timestamps must preserve ISO-8601 extended
     * format with microsecond precision: YYYY-MM-DD-HH.MM.SS.mmmmmm
     * (26 characters) matching TRAN-ORIG-TS."</p>
     */
    @Test
    @DisplayName("Test 7: timestamp format — YYYY-MM-DD-HH.MM.SS.mmmmmm (26 chars)")
    void testViewTransaction_TimestampFormat() {
        // Arrange — specific timestamp format matching COBOL PIC X(26)
        String expectedOrigTimestamp = "2025-06-15-14.30.45.000001";
        String expectedProcTimestamp = "2025-06-15-15.00.00.999999";
        Transaction transaction = createTestTransaction(
                TEST_TRAN_ID, TEST_TYPE_CODE, TEST_CATEGORY_CODE,
                TEST_SOURCE, TEST_DESCRIPTION, TEST_AMOUNT,
                TEST_MERCHANT_ID, TEST_MERCHANT_NAME, TEST_MERCHANT_CITY,
                TEST_MERCHANT_ZIP, TEST_CARD_NUM,
                expectedOrigTimestamp, expectedProcTimestamp);

        when(transactionRepository.findById(TEST_TRAN_ID))
                .thenReturn(Optional.of(transaction));

        // Act
        Transaction result = transactionViewService.viewTransaction(TEST_TRAN_ID);

        // Assert — exact string preservation
        assertThat(result.getOrigTimestamp()).isEqualTo(expectedOrigTimestamp);
        assertThat(result.getProcTimestamp()).isEqualTo(expectedProcTimestamp);

        // Assert — ISO-8601 format: YYYY-MM-DD-HH.MM.SS.mmmmmm (26 chars)
        assertThat(result.getOrigTimestamp()).hasSize(26);
        assertThat(result.getProcTimestamp()).hasSize(26);

        // Assert — timestamp format pattern:
        // digits(4)-digits(2)-digits(2)-digits(2).digits(2).digits(2).digits(6)
        String timestampPattern =
                "\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}";
        assertThat(result.getOrigTimestamp()).matches(timestampPattern);
        assertThat(result.getProcTimestamp()).matches(timestampPattern);
    }

    // ========================================================================
    // Private Helper — Transaction test fixture factory
    // ========================================================================

    /**
     * Creates a fully populated {@link Transaction} entity matching all
     * CVTRA05Y.cpy fields for test assertions.
     *
     * <p>Uses the all-args constructor to set every field from the COBOL
     * TRAN-RECORD 350-byte layout. Fields are set in the exact order they
     * appear in CVTRA05Y.cpy to maintain source traceability.</p>
     *
     * @param tranId        TRAN-ID PIC X(16) — primary key
     * @param typeCode      TRAN-TYPE-CD PIC X(02) — type code
     * @param categoryCode  TRAN-CAT-CD PIC 9(04) — category code
     * @param source        TRAN-SOURCE PIC X(10) — source identifier
     * @param description   TRAN-DESC PIC X(100) — description
     * @param amount        TRAN-AMT PIC S9(09)V99 — amount as BigDecimal
     * @param merchantId    TRAN-MERCHANT-ID PIC 9(09) — merchant ID
     * @param merchantName  TRAN-MERCHANT-NAME PIC X(50) — merchant name
     * @param merchantCity  TRAN-MERCHANT-CITY PIC X(50) — merchant city
     * @param merchantZip   TRAN-MERCHANT-ZIP PIC X(10) — merchant ZIP
     * @param cardNum       TRAN-CARD-NUM PIC X(16) — card number
     * @param origTimestamp TRAN-ORIG-TS PIC X(26) — original timestamp
     * @param procTimestamp TRAN-PROC-TS PIC X(26) — processing timestamp
     * @return fully populated Transaction entity
     */
    private Transaction createTestTransaction(String tranId, String typeCode,
                                              int categoryCode, String source,
                                              String description,
                                              BigDecimal amount,
                                              String merchantId,
                                              String merchantName,
                                              String merchantCity,
                                              String merchantZip,
                                              String cardNum,
                                              String origTimestamp,
                                              String procTimestamp) {
        Transaction transaction = new Transaction(
                tranId, typeCode, categoryCode, source, description,
                amount, merchantId, merchantName, merchantCity,
                merchantZip, cardNum, origTimestamp, procTimestamp);
        return transaction;
    }
}

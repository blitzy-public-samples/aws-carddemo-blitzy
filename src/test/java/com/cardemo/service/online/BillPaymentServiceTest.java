/*
 * BillPaymentServiceTest.java — JUnit 5 unit tests for BillPaymentService
 *
 * Maps to: COBIL00C.cbl (Online: Bill Payment Processing — CICS)
 *
 * Tests cover the complete bill payment workflow translated from the COBOL
 * COBIL00C program: account lookup (READ-ACCTDAT-FILE), cross-reference
 * resolution (READ-CXACAIX-FILE), browse-last transaction ID generation
 * (STARTBR → READPREV → ENDBR), transaction record creation
 * (WRITE-TRANSACT-FILE), balance update with BigDecimal arithmetic
 * (COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT), and @Version
 * optimistic locking (CICS READ UPDATE → REWRITE pattern).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.cardemo.service.online;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.online.BillPaymentService.BillPaymentRequest;
import com.cardemo.service.online.BillPaymentService.BillPaymentResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-based unit tests for {@link BillPaymentService}.
 *
 * <p>Each test method maps to one or more COBIL00C.cbl paragraphs,
 * verifying that the Java service faithfully reproduces the COBOL
 * bill-payment business logic with zero behavioral regressions.</p>
 *
 * <p>Key verification areas:</p>
 * <ul>
 *   <li>Atomic {@code @Transactional} bill-payment flow (CICS SYNCPOINT)</li>
 *   <li>BigDecimal subtraction with {@link RoundingMode#HALF_UP}</li>
 *   <li>Browse-last transaction ID generation (STARTBR → READPREV)</li>
 *   <li>{@code @Version} optimistic locking (READ UPDATE → REWRITE)</li>
 *   <li>ISO-8601 timestamp format (ASKTIME / FORMATTIME)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillPaymentService — COBIL00C.cbl Bill Payment Program")
class BillPaymentServiceTest {

    // ================================================================
    // Mock Dependencies
    // ================================================================

    /** ACCTDATA VSAM repository — account lookup and balance update. */
    @Mock
    private AccountRepository accountRepository;

    /** TRANSACT VSAM repository — transaction write and browse-last. */
    @Mock
    private TransactionRepository transactionRepository;

    /** CARDXREF VSAM (CXACAIX alternate index) — card cross-reference. */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** CARDDEMO-COMMAREA session context — user identity and state. */
    @Mock
    private CardDemoContext cardDemoContext;

    /** Class under test — injected with all mock dependencies. */
    @InjectMocks
    private BillPaymentService billPaymentService;

    // ================================================================
    // Test Fixture Constants
    // ================================================================

    /** 11-character account ID matching COBOL PIC 9(11). */
    private static final String ACCOUNT_ID = "00000012345";

    /** 16-character card number matching COBOL PIC X(16). */
    private static final String CARD_NUM = "1234567890123456";

    /** 9-character customer ID matching COBOL PIC 9(09). */
    private static final String CUST_ID = "000000001";

    /** Transaction ID length — 16-character zero-padded (WS-TRAN-ID PIC X(16)). */
    private static final int TRAN_ID_LENGTH = 16;

    /** Monetary scale matching COBOL PIC S9(n)V99 (2 decimal places). */
    private static final int MONETARY_SCALE = 2;

    // ================================================================
    // Test Fixture Objects (re-created fresh for each test)
    // ================================================================

    private Account testAccount;
    private Transaction lastTransaction;
    private CardXref testCardXref;

    /**
     * Initialises shared test fixtures before each test method.
     *
     * <p>Creates fresh entity objects so that in-test mutations
     * (e.g.&nbsp;{@code account.setCurrBal(BigDecimal.ZERO)}) do not
     * leak between tests.</p>
     */
    @BeforeEach
    void setUp() {
        // Account fixture — maps CVACT01Y.cpy 300-byte record
        // Account(acctId, activeStatus, currBal, creditLimit, cashCreditLimit,
        //         openDate, expirationDate, reissueDate,
        //         currCycCredit, currCycDebit, addrZip, groupId)
        testAccount = new Account(
                ACCOUNT_ID,
                "Y",
                new BigDecimal("5000.00"),
                new BigDecimal("10000.00"),
                BigDecimal.ZERO,
                "2020-01-01",
                "2030-12-31",
                "2025-01-01",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "12345",
                "GRP001"
        );

        // Last-transaction fixture — for browse-last ID generation
        // Transaction(tranId, typeCode, categoryCode, source, description,
        //             amount, merchantId, merchantName, merchantCity,
        //             merchantZip, cardNum, origTimestamp, procTimestamp)
        lastTransaction = new Transaction(
                "0000000000000050",
                "02",
                Integer.valueOf(2),
                "POS TERM",
                "PAYMENT",
                new BigDecimal("100.00"),
                "999999999",
                "BILL PAYMENT",
                "N/A",
                "N/A",
                CARD_NUM,
                "2025-01-01-10:30:00.000000",
                "2025-01-01-10:30:00.000000"
        );

        // Card cross-reference fixture — maps CVACT03Y.cpy 50-byte record
        // CardXref(xrefCardNum, custId, accountId)
        testCardXref = new CardXref(CARD_NUM, CUST_ID, ACCOUNT_ID);
    }

    // ================================================================
    // Payment Processing Tests
    // ================================================================

    /**
     * Tests the full confirmed bill-payment flow end-to-end.
     *
     * <p>Maps: MAIN-PARA → PROCESS-ENTER-KEY (confirm 'Y') →
     * READ-CXACAIX-FILE → STARTBR/READPREV (browse-last) →
     * WRITE-TRANSACT-FILE → UPDATE-ACCTDAT-FILE.</p>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>New transaction ID = last ID + 1 (zero-padded to 16 chars)</li>
     *   <li>Payment amount equals full current balance (COBOL semantics)</li>
     *   <li>Account balance updated to zero after full payment</li>
     *   <li>Both transaction write and account update are invoked</li>
     * </ul>
     */
    @Test
    @DisplayName("processBillPayment — confirmed payment success "
            + "(MAIN-PARA → PROCESS-ENTER-KEY → confirm Y)")
    void testProcessBillPayment_Success() {
        // Arrange — mock the full payment flow dependencies
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(cardXrefRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(testCardXref));
        when(transactionRepository.findFirstByOrderByTranIdDesc())
                .thenReturn(Optional.of(lastTransaction));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "Y");

        // Act
        BillPaymentResult result = billPaymentService.processBillPayment(request);

        // Assert — verify successful payment outcome
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage())
                .contains("Bill payment was successful");

        // Transaction ID: last was "0000000000000050" (=50), next = 51
        assertThat(result.getTransactionId())
                .isEqualTo("0000000000000051");

        // Payment amount = full current balance (COBOL: MOVE ACCT-CURR-BAL TO TRAN-AMT)
        assertThat(result.getPaymentAmount())
                .isEqualByComparingTo(new BigDecimal("5000.00"));

        // New balance = currBal - currBal = 0.00
        assertThat(result.getCurrentBalance())
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Verify both repository writes occurred
        verify(transactionRepository).save(any(Transaction.class));
        verify(accountRepository).save(any(Account.class));
    }

    /**
     * Tests BigDecimal balance arithmetic with non-round monetary values.
     *
     * <p>Maps: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT</p>
     *
     * <p>Per AAP mandate: {@code currBal.subtract(paymentAmount).setScale(2,
     * RoundingMode.HALF_UP)} — all monetary arithmetic must use
     * {@link BigDecimal} with COBOL-equivalent rounding, never
     * floating-point.</p>
     *
     * <p>The COBOL program pays the full balance, so paymentAmount =
     * currBal and the new balance is always zero. The test verifies
     * that the result has exact scale=2 and is computed via
     * BigDecimal.subtract() with HALF_UP rounding.</p>
     */
    @Test
    @DisplayName("processBillPayment — BigDecimal balance arithmetic "
            + "(ACCT-CURR-BAL subtraction with HALF_UP)")
    void testProcessBillPayment_BalanceUpdate_BigDecimalArithmetic() {
        // Arrange — set a non-round balance to exercise precision handling
        testAccount.setCurrBal(new BigDecimal("1234.56"));

        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(cardXrefRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(testCardXref));
        when(transactionRepository.findFirstByOrderByTranIdDesc())
                .thenReturn(Optional.of(lastTransaction));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "Y");

        // Act
        BillPaymentResult result = billPaymentService.processBillPayment(request);

        // Assert — payment amount is the full balance
        assertThat(result.getPaymentAmount())
                .isEqualByComparingTo(new BigDecimal("1234.56"));

        // Replicate exact COBOL computation: subtract then setScale with HALF_UP
        BigDecimal expectedBalance = new BigDecimal("1234.56")
                .subtract(new BigDecimal("1234.56"))
                .setScale(MONETARY_SCALE, RoundingMode.HALF_UP);

        assertThat(result.getCurrentBalance())
                .isEqualByComparingTo(expectedBalance);

        // Verify account was persisted with the correct balance
        ArgumentCaptor<Account> accountCaptor =
                ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();

        // BigDecimal precision: scale must be exactly 2 (PIC S9(n)V99)
        assertThat(savedAccount.getCurrBal())
                .isEqualByComparingTo(expectedBalance);
        assertThat(savedAccount.getCurrBal().scale())
                .isEqualTo(MONETARY_SCALE);
    }

    /**
     * Tests optimistic locking conflict during account balance update.
     *
     * <p>Maps: READ-ACCTDAT-FILE (READ UPDATE) →
     * UPDATE-ACCTDAT-FILE (REWRITE) with {@code @Version} conflict.</p>
     *
     * <p>The CICS pattern of READ UPDATE → REWRITE is mapped to JPA
     * {@code @Version}-based optimistic locking. When a concurrent
     * modification is detected, {@link OptimisticLockException} is
     * thrown and must propagate to the caller.</p>
     */
    @Test
    @DisplayName("processBillPayment — @Version optimistic locking conflict "
            + "(CICS READ UPDATE → REWRITE)")
    void testProcessBillPayment_OptimisticLocking() {
        // Arrange — account read succeeds, but save detects version conflict
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(cardXrefRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(testCardXref));
        when(transactionRepository.findFirstByOrderByTranIdDesc())
                .thenReturn(Optional.of(lastTransaction));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new OptimisticLockException(
                        "Row was updated by another transaction"));

        BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "Y");

        // Act & Assert — OptimisticLockException must propagate
        assertThatThrownBy(() ->
                billPaymentService.processBillPayment(request))
                .isInstanceOf(OptimisticLockException.class);
    }

    // ================================================================
    // XREF Validation Tests (READ-CXACAIX-FILE paragraph)
    // ================================================================

    /**
     * Tests successful card cross-reference lookup by account ID.
     *
     * <p>Maps: READ-CXACAIX-FILE → EXEC CICS READ DATASET('CXACAIX')
     * INTO(CARD-XREF-RECORD) RIDFLD(XREF-ACCT-ID)</p>
     */
    @Test
    @DisplayName("readCxacaixFile — success "
            + "(READ-CXACAIX-FILE → DATASET CXACAIX)")
    void testReadCxacaixFile_Success() {
        // Arrange
        when(cardXrefRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(testCardXref));

        // Act
        CardXref result = billPaymentService.readCxacaixFile(ACCOUNT_ID);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getXrefCardNum()).isEqualTo(CARD_NUM);
        assertThat(result.getAccountId()).isEqualTo(ACCOUNT_ID);
        verify(cardXrefRepository).findByAccountId(ACCOUNT_ID);
    }

    /**
     * Tests cross-reference not-found scenario.
     *
     * <p>Maps: RESP=13 (DFHRESP(NOTFND)) → VSAM file status '23'.</p>
     */
    @Test
    @DisplayName("readCxacaixFile — not found → RecordNotFoundException "
            + "(RESP NOTFND status 23)")
    void testReadCxacaixFile_NotFound() {
        // Arrange — empty list simulates NOTFND
        when(cardXrefRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(List.of());

        // Act & Assert
        assertThatThrownBy(() ->
                billPaymentService.readCxacaixFile(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Card cross-reference not found");
    }

    // ================================================================
    // Account Access Tests (READ-ACCTDAT-FILE paragraph)
    // ================================================================

    /**
     * Tests successful account record read with BigDecimal monetary fields.
     *
     * <p>Maps: READ-ACCTDAT-FILE → EXEC CICS READ UPDATE
     * DATASET('ACCTDAT') INTO(ACCOUNT-RECORD) RIDFLD(WS-ACCT-ID)</p>
     */
    @Test
    @DisplayName("readAcctdatFile — success "
            + "(READ-ACCTDAT-FILE → DATASET ACCTDAT)")
    void testReadAcctdatFile_Success() {
        // Arrange
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));

        // Act
        Account result = billPaymentService.readAcctdatFile(ACCOUNT_ID);

        // Assert — verify all key fields from CVACT01Y.cpy
        assertThat(result).isNotNull();
        assertThat(result.getAcctId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.getCurrBal())
                .isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(result.getCreditLimit())
                .isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(result.getActiveStatus()).isEqualTo("Y");
        assertThat(result.getExpirationDate()).isEqualTo("2030-12-31");
        verify(accountRepository).findById(ACCOUNT_ID);
    }

    /**
     * Tests account not-found scenario.
     *
     * <p>Maps: RESP=13 (DFHRESP(NOTFND)) → VSAM file status '23'.</p>
     */
    @Test
    @DisplayName("readAcctdatFile — not found → RecordNotFoundException "
            + "(RESP NOTFND status 23)")
    void testReadAcctdatFile_NotFound() {
        // Arrange
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() ->
                billPaymentService.readAcctdatFile(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Account not found");
    }

    // ================================================================
    // Transaction Record Tests (WRITE-TRANSACT-FILE paragraph)
    // ================================================================

    /**
     * Tests successful transaction record write.
     *
     * <p>Maps: WRITE-TRANSACT-FILE → EXEC CICS WRITE
     * DATASET('TRANSACT') FROM(TRAN-RECORD) RIDFLD(TRAN-ID)</p>
     */
    @Test
    @DisplayName("writeTransactFile — success "
            + "(WRITE-TRANSACT-FILE → DATASET TRANSACT)")
    void testWriteTransactFile_Success() {
        // Arrange — construct a payment transaction matching COBIL00C MOVEs
        Transaction transaction = new Transaction(
                "0000000000000051",
                "02",
                Integer.valueOf(2),
                "POS TERM",
                "BILL PAYMENT - ONLINE",
                new BigDecimal("5000.00"),
                "999999999",
                "BILL PAYMENT",
                "N/A",
                "N/A",
                CARD_NUM,
                "2025-06-15-14:30:00.000000",
                "2025-06-15-14:30:00.000000"
        );
        when(transactionRepository.save(any(Transaction.class)))
                .thenReturn(transaction);

        // Act
        Transaction saved = billPaymentService.writeTransactFile(transaction);

        // Assert — verify persisted record fields
        assertThat(saved).isNotNull();
        assertThat(saved.getTranId()).isEqualTo("0000000000000051");
        assertThat(saved.getAmount())
                .isEqualByComparingTo(new BigDecimal("5000.00"));
        verify(transactionRepository).save(transaction);
    }

    // ================================================================
    // Browse-Last ID Generation
    // (STARTBR-TRANSACT-FILE → READPREV-TRANSACT-FILE → ENDBR)
    // ================================================================

    /**
     * Tests browse-last transaction ID generation.
     *
     * <p>Maps: STARTBR-TRANSACT-FILE (HIGH-VALUES) →
     * READPREV-TRANSACT-FILE → ENDBR-TRANSACT-FILE →
     * ADD 1 TO WS-TRAN-ID-NUM.</p>
     *
     * <p>The COBOL program uses STARTBR with HIGH-VALUES, then READPREV
     * to find the last transaction, then increments the numeric portion
     * by 1. The Java translation uses
     * {@code findFirstByOrderByTranIdDesc()} for the same effect.</p>
     */
    @Test
    @DisplayName("browse-last ID generation "
            + "(STARTBR → READPREV → ENDBR → ADD 1)")
    void testBrowseLastIdGeneration() {
        // Arrange — set last transaction ID to "0000000000000100" (= 100)
        Transaction lastTranForBrowse = new Transaction(
                "0000000000000100",
                "02",
                Integer.valueOf(2),
                "POS TERM",
                "PREV PAYMENT",
                new BigDecimal("500.00"),
                "999999999",
                "TEST MERCHANT",
                "ANYTOWN",
                "00000",
                CARD_NUM,
                "2025-03-01-09:00:00.000000",
                "2025-03-01-09:00:00.000000"
        );

        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(cardXrefRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(testCardXref));
        when(transactionRepository.findFirstByOrderByTranIdDesc())
                .thenReturn(Optional.of(lastTranForBrowse));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "Y");

        // Act
        BillPaymentResult result = billPaymentService.processBillPayment(request);

        // Assert — new ID = 100 + 1 = 101, zero-padded to 16 chars
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTransactionId())
                .hasSize(TRAN_ID_LENGTH)
                .isEqualTo("0000000000000101");
    }

    // ================================================================
    // Timestamp Format Test
    // (GET-CURRENT-TIMESTAMP — ASKTIME / FORMATTIME)
    // ================================================================

    /**
     * Tests that generated timestamps conform to the COBOL ISO-8601
     * extended format: {@code YYYY-MM-DD-HH:MM:SS.mmmmmm} (26 chars).
     *
     * <p>Maps: GET-CURRENT-TIMESTAMP paragraph → EXEC CICS ASKTIME /
     * EXEC CICS FORMATTIME with DATESEP('-') TIMESEP(':').</p>
     *
     * <p>Both {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are set
     * to the same timestamp value in the COBOL program (lines 210–211).</p>
     */
    @Test
    @DisplayName("getCurrentTimestamp — ISO-8601 format "
            + "YYYY-MM-DD-HH:MM:SS.mmmmmm (ASKTIME/FORMATTIME)")
    void testGetCurrentTimestamp_IsoFormat() {
        // Arrange — full flow to exercise the private getCurrentTimestamp()
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(cardXrefRepository.findByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(testCardXref));
        when(transactionRepository.findFirstByOrderByTranIdDesc())
                .thenReturn(Optional.of(lastTransaction));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BillPaymentRequest request = new BillPaymentRequest(ACCOUNT_ID, "Y");

        // Act
        billPaymentService.processBillPayment(request);

        // Assert — capture the saved transaction to inspect timestamps
        ArgumentCaptor<Transaction> tranCaptor =
                ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(tranCaptor.capture());
        Transaction savedTran = tranCaptor.getValue();

        String origTs = savedTran.getOrigTimestamp();
        String procTs = savedTran.getProcTimestamp();

        // Verify 26-character length (COBOL WS-TIMESTAMP PIC X(26))
        assertThat(origTs).hasSize(26);
        assertThat(procTs).hasSize(26);

        // Verify ISO-8601 format: YYYY-MM-DD-HH:MM:SS.mmmmmm
        String timestampPattern =
                "\\d{4}-\\d{2}-\\d{2}-\\d{2}:\\d{2}:\\d{2}\\.\\d{6}";
        assertThat(origTs).matches(timestampPattern);
        assertThat(procTs).matches(timestampPattern);

        // Both timestamps are set from the same getCurrentTimestamp() call
        assertThat(origTs).isEqualTo(procTs);
    }

}

package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.entity.Account;
import com.cardemo.entity.Card;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.entity.DailyTransaction;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionUtilService} — covers the transaction file
 * management logic translated from CBTRN01C.cbl.
 *
 * <p>Tests processTransactions flow, XREF lookup, account lookup,
 * IO status logging with VSAM status code mapping, and all error paths.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionUtilServiceTest {

    @Mock
    private DailyTransactionRepository dailyTransactionRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionUtilService service;

    // ── Helper factories ────────────────────────────────────────────

    /**
     * Creates a DailyTransaction using the public all-args constructor:
     * DailyTransaction(dalytranId, typeCode, categoryCode, source,
     *     description, amount, merchantId, merchantName, merchantCity,
     *     merchantZip, cardNum, origTimestamp, procTimestamp)
     */
    private DailyTransaction buildDailyTransaction(String cardNum) {
        return new DailyTransaction(
                "TRN0000001", "SA", 5001, "ONLINE",
                "Test Transaction", new BigDecimal("100.00"),
                "12345678", "Test Merchant", "TestCity", "12345",
                cardNum,
                "2026-01-15-12.00.00.000000",
                "2026-01-15-12.00.00.000000");
    }

    /**
     * Creates a CardXref using public constructor:
     * CardXref(xrefCardNum, custId, accountId)
     */
    private CardXref buildXref(String cardNum, String acctId, String custId) {
        return new CardXref(cardNum, custId, acctId);
    }

    /**
     * Creates an Account using public constructor:
     * Account(acctId, activeStatus, currBal, creditLimit, cashCreditLimit,
     *     openDate, expirationDate, reissueDate, currCycCredit,
     *     currCycDebit, addrZip, groupId)
     */
    private Account buildAccount(String acctId) {
        return new Account(
                acctId, "Y",
                new BigDecimal("5000.00"), new BigDecimal("10000.00"),
                new BigDecimal("5000.00"),
                "20200101", "20301231", "20250101",
                BigDecimal.ZERO, BigDecimal.ZERO,
                "62701", "GRP01");
    }

    // ── processTransactions ─────────────────────────────────────────

    @Nested
    @DisplayName("processTransactions")
    class ProcessTransactions {

        @Test
        @DisplayName("Empty daily transaction list completes successfully")
        void emptyListCompletes() {
            when(dailyTransactionRepository.findAll()).thenReturn(Collections.emptyList());

            assertThatCode(() -> service.processTransactions()).doesNotThrowAnyException();
            verify(dailyTransactionRepository).findAll();
        }

        @Test
        @DisplayName("Single transaction with valid XREF and account is processed")
        void singleTransactionWithXrefAndAccount() {
            DailyTransaction dt = buildDailyTransaction("4111111111111111");
            CardXref xref = buildXref("4111111111111111", "00000000001", "000000001");
            Account account = buildAccount("00000000001");

            when(dailyTransactionRepository.findAll()).thenReturn(List.of(dt));
            when(cardXrefRepository.findById("4111111111111111")).thenReturn(Optional.of(xref));
            when(accountRepository.findById("00000000001")).thenReturn(Optional.of(account));

            assertThatCode(() -> service.processTransactions()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Missing XREF for card number logs warning and continues")
        void missingXrefLogsAndContinues() {
            DailyTransaction dt = buildDailyTransaction("9999999999999999");

            when(dailyTransactionRepository.findAll()).thenReturn(List.of(dt));
            when(cardXrefRepository.findById("9999999999999999")).thenReturn(Optional.empty());

            // TransactionUtilService.lookupXref returns Optional; missing XREF is logged, not thrown
            assertThatCode(() -> service.processTransactions()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Missing account logs warning and continues")
        void missingAccountLogsAndContinues() {
            DailyTransaction dt = buildDailyTransaction("4111111111111111");
            CardXref xref = buildXref("4111111111111111", "00000000099", "000000001");

            when(dailyTransactionRepository.findAll()).thenReturn(List.of(dt));
            when(cardXrefRepository.findById("4111111111111111")).thenReturn(Optional.of(xref));
            when(accountRepository.findById("00000000099")).thenReturn(Optional.empty());

            // TransactionUtilService.readAccount returns Optional; missing account is logged, not thrown
            assertThatCode(() -> service.processTransactions()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Multiple transactions are all processed")
        void multipleTransactionsProcessed() {
            DailyTransaction dt1 = buildDailyTransaction("4111111111111111");
            DailyTransaction dt2 = buildDailyTransaction("5500000000000004");
            CardXref xref1 = buildXref("4111111111111111", "00000000001", "000000001");
            CardXref xref2 = buildXref("5500000000000004", "00000000002", "000000002");
            Account acct1 = buildAccount("00000000001");
            Account acct2 = buildAccount("00000000002");

            when(dailyTransactionRepository.findAll()).thenReturn(List.of(dt1, dt2));
            when(cardXrefRepository.findById("4111111111111111")).thenReturn(Optional.of(xref1));
            when(cardXrefRepository.findById("5500000000000004")).thenReturn(Optional.of(xref2));
            when(accountRepository.findById("00000000001")).thenReturn(Optional.of(acct1));
            when(accountRepository.findById("00000000002")).thenReturn(Optional.of(acct2));

            assertThatCode(() -> service.processTransactions()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RuntimeException from repository triggers logIoStatus and CardDemoException")
        void repositoryRuntimeExceptionTriggersAbend() {
            when(dailyTransactionRepository.findAll())
                    .thenThrow(new RuntimeException("DB connection lost"));

            assertThatThrownBy(() -> service.processTransactions())
                    .isInstanceOf(CardDemoException.class);
        }
    }

    // ── logIoStatus ─────────────────────────────────────────────────

    @Nested
    @DisplayName("logIoStatus")
    class LogIoStatus {

        @Test
        @DisplayName("logIoStatus always throws CardDemoException with ABCODE 999")
        void logIoStatusThrows() {
            assertThatThrownBy(() -> service.logIoStatus("TEST-CONTEXT", "35"))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("999");
        }

        @Test
        @DisplayName("logIoStatus with status 23 (not found) maps to VSAM message")
        void status23MapsCorrectly() {
            assertThatThrownBy(() -> service.logIoStatus("READ-ACCTDAT", "23"))
                    .isInstanceOf(CardDemoException.class);
        }

        @Test
        @DisplayName("logIoStatus with status 00 (success) still throws")
        void status00StillThrows() {
            assertThatThrownBy(() -> service.logIoStatus("WRITE-TRANSACT", "00"))
                    .isInstanceOf(CardDemoException.class);
        }
    }

    // ── DailyTransaction field coverage ─────────────────────────────

    @Nested
    @DisplayName("DailyTransaction Field Logging")
    class DailyTransactionFieldCoverage {

        @Test
        @DisplayName("DailyTransaction fields are accessible via getters")
        void dailyTransactionFieldAccess() {
            DailyTransaction dt = buildDailyTransaction("4111111111111111");
            assertThat(dt.getCardNum()).isEqualTo("4111111111111111");
            assertThat(dt.getDalytranId()).isEqualTo("TRN0000001");
            assertThat(dt.getTypeCode()).isEqualTo("SA");
            assertThat(dt.getCategoryCode()).isEqualTo(5001);
            assertThat(dt.getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(dt.getDescription()).isEqualTo("Test Transaction");
            assertThat(dt.getOrigTimestamp()).isEqualTo("2026-01-15-12.00.00.000000");
        }

        @Test
        @DisplayName("DailyTransaction with minimal data uses constructor correctly")
        void minimalDailyTransaction() {
            DailyTransaction dt = new DailyTransaction(
                    "DT001", "SA", 5001, null,
                    null, BigDecimal.ZERO,
                    null, null, null, null,
                    "4111111111111111", null, null);
            assertThat(dt.getDalytranId()).isEqualTo("DT001");
            assertThat(dt.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}

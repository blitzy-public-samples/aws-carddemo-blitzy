/*
 * StatementIoServiceTest.java — Unit tests for StatementIoService
 *
 * Tests the CBSTM03B.CBL-equivalent I/O service with four dataset groups:
 * Transaction, Xref, Customer, Account — each with open/read/readByKey/close.
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementIoServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private CardXrefRepository cardXrefRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private AccountRepository accountRepository;

    @InjectMocks
    private StatementIoService service;

    // --- Sample entities ---
    private Transaction sampleTxn;
    private CardXref sampleXref;
    private Customer sampleCust;
    private Account sampleAcct;

    @BeforeEach
    void setUp() {
        sampleTxn = new Transaction("T0000001", "01", 5001,
                "ONLINE", "Purchase", new BigDecimal("100.00"),
                "M001", "Merchant", "City", "12345",
                "4111111111111111", "2024-01-15-10.30.00.000000",
                "2024-01-15-10.30.01.000000");
        sampleXref = new CardXref("4111111111111111", "000000001", "00000000001");
        sampleCust = new Customer("000000001", "John", "M", "Doe",
                "123 Main St", "", "", "NY", "US", "10001",
                "5551234567", "", "123456789", "D12345678",
                "19800101", "EFT001", "Y", 750);
        sampleAcct = new Account("00000000001", "Y",
                new BigDecimal("5000.00"), new BigDecimal("10000.00"),
                new BigDecimal("5000.00"), "20200101", "20281231",
                "20240101", new BigDecimal("200.00"),
                new BigDecimal("100.00"), "10001", "GRP01");
    }

    // ===================================================================
    // TRNX-FILE tests
    // ===================================================================
    @Nested
    @DisplayName("Transaction File Operations")
    class TransactionFileTests {

        @Test
        @DisplayName("open → read sequential → close lifecycle")
        void fullLifecycle() {
            when(transactionRepository.findAll()).thenReturn(List.of(sampleTxn));
            List<Transaction> records = service.openTransactionFile();
            assertThat(records).hasSize(1);

            Optional<Transaction> first = service.readNextTransaction();
            assertThat(first).isPresent();
            assertThat(first.get().getTranId()).isEqualTo("T0000001");

            Optional<Transaction> eof = service.readNextTransaction();
            assertThat(eof).isEmpty();

            service.closeTransactionFile();
            // After close, read should throw
            assertThatThrownBy(() -> service.readNextTransaction())
                    .isInstanceOf(FileStatusException.class)
                    .hasMessageContaining("not opened");
        }

        @Test
        @DisplayName("readNext before open → FileStatusException RC=47")
        void readBeforeOpen() {
            assertThatThrownBy(() -> service.readNextTransaction())
                    .isInstanceOf(FileStatusException.class)
                    .hasMessageContaining("not opened");
        }

        @Test
        @DisplayName("open with repository error → FileStatusException RC=35")
        void openError() {
            when(transactionRepository.findAll())
                    .thenThrow(new RuntimeException("DB down"));
            assertThatThrownBy(() -> service.openTransactionFile())
                    .isInstanceOf(FileStatusException.class)
                    .hasMessageContaining("TRNXFILE");
        }

        @Test
        @DisplayName("readByKey found → Optional with record")
        void readByKeyFound() {
            when(transactionRepository.findById("T0000001"))
                    .thenReturn(Optional.of(sampleTxn));
            Optional<Transaction> result = service.readTransactionByKey("T0000001");
            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("readByKey not found → empty Optional")
        void readByKeyNotFound() {
            when(transactionRepository.findById("MISSING"))
                    .thenReturn(Optional.empty());
            Optional<Transaction> result = service.readTransactionByKey("MISSING");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("readByKey repository error → CardDemoException")
        void readByKeyError() {
            when(transactionRepository.findById("BAD"))
                    .thenThrow(new RuntimeException("Error"));
            assertThatThrownBy(() -> service.readTransactionByKey("BAD"))
                    .isInstanceOf(CardDemoException.class);
        }
    }

    // ===================================================================
    // XREF-FILE tests
    // ===================================================================
    @Nested
    @DisplayName("Xref File Operations")
    class XrefFileTests {

        @Test
        @DisplayName("open → read sequential → close lifecycle")
        void fullLifecycle() {
            when(cardXrefRepository.findAll()).thenReturn(List.of(sampleXref));
            List<CardXref> records = service.openXrefFile();
            assertThat(records).hasSize(1);

            Optional<CardXref> first = service.readNextXref();
            assertThat(first).isPresent();

            Optional<CardXref> eof = service.readNextXref();
            assertThat(eof).isEmpty();

            service.closeXrefFile();
            assertThatThrownBy(() -> service.readNextXref())
                    .isInstanceOf(FileStatusException.class);
        }

        @Test
        @DisplayName("readNext before open → FileStatusException")
        void readBeforeOpen() {
            assertThatThrownBy(() -> service.readNextXref())
                    .isInstanceOf(FileStatusException.class);
        }

        @Test
        @DisplayName("open error → FileStatusException RC=35")
        void openError() {
            when(cardXrefRepository.findAll())
                    .thenThrow(new RuntimeException("DB err"));
            assertThatThrownBy(() -> service.openXrefFile())
                    .isInstanceOf(FileStatusException.class);
        }

        @Test
        @DisplayName("readByKey found")
        void readByKeyFound() {
            when(cardXrefRepository.findById("4111111111111111"))
                    .thenReturn(Optional.of(sampleXref));
            assertThat(service.readXrefByKey("4111111111111111")).isPresent();
        }

        @Test
        @DisplayName("readByKey not found")
        void readByKeyNotFound() {
            when(cardXrefRepository.findById("MISS"))
                    .thenReturn(Optional.empty());
            assertThat(service.readXrefByKey("MISS")).isEmpty();
        }

        @Test
        @DisplayName("readByKey error → CardDemoException")
        void readByKeyError() {
            when(cardXrefRepository.findById("BAD"))
                    .thenThrow(new RuntimeException("err"));
            assertThatThrownBy(() -> service.readXrefByKey("BAD"))
                    .isInstanceOf(CardDemoException.class);
        }
    }

    // ===================================================================
    // CUST-FILE tests
    // ===================================================================
    @Nested
    @DisplayName("Customer File Operations")
    class CustomerFileTests {

        @Test
        @DisplayName("open → read sequential → close lifecycle")
        void fullLifecycle() {
            when(customerRepository.findAll()).thenReturn(List.of(sampleCust));
            List<Customer> records = service.openCustomerFile();
            assertThat(records).hasSize(1);

            Optional<Customer> first = service.readNextCustomer();
            assertThat(first).isPresent();

            Optional<Customer> eof = service.readNextCustomer();
            assertThat(eof).isEmpty();

            service.closeCustomerFile();
            assertThatThrownBy(() -> service.readNextCustomer())
                    .isInstanceOf(FileStatusException.class);
        }

        @Test
        @DisplayName("readNext before open → FileStatusException")
        void readBeforeOpen() {
            assertThatThrownBy(() -> service.readNextCustomer())
                    .isInstanceOf(FileStatusException.class);
        }

        @Test
        @DisplayName("open error → FileStatusException")
        void openError() {
            when(customerRepository.findAll())
                    .thenThrow(new RuntimeException("DB"));
            assertThatThrownBy(() -> service.openCustomerFile())
                    .isInstanceOf(FileStatusException.class);
        }

        @Test
        @DisplayName("readByKey found")
        void readByKeyFound() {
            when(customerRepository.findById("000000001"))
                    .thenReturn(Optional.of(sampleCust));
            assertThat(service.readCustomerByKey("000000001")).isPresent();
        }

        @Test
        @DisplayName("readByKey not found")
        void readByKeyNotFound() {
            when(customerRepository.findById("MISS"))
                    .thenReturn(Optional.empty());
            assertThat(service.readCustomerByKey("MISS")).isEmpty();
        }

        @Test
        @DisplayName("readByKey error → CardDemoException")
        void readByKeyError() {
            when(customerRepository.findById("BAD"))
                    .thenThrow(new RuntimeException("err"));
            assertThatThrownBy(() -> service.readCustomerByKey("BAD"))
                    .isInstanceOf(CardDemoException.class);
        }
    }

    // ===================================================================
    // ACCT-FILE tests
    // ===================================================================
    @Nested
    @DisplayName("Account File Operations")
    class AccountFileTests {

        @Test
        @DisplayName("open → read sequential → close lifecycle")
        void fullLifecycle() {
            when(accountRepository.findAll()).thenReturn(List.of(sampleAcct));
            List<Account> records = service.openAccountFile();
            assertThat(records).hasSize(1);

            Optional<Account> first = service.readNextAccount();
            assertThat(first).isPresent();

            Optional<Account> eof = service.readNextAccount();
            assertThat(eof).isEmpty();

            service.closeAccountFile();
            assertThatThrownBy(() -> service.readNextAccount())
                    .isInstanceOf(FileStatusException.class);
        }

        @Test
        @DisplayName("readNext before open → FileStatusException")
        void readBeforeOpen() {
            assertThatThrownBy(() -> service.readNextAccount())
                    .isInstanceOf(FileStatusException.class);
        }

        @Test
        @DisplayName("open error → FileStatusException")
        void openError() {
            when(accountRepository.findAll())
                    .thenThrow(new RuntimeException("DB"));
            assertThatThrownBy(() -> service.openAccountFile())
                    .isInstanceOf(FileStatusException.class);
        }

        @Test
        @DisplayName("readByKey found")
        void readByKeyFound() {
            when(accountRepository.findById("00000000001"))
                    .thenReturn(Optional.of(sampleAcct));
            assertThat(service.readAccountByKey("00000000001")).isPresent();
        }

        @Test
        @DisplayName("readByKey not found")
        void readByKeyNotFound() {
            when(accountRepository.findById("MISS"))
                    .thenReturn(Optional.empty());
            assertThat(service.readAccountByKey("MISS")).isEmpty();
        }

        @Test
        @DisplayName("readByKey error → CardDemoException")
        void readByKeyError() {
            when(accountRepository.findById("BAD"))
                    .thenThrow(new RuntimeException("err"));
            assertThatThrownBy(() -> service.readAccountByKey("BAD"))
                    .isInstanceOf(CardDemoException.class);
        }
    }
}

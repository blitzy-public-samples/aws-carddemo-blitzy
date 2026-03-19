package com.cardemo.batch.processor;

import com.cardemo.batch.writer.StatementFileWriter;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StatementProcessor} — covers the statement generation
 * logic translated from CBSTM03A.CBL.
 *
 * <p>Tests process() with various skip scenarios (null/blank fields,
 * missing customer/account), happy path with and without transactions,
 * and StatementData inner class usage.</p>
 */
@ExtendWith(MockitoExtension.class)
class StatementProcessorTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private StatementProcessor processor;

    // ── Helper factories ────────────────────────────────────────────

    /**
     * CardXref(xrefCardNum, custId, accountId)
     */
    private CardXref buildXref(String cardNum, String custId, String accountId) {
        return new CardXref(cardNum, custId, accountId);
    }

    /**
     * Customer full constructor.
     */
    private Customer buildCustomer(String custId) {
        return new Customer(
                custId, "John", "Michael", "Doe",
                "123 Main St", "Apt 4B", null,
                "IL", "US", "62701",
                "2175551234", null,
                "123456789", "DL987654321", "19800115",
                "00000000001", "Y", 750);
    }

    /**
     * Account full constructor.
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

    /**
     * Transaction full constructor.
     */
    private Transaction buildTransaction(String tranId, String cardNum, BigDecimal amount) {
        return new Transaction(
                tranId, "SA", 5001, "ONLINE",
                "Purchase", amount,
                "MERCH001", "Test Store", "Chicago", "60601",
                cardNum,
                "2026-01-15-12.00.00.000000",
                "2026-01-15-12.00.00.000000");
    }

    // ── Skip scenarios (process returns null) ───────────────────────

    @Nested
    @DisplayName("Skip Scenarios (null return)")
    class SkipScenarios {

        @Test
        @DisplayName("Null custId in CardXref returns null")
        void nullCustIdReturnsNull() throws Exception {
            CardXref xref = buildXref("4111111111111111", null, "00000000001");

            StatementFileWriter.StatementData result = processor.process(xref);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Blank custId in CardXref returns null")
        void blankCustIdReturnsNull() throws Exception {
            CardXref xref = buildXref("4111111111111111", "  ", "00000000001");

            StatementFileWriter.StatementData result = processor.process(xref);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Null accountId in CardXref returns null")
        void nullAccountIdReturnsNull() throws Exception {
            CardXref xref = buildXref("4111111111111111", "000000001", null);

            StatementFileWriter.StatementData result = processor.process(xref);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Blank accountId in CardXref returns null")
        void blankAccountIdReturnsNull() throws Exception {
            CardXref xref = buildXref("4111111111111111", "000000001", "  ");

            StatementFileWriter.StatementData result = processor.process(xref);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Customer not found returns null")
        void customerNotFoundReturnsNull() throws Exception {
            CardXref xref = buildXref("4111111111111111", "000000001", "00000000001");
            when(customerRepository.findById("000000001")).thenReturn(Optional.empty());

            StatementFileWriter.StatementData result = processor.process(xref);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Account not found returns null")
        void accountNotFoundReturnsNull() throws Exception {
            CardXref xref = buildXref("4111111111111111", "000000001", "00000000001");
            when(customerRepository.findById("000000001"))
                    .thenReturn(Optional.of(buildCustomer("000000001")));
            when(accountRepository.findById("00000000001"))
                    .thenReturn(Optional.empty());

            StatementFileWriter.StatementData result = processor.process(xref);

            assertThat(result).isNull();
        }
    }

    // ── Happy path scenarios ────────────────────────────────────────

    @Nested
    @DisplayName("Happy Path")
    class HappyPath {

        @Test
        @DisplayName("Valid CardXref with transactions returns StatementData")
        void validXrefWithTransactions() throws Exception {
            CardXref xref = buildXref("4111111111111111", "000000001", "00000000001");
            Customer customer = buildCustomer("000000001");
            Account account = buildAccount("00000000001");
            Transaction t1 = buildTransaction("TRN0000001", "4111111111111111", new BigDecimal("125.50"));
            Transaction t2 = buildTransaction("TRN0000002", "4111111111111111", new BigDecimal("75.00"));

            when(customerRepository.findById("000000001")).thenReturn(Optional.of(customer));
            when(accountRepository.findById("00000000001")).thenReturn(Optional.of(account));
            when(transactionRepository.findByCardNum(eq("4111111111111111"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(t1, t2)));

            StatementFileWriter.StatementData result = processor.process(xref);

            assertThat(result).isNotNull();
            assertThat(result.customer()).isEqualTo(customer);
            assertThat(result.account()).isEqualTo(account);
            assertThat(result.transactions()).hasSize(2);
        }

        @Test
        @DisplayName("Valid CardXref with no transactions returns StatementData with empty list")
        void validXrefNoTransactions() throws Exception {
            CardXref xref = buildXref("4111111111111111", "000000001", "00000000001");
            Customer customer = buildCustomer("000000001");
            Account account = buildAccount("00000000001");

            when(customerRepository.findById("000000001")).thenReturn(Optional.of(customer));
            when(accountRepository.findById("00000000001")).thenReturn(Optional.of(account));
            when(transactionRepository.findByCardNum(eq("4111111111111111"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            StatementFileWriter.StatementData result = processor.process(xref);

            assertThat(result).isNotNull();
            assertThat(result.transactions()).isEmpty();
        }
    }

    // ── Customer name formatting edge cases ─────────────────────────

    @Nested
    @DisplayName("Customer Name Edge Cases")
    class CustomerNameEdgeCases {

        @Test
        @DisplayName("Customer with double-space in name is handled")
        void doubleSpaceInName() throws Exception {
            Customer customer = new Customer(
                    "000000001", "Mary  Jane", null, "Watson",
                    "456 Oak", null, null,
                    "NY", "US", "10001",
                    "2125551234", null,
                    "987654321", null, "19850901",
                    null, "N", 700);

            CardXref xref = buildXref("4111111111111111", "000000001", "00000000001");
            Account account = buildAccount("00000000001");

            when(customerRepository.findById("000000001")).thenReturn(Optional.of(customer));
            when(accountRepository.findById("00000000001")).thenReturn(Optional.of(account));
            when(transactionRepository.findByCardNum(eq("4111111111111111"), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            StatementFileWriter.StatementData result = processor.process(xref);

            assertThat(result).isNotNull();
            assertThat(result.customer().getFirstName()).contains("Mary");
        }
    }

    // ── StatementData record structure ──────────────────────────────

    @Nested
    @DisplayName("StatementData Record")
    class StatementDataRecord {

        @Test
        @DisplayName("StatementData stores customer, account, and transactions")
        void statementDataFields() {
            Customer customer = buildCustomer("000000001");
            Account account = buildAccount("00000000001");
            Transaction t = buildTransaction("TRN001", "4111111111111111", new BigDecimal("50.00"));

            StatementFileWriter.StatementData data =
                    new StatementFileWriter.StatementData(customer, account, List.of(t));

            assertThat(data.customer()).isEqualTo(customer);
            assertThat(data.account()).isEqualTo(account);
            assertThat(data.transactions()).hasSize(1);
            assertThat(data.transactions().getFirst().getTranId()).isEqualTo("TRN001");
        }
    }
}

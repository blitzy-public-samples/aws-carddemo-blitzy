/*
 * TransactionProcessServiceTest.java — Unit tests for TransactionProcessService
 * Covers processTransactionReport, lookupXref, lookupTransactionType,
 * lookupTransactionCategory, writeHeaders
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Transaction;
import com.cardemo.entity.TransactionCategoryRef;
import com.cardemo.entity.TransactionTypeRef;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.TransactionCategoryRefRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRefRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TransactionProcessService — the CBTRN03C.cbl equivalent.
 */
@ExtendWith(MockitoExtension.class)
class TransactionProcessServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionTypeRefRepository transactionTypeRefRepository;

    @Mock
    private TransactionCategoryRefRepository transactionCategoryRefRepository;

    @InjectMocks
    private TransactionProcessService service;

    // ═══════════════════════════════════════════════════════════════════════
    // Null/Invalid Parameter Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null Parameter Handling")
    class NullParams {

        @Test
        @DisplayName("Null startDate throws CardDemoException")
        void nullStartDate() {
            Writer writer = new StringWriter();
            assertThatThrownBy(() -> service.processTransactionReport(null, "2026-03-19", writer))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("Date parameters are required");
        }

        @Test
        @DisplayName("Null endDate throws CardDemoException")
        void nullEndDate() {
            Writer writer = new StringWriter();
            assertThatThrownBy(() -> service.processTransactionReport("2026-01-01", null, writer))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("Date parameters are required");
        }

        @Test
        @DisplayName("Null writer throws CardDemoException")
        void nullWriter() {
            assertThatThrownBy(() -> service.processTransactionReport("2026-01-01", "2026-03-19", null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("Report writer must not be null");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Empty Data Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Empty Data")
    class EmptyData {

        @Test
        @DisplayName("No transactions produces empty report without error")
        void noTransactions() {
            when(transactionRepository.findAll()).thenReturn(Collections.emptyList());
            Writer writer = new StringWriter();
            service.processTransactionReport("2026-01-01", "2026-03-19", writer);
            assertThat(writer.toString()).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Single Transaction (in date range)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Single Transaction Processing")
    class SingleTransaction {

        @Test
        @DisplayName("Transaction within date range is processed")
        void singleTransactionInRange() {
            // Create a transaction with proc timestamp in range
            Transaction tran = new Transaction(
                    "TRAN001", "01", 1, "POS", "TEST PURCHASE",
                    new BigDecimal("50.00"), "MERCH001", "TEST MERCHANT",
                    "CITY", "12345", "4111111111111111",
                    "2026-01-15-10.30.00.000000", "2026-01-15-10.30.00.000000");

            when(transactionRepository.findAll()).thenReturn(List.of(tran));

            // Mock XREF lookup: findByXrefCardNum returns a valid XREF
            CardXref xref = new CardXref("4111111111111111", "CUST001", "ACCT00001");
            when(cardXrefRepository.findByXrefCardNum("4111111111111111"))
                    .thenReturn(Optional.of(xref));

            // Mock type lookup
            when(transactionTypeRefRepository.findById("01"))
                    .thenReturn(Optional.of(new TransactionTypeRef("01", "Purchase")));

            // Mock category lookup
            TransactionCategoryRef.TransactionCategoryRefId catKey =
                    new TransactionCategoryRef.TransactionCategoryRefId("01", 1);
            when(transactionCategoryRefRepository.findById(catKey))
                    .thenReturn(Optional.of(new TransactionCategoryRef("01", 1, "Retail")));

            Writer writer = new StringWriter();
            service.processTransactionReport("2026-01-01", "2026-12-31", writer);

            String output = writer.toString();
            assertThat(output).isNotEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Date Filtering Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Date Filtering")
    class DateFiltering {

        @Test
        @DisplayName("Transaction before start date is skipped")
        void transactionBeforeStartDateSkipped() {
            Transaction tran = new Transaction(
                    "TRAN001", "01", 1, "POS", "OLD PURCHASE",
                    new BigDecimal("25.00"), "MERCH001", "OLD MERCHANT",
                    "CITY", "12345", "4111111111111111",
                    "2025-01-01-10.00.00.000000", "2025-01-01-10.00.00.000000");

            when(transactionRepository.findAll()).thenReturn(List.of(tran));

            Writer writer = new StringWriter();
            // Date range: 2026-01-01 to 2026-12-31; transaction is 2025
            service.processTransactionReport("2026-01-01", "2026-12-31", writer);
            // Transaction out of range, so no detail output
            assertThat(writer.toString()).isEmpty();
        }

        @Test
        @DisplayName("Transaction after end date is skipped")
        void transactionAfterEndDateSkipped() {
            Transaction tran = new Transaction(
                    "TRAN001", "01", 1, "POS", "FUTURE PURCHASE",
                    new BigDecimal("75.00"), "MERCH001", "FUTURE MERCHANT",
                    "CITY", "12345", "4111111111111111",
                    "2027-06-15-10.00.00.000000", "2027-06-15-10.00.00.000000");

            when(transactionRepository.findAll()).thenReturn(List.of(tran));

            Writer writer = new StringWriter();
            service.processTransactionReport("2026-01-01", "2026-12-31", writer);
            assertThat(writer.toString()).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lookup Failure Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Lookup Failures")
    class LookupFailures {

        @Test
        @DisplayName("lookupXref throws CardDemoException when XREF not found")
        void missingXrefThrows() {
            when(cardXrefRepository.findByXrefCardNum("9999888877776666"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.lookupXref("9999888877776666"))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("INVALID CARD NUMBER");
        }

        @Test
        @DisplayName("lookupTransactionType throws when type not found")
        void missingTypeRefThrows() {
            when(transactionTypeRefRepository.findById("ZZ"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.lookupTransactionType("ZZ"))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("INVALID TRANSACTION TYPE");
        }

        @Test
        @DisplayName("lookupTransactionCategory throws when category not found")
        void missingCatRefThrows() {
            TransactionCategoryRef.TransactionCategoryRefId catKey =
                    new TransactionCategoryRef.TransactionCategoryRefId("01", 9999);
            when(transactionCategoryRefRepository.findById(catKey))
                    .thenReturn(Optional.empty());
            when(transactionCategoryRefRepository.findByTypeCode("01"))
                    .thenReturn(Collections.emptyList());

            assertThatThrownBy(() -> service.lookupTransactionCategory("01", "9999"))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("INVALID TRAN CATG KEY");
        }

        @Test
        @DisplayName("lookupTransactionCategory throws on non-numeric catCode")
        void nonNumericCatCodeThrows() {
            assertThatThrownBy(() -> service.lookupTransactionCategory("01", "ABC"))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("INVALID TRAN CATG KEY");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WriteHeaders Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WriteHeaders")
    class WriteHeadersTests {

        @Test
        @DisplayName("writeHeaders writes header lines to writer")
        void writeHeadersProducesOutput() {
            Writer writer = new StringWriter();
            service.writeHeaders(writer);
            String output = writer.toString();
            assertThat(output).isNotBlank();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Card Grouping Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Card Grouping")
    class CardGrouping {

        @Test
        @DisplayName("Multiple card numbers trigger account subtotals")
        void multipleCardGroups() {
            Transaction tran1 = new Transaction(
                    "TRAN001", "01", 1, "POS", "PURCHASE 1",
                    new BigDecimal("100.00"), "MERCH001", "MERCHANT1",
                    "CITY1", "11111", "4111111111111111",
                    "2026-02-01-10.00.00.000000", "2026-02-01-10.00.00.000000");
            Transaction tran2 = new Transaction(
                    "TRAN002", "01", 1, "POS", "PURCHASE 2",
                    new BigDecimal("200.00"), "MERCH002", "MERCHANT2",
                    "CITY2", "22222", "5222222222222222",
                    "2026-02-15-10.00.00.000000", "2026-02-15-10.00.00.000000");

            when(transactionRepository.findAll()).thenReturn(List.of(tran1, tran2));

            CardXref xref1 = new CardXref("4111111111111111", "CUST001", "ACCT00001");
            CardXref xref2 = new CardXref("5222222222222222", "CUST002", "ACCT00002");
            when(cardXrefRepository.findByXrefCardNum("4111111111111111"))
                    .thenReturn(Optional.of(xref1));
            when(cardXrefRepository.findByXrefCardNum("5222222222222222"))
                    .thenReturn(Optional.of(xref2));

            when(transactionTypeRefRepository.findById("01"))
                    .thenReturn(Optional.of(new TransactionTypeRef("01", "Purchase")));

            TransactionCategoryRef.TransactionCategoryRefId catKey =
                    new TransactionCategoryRef.TransactionCategoryRefId("01", 1);
            when(transactionCategoryRefRepository.findById(catKey))
                    .thenReturn(Optional.of(new TransactionCategoryRef("01", 1, "Retail")));

            Writer writer = new StringWriter();
            service.processTransactionReport("2026-01-01", "2026-12-31", writer);

            String output = writer.toString();
            assertThat(output).isNotEmpty();
        }
    }
}

package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.StatementHtmlBuilder;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link StatementService}, covering the CP3 remediation findings:
 * <ul>
 *   <li><strong>F9</strong> &mdash; the full card number (PAN) is never embedded in a log line
 *       or {@link IllegalStateException} message; only the masked last-four form appears.</li>
 *   <li><strong>F10</strong> &mdash; {@link StatementService#buildAllStatements()} prefetches
 *       master/transaction data in bulk ({@code findAll()}) rather than issuing per-row keyed
 *       reads, eliminating the previous N+1 pattern.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class StatementServiceTest {

    private static final String CARD_1 = "4111111111111111";
    private static final String CARD_2 = "5500000000000004";

    @Mock private CardXrefRepository cardXrefRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private StatementHtmlBuilder statementHtmlBuilder;

    private StatementService service;

    @BeforeEach
    void setUp() {
        // Explicit constructor (RequiredArgsConstructor field order) for clarity.
        service = new StatementService(
                cardXrefRepository,
                customerRepository,
                accountRepository,
                transactionRepository,
                statementHtmlBuilder);
    }

    private static CardXref xref(Long custId, Long accountId, String cardNum) {
        return CardXref.builder()
                .xrefCardNum(cardNum)
                .custId(custId)
                .accountId(accountId)
                .build();
    }

    private static Customer customer(Long custId) {
        return Customer.builder().custId(custId).firstName("Jane").lastName("Doe").build();
    }

    private static Account account(Long acctId) {
        return Account.builder().acctId(acctId).currBal(new BigDecimal("100.00")).build();
    }

    private static Transaction tx(String cardNum, String tranId, String amount) {
        return Transaction.builder()
                .tranId(tranId)
                .cardNum(cardNum)
                .description("desc")
                .amount(new BigDecimal(amount))
                .build();
    }

    @Nested
    @DisplayName("F9 — card number is masked in data-integrity errors")
    class CardMasking {

        @Test
        @DisplayName("missing customer → IllegalStateException carries masked card, not the full PAN")
        void customerMissingMasksCard() {
            CardXref xref = xref(7L, 9L, CARD_1);
            when(customerRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.buildStatementForXref(xref))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("custId=7")
                    .hasMessageContaining("************1111")
                    .hasMessageNotContaining(CARD_1);
        }

        @Test
        @DisplayName("missing account → IllegalStateException carries masked card, not the full PAN")
        void accountMissingMasksCard() {
            CardXref xref = xref(7L, 9L, CARD_1);
            when(customerRepository.findById(7L)).thenReturn(Optional.of(customer(7L)));
            when(accountRepository.findById(9L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.buildStatementForXref(xref))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("acctId=9")
                    .hasMessageContaining("************1111")
                    .hasMessageNotContaining(CARD_1);
        }
    }

    @Nested
    @DisplayName("F10 — buildAllStatements prefetches instead of N+1")
    class Prefetch {

        @Test
        @DisplayName("uses bulk findAll() once each and never issues per-row keyed reads")
        void prefetchesWithoutPerRowLookups() {
            CardXref x1 = xref(1L, 11L, CARD_1);
            CardXref x2 = xref(2L, 12L, CARD_2);
            when(cardXrefRepository.findAll()).thenReturn(List.of(x1, x2));
            when(customerRepository.findAll()).thenReturn(List.of(customer(1L), customer(2L)));
            when(accountRepository.findAll()).thenReturn(List.of(account(11L), account(12L)));
            when(transactionRepository.findAll()).thenReturn(List.of(
                    tx(CARD_1, "T1", "10.00"),
                    tx(CARD_2, "T2", "5.50")));
            lenient().when(statementHtmlBuilder.renderFullStatement(any(), any(), any()))
                    .thenReturn("<html/>");

            List<StatementService.StatementResult> results = service.buildAllStatements();

            assertThat(results).hasSize(2);
            assertThat(results.get(0).totalAmount()).isEqualByComparingTo("10.00");
            assertThat(results.get(1).totalAmount()).isEqualByComparingTo("5.50");

            // F10: exactly one bulk read per master/transaction set...
            verify(customerRepository, times(1)).findAll();
            verify(accountRepository, times(1)).findAll();
            verify(transactionRepository, times(1)).findAll();
            // ...and ZERO per-row keyed reads (the eliminated N+1 path).
            verify(customerRepository, never()).findById(any());
            verify(accountRepository, never()).findById(any());
            verify(transactionRepository, never()).findByCardNum(any());
        }

        @Test
        @DisplayName("missing prefetched customer → masked IllegalStateException")
        void throwsMaskedWhenCustomerMissing() {
            CardXref orphan = xref(99L, 11L, CARD_1);
            when(cardXrefRepository.findAll()).thenReturn(List.of(orphan));
            when(customerRepository.findAll()).thenReturn(List.of()); // 99 not present
            when(accountRepository.findAll()).thenReturn(List.of(account(11L)));
            when(transactionRepository.findAll()).thenReturn(List.of());

            assertThatThrownBy(() -> service.buildAllStatements())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("************1111")
                    .hasMessageNotContaining(CARD_1);
        }
    }

    @Nested
    @DisplayName("single-xref happy path")
    class SingleHappyPath {

        @Test
        @DisplayName("computes the scale-2 running total and returns the rendered result")
        void computesTotal() {
            CardXref xref = xref(1L, 11L, CARD_1);
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
            when(accountRepository.findById(11L)).thenReturn(Optional.of(account(11L)));
            when(transactionRepository.findByCardNum(CARD_1)).thenReturn(List.of(
                    tx(CARD_1, "T1", "10.00"),
                    tx(CARD_1, "T2", "5.50")));
            when(statementHtmlBuilder.renderFullStatement(any(), any(), any()))
                    .thenReturn("<html/>");

            StatementService.StatementResult result = service.buildStatementForXref(xref);

            assertThat(result.acctId()).isEqualTo(11L);
            assertThat(result.totalAmount()).isEqualByComparingTo("15.50");
            assertThat(result.transactions()).hasSize(2);
            assertThat(result.htmlOutput()).isEqualTo("<html/>");
        }
    }
}

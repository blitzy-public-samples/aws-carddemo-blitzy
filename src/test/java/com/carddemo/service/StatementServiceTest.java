package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@link StatementService} &mdash; the Java port of the data-gathering
 * main loop of the COBOL batch statement generator {@code app/cbl/CBSTM03A.CBL}.
 *
 * <h2>COBOL source mapping ({@code CBSTM03A.CBL} {@code 1000-MAINLINE}, L316-L342)</h2>
 * <ul>
 *   <li>{@code 1000-XREFFILE-GET-NEXT} &rarr; {@link CardXrefRepository#findAll()} drives the loop
 *       in {@link StatementService#buildAllStatements()}; one iteration is exercised directly via
 *       {@link StatementService#buildStatementForXref(CardXref)}.</li>
 *   <li>{@code 2000-CUSTFILE-GET} &rarr; {@link CustomerRepository#findById(Object)}.</li>
 *   <li>{@code 3000-ACCTFILE-GET} &rarr; {@link AccountRepository#findById(Object)}.</li>
 *   <li>{@code 4000-TRNXFILE-GET} (+ {@code WS-TOTAL-AMT}) &rarr;
 *       {@link TransactionRepository#findByCardNum(String)} and the scale-2 running total.</li>
 *   <li>{@code 5100-WRITE-HTML-HEADER} (L506-L555) &rarr; delegated to
 *       {@link StatementHtmlBuilder#renderFullStatement} (HTML byte-for-byte parity is asserted by
 *       {@code StatementGenerationParityTest}, not here &mdash; PR-09).</li>
 * </ul>
 *
 * <h2>Regression findings covered</h2>
 * <ul>
 *   <li><strong>F9</strong> &mdash; the full card number (PAN) is never embedded in a
 *       data-integrity {@link IllegalStateException} message; only the masked last-four form
 *       ({@code ************NNNN}) appears.</li>
 *   <li><strong>F10</strong> &mdash; {@link StatementService#buildAllStatements()} prefetches
 *       master and transaction data with bulk {@code findAll()} queries instead of issuing per-row
 *       keyed reads, eliminating the prior N+1 access pattern.</li>
 * </ul>
 *
 * <p>The service throws {@link IllegalStateException} (a data-integrity violation) &mdash; not a
 * validation exception &mdash; when a cross-reference points at a missing customer or account, so
 * the tests assert against that exact type. Monetary fixtures use {@link BigDecimal} exclusively
 * (PR-16); collaborators are supplied by constructor injection (PR-29).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementService - replaces COBOL CBSTM03A statement orchestration")
class StatementServiceTest {

    /** Canonical Visa test PAN; {@code CardNumberMasker.mask} renders it as {@code ************1111}. */
    private static final String CARD_1 = "4111111111111111";
    /** Canonical Mastercard test PAN; masks to {@code ************0004} (proves masking is general). */
    private static final String CARD_2 = "5500000000000004";
    /** {@code CARD_1} after F9 masking: 12 asterisks followed by the last four digits. */
    private static final String CARD_1_MASKED = "************1111";
    /** {@code CARD_2} after F9 masking: 12 asterisks followed by the last four digits. */
    private static final String CARD_2_MASKED = "************0004";

    private static final Long DEF_CUST_ID = 100000001L;
    private static final Long DEF_ACCT_ID = 11111111111L;
    private static final Integer DEF_FICO = 750;

    @Mock private CardXrefRepository cardXrefRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private StatementHtmlBuilder statementHtmlBuilder;

    @InjectMocks private StatementService statementService;

    /** A valid customer matching {@link #defaultXref} ({@code 2000-CUSTFILE-GET} target). */
    private Customer defaultCustomer;
    /** A valid account matching {@link #defaultXref} ({@code 3000-ACCTFILE-GET} target). */
    private Account defaultAccount;
    /** A valid cross-reference wiring {@link #defaultCustomer} + {@link #defaultAccount} + {@link #CARD_1}. */
    private CardXref defaultXref;

    @BeforeEach
    void setUp() {
        defaultCustomer = buildCustomer(DEF_CUST_ID, "John", "Doe");
        defaultAccount = buildAccount(DEF_ACCT_ID, new BigDecimal("1500.00"));
        defaultXref = buildCardXref(CARD_1, DEF_ACCT_ID, DEF_CUST_ID);
    }

    // ------------------------------------------------------------------------------------------
    // Group 1 — Statement assembly (CBSTM03A 1000-MAINLINE single-iteration orchestration).
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Statement assembly")
    class StatementAssembly {

        @Test
        @DisplayName("Should assemble statement from customer + account + transactions "
                + "(CBSTM03A L316-L342 1000-MAINLINE)")
        void shouldAssembleStatementWithCustomerAccountAndTransactions() {
            // given — one iteration of 1000-MAINLINE: xref -> customer -> account -> transactions.
            Transaction t1 = buildTransaction("0000000000000001", CARD_1, new BigDecimal("100.00"));
            Transaction t2 = buildTransaction("0000000000000002", CARD_1, new BigDecimal("50.00"));
            List<Transaction> txns = List.of(t1, t2);

            when(customerRepository.findById(DEF_CUST_ID)).thenReturn(Optional.of(defaultCustomer));
            when(accountRepository.findById(DEF_ACCT_ID)).thenReturn(Optional.of(defaultAccount));
            when(transactionRepository.findByCardNum(CARD_1)).thenReturn(txns);
            when(statementHtmlBuilder.renderFullStatement(any(), any(), any()))
                    .thenReturn("<html>Statement</html>");

            // when
            StatementService.StatementResult result = statementService.buildStatementForXref(defaultXref);

            // then — every structured field of the assembled StatementResult is populated.
            assertThat(result).isNotNull();
            assertThat(result.customerName()).isEqualTo("John Doe");
            assertThat(result.acctId()).isEqualTo(DEF_ACCT_ID);
            assertThat(result.currentBalance()).isEqualByComparingTo("1500.00");
            assertThat(result.ficoScore()).isEqualTo(DEF_FICO);
            assertThat(result.transactions()).hasSize(2);
            assertThat(result.totalAmount()).isEqualByComparingTo("150.00");
            assertThat(result.htmlOutput()).isEqualTo("<html>Statement</html>");
            assertThat(result.plainTextOutput()).isNotBlank();

            // and — each COBOL read paragraph maps to exactly one repository call.
            verify(customerRepository).findById(DEF_CUST_ID);   // 2000-CUSTFILE-GET
            verify(accountRepository).findById(DEF_ACCT_ID);     // 3000-ACCTFILE-GET
            verify(transactionRepository).findByCardNum(CARD_1); // 4000-TRNXFILE-GET
            verify(statementHtmlBuilder).renderFullStatement(defaultCustomer, defaultAccount, txns);
        }

        @Test
        @DisplayName("Should handle an account with no transactions (still generates a statement, "
                + "WS-TOTAL-AMT = 0.00)")
        void shouldHandleAccountWithNoTransactions() {
            // given — 4000-TRNXFILE-GET returns no rows; the statement is still produced.
            when(customerRepository.findById(DEF_CUST_ID)).thenReturn(Optional.of(defaultCustomer));
            when(accountRepository.findById(DEF_ACCT_ID)).thenReturn(Optional.of(defaultAccount));
            when(transactionRepository.findByCardNum(CARD_1)).thenReturn(Collections.emptyList());
            when(statementHtmlBuilder.renderFullStatement(any(), any(), any()))
                    .thenReturn("<html>Empty</html>");

            // when
            StatementService.StatementResult result = statementService.buildStatementForXref(defaultXref);

            // then
            assertThat(result).isNotNull();
            assertThat(result.transactions()).isEmpty();
            assertThat(result.totalAmount()).isEqualByComparingTo("0.00");
            assertThat(result.htmlOutput()).isEqualTo("<html>Empty</html>");
            // the empty transaction list is still forwarded to the HTML builder.
            verify(statementHtmlBuilder)
                    .renderFullStatement(defaultCustomer, defaultAccount, Collections.emptyList());
        }

        @Test
        @DisplayName("Should preserve repository transaction order (service does not re-sort the "
                + "4000-TRNXFILE-GET sequence)")
        void shouldPreserveRepositoryTransactionOrder() {
            // given — transactions deliberately returned out of natural id order.
            Transaction t3 = buildTransaction("0000000000000003", CARD_1, new BigDecimal("30.00"));
            Transaction t1 = buildTransaction("0000000000000001", CARD_1, new BigDecimal("10.00"));
            Transaction t2 = buildTransaction("0000000000000002", CARD_1, new BigDecimal("20.00"));

            when(customerRepository.findById(DEF_CUST_ID)).thenReturn(Optional.of(defaultCustomer));
            when(accountRepository.findById(DEF_ACCT_ID)).thenReturn(Optional.of(defaultAccount));
            when(transactionRepository.findByCardNum(CARD_1)).thenReturn(List.of(t3, t1, t2));
            when(statementHtmlBuilder.renderFullStatement(any(), any(), any())).thenReturn("<html/>");

            // when
            StatementService.StatementResult result = statementService.buildStatementForXref(defaultXref);

            // then — the contract holds: the list is passed through verbatim, not reordered.
            assertThat(result.transactions())
                    .extracting(Transaction::getTranId)
                    .containsExactly("0000000000000003", "0000000000000001", "0000000000000002");
        }

        @Test
        @DisplayName("Should normalize the WS-TOTAL-AMT running total to scale 2 (PR-16)")
        void shouldComputeScaleTwoRunningTotal() {
            // given — amounts with mixed scales (1 and 0) sum to 15.1 before normalization.
            Transaction a = buildTransaction("0000000000000001", CARD_1, new BigDecimal("10.1"));
            Transaction b = buildTransaction("0000000000000002", CARD_1, new BigDecimal("5"));

            when(customerRepository.findById(DEF_CUST_ID)).thenReturn(Optional.of(defaultCustomer));
            when(accountRepository.findById(DEF_ACCT_ID)).thenReturn(Optional.of(defaultAccount));
            when(transactionRepository.findByCardNum(CARD_1)).thenReturn(List.of(a, b));
            when(statementHtmlBuilder.renderFullStatement(any(), any(), any())).thenReturn("<html/>");

            // when
            StatementService.StatementResult result = statementService.buildStatementForXref(defaultXref);

            // then — value is 15.10 and the BigDecimal scale is exactly 2 (no float/double; PR-16).
            assertThat(result.totalAmount()).isEqualByComparingTo("15.10");
            assertThat(result.totalAmount().scale()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Group 2 — HTML generation delegation (PR-09: this service emits no markup of its own).
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Statement HTML generation")
    class HtmlGeneration {

        @Test
        @DisplayName("Should invoke StatementHtmlBuilder.renderFullStatement with the assembled "
                + "customer, account, and transactions")
        void shouldInvokeStatementHtmlBuilderWithAssembledData() {
            // given — wildcard any()/anyLong()/anyString() matchers prove the wiring regardless of keys.
            Customer cust = buildCustomer(DEF_CUST_ID, "Ada", "Lovelace");
            Account acct = buildAccount(DEF_ACCT_ID, new BigDecimal("200.00"));
            List<Transaction> txns =
                    List.of(buildTransaction("0000000000000009", CARD_1, new BigDecimal("9.99")));

            when(customerRepository.findById(anyLong())).thenReturn(Optional.of(cust));
            when(accountRepository.findById(anyLong())).thenReturn(Optional.of(acct));
            when(transactionRepository.findByCardNum(anyString())).thenReturn(txns);
            when(statementHtmlBuilder.renderFullStatement(any(), any(), any())).thenReturn("<html/>");

            // when
            statementService.buildStatementForXref(defaultXref);

            // then — exactly the resolved entities/transactions are handed to the HTML builder.
            verify(statementHtmlBuilder).renderFullStatement(cust, acct, txns);
        }

        @Test
        @DisplayName("Should return the HTML produced by the builder verbatim (PR-09 - no markup "
                + "is synthesized by the service)")
        void shouldReturnHtmlContentFromBuilder() {
            // given
            String expectedHtml = "<!DOCTYPE html><html><body>Bank Statement</body></html>";
            when(customerRepository.findById(DEF_CUST_ID)).thenReturn(Optional.of(defaultCustomer));
            when(accountRepository.findById(DEF_ACCT_ID)).thenReturn(Optional.of(defaultAccount));
            when(transactionRepository.findByCardNum(CARD_1)).thenReturn(
                    List.of(buildTransaction("0000000000000001", CARD_1, new BigDecimal("1.00"))));
            when(statementHtmlBuilder.renderFullStatement(any(), any(), any()))
                    .thenReturn(expectedHtml);

            // when
            StatementService.StatementResult result = statementService.buildStatementForXref(defaultXref);

            // then — the service propagates the builder output unchanged.
            assertThat(result.htmlOutput()).isEqualTo(expectedHtml);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Group 3 — buildAllStatements (full CBSTM03A 1000-MAINLINE loop + F10 prefetch).
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Build all statements (full 1000-MAINLINE loop)")
    class BuildAllStatements {

        @Test
        @DisplayName("Should build one statement per cross-reference using bulk findAll() and never "
                + "issue per-row keyed reads (F10 N+1 elimination)")
        void shouldBuildOneStatementPerXrefViaPrefetch() {
            // given — two cross-references resolved entirely from prefetched bulk reads.
            CardXref x1 = buildCardXref(CARD_1, 11L, 1L);
            CardXref x2 = buildCardXref(CARD_2, 12L, 2L);
            when(cardXrefRepository.findAll()).thenReturn(List.of(x1, x2));
            when(customerRepository.findAll())
                    .thenReturn(List.of(buildCustomer(1L, "Alice", "Adams"),
                            buildCustomer(2L, "Bob", "Brown")));
            when(accountRepository.findAll())
                    .thenReturn(List.of(buildAccount(11L, new BigDecimal("100.00")),
                            buildAccount(12L, new BigDecimal("200.00"))));
            when(transactionRepository.findAll()).thenReturn(List.of(
                    buildTransaction("0000000000000001", CARD_1, new BigDecimal("10.00")),
                    buildTransaction("0000000000000002", CARD_2, new BigDecimal("5.50"))));
            when(statementHtmlBuilder.renderFullStatement(any(), any(), any())).thenReturn("<html/>");

            // when
            List<StatementService.StatementResult> results = statementService.buildAllStatements();

            // then — one StatementResult per xref, totals grouped by card number.
            assertThat(results).hasSize(2);
            assertThat(results.get(0).acctId()).isEqualTo(11L);
            assertThat(results.get(0).totalAmount()).isEqualByComparingTo("10.00");
            assertThat(results.get(1).acctId()).isEqualTo(12L);
            assertThat(results.get(1).totalAmount()).isEqualByComparingTo("5.50");

            // F10: exactly one bulk read of each master/transaction set...
            verify(cardXrefRepository, times(1)).findAll();
            verify(customerRepository, times(1)).findAll();
            verify(accountRepository, times(1)).findAll();
            verify(transactionRepository, times(1)).findAll();
            // ...and ZERO per-row keyed reads (the eliminated N+1 path).
            verify(customerRepository, never()).findById(any());
            verify(accountRepository, never()).findById(any());
            verify(transactionRepository, never()).findByCardNum(any());
        }

        @Test
        @DisplayName("Should return an empty list when there are no cross-references (prefetch still "
                + "runs once; builder never invoked)")
        void shouldReturnEmptyListWhenNoCrossReferences() {
            // given — no XREF rows; the loop body never executes.
            when(cardXrefRepository.findAll()).thenReturn(Collections.emptyList());

            // when
            List<StatementService.StatementResult> results = statementService.buildAllStatements();

            // then
            assertThat(results).isEmpty();
            verify(cardXrefRepository, times(1)).findAll();
            verify(customerRepository, times(1)).findAll();
            verify(accountRepository, times(1)).findAll();
            verify(transactionRepository, times(1)).findAll();
            verify(statementHtmlBuilder, never()).renderFullStatement(any(), any(), any());
        }

        @Test
        @DisplayName("Should throw IllegalStateException (masked card) when a prefetched account is "
                + "missing for an existing cross-reference")
        void shouldThrowWhenPrefetchedAccountMissing() {
            // given — customer present in the prefetch map, account absent (dangling reference).
            CardXref orphan = buildCardXref(CARD_1, 11L, 1L);
            when(cardXrefRepository.findAll()).thenReturn(List.of(orphan));
            when(customerRepository.findAll()).thenReturn(List.of(buildCustomer(1L, "Alice", "Adams")));
            when(accountRepository.findAll()).thenReturn(Collections.emptyList());

            // when / then
            assertThatThrownBy(() -> statementService.buildAllStatements())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Account not found")
                    .hasMessageContaining("acctId=11")
                    .hasMessageContaining(CARD_1_MASKED)
                    .hasMessageNotContaining(CARD_1);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Group 4 — Error handling (data-integrity aborts + F9 PAN masking).
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should throw IllegalStateException with a masked card when the customer is "
                + "not found (2000-CUSTFILE-GET miss; F9)")
        void shouldThrowAndMaskCardWhenCustomerNotFound() {
            // given
            CardXref xref = buildCardXref(CARD_1, 9L, 7L);
            when(customerRepository.findById(7L)).thenReturn(Optional.empty());

            // when / then — the message identifies the missing key but masks the PAN.
            assertThatThrownBy(() -> statementService.buildStatementForXref(xref))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Customer not found")
                    .hasMessageContaining("custId=7")
                    .hasMessageContaining(CARD_1_MASKED)
                    .hasMessageNotContaining(CARD_1);
        }

        @Test
        @DisplayName("Should throw IllegalStateException with a masked card when the account is "
                + "not found (3000-ACCTFILE-GET miss; F9)")
        void shouldThrowAndMaskCardWhenAccountNotFound() {
            // given — customer resolves, account does not.
            CardXref xref = buildCardXref(CARD_1, 9L, 7L);
            when(customerRepository.findById(7L)).thenReturn(Optional.of(buildCustomer(7L, "John", "Doe")));
            when(accountRepository.findById(9L)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> statementService.buildStatementForXref(xref))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Account not found")
                    .hasMessageContaining("acctId=9")
                    .hasMessageContaining(CARD_1_MASKED)
                    .hasMessageNotContaining(CARD_1);
        }

        @Test
        @DisplayName("Should never leak the full PAN for any card in a data-integrity error "
                + "(F9 masking is general, not hard-coded)")
        void shouldNeverLeakFullPanForAnyCard() {
            // given — a different PAN exercises the masker beyond the canonical Visa fixture.
            CardXref xref = buildCardXref(CARD_2, 9L, 7L);
            when(customerRepository.findById(7L)).thenReturn(Optional.empty());

            // when / then — only the masked form (last four) is present; the full PAN is absent.
            assertThatThrownBy(() -> statementService.buildStatementForXref(xref))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CARD_2_MASKED)
                    .hasMessageNotContaining(CARD_2);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Test fixture builders — populated with the REAL entity setters (Lombok @Setter), using the
    // actual field names (Account.activeStatus, Customer.firstName/lastName/ficoScore,
    // CardXref.custId, Transaction.tranId/cardNum/amount/description).
    // ------------------------------------------------------------------------------------------

    /** Builds an {@link Account} fixture ({@code ACCTFILE-GET} target). */
    private Account buildAccount(Long acctId, BigDecimal currBal) {
        Account a = new Account();
        a.setAcctId(acctId);
        a.setCurrBal(currBal);
        a.setCreditLimit(new BigDecimal("5000.00"));
        a.setActiveStatus("Y");
        return a;
    }

    /** Builds a {@link Customer} fixture ({@code CUSTFILE-GET} target) with a known FICO score. */
    private Customer buildCustomer(Long custId, String firstName, String lastName) {
        Customer c = new Customer();
        c.setCustId(custId);
        c.setFirstName(firstName);
        c.setLastName(lastName);
        c.setFicoScore(DEF_FICO);
        return c;
    }

    /** Builds a {@link CardXref} fixture (the {@code 1000-MAINLINE} loop driver record). */
    private CardXref buildCardXref(String cardNum, Long acctId, Long custId) {
        CardXref x = new CardXref();
        x.setXrefCardNum(cardNum);
        x.setAccountId(acctId);
        x.setCustId(custId);
        return x;
    }

    /** Builds a {@link Transaction} fixture ({@code TRNXFILE-GET} row). */
    private Transaction buildTransaction(String tranId, String cardNum, BigDecimal amount) {
        Transaction t = new Transaction();
        t.setTranId(tranId);
        t.setCardNum(cardNum);
        t.setAmount(amount);
        t.setDescription("TEST TRANSACTION");
        return t;
    }
}

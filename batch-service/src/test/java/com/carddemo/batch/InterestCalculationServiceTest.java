/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
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
package com.carddemo.batch;

import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DiscGroupRepository;
import com.carddemo.batch.repository.TranCatBalRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.InterestCalculationService;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.DiscGroup;
import com.carddemo.common.domain.DiscGroupId;
import com.carddemo.common.domain.TranCatBal;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.exception.RecordNotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * :purpose: Pure Mockito unit tests for {@link InterestCalculationService}, the ``CBACT04C``
 *     interest-calculation re-platforming. Exercises the monthly-interest formula precision
 *     (multiply-then-divide-by-1200 at scale 2, truncated toward zero), the disclosure-group ``DEFAULT``
 *     fallback, the keyed account and card-cross-reference reads, the interest-transaction
 *     assembly, the transaction write, the per-account cycle roll-up and the account-ordered
 *     driving read.
 * :note: No database, Spring context, or Testcontainers are used; the five repositories are
 *     mocked and the service is constructed directly. ``MockitoExtension`` runs with the
 *     default strict stubs, so each test stubs only what it exercises.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestCalculationService (CBACT04C) unit tests")
class InterestCalculationServiceTest {

    /** Mocked driving-read repository for account-ordered category balances. */
    @Mock
    private TranCatBalRepository tranCatBalRepository;

    /** Mocked repository for the keyed account read and cycle roll-up rewrite. */
    @Mock
    private AccountRepository accountRepository;

    /** Mocked repository for the account-scoped card cross-reference read. */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Mocked repository for the disclosure-group interest-rate lookup. */
    @Mock
    private DiscGroupRepository discGroupRepository;

    /** Mocked repository for persisting assembled interest transactions. */
    @Mock
    private TransactionRepository transactionRepository;

    /** System under test, reconstructed before each scenario. */
    private InterestCalculationService service;

    /**
     * :purpose: Construct the service with the mocked repositories in the exact constructor
     *     order declared by the production class. No stubbing occurs here so that strict stubs
     *     remain satisfied per scenario.
     */
    @BeforeEach
    void setUp() {
        service = new InterestCalculationService(
                tranCatBalRepository,
                accountRepository,
                cardXrefRepository,
                discGroupRepository,
                transactionRepository);
    }

    /**
     * :purpose: Build an {@link Account} carrying only the identity used by the
     *     interest-transaction assembly.
     * :param id: the account identifier.
     * :return: an account with the id populated.
     */
    private static Account newAccountWithId(long id) {
        Account account = new Account();
        account.setAcctId(id);
        return account;
    }

    /**
     * :purpose: Build an {@link Account} with the balance and current-cycle figures exercised
     *     by the cycle roll-up.
     * :param id: the account identifier.
     * :param balance: the current balance.
     * :param cycleCredit: the current-cycle credit figure.
     * :param cycleDebit: the current-cycle debit figure.
     * :return: a fully populated account for roll-up scenarios.
     */
    private static Account newAccount(long id, String balance, String cycleCredit, String cycleDebit) {
        Account account = new Account();
        account.setAcctId(id);
        account.setAcctCurrBal(new BigDecimal(balance));
        account.setAcctCurrCycCredit(new BigDecimal(cycleCredit));
        account.setAcctCurrCycDebit(new BigDecimal(cycleDebit));
        return account;
    }

    /**
     * :purpose: Build a {@link DiscGroup} for disclosure-group resolution scenarios.
     * :param groupId: the account group id key component.
     * :param typeCd: the transaction type code key component.
     * :param catCd: the transaction category code key component.
     * :param rate: the disclosure interest rate.
     * :return: a populated disclosure group.
     */
    private static DiscGroup newDiscGroup(String groupId, String typeCd, Integer catCd, String rate) {
        return new DiscGroup(groupId, typeCd, catCd, new BigDecimal(rate));
    }

    /**
     * :purpose: Build a {@link TranCatBal} row for the account-ordered driving-read scenario.
     * :param acctId: the account identifier key component.
     * :param typeCd: the transaction type code key component.
     * :param catCd: the transaction category code key component.
     * :param balance: the category running balance.
     * :return: a populated transaction-category balance.
     */
    private static TranCatBal newTranCatBal(long acctId, String typeCd, Integer catCd, String balance) {
        return new TranCatBal(acctId, typeCd, catCd, new BigDecimal(balance));
    }

    // ---------------------------------------------------------------------
    // Group A - computeMonthlyInterest (financial precision, no stubbing)
    // ---------------------------------------------------------------------

    /**
     * :purpose: A whole-value rate yields an exact scale-2 result.
     */
    @Test
    @DisplayName("computeMonthlyInterest: 1000.00 at 12.00 rate -> 10.00 (scale 2)")
    void computeMonthlyInterest_basic() {
        BigDecimal result = service.computeMonthlyInterest(new BigDecimal("1000.00"), new BigDecimal("12.00"));

        assertThat(result).isEqualByComparingTo("10.00");
        assertThat(result.scale()).isEqualTo(2);
        verifyNoInteractions(tranCatBalRepository, accountRepository, cardXrefRepository,
                discGroupRepository, transactionRepository);
    }

    /**
     * :purpose: A raw result of exactly 0.005 is TRUNCATED to 0.00. The COBOL ``COMPUTE``
     *     carries no ``ROUNDED`` phrase, so the excess digits are dropped as the quotient is
     *     stored into ``WS-MONTHLY-INT PIC S9(09)V99``; rounding half up added a cent that
     *     propagated into the account balance.
     */
    @Test
    @DisplayName("computeMonthlyInterest: 0.005 truncates to 0.00, never 0.01")
    void computeMonthlyInterest_truncatesExactHalfCent() {
        BigDecimal result = service.computeMonthlyInterest(new BigDecimal("1.00"), new BigDecimal("6.00"));

        assertThat(result).isEqualByComparingTo("0.00");
        assertThat(result.scale()).isEqualTo(2);
    }

    /**
     * :purpose: A raw result of exactly 0.015 is TRUNCATED to 0.01, not rounded to 0.02.
     */
    @Test
    @DisplayName("computeMonthlyInterest: 0.015 truncates to 0.01, never 0.02")
    void computeMonthlyInterest_truncatesOneAndAHalfCent() {
        BigDecimal result = service.computeMonthlyInterest(new BigDecimal("1.00"), new BigDecimal("18.00"));

        assertThat(result).isEqualByComparingTo("0.01");
        assertThat(result.scale()).isEqualTo(2);
    }

    /**
     * :purpose: The golden boundary cases QA measured against the COBOL semantics, asserted
     *     against the PRODUCTION method so the specification cannot drift from the code
     *     again: a non-terminating quotient truncates toward zero in BOTH directions and an
     *     exact 0.125 becomes 0.12.
     */
    @Test
    @DisplayName("computeMonthlyInterest: the golden truncation cases, positive and negative")
    void computeMonthlyInterest_goldenTruncationCases() {
        // 100.00 * 5.00 / 1200 = 0.41666...
        assertThat(service.computeMonthlyInterest(new BigDecimal("100.00"), new BigDecimal("5.00")))
                .isEqualByComparingTo("0.41");
        // 30.00 * 5.00 / 1200 = 0.125 exactly
        assertThat(service.computeMonthlyInterest(new BigDecimal("30.00"), new BigDecimal("5.00")))
                .isEqualByComparingTo("0.12");
        // A credit balance truncates toward zero, so -0.41666... becomes -0.41.
        assertThat(service.computeMonthlyInterest(new BigDecimal("-100.00"), new BigDecimal("5.00")))
                .isEqualByComparingTo("-0.41");
        // -1.00 * 6.00 / 1200 = -0.005 -> -0.00
        assertThat(service.computeMonthlyInterest(new BigDecimal("-1.00"), new BigDecimal("6.00")))
                .isEqualByComparingTo("0.00");
        // Terminating quotients are unaffected.
        assertThat(service.computeMonthlyInterest(new BigDecimal("1000.00"), new BigDecimal("15.00")))
                .isEqualByComparingTo("12.50");
        assertThat(service.computeMonthlyInterest(new BigDecimal("800.00"), new BigDecimal("15.00")))
                .isEqualByComparingTo("10.00");
    }

    /**
     * :purpose: A non-terminating quotient (0.0833...) truncates down to 0.08 at scale 2.
     */
    @Test
    @DisplayName("computeMonthlyInterest: 0.0833... rounds down to 0.08")
    void computeMonthlyInterest_roundsDown() {
        BigDecimal result = service.computeMonthlyInterest(new BigDecimal("100.00"), new BigDecimal("1.00"));

        assertThat(result).isEqualByComparingTo("0.08");
        assertThat(result.scale()).isEqualTo(2);
    }

    /**
     * :purpose: A zero rate yields zero interest; the service applies no rate guard of its own.
     */
    @Test
    @DisplayName("computeMonthlyInterest: zero rate -> 0.00")
    void computeMonthlyInterest_zeroRate() {
        BigDecimal result = service.computeMonthlyInterest(new BigDecimal("1000.00"), new BigDecimal("0.00"));

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.scale()).isEqualTo(2);
    }

    /**
     * :purpose: A signed (negative) balance preserves the sign, mirroring COBOL ``S9(09)V99``.
     */
    @Test
    @DisplayName("computeMonthlyInterest: negative balance -> -10.00")
    void computeMonthlyInterest_negativeBalance() {
        BigDecimal result = service.computeMonthlyInterest(new BigDecimal("-1000.00"), new BigDecimal("12.00"));

        assertThat(result).isEqualByComparingTo("-10.00");
        assertThat(result.scale()).isEqualTo(2);
    }

    /**
     * :purpose: A multi-digit balance and rate exercise the multiply-then-divide order of
     *     ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`` (``CBACT04C`` lines 464-465): 12345.67 x
     *     15.25 = 188271.4675, divided by 1200 gives 156.8928..., which truncates at scale 2
     *     to 156.89 - the same value HALF_UP would give here, so this case isolates the
     *     operand order rather than the rounding mode.
     */
    @Test
    @DisplayName("computeMonthlyInterest: 12345.67 at 15.25 rate -> 156.89")
    void computeMonthlyInterest_multiDigit() {
        BigDecimal result = service.computeMonthlyInterest(new BigDecimal("12345.67"), new BigDecimal("15.25"));

        assertThat(result).isEqualByComparingTo("156.89");
        assertThat(result.scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // Group B - resolveDiscGroup (DEFAULT fallback)
    // ---------------------------------------------------------------------

    /**
     * :purpose: When the primary group id resolves, that group is returned and the ``DEFAULT``
     *     fallback lookup is never attempted.
     */
    @Test
    @DisplayName("resolveDiscGroup: primary found -> no DEFAULT fallback")
    void resolveDiscGroup_primaryFound() {
        DiscGroup dg = newDiscGroup("GRP0000001", "01", 5, "12.00");
        when(discGroupRepository.findById(new DiscGroupId("GRP0000001", "01", 5)))
                .thenReturn(Optional.of(dg));

        DiscGroup result = service.resolveDiscGroup("GRP0000001", "01", 5);

        assertThat(result).isSameAs(dg);
        verify(discGroupRepository, times(1)).findById(new DiscGroupId("GRP0000001", "01", 5));
        verify(discGroupRepository, never()).findById(new DiscGroupId("DEFAULT", "01", 5));
    }

    /**
     * :purpose: When the primary group id is missing, the ``DEFAULT`` group id is re-read with
     *     the same type and category codes, and its result is returned.
     */
    @Test
    @DisplayName("resolveDiscGroup: primary missing -> DEFAULT fallback used")
    void resolveDiscGroup_defaultFallback() {
        DiscGroup defaultDg = newDiscGroup("DEFAULT", "01", 5, "10.00");
        when(discGroupRepository.findById(new DiscGroupId("GRP0000001", "01", 5)))
                .thenReturn(Optional.empty());
        when(discGroupRepository.findById(new DiscGroupId("DEFAULT", "01", 5)))
                .thenReturn(Optional.of(defaultDg));

        DiscGroup result = service.resolveDiscGroup("GRP0000001", "01", 5);

        assertThat(result).isSameAs(defaultDg);
        verify(discGroupRepository, times(1)).findById(new DiscGroupId("GRP0000001", "01", 5));
        verify(discGroupRepository, times(1)).findById(new DiscGroupId("DEFAULT", "01", 5));
    }

    /**
     * :purpose: When both the primary and ``DEFAULT`` lookups are empty, resolution fails with
     *     a {@link RecordNotFoundException} naming the group and the failed fallback.
     */
    @Test
    @DisplayName("resolveDiscGroup: primary and DEFAULT missing -> RecordNotFoundException")
    void resolveDiscGroup_bothMissing() {
        when(discGroupRepository.findById(new DiscGroupId("GRP0000001", "01", 5)))
                .thenReturn(Optional.empty());
        when(discGroupRepository.findById(new DiscGroupId("DEFAULT", "01", 5)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveDiscGroup("GRP0000001", "01", 5))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("GRP0000001")
                .hasMessageContaining("DEFAULT fallback failed");
    }

    // ---------------------------------------------------------------------
    // Group C - loadAccount
    // ---------------------------------------------------------------------

    /**
     * :purpose: A present account id returns the managed account.
     */
    @Test
    @DisplayName("loadAccount: found -> returns account")
    void loadAccount_found() {
        Account account = newAccountWithId(1L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        Account result = service.loadAccount(1L);

        assertThat(result).isSameAs(account);
        verify(accountRepository, times(1)).findById(1L);
    }

    /**
     * :purpose: A missing account id fails with a {@link RecordNotFoundException} naming the id.
     */
    @Test
    @DisplayName("loadAccount: not found -> RecordNotFoundException")
    void loadAccount_notFound() {
        when(accountRepository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadAccount(2L))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("2");
    }

    // ---------------------------------------------------------------------
    // Group D - resolveCardNumber
    // ---------------------------------------------------------------------

    /**
     * :purpose: A present cross-reference yields its 16-character card number via the
     *     ``xref_acct_id`` secondary index, read in ascending ``XREF-CARD-NUM`` order so an
     *     account holding several cards always stamps the same card number on its interest
     *     transaction, as the VSAM alternate-index read did.
     */
    @Test
    @DisplayName("resolveCardNumber: found -> returns the lowest card number of the account")
    void resolveCardNumber_found() {
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(1L))
                .thenReturn(Optional.of(new CardXref("1234567890123456", 100000001L, 1L)));

        String result = service.resolveCardNumber(1L);

        assertThat(result).isEqualTo("1234567890123456");
        verify(cardXrefRepository, times(1)).findFirstByXrefAcctIdOrderByXrefCardNumAsc(1L);
    }

    /**
     * :purpose: A missing cross-reference fails with a {@link RecordNotFoundException} naming
     *     the id.
     */
    @Test
    @DisplayName("resolveCardNumber: not found -> RecordNotFoundException")
    void resolveCardNumber_notFound() {
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(2L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveCardNumber(2L))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("2");
    }

    // ---------------------------------------------------------------------
    // Group E - buildInterestTransaction (exact field mappings, no stubbing)
    // ---------------------------------------------------------------------

    /**
     * :purpose: Every assembled field matches the ``1300-B-WRITE-TX`` contract, including the
     *     16-character transaction id, the fixed type/category/source literals, the zero-padded
     *     description, and a single 26-character DB2 timestamp reused for both the origination
     *     and processing timestamps.
     */
    @Test
    @DisplayName("buildInterestTransaction: all fields mapped exactly")
    void buildInterestTransaction_allFields() {
        Account account = newAccountWithId(99L);

        Transaction tx = service.buildInterestTransaction(
                account, "1234567890123456", new BigDecimal("10.00"), "2022071800", 1L);

        assertThat(tx.getTranId()).isEqualTo("2022071800000001");
        assertThat(tx.getTranId()).hasSize(16);
        assertThat(tx.getTranTypeCd()).isEqualTo("01");
        assertThat(tx.getTranCatCd()).isEqualTo(5);
        assertThat(tx.getTranSource()).isEqualTo("System");
        assertThat(tx.getTranDesc()).isEqualTo("Int. for a/c 00000000099");
        assertThat(tx.getTranDesc()).hasSize(24);
        assertThat(tx.getTranAmt()).isEqualByComparingTo("10.00");
        assertThat(tx.getTranMerchantId()).isEqualTo(0L);
        // MOVE SPACES fills the whole fixed-width field, so the stored value is the field's
        // width in blanks rather than an empty string: CVTRA05Y declares
        // TRAN-MERCHANT-NAME X(50), TRAN-MERCHANT-CITY X(50) and TRAN-MERCHANT-ZIP X(10).
        assertThat(tx.getTranMerchantName()).isEqualTo(" ".repeat(50));
        assertThat(tx.getTranMerchantCity()).isEqualTo(" ".repeat(50));
        assertThat(tx.getTranMerchantZip()).isEqualTo(" ".repeat(10));
        assertThat(tx.getTranCardNum()).isEqualTo("1234567890123456");
        assertThat(tx.getTranOrigTs()).isNotNull();
        assertThat(tx.getTranOrigTs()).hasSize(26);
        assertThat(tx.getTranOrigTs())
                .matches("^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}$");
        assertThat(tx.getTranOrigTs()).endsWith("0000");
        assertThat(tx.getTranOrigTs()).isEqualTo(tx.getTranProcTs());
        verifyNoInteractions(transactionRepository);
    }

    /**
     * :purpose: The six-digit suffix is left-zero-padded and appended to the 10-character parm
     *     date, keeping the id at 16 characters for both a small and a six-digit suffix.
     */
    @Test
    @DisplayName("buildInterestTransaction: suffix zero-padded to 6 digits")
    void buildInterestTransaction_suffixPadding() {
        Account account = newAccountWithId(99L);

        Transaction seven = service.buildInterestTransaction(
                account, "1234567890123456", new BigDecimal("1.00"), "2022071800", 7L);
        assertThat(seven.getTranId()).isEqualTo("2022071800000007");
        assertThat(seven.getTranId()).hasSize(16);

        Transaction big = service.buildInterestTransaction(
                account, "1234567890123456", new BigDecimal("1.00"), "2022071800", 123456L);
        assertThat(big.getTranId()).endsWith("123456");
        assertThat(big.getTranId()).hasSize(16);
    }

    /**
     * :purpose: An 11-digit account id fills the description's zero-padded field exactly.
     */
    @Test
    @DisplayName("buildInterestTransaction: 11-digit acctId fills description")
    void buildInterestTransaction_largeAcctIdPadding() {
        Account account = newAccountWithId(12345678901L);

        Transaction tx = service.buildInterestTransaction(
                account, "1234567890123456", new BigDecimal("1.00"), "2022071800", 1L);

        assertThat(tx.getTranDesc()).isEqualTo("Int. for a/c 12345678901");
    }

    // ---------------------------------------------------------------------
    // Group F - saveTransaction
    // ---------------------------------------------------------------------

    /**
     * :purpose: The write delegates to the repository and returns the persisted instance.
     */
    @Test
    @DisplayName("saveTransaction: delegates to repository")
    void saveTransaction_delegates() {
        Transaction tx = new Transaction();
        when(transactionRepository.save(tx)).thenReturn(tx);

        Transaction result = service.saveTransaction(tx);

        assertThat(result).isSameAs(tx);
        verify(transactionRepository, times(1)).save(tx);
    }

    // ---------------------------------------------------------------------
    // Group G - updateAccount (roll-up + unconditional cycle-zeroing)
    // ---------------------------------------------------------------------

    /**
     * :purpose: The accumulated interest is added to the balance and both current-cycle figures
     *     are zeroed before the account is rewritten.
     */
    @Test
    @DisplayName("updateAccount: adds interest and zeroes cycle figures")
    void updateAccount_addsInterestAndZeroesCycles() {
        Account account = newAccount(1L, "100.00", "500.00", "200.00");

        service.updateAccount(account, new BigDecimal("10.00"));

        assertThat(account.getAcctCurrBal()).isEqualByComparingTo("110.00");
        assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);
        assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(accountRepository, times(1)).save(account);
    }

    /**
     * :purpose: Even with zero interest the cycle figures are zeroed and the account rewritten,
     *     confirming the roll-up runs unconditionally for every account (``CBACT04C`` lines
     *     353-354).
     */
    @Test
    @DisplayName("updateAccount: zero interest still zeroes cycle figures")
    void updateAccount_zeroInterestStillZeroesCycles() {
        Account account = newAccount(1L, "100.00", "500.00", "200.00");

        service.updateAccount(account, new BigDecimal("0.00"));

        assertThat(account.getAcctCurrBal()).isEqualByComparingTo("100.00");
        assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);
        assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(accountRepository, times(1)).save(account);
    }

    // ---------------------------------------------------------------------
    // Group H - readAccountOrderedBalances
    // ---------------------------------------------------------------------

    /**
     * :purpose: The driving read delegates to the account-key-ordered derived query and returns
     *     its rows unchanged, preserving order.
     */
    @Test
    @DisplayName("readAccountOrderedBalances: delegates to ordered query")
    void readAccountOrderedBalances_delegates() {
        Pageable page = PageRequest.of(0, 100);
        TranCatBal tcb1 = newTranCatBal(1L, "01", 5, "100.00");
        TranCatBal tcb2 = newTranCatBal(2L, "01", 5, "200.00");
        when(tranCatBalRepository.findAllByOrderByTrancatAcctIdAscTrancatTypeCdAscTrancatCdAsc(page))
                .thenReturn(List.of(tcb1, tcb2));

        List<TranCatBal> result = service.readAccountOrderedBalances(page);

        assertThat(result).hasSize(2).containsExactly(tcb1, tcb2);
        verify(tranCatBalRepository, times(1))
                .findAllByOrderByTrancatAcctIdAscTrancatTypeCdAscTrancatCdAsc(page);
    }
}

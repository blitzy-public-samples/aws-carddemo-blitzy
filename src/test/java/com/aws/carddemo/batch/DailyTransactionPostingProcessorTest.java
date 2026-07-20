/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aws.carddemo.batch.processor.DailyTransactionPostingProcessor;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.exception.RejectCode;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link DailyTransactionPostingProcessor}, the Spring
 * Batch {@code ItemProcessor} that reproduces the daily-transaction validation core of the legacy
 * COBOL program {@code CBTRN02C} (paragraph {@code 1500-VALIDATE-TRAN} and its sub-paragraphs
 * {@code 1500-A-LOOKUP-XREF} / {@code 1500-B-LOOKUP-ACCT}, {@code legacy/cbl/CBTRN02C.cbl} L370-L422).
 *
 * <h2>Why these tests exist (and why they are pure unit tests)</h2>
 * <p>The processor's two collaborators &mdash; {@link CardXrefRepository} and
 * {@link AccountRepository} &mdash; are mocked so the reject-code decision logic is exercised in
 * complete isolation: <strong>no Spring context, no database, no Testcontainers</strong>. This is the
 * only place reject code <strong>101</strong> ({@code ACCOUNT RECORD NOT FOUND}) can be proven,
 * because in the relational schema {@code card_xref.acct_id} is a {@code NOT NULL} foreign key to
 * {@code account}, so an integration test can never observe a cross-reference hit that fails to
 * resolve an account. Mocking lets us drive exactly that otherwise-unreachable branch, along with the
 * deterministic evaluation order, short-circuit, and last-writer-wins semantics that are the highest
 * parity risk of the posting flow (Technical Specification &sect;0.9.2, hotspot H4).</p>
 *
 * <h2>Frozen parity contract asserted here (the exact order {@code CBTRN02C} imposes)</h2>
 * <ol>
 *   <li><strong>XREF lookup first.</strong> A missing cross-reference yields reject
 *       <strong>100</strong> {@code INVALID CARD NUMBER FOUND} and <em>short-circuits</em>: the
 *       account repository is never consulted (CBTRN02C L372-L376, L385-L387).</li>
 *   <li><strong>Account lookup second</strong> (only when the xref resolved). A missing account
 *       yields reject <strong>101</strong> {@code ACCOUNT RECORD NOT FOUND} (L397-L399).</li>
 *   <li>With the account found, {@code tempBal = currCycCredit - currCycDebit + amount} (L403-L405):
 *     <ul>
 *       <li><em>IF&nbsp;#1</em> &mdash; {@code creditLimit >= tempBal} passes, otherwise reject
 *           <strong>102</strong> {@code OVERLIMIT TRANSACTION} (L407-L413).</li>
 *       <li><em>IF&nbsp;#2, independent</em> &mdash; {@code expirationDate >= origTs(1:10)} passes,
 *           otherwise reject <strong>103</strong> {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}
 *           (L414-L420).</li>
 *     </ul>
 *     Because the two checks are separate assignments to the same reason field, when both fail
 *     <strong>103 wins (last-writer-wins)</strong>.</li>
 *   <li>When every check passes the record is valid: the processor emits a {@code PostingResult} with
 *       a {@code null} reject code and a non-null candidate {@code Transaction}.</li>
 * </ol>
 *
 * <h2>Monetary discipline</h2>
 * <p>Every amount, limit, and balance in the fixtures is a {@link BigDecimal} at scale&nbsp;2 built
 * from a string literal; binary floating-point ({@code double}/{@code float}) is never used, matching
 * the COBOL {@code COMP-3} fidelity requirement.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyTransactionPostingProcessor — CBTRN02C 1500-VALIDATE-TRAN reject-code parity")
class DailyTransactionPostingProcessorTest {

    // ---------------------------------------------------------------------------------------------
    // Fixture constants. Card number, account id, and the cycle amounts are shared so the working
    // balance is a known, deterministic value across every test:
    //     tempBal = CYC_CREDIT - CYC_DEBIT + AMOUNT = 200.00 - 50.00 + 100.00 = 250.00
    // ---------------------------------------------------------------------------------------------

    /** Card number driving the cross-reference lookup (COBOL {@code DALYTRAN-CARD-NUM PIC X(16)}). */
    private static final String CARD_NUM = "1234567890123456";

    /** Account id resolved from the cross-reference (COBOL {@code XREF-ACCT-ID PIC 9(11)}). */
    private static final Long ACCT_ID = 12_345_678_901L;

    /** Owning customer id carried on the cross-reference (COBOL {@code XREF-CUST-ID PIC 9(09)}). */
    private static final Long CUST_ID = 123_456_789L;

    /** Business transaction id (COBOL {@code DALYTRAN-ID PIC X(16)}). */
    private static final String DALYTRAN_ID = "TXN0000000000001";

    /** Transaction type code (COBOL {@code DALYTRAN-TYPE-CD PIC X(02)}). */
    private static final String TYPE_CD = "PU";

    /** Transaction category code (COBOL {@code DALYTRAN-CAT-CD PIC 9(04)}). */
    private static final Integer CAT_CD = 1000;

    /** Transaction source (COBOL {@code DALYTRAN-SOURCE PIC X(10)}). */
    private static final String TRAN_SOURCE = "POS";

    /** Transaction description (COBOL {@code DALYTRAN-DESC PIC X(100)}). */
    private static final String TRAN_DESC = "GROCERY PURCHASE";

    /** Merchant id (COBOL {@code DALYTRAN-MERCHANT-ID PIC 9(09)}). */
    private static final Long MERCHANT_ID = 987_654_321L;

    /** Merchant name (COBOL {@code DALYTRAN-MERCHANT-NAME PIC X(50)}). */
    private static final String MERCHANT_NAME = "TEST MERCHANT";

    /** Merchant city (COBOL {@code DALYTRAN-MERCHANT-CITY PIC X(50)}). */
    private static final String MERCHANT_CITY = "SEATTLE";

    /** Merchant ZIP (COBOL {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}). */
    private static final String MERCHANT_ZIP = "98101";

    /**
     * Original transaction timestamp (COBOL {@code DALYTRAN-ORIG-TS PIC X(26)}). The processor
     * compares the account expiration date against the first ten characters (the {@code yyyy-MM-dd}
     * date portion), which is {@link #ORIG_TS_DATE} here.
     */
    private static final String ORIG_TS = "2024-06-15 12:30:45.123456";

    /** The {@code yyyy-MM-dd} date portion of {@link #ORIG_TS} ({@code origTs.substring(0, 10)}). */
    private static final String ORIG_TS_DATE = "2024-06-15";

    /** Processing timestamp placeholder (COBOL {@code DALYTRAN-PROC-TS PIC X(26)}). */
    private static final String PROC_TS = "2024-06-15 12:30:45.654321";

    /** Account active status flag (COBOL {@code ACCT-ACTIVE-STATUS PIC X(01)}). */
    private static final String ACTIVE_STATUS = "Y";

    /** Account open date (COBOL {@code ACCT-OPEN-DATE PIC X(10)}). */
    private static final String OPEN_DATE = "2020-01-01";

    /** Account reissue date (COBOL {@code ACCT-REISSUE-DATE PIC X(10)}). */
    private static final String REISSUE_DATE = "2023-01-01";

    /** Account address ZIP (COBOL {@code ACCT-ADDR-ZIP PIC X(10)}). */
    private static final String ADDR_ZIP = "98101";

    /** Disclosure-group id (COBOL {@code ACCT-GROUP-ID PIC X(10)}). */
    private static final String GROUP_ID = "DEFAULT";

    // ---- Monetary fixtures (BigDecimal, scale 2) -------------------------------------------------

    /** Zero money value used for the (irrelevant-to-validation) current balance. */
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");

    /** Cash credit limit (not exercised by the validation path, but populated for realism). */
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("1000.00");

    /** Current-cycle credit total (COBOL {@code ACCT-CURR-CYC-CREDIT}). */
    private static final BigDecimal CYC_CREDIT = new BigDecimal("200.00");

    /** Current-cycle debit total (COBOL {@code ACCT-CURR-CYC-DEBIT}). */
    private static final BigDecimal CYC_DEBIT = new BigDecimal("50.00");

    /** Transaction amount (COBOL {@code DALYTRAN-AMT}). */
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    /**
     * The working balance the processor computes: {@code CYC_CREDIT - CYC_DEBIT + AMOUNT}
     * ({@code 200.00 - 50.00 + 100.00 = 250.00}). Declared explicitly so the credit-limit
     * boundary fixtures read unambiguously.
     */
    private static final BigDecimal TEMP_BAL = new BigDecimal("250.00");

    /** A credit limit comfortably above {@link #TEMP_BAL}: the over-limit check passes. */
    private static final BigDecimal LIMIT_AMPLE = new BigDecimal("5000.00");

    /** A credit limit strictly below {@link #TEMP_BAL}: the over-limit check fails (reject 102). */
    private static final BigDecimal LIMIT_OVER = new BigDecimal("100.00");

    /** A credit limit exactly equal to {@link #TEMP_BAL}: the inclusive {@code >=} boundary passes. */
    private static final BigDecimal LIMIT_BOUNDARY = new BigDecimal("250.00");

    /** An expiration date after {@link #ORIG_TS_DATE}: the expiration check passes. */
    private static final String EXP_FUTURE = "2025-12-31";

    /** An expiration date before {@link #ORIG_TS_DATE}: the expiration check fails (reject 103). */
    private static final String EXP_PAST = "2020-01-01";

    /** An expiration date equal to {@link #ORIG_TS_DATE}: the inclusive {@code >=} boundary passes. */
    private static final String EXP_EQUAL = ORIG_TS_DATE;

    /** Mocked cross-reference repository ({@code 1500-A-LOOKUP-XREF}, keyed by card number). */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Mocked account repository ({@code 1500-B-LOOKUP-ACCT}, keyed by account id). */
    @Mock
    private AccountRepository accountRepository;

    /** System under test, wired with the mocks via the processor's constructor (constructor injection). */
    private DailyTransactionPostingProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DailyTransactionPostingProcessor(cardXrefRepository, accountRepository);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture builders. Each returns a fully-populated domain object built through the public
    // all-arguments constructor of the entity (the no-arg constructors are protected / JPA-only).
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a staged daily transaction for {@link #CARD_NUM} with the supplied amount and original
     * timestamp; all other fields carry stable, non-null fixture values.
     *
     * @param amount the transaction amount ({@code DALYTRAN-AMT})
     * @param origTs the 26-character original timestamp ({@code DALYTRAN-ORIG-TS})
     * @return a populated {@link DailyTransaction}
     */
    private static DailyTransaction dailyTransaction(BigDecimal amount, String origTs) {
        return new DailyTransaction(
                DALYTRAN_ID, TYPE_CD, CAT_CD, TRAN_SOURCE, TRAN_DESC, amount,
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP,
                CARD_NUM, origTs, PROC_TS);
    }

    /**
     * Builds the cross-reference row that links {@link #CARD_NUM} to {@link #ACCT_ID}.
     *
     * @return a populated {@link CardXref}
     */
    private static CardXref cardXref() {
        return new CardXref(CARD_NUM, CUST_ID, ACCT_ID);
    }

    /**
     * Builds an account keyed by {@link #ACCT_ID} with the supplied credit limit and expiration date;
     * the cycle credit/debit totals are fixed at {@link #CYC_CREDIT}/{@link #CYC_DEBIT} so the
     * processor's working balance is always {@link #TEMP_BAL}.
     *
     * @param creditLimit    the account credit limit ({@code ACCT-CREDIT-LIMIT})
     * @param expirationDate the account expiration date ({@code ACCT-EXPIRAION-DATE})
     * @return a populated {@link Account}
     */
    private static Account account(BigDecimal creditLimit, String expirationDate) {
        return new Account(
                ACCT_ID, ACTIVE_STATUS, ZERO_MONEY, creditLimit, CASH_CREDIT_LIMIT,
                OPEN_DATE, expirationDate, REISSUE_DATE, CYC_CREDIT, CYC_DEBIT,
                ADDR_ZIP, GROUP_ID);
    }

    // ---------------------------------------------------------------------------------------------
    // Reject-path tests: one dedicated test per reject code, asserting the enum constant, its numeric
    // code, and its verbatim COBOL description text.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("100 INVALID CARD NUMBER FOUND — missing xref rejects and short-circuits the account lookup")
    void reject100_whenXrefNotFound_andShortCircuitsAccountLookup() {
        // 1500-A-LOOKUP-XREF: READ XREF-FILE ... INVALID KEY (CBTRN02C L385-L387).
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        var result = processor.process(dailyTransaction(AMOUNT, ORIG_TS));

        assertThat(result).isNotNull();
        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectCode()).isEqualTo(RejectCode.INVALID_CARD_NUMBER);
        assertThat(result.rejectCode().getCode()).isEqualTo(100);
        assertThat(result.rejectCode().getDescription()).isEqualTo("INVALID CARD NUMBER FOUND");
        // acctId is null only for reject 100 (no cross-reference was resolved).
        assertThat(result.acctId()).isNull();
        // A rejected record never carries a candidate transaction.
        assertThat(result.posted()).isNull();

        // The 100 short-circuit: 1500-B-LOOKUP-ACCT runs only while the fail reason is still zero
        // (CBTRN02C L372), so a missing cross-reference must NEVER reach the account repository.
        verify(cardXrefRepository).findById(CARD_NUM);
        verifyNoInteractions(accountRepository);
    }

    @Test
    @DisplayName("101 ACCOUNT RECORD NOT FOUND — xref resolves but the account is missing")
    void reject101_whenAccountNotFound() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
        // 1500-B-LOOKUP-ACCT: READ ACCOUNT-FILE ... INVALID KEY (CBTRN02C L397-L399). This branch is
        // unreachable from the integration test because card_xref.acct_id is a NOT-NULL FK to account.
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        var result = processor.process(dailyTransaction(AMOUNT, ORIG_TS));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectCode()).isEqualTo(RejectCode.ACCOUNT_NOT_FOUND);
        assertThat(result.rejectCode().getCode()).isEqualTo(101);
        assertThat(result.rejectCode().getDescription()).isEqualTo("ACCOUNT RECORD NOT FOUND");
        assertThat(result.posted()).isNull();
        // The account id was resolved from the cross-reference before the account read failed.
        assertThat(result.acctId()).isEqualTo(ACCT_ID);
    }

    @Test
    @DisplayName("102 OVERLIMIT TRANSACTION — creditLimit < tempBal with a non-expired account")
    void reject102_whenOverLimit() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
        // creditLimit 100.00 < tempBal 250.00 => over limit (L407 fails); expiration in the future.
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(LIMIT_OVER, EXP_FUTURE)));

        var result = processor.process(dailyTransaction(AMOUNT, ORIG_TS));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectCode()).isEqualTo(RejectCode.OVER_CREDIT_LIMIT);
        assertThat(result.rejectCode().getCode()).isEqualTo(102);
        assertThat(result.rejectCode().getDescription()).isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(result.posted()).isNull();
        assertThat(result.acctId()).isEqualTo(ACCT_ID);
    }

    @Test
    @DisplayName("103 TRANSACTION RECEIVED AFTER ACCT EXPIRATION — within limit but expiration < origTs date")
    void reject103_whenAfterExpiration() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
        // creditLimit 5000.00 >= tempBal 250.00 (within limit) but expiration 2020-01-01 < 2024-06-15
        // => transaction received after account expiration (L414 fails).
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(LIMIT_AMPLE, EXP_PAST)));

        var result = processor.process(dailyTransaction(AMOUNT, ORIG_TS));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectCode()).isEqualTo(RejectCode.ACCOUNT_EXPIRED);
        assertThat(result.rejectCode().getCode()).isEqualTo(103);
        assertThat(result.rejectCode().getDescription())
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        assertThat(result.posted()).isNull();
        assertThat(result.acctId()).isEqualTo(ACCT_ID);
    }

    // ---------------------------------------------------------------------------------------------
    // Evaluation-order / last-writer-wins and the valid path.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Last-writer-wins — both over-limit AND expired yields 103 (independent IF at L414 overwrites L410)")
    void lastWriterWins_bothOverlimitAndExpired_yields103() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
        // BOTH conditions fail: creditLimit 100.00 < 250.00 (would set 102) AND expiration 2020-01-01
        // < 2024-06-15 (sets 103). Because the expiration test is a separate, later IF (not else-if),
        // the MOVE 103 at L417 overwrites the MOVE 102 at L410, so 103 wins.
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(LIMIT_OVER, EXP_PAST)));

        var result = processor.process(dailyTransaction(AMOUNT, ORIG_TS));

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectCode()).isEqualTo(RejectCode.ACCOUNT_EXPIRED);
        assertThat(result.rejectCode().getCode()).isEqualTo(103);
        assertThat(result.rejectCode().getDescription())
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        // Prove 103 overwrote 102 — the two checks are independent IFs, not an else-if chain.
        assertThat(result.rejectCode()).isNotEqualTo(RejectCode.OVER_CREDIT_LIMIT);
        assertThat(result.posted()).isNull();
    }

    @Test
    @DisplayName("Valid — within limit and not expired yields no reject and a candidate transaction")
    void valid_whenAllChecksPass_noReject() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
        // creditLimit 5000.00 >= 250.00 (within limit) AND expiration 2025-12-31 >= 2024-06-15 (not expired).
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(LIMIT_AMPLE, EXP_FUTURE)));

        var result = processor.process(dailyTransaction(AMOUNT, ORIG_TS));

        assertThat(result.isRejected()).isFalse();
        assertThat(result.rejectCode()).isNull();
        // A valid record carries the candidate transaction built by 2000-POST-TRANSACTION.
        assertThat(result.posted()).isNotNull();
        assertThat(result.acctId()).isEqualTo(ACCT_ID);
    }

    // ---------------------------------------------------------------------------------------------
    // Inclusive-boundary tests: COBOL uses '>=' for both checks, so equality must PASS (no reject).
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Boundary — creditLimit == tempBal passes the inclusive '>=' over-limit check (no 102)")
    void boundary_creditLimitEqualsTempBal_passesInclusively() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
        // creditLimit 250.00 == tempBal 250.00 => ACCT-CREDIT-LIMIT >= WS-TEMP-BAL is TRUE (L407).
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(LIMIT_BOUNDARY, EXP_FUTURE)));

        var result = processor.process(dailyTransaction(AMOUNT, ORIG_TS));

        assertThat(result.isRejected()).isFalse();
        assertThat(result.rejectCode()).isNull();
        assertThat(result.posted()).isNotNull();
    }

    @Test
    @DisplayName("Boundary — expirationDate == origTs date passes the inclusive '>=' expiration check (no 103)")
    void boundary_expirationDateEqualsOrigTsDate_passesInclusively() {
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
        // expiration 2024-06-15 == origTs(1:10) 2024-06-15 => ACCT-EXPIRAION-DATE >= ... is TRUE (L414).
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(account(LIMIT_AMPLE, EXP_EQUAL)));

        var result = processor.process(dailyTransaction(AMOUNT, ORIG_TS));

        assertThat(result.isRejected()).isFalse();
        assertThat(result.rejectCode()).isNull();
        assertThat(result.posted()).isNotNull();
    }
}

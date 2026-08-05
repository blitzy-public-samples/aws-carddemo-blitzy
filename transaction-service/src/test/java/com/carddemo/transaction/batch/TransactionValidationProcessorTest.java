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
package com.carddemo.transaction.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.DailyTransaction;
import com.carddemo.common.exception.TransactionRejectException;
import com.carddemo.transaction.repository.AccountRepository;
import com.carddemo.transaction.repository.CardXrefRepository;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for :class:`TransactionValidationProcessor`.
 *
 * :purpose: Verify the ``CBTRN02C`` ``1500-VALIDATE-TRAN`` contract that AAP
 *     §0.6.4 freezes: the ordered cross-reference-then-account lookup with its
 *     short circuit, the four reject codes 100/101/102/103 with their verbatim
 *     descriptions, the over-limit verdict computed from the CYCLE figures
 *     (``WS-TEMP-BAL``) rather than the current balance, and the expiration
 *     comparison of ``ACCT-EXPIRAION-DATE`` against the first TEN characters of
 *     the 26-character ``DALYTRAN-ORIG-TS``.
 * :output: JUnit 5 / AssertJ / Mockito assertions only; the two repositories are
 *     mocked so no database, Spring context or file is involved.
 */
@ExtendWith(MockitoExtension.class)
class TransactionValidationProcessorTest {

    /** :purpose: Card number of the daily record under validation. */
    private static final String CARD_NUM = "4111111111111111";

    /** :purpose: Account id the cross-reference resolves to. */
    private static final Long ACCT_ID = 12345678901L;

    /** :purpose: Customer id carried by the cross-reference row. */
    private static final Long CUST_ID = 90L;

    /**
     * :purpose: A 26-character ``DALYTRAN-ORIG-TS``; only its first ten characters
     *     (``2024-06-15``) participate in the expiration comparison.
     */
    private static final String ORIG_TS = "2024-06-15-13.45.30.123456";

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private AccountRepository accountRepository;

    private TransactionValidationProcessor processor;

    @BeforeEach
    void createProcessor() {
        processor = new TransactionValidationProcessor(cardXrefRepository, accountRepository);
    }

    /**
     * Builds a daily-transaction record carrying the given amount.
     *
     * :param amount: the ``DALYTRAN-AMT`` value.
     * :output: a fully populated :class:`DailyTransaction`.
     */
    private static DailyTransaction dailyTransaction(String amount) {
        DailyTransaction dt = new DailyTransaction();
        dt.setDalytranId("0000000000000501");
        dt.setDalytranTypeCd("01");
        dt.setDalytranCatCd(5001);
        dt.setDalytranSource("POS TERM");
        dt.setDalytranDesc("Point of sale purchase");
        dt.setDalytranAmt(new BigDecimal(amount));
        dt.setDalytranMerchantId(123456789L);
        dt.setDalytranMerchantName("Mercado Central");
        dt.setDalytranMerchantCity("Springfield");
        dt.setDalytranMerchantZip("22770");
        dt.setDalytranCardNum(CARD_NUM);
        dt.setDalytranOrigTs(ORIG_TS);
        dt.setDalytranProcTs("2024-06-16-01.00.00.000000");
        return dt;
    }

    /**
     * Builds an account with the cycle figures and expiration date under test.
     *
     * :param creditLimit: ``ACCT-CREDIT-LIMIT``.
     * :param cycCredit: ``ACCT-CURR-CYC-CREDIT``.
     * :param cycDebit: ``ACCT-CURR-CYC-DEBIT``.
     * :param expiraionDate: ``ACCT-EXPIRAION-DATE`` (frozen legacy misspelling).
     * :output: the account the ``1500-B-LOOKUP-ACCT`` read resolves to.
     */
    private static Account account(String creditLimit, String cycCredit, String cycDebit,
                                   String expiraionDate) {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setAcctActiveStatus("Y");
        // A deliberately huge current balance: the over-limit rule must NOT look at it.
        account.setAcctCurrBal(new BigDecimal("999999999.99"));
        account.setAcctCreditLimit(new BigDecimal(creditLimit));
        account.setAcctCashCreditLimit(new BigDecimal(creditLimit));
        account.setAcctCurrCycCredit(new BigDecimal(cycCredit));
        account.setAcctCurrCycDebit(new BigDecimal(cycDebit));
        account.setAcctOpenDate("2013-06-19");
        account.setAcctExpiraionDate(expiraionDate);
        account.setAcctReissueDate("2024-08-11");
        return account;
    }

    /**
     * The ordered lookup and its short circuit (``1500-A`` then ``1500-B``).
     */
    @Nested
    @DisplayName("Ordered lookup: reject 100 and 101")
    class OrderedLookup {

        @Test
        @DisplayName("100 INVALID CARD NUMBER FOUND when the cross-reference is missing, and the account is never read")
        void crossReferenceMissYields100AndSkipsTheAccountRead() {
            DailyTransaction dt = dailyTransaction("100.00");
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM)).thenReturn(Optional.empty());

            PostingItem item = processor.process(dt);

            assertThat(item).isNotNull();
            assertThat(item.isRejected()).isTrue();
            assertThat(item.getRejectCode()).isEqualTo(100);
            assertThat(item.getRejectCode()).isEqualTo(TransactionRejectException.INVALID_CARD_NUMBER);
            assertThat(item.getRejectDescription())
                    .isEqualTo(TransactionRejectException.MSG_INVALID_CARD_NUMBER)
                    .isEqualTo("INVALID CARD NUMBER FOUND");
            assertThat(item.getDailyTransaction()).isSameAs(dt);
            assertThat(item.getAccount()).isNull();
            assertThat(item.getXrefAcctId()).isNull();
            // 1500-VALIDATE-TRAN skips 1500-B-LOOKUP-ACCT once the reason is non-zero.
            verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("101 ACCOUNT RECORD NOT FOUND when the cross-reference resolves but the account does not")
        void accountMissYields101AndCarriesTheCrossReferencedAccountId() {
            DailyTransaction dt = dailyTransaction("100.00");
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            PostingItem item = processor.process(dt);

            assertThat(item.getRejectCode()).isEqualTo(101);
            assertThat(item.getRejectCode()).isEqualTo(TransactionRejectException.ACCOUNT_NOT_FOUND);
            assertThat(item.getRejectDescription())
                    .isEqualTo(TransactionRejectException.MSG_ACCOUNT_NOT_FOUND)
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");
            assertThat(item.getAccount()).isNull();
            assertThat(item.getXrefAcctId()).isEqualTo(ACCT_ID);
            verify(accountRepository).findById(ACCT_ID);
        }

        @Test
        @DisplayName("the account is read with the cross-referenced id, never with the card number")
        void accountIsReadByCrossReferencedAccountId() {
            DailyTransaction dt = dailyTransaction("10.00");
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account("5000.00", "0.00", "0.00", "2025-01-01")));

            processor.process(dt);

            verify(cardXrefRepository).findByXrefCardNum(CARD_NUM);
            verify(accountRepository).findById(ACCT_ID);
            verify(accountRepository, never()).findById(0L);
        }
    }

    /**
     * The over-limit rule and its exact boundary.
     *
     * ``COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT +
     * DALYTRAN-AMT`` then ``IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`` passes.
     */
    @Nested
    @DisplayName("Reject 102: over-limit computed from the cycle figures")
    class OverLimit {

        @ParameterizedTest
        @DisplayName("the verdict flips exactly at ACCT-CREDIT-LIMIT >= WS-TEMP-BAL")
        @CsvSource({
                // creditLimit, cycCredit, cycDebit,  amount,  expectedCode
                "1000.00,      0.00,      0.00,      999.99,  0",     // below the limit
                "1000.00,      0.00,      0.00,      1000.00, 0",     // exactly AT the limit -> passes
                "1000.00,      0.00,      0.00,      1000.01, 102",   // one cent over -> rejects
                "1000.00,      400.00,    100.00,    700.00,  0",     // tempBal = 1000.00 -> passes
                "1000.00,      400.00,    100.00,    700.01,  102",   // tempBal = 1000.01 -> rejects
                "1000.00,      100.00,    900.00,    1800.00, 0",     // debit reduces tempBal to 1000.00
                "1000.00,      0.00,      0.00,      -5000.00, 0"     // a credit can never be over-limit
        })
        void overLimitBoundaryIsExact(String creditLimit, String cycCredit, String cycDebit,
                                      String amount, int expectedCode) {
            DailyTransaction dt = dailyTransaction(amount);
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    account(creditLimit, cycCredit, cycDebit, "2025-01-01")));

            PostingItem item = processor.process(dt);

            assertThat(item.getRejectCode()).isEqualTo(expectedCode);
            if (expectedCode == 102) {
                assertThat(item.getRejectDescription())
                        .isEqualTo(TransactionRejectException.MSG_OVER_LIMIT)
                        .isEqualTo("OVERLIMIT TRANSACTION");
                assertThat(item.getAccount()).isNotNull();
                assertThat(item.getXrefAcctId()).isEqualTo(ACCT_ID);
            } else {
                assertThat(item.getRejectDescription()).isNull();
            }
        }

        @Test
        @DisplayName("the current balance is ignored: a hugely overdrawn account still passes on the cycle figures")
        void currentBalanceIsNotPartOfTheRule() {
            DailyTransaction dt = dailyTransaction("1.00");
            Account overdrawn = account("1000.00", "0.00", "0.00", "2025-01-01");
            overdrawn.setAcctCurrBal(new BigDecimal("999999999.99"));
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(overdrawn));

            assertThat(processor.process(dt).getRejectCode()).isZero();
        }

        @Test
        @DisplayName("WS-TEMP-BAL is evaluated at scale 2 (PIC S9(09)V99), truncating toward zero")
        void tempBalanceIsEvaluatedAtScaleTwo() {
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    account("1000.00", "0.00", "0.00", "2025-01-01")));

            // WS-TEMP-BAL is a PIC S9(09)V99 receiver and the COBOL COMPUTE carries no
            // ROUNDED phrase, so the third fraction digit is DROPPED: 1000.005 becomes
            // 1000.00, exactly AT the limit, which passes. Comparing the unnormalized
            // 1000.005 would reject, and HALF_UP would give 1000.01 and also reject,
            // so a code of 0 here is what pins truncation at scale 2.
            assertThat(processor.process(dailyTransaction("1000.005")).getRejectCode()).isZero();

            // The truncation is not a clamp: a third digit that still leaves the
            // truncated value above the limit rejects. 1000.015 -> 1000.01 -> 102.
            assertThat(processor.process(dailyTransaction("1000.015")).getRejectCode())
                    .isEqualTo(102);
        }
    }

    /**
     * The expiration rule and the ten-character timestamp prefix.
     */
    @Nested
    @DisplayName("Reject 103: ACCT-EXPIRAION-DATE against DALYTRAN-ORIG-TS (1:10)")
    class Expiration {

        @ParameterizedTest
        @DisplayName("passes when ACCT-EXPIRAION-DATE >= the origination DATE, rejects when strictly earlier")
        @CsvSource({
                "2024-06-16, 0",     // after  the origination date
                "2024-06-15, 0",     // EQUAL to the origination date -> passes
                "2024-06-14, 103",   // one day before -> rejects
                "1999-12-31, 103"
        })
        void expirationBoundaryIsExact(String expiraionDate, int expectedCode) {
            DailyTransaction dt = dailyTransaction("10.00");
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    account("100000.00", "0.00", "0.00", expiraionDate)));

            PostingItem item = processor.process(dt);

            assertThat(item.getRejectCode()).isEqualTo(expectedCode);
            if (expectedCode == 103) {
                assertThat(item.getRejectDescription())
                        .isEqualTo(TransactionRejectException.MSG_ACCOUNT_EXPIRED)
                        .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
            }
        }

        @Test
        @DisplayName("only the first ten characters of the 26-character timestamp are compared")
        void onlyTheDatePortionOfTheTimestampIsCompared() {
            DailyTransaction lateInTheDay = dailyTransaction("10.00");
            lateInTheDay.setDalytranOrigTs("2024-06-15-23.59.59.999999");
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    account("100000.00", "0.00", "0.00", "2024-06-15")));

            // The time of day never matters: the account expires ON that date, so the
            // record is accepted no matter how late in the day it originated.
            assertThat(processor.process(lateInTheDay).getRejectCode()).isZero();
        }

        @Test
        @DisplayName("103 overwrites 102 when the record is both over-limit and past expiration")
        void expirationOverwritesOverLimit() {
            DailyTransaction dt = dailyTransaction("5000.00");
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    account("10.00", "0.00", "0.00", "2020-01-01")));

            PostingItem item = processor.process(dt);

            // The legacy code applies the expiration IF as a second, independent test
            // inside the account-found branch, so its verdict wins.
            assertThat(item.getRejectCode()).isEqualTo(103);
            assertThat(item.getRejectDescription())
                    .isEqualTo(TransactionRejectException.MSG_ACCOUNT_EXPIRED);
        }
    }

    /**
     * The pass-through verdict.
     */
    @Nested
    @DisplayName("Valid records")
    class Valid {

        @Test
        @DisplayName("a record that clears every rule carries reason 0, the account and the cross-referenced id")
        void validRecordCarriesReasonZeroAndTheResolvedAccount() {
            DailyTransaction dt = dailyTransaction("250.75");
            Account resolved = account("5000.00", "0.00", "0.00", "2025-12-31");
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(resolved));

            PostingItem item = processor.process(dt);

            assertThat(item.isRejected()).isFalse();
            assertThat(item.getRejectCode()).isZero();
            assertThat(item.getRejectDescription()).isNull();
            assertThat(item.getDailyTransaction()).isSameAs(dt);
            assertThat(item.getAccount()).isSameAs(resolved);
            assertThat(item.getXrefAcctId()).isEqualTo(ACCT_ID);
        }

        @Test
        @DisplayName("the processor never returns null, so no record is silently dropped from the feed")
        void processorNeverFiltersARecordOut() {
            DailyTransaction dt = dailyTransaction("10.00");
            when(cardXrefRepository.findByXrefCardNum(any())).thenReturn(Optional.empty());

            assertThat(processor.process(dt)).isNotNull();
        }
    }
}

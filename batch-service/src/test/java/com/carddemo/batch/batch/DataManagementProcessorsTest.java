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
package com.carddemo.batch.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.DailyTransaction;
import com.carddemo.common.domain.TranCatg;
import com.carddemo.common.domain.TranCatgId;
import com.carddemo.common.domain.TranType;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.exception.RecordNotFoundException;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the data-management item processors.
 *
 * :purpose: Verify :class:`DailyTransactionValidationProcessor` (``CBTRN01C``
 *     ordered cross-reference then account lookup, which reports but never
 *     filters a record) and :class:`TransactionReportItemProcessor` (``CBTRN03C``
 *     ``1500-A/B/C`` lookups, which abend on a missing reference row) so the two
 *     opposite error policies are pinned and cannot silently swap.
 * :output: JUnit 5 / AssertJ / Mockito assertions; every collaborator is mocked so
 *     no Spring context, database or file is involved.
 */
@ExtendWith(MockitoExtension.class)
class DataManagementProcessorsTest {

    /** :purpose: Card number of the record under validation. */
    private static final String CARD_NUM = "4859452612877065";

    /** :purpose: Account id the cross-reference resolves to. */
    private static final Long ACCT_ID = 1L;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private AccountRepository accountRepository;

    /**
     * Builds the daily-transaction record under validation.
     *
     * :output: a minimally populated feed record.
     */
    private static DailyTransaction dailyTransaction() {
        DailyTransaction record = new DailyTransaction();
        record.setDalytranId("0000000000683580");
        record.setDalytranTypeCd("01");
        record.setDalytranCatCd(1);
        record.setDalytranAmt(new BigDecimal("504.77"));
        record.setDalytranCardNum(CARD_NUM);
        record.setDalytranOrigTs("2022-06-10 19:27:53.000000");
        return record;
    }

    @Nested
    @DisplayName("DailyTransactionValidationProcessor (CBTRN01C): reports, never filters")
    class ValidationProcessor {

        private DailyTransactionValidationProcessor processor;

        @BeforeEach
        void createProcessor() {
            processor = new DailyTransactionValidationProcessor(cardXrefRepository, accountRepository);
        }

        @Test
        @DisplayName("a fully resolvable record is returned unchanged, with both lookups performed")
        void resolvableRecordIsReturnedUnchanged() throws Exception {
            DailyTransaction record = dailyTransaction();
            when(cardXrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, 90L, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(new Account()));

            assertThat(processor.process(record)).isSameAs(record);
            verify(cardXrefRepository).findById(CARD_NUM);
            verify(accountRepository).findById(ACCT_ID);
        }

        @Test
        @DisplayName("a missing cross-reference short-circuits the account read yet still passes the record on")
        void missingCrossReferenceSkipsTheAccountRead() throws Exception {
            DailyTransaction record = dailyTransaction();
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            // CBTRN01C reports the miss and continues, so the count-only writer still
            // observes every input record; the record is never filtered out (null).
            assertThat(processor.process(record)).isSameAs(record);
            verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("a missing account is reported but the record is still passed on")
        void missingAccountStillPassesTheRecordOn() throws Exception {
            DailyTransaction record = dailyTransaction();
            when(cardXrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, 90L, ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            assertThat(processor.process(record)).isSameAs(record);
            verify(accountRepository).findById(ACCT_ID);
        }

        @Test
        @DisplayName("the cross-reference is read by card number, never by the transaction id")
        void crossReferenceIsReadByCardNumber() throws Exception {
            DailyTransaction record = dailyTransaction();
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            processor.process(record);

            verify(cardXrefRepository).findById(CARD_NUM);
            verify(cardXrefRepository, never()).findById(record.getDalytranId());
        }
    }

    @Nested
    @DisplayName("TransactionReportItemProcessor (CBTRN03C): abends on a missing reference row")
    class ReportItemProcessor {

        @Mock
        private EntityManager entityManager;

        private TransactionReportItemProcessor processor;

        @BeforeEach
        void createProcessor() {
            processor = new TransactionReportItemProcessor(cardXrefRepository);
            // The production field is injected by @PersistenceContext at runtime.
            ReflectionTestUtils.setField(processor, "entityManager", entityManager);
        }

        /**
         * Builds the posted transaction under report.
         *
         * :output: a populated transaction.
         */
        private static Transaction transaction() {
            Transaction transaction = new Transaction();
            transaction.setTranId("0000000000683580");
            transaction.setTranTypeCd("01");
            transaction.setTranCatCd(1);
            transaction.setTranSource("POS TERM");
            transaction.setTranAmt(new BigDecimal("504.77"));
            transaction.setTranCardNum(CARD_NUM);
            return transaction;
        }

        @Test
        @DisplayName("the resolved row carries the zero-padded eleven-digit account id and both descriptions")
        void resolvedRowCarriesEveryLookedUpValue() throws Exception {
            Transaction transaction = transaction();
            when(cardXrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, 90L, ACCT_ID)));
            when(entityManager.find(TranType.class, "01"))
                    .thenReturn(new TranType("01", "Purchase"));
            when(entityManager.find(TranCatg.class, new TranCatgId("01", 1)))
                    .thenReturn(new TranCatg("01", 1, "Regular Sales Draft"));

            TransactionReportItem item = processor.process(transaction);

            assertThat(item.getTranId()).isEqualTo("0000000000683580");
            assertThat(item.getAccountId()).isEqualTo("00000000001");
            assertThat(item.getAccountId()).hasSize(11);
            assertThat(item.getTranTypeCd()).isEqualTo("01");
            assertThat(item.getTranTypeDesc()).isEqualTo("Purchase");
            assertThat(item.getTranCatCd()).isEqualTo(1);
            assertThat(item.getTranCatDesc()).isEqualTo("Regular Sales Draft");
            assertThat(item.getTranSource()).isEqualTo("POS TERM");
            assertThat(item.getTranAmt()).isEqualByComparingTo("504.77");
            assertThat(item.getTranCardNum()).isEqualTo(CARD_NUM);
        }

        @Test
        @DisplayName("a missing cross-reference abends, and the reference rows are never read")
        void missingCrossReferenceAbends() {
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> processor.process(transaction()))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("Card cross-reference not found for transaction")
                    .hasMessageContaining("0000000000683580");
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("a missing transaction type abends before the category is read")
        void missingTransactionTypeAbends() {
            when(cardXrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, 90L, ACCT_ID)));
            when(entityManager.find(TranType.class, "01")).thenReturn(null);

            assertThatThrownBy(() -> processor.process(transaction()))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("Transaction type not found for transaction");
            verify(entityManager, never()).find(any(), any(TranCatgId.class));
        }

        @Test
        @DisplayName("a missing transaction category abends")
        void missingTransactionCategoryAbends() {
            when(cardXrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, 90L, ACCT_ID)));
            when(entityManager.find(TranType.class, "01"))
                    .thenReturn(new TranType("01", "Purchase"));
            when(entityManager.find(TranCatg.class, new TranCatgId("01", 1))).thenReturn(null);

            assertThatThrownBy(() -> processor.process(transaction()))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("Transaction category not found for transaction");
        }

        @Test
        @DisplayName("the category is looked up by the compound (type, category) key")
        void categoryIsLookedUpByTheCompoundKey() throws Exception {
            Transaction transaction = transaction();
            transaction.setTranTypeCd("05");
            transaction.setTranCatCd(4321);
            when(cardXrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, 90L, 42L)));
            when(entityManager.find(TranType.class, "05"))
                    .thenReturn(new TranType("05", "Payment"));
            when(entityManager.find(TranCatg.class, new TranCatgId("05", 4321)))
                    .thenReturn(new TranCatg("05", 4321, "Bill Payment"));

            TransactionReportItem item = processor.process(transaction);

            assertThat(item.getAccountId()).isEqualTo("00000000042");
            assertThat(item.getTranCatDesc()).isEqualTo("Bill Payment");
            verify(entityManager).find(TranCatg.class, new TranCatgId("05", 4321));
        }
    }
}

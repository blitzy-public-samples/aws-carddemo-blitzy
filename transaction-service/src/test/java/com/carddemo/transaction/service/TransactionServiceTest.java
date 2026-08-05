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
package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.TransactionAddRequestDto;
import com.carddemo.common.dto.TransactionAddResponseDto;
import com.carddemo.common.dto.TransactionListRequestDto;
import com.carddemo.common.dto.TransactionListResponseDto;
import com.carddemo.common.dto.TransactionViewResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.CardXrefRepository;
import com.carddemo.transaction.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for :class:`TransactionService`.
 *
 * :purpose: Verify the online transaction contracts migrated from ``COTRN00C``
 *     (list, ``CT00``), ``COTRN01C`` (view, ``CT01``) and ``COTRN02C`` (add,
 *     ``CT02``) — in particular the AAP §0.6.5 transaction-id rule that the id is
 *     drawn from the database sequence yet still surfaces in the frozen 16-digit
 *     zero-padded wire form, the ordered add-screen validations with their
 *     verbatim messages, the ``STARTBR`` GTEQ paging semantics, and the
 *     zero-padded key normalization of the view lookup.
 * :output: JUnit 5 / AssertJ / Mockito assertions; the repositories are mocked and
 *     the real :class:`TransactionMapper` is used, so no database, Spring context
 *     or file is involved.
 *
 * Stubbing is lenient because several scenarios assert that a rule short-circuits
 * BEFORE a stubbed collaborator is reached; the assertions themselves verify which
 * repository calls did and did not happen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionServiceTest {

    /** :purpose: Card number the cross-reference resolves for the add screen. */
    private static final String CARD_NUM = "4111111111111111";

    /** :purpose: Account id the cross-reference resolves for the add screen. */
    private static final Long ACCT_ID = 12345678901L;

    /** :purpose: Low-key sentinel used as the top-of-file browse cursor. */
    private static final String LOW_SENTINEL = "0000000000000000";

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    private TransactionService service;

    @BeforeEach
    void createService() {
        service = new TransactionService(transactionRepository, cardXrefRepository,
                new TransactionMapper());
    }

    /**
     * Builds an add request that clears every validation rule.
     *
     * :output: a fully valid, confirmed add request keyed by account id.
     */
    private static TransactionAddRequestDto validAddRequest() {
        TransactionAddRequestDto request = new TransactionAddRequestDto();
        request.setAcctId("12345678901");
        request.setTranTypeCd("01");
        request.setTranCatCd(5001);
        request.setTranSource("POS TERM");
        request.setTranDesc("Point of sale purchase");
        request.setTranAmt(new BigDecimal("250.75"));
        request.setTranOrigTs("2024-06-15");
        request.setTranProcTs("2024-06-16");
        request.setTranMerchantId(123456789L);
        request.setTranMerchantName("Mercado Central");
        request.setTranMerchantCity("Springfield");
        request.setTranMerchantZip("22770");
        request.setConfirm("Y");
        return request;
    }

    /**
     * Builds a persisted transaction fixture.
     *
     * :param tranId: the 16-character zero-padded id.
     * :output: a transaction whose fields the mapper copies to the DTOs.
     */
    private static Transaction transaction(String tranId) {
        Transaction entity = new Transaction();
        entity.setTranId(tranId);
        entity.setTranTypeCd("01");
        entity.setTranCatCd(5001);
        entity.setTranSource("POS TERM");
        entity.setTranDesc("Point of sale purchase");
        entity.setTranAmt(new BigDecimal("250.75"));
        entity.setTranMerchantId(123456789L);
        entity.setTranMerchantName("Mercado Central");
        entity.setTranMerchantCity("Springfield");
        entity.setTranMerchantZip("22770");
        entity.setTranCardNum(CARD_NUM);
        entity.setTranOrigTs("2024-06-15-13.45.30.123456");
        entity.setTranProcTs("2024-06-16-01.00.00.000000");
        return entity;
    }

    /**
     * Stubs a resolvable cross-reference for the account-id key path.
     */
    private void stubAccountCrossReference() {
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID))
                .thenReturn(Optional.of(new CardXref(CARD_NUM, 90L, ACCT_ID)));
    }

    /**
     * Stubs the sequence and the save so an add reaches its confirmation response.
     *
     * :param sequenceValue: the value the ``transaction_id_seq`` sequence yields.
     */
    private void stubSequenceAndSave(long sequenceValue) {
        when(transactionRepository.getNextTransactionId()).thenReturn(sequenceValue);
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Builds the violation PostgreSQL raises for a duplicate transaction primary key.
     *
     * :param constraintName: the violated constraint name reported by the driver.
     * :returns: a :java:type:`DataIntegrityViolationException` with a chained SQL state 23505.
     */
    private static DataIntegrityViolationException uniqueViolation(String constraintName) {
        return integrityViolation("23505",
                "ERROR: duplicate key value violates unique constraint \"" + constraintName + "\"");
    }

    /**
     * Builds a data-integrity violation carrying a realistic chained {@link SQLException}, the
     * only thing that distinguishes a duplicate transaction id from any other integrity fault.
     *
     * :param sqlState: the SQL state the driver reports.
     * :param message: the driver message.
     * :returns: a :java:type:`DataIntegrityViolationException` wrapping that SQL exception.
     */
    private static DataIntegrityViolationException integrityViolation(String sqlState,
                                                                     String message) {
        return new DataIntegrityViolationException(message,
                new SQLException(message, sqlState));
    }

    @Nested
    @DisplayName("addTransaction: 16-digit id generation (AAP 0.6.5)")
    class IdGeneration {

        @ParameterizedTest
        @DisplayName("the sequence value is rendered as a 16-digit zero-padded id")
        @CsvSource({
                "1,                0000000000000001",
                "42,               0000000000000042",
                "999,              0000000000000999",
                "1234567890,       0000001234567890",
                "1234567890123456, 1234567890123456"
        })
        void sequenceValueIsZeroPaddedTo16Digits(long sequenceValue, String expectedId) {
            stubAccountCrossReference();
            stubSequenceAndSave(sequenceValue);

            TransactionAddResponseDto response = service.addTransaction(validAddRequest(), null);

            assertThat(response.getTranId()).isEqualTo(expectedId);
            assertThat(response.getTranId()).hasSize(16);
            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getTranId()).isEqualTo(expectedId);
        }

        @Test
        @DisplayName("the id comes from the database sequence, never from a browse of the last record")
        void idIsDrawnFromTheSequenceNotFromABrowse() {
            stubAccountCrossReference();
            stubSequenceAndSave(7L);

            service.addTransaction(validAddRequest(), null);

            verify(transactionRepository).getNextTransactionId();
            // The legacy MOVE HIGH-VALUES / STARTBR / READPREV race is replaced by the
            // sequence, so the descending browse must not be used for id generation.
            verify(transactionRepository, never()).findFirstByOrderByTranIdDesc();
        }

        @Test
        @DisplayName("the confirmation message carries the generated id verbatim, with its two spaces")
        void confirmationMessageIsVerbatim() {
            stubAccountCrossReference();
            stubSequenceAndSave(42L);

            TransactionAddResponseDto response = service.addTransaction(validAddRequest(), null);

            assertThat(response.getMessage())
                    .isEqualTo("Transaction added successfully.  Your Tran ID is 0000000000000042.");
        }

        @Test
        @DisplayName("a duplicate transaction id surfaces the verbatim 'Tran ID already exist...' message")
        void duplicateKeyIsTranslated() {
            stubAccountCrossReference();
            when(transactionRepository.getNextTransactionId()).thenReturn(42L);
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(uniqueViolation("transactions_pkey"));

            assertThatThrownBy(() -> service.addTransaction(validAddRequest(), null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage("Tran ID already exist...");
        }

        @Test
        @DisplayName("a violated CHECK constraint is NOT reported as a duplicate transaction id")
        void checkConstraintViolationIsNotReportedAsDuplicateId() {
            stubAccountCrossReference();
            when(transactionRepository.getNextTransactionId()).thenReturn(42L);
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(integrityViolation("23514",
                            "new row for relation \"transactions\" violates check constraint "
                                    + "\"chk_transactions_merchant_id\""));

            assertThatThrownBy(() -> service.addTransaction(validAddRequest(), null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("a numeric overflow is NOT reported as a duplicate transaction id")
        void numericOverflowIsNotReportedAsDuplicateId() {
            stubAccountCrossReference();
            when(transactionRepository.getNextTransactionId()).thenReturn(42L);
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(integrityViolation("22003", "numeric field overflow"));

            assertThatThrownBy(() -> service.addTransaction(validAddRequest(), null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("a unique violation on another column is NOT reported as a duplicate transaction id")
        void unrelatedUniqueViolationIsNotReportedAsDuplicateId() {
            stubAccountCrossReference();
            when(transactionRepository.getNextTransactionId()).thenReturn(42L);
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(integrityViolation("23505",
                            "duplicate key value violates unique constraint "
                                    + "\"uq_some_other_table_column\""));

            assertThatThrownBy(() -> service.addTransaction(validAddRequest(), null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("addTransaction: key resolution and session continuity")
    class KeyResolution {

        @Test
        @DisplayName("the account id takes priority over the card number")
        void accountIdHasPriorityOverCardNumber() {
            TransactionAddRequestDto request = validAddRequest();
            request.setTranCardNum("9999999999999999");
            stubAccountCrossReference();
            stubSequenceAndSave(1L);

            service.addTransaction(request, null);

            verify(cardXrefRepository).findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID);
            verify(cardXrefRepository, never()).findByXrefCardNum(anyString());
            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).saveAndFlush(saved.capture());
            // The cross-referenced card number replaces whatever the screen submitted.
            assertThat(saved.getValue().getTranCardNum()).isEqualTo(CARD_NUM);
        }

        @Test
        @DisplayName("the card number is used when no account id is supplied")
        void cardNumberPathIsUsedWithoutAnAccountId() {
            TransactionAddRequestDto request = validAddRequest();
            request.setAcctId(null);
            request.setTranCardNum(CARD_NUM);
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM))
                    .thenReturn(Optional.of(new CardXref(CARD_NUM, 90L, ACCT_ID)));
            stubSequenceAndSave(1L);

            service.addTransaction(request, null);

            verify(cardXrefRepository).findByXrefCardNum(CARD_NUM);
        }

        @Test
        @DisplayName("a missing account cross-reference raises 'Account ID NOT found...' (404)")
        void missingAccountCrossReferenceIsNotFound() {
            when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addTransaction(validAddRequest(), null))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage("Account ID NOT found...");
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a missing card cross-reference raises 'Card Number NOT found...' (404)")
        void missingCardCrossReferenceIsNotFound() {
            TransactionAddRequestDto request = validAddRequest();
            request.setAcctId(null);
            request.setTranCardNum(CARD_NUM);
            when(cardXrefRepository.findByXrefCardNum(CARD_NUM)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addTransaction(request, null))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage("Card Number NOT found...");
        }

        @ParameterizedTest
        @DisplayName("non-numeric and absent keys raise their verbatim edit messages")
        @CsvSource({
                "ABC,  ,      Account ID must be Numeric...",
                "  ,   ABCD,   Card Number must be Numeric...",
                "  ,   ,       Account or Card Number must be entered..."
        })
        void invalidKeysRaiseTheirVerbatimMessage(String acctId, String cardNum, String message) {
            TransactionAddRequestDto request = validAddRequest();
            request.setAcctId(acctId);
            request.setTranCardNum(cardNum);

            assertThatThrownBy(() -> service.addTransaction(request, null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage(message);
        }

        @Test
        @DisplayName("a null request is rejected before any lookup")
        void nullRequestIsRejected() {
            assertThatThrownBy(() -> service.addTransaction(null, null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage("Account or Card Number must be entered...");
            verifyNoInteractions(cardXrefRepository, transactionRepository);
        }

        @Test
        @DisplayName("the resolved account and card are propagated into the session context")
        void resolvedKeysArePropagatedToTheSessionContext() {
            stubAccountCrossReference();
            stubSequenceAndSave(1L);
            SessionContext context = new SessionContext();

            service.addTransaction(validAddRequest(), context);

            assertThat(context.getAcctId()).isEqualTo(ACCT_ID);
            assertThat(context.getCardNum()).isEqualTo(CARD_NUM);
        }
    }

    @Nested
    @DisplayName("addTransaction: ordered field validation")
    class FieldValidation {

        /**
         * Applies a mutation to an otherwise valid request and asserts the message.
         *
         * :param mutation: the field mutation that must trigger the rejection.
         * :param expectedMessage: the verbatim legacy message expected.
         */
        private void assertRejects(Consumer<TransactionAddRequestDto> mutation,
                                   String expectedMessage) {
            TransactionAddRequestDto request = validAddRequest();
            mutation.accept(request);
            stubAccountCrossReference();

            assertThatThrownBy(() -> service.addTransaction(request, null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage(expectedMessage);
            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("every empty-field guard raises its verbatim message")
        void emptyFieldGuards() {
            assertRejects(r -> r.setTranTypeCd(null), "Type CD can NOT be empty...");
            assertRejects(r -> r.setTranCatCd(null), "Category CD can NOT be empty...");
            assertRejects(r -> r.setTranSource("  "), "Source can NOT be empty...");
            assertRejects(r -> r.setTranDesc(null), "Description can NOT be empty...");
            assertRejects(r -> r.setTranAmt(null), "Amount can NOT be empty...");
            assertRejects(r -> r.setTranOrigTs(null), "Orig Date can NOT be empty...");
            assertRejects(r -> r.setTranProcTs(""), "Proc Date can NOT be empty...");
            assertRejects(r -> r.setTranMerchantId(null), "Merchant ID can NOT be empty...");
            assertRejects(r -> r.setTranMerchantName(null), "Merchant Name can NOT be empty...");
            assertRejects(r -> r.setTranMerchantCity(null), "Merchant City can NOT be empty...");
            assertRejects(r -> r.setTranMerchantZip(null), "Merchant Zip can NOT be empty...");
        }

        @Test
        @DisplayName("the empty-field guards fire in the exact legacy order")
        void emptyFieldGuardsFireInLegacyOrder() {
            // Everything after the type code is blanked too; the FIRST message must be
            // the type-code one, proving the legacy evaluation order is preserved.
            TransactionAddRequestDto request = validAddRequest();
            request.setTranTypeCd(null);
            request.setTranCatCd(null);
            request.setTranSource(null);
            request.setTranDesc(null);
            request.setTranAmt(null);
            stubAccountCrossReference();

            assertThatThrownBy(() -> service.addTransaction(request, null))
                    .hasMessage("Type CD can NOT be empty...");
        }

        @Test
        @DisplayName("numeric, amount-format and date-shape rules raise their verbatim messages")
        void formatRules() {
            assertRejects(r -> r.setTranTypeCd("XX"), "Type CD must be Numeric...");
            assertRejects(r -> r.setTranCatCd(-1), "Category CD must be Numeric...");
            assertRejects(r -> r.setTranAmt(new BigDecimal("100000000.00")),
                    "Amount should be in format -99999999.99");
            assertRejects(r -> r.setTranAmt(new BigDecimal("-100000000.00")),
                    "Amount should be in format -99999999.99");
            assertRejects(r -> r.setTranOrigTs("15/06/2024"), "Orig Date should be in format YYYY-MM-DD");
            assertRejects(r -> r.setTranProcTs("2024/06/16"), "Proc Date should be in format YYYY-MM-DD");
            assertRejects(r -> r.setTranMerchantId(-1L), "Merchant ID must be Numeric...");
        }

        @Test
        @DisplayName("an over-precise amount is rejected, never rounded into the picture")
        void overPreciseAmountsAreRejected() {
            // COTRN02C L339-351 tests TRNAMTI(11:2): a third decimal digit has nowhere to go on
            // the map, so silently rounding 1.005 to 1.01 would alter a financial value the
            // caller never authorised (AAP 0.7.6).
            assertRejects(r -> r.setTranAmt(new BigDecimal("1.005")),
                    "Amount should be in format -99999999.99");
            assertRejects(r -> r.setTranAmt(new BigDecimal("-1.005")),
                    "Amount should be in format -99999999.99");
            assertRejects(r -> r.setTranAmt(new BigDecimal("0.001")),
                    "Amount should be in format -99999999.99");
            assertRejects(r -> r.setTranAmt(new BigDecimal("99999999.999")),
                    "Amount should be in format -99999999.99");
        }

        @Test
        @DisplayName("trailing zeros are presentation only and stay acceptable")
        void trailingZeroAmountsAreAccepted() {
            TransactionAddRequestDto request = validAddRequest();
            request.setTranAmt(new BigDecimal("1.500"));
            stubAccountCrossReference();
            stubSequenceAndSave(1L);

            service.addTransaction(request, null);

            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getTranAmt()).isEqualByComparingTo("1.50");
            assertThat(saved.getValue().getTranAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("legacy field widths bound the category code and the merchant id")
        void legacyNumericWidthsAreEnforced() {
            // TCATCDI is four characters (TRAN-CAT-CD PIC 9(04)) and MIDI is nine
            // (TRAN-MERCHANT-ID PIC 9(09)); an over-wide value used to reach the INSERT and
            // trip a CHECK constraint, which was then reported as a duplicate transaction id.
            assertRejects(r -> r.setTranCatCd(10000), "Category CD must be Numeric...");
            assertRejects(r -> r.setTranMerchantId(1_000_000_000L), "Merchant ID must be Numeric...");
        }

        @Test
        @DisplayName("the widest in-picture category code and merchant id are accepted")
        void legacyNumericWidthBoundariesAreInclusive() {
            TransactionAddRequestDto request = validAddRequest();
            request.setTranCatCd(9999);
            request.setTranMerchantId(999_999_999L);
            stubAccountCrossReference();
            stubSequenceAndSave(1L);

            assertThat(service.addTransaction(request, null).getTranId()).hasSize(16);
        }

        @Test
        @DisplayName("an impossible calendar date is rejected with the CSUTLDTC message")
        void impossibleDatesAreRejected() {
            assertRejects(r -> r.setTranOrigTs("2024-02-30"), "Orig Date - Not a valid date...");
            assertRejects(r -> r.setTranProcTs("2023-02-29"), "Proc Date - Not a valid date...");
        }

        @Test
        @DisplayName("a leap-year date is accepted")
        void leapYearDateIsAccepted() {
            TransactionAddRequestDto request = validAddRequest();
            request.setTranOrigTs("2024-02-29");
            stubAccountCrossReference();
            stubSequenceAndSave(1L);

            assertThat(service.addTransaction(request, null).getTranId()).hasSize(16);
        }

        @ParameterizedTest
        @DisplayName("the amount boundary -99999999.99 .. 99999999.99 is inclusive")
        @ValueSource(strings = {"99999999.99", "-99999999.99", "0.00"})
        void amountBoundaryIsInclusive(String amount) {
            TransactionAddRequestDto request = validAddRequest();
            request.setTranAmt(new BigDecimal(amount));
            stubAccountCrossReference();
            stubSequenceAndSave(1L);

            service.addTransaction(request, null);

            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getTranAmt()).isEqualByComparingTo(new BigDecimal(amount));
            assertThat(saved.getValue().getTranAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("an in-picture amount is stored at the fixed COBOL scale of two")
        void amountIsStoredAtScaleTwo() {
            // A value the TRNAMTI picture can carry is padded to the fixed S9(09)V99 scale;
            // a value it cannot carry (10.005) is rejected by the amount edit instead of being
            // rounded into the picture -- see overPreciseAmountsAreRejected().
            TransactionAddRequestDto request = validAddRequest();
            request.setTranAmt(new BigDecimal("10.5"));
            stubAccountCrossReference();
            stubSequenceAndSave(1L);

            service.addTransaction(request, null);

            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getTranAmt()).isEqualTo(new BigDecimal("10.50"));
        }

        @Test
        @DisplayName("the confirmation flag is evaluated only after every field rule")
        void confirmationFlagRules() {
            assertRejects(r -> r.setConfirm(null), "Confirm to add this transaction...");
            assertRejects(r -> r.setConfirm("N"), "Confirm to add this transaction...");
            assertRejects(r -> r.setConfirm("X"), "Invalid value. Valid values are (Y/N)...");
        }

        @Test
        @DisplayName("a lower-case confirmation is accepted, as the legacy screen upper-cases input")
        void lowerCaseConfirmationIsAccepted() {
            TransactionAddRequestDto request = validAddRequest();
            request.setConfirm("y");
            stubAccountCrossReference();
            stubSequenceAndSave(1L);

            assertThat(service.addTransaction(request, null).getTranId()).isEqualTo("0000000000000001");
        }
    }

    @Nested
    @DisplayName("viewTransaction (CT01 / COTRN01C)")
    class ViewTransaction {

        @Test
        @DisplayName("a short numeric id is normalized to the 16-character zero-padded key")
        void idIsNormalizedToTheStoredKey() {
            when(transactionRepository.findById("0000000000000042"))
                    .thenReturn(Optional.of(transaction("0000000000000042")));

            TransactionViewResponseDto response = service.viewTransaction("42", null);

            assertThat(response.getTranId()).isEqualTo("0000000000000042");
            verify(transactionRepository).findById("0000000000000042");
        }

        @Test
        @DisplayName("every field of the located transaction is mapped to the view response")
        void allFieldsAreMapped() {
            when(transactionRepository.findById("0000000000000042"))
                    .thenReturn(Optional.of(transaction("0000000000000042")));

            TransactionViewResponseDto response = service.viewTransaction("0000000000000042", null);

            assertThat(response.getTranCardNum()).isEqualTo(CARD_NUM);
            assertThat(response.getTranTypeCd()).isEqualTo("01");
            assertThat(response.getTranCatCd()).isEqualTo(5001);
            assertThat(response.getTranSource()).isEqualTo("POS TERM");
            assertThat(response.getTranAmt()).isEqualTo(new BigDecimal("250.75"));
            assertThat(response.getTranDesc()).isEqualTo("Point of sale purchase");
            assertThat(response.getTranOrigTs()).isEqualTo("2024-06-15-13.45.30.123456");
            assertThat(response.getTranProcTs()).isEqualTo("2024-06-16-01.00.00.000000");
            assertThat(response.getTranMerchantId()).isEqualTo(123456789L);
            assertThat(response.getTranMerchantName()).isEqualTo("Mercado Central");
            assertThat(response.getTranMerchantCity()).isEqualTo("Springfield");
            assertThat(response.getTranMerchantZip()).isEqualTo("22770");
        }

        @ParameterizedTest
        @DisplayName("an absent, empty or blank id raises 'Tran ID can NOT be empty...'")
        @ValueSource(strings = {"", " ", "     "})
        void blankIdIsRejected(String tranId) {
            assertThatThrownBy(() -> service.viewTransaction(tranId, null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage("Tran ID can NOT be empty...");
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a null id raises 'Tran ID can NOT be empty...'")
        void nullIdIsRejected() {
            assertThatThrownBy(() -> service.viewTransaction(null, null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage("Tran ID can NOT be empty...");
        }

        @Test
        @DisplayName("an unknown id raises 'Transaction ID NOT found...' (404)")
        void unknownIdIsNotFound() {
            when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.viewTransaction("999", null))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage("Transaction ID NOT found...");
        }
    }

    @Nested
    @DisplayName("viewLastTransaction (COTRN02C COPY-LAST-TRAN-DATA)")
    class ViewLastTransaction {

        @Test
        @DisplayName("the descending-order read reproduces the HIGH-VALUES / READPREV browse")
        void readsTheHighestKeyedTransaction() {
            when(transactionRepository.findFirstByOrderByTranIdDesc())
                    .thenReturn(Optional.of(transaction("0000000000000099")));

            TransactionViewResponseDto response = service.viewLastTransaction();

            assertThat(response.getTranId()).isEqualTo("0000000000000099");
            verify(transactionRepository).findFirstByOrderByTranIdDesc();
        }

        @Test
        @DisplayName("every copyable field of the last transaction is mapped")
        void allCopyableFieldsAreMapped() {
            when(transactionRepository.findFirstByOrderByTranIdDesc())
                    .thenReturn(Optional.of(transaction("0000000000000099")));

            TransactionViewResponseDto response = service.viewLastTransaction();

            assertThat(response.getTranTypeCd()).isEqualTo("01");
            assertThat(response.getTranCatCd()).isEqualTo(5001);
            assertThat(response.getTranSource()).isEqualTo("POS TERM");
            assertThat(response.getTranAmt()).isEqualTo(new BigDecimal("250.75"));
            assertThat(response.getTranDesc()).isEqualTo("Point of sale purchase");
            assertThat(response.getTranOrigTs()).isEqualTo("2024-06-15-13.45.30.123456");
            assertThat(response.getTranProcTs()).isEqualTo("2024-06-16-01.00.00.000000");
            assertThat(response.getTranMerchantId()).isEqualTo(123456789L);
            assertThat(response.getTranMerchantName()).isEqualTo("Mercado Central");
            assertThat(response.getTranMerchantCity()).isEqualTo("Springfield");
            assertThat(response.getTranMerchantZip()).isEqualTo("22770");
        }

        @Test
        @DisplayName("an empty transaction file raises 'Transaction ID NOT found...' (404)")
        void emptyFileIsNotFound() {
            when(transactionRepository.findFirstByOrderByTranIdDesc()).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.viewLastTransaction())
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage("Transaction ID NOT found...");
        }
    }

    @Nested
    @DisplayName("listTransactions (CT00 / COTRN00C)")
    class ListTransactions {

        /**
         * Captures the cursor the service passed to the ascending browse query.
         *
         * :output: the cursor value.
         */
        private String capturedForwardCursor() {
            ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            verify(transactionRepository).findByTranIdGreaterThanOrderByTranIdAsc(
                    cursor.capture(), any(Pageable.class));
            return cursor.getValue();
        }

        @Test
        @DisplayName("ENTER without a filter browses from the top of the file")
        void enterWithoutFilterBrowsesFromTheTop() {
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of());

            TransactionListResponseDto response = service.listTransactions(
                    new TransactionListRequestDto(), null);

            assertThat(capturedForwardCursor()).isEqualTo(LOW_SENTINEL);
            assertThat(response.getTransactions()).isEmpty();
            assertThat(response.isNextPage()).isFalse();
        }

        @ParameterizedTest
        @DisplayName("a filter id starts the browse inclusively (STARTBR GTEQ semantics)")
        @CsvSource({
                "5,    0000000000000004",
                "1,    0000000000000000",
                "0,    0000000000000000",
                "1000, 0000000000000999"
        })
        void filterStartsTheBrowseInclusively(String filter, String expectedCursor) {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setTranIdFilter(filter);
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of());

            service.listTransactions(request, null);

            assertThat(capturedForwardCursor()).isEqualTo(expectedCursor);
        }

        @Test
        @DisplayName("a non-numeric filter raises 'Tran ID must be Numeric ...'")
        void nonNumericFilterIsRejected() {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setTranIdFilter("ABC");

            assertThatThrownBy(() -> service.listTransactions(request, null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage("Tran ID must be Numeric ...");
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("a page carries ten rows with first/last cursors and the next-page flag")
        void pageCarriesCursorsAndNextPageFlag() {
            List<Transaction> rows = List.of(
                    transaction("0000000000000001"), transaction("0000000000000002"),
                    transaction("0000000000000003"), transaction("0000000000000004"),
                    transaction("0000000000000005"), transaction("0000000000000006"),
                    transaction("0000000000000007"), transaction("0000000000000008"),
                    transaction("0000000000000009"), transaction("0000000000000010"));
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(rows);
            when(transactionRepository.existsByTranIdGreaterThan("0000000000000010")).thenReturn(true);

            TransactionListResponseDto response = service.listTransactions(
                    new TransactionListRequestDto(), null);

            assertThat(response.getTransactions()).hasSize(10);
            assertThat(response.getTranIdFirst()).isEqualTo("0000000000000001");
            assertThat(response.getTranIdLast()).isEqualTo("0000000000000010");
            assertThat(response.isNextPage()).isTrue();
            assertThat(response.getPageNumber()).isEqualTo(1);
            assertThat(response.getTransactions().get(0).getTranId()).isEqualTo("0000000000000001");
            assertThat(response.getTransactions().get(0).getTranAmt())
                    .isEqualTo(new BigDecimal("250.75"));
        }

        @Test
        @DisplayName("a list description is truncated to the TDESC0n field width of 26")
        void listDescriptionIsTruncatedToTheMapFieldWidth() {
            Transaction row = transaction("0000000000000001");
            row.setTranDesc("Return item at Nitzsche, Nicolas and Lowe");
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of(row));

            TransactionListResponseDto response =
                    service.listTransactions(new TransactionListRequestDto(), null);

            // COTRN00C moves TRAN-DESC PIC X(100) into TDESC0nI PIC X(26); the 3270 field
            // is a fixed 26-cell window, so the row can never occupy more than one line.
            assertThat(response.getTransactions()).hasSize(1);
            assertThat(response.getTransactions().get(0).getTranDesc())
                    .isEqualTo("Return item at Nitzsche, N")
                    .hasSize(26);
        }

        @Test
        @DisplayName("a description already within the field width is left unchanged")
        void shortListDescriptionIsUnchanged() {
            Transaction row = transaction("0000000000000001");
            row.setTranDesc("Purchase at Guann LLC");
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of(row));

            TransactionListResponseDto response =
                    service.listTransactions(new TransactionListRequestDto(), null);

            assertThat(response.getTransactions().get(0).getTranDesc())
                    .isEqualTo("Purchase at Guann LLC");
        }

        @Test
        @DisplayName("the page reads exactly ten rows per page")
        void pageSizeIsTen() {
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of());

            service.listTransactions(new TransactionListRequestDto(), null);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository).findByTranIdGreaterThanOrderByTranIdAsc(
                    anyString(), pageable.capture());
            assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("PF8 at the bottom surfaces the banner and leaves the displayed rows in place")
        void pageForwardAtTheBottomSurfacesTheBanner() {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setAction("PF8");
            request.setNextPage(false);
            request.setPageNumber(3);
            request.setTranIdFirst("0000000000000021");
            request.setTranIdLast("0000000000000030");
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of(transaction("0000000000000021")));

            TransactionListResponseDto response = service.listTransactions(request, null);

            assertThat(response.getMessage()).isEqualTo("You are already at the bottom of the page...");
            assertThat(response.getPageNumber()).isEqualTo(3);
            assertThat(response.getTranIdFirst()).isEqualTo("0000000000000021");
            // PROCESS-PF8-KEY reaches SEND-TRNLST-SCREEN with SEND-ERASE-NO, which re-sends
            // the map without erasing it, so the rows already on the screen stay visible.
            assertThat(response.getTransactions()).hasSize(1);
            assertThat(response.isNextPage()).isFalse();
        }

        @Test
        @DisplayName("PF7 on the first page with nothing yet displayed surfaces the banner and reads nothing")
        void pageBackwardOnTheFirstPageSurfacesTheBanner() {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setAction("PF7");
            request.setPageNumber(1);

            TransactionListResponseDto response = service.listTransactions(request, null);

            assertThat(response.getMessage()).isEqualTo("You are already at the top of the page...");
            assertThat(response.getTransactions()).isEmpty();
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("PF7 on the first page re-reads the page on display so its rows stay visible")
        void pageBackwardOnTheFirstPageRedisplaysItsRows() {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setAction("PF7");
            request.setPageNumber(1);
            request.setTranIdFirst("0000000000000001");
            request.setTranIdLast("0000000000000010");
            request.setNextPage(true);
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of(transaction("0000000000000001")));

            TransactionListResponseDto response = service.listTransactions(request, null);

            assertThat(response.getMessage()).isEqualTo("You are already at the top of the page...");
            assertThat(response.getTransactions()).hasSize(1);
            assertThat(response.getPageNumber()).isEqualTo(1);
            assertThat(response.isNextPage()).isTrue();
            // The cursor is made inclusive so the same page comes back, not the next one.
            ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            verify(transactionRepository).findByTranIdGreaterThanOrderByTranIdAsc(
                    cursor.capture(), any(Pageable.class));
            assertThat(cursor.getValue()).isEqualTo("0000000000000000");
        }

        @Test
        @DisplayName("PF7 reads the previous page descending and re-sorts it into display order")
        void pageBackwardResortsIntoAscendingOrder() {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setAction("PF7");
            request.setPageNumber(3);
            request.setTranIdFirst("0000000000000021");
            when(transactionRepository.findByTranIdLessThanOrderByTranIdDesc(anyString(),
                    any(Pageable.class))).thenReturn(List.of(
                            transaction("0000000000000020"),
                            transaction("0000000000000019"),
                            transaction("0000000000000018")));

            TransactionListResponseDto response = service.listTransactions(request, null);

            assertThat(response.getTransactions()).extracting("tranId")
                    .containsExactly("0000000000000018", "0000000000000019", "0000000000000020");
            assertThat(response.getPageNumber()).isEqualTo(2);
            ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
            verify(transactionRepository).findByTranIdLessThanOrderByTranIdDesc(
                    cursor.capture(), any(Pageable.class));
            assertThat(cursor.getValue()).isEqualTo("0000000000000021");
        }

        @Test
        @DisplayName("PF8 browses forward from the last id of the current page")
        void pageForwardBrowsesFromTheLastId() {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setAction("PF8");
            request.setNextPage(true);
            request.setPageNumber(1);
            request.setTranIdLast("0000000000000010");
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of(transaction("0000000000000011")));

            TransactionListResponseDto response = service.listTransactions(request, null);

            assertThat(capturedForwardCursor()).isEqualTo("0000000000000010");
            assertThat(response.getPageNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("an empty forward page keeps the current page number and clears the next-page flag")
        void emptyForwardPageKeepsThePageNumber() {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setAction("PF8");
            request.setNextPage(true);
            request.setPageNumber(4);
            request.setTranIdLast("0000000000000040");
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of());

            TransactionListResponseDto response = service.listTransactions(request, null);

            assertThat(response.getPageNumber()).isEqualTo(4);
            assertThat(response.isNextPage()).isFalse();
        }

        @Test
        @DisplayName("selection 'S' echoes the selected id for the view navigation")
        void selectionEchoesTheSelectedId() {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setSelectionFlag("S");
            request.setSelectedTranId("0000000000000007");
            request.setPageNumber(2);

            TransactionListResponseDto response = service.listTransactions(request, null);

            assertThat(response.getSelectedTranId()).isEqualTo("0000000000000007");
            assertThat(response.getPageNumber()).isEqualTo(2);
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("any other selection flag raises 'Invalid selection. Valid value is S'")
        void invalidSelectionFlagIsRejected() {
            TransactionListRequestDto request = new TransactionListRequestDto();
            request.setSelectionFlag("X");
            request.setSelectedTranId("0000000000000007");

            assertThatThrownBy(() -> service.listTransactions(request, null))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage("Invalid selection. Valid value is S");
        }

        @Test
        @DisplayName("a null request is treated as a first-page ENTER")
        void nullRequestIsTreatedAsFirstPageEnter() {
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of());

            assertThat(service.listTransactions(null, null)).isNotNull();
            assertThat(capturedForwardCursor()).isEqualTo(LOW_SENTINEL);
        }

        @Test
        @DisplayName("the list row shows the short origination date derived from the timestamp")
        void listRowShowsTheShortOriginationDate() {
            when(transactionRepository.findByTranIdGreaterThanOrderByTranIdAsc(anyString(),
                    any(Pageable.class))).thenReturn(List.of(transaction("0000000000000001")));

            TransactionListResponseDto response = service.listTransactions(
                    new TransactionListRequestDto(), null);

            // COTRN00 renders the origination date as MM/DD/YY from the first ten
            // characters of the 26-character timestamp.
            assertThat(response.getTransactions().get(0).getTranDate()).isEqualTo("06/15/24");
        }
    }
}

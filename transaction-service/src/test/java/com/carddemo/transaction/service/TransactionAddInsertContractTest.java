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
package com.carddemo.transaction.service;

import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.dto.TransactionAddRequestDto;
import com.carddemo.common.dto.TransactionAddResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.CardXrefRepository;
import com.carddemo.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * :purpose: Verify that adding a transaction performs a genuine INSERT that is flushed
 *     inside the service's own try block, so an id that already exists is rejected with
 *     the verbatim ``COTRN02C`` duplicate-key message and the existing financial record
 *     is never overwritten.
 */
@ExtendWith(MockitoExtension.class)
class TransactionAddInsertContractTest {

    /** :purpose: Frozen duplicate-key message (``COTRN02C`` L738 DUPKEY/DUPREC). */
    private static final String MSG_TRAN_ID_EXISTS = "Tran ID already exist...";

    /** :purpose: Account id used by the key-field branch of the add flow. */
    private static final String ACCT_ID_INPUT = "00000000001";

    /** :purpose: Cross-referenced card number resolved from the account id. */
    private static final String CARD_NUM = "9000000000000001";

    /**
     * :purpose: The ten-character date the ``LENGTH=10`` COTRN02 map field carries
     *     (``TORIGDT`` / ``TPROCDT``).
     */
    private static final String ORIG_DATE_INPUT = "2026-08-01";

    /**
     * :purpose: The stored twenty-six-character form of that date: the field
     *     left-justified and space-filled, which is what ``MOVE TORIGDTI TO
     *     TRAN-ORIG-TS`` produces at ``COTRN02C`` L464-L465.
     */
    private static final String ORIG_DATE_STORED = "2026-08-01" + "                ";

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionMapper transactionMapper;

    @InjectMocks
    private TransactionService transactionService;

    private TransactionAddRequestDto request;

    @BeforeEach
    void setUp() {
        request = new TransactionAddRequestDto();
        request.setAcctId(ACCT_ID_INPUT);
        request.setTranTypeCd("01");
        request.setTranCatCd(1);
        request.setTranSource("POS TERM");
        request.setTranDesc("CONTRACT TEST ROW");
        request.setTranAmt(new BigDecimal("0.01"));
        request.setTranMerchantId(999L);
        request.setTranMerchantName("MERCHANT");
        request.setTranMerchantCity("CITY");
        request.setTranMerchantZip("ZIP");
        // TORIGDT / TPROCDT are DFHMDF LENGTH=10 [app/bms/COTRN02.bms:L187-L190, L200-L203],
        // so the ten-character date is the WHOLE field the screen can send. COTRN02C L464
        // widens it to the stored twenty-six characters; the service does the same.
        request.setTranOrigTs(ORIG_DATE_INPUT);
        request.setTranProcTs(ORIG_DATE_INPUT);
        request.setConfirm("Y");

        CardXref xref = new CardXref();
        xref.setXrefCardNum(CARD_NUM);
        xref.setXrefAcctId(1L);
        xref.setXrefCustId(1L);
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(1L)).thenReturn(Optional.of(xref));
        // Lenient: the id generator and the mapper are reached only AFTER every field edit
        // passes, so a test that asserts a field is refused never gets this far.
        lenient().when(transactionRepository.getNextTransactionId()).thenReturn(900L);
        lenient().when(transactionMapper.toEntity(any(TransactionAddRequestDto.class)))
                .thenAnswer(invocation -> new Transaction());
    }

    @Test
    @DisplayName("A successful add flushes the INSERT and returns the 16-digit id")
    void successfulAddFlushesTheInsert() {
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionAddResponseDto response = transactionService.addTransaction(request, null);

        assertThat(response.getTranId()).isEqualTo("0000000000000900");
        assertThat(response.getMessage())
                .isEqualTo("Transaction added successfully.  Your Tran ID is 0000000000000900.");
        // saveAndFlush, never plain save: the INSERT must hit the database inside the
        // try block so a duplicate key becomes the COBOL rejection, not a silent UPDATE.
        ArgumentCaptor<Transaction> inserted = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(inserted.capture());
        verify(transactionRepository, never()).save(any(Transaction.class));
        // The ten-character map field is widened to the stored twenty-six characters BEFORE
        // the row is written, so every CT02 row has one stored shape (COTRN02C L464-L465).
        assertThat(inserted.getValue().getTranOrigTs())
                .isEqualTo(ORIG_DATE_STORED)
                .hasSize(26);
        assertThat(inserted.getValue().getTranProcTs())
                .isEqualTo(ORIG_DATE_STORED)
                .hasSize(26);
    }

    @Test
    @DisplayName("A date submitted space-padded to the stored width is normalized identically")
    void spacePaddedDateStoresTheSameBytes() {
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // COTRN02C L487-L488 moves the 26-character STORED value straight back into the
        // 10-character map field on redisplay, so a resubmitted screen can legitimately
        // present the date space-filled. It must store byte-identically to the bare date.
        request.setTranOrigTs(ORIG_DATE_STORED);
        request.setTranProcTs(ORIG_DATE_STORED);

        transactionService.addTransaction(request, null);

        ArgumentCaptor<Transaction> inserted = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(inserted.capture());
        assertThat(inserted.getValue().getTranOrigTs()).isEqualTo(ORIG_DATE_STORED);
        assertThat(inserted.getValue().getTranProcTs()).isEqualTo(ORIG_DATE_STORED);
    }

    @Test
    @DisplayName("A date carrying a trailing suffix is refused, never stored verbatim")
    void suffixedDateIsRefusedBeforeAnyWrite() {
        // The pre-fix shape check read only positions one to ten, so this value passed and
        // was persisted verbatim as a timestamp. Ten characters is the whole field.
        request.setTranOrigTs("2026-08-01GARBAGE-SUFFIX!!");

        assertThatThrownBy(() -> transactionService.addTransaction(request, null))
                .isInstanceOf(CardDemoException.class)
                .hasMessage("Orig Date should be in format YYYY-MM-DD");

        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("A colliding transaction id is rejected with the verbatim COBOL message")
    void collidingIdIsRejectedWithTheCobolMessage() {
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"transactions_pkey\""));

        assertThatThrownBy(() -> transactionService.addTransaction(request, null))
                .isInstanceOf(CardDemoException.class)
                .hasMessage(MSG_TRAN_ID_EXISTS);

        verify(transactionRepository).saveAndFlush(any(Transaction.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }
}

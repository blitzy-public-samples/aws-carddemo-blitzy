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
package com.aws.carddemo.batch.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.repository.DailyTransactionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit test for {@link DailyTransactionItemReader}, the input side of the COBOL {@code CBTRN02C}
 * {@code 1000-DALYTRAN-GET-NEXT} sequential read loop (behavioral spec: {@code
 * legacy/app/cbl/CBTRN02C.cbl}; fixture origin: {@code legacy/app/data/ASCII/dailytran.txt} &mdash;
 * 16-character zero-padded ascending ids).
 *
 * <p>Verifies VSAM-parity read semantics: ascending {@code dalytranId} order (FILE STATUS {@code
 * '00'} returns the next record) and {@code null} at end-of-file (FILE STATUS {@code '10'} sets
 * {@code END-OF-FILE = 'Y'}). Pure JUnit 5 + Mockito test; the backing {@link
 * DailyTransactionRepository} is mocked, so no Spring context or database is required.
 *
 * <p>The reader extends {@link org.springframework.batch.item.data.RepositoryItemReader}, which is
 * page-driven: it calls {@code findAll(Pageable)} for page 0, serves those items one per {@code
 * read()}, and fetches the next page when the current one is exhausted, returning {@code null} only
 * once a fetched page comes back empty. Each test therefore stubs {@code findAll} exactly as many
 * times as the reader invokes it (Mockito strict stubs), and any test that reads to end-of-file
 * supplies a terminal empty page so the reader cannot loop forever.
 */
@ExtendWith(MockitoExtension.class)
class DailyTransactionItemReaderTest {

  @Mock private DailyTransactionRepository dailyTransactionRepository;

  private DailyTransactionItemReader reader;

  @BeforeEach
  void setUp() {
    reader = new DailyTransactionItemReader(dailyTransactionRepository);
  }

  private static DailyTransaction dailyTransaction(String dalytranId) {
    DailyTransaction transaction = new DailyTransaction();
    transaction.setDalytranId(dalytranId);
    return transaction;
  }

  @Test
  void read_returnsRecordsInAscendingDalytranIdOrder_preservingVsamSequentialReadParity()
      throws Exception {
    DailyTransaction first = dailyTransaction("0000000000000001");
    DailyTransaction second = dailyTransaction("0000000000000002");
    DailyTransaction third = dailyTransaction("0000000000000003");
    // Page content is already ascending (what the DB returns for Sort dalytranId ASC); the reader
    // passes it through unsorted. Reading exactly 3-of-3 triggers ONE findAll (page 0) -> one stub.
    when(dailyTransactionRepository.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(first, second, third)));

    reader.open(new ExecutionContext());

    DailyTransaction read1 = reader.read();
    DailyTransaction read2 = reader.read();
    DailyTransaction read3 = reader.read();

    assertThat(read1).isNotNull();
    assertThat(read2).isNotNull();
    assertThat(read3).isNotNull();
    assertThat(List.of(read1.getDalytranId(), read2.getDalytranId(), read3.getDalytranId()))
        .containsExactly("0000000000000001", "0000000000000002", "0000000000000003");
  }

  @Test
  void read_returnsNull_afterAllRecordsConsumed_matchingFileStatus10EndOfFile() throws Exception {
    DailyTransaction first = dailyTransaction("0000000000000001");
    DailyTransaction second = dailyTransaction("0000000000000002");
    List<DailyTransaction> empty = List.of();
    // Page 0 -> two items; page 1 -> empty so the reader reports EOF (FILE STATUS '10').
    when(dailyTransactionRepository.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(first, second)))
        .thenReturn(new PageImpl<>(empty));

    reader.open(new ExecutionContext());

    assertThat(reader.read()).isNotNull();
    assertThat(reader.read()).isNotNull();
    assertThat(reader.read()).isNull();
  }

  @Test
  void read_returnsNull_whenNoDailyTransactionsExist_matchingEmptyFileEndOfFile() throws Exception {
    List<DailyTransaction> empty = List.of();
    when(dailyTransactionRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(empty));

    reader.open(new ExecutionContext());

    assertThat(reader.read()).isNull();
  }

  @Test
  void read_requestsPageSizeOf100_andAscendingSortOnDalytranId() throws Exception {
    when(dailyTransactionRepository.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(dailyTransaction("0000000000000001"))));

    reader.open(new ExecutionContext());
    reader.read();

    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(dailyTransactionRepository).findAll(pageableCaptor.capture());
    Pageable pageable = pageableCaptor.getValue();
    assertThat(pageable.getPageNumber()).isZero();
    assertThat(pageable.getPageSize()).isEqualTo(100);
    assertThat(pageable.getSort().stream().count()).isEqualTo(1L);
    Sort.Order order = pageable.getSort().getOrderFor("dalytranId");
    assertThat(order).isNotNull();
    assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
  }

  @Test
  void read_savesReadCountUnderReaderName_forRestartParity() throws Exception {
    when(dailyTransactionRepository.findAll(any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(
                List.of(
                    dailyTransaction("0000000000000001"), dailyTransaction("0000000000000002"))));
    ExecutionContext executionContext = new ExecutionContext();

    reader.open(executionContext);
    reader.read();
    reader.read();
    reader.update(executionContext);

    // saveState(true) + setName("dailyTransactionItemReader") namespace the read.count key.
    assertThat(executionContext.containsKey("dailyTransactionItemReader.read.count")).isTrue();
    assertThat(executionContext.getInt("dailyTransactionItemReader.read.count")).isEqualTo(2);
  }
}

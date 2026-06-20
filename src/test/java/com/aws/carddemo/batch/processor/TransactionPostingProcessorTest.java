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
package com.aws.carddemo.batch.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.service.batch.TransactionPostingService;
import com.aws.carddemo.service.batch.TransactionPostingService.PostingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link TransactionPostingProcessor}, the
 * Spring Batch {@code ItemProcessor} that reproduces the per-record validate / post-or-reject
 * decision of legacy COBOL program {@code CBTRN02C} ({@code 1500-VALIDATE-TRAN}).
 *
 * <p>The processor is a <strong>thin delegator</strong>: its only collaborator is {@link
 * TransactionPostingService}, mocked here, and its only behaviour is to return that service's
 * per-record result unchanged. The tests therefore pin three contract invariants and nothing else
 * (there is no business logic in the class to exercise):
 *
 * <ul>
 *   <li><strong>Exact delegation.</strong> {@link
 *       TransactionPostingProcessor#process(DailyTransaction)} returns the <em>same instance</em>
 *       the service returns and invokes the service <em>exactly once</em> per record, with no other
 *       interaction.
 *   <li><strong>Rejects are never filtered (parity-critical).</strong> A {@code posted=false}
 *       result for every COBOL reason code ({@code 100}, {@code 101}, {@code 102}, {@code 103},
 *       {@code 109}) is returned <em>non-null</em>. Returning {@code null} would cause Spring Batch
 *       to filter the item, silently dropping a reject that {@code CBTRN02C} requires to flow to
 *       the {@code DALYREJS} writer.
 *   <li><strong>Abend propagation.</strong> An unchecked {@link IoStatusException} raised by the
 *       service (the {@code 9910-DISPLAY-IO-STATUS} / {@code 9999-ABEND-PROGRAM} equivalent)
 *       propagates out of {@code process(...)} unchanged so the Spring Batch step fails.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TransactionPostingProcessorTest {

  @Mock private TransactionPostingService transactionPostingService;

  private TransactionPostingProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new TransactionPostingProcessor(transactionPostingService);
  }

  /**
   * A posted result is returned unchanged (same instance, non-null) and the service is invoked
   * exactly once, with no further interaction &mdash; proving the class is a pure delegator.
   */
  @Test
  void process_returnsServicePostedResultUnchanged_andDelegatesExactlyOnce() throws Exception {
    DailyTransaction item = new DailyTransaction();
    PostingResult posted = PostingResult.ofPosted();
    when(transactionPostingService.processOneTransaction(item)).thenReturn(posted);

    PostingResult result = processor.process(item);

    assertThat(result).isNotNull().isSameAs(posted);
    assertThat(result.posted()).isTrue();
    assertThat(result.reason()).isZero();
    assertThat(result.rejectRecord()).isNull();
    verify(transactionPostingService, times(1)).processOneTransaction(item);
    verifyNoMoreInteractions(transactionPostingService);
  }

  /**
   * Every reject reason code yields a non-null result that flows downstream unchanged. A {@code
   * null} return here would be filtered by Spring Batch, dropping the reject &mdash; the exact
   * regression this test guards against.
   *
   * @param reason a COBOL {@code WS-VALIDATION-FAIL-REASON} reject code
   */
  @ParameterizedTest
  @ValueSource(ints = {100, 101, 102, 103, 109})
  void process_returnsRejectResultNonNull_neverFiltered(int reason) throws Exception {
    DailyTransaction item = new DailyTransaction();
    String rejectImage = "DALYREJS-430-CHAR-IMAGE";
    PostingResult rejected = PostingResult.ofRejected(rejectImage, reason);
    when(transactionPostingService.processOneTransaction(item)).thenReturn(rejected);

    PostingResult result = processor.process(item);

    assertThat(result).isNotNull().isSameAs(rejected);
    assertThat(result.posted()).isFalse();
    assertThat(result.reason()).isEqualTo(reason);
    assertThat(result.rejectRecord()).isEqualTo(rejectImage);
    verify(transactionPostingService, times(1)).processOneTransaction(item);
    verifyNoMoreInteractions(transactionPostingService);
  }

  /**
   * An unchecked {@link IoStatusException} thrown by the service propagates out of {@code
   * process(...)} unchanged (it is neither caught nor wrapped), so the Spring Batch step exit
   * status reflects the abend.
   */
  @Test
  void process_propagatesIoStatusExceptionFromService() {
    DailyTransaction item = new DailyTransaction();
    IoStatusException abend = new IoStatusException("DALYTRAN", "READ", "99");
    when(transactionPostingService.processOneTransaction(item)).thenThrow(abend);

    assertThatThrownBy(() -> processor.process(item)).isSameAs(abend);
    verify(transactionPostingService, times(1)).processOneTransaction(item);
    verifyNoMoreInteractions(transactionPostingService);
  }
}

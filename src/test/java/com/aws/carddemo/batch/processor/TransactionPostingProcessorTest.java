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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.service.batch.TransactionPostingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link TransactionPostingProcessor}, the
 * Spring Batch {@code ItemProcessor} that reproduces the per-record validate / post-or-reject
 * decision of legacy COBOL program {@code CBTRN02C} (paragraph {@code 1500-VALIDATE-TRAN};
 * behavioral source {@code legacy/app/cbl/CBTRN02C.cbl}).
 *
 * <p>The processor is a <strong>thin delegator</strong>: its sole collaborator is {@link
 * TransactionPostingService} (mocked here, so there is no Spring context, no Testcontainers and no
 * database) and its only behaviour is to forward each {@link DailyTransaction} to {@link
 * TransactionPostingService#processOneTransaction(DailyTransaction)} and return that result
 * unchanged. The tests therefore pin the delegation contract &mdash; not business logic, which
 * lives in and is tested through {@link TransactionPostingService} &mdash; using four focused
 * invariants:
 *
 * <ul>
 *   <li><strong>Exact delegation</strong> &mdash; {@code process(...)} returns the <em>same
 *       instance</em> the service returns, calls the service <em>exactly once</em> with the same
 *       item, and does nothing else.
 *   <li><strong>Rejects are never filtered (parity-critical)</strong> &mdash; a {@code
 *       posted=false} reject result is returned <em>non-null</em>; returning {@code null} would
 *       make Spring Batch filter (silently drop) the item, losing a reject that {@code CBTRN02C}
 *       requires to flow to the {@code DALYREJS} writer (reasons {@code 100}-{@code 103}).
 *   <li><strong>Abend propagation</strong> (Agent Action Plan &sect;0.6.6) &mdash; an unchecked
 *       {@link IoStatusException} raised by the service propagates out of {@code process(...)}
 *       unchanged so the Spring Batch step exit status reflects the abend.
 *   <li><strong>No online exception in the batch path</strong> &mdash; a record-not-found is a
 *       reject, never a {@code RecordNotFoundException} (an online-only type deliberately absent
 *       from this file).
 * </ul>
 *
 * <p>Each {@link TransactionPostingService.PostingResult} is a real instance obtained from the
 * production factory methods and compared by reference identity ({@code isSameAs}); no record
 * accessor is read, keeping every test focused purely on the processor's delegation rather than on
 * the result's field values.
 */
@ExtendWith(MockitoExtension.class)
class TransactionPostingProcessorTest {

  /** The collaborating service holding all {@code CBTRN02C} per-record business logic. */
  @Mock private TransactionPostingService transactionPostingService;

  /** Opaque pass-through input; never inspected (the processor forwards it untouched). */
  @Mock private DailyTransaction dailyTransaction;

  /** Class under test, with the mocked service supplied through its single constructor. */
  @InjectMocks private TransactionPostingProcessor processor;

  /**
   * Test A &mdash; pure delegation and exact identity. The processor returns the very same {@link
   * TransactionPostingService.PostingResult} instance produced by the service (no copy, no
   * wrapper), invokes the service exactly once with the same item, and performs no other
   * interaction.
   */
  @Test
  void process_delegates_to_service_and_returns_same_posting_result() throws Exception {
    // A "posted" outcome modeled with a real sentinel; assertions compare by reference identity, so
    // the test is decoupled from the record's field values.
    var posted = TransactionPostingService.PostingResult.ofPosted();
    when(transactionPostingService.processOneTransaction(dailyTransaction)).thenReturn(posted);

    var actual = processor.process(dailyTransaction);

    // Exact same instance: the processor neither copies nor wraps the service's result.
    assertThat(actual).isSameAs(posted);
    // Pure delegation: the service is called exactly once with the same item, and nothing else.
    verify(transactionPostingService).processOneTransaction(dailyTransaction);
    verifyNoMoreInteractions(transactionPostingService);
  }

  /**
   * Test B &mdash; rejects are returned, not filtered. Models a {@code CBTRN02C 1500-VALIDATE-TRAN}
   * reject (reason {@code 101} = ACCOUNT RECORD NOT FOUND): a record-not-found is a REJECT, not an
   * exception, and MUST flow downstream to the {@code DALYREJS} writer. A {@code null} return here
   * would make Spring Batch filter (silently drop) the item and lose the reject &mdash; the single
   * most important regression this test guards against.
   */
  @Test
  void process_returns_reject_result_unchanged_and_never_null() throws Exception {
    var reject = TransactionPostingService.PostingResult.ofRejected("reject-image", 101);
    when(transactionPostingService.processOneTransaction(dailyTransaction)).thenReturn(reject);

    // Non-null AND the exact reject instance: the processor passes rejects through unchanged.
    assertThat(processor.process(dailyTransaction)).isNotNull().isSameAs(reject);
  }

  /**
   * Test C &mdash; abend parity (Agent Action Plan &sect;0.6.6). An unrecoverable I/O failure
   * surfaces from the service as the unchecked {@link IoStatusException} (the {@code
   * 9910-DISPLAY-IO-STATUS} &rarr; {@code 9999-ABEND-PROGRAM} path); the processor must let the
   * <em>same</em> instance propagate, unwrapped and untranslated, so the Spring Batch step fails.
   */
  @Test
  void process_propagates_io_status_exception_from_service() {
    var abend = new IoStatusException("DALYTRAN", "READ", "35");
    when(transactionPostingService.processOneTransaction(dailyTransaction)).thenThrow(abend);

    assertThatThrownBy(() -> processor.process(dailyTransaction)).isSameAs(abend);
  }

  /**
   * Test D &mdash; a missing record never surfaces as an exception. {@code RecordNotFoundException}
   * is ONLINE-ONLY and is intentionally never imported, referenced, or thrown in this batch path:
   * in {@code CBTRN02C} an {@code INVALID KEY} (COBOL FILE STATUS {@code '23'}) on the card /
   * account lookup is a REJECT (reason {@code 100} = INVALID CARD NUMBER FOUND), never an
   * exception. The service therefore returns a not-found reject and {@code process(...)} must
   * return it non-null without throwing.
   */
  @Test
  void process_never_throws_record_not_found_for_missing_records() throws Exception {
    var reject = TransactionPostingService.PostingResult.ofRejected("reject-image", 100);
    when(transactionPostingService.processOneTransaction(dailyTransaction)).thenReturn(reject);

    assertThatCode(() -> processor.process(dailyTransaction)).doesNotThrowAnyException();
    assertThat(processor.process(dailyTransaction)).isNotNull().isSameAs(reject);
  }
}

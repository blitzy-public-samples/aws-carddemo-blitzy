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

import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.service.batch.TransactionPostingService;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} reproducing the per-record validate / post-or-reject decision
 * of legacy COBOL program {@code CBTRN02C} (paragraph {@code 1500-VALIDATE-TRAN}; behavioral source
 * {@code legacy/app/cbl/CBTRN02C.cbl}). This class is a <strong>thin delegator</strong> to {@link
 * TransactionPostingService} and deliberately contains <strong>no business logic</strong>: no
 * validation arithmetic, no card-xref / account lookups, no {@code BigDecimal} over-limit math, no
 * expiration-date comparison and no reject-record formatting. All of that resides in the service.
 *
 * <p><strong>Chunk pipeline position.</strong> In the daily transaction-posting chunk step
 * (assembled by {@code TransactionPostingJobConfig} in {@code com.aws.carddemo.batch.config}) this
 * processor sits between the {@code DailyTransactionItemReader} (which supplies one {@link
 * DailyTransaction} per legacy {@code READ DALYTRAN-FILE}) and the reject / transaction writer. It
 * invokes {@link TransactionPostingService#processOneTransaction(DailyTransaction)} exactly once
 * per record, mirroring the {@code CBTRN02C} main loop, which performs {@code 1500-VALIDATE-TRAN}
 * once per read and then branches to {@code 2000-POST-TRANSACTION} (valid) or {@code
 * 2500-WRITE-REJECT-REC} (invalid).
 *
 * <p><strong>Never returns {@code null} (parity-critical).</strong> In Spring Batch a {@code null}
 * returned from {@link #process(DailyTransaction)} <em>filters</em> the item, causing it to vanish
 * silently from the chunk. {@code CBTRN02C} rejects must still flow downstream to the reject
 * writer, so every record yields a <em>non-null</em> {@link
 * TransactionPostingService.PostingResult}. The result object itself carries whether the record was
 * posted or rejected (and, when rejected, the 430-character {@code DALYREJS} reject record plus the
 * numeric reason code); the downstream writer performs the routing.
 *
 * <p><strong>Exception / skip semantics ({@code CBTRN02C} parity).</strong> A record-not-found
 * during validation (COBOL FILE STATUS {@code '23'} / {@code INVALID KEY}) is <em>not</em> an error
 * here: the service returns a normal {@code posted=false} result carrying reason code {@code 100}
 * or {@code 101}, which is routed to the reject writer exactly as the legacy program does (it never
 * abends on {@code '23'}). Genuinely unrecoverable I/O is surfaced by the service as the unchecked
 * {@code IoStatusException} (mirroring {@code 9910-DISPLAY-IO-STATUS} then {@code
 * 9999-ABEND-PROGRAM}); this processor lets it <em>propagate</em> so the Spring Batch step exit
 * status reflects the abend. Because {@link #process(DailyTransaction)} already declares {@code
 * throws Exception} per the {@link ItemProcessor} contract, no special handling is required.
 *
 * <p><strong>Wiring.</strong> Annotated {@link Component} so Spring Boot batch auto-configuration
 * can register it (no {@code @EnableBatchProcessing}); the collaborating service is supplied by
 * constructor injection (no field-level {@code @Autowired}).
 */
@Component
public class TransactionPostingProcessor
    implements ItemProcessor<DailyTransaction, TransactionPostingService.PostingResult> {

  /** Business service holding the {@code CBTRN02C} per-record validate / post-or-reject logic. */
  private final TransactionPostingService transactionPostingService;

  /**
   * Creates the processor with its collaborating posting service.
   *
   * @param transactionPostingService the service that performs the per-record {@code CBTRN02C}
   *     {@code 1500-VALIDATE-TRAN} validation and the {@code 2000-POST-TRANSACTION} / {@code
   *     2500-WRITE-REJECT-REC} decision; must not be {@code null}
   */
  public TransactionPostingProcessor(TransactionPostingService transactionPostingService) {
    this.transactionPostingService = transactionPostingService;
  }

  /**
   * Processes a single daily transaction by delegating to {@link
   * TransactionPostingService#processOneTransaction(DailyTransaction)}, reproducing one iteration
   * of the {@code CBTRN02C} main loop. The returned {@link TransactionPostingService.PostingResult}
   * is always non-null (whether the record was posted or rejected), so the item is never filtered
   * out of the chunk and rejects always reach the downstream reject writer.
   *
   * @param item the daily transaction read by the upstream reader (one per legacy {@code READ
   *     DALYTRAN-FILE}); passed straight through to the service per the reader contract
   * @return the non-null posting outcome &mdash; posted, or rejected with its reason code and the
   *     430-character reject record
   * @throws Exception if the service surfaces an unrecoverable condition (for example the unchecked
   *     {@code IoStatusException} on an abend-equivalent I/O failure); it is allowed to propagate
   *     so that the Spring Batch step fails
   */
  @Override
  public TransactionPostingService.PostingResult process(DailyTransaction item) throws Exception {
    return transactionPostingService.processOneTransaction(item);
  }
}

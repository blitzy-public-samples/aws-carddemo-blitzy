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

import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.repository.DailyTransactionRepository;
import java.util.Map;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link org.springframework.batch.item.ItemReader} that supplies {@link
 * DailyTransaction} items, in ascending primary-key order, to the parity-critical daily transaction
 * posting job ({@code TransactionPostingJobConfig}, package {@code com.aws.carddemo.batch.config}).
 *
 * <p>This reader is the Java realization of the <strong>input side</strong> of the legacy COBOL
 * sequential read loop {@code 1000-DALYTRAN-GET-NEXT} in {@code legacy/app/cbl/CBTRN02C.cbl}. That
 * paragraph issues {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} against the {@code DALYTRAN}
 * physical-sequential dataset ({@code legacy/app/jcl/POSTTRAN.jcl}: {@code DALYTRAN DD DISP=SHR,
 * DSN=AWS.M2.CARDDEMO.DALYTRAN.PS}) and branches on the two-byte {@code DALYTRAN-STATUS} FILE
 * STATUS:
 *
 * <ul>
 *   <li>{@code '00'} (successful read) &rarr; the record is returned for processing &mdash; here,
 *       {@code read()} returns the next {@link DailyTransaction};
 *   <li>{@code '10'} (end-of-file) &rarr; the COBOL sets {@code END-OF-FILE = 'Y'} &mdash; here,
 *       {@code read()} returns {@code null} once the backing data is exhausted, signalling the
 *       Spring Batch chunk step to stop;
 *   <li>any other status &rarr; an unrecoverable I/O error that the legacy program abends on
 *       ({@code 9910-DISPLAY-IO-STATUS} then {@code 9999-ABEND-PROGRAM}) &mdash; here, the
 *       inherited {@link RepositoryItemReader} surfaces the equivalent failure by propagating the
 *       exception, which fails the Spring Batch step exit status.
 * </ul>
 *
 * <p><strong>Read order (parity-critical).</strong> Items are returned <em>ascending by the entity
 * property {@code dalytranId}</em> (the {@code @Id}, COBOL {@code DALYTRAN-ID PIC X(16)}), which
 * reproduces the sequential read order of the legacy {@code DALYTRAN} dataset and exactly matches
 * the cursor that the sibling {@code TransactionPostingService} / {@code
 * DailyTransactionPostService} establish via {@code
 * dailyTransactionRepository.findAll(Sort.by("dalytranId"))}. Golden-file parity tests depend on
 * this ordering. Sort stability is a non-issue because {@code dalytranId} is the unique primary
 * key, so no two rows tie (the legacy {@code SORT} declares no {@code EQUALS} option; Agent Action
 * Plan &sect;0.6.3).
 *
 * <p><strong>Decimal fidelity.</strong> The monetary amount {@code dalytranAmt} is carried on the
 * entity as {@link java.math.BigDecimal}; this reader passes whole {@link DailyTransaction} objects
 * through untouched and performs no arithmetic, so binary floating-point ({@code float} / {@code
 * double}) never appears (Agent Action Plan &sect;0.6.1).
 *
 * <p><strong>Concurrency &amp; restartability.</strong> The bean is intended for single-threaded,
 * chunk-oriented batch use. It is restartable: {@code saveState} is enabled and the sort key is the
 * stable, unique primary key, so a restart resumes deterministically from the saved read count held
 * in the {@code ExecutionContext} under the namespaced reader name.
 *
 * <p><strong>Scope.</strong> This class supplies items only. Per-transaction validation, posting,
 * and reject handling (COBOL {@code 1500-VALIDATE-TRAN}, {@code 2000-POST-TRANSACTION}, {@code
 * 2500-WRITE-REJECT-REC}, {@code 2700-UPDATE-TCATBAL}, {@code 2800-UPDATE-ACCOUNT-REC}, {@code
 * 2900-WRITE-TRANSACTION-FILE}) live in {@code TransactionPostingService} / the chunk {@code
 * ItemProcessor} and {@code ItemWriter}, and the {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop
 * itself is driven by the Spring Batch chunk step declared in {@code
 * com.aws.carddemo.batch.config}.
 *
 * <p>Because it extends {@link RepositoryItemReader}, an instance <em>is-a</em> {@code
 * ItemReader<DailyTransaction>} (and {@code ItemStreamReader<DailyTransaction>}), so the consuming
 * job configuration may inject it either by the concrete type {@code DailyTransactionItemReader} or
 * by the interface {@code ItemReader<DailyTransaction>}; both resolve to the singleton bean named
 * {@code dailyTransactionItemReader}.
 *
 * <p><strong>Why {@code final}.</strong> The project builds under {@code -Werror
 * -Xlint:all,-processing} (the zero-warning quality gate). Java's {@code this-escape} lint flags
 * any constructor that invokes an overridable instance method, because a subclass override could
 * observe a partially-initialized instance. This reader configures its inherited {@link
 * RepositoryItemReader} state from the constructor (the idiomatic way to wire a {@code
 * RepositoryItemReader}); declaring the class {@code final} removes the possibility of a subclass
 * and therefore the warning, with no {@code @SuppressWarnings} required. The class is not designed
 * for extension, so {@code final} costs nothing and still permits injection by concrete type or by
 * interface.
 *
 * <p><strong>COBOL &rarr; Java traceability</strong> (cited by {@code
 * docs/traceability-matrix.md}):
 *
 * <table border="1">
 *   <caption>{@code CBTRN02C} DALYTRAN read paragraphs &rarr; reader lifecycle</caption>
 *   <tr>
 *     <th>COBOL ({@code CBTRN02C})</th>
 *     <th>Java</th>
 *   </tr>
 *   <tr>
 *     <td>{@code 1000-DALYTRAN-GET-NEXT} ({@code READ DALYTRAN-FILE}; {@code '00'} &rarr; record,
 *         {@code '10'} &rarr; {@code END-OF-FILE = 'Y'})</td>
 *     <td>{@code DailyTransactionItemReader.read()} &mdash; returns the next {@link DailyTransaction},
 *         or {@code null} at exhaustion</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 0000-DALYTRAN-OPEN} (open INPUT)</td>
 *     <td>{@code open(ExecutionContext)} (inherited from {@link RepositoryItemReader})</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 9000-DALYTRAN-CLOSE} (close)</td>
 *     <td>{@code close()} (inherited from {@link RepositoryItemReader})</td>
 *   </tr>
 * </table>
 *
 * @see RepositoryItemReader
 * @see DailyTransaction
 * @see DailyTransactionRepository
 */
@Component
public final class DailyTransactionItemReader extends RepositoryItemReader<DailyTransaction> {

  /**
   * Page size used by the inherited {@link RepositoryItemReader} when it invokes {@code
   * findAll(Pageable)}.
   *
   * <p>This is purely a performance / throughput knob: because the sort key {@code dalytranId} is
   * the unique primary key, the page size never changes the produced ordering or the golden-file
   * parity outcome. Any positive value is acceptable; {@code 100} is a sensible default.
   */
  private static final int PAGE_SIZE = 100;

  /**
   * Configures the inherited {@link RepositoryItemReader} to page through every {@link
   * DailyTransaction} ascending by {@code dalytranId}.
   *
   * <p>The reader invokes {@link DailyTransactionRepository}'s inherited {@code
   * JpaRepository.findAll(Pageable)} (no custom finder is added or required); the {@link Sort}
   * supplied here drives the {@code ORDER BY dalytran_id ASC} that preserves sequential-read parity
   * with the legacy {@code DALYTRAN} dataset. The exact JPA property name {@code dalytranId} is
   * used (not the {@code dalytran_id} column name and not the COBOL {@code DALYTRAN-ID}). Spring
   * invokes {@code afterPropertiesSet()} automatically (the reader implements {@link
   * org.springframework.beans.factory.InitializingBean}) to validate that the repository, method
   * name, sort, and page size are all set &mdash; which they are, here.
   *
   * @param dailyTransactionRepository the backing {@link
   *     org.springframework.data.repository.PagingAndSortingRepository} for the {@code
   *     daily_transaction} table (a {@code JpaRepository} extends it); must not be {@code null}
   */
  public DailyTransactionItemReader(DailyTransactionRepository dailyTransactionRepository) {
    setRepository(dailyTransactionRepository);
    setMethodName("findAll");
    setSort(Map.of("dalytranId", Sort.Direction.ASC));
    setPageSize(PAGE_SIZE);
    setName("dailyTransactionItemReader");
    setSaveState(true);
  }
}

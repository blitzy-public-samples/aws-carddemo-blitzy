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
package com.aws.carddemo.batch.writer;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemWriter} that persists each {@link Transaction} of a chunk into the
 * relational transaction master (PostgreSQL table {@code transaction}). It is the Java re-platform
 * of the <em>REPRO load</em> performed by the legacy mainframe job {@code legacy/jcl/COMBTRAN.jcl}
 * (source {@code app/jcl/COMBTRAN.jcl}).
 *
 * <h2>COBOL / JCL lineage</h2>
 * <p>{@code COMBTRAN.jcl} is a pure <em>combine &rarr; sort &rarr; load</em> job that carries no
 * business logic:</p>
 * <ul>
 *   <li><strong>{@code STEP05R} ({@code PGM=SORT}).</strong> Concatenates the current transaction
 *       backup generation {@code TRANSACT.BKUP(0)} with the system-generated transactions
 *       {@code SYSTRAN(0)} (350-byte {@code TRAN-RECORD} images, copybook
 *       {@code legacy/cpy/CVTRA05Y.cpy}) and sorts them by transaction id ascending
 *       ({@code SYMNAMES TRAN-ID,1,16,CH}; {@code SORT FIELDS=(TRAN-ID,A)}) into the new generation
 *       {@code TRANSACT.COMBINED(+1)}.</li>
 *   <li><strong>{@code STEP10} ({@code PGM=IDCAMS}).</strong> Loads the sorted combined file into
 *       the key-sequenced transaction master with {@code REPRO INFILE(TRANSACT)
 *       OUTFILE(TRANVSAM)}, where {@code TRANVSAM} is {@code TRANSACT.VSAM.KSDS}.</li>
 * </ul>
 *
 * <p>In the relational target the master KSDS becomes the {@code transaction} table. The
 * chunk-oriented step {@code transactionCombineStep} of {@code batch/TransactionCombineJob} wires an
 * upstream reader ({@code batch/reader/CombinedTransactionItemReader}) that already yields the
 * combined stream in {@code tranId}-ascending order together with <em>this</em> writer as the
 * persistence sink. Consequently this writer performs <strong>no sorting</strong> (ordering is the
 * reader's responsibility, mirroring {@code STEP05R}) and <strong>no business mutation</strong>
 * &mdash; its sole responsibility is to load each {@link Transaction} into the master, exactly as
 * {@code STEP10}'s {@code REPRO} does.</p>
 *
 * <h2>REPRO / insert semantics</h2>
 * <p>{@link Transaction} carries an <em>assigned</em> natural primary key ({@code tranId}, a
 * 16-character {@code String}) and implements {@link org.springframework.data.domain.Persistable
 * Persistable&lt;String&gt;}. The records this writer receives are freshly decoded from the two
 * external sequential inputs by {@code batch/reader/CombinedTransactionItemReader} (they are
 * constructed, never JPA-loaded), so each reports {@code isNew() == true}. Spring Data's
 * {@code SimpleJpaRepository.saveAll(...)} therefore issues {@code EntityManager.persist(...)}
 * &mdash; an <strong>insert</strong> keyed on {@code tran_id} &mdash; rather than
 * {@code merge(...)}. That is precisely the semantics of {@code STEP10}'s IDCAMS load, whose
 * control statement is {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} with <em>no</em>
 * {@code REPLACE} option [legacy/jcl/COMBTRAN.jcl]: IDCAMS inserts every combined record into the
 * (freshly (re)defined) master KSDS and <em>rejects</em> &mdash; never silently overwrites &mdash;
 * any record whose key already exists. Under {@code persist} a colliding key surfaces as a
 * {@link org.springframework.dao.DataIntegrityViolationException} on {@code pk_transaction} that
 * fails the step, mirroring the non-zero condition code a duplicate-key {@code REPRO} would raise.
 * {@code saveAll} over a {@link org.springframework.data.domain.Persistable} entity is therefore
 * the correct REPRO analog, and this writer intentionally relies on it.</p>
 *
 * <h2>Transaction boundary and state</h2>
 * <p>The chunk commit interval and the surrounding {@code @Transactional} boundary are owned by the
 * Spring Batch {@code Step} declared in {@code batch/TransactionCombineJob}; this writer must not
 * &mdash; and does not &mdash; open its own transaction or annotate {@link #write(Chunk)} with
 * {@code @Transactional}. The class holds no mutable state, needs no {@code StepExecutionListener},
 * and is safe as a stateless singleton {@link Component}.</p>
 *
 * <h2>Registration and security</h2>
 * <p>The bean's default name is {@code transactionJpaItemWriter} (the decapitalized class name); it
 * is injected under exactly that name by {@code batch/TransactionCombineJob.transactionCombineStep}.
 * The writer logs only the non-sensitive per-chunk record count at {@code DEBUG}; it never logs a
 * transaction amount, a full card number, a CVV, an SSN, a password, or any other sensitive
 * field.</p>
 *
 * @see Transaction
 * @see TransactionRepository
 * @see ItemWriter
 * @see Chunk
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Component
public class TransactionJpaItemWriter implements ItemWriter<Transaction> {

    /** SLF4J logger; emits only non-sensitive operational metadata (per-chunk record counts). */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(TransactionJpaItemWriter.class);

    /** Spring Data JPA repository for the {@code transaction} master; the REPRO-load target. */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the writer with the transaction repository it loads into.
     *
     * <p>Constructor injection only: the repository is stored in a {@code private final} field and
     * there is no field-level {@code @Autowired}.</p>
     *
     * @param transactionRepository the Spring Data JPA repository for the {@code transaction}
     *                              master; never {@code null}
     */
    public TransactionJpaItemWriter(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Persists every {@link Transaction} in the supplied chunk into the {@code transaction} master
     * table, reproducing the {@code STEP10} {@code REPRO} load of {@code legacy/jcl/COMBTRAN.jcl}.
     *
     * <p>The items are handed to {@link TransactionRepository#saveAll(Iterable)} in the exact order
     * the reader supplied them ({@code tranId} ascending); this method neither reorders nor mutates
     * them. Because {@link Transaction} implements {@link org.springframework.data.domain.Persistable
     * Persistable} and the reader-built items report {@code isNew() == true}, {@code saveAll}
     * performs an insert per row &mdash; the relational equivalent of loading a record into the
     * master KSDS via a no-{@code REPLACE} {@code REPRO} (see the class Javadoc). A duplicate key
     * therefore fails the step rather than being silently overwritten.</p>
     *
     * <p>This method opens no transaction of its own; the chunk is committed within the
     * {@code Step}-owned transaction of {@code batch/TransactionCombineJob}. Any persistence failure
     * propagates out unchanged (the method is declared {@code throws Exception}) so the batch reports
     * the corresponding non-zero return code instead of silently dropping records.</p>
     *
     * @param chunk the chunk of transactions to persist, supplied by the upstream reader in
     *              {@code tranId}-ascending order; never {@code null}
     * @throws Exception if the underlying repository/persistence operation fails, failing the step
     */
    @Override
    public void write(Chunk<? extends Transaction> chunk) throws Exception {
        LOGGER.debug(
                "REPRO-load: inserting {} transaction(s) into the transaction master (keyed on tran_id).",
                chunk.size());
        transactionRepository.saveAll(chunk.getItems());
    }
}

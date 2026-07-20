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

import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.repository.DailyTransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemWriter} that persists each decoded {@link DailyTransaction} of a chunk
 * into the {@code daily_transaction} staging table. It is the persistence sink of the raw
 * external-file load performed by {@code batch/DailyTransactionLoadJob}, whose upstream
 * {@code batch/reader/DailyTransactionFileItemReader} decodes the 350-byte fixed-width
 * {@code DALYTRAN} records (copybook {@code legacy/cpy/CVTRA06Y.cpy}).
 *
 * <h2>Legacy lineage</h2>
 * In the mainframe pipeline the raw {@code DALYTRAN} dataset is prepared (loaded/positioned) before
 * the daily-transaction programs {@code legacy/cbl/CBTRN01C.cbl} (validate) and
 * {@code legacy/cbl/CBTRN02C.cbl} (posting) read it sequentially. In the relational target that raw
 * file is materialized into the {@code daily_transaction} staging table; this writer performs that
 * materialization, after which the validate and posting jobs read the table set-based via
 * {@code batch/reader/DailyTransactionItemReader}.
 *
 * <h2>Insert semantics</h2>
 * {@link DailyTransaction} uses a database-generated surrogate identity primary key
 * ({@code daily_transaction.id}); the reader-built items are transient (never JPA-loaded), so each
 * has a {@code null} id and {@link DailyTransactionRepository#saveAll(Iterable)} issues an
 * {@code INSERT} per row. Staging rows may re-use the same business id ({@code DALYTRAN-ID}) across
 * loads, which is precisely why the primary key is the surrogate rather than the business id.
 *
 * <h2>Transaction boundary, state and security</h2>
 * The chunk commit interval and the surrounding transaction are owned by the Spring Batch
 * {@code Step} declared in {@code batch/DailyTransactionLoadJob}; this writer opens no transaction of
 * its own and holds no mutable state, so it is safe as a stateless singleton {@link Component} (bean
 * name {@code dailyTransactionStagingWriter}). It logs only the non-sensitive per-chunk row count at
 * {@code DEBUG}; it never logs a transaction amount, a full card number, a CVV, an SSN, or any other
 * sensitive field.
 *
 * @see DailyTransaction
 * @see DailyTransactionRepository
 * @see ItemWriter
 * @see Chunk
 */
@Component
public class DailyTransactionStagingWriter implements ItemWriter<DailyTransaction> {

    /** SLF4J logger; emits only non-sensitive operational metadata (per-chunk row counts). */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(DailyTransactionStagingWriter.class);

    /** Spring Data JPA repository for the {@code daily_transaction} staging table; the load target. */
    private final DailyTransactionRepository dailyTransactionRepository;

    /**
     * Creates the writer with the staging repository it loads into.
     *
     * <p>Constructor injection only: the repository is stored in a {@code private final} field and
     * there is no field-level {@code @Autowired}.</p>
     *
     * @param dailyTransactionRepository the Spring Data JPA repository for the
     *                                   {@code daily_transaction} staging table; never {@code null}
     */
    public DailyTransactionStagingWriter(DailyTransactionRepository dailyTransactionRepository) {
        this.dailyTransactionRepository = dailyTransactionRepository;
    }

    /**
     * Persists every {@link DailyTransaction} in the supplied chunk into the {@code daily_transaction}
     * staging table in the exact order the reader supplied them (file order). Because the items are
     * transient, {@link DailyTransactionRepository#saveAll(Iterable)} performs an insert per row.
     *
     * <p>This method opens no transaction of its own; the chunk is committed within the
     * {@code Step}-owned transaction of {@code batch/DailyTransactionLoadJob}. Any persistence
     * failure propagates out unchanged (the method is declared {@code throws Exception}) so the batch
     * reports the corresponding non-zero return code instead of silently dropping records.</p>
     *
     * @param chunk the chunk of decoded staging records to persist, supplied by the upstream reader
     *              in file order; never {@code null}
     * @throws Exception if the underlying repository/persistence operation fails, failing the step
     */
    @Override
    public void write(Chunk<? extends DailyTransaction> chunk) throws Exception {
        LOGGER.debug("Loading {} daily-transaction record(s) into the daily_transaction staging table.",
                chunk.size());
        dailyTransactionRepository.saveAll(chunk.getItems());
    }
}

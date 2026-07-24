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
package com.carddemo.transaction.repository;

import com.carddemo.common.domain.DailyTransaction;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * :purpose: Data-access repository for the daily-transaction feed
 *     (``DALYTRAN``); the in-memory source of the batch posting ``ItemReader``
 *     that the ``CBTRN02C`` transaction-posting job consumes. The feed is a
 *     sequential batch input keyed by ``DALYTRAN-ID`` and is never persisted as
 *     a relational table, so access is provided over a transient, thread-safe
 *     store of {@link DailyTransaction} records rather than through Spring Data
 *     JPA.
 * :output: {@link DailyTransaction} feed records keyed by their sixteen-character
 *     ``dalytranId``; {@link #findAll()} returns them in ascending ``dalytranId``
 *     order, reproducing the legacy sequential browse order of the posting job.
 */
@Repository
public class DailyTransactionRepository {

    /**
     * Thread-safe backing store of feed records keyed by ``dalytranId``.
     *
     * A {@link ConcurrentHashMap} allows the posting step to stage and query
     * the feed concurrently without external synchronization.
     */
    private final Map<String, DailyTransaction> feed = new ConcurrentHashMap<>();

    /**
     * Stage (insert or replace) a single feed record.
     *
     * :purpose: Registers one daily-transaction record under its
     *     ``dalytranId`` key, replacing any record already held under that key.
     * :param dailyTransaction: the feed record to stage; must be non-null and
     *     must carry a non-null ``dalytranId``.
     * :return: the same {@link DailyTransaction} instance that was stored.
     */
    public DailyTransaction save(DailyTransaction dailyTransaction) {
        Objects.requireNonNull(dailyTransaction, "dailyTransaction must not be null");
        String id = Objects.requireNonNull(dailyTransaction.getDalytranId(),
                "dailyTransaction.dalytranId must not be null");
        feed.put(id, dailyTransaction);
        return dailyTransaction;
    }

    /**
     * Stage every record supplied by the given iterable.
     *
     * :purpose: Bulk-registers a batch of daily-transaction records, applying
     *     the same key semantics as {@link #save(DailyTransaction)} to each.
     * :param dailyTransactions: the feed records to stage; must be non-null and
     *     must not contain null elements or elements with a null ``dalytranId``.
     * :return: an unmodifiable-order {@link List} of the stored records in
     *     iteration order.
     */
    public List<DailyTransaction> saveAll(Iterable<DailyTransaction> dailyTransactions) {
        Objects.requireNonNull(dailyTransactions, "dailyTransactions must not be null");
        List<DailyTransaction> saved = new ArrayList<>();
        for (DailyTransaction dailyTransaction : dailyTransactions) {
            saved.add(save(dailyTransaction));
        }
        return saved;
    }

    /**
     * Look up a single staged feed record by its identifier.
     *
     * :purpose: Provides keyed single-record access to the feed, mirroring the
     *     legacy keyed read of a daily-transaction record by ``DALYTRAN-ID``.
     * :param dalytranId: the sixteen-character record identifier; a null id
     *     never matches a stored record.
     * :return: an {@link Optional} holding the matching record, or an empty
     *     {@link Optional} when the id is null or no record is staged for it.
     */
    public Optional<DailyTransaction> findById(String dalytranId) {
        if (dalytranId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(feed.get(dalytranId));
    }

    /**
     * Report whether a record is staged under the given identifier.
     *
     * :purpose: Tests membership of a ``dalytranId`` in the staged feed without
     *     materializing the record.
     * :param dalytranId: the record identifier to test; a null id is never
     *     present.
     * :return: ``true`` when a record is staged under the id; ``false`` when the
     *     id is null or absent.
     */
    public boolean existsById(String dalytranId) {
        return dalytranId != null && feed.containsKey(dalytranId);
    }

    /**
     * Return a snapshot of every staged feed record in feed order.
     *
     * :purpose: Supplies the ordered record set that the posting ``ItemReader``
     *     iterates, reproducing the ``CBTRN02C`` sequential browse in ascending
     *     ``dalytranId`` order.
     * :return: a new, caller-owned {@link List} of all staged records sorted by
     *     ``dalytranId`` ascending; an empty list when nothing is staged.
     */
    public List<DailyTransaction> findAll() {
        List<DailyTransaction> snapshot = new ArrayList<>(feed.values());
        snapshot.sort(Comparator.comparing(DailyTransaction::getDalytranId));
        return snapshot;
    }

    /**
     * Count the staged feed records.
     *
     * :purpose: Reports the number of records currently held, used by the
     *     posting job to size its read tally.
     * :return: the number of staged {@link DailyTransaction} records.
     */
    public long count() {
        return feed.size();
    }

    /**
     * Discard every staged feed record.
     *
     * :purpose: Clears the transient store between posting runs so a fresh feed
     *     can be staged without carrying rows over from a prior run.
     * :return: nothing.
     */
    public void deleteAll() {
        feed.clear();
    }
}

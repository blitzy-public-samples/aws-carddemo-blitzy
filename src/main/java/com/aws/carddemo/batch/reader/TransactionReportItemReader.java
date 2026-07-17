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
package com.aws.carddemo.batch.reader;

import java.util.HashMap;
import java.util.Map;

import com.aws.carddemo.domain.Transaction;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link org.springframework.batch.item.ItemReader ItemReader} that streams
 * {@link Transaction} rows for the transaction-detail report, filtered by an inclusive
 * <em>processing-date</em> range and ordered so that a downstream card control break is
 * well-formed. It is the set-based replacement for the sequential file read plus in-line date
 * filter performed by the legacy batch program {@code legacy/cbl/CBTRN03C.cbl}
 * (source {@code app/cbl/CBTRN03C.cbl}); each streamed item carries the layout of the copybook
 * {@code CVTRA05Y.cpy} ({@code TRAN-RECORD}, fixed record length 350), projected here as the
 * {@link Transaction} JPA entity.
 *
 * <h2>Legacy lineage (CBTRN03C)</h2>
 * <ul>
 *   <li><strong>Sequential scan.</strong> The legacy program declares
 *       {@code SELECT TRANSACT-FILE ... ORGANIZATION IS SEQUENTIAL} (CBTRN03C L30) and walks the
 *       transaction file front-to-back. Here that becomes a single paged JPQL query.</li>
 *   <li><strong>Date-range filter (CBTRN03C L173-174).</strong> The only row-level filter in the
 *       legacy read loop is
 *       {@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <= WS-END-DATE};
 *       records outside the range are skipped ({@code NEXT SENTENCE}). {@code WS-START-DATE} and
 *       {@code WS-END-DATE} are {@code PIC X(10)} values read from the {@code DATEPARM} file
 *       (CBTRN03C L123-125), i.e. a textual {@code YYYY-MM-DD} comparison on the first ten
 *       characters (the date portion) of the processing timestamp {@code TRAN-PROC-TS}. This reader
 *       reproduces that filter exactly with {@code SUBSTRING(t.procTs, 1, 10) BETWEEN :startDate
 *       AND :endDate} (see <em>Query semantics</em> below).</li>
 *   <li><strong>Card control break (CBTRN03C L181).</strong> The report totals break on a change of
 *       card number ({@code IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM}); the report is therefore
 *       <em>grouped by card</em>. Producing the totals themselves is <strong>not</strong> this
 *       reader's job (see <em>Scope</em>); the reader only guarantees the input ordering that makes
 *       the break well-formed.</li>
 * </ul>
 *
 * <h2>Ordering &mdash; documented AAP deviation</h2>
 * The legacy KSDS is read in {@code TRAN-ID} (physical) order and the report control-breaks on card
 * as cards happen to appear. Technical Specification &sect;0.5.4 mandates that this set-based reader
 * order <strong>by card number, then transaction id</strong> ({@code ORDER BY t.cardNum ASC,
 * t.tranId ASC}) so the card control break is well-formed rather than dependent on physical file
 * order. The secondary {@code tranId} key preserves the legacy within-file {@code TRAN-ID} ordering
 * inside each card group. This is an <strong>intentional, documented deviation</strong>: it changes
 * the <em>read order</em> only, never the <em>content</em> &mdash; the same set of rows is returned,
 * grouped identically &mdash; and is recorded as such in {@code docs/decision-log.md}.
 *
 * <h2>Query semantics</h2>
 * <ul>
 *   <li>{@code SUBSTRING(t.procTs, 1, 10)} &mdash; JPQL {@code SUBSTRING(string, start, length)} is
 *       1-indexed, so {@code (1, 10)} yields the leading {@code YYYY-MM-DD} date portion, the exact
 *       analog of the COBOL reference modification {@code TRAN-PROC-TS (1:10)}.</li>
 *   <li>{@code BETWEEN :startDate AND :endDate} &mdash; inclusive on both bounds, matching the
 *       COBOL {@code >= WS-START-DATE AND <= WS-END-DATE}.</li>
 *   <li><strong>Textual comparison.</strong> Both bound parameters and the substring operand are
 *       {@code YYYY-MM-DD} strings, so the comparison is lexicographic &mdash; the same alphanumeric
 *       comparison the COBOL performs on {@code PIC X(10)} fields. For zero-padded ISO-8601 dates
 *       lexicographic order is identical to chronological order, so no date parsing is required or
 *       performed.</li>
 *   <li><strong>Processing timestamp, not origination.</strong> The filter is applied to
 *       {@code procTs} ({@code TRAN-PROC-TS}), never {@code origTs} ({@code TRAN-ORIG-TS}), exactly
 *       as CBTRN03C does.</li>
 * </ul>
 *
 * <p><strong>Assumption.</strong> The {@code YYYY-MM-DD} substring logic assumes {@code procTs}
 * begins with the ISO date, which holds for the CardDemo timestamp contract
 * ({@code PIC X(26)}, format {@code YYYY-MM-DD-HH.MM.SS.ffffff}) preserved by
 * {@link Transaction#getProcTs()}.</p>
 *
 * <h2>Scope (what this reader deliberately does not do)</h2>
 * Aggregation and reference lookups from CBTRN03C &mdash; page totals, account totals, the grand
 * total, and the cross-reference / transaction-type / transaction-category lookups (paragraphs
 * {@code 1100}, {@code 1110}, {@code 1120}, {@code 1500-A}, {@code 1500-B}, {@code 1500-C}) &mdash;
 * are the responsibility of the report processor and writer in the parent {@code batch/} job, not
 * of this reader. The reader emits raw, ordered, in-range {@link Transaction} rows only.
 *
 * <h2>Job parameters (late binding) and scope</h2>
 * The {@code startDate} and {@code endDate} bounds (the {@code DATEPARM} analog, format
 * {@code YYYY-MM-DD}) are supplied as <em>job parameters</em> and injected with SpEL late binding
 * via {@link Value}. Because {@code #{jobParameters[...]}} resolves only within a running step, this
 * bean is {@link StepScope step-scoped}: a singleton could not late-bind the parameters, and a fresh
 * reader instance per step execution is also the correct pattern for the stateful, restartable
 * paging that {@link JpaPagingItemReader} implements. The bean is registered as a Spring
 * {@link Component}; its default bean name is the decapitalized class name,
 * {@code transactionReportItemReader}, which is also set as the reader name (the
 * {@code ExecutionContext} key prefix used for save-state) via {@link #setName(String)}.
 *
 * <h2>Why not a repository method</h2>
 * The {@code TransactionRepository} exposes no processing-date-range query
 * ({@code findByCardNumOrderByOrigTsAsc}, {@code findTopByOrderByTranIdDesc},
 * {@code findMaxTranId}); rather than inventing one, this reader is self-contained and drives an
 * explicit JPQL string over the shared {@link EntityManagerFactory}.
 *
 * @see Transaction
 * @see JpaPagingItemReader
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Component
@StepScope
public class TransactionReportItemReader extends JpaPagingItemReader<Transaction> {

    /**
     * Reader name and Spring bean name. It is intentionally identical to the decapitalized class
     * name so that the parent {@code TransactionReportJob} (CBTRN03C) can wire this reader by its
     * conventional bean name. It is also used by {@link JpaPagingItemReader} as the
     * {@code ExecutionContext} key prefix under which paging save-state is persisted, so it must be
     * stable across restarts.
     */
    private static final String READER_NAME = "transactionReportItemReader";

    /**
     * JPA paging page size. The reader fetches the filtered, ordered result set in pages of this
     * many rows, keeping memory bounded while streaming an arbitrarily large report input.
     */
    private static final int PAGE_SIZE = 100;

    /**
     * JPQL that reproduces the CBTRN03C L173-174 processing-date filter and applies the
     * AAP-mandated {@code (cardNum, tranId)} ordering. The named parameters {@code startDate} and
     * {@code endDate} are bound from the step's job parameters. See the class Javadoc
     * (<em>Query semantics</em>) for the 1-indexed {@code SUBSTRING}, inclusive {@code BETWEEN},
     * and textual-comparison rationale.
     */
    private static final String REPORT_QUERY =
            "SELECT t FROM Transaction t "
            + "WHERE SUBSTRING(t.procTs, 1, 10) BETWEEN :startDate AND :endDate "
            + "ORDER BY t.cardNum ASC, t.tranId ASC";

    /**
     * Constructs and fully configures the step-scoped reader.
     *
     * <p>The {@code startDate} and {@code endDate} arguments are late-bound job parameters (the
     * {@code DATEPARM} analog), each an inclusive {@code YYYY-MM-DD} bound. They are passed as
     * named JPQL parameters; no parsing or normalization is applied, preserving the legacy textual
     * comparison semantics.</p>
     *
     * <p><strong>On {@code @SuppressWarnings("this-escape")}.</strong> The constructor invokes the
     * inherited configuration setters of {@link JpaPagingItemReader} (and its superclasses) to
     * establish the reader's fixed configuration up front. Under Java's {@code -Xlint:all} those
     * calls raise the {@code this-escape} lint because the setters are overridable and this class is
     * not {@code final}. The class cannot be made {@code final}: {@link StepScope} uses a
     * {@code TARGET_CLASS} (CGLIB) scoped proxy, which must subclass this type at runtime. The
     * escape is nonetheless safe &mdash; the invoked methods are the framework's own configuration
     * setters, this class declares no subclass that overrides them, and the CGLIB proxy only
     * delegates rather than overriding them &mdash; so the lint is suppressed locally and explained
     * here rather than in a code comment (per the project's explainability rule).</p>
     *
     * @param entityManagerFactory the shared JPA {@link EntityManagerFactory} used to run the paged
     *                             query; never {@code null}
     * @param startDate            inclusive lower bound, format {@code YYYY-MM-DD}, late-bound from
     *                             the {@code startDate} job parameter
     * @param endDate              inclusive upper bound, format {@code YYYY-MM-DD}, late-bound from
     *                             the {@code endDate} job parameter
     */
    @SuppressWarnings("this-escape")
    public TransactionReportItemReader(
            EntityManagerFactory entityManagerFactory,
            @Value("#{jobParameters['startDate']}") String startDate,
            @Value("#{jobParameters['endDate']}") String endDate) {

        setEntityManagerFactory(entityManagerFactory);
        setQueryString(REPORT_QUERY);

        final Map<String, Object> parameterValues = new HashMap<>();
        parameterValues.put("startDate", startDate);
        parameterValues.put("endDate", endDate);
        setParameterValues(parameterValues);

        setPageSize(PAGE_SIZE);
        setName(READER_NAME);
        setSaveState(true);
    }
}

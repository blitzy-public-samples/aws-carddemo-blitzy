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

import com.aws.carddemo.domain.DailyTransaction;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Spring Batch {@link FlatFileItemReader} that ingests the raw external, fixed-width
 * {@code DALYTRAN} sequential file (350-byte {@code DALYTRAN-RECORD}, copybook
 * {@code legacy/cpy/CVTRA06Y.cpy}) into {@link DailyTransaction} staging items. It is the executable
 * realization of the external-file ingestion path described by {@link DailyTransactionItemReader}
 * and preserves the fixed-width record contract of AAP &sect;0.7.2 hotspot M2.
 *
 * <h2>Role in the daily-transaction pipeline</h2>
 * This reader is the <em>front door</em> of the daily-transaction flow: it decodes the raw external
 * file and, paired with {@code batch/writer/DailyTransactionStagingWriter}, loads the
 * {@code daily_transaction} staging table. The downstream validate ({@code CBTRN01C}) and posting
 * ({@code CBTRN02C}) jobs then read that staging table set-based via the DB-backed
 * {@link DailyTransactionItemReader}. The two readers are therefore complementary, not alternatives:
 * <ul>
 *   <li><strong>this class</strong> &mdash; a streaming {@code FlatFileItemReader} that frames the raw
 *       external file into fixed-length 350-byte records and decodes each; and</li>
 *   <li>{@link DailyTransactionItemReader} &mdash; a paged {@code RepositoryItemReader} over the
 *       populated {@code daily_transaction} table, reproducing the COBOL sequential read order.</li>
 * </ul>
 *
 * <h2>Decode and encoding</h2>
 * Each line is decoded by the injected, stateless {@link DailyTransactionLineMapper}, which slices
 * the record with {@link com.aws.carddemo.common.util.FixedWidthCodec} per the CVTRA06Y offset
 * table. The stream is read with {@link StandardCharsets#ISO_8859_1}: that single-byte charset is
 * mandatory so the zoned-decimal overpunch byte of {@code DALYTRAN-AMT} survives intact as its
 * canonical character (for example a trailing {@code G} = positive last digit 7), which the mapper
 * then decodes to a scale-2 {@link java.math.BigDecimal} via
 * {@link com.aws.carddemo.common.util.FixedWidthCodec#readSignedDecimal(String, int, int, int)}.
 * Records are framed by <em>position</em>, not by newline: the injected
 * {@link FixedLengthBufferedReaderFactory} returns exactly one 350-character record per
 * {@code readLine()} regardless of whether the input is newline-framed (the shipped LF-terminated
 * ASCII fixtures) or a true contiguous {@code RECFM=FB} fixed-block file with no in-band delimiter.
 * This preserves the {@code DALYTRAN} fixed-width contract exactly &mdash; a delimiter-free file no
 * longer collapses into a single over-length line that would drop every record after the first
 * &mdash; and a non-blank short trailing remainder fails the step deterministically.
 *
 * <h2>Late-bound input location and scope</h2>
 * The input file location is supplied entirely by the {@code inputResource} job parameter, resolved
 * with SpEL ({@code #{jobParameters['inputResource']}}); Spring converts that string location into a
 * {@link Resource} before injection, so <strong>no file path or credential is hard-coded</strong>
 * (AAP &sect;0.8.1, "no hardcoded credentials/paths"). Because the location is late-bound per launch,
 * the bean is {@link StepScope step-scoped}: a plain singleton could not bind a job parameter. The
 * reader is {@code strict}, so a missing input file fails the step deterministically rather than
 * silently loading nothing. Its bean name is the decapitalized class name
 * {@code dailyTransactionFileItemReader}, wired by {@code batch/DailyTransactionLoadJob}.
 *
 * <h2>On {@code @SuppressWarnings("this-escape")}</h2>
 * The constructor establishes the reader's fixed configuration by invoking the inherited
 * configuration setters of {@link FlatFileItemReader}. Under the project's warning-free build
 * ({@code -Xlint:all} with {@code failOnWarning=true}) those calls raise the {@code this-escape}
 * lint because the setters are overridable and this class is not {@code final}. The class is left
 * non-{@code final} so the {@code @StepScope} CGLIB proxy (which must subclass this type) remains
 * valid; the escape is nonetheless safe &mdash; the invoked methods are the framework's own
 * configuration setters and this class declares no overriding subclass &mdash; so the lint is
 * suppressed locally on the constructor, consistent with {@link DailyTransactionItemReader}.
 *
 * @see DailyTransactionLineMapper
 * @see DailyTransactionItemReader
 * @see DailyTransaction
 * @see com.aws.carddemo.common.util.FixedWidthCodec
 */
@Component
@StepScope
public class DailyTransactionFileItemReader extends FlatFileItemReader<DailyTransaction> {

    /**
     * Reader (and bean) name. Identical to the decapitalized class name so
     * {@code batch/DailyTransactionLoadJob} can wire it by its conventional bean name; it is also the
     * {@code ExecutionContext} key prefix under which {@link FlatFileItemReader} persists its
     * line-count save-state, so it must remain stable across restarts.
     */
    private static final String READER_NAME = "dailyTransactionFileItemReader";

    /**
     * Canonical fixed record length of {@code DALYTRAN-RECORD} (CVTRA06Y): {@code RECFM=FB, LRECL=350}.
     * Every record is framed to exactly this width by the {@link FixedLengthBufferedReaderFactory}
     * regardless of whether the raw input carries inter-record newlines.
     */
    private static final int RECORD_LENGTH = 350;

    /**
     * Constructs and fully configures the fixed-width daily-transaction file reader.
     *
     * @param inputResource the raw external {@code DALYTRAN} fixed-width file, late-bound from the
     *                      {@code inputResource} job parameter and converted to a {@link Resource}
     *                      by Spring; never {@code null} for a real launch
     * @param lineMapper    the stateless {@link DailyTransactionLineMapper} that decodes each
     *                      350-byte record per the CVTRA06Y offset table; injected by the container
     */
    @SuppressWarnings("this-escape")
    public DailyTransactionFileItemReader(
            @Value("#{jobParameters['inputResource']}") Resource inputResource,
            DailyTransactionLineMapper lineMapper) {
        setName(READER_NAME);
        setResource(inputResource);
        setLineMapper(lineMapper);
        // ISO-8859-1 is mandatory: it preserves the zoned-decimal overpunch byte of DALYTRAN-AMT as
        // its canonical single-byte character so readSignedDecimal decodes the sign correctly.
        setEncoding(StandardCharsets.ISO_8859_1.name());
        // Frame the raw stream into fixed-length 350-byte records by POSITION rather than by newline.
        // The COBOL DALYTRAN dataset is RECFM=FB (contiguous fixed blocks, no in-band delimiter); the
        // default line-oriented factory would collapse a delimiter-free file into one over-length line
        // and silently drop every record after the first. This factory returns exactly one 350-char
        // record per readLine() whether or not the file is newline-framed, so the LF-framed ASCII
        // fixtures and a true fixed-block file decode identically, and it fails fast on a non-blank
        // short trailing remainder. One readLine() still equals one record, so the reader's restart
        // line-count save-state is unchanged.
        setBufferedReaderFactory(new FixedLengthBufferedReaderFactory(RECORD_LENGTH));
        // A missing input file must fail the step deterministically rather than load zero records.
        setStrict(true);
        // Restartable line-count save-state for chunk-oriented steps.
        setSaveState(true);
    }
}

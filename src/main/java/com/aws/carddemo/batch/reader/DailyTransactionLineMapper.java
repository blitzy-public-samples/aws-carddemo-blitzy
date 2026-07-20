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

import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.domain.DailyTransaction;

import org.springframework.batch.item.file.LineMapper;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link LineMapper} that decodes one fixed-width {@code DALYTRAN-RECORD} line
 * (copybook {@code legacy/cpy/CVTRA06Y.cpy}, {@code RECLN = 350}) into a {@link DailyTransaction}
 * staging entity. It is the field-level decode half of the raw external-file ingestion path
 * (AAP &sect;0.7.2 hotspot M2, "external fixed-width file contracts"), separated from the
 * {@link DailyTransactionFileItemReader} that owns the stream so the decode can be reviewed and
 * unit-tested in isolation.
 *
 * <h2>Legacy lineage</h2>
 * The {@code DALYTRAN} dataset is the raw, unposted daily-transaction input consumed sequentially by
 * the COBOL batch programs {@code legacy/cbl/CBTRN01C.cbl} (validate) and
 * {@code legacy/cbl/CBTRN02C.cbl} (posting), both of which {@code READ DALYTRAN-FILE INTO
 * DALYTRAN-RECORD}. This mapper reproduces that record decode so the external file can be ingested
 * directly into the {@code daily_transaction} staging table, from which the validate and posting
 * jobs then read via {@link DailyTransactionItemReader}.
 *
 * <h2>Field layout (CVTRA06Y {@code DALYTRAN-RECORD}, 0-based offsets)</h2>
 * <pre>
 * Field         COBOL PIC     Offset  Len   FixedWidthCodec call
 * ------------  ------------  ------  ----  ----------------------------------------
 * dalytranId    X(16)              0    16  readAlphanumericTrimmed(rec,   0,  16)
 * typeCd        X(02)             16     2  readAlphanumericTrimmed(rec,  16,   2)
 * catCd         9(04)             18     4  readNumericInt(rec,  18,   4)
 * tranSource    X(10)             22    10  readAlphanumericTrimmed(rec,  22,  10)
 * tranDesc      X(100)            32   100  readAlphanumericTrimmed(rec,  32, 100)
 * tranAmt       S9(09)V99        132    11  readSignedDecimal(rec, 132,  11, 2)
 * merchantId    9(09)            143     9  readNumeric(rec, 143,   9)
 * merchantName  X(50)            152    50  readAlphanumericTrimmed(rec, 152,  50)
 * merchantCity  X(50)            202    50  readAlphanumericTrimmed(rec, 202,  50)
 * merchantZip   X(10)            252    10  readAlphanumericTrimmed(rec, 252,  10)
 * cardNum       X(16)            262    16  readAlphanumericTrimmed(rec, 262,  16)
 * origTs        X(26)            278    26  readAlphanumericTrimmed(rec, 278,  26)
 * procTs        X(26)            304    26  readAlphanumericTrimmed(rec, 304,  26)
 * (FILLER)      X(20)            330    20  ignored -- total record length = 350
 * </pre>
 *
 * <h2>Monetary fidelity (mandatory)</h2>
 * The amount field {@code tranAmt} ({@code PIC S9(09)V99}) is decoded with the overpunch-aware
 * {@link FixedWidthCodec#readSignedDecimal(String, int, int, int)}, whose zoned-decimal handling
 * carries the sign on the trailing byte (for example a trailing {@code G} denotes a positive last
 * digit 7 &rarr; {@code ...7}, whereas a trailing right-brace {@code }} denotes a negative last
 * digit 0). It is always modeled as a scale-2 {@link java.math.BigDecimal}; it is <strong>never</strong>
 * parsed with {@code new BigDecimal(String)} and never a primitive {@code double}/{@code float}
 * (AAP &sect;0.6.4, decimal-handling constraint).
 *
 * <h2>Encoding contract</h2>
 * The mapper operates on a {@link String} whose characters are the record's bytes decoded with
 * {@code ISO-8859-1} by {@link DailyTransactionFileItemReader}; that single-byte charset is required
 * so the overpunch bytes of {@code tranAmt} survive intact as their canonical characters. This class
 * performs no I/O and holds no mutable state, so it is safe as a stateless singleton
 * {@link Component}; its bean name is the decapitalized class name {@code dailyTransactionLineMapper}.
 *
 * @see DailyTransactionFileItemReader
 * @see DailyTransaction
 * @see FixedWidthCodec
 */
@Component
public class DailyTransactionLineMapper implements LineMapper<DailyTransaction> {

    /** Canonical fixed record length of {@code DALYTRAN-RECORD} (CVTRA06Y): {@code RECLN = 350}. */
    static final int RECORD_LENGTH = 350;

    /**
     * End offset of the last mapped field {@code DALYTRAN-PROC-TS} ({@code @304} + 26 = 330). The
     * trailing {@code FILLER PIC X(20)} ({@code @330}) is not mapped, so a record is decodable once
     * it is at least this long.
     */
    static final int MAPPED_SPAN_LENGTH = 330;

    // --- CVTRA06Y DALYTRAN-RECORD field offsets (0-based) and lengths ---
    /** {@code DALYTRAN-ID PIC X(16)} at offset 0. */
    private static final int DALYTRAN_ID_OFFSET = 0;
    private static final int DALYTRAN_ID_LENGTH = 16;
    /** {@code DALYTRAN-TYPE-CD PIC X(02)} at offset 16. */
    private static final int TYPE_CD_OFFSET = 16;
    private static final int TYPE_CD_LENGTH = 2;
    /** {@code DALYTRAN-CAT-CD PIC 9(04)} at offset 18. */
    private static final int CAT_CD_OFFSET = 18;
    private static final int CAT_CD_LENGTH = 4;
    /** {@code DALYTRAN-SOURCE PIC X(10)} at offset 22. */
    private static final int SOURCE_OFFSET = 22;
    private static final int SOURCE_LENGTH = 10;
    /** {@code DALYTRAN-DESC PIC X(100)} at offset 32. */
    private static final int DESC_OFFSET = 32;
    private static final int DESC_LENGTH = 100;
    /** {@code DALYTRAN-AMT PIC S9(09)V99} at offset 132 (length 11, scale 2). */
    private static final int AMT_OFFSET = 132;
    private static final int AMT_LENGTH = 11;
    private static final int AMT_SCALE = 2;
    /** {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at offset 143. */
    private static final int MERCHANT_ID_OFFSET = 143;
    private static final int MERCHANT_ID_LENGTH = 9;
    /** {@code DALYTRAN-MERCHANT-NAME PIC X(50)} at offset 152. */
    private static final int MERCHANT_NAME_OFFSET = 152;
    private static final int MERCHANT_NAME_LENGTH = 50;
    /** {@code DALYTRAN-MERCHANT-CITY PIC X(50)} at offset 202. */
    private static final int MERCHANT_CITY_OFFSET = 202;
    private static final int MERCHANT_CITY_LENGTH = 50;
    /** {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} at offset 252. */
    private static final int MERCHANT_ZIP_OFFSET = 252;
    private static final int MERCHANT_ZIP_LENGTH = 10;
    /** {@code DALYTRAN-CARD-NUM PIC X(16)} at offset 262. */
    private static final int CARD_NUM_OFFSET = 262;
    private static final int CARD_NUM_LENGTH = 16;
    /** {@code DALYTRAN-ORIG-TS PIC X(26)} at offset 278. */
    private static final int ORIG_TS_OFFSET = 278;
    private static final int ORIG_TS_LENGTH = 26;
    /** {@code DALYTRAN-PROC-TS PIC X(26)} at offset 304. */
    private static final int PROC_TS_OFFSET = 304;
    private static final int PROC_TS_LENGTH = 26;

    /**
     * Decodes one fixed-width {@code DALYTRAN-RECORD} line into a transient {@link DailyTransaction}
     * following the CVTRA06Y offset table documented on this class. The surrogate identity is left
     * unset (assigned by the database on insert); every business field is populated in copybook
     * order via the 13-argument convenience constructor.
     *
     * @param line       one fixed-width daily-transaction record; must span at least
     *                   {@link #MAPPED_SPAN_LENGTH} characters (canonical LRECL {@link #RECORD_LENGTH})
     * @param lineNumber the 1-based line number within the input, supplied by the framework and used
     *                   only to enrich the exception message when a record is malformed
     * @return the decoded, transient {@link DailyTransaction}
     * @throws IllegalArgumentException if {@code line} is shorter than the mapped field span
     */
    @Override
    public DailyTransaction mapLine(String line, int lineNumber) {
        if (line == null || line.length() < MAPPED_SPAN_LENGTH) {
            throw new IllegalArgumentException(
                    "DALYTRAN-RECORD on line " + lineNumber + " too short: expected at least "
                            + MAPPED_SPAN_LENGTH + " characters (canonical LRECL " + RECORD_LENGTH
                            + ") but was " + (line == null ? 0 : line.length()));
        }
        return new DailyTransaction(
                FixedWidthCodec.readAlphanumericTrimmed(line, DALYTRAN_ID_OFFSET, DALYTRAN_ID_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(line, TYPE_CD_OFFSET, TYPE_CD_LENGTH),
                FixedWidthCodec.readNumericInt(line, CAT_CD_OFFSET, CAT_CD_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(line, SOURCE_OFFSET, SOURCE_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(line, DESC_OFFSET, DESC_LENGTH),
                FixedWidthCodec.readSignedDecimal(line, AMT_OFFSET, AMT_LENGTH, AMT_SCALE),
                FixedWidthCodec.readNumeric(line, MERCHANT_ID_OFFSET, MERCHANT_ID_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(line, MERCHANT_NAME_OFFSET, MERCHANT_NAME_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(line, MERCHANT_CITY_OFFSET, MERCHANT_CITY_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(line, MERCHANT_ZIP_OFFSET, MERCHANT_ZIP_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(line, CARD_NUM_OFFSET, CARD_NUM_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(line, ORIG_TS_OFFSET, ORIG_TS_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(line, PROC_TS_OFFSET, PROC_TS_LENGTH));
    }
}

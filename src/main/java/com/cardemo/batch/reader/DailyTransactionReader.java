package com.cardemo.batch.reader;

import com.cardemo.entity.DailyTransaction;

import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Batch reader factory for DALYTRAN (daily transaction) fixed-width records.
 *
 * <p>Translates the COBOL sequential READ pattern from paragraph
 * {@code 1000-DALYTRAN-GET-NEXT} in {@code CBTRN02C.cbl} (lines 345–369)
 * into a Spring Batch {@link FlatFileItemReader} configuration.</p>
 *
 * <h3>COBOL Source Pattern (CBTRN02C.cbl):</h3>
 * <pre>{@code
 * 1000-DALYTRAN-GET-NEXT.
 *     READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
 *     IF DALYTRAN-STATUS = '00'
 *         MOVE 0 TO APPL-RESULT
 *     ELSE IF DALYTRAN-STATUS = '10'
 *         MOVE 16 TO APPL-RESULT    (EOF)
 *     ELSE
 *         MOVE 12 TO APPL-RESULT    (ERROR → ABEND)
 *     END-IF
 * }</pre>
 *
 * <h3>Record Layout (CVTRA06Y.cpy, DALYTRAN-RECORD, RECLN = 350):</h3>
 * <pre>{@code
 * 01  DALYTRAN-RECORD.                              Total: 350 bytes
 *     05  DALYTRAN-ID                PIC X(16).     [  1- 16]
 *     05  DALYTRAN-TYPE-CD           PIC X(02).     [ 17- 18]
 *     05  DALYTRAN-CAT-CD            PIC 9(04).     [ 19- 22]
 *     05  DALYTRAN-SOURCE            PIC X(10).     [ 23- 32]
 *     05  DALYTRAN-DESC              PIC X(100).    [ 33-132]
 *     05  DALYTRAN-AMT               PIC S9(09)V99. [133-143] zoned decimal
 *     05  DALYTRAN-MERCHANT-ID       PIC 9(09).     [144-152]
 *     05  DALYTRAN-MERCHANT-NAME     PIC X(50).     [153-202]
 *     05  DALYTRAN-MERCHANT-CITY     PIC X(50).     [203-252]
 *     05  DALYTRAN-MERCHANT-ZIP      PIC X(10).     [253-262]
 *     05  DALYTRAN-CARD-NUM          PIC X(16).     [263-278]
 *     05  DALYTRAN-ORIG-TS           PIC X(26).     [279-304]
 *     05  DALYTRAN-PROC-TS           PIC X(26).     [305-330]
 *     05  FILLER                     PIC X(20).     [331-350] not tokenized
 * }</pre>
 *
 * <p>EOF is handled natively by {@link FlatFileItemReader} (returns {@code null}
 * when no more lines), equivalent to COBOL FILE STATUS {@code '10'}.
 * I/O errors map to Spring Batch exception handling, equivalent to the COBOL
 * ABEND path ({@code DALYTRAN-STATUS != '00' AND != '10'}).</p>
 *
 * <p>Column positions are obtained from
 * {@link FixedWidthFileReader#getDailyTransactionColumnSpecs()}, and the
 * COBOL zoned decimal amount field (PIC S9(09)V99 with overpunch sign in
 * last byte) is parsed using
 * {@link FixedWidthFileReader#parseZonedDecimal(String, int)}.</p>
 *
 * @see FixedWidthFileReader
 * @see DailyTransaction
 * @see com.cardemo.batch.job.DailyPostingJobConfig
 */
@Component
public class DailyTransactionReader {

    /**
     * Decimal scale for PIC S9(09)V99 — 2 decimal places from V99.
     * Used when parsing the zoned decimal amount field via
     * {@link FixedWidthFileReader#parseZonedDecimal(String, int)}.
     */
    private static final int AMOUNT_SCALE = 2;

    /**
     * Creates a fully configured {@link FlatFileItemReader} for DALYTRAN records.
     *
     * <p>Translates the COBOL {@code OPEN INPUT DALYTRAN-FILE} followed by
     * {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} pattern into a
     * Spring Batch reader that produces {@link DailyTransaction} entities.</p>
     *
     * <p>Configuration details:</p>
     * <ul>
     *   <li>Reader name: {@code "dailyTransactionReader"} — enables restart support</li>
     *   <li>Encoding: ASCII (all files in {@code app/data/ASCII/} are ASCII)</li>
     *   <li>Strict: {@code true} — fail if file missing (COBOL OPEN error → ABEND)</li>
     *   <li>Lines to skip: 0 — COBOL fixed-width files have no headers</li>
     *   <li>Column positions: from {@link FixedWidthFileReader#getDailyTransactionColumnSpecs()}</li>
     * </ul>
     *
     * @param resource the DALYTRAN input file resource, translating COBOL
     *                 {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN} into
     *                 Spring resource resolution (e.g., {@code classpath:fixtures/dailytran.txt})
     * @return a configured {@link FlatFileItemReader} producing {@link DailyTransaction} entities
     */
    public FlatFileItemReader<DailyTransaction> createReader(Resource resource) {
        // Retrieve DALYTRAN column specifications from FixedWidthFileReader
        // These match CVTRA06Y.cpy byte layout byte-for-byte
        List<FixedWidthFileReader.ColumnSpec> columnSpecs =
                FixedWidthFileReader.getDailyTransactionColumnSpecs();

        // Build Range and name arrays from column specifications
        // Ranges use 1-based inclusive indices matching COBOL PIC positions
        Range[] ranges = new Range[columnSpecs.size()];
        String[] names = new String[columnSpecs.size()];
        for (int i = 0; i < columnSpecs.size(); i++) {
            FixedWidthFileReader.ColumnSpec spec = columnSpecs.get(i);
            ranges[i] = new Range(spec.startPosition(), spec.endPosition());
            names[i] = spec.fieldName();
        }

        // Configure FixedLengthTokenizer — splits 350-byte lines
        // by CVTRA06Y.cpy field positions (13 data fields, FILLER excluded).
        // Strict=false allows lines longer than the last column range (330)
        // since the 20-byte FILLER at positions 331-350 is intentionally
        // not tokenized — it carries no business data per CVTRA06Y.cpy.
        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        tokenizer.setColumns(ranges);
        tokenizer.setNames(names);
        tokenizer.setStrict(false);

        // Configure DefaultLineMapper — wires tokenizer and field mapper
        DefaultLineMapper<DailyTransaction> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(new DailyTransactionFieldSetMapper());

        // Configure FlatFileItemReader — equivalent to COBOL OPEN INPUT + READ
        FlatFileItemReader<DailyTransaction> reader = new FlatFileItemReader<>();
        reader.setName("dailyTransactionReader");
        reader.setResource(resource);
        reader.setEncoding("ASCII");
        // Strict=true: fail if file doesn't exist, equivalent to COBOL OPEN
        // INPUT error → DISPLAY 'ERROR OPENING DALYTRAN' → ABEND
        reader.setStrict(true);
        // No header lines in COBOL fixed-width files
        reader.setLinesToSkip(0);
        reader.setLineMapper(lineMapper);

        return reader;
    }

    /**
     * Maps parsed fixed-width fields to {@link DailyTransaction} entity instances.
     *
     * <p>Each field is read by its column spec name (as defined in
     * {@link FixedWidthFileReader#getDailyTransactionColumnSpecs()}) and mapped to
     * the corresponding {@link DailyTransaction} property. Field names use the
     * {@code tran*} prefix from the shared transaction column spec definitions.</p>
     *
     * <p>Field mapping (column spec name → entity property):</p>
     * <ul>
     *   <li>{@code tranId} → {@code dalytranId} (PIC X(16), trimmed)</li>
     *   <li>{@code tranTypeCd} → {@code typeCode} (PIC X(02), trimmed)</li>
     *   <li>{@code tranCatCd} → {@code categoryCode} (PIC 9(04), parsed to Integer)</li>
     *   <li>{@code tranSource} → {@code source} (PIC X(10), trimmed)</li>
     *   <li>{@code tranDesc} → {@code description} (PIC X(100), trimmed)</li>
     *   <li>{@code tranAmt} → {@code amount} (PIC S9(09)V99, zoned decimal → BigDecimal)</li>
     *   <li>{@code merchantId} → {@code merchantId} (PIC 9(09), trimmed)</li>
     *   <li>{@code merchantName} → {@code merchantName} (PIC X(50), trimmed)</li>
     *   <li>{@code merchantCity} → {@code merchantCity} (PIC X(50), trimmed)</li>
     *   <li>{@code merchantZip} → {@code merchantZip} (PIC X(10), trimmed)</li>
     *   <li>{@code cardNum} → {@code cardNum} (PIC X(16), trimmed)</li>
     *   <li>{@code origTimestamp} → {@code origTimestamp} (PIC X(26), trimmed)</li>
     *   <li>{@code procTimestamp} → {@code procTimestamp} (PIC X(26), trimmed)</li>
     * </ul>
     *
     * <p>The amount field uses COBOL zoned decimal parsing via
     * {@link FixedWidthFileReader#parseZonedDecimal(String, int)} to handle
     * the EBCDIC-to-ASCII overpunch sign encoding in the last byte.
     * Examples from actual data:</p>
     * <ul>
     *   <li>{@code "0000005047G"} → G=+7 → BigDecimal("504.77")</li>
     *   <li>{@code "0000009190}"} → }=-0 → BigDecimal("-919.00")</li>
     *   <li>{@code "0000000567P"} → P=-7 → BigDecimal("-56.77")</li>
     * </ul>
     *
     * <p>The surrogate {@code id} field on {@link DailyTransaction} is NOT set
     * — it is auto-generated by the database ({@code @GeneratedValue(IDENTITY)}).</p>
     */
    private static class DailyTransactionFieldSetMapper
            implements FieldSetMapper<DailyTransaction> {

        @Override
        public DailyTransaction mapFieldSet(FieldSet fieldSet) {
            // Read PIC X string fields with trim to remove trailing spaces
            String dalytranId = fieldSet.readString("tranId").trim();
            String typeCode = fieldSet.readString("tranTypeCd").trim();
            String source = fieldSet.readString("tranSource").trim();
            String description = fieldSet.readString("tranDesc").trim();
            String merchantId = fieldSet.readString("merchantId").trim();
            String merchantName = fieldSet.readString("merchantName").trim();
            String merchantCity = fieldSet.readString("merchantCity").trim();
            String merchantZip = fieldSet.readString("merchantZip").trim();
            String cardNum = fieldSet.readString("cardNum").trim();
            String origTimestamp = fieldSet.readString("origTimestamp").trim();
            String procTimestamp = fieldSet.readString("procTimestamp").trim();

            // Parse category code: PIC 9(04) → Integer
            // COBOL PIC 9(04) is a 4-digit unsigned integer (e.g., "0001")
            String catCdRaw = fieldSet.readString("tranCatCd").trim();
            Integer categoryCode = catCdRaw.isEmpty() ? null : Integer.valueOf(catCdRaw);

            // Parse zoned decimal amount: PIC S9(09)V99 → BigDecimal
            // The last byte carries the sign via EBCDIC overpunch encoding:
            //   Positive: '{' = 0, 'A' = 1, ... 'I' = 9
            //   Negative: '}' = 0, 'J' = 1, ... 'R' = 9
            // V99 means 2 implicit decimal places → scale=2
            String amountRaw = fieldSet.readString("tranAmt");
            BigDecimal amount = FixedWidthFileReader.parseZonedDecimal(
                    amountRaw, AMOUNT_SCALE);

            // Create entity via parameterized constructor
            // (protected no-arg constructor is reserved for JPA framework)
            return new DailyTransaction(
                    dalytranId, typeCode, categoryCode,
                    source, description, amount,
                    merchantId, merchantName, merchantCity,
                    merchantZip, cardNum, origTimestamp, procTimestamp);
        }
    }
}

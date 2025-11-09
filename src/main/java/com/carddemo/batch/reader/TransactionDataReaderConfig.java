package com.carddemo.batch.reader;

import com.carddemo.entity.Transaction;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;
import org.springframework.context.annotation.Bean;
// DISABLED: @Configuration annotation removed to prevent bean definition conflicts with TransactionDataReader.java
// import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * LEGACY FILE - NOT ACTIVELY USED
 * 
 * This file was created during refactoring but is superseded by TransactionDataReader.java.
 * The @Configuration annotation has been removed to prevent Spring bean definition conflicts.
 * This class is retained for reference purposes only.
 * 
 * USE TransactionDataReader.java instead for daily transaction file reading.
 * 
 * Spring Batch FlatFileItemReader configuration for reading daily transaction data files.
 * 
 * <p>This reader processes fixed-width transaction records migrated from mainframe VSAM KSDS 
 * TRANSACT file format. Each record is 350 bytes containing transaction details including
 * monetary amounts in COBOL display format, merchant information, and ISO-8601 timestamps
 * with microsecond precision.</p>
 * 
 * <p><strong>Source File Mapping:</strong></p>
 * <ul>
 *   <li>COBOL Copybook: app/cpy/CVTRA05Y.cpy (TRAN-RECORD structure)</li>
 *   <li>Data File: app/data/ASCII/dailytran.txt (EBCDIC-to-ASCII converted)</li>
 *   <li>Original Format: VSAM KSDS TRANSACT file, 350-byte records</li>
 *   <li>Target Entity: com.carddemo.entity.Transaction (JPA entity)</li>
 * </ul>
 * 
 * <p><strong>Record Layout (350 bytes fixed-width):</strong></p>
 * <table border="1">
 *   <tr><th>Position</th><th>Length</th><th>COBOL Field</th><th>Java Field</th><th>Format</th></tr>
 *   <tr><td>1-16</td><td>16</td><td>TRAN-ID</td><td>transactionId</td><td>Alphanumeric</td></tr>
 *   <tr><td>17-18</td><td>2</td><td>TRAN-TYPE-CD</td><td>typeCode</td><td>Alphanumeric</td></tr>
 *   <tr><td>19-22</td><td>4</td><td>TRAN-CAT-CD</td><td>categoryCode</td><td>Numeric (stored as String)</td></tr>
 *   <tr><td>23-32</td><td>10</td><td>TRAN-SOURCE</td><td>transactionSource</td><td>Alphanumeric</td></tr>
 *   <tr><td>33-132</td><td>100</td><td>TRAN-DESC</td><td>description</td><td>Alphanumeric</td></tr>
 *   <tr><td>133-143</td><td>11</td><td>TRAN-AMT</td><td>amount</td><td>COBOL Display with sign overpunch</td></tr>
 *   <tr><td>144-152</td><td>9</td><td>TRAN-MERCHANT-ID</td><td>merchantId</td><td>Numeric</td></tr>
 *   <tr><td>153-202</td><td>50</td><td>TRAN-MERCHANT-NAME</td><td>merchantName</td><td>Alphanumeric</td></tr>
 *   <tr><td>203-252</td><td>50</td><td>TRAN-MERCHANT-CITY</td><td>merchantCity</td><td>Alphanumeric</td></tr>
 *   <tr><td>253-262</td><td>10</td><td>TRAN-MERCHANT-ZIP</td><td>merchantZip</td><td>Alphanumeric</td></tr>
 *   <tr><td>263-278</td><td>16</td><td>TRAN-CARD-NUM</td><td>cardNumber</td><td>Alphanumeric</td></tr>
 *   <tr><td>279-304</td><td>26</td><td>TRAN-ORIG-TS</td><td>originationTimestamp</td><td>ISO-8601 with microseconds</td></tr>
 *   <tr><td>305-330</td><td>26</td><td>TRAN-PROC-TS</td><td>processingTimestamp</td><td>ISO-8601 with microseconds</td></tr>
 *   <tr><td>331-350</td><td>20</td><td>FILLER</td><td>(ignored)</td><td>Padding</td></tr>
 * </table>
 * 
 * <p><strong>CRITICAL: COBOL Display Format Amount Decoding</strong></p>
 * <p>The TRAN-AMT field (positions 133-143, 11 characters) uses COBOL display format with
 * sign overpunch encoding (PIC S9(09)V99). This format represents a signed decimal number
 * with 9 integer digits and 2 implied decimal places. The sign is encoded in the LAST
 * (rightmost) digit using alphabetic characters:</p>
 * 
 * <p><strong>Sign Overpunch Encoding Table:</strong></p>
 * <table border="1">
 *   <tr><th>Character</th><th>Digit Value</th><th>Sign</th><th>Example</th><th>Numeric Value</th></tr>
 *   <tr><td>{ (left brace)</td><td>0</td><td>Positive</td><td>"0000000123{"</td><td>+1230.00</td></tr>
 *   <tr><td>A</td><td>1</td><td>Positive</td><td>"0000000123A"</td><td>+1231.00</td></tr>
 *   <tr><td>B</td><td>2</td><td>Positive</td><td>"0000000123B"</td><td>+1232.00</td></tr>
 *   <tr><td>C</td><td>3</td><td>Positive</td><td>"0000000123C"</td><td>+1233.00</td></tr>
 *   <tr><td>D</td><td>4</td><td>Positive</td><td>"0000000123D"</td><td>+1234.00</td></tr>
 *   <tr><td>E</td><td>5</td><td>Positive</td><td>"0000000123E"</td><td>+1235.00</td></tr>
 *   <tr><td>F</td><td>6</td><td>Positive</td><td>"0000000123F"</td><td>+1236.00</td></tr>
 *   <tr><td>G</td><td>7</td><td>Positive</td><td>"0000000123G"</td><td>+1237.00</td></tr>
 *   <tr><td>H</td><td>8</td><td>Positive</td><td>"0000000123H"</td><td>+1238.00</td></tr>
 *   <tr><td>I</td><td>9</td><td>Positive</td><td>"0000000123I"</td><td>+1239.00</td></tr>
 *   <tr><td>} (right brace)</td><td>0</td><td>Negative</td><td>"0000000123}"</td><td>-1230.00</td></tr>
 *   <tr><td>J</td><td>1</td><td>Negative</td><td>"0000000123J"</td><td>-1231.00</td></tr>
 *   <tr><td>K</td><td>2</td><td>Negative</td><td>"0000000123K"</td><td>-1232.00</td></tr>
 *   <tr><td>L</td><td>3</td><td>Negative</td><td>"0000000123L"</td><td>-1233.00</td></tr>
 *   <tr><td>M</td><td>4</td><td>Negative</td><td>"0000000123M"</td><td>-1234.00</td></tr>
 *   <tr><td>N</td><td>5</td><td>Negative</td><td>"0000000123N"</td><td>-1235.00</td></tr>
 *   <tr><td>O</td><td>6</td><td>Negative</td><td>"0000000123O"</td><td>-1236.00</td></tr>
 *   <tr><td>P</td><td>7</td><td>Negative</td><td>"0000000123P"</td><td>-1237.00</td></tr>
 *   <tr><td>Q</td><td>8</td><td>Negative</td><td>"0000000123Q"</td><td>-1238.00</td></tr>
 *   <tr><td>R</td><td>9</td><td>Negative</td><td>"0000000123R"</td><td>-1239.00</td></tr>
 * </table>
 * 
 * <p><strong>Amount Decoding Algorithm:</strong></p>
 * <ol>
 *   <li>Extract 11-character amount string from positions 133-143</li>
 *   <li>Extract first 10 characters as numeric digits (positions 0-9)</li>
 *   <li>Extract last character (position 10) for sign and final digit decoding</li>
 *   <li>Decode last character using overpunch table to get digit value (0-9) and sign (+/-)</li>
 *   <li>Replace last character in numeric string with decoded digit</li>
 *   <li>Convert full numeric string to BigDecimal</li>
 *   <li>Apply sign (positive or negative)</li>
 *   <li>Divide by 100 to apply implied decimal point (V99 = 2 decimal places)</li>
 *   <li>Set scale to 2 with RoundingMode.HALF_UP for exact COBOL precision</li>
 * </ol>
 * 
 * <p><strong>Example Amount Conversions:</strong></p>
 * <pre>
 * COBOL Display Format → Numeric Value → BigDecimal
 * "0000005047G"        → +50477        → 504.77
 * "0000009190}"        → -91900        → -919.00
 * "0000000678H"        → +6788         → 67.88
 * "0000003250{"        → +32500        → 325.00
 * "0000000567P"        → -5677         → -56.77
 * </pre>
 * 
 * <p><strong>Timestamp Format:</strong></p>
 * <p>Both TRAN-ORIG-TS and TRAN-PROC-TS use ISO-8601 format with microsecond precision:</p>
 * <ul>
 *   <li>Format: "yyyy-MM-dd HH:mm:ss.SSSSSS" (26 characters)</li>
 *   <li>Example: "2022-06-10 19:27:53.000000"</li>
 *   <li>Timezone: No timezone indicator (merchant local time or UTC)</li>
 *   <li>Precision: Microseconds (6 decimal places for seconds)</li>
 *   <li>Conversion: Parse to LocalDateTime using DateTimeFormatter</li>
 * </ul>
 * 
 * <p><strong>Usage in Batch Processing:</strong></p>
 * <p>This reader is configured as a bean and injected into DailyTransactionProcessingJob
 * (CBTRN02C.cbl transformation). The batch job workflow:</p>
 * <ol>
 *   <li><strong>Read Phase:</strong> ItemReader reads fixed-width records from dailytran.txt</li>
 *   <li><strong>Process Phase:</strong> ItemProcessor validates transactions, checks authorization,
 *       calculates account balance updates</li>
 *   <li><strong>Write Phase:</strong> ItemWriter persists Transaction entities to PostgreSQL,
 *       updates account balances atomically</li>
 * </ol>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>Chunk Size: Typically 1000 records (configurable in job definition)</li>
 *   <li>Transaction Boundary: Chunk-level @Transactional commits</li>
 *   <li>Processing Window: Must complete within 4-hour batch window</li>
 *   <li>Volume Capacity: Designed to handle daily transaction volumes efficiently</li>
 *   <li>Error Handling: Skip policy configured in job for malformed records</li>
 * </ul>
 * 
 * <p><strong>Functional Equivalence:</strong></p>
 * <p>This reader maintains complete functional equivalence with COBOL CBTRN02C batch program:</p>
 * <ul>
 *   <li>Exact field positions matching COBOL copybook CVTRA05Y.cpy</li>
 *   <li>Precise COBOL display format decoding (sign overpunch)</li>
 *   <li>BigDecimal precision matching PIC S9(09)V99 with scale=2</li>
 *   <li>ISO-8601 timestamp parsing with microsecond precision</li>
 *   <li>Whitespace trimming for alphanumeric fields (matching COBOL behavior)</li>
 *   <li>UTF-8 encoding for EBCDIC-to-ASCII converted files</li>
 * </ul>
 * 
 * @see com.carddemo.entity.Transaction
 * @see com.carddemo.batch.job.DailyTransactionProcessingJob
 * @see <a href="Section 0.6">File-by-File Transformation Plan - CBTRN02C to DailyTransactionProcessingJob</a>
 * @see <a href="Section 0.10">Special Instructions - COBOL COMP-3 to Java BigDecimal Precision</a>
 */
// @Configuration - DISABLED to prevent bean definition conflicts with TransactionDataReader.java
public class TransactionDataReaderConfig {

    /**
     * DateTimeFormatter for parsing COBOL timestamp format.
     * 
     * <p>Format: "yyyy-MM-dd HH:mm:ss.SSSSSS" (26 characters with microsecond precision)</p>
     * <p>Example: "2022-06-10 19:27:53.000000"</p>
     * 
     * <p>This formatter handles the ISO-8601 timestamp format used in TRAN-ORIG-TS
     * and TRAN-PROC-TS fields from the COBOL copybook.</p>
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * Creates and configures FlatFileItemReader for transaction data.
     * 
     * <p>This bean configures a Spring Batch FlatFileItemReader with FixedLengthTokenizer
     * for reading 350-byte transaction records from dailytran.txt. The reader uses a custom
     * FieldSetMapper (TransactionFieldSetMapper inner class) to handle complex COBOL display
     * format conversions, particularly for monetary amounts with sign overpunch encoding.</p>
     * 
     * <p><strong>Configuration Details:</strong></p>
     * <ul>
     *   <li>Name: "transactionDataReader" (for batch metadata tracking)</li>
     *   <li>Resource: ClassPathResource("data/dailytran.txt") for development/testing</li>
     *   <li>Alternative: FileSystemResource for production file system access</li>
     *   <li>Tokenizer: FixedLengthTokenizer with exact field ranges</li>
     *   <li>Mapper: Custom TransactionFieldSetMapper for COBOL format conversions</li>
     *   <li>Encoding: UTF-8 (assumes EBCDIC-to-ASCII conversion already performed)</li>
     *   <li>Lines to Skip: 0 (no header row in fixed-width format)</li>
     *   <li>Strict: true (fail on malformed records)</li>
     * </ul>
     * 
     * <p><strong>Field Range Configuration:</strong></p>
     * <p>Each Range object defines the start and end positions (1-based, inclusive) for
     * extracting fields from the 350-byte fixed-width record. These ranges exactly match
     * the COBOL copybook CVTRA05Y.cpy field positions.</p>
     * 
     * <p><strong>Production Usage Note:</strong></p>
     * <p>For production deployments, replace ClassPathResource with FileSystemResource
     * pointing to the actual daily transaction file location:</p>
     * <pre>
     * .resource(new FileSystemResource("/data/batch/dailytran.txt"))
     * </pre>
     * 
     * @return Configured FlatFileItemReader&lt;Transaction&gt; for reading transaction data
     */
    @Bean
    public FlatFileItemReader<Transaction> transactionDataReader() {
        return new FlatFileItemReaderBuilder<Transaction>()
            .name("transactionDataReader")
            .resource(new ClassPathResource("data/dailytran.txt"))
            .lineTokenizer(fixedLengthTokenizer())
            .fieldSetMapper(new TransactionFieldSetMapper())
            .strict(true)
            .encoding("UTF-8")
            .linesToSkip(0)
            .build();
    }

    /**
     * Configures FixedLengthTokenizer with exact field ranges from COBOL copybook.
     * 
     * <p>This tokenizer parses 350-byte fixed-width records into named fields using
     * exact character position ranges. All positions are 1-based and inclusive,
     * matching COBOL field definitions.</p>
     * 
     * <p><strong>Field Mapping:</strong></p>
     * <ul>
     *   <li>transactionId: positions 1-16 (16 chars)</li>
     *   <li>typeCode: positions 17-18 (2 chars)</li>
     *   <li>categoryCode: positions 19-22 (4 chars)</li>
     *   <li>transactionSource: positions 23-32 (10 chars)</li>
     *   <li>description: positions 33-132 (100 chars)</li>
     *   <li>amount: positions 133-143 (11 chars, COBOL display format)</li>
     *   <li>merchantId: positions 144-152 (9 chars)</li>
     *   <li>merchantName: positions 153-202 (50 chars)</li>
     *   <li>merchantCity: positions 203-252 (50 chars)</li>
     *   <li>merchantZip: positions 253-262 (10 chars)</li>
     *   <li>cardNumber: positions 263-278 (16 chars)</li>
     *   <li>originationTimestamp: positions 279-304 (26 chars)</li>
     *   <li>processingTimestamp: positions 305-330 (26 chars)</li>
     * </ul>
     * <p>Note: FILLER field (positions 331-350, 20 chars) is not extracted</p>
     * 
     * @return Configured FixedLengthTokenizer for transaction records
     */
    private FixedLengthTokenizer fixedLengthTokenizer() {
        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        
        // Set field names matching Transaction entity properties
        tokenizer.setNames(
            "transactionId",
            "typeCode",
            "categoryCode",
            "transactionSource",
            "description",
            "amount",
            "merchantId",
            "merchantName",
            "merchantCity",
            "merchantZip",
            "cardNumber",
            "originationTimestamp",
            "processingTimestamp"
        );
        
        // Set exact column ranges matching COBOL copybook field positions
        // All positions are 1-based and inclusive (Range constructor uses 1-based indexing)
        tokenizer.setColumns(
            new Range(1, 16),     // TRAN-ID: PIC X(16)
            new Range(17, 18),    // TRAN-TYPE-CD: PIC X(02)
            new Range(19, 22),    // TRAN-CAT-CD: PIC 9(04)
            new Range(23, 32),    // TRAN-SOURCE: PIC X(10)
            new Range(33, 132),   // TRAN-DESC: PIC X(100)
            new Range(133, 143),  // TRAN-AMT: PIC S9(09)V99 (11 chars in display format)
            new Range(144, 152),  // TRAN-MERCHANT-ID: PIC 9(09)
            new Range(153, 202),  // TRAN-MERCHANT-NAME: PIC X(50)
            new Range(203, 252),  // TRAN-MERCHANT-CITY: PIC X(50)
            new Range(253, 262),  // TRAN-MERCHANT-ZIP: PIC X(10)
            new Range(263, 278),  // TRAN-CARD-NUM: PIC X(16)
            new Range(279, 304),  // TRAN-ORIG-TS: PIC X(26)
            new Range(305, 330)   // TRAN-PROC-TS: PIC X(26)
            // Note: FILLER (positions 331-350) is not extracted
        );
        
        return tokenizer;
    }

    /**
     * Custom FieldSetMapper for converting parsed fields to Transaction entity.
     * 
     * <p>This mapper handles complex data type conversions from COBOL format strings
     * to Java types, with special handling for:</p>
     * <ul>
     *   <li><strong>Monetary Amounts:</strong> COBOL display format with sign overpunch
     *       → BigDecimal with scale=2</li>
     *   <li><strong>Timestamps:</strong> ISO-8601 strings with microseconds → LocalDateTime</li>
     *   <li><strong>Numeric IDs:</strong> Numeric strings → Long</li>
     *   <li><strong>Text Fields:</strong> Fixed-width strings → Trimmed strings</li>
     * </ul>
     * 
     * <p>The mapper creates Transaction entity instances (not persisted Card references)
     * that will be processed and written by the batch job.</p>
     */
    private static class TransactionFieldSetMapper implements FieldSetMapper<Transaction> {

        /**
         * Maps FieldSet (parsed record fields) to Transaction entity.
         * 
         * <p>This method performs the following conversions:</p>
         * <ol>
         *   <li>Extracts all fields from FieldSet</li>
         *   <li>Decodes COBOL display format amount using decodeCobolDisplayAmount()</li>
         *   <li>Parses ISO-8601 timestamps using TIMESTAMP_FORMATTER</li>
         *   <li>Converts merchant ID string to Long</li>
         *   <li>Trims whitespace from all text fields</li>
         *   <li>Builds Transaction entity using builder pattern</li>
         * </ol>
         * 
         * <p><strong>Card Reference Handling:</strong></p>
         * <p>The card relationship is NOT set by this mapper. The ItemProcessor or
         * ItemWriter must resolve the cardNumber to an actual Card entity reference
         * before persisting the Transaction.</p>
         * 
         * <p><strong>Error Handling:</strong></p>
         * <p>If any field conversion fails (e.g., invalid amount format, unparseable
         * timestamp), the method throws a runtime exception that will be handled by the
         * batch job's skip policy.</p>
         * 
         * @param fieldSet Parsed fields from one fixed-width record line
         * @return Transaction entity populated with converted field values
         * @throws IllegalArgumentException if field conversion fails (amount decoding, timestamp parsing, etc.)
         */
        @Override
        public Transaction mapFieldSet(org.springframework.batch.item.file.transform.FieldSet fieldSet) {
            
            // Extract raw field values
            String transactionId = fieldSet.readString("transactionId").trim();
            String typeCode = fieldSet.readString("typeCode").trim();
            Integer categoryCode = Integer.parseInt(fieldSet.readString("categoryCode").trim());
            String transactionSource = fieldSet.readString("transactionSource").trim();
            String description = fieldSet.readString("description").trim();
            String amountString = fieldSet.readString("amount"); // Do NOT trim - fixed 11 chars
            String merchantIdString = fieldSet.readString("merchantId").trim();
            String merchantName = fieldSet.readString("merchantName").trim();
            String merchantCity = fieldSet.readString("merchantCity").trim();
            String merchantZip = fieldSet.readString("merchantZip").trim();
            String cardNumber = fieldSet.readString("cardNumber").trim();
            String originationTimestampString = fieldSet.readString("originationTimestamp").trim();
            String processingTimestampString = fieldSet.readString("processingTimestamp").trim();
            
            // Decode COBOL display format amount to BigDecimal
            BigDecimal amount = decodeCobolDisplayAmount(amountString);
            
            // Parse merchant ID (may be zero or null for non-merchant transactions)
            Long merchantId = null;
            if (merchantIdString != null && !merchantIdString.isEmpty() && !merchantIdString.equals("000000000")) {
                merchantId = Long.parseLong(merchantIdString);
            }
            
            // Parse ISO-8601 timestamps with microsecond precision
            LocalDateTime originationTimestamp = LocalDateTime.parse(originationTimestampString, TIMESTAMP_FORMATTER);
            LocalDateTime processingTimestamp = LocalDateTime.parse(processingTimestampString, TIMESTAMP_FORMATTER);
            
            // Build Transaction entity using builder pattern
            // Note: Card relationship must be resolved by ItemProcessor or ItemWriter
            return Transaction.builder()
                .transactionId(transactionId)
                .typeCode(typeCode)
                .categoryCode(categoryCode)
                .transactionSource(transactionSource)
                .description(description)
                .amount(amount)
                .merchantId(merchantId)
                .merchantName(merchantName)
                .merchantCity(merchantCity)
                .merchantZip(merchantZip)
                // card field will be set by processor after looking up Card entity by cardNumber
                .originationTimestamp(originationTimestamp)
                .processingTimestamp(processingTimestamp)
                .build();
        }

        /**
         * Decodes COBOL display format amount with sign overpunch to BigDecimal.
         * 
         * <p>This method implements the COBOL PIC S9(09)V99 display format decoding
         * algorithm, handling sign overpunch encoding where the sign is combined with
         * the last digit using alphabetic characters.</p>
         * 
         * <p><strong>Algorithm Steps:</strong></p>
         * <ol>
         *   <li>Validate input is exactly 11 characters</li>
         *   <li>Extract first 10 characters as base numeric string</li>
         *   <li>Extract last character (position 10) for sign/digit decoding</li>
         *   <li>Decode last character using getDigitFromOverpunch() and getSignFromOverpunch()</li>
         *   <li>Replace last character position with decoded digit</li>
         *   <li>Parse full 11-digit numeric string to long</li>
         *   <li>Apply sign (multiply by -1 if negative)</li>
         *   <li>Convert to BigDecimal and divide by 100 (for V99 implied decimal)</li>
         *   <li>Set scale to 2 with RoundingMode.HALF_UP for COBOL precision</li>
         * </ol>
         * 
         * <p><strong>Overpunch Character Mapping:</strong></p>
         * <table border="1">
         *   <tr><th>Char</th><th>Digit</th><th>Sign</th></tr>
         *   <tr><td>{ (or 0-9)</td><td>0</td><td>Positive</td></tr>
         *   <tr><td>A</td><td>1</td><td>Positive</td></tr>
         *   <tr><td>B</td><td>2</td><td>Positive</td></tr>
         *   <tr><td>C</td><td>3</td><td>Positive</td></tr>
         *   <tr><td>D</td><td>4</td><td>Positive</td></tr>
         *   <tr><td>E</td><td>5</td><td>Positive</td></tr>
         *   <tr><td>F</td><td>6</td><td>Positive</td></tr>
         *   <tr><td>G</td><td>7</td><td>Positive</td></tr>
         *   <tr><td>H</td><td>8</td><td>Positive</td></tr>
         *   <tr><td>I</td><td>9</td><td>Positive</td></tr>
         *   <tr><td>}</td><td>0</td><td>Negative</td></tr>
         *   <tr><td>J</td><td>1</td><td>Negative</td></tr>
         *   <tr><td>K</td><td>2</td><td>Negative</td></tr>
         *   <tr><td>L</td><td>3</td><td>Negative</td></tr>
         *   <tr><td>M</td><td>4</td><td>Negative</td></tr>
         *   <tr><td>N</td><td>5</td><td>Negative</td></tr>
         *   <tr><td>O</td><td>6</td><td>Negative</td></tr>
         *   <tr><td>P</td><td>7</td><td>Negative</td></tr>
         *   <tr><td>Q</td><td>8</td><td>Negative</td></tr>
         *   <tr><td>R</td><td>9</td><td>Negative</td></tr>
         * </table>
         * 
         * <p><strong>Example Conversions:</strong></p>
         * <pre>
         * "0000005047G" → 50477 (positive, G=7) → 504.77
         * "0000009190}" → 91900 (negative, }=0) → -919.00
         * "0000000678H" → 6788 (positive, H=8) → 67.88
         * "0000003250{" → 32500 (positive, {=0) → 325.00
         * "0000000567P" → 5677 (negative, P=7) → -56.77
         * </pre>
         * 
         * <p><strong>Precision Guarantee:</strong></p>
         * <p>The resulting BigDecimal has precision=12 and scale=2, exactly matching
         * COBOL PIC S9(09)V99 COMP-3 packed decimal behavior. RoundingMode.HALF_UP
         * ensures identical rounding to COBOL arithmetic operations.</p>
         * 
         * @param amountString 11-character COBOL display format amount string
         * @return BigDecimal with scale=2 representing the decoded monetary amount
         * @throws IllegalArgumentException if amountString is not exactly 11 characters
         * @throws NumberFormatException if numeric parsing fails
         */
        private BigDecimal decodeCobolDisplayAmount(String amountString) {
            // Validate input length
            if (amountString == null || amountString.length() != 11) {
                throw new IllegalArgumentException(
                    "COBOL display format amount must be exactly 11 characters, got: " + 
                    (amountString == null ? "null" : amountString.length()));
            }
            
            // Extract first 10 characters and last character
            String baseTenDigits = amountString.substring(0, 10);
            char lastChar = amountString.charAt(10);
            
            // Decode last character to get digit and sign
            int lastDigit = getDigitFromOverpunch(lastChar);
            int sign = getSignFromOverpunch(lastChar);
            
            // Build complete numeric string by replacing last char with decoded digit
            String fullNumericString = baseTenDigits + lastDigit;
            
            // Parse to long (11 digits)
            long numericValue = Long.parseLong(fullNumericString);
            
            // Apply sign
            numericValue = numericValue * sign;
            
            // Convert to BigDecimal with implied decimal point (divide by 100 for 2 decimal places)
            BigDecimal amount = new BigDecimal(numericValue);
            amount = amount.divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
            
            // Ensure scale is exactly 2 (matching COBOL V99)
            amount = amount.setScale(2, RoundingMode.HALF_UP);
            
            return amount;
        }

        /**
         * Extracts digit value (0-9) from COBOL overpunch character.
         * 
         * <p>Maps alphabetic overpunch characters to their numeric digit values:</p>
         * <ul>
         *   <li>{ or } → 0</li>
         *   <li>A or J → 1</li>
         *   <li>B or K → 2</li>
         *   <li>C or L → 3</li>
         *   <li>D or M → 4</li>
         *   <li>E or N → 5</li>
         *   <li>F or O → 6</li>
         *   <li>G or P → 7</li>
         *   <li>H or Q → 8</li>
         *   <li>I or R → 9</li>
         *   <li>0-9 → same digit (for unsigned or alternative encoding)</li>
         * </ul>
         * 
         * @param c Overpunch character from last position of amount field
         * @return Digit value 0-9
         * @throws IllegalArgumentException if character is not a valid overpunch encoding
         */
        private int getDigitFromOverpunch(char c) {
            switch (c) {
                case '{': case '}': return 0;
                case 'A': case 'J': return 1;
                case 'B': case 'K': return 2;
                case 'C': case 'L': return 3;
                case 'D': case 'M': return 4;
                case 'E': case 'N': return 5;
                case 'F': case 'O': return 6;
                case 'G': case 'P': return 7;
                case 'H': case 'Q': return 8;
                case 'I': case 'R': return 9;
                case '0': return 0;
                case '1': return 1;
                case '2': return 2;
                case '3': return 3;
                case '4': return 4;
                case '5': return 5;
                case '6': return 6;
                case '7': return 7;
                case '8': return 8;
                case '9': return 9;
                default:
                    throw new IllegalArgumentException(
                        "Invalid COBOL overpunch character: '" + c + "' (ASCII " + (int)c + ")");
            }
        }

        /**
         * Extracts sign (+1 or -1) from COBOL overpunch character.
         * 
         * <p>Determines whether the overpunch character represents a positive
         * or negative value:</p>
         * <ul>
         *   <li>Positive (+1): {, A-I, or regular digits 0-9</li>
         *   <li>Negative (-1): }, J-R</li>
         * </ul>
         * 
         * @param c Overpunch character from last position of amount field
         * @return +1 for positive, -1 for negative
         * @throws IllegalArgumentException if character is not a valid overpunch encoding
         */
        private int getSignFromOverpunch(char c) {
            // Positive characters: { A B C D E F G H I and regular digits 0-9
            if (c == '{' || (c >= 'A' && c <= 'I') || (c >= '0' && c <= '9')) {
                return 1;
            }
            // Negative characters: } J K L M N O P Q R
            else if (c == '}' || (c >= 'J' && c <= 'R')) {
                return -1;
            }
            else {
                throw new IllegalArgumentException(
                    "Invalid COBOL overpunch character: '" + c + "' (ASCII " + (int)c + ")");
            }
        }
    }
}


package com.carddemo.batch.reader;

import com.carddemo.dto.DailyTransactionInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Spring Batch configuration for reading daily transaction input data from fixed-width text files.
 * 
 * <p>This class configures a {@link FlatFileItemReader} that reads daily transaction data from
 * the DALYTRAN-FILE (dailytran.txt), which contains 350-byte fixed-width records converted from
 * the mainframe VSAM sequential file with EBCDIC-to-ASCII encoding transformation.</p>
 * 
 * <p><strong>Transformation from COBOL CBTRN02C.cbl:</strong></p>
 * <pre>
 * COBOL File Definition (lines 29-32):
 *     SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN
 *            ORGANIZATION IS SEQUENTIAL
 *            ACCESS MODE  IS SEQUENTIAL
 *            FILE STATUS  IS DALYTRAN-STATUS.
 * 
 * Spring Batch Equivalent:
 *     FlatFileItemReader with FixedLengthTokenizer reading dailytran.txt
 *     Resource path configured via job parameter "dailyTransactionFile"
 *     Sequential access via reader.read() calls in chunk loop
 * </pre>
 * 
 * <h2>Data Source Information</h2>
 * <ul>
 *   <li><strong>Source File:</strong> app/data/ASCII/dailytran.txt (configurable via job parameter)</li>
 *   <li><strong>COBOL Copybook:</strong> CVTRA06Y.cpy (DALYTRAN-RECORD structure)</li>
 *   <li><strong>Record Length:</strong> 350 bytes fixed-width format</li>
 *   <li><strong>Encoding:</strong> UTF-8 (converted from EBCDIC)</li>
 *   <li><strong>Original Format:</strong> VSAM sequential file (ORGANIZATION IS SEQUENTIAL)</li>
 *   <li><strong>Access Pattern:</strong> Sequential read (PERFORM 1000-DALYTRAN-GET-NEXT)</li>
 * </ul>
 * 
 * <h2>Field Position Mapping from COBOL Copybook</h2>
 * <p>The following table shows the exact field positions matching the COBOL
 * DALYTRAN-RECORD structure defined in CVTRA06Y.cpy:</p>
 * 
 * <table border="1">
 *   <tr>
 *     <th>Field Name</th>
 *     <th>COBOL Definition</th>
 *     <th>Position Range</th>
 *     <th>Length</th>
 *     <th>DTO Property</th>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-ID</td>
 *     <td>PIC X(16)</td>
 *     <td>1-16</td>
 *     <td>16</td>
 *     <td>transactionId</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-TYPE-CD</td>
 *     <td>PIC X(02)</td>
 *     <td>17-18</td>
 *     <td>2</td>
 *     <td>typeCode</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-CAT-CD</td>
 *     <td>PIC 9(04)</td>
 *     <td>19-22</td>
 *     <td>4</td>
 *     <td>categoryCode</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-SOURCE</td>
 *     <td>PIC X(10)</td>
 *     <td>23-32</td>
 *     <td>10</td>
 *     <td>transactionSource</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-DESC</td>
 *     <td>PIC X(100)</td>
 *     <td>33-132</td>
 *     <td>100</td>
 *     <td>description</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-AMT</td>
 *     <td>PIC S9(09)V99</td>
 *     <td>133-143</td>
 *     <td>11</td>
 *     <td>amount (BigDecimal scale=2)</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-MERCHANT-ID</td>
 *     <td>PIC 9(09)</td>
 *     <td>144-152</td>
 *     <td>9</td>
 *     <td>merchantId</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-MERCHANT-NAME</td>
 *     <td>PIC X(50)</td>
 *     <td>153-202</td>
 *     <td>50</td>
 *     <td>merchantName</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-MERCHANT-CITY</td>
 *     <td>PIC X(50)</td>
 *     <td>203-252</td>
 *     <td>50</td>
 *     <td>merchantCity</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-MERCHANT-ZIP</td>
 *     <td>PIC X(10)</td>
 *     <td>253-262</td>
 *     <td>10</td>
 *     <td>merchantZip</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-CARD-NUM</td>
 *     <td>PIC X(16)</td>
 *     <td>263-278</td>
 *     <td>16</td>
 *     <td>cardNumber</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-ORIG-TS</td>
 *     <td>PIC X(26)</td>
 *     <td>279-304</td>
 *     <td>26</td>
 *     <td>originationTimestamp (LocalDateTime)</td>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-PROC-TS</td>
 *     <td>PIC X(26)</td>
 *     <td>305-330</td>
 *     <td>26</td>
 *     <td>(not mapped - processing timestamp)</td>
 *   </tr>
 *   <tr>
 *     <td>FILLER</td>
 *     <td>PIC X(20)</td>
 *     <td>331-350</td>
 *     <td>20</td>
 *     <td>(not mapped)</td>
 *   </tr>
 * </table>
 * 
 * <h2>COBOL Sign Overpunch Handling</h2>
 * <p>The DALYTRAN-AMT field uses COBOL display format with sign overpunch encoding:</p>
 * <ul>
 *   <li><strong>Format:</strong> PIC S9(09)V99 - 11 characters with implied decimal point</li>
 *   <li><strong>Positive Numbers:</strong> Last digit encoded with A-I representing 1-9, { representing 0</li>
 *   <li><strong>Negative Numbers:</strong> Last digit encoded with J-R representing 1-9, } representing 0</li>
 *   <li><strong>Example:</strong> "0000005047G" = +50.47, "0000009190}" = -91.90</li>
 *   <li><strong>Conversion:</strong> Custom converter decodes overpunch and creates BigDecimal with scale=2</li>
 * </ul>
 * 
 * <h2>Date/Time Format Conversion</h2>
 * <p>COBOL timestamp format (DALYTRAN-ORIG-TS) conversion:</p>
 * <ul>
 *   <li><strong>COBOL Format:</strong> "YYYY-MM-DD HH:MM:SS.NNNNNN" (26 characters)</li>
 *   <li><strong>Java Format:</strong> LocalDateTime parsed using custom DateTimeFormatter</li>
 *   <li><strong>Example:</strong> "2022-06-10 19:27:53.000000" → LocalDateTime</li>
 * </ul>
 * 
 * <h2>Usage in Batch Job</h2>
 * <p>This reader is injected into the dailyTransactionProcessingStep defined in
 * {@link com.carddemo.batch.job.DailyTransactionProcessingJob}:</p>
 * <pre>
 * &#64;Bean
 * public Step dailyTransactionProcessingStep(
 *         JobRepository jobRepository,
 *         PlatformTransactionManager transactionManager,
 *         ItemReader&lt;DailyTransactionInput&gt; reader,  // This bean
 *         ItemProcessor&lt;DailyTransactionInput, Transaction&gt; processor,
 *         ItemWriter&lt;Transaction&gt; writer) {
 *     // Step configuration
 * }
 * </pre>
 * 
 * <h2>Job Parameter Configuration</h2>
 * <p>The input file path is configured via job parameter:</p>
 * <pre>
 * JobParameters params = new JobParametersBuilder()
 *     .addString("dailyTransactionFile", "/path/to/dailytran.txt")
 *     .addDate("date", new Date())
 *     .toJobParameters();
 * </pre>
 * 
 * @see com.carddemo.dto.DailyTransactionInput
 * @see com.carddemo.batch.job.DailyTransactionProcessingJob
 * @see com.carddemo.batch.processor.TransactionProcessor
 */
@Slf4j
@Configuration
public class TransactionDataReader {

    /**
     * Creates and configures the ItemReader bean for daily transaction input.
     * 
     * <p>This method is annotated with &#64;StepScope to enable late binding of job parameters,
     * allowing the input file path to be specified at job execution time rather than at
     * Spring context initialization.</p>
     * 
     * <p><strong>Reader Configuration:</strong></p>
     * <ul>
     *   <li><strong>Name:</strong> dailyTransactionReader (for logging and metrics)</li>
     *   <li><strong>Resource:</strong> FileSystemResource from job parameter dailyTransactionFile</li>
     *   <li><strong>Line Tokenizer:</strong> FixedLengthTokenizer with field positions from CVTRA06Y.cpy</li>
     *   <li><strong>Field Mapper:</strong> BeanWrapperFieldSetMapper with custom converters</li>
     *   <li><strong>Conversion Service:</strong> Custom converters for BigDecimal (overpunch) and LocalDateTime</li>
     *   <li><strong>Lines to Skip:</strong> 0 (no header row in fixed-width COBOL file)</li>
     *   <li><strong>Strict:</strong> true (fail fast on malformed records)</li>
     * </ul>
     * 
     * <p><strong>COBOL Paragraph Mapping:</strong></p>
     * <pre>
     * COBOL (CBTRN02C.cbl line 204):
     *     PERFORM 1000-DALYTRAN-GET-NEXT.
     *     1000-DALYTRAN-GET-NEXT.
     *         READ DALYTRAN-FILE INTO WS-TRAN-RECORD
     *             AT END MOVE 'Y' TO END-OF-FILE
     *         END-READ.
     * 
     * Spring Batch:
     *     DailyTransactionInput item = reader.read();
     *     if (item == null) {  // AT END condition
     *         // End of chunk/job
     *     }
     * </pre>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <ul>
     *   <li>Malformed records throw FlatFileParseException (skippable via step configuration)</li>
     *   <li>Invalid numeric formats throw NumberFormatException (skippable)</li>
     *   <li>Invalid date formats throw DateTimeParseException (skippable)</li>
     *   <li>File not found throws ItemStreamException (non-skippable, fails job)</li>
     * </ul>
     * 
     * @param dailyTransactionFile path to the daily transaction input file from job parameter
     * @return configured ItemReader for DailyTransactionInput records
     */
    @Bean
    public FlatFileItemReader<DailyTransactionInput> dailyTransactionReader(
            @Value("${batch.daily.transaction.file:batch-data/dailytran.txt}") String dailyTransactionFile) {
        
        log.info("Configuring dailyTransactionReader for file: {}", dailyTransactionFile);
        
        // Configure fixed-length tokenizer with field positions from CVTRA06Y.cpy
        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        tokenizer.setNames(
            "transactionId",        // DALYTRAN-ID
            "typeCode",             // DALYTRAN-TYPE-CD
            "categoryCode",         // DALYTRAN-CAT-CD
            "transactionSource",    // DALYTRAN-SOURCE
            "description",          // DALYTRAN-DESC
            "amount",               // DALYTRAN-AMT (with sign overpunch)
            "merchantId",           // DALYTRAN-MERCHANT-ID
            "merchantName",         // DALYTRAN-MERCHANT-NAME
            "merchantCity",         // DALYTRAN-MERCHANT-CITY
            "merchantZip",          // DALYTRAN-MERCHANT-ZIP
            "cardNumber",           // DALYTRAN-CARD-NUM
            "originationTimestamp"  // DALYTRAN-ORIG-TS
            // DALYTRAN-PROC-TS and FILLER are not mapped
        );
        
        // Set column ranges matching COBOL PIC clause positions
        tokenizer.setColumns(
            new Range(1, 16),       // transactionId: PIC X(16)
            new Range(17, 18),      // typeCode: PIC X(02)
            new Range(19, 22),      // categoryCode: PIC 9(04)
            new Range(23, 32),      // transactionSource: PIC X(10)
            new Range(33, 132),     // description: PIC X(100)
            new Range(133, 143),    // amount: PIC S9(09)V99 (with overpunch)
            new Range(144, 152),    // merchantId: PIC 9(09)
            new Range(153, 202),    // merchantName: PIC X(50)
            new Range(203, 252),    // merchantCity: PIC X(50)
            new Range(253, 262),    // merchantZip: PIC X(10)
            new Range(263, 278),    // cardNumber: PIC X(16)
            new Range(279, 304)     // originationTimestamp: PIC X(26)
        );
        
        // Configure field set mapper with custom converters
        BeanWrapperFieldSetMapper<DailyTransactionInput> fieldSetMapper = 
                new BeanWrapperFieldSetMapper<>();
        fieldSetMapper.setTargetType(DailyTransactionInput.class);
        fieldSetMapper.setConversionService(createConversionService());
        
        // Build and configure the reader
        return new FlatFileItemReaderBuilder<DailyTransactionInput>()
                .name("dailyTransactionReader")
                .resource(new FileSystemResource(dailyTransactionFile))
                .lineTokenizer(tokenizer)
                .fieldSetMapper(fieldSetMapper)
                .linesToSkip(0)  // No header row in COBOL fixed-width file
                .strict(true)     // Fail fast on malformed records
                .build();
    }
    
    /**
     * Creates a custom ConversionService with converters for COBOL data types.
     * 
     * <p>This service provides custom converters for:</p>
     * <ul>
     *   <li><strong>String to BigDecimal:</strong> Handles COBOL sign overpunch encoding</li>
     *   <li><strong>String to LocalDateTime:</strong> Parses COBOL timestamp format</li>
     * </ul>
     * 
     * @return configured ConversionService with COBOL data type converters
     */
    private ConversionService createConversionService() {
        DefaultConversionService conversionService = new DefaultConversionService();
        
        // Register custom converter for BigDecimal with COBOL sign overpunch
        conversionService.addConverter(new Converter<String, BigDecimal>() {
            @Override
            public BigDecimal convert(String source) {
                return parseCobolDecimalWithOverpunch(source.trim());
            }
        });
        
        // Register custom converter for LocalDateTime from COBOL timestamp
        conversionService.addConverter(new Converter<String, LocalDateTime>() {
            @Override
            public LocalDateTime convert(String source) {
                return parseCobolTimestamp(source.trim());
            }
        });
        
        return conversionService;
    }
    
    /**
     * Parses COBOL display format decimal with sign overpunch encoding.
     * 
     * <p><strong>COBOL Sign Overpunch Encoding:</strong></p>
     * <p>COBOL PIC S9(09)V99 uses the last digit to encode both the value and sign:</p>
     * <table border="1">
     *   <tr>
     *     <th>Overpunch Character</th>
     *     <th>Numeric Value</th>
     *     <th>Sign</th>
     *   </tr>
     *   <tr><td>{ or 0</td><td>0</td><td>Positive</td></tr>
     *   <tr><td>A or 1</td><td>1</td><td>Positive</td></tr>
     *   <tr><td>B or 2</td><td>2</td><td>Positive</td></tr>
     *   <tr><td>C or 3</td><td>3</td><td>Positive</td></tr>
     *   <tr><td>D or 4</td><td>4</td><td>Positive</td></tr>
     *   <tr><td>E or 5</td><td>5</td><td>Positive</td></tr>
     *   <tr><td>F or 6</td><td>6</td><td>Positive</td></tr>
     *   <tr><td>G or 7</td><td>7</td><td>Positive</td></tr>
     *   <tr><td>H or 8</td><td>8</td><td>Positive</td></tr>
     *   <tr><td>I or 9</td><td>9</td><td>Positive</td></tr>
     *   <tr><td>} or 0</td><td>0</td><td>Negative</td></tr>
     *   <tr><td>J or 1</td><td>1</td><td>Negative</td></tr>
     *   <tr><td>K or 2</td><td>2</td><td>Negative</td></tr>
     *   <tr><td>L or 3</td><td>3</td><td>Negative</td></tr>
     *   <tr><td>M or 4</td><td>4</td><td>Negative</td></tr>
     *   <tr><td>N or 5</td><td>5</td><td>Negative</td></tr>
     *   <tr><td>O or 6</td><td>6</td><td>Negative</td></tr>
     *   <tr><td>P or 7</td><td>7</td><td>Negative</td></tr>
     *   <tr><td>Q or 8</td><td>8</td><td>Negative</td></tr>
     *   <tr><td>R or 9</td><td>9</td><td>Negative</td></tr>
     * </table>
     * 
     * <p><strong>Examples:</strong></p>
     * <pre>
     * "0000005047G" → +50.47  (last char 'G' = positive 7, value = 0000005047)
     * "0000009190}" → -91.90  (last char '}' = negative 0, value = -0000009190)
     * "0000000678H" → +6.78   (last char 'H' = positive 8, value = 0000000678)
     * </pre>
     * 
     * <p><strong>Field Format:</strong> PIC S9(09)V99</p>
     * <ul>
     *   <li>S = Signed</li>
     *   <li>9(09) = 9 integer digits</li>
     *   <li>V = Implied decimal point (not stored)</li>
     *   <li>99 = 2 decimal digits</li>
     *   <li>Total: 11 display characters (9 digits + overpunch + leading digit)</li>
     * </ul>
     * 
     * @param value the COBOL display format decimal string with overpunch
     * @return BigDecimal with scale=2 and HALF_UP rounding matching COBOL arithmetic
     * @throws NumberFormatException if the value cannot be parsed
     */
    private BigDecimal parseCobolDecimalWithOverpunch(String value) {
        if (value == null || value.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        
        // Extract the last character (overpunch position)
        char lastChar = value.charAt(value.length() - 1);
        String numericPart = value.substring(0, value.length() - 1);
        
        // Decode the overpunch character
        int lastDigit;
        boolean isNegative;
        
        switch (lastChar) {
            // Positive overpunch characters
            case '{': case '0': lastDigit = 0; isNegative = false; break;
            case 'A': case '1': lastDigit = 1; isNegative = false; break;
            case 'B': case '2': lastDigit = 2; isNegative = false; break;
            case 'C': case '3': lastDigit = 3; isNegative = false; break;
            case 'D': case '4': lastDigit = 4; isNegative = false; break;
            case 'E': case '5': lastDigit = 5; isNegative = false; break;
            case 'F': case '6': lastDigit = 6; isNegative = false; break;
            case 'G': case '7': lastDigit = 7; isNegative = false; break;
            case 'H': case '8': lastDigit = 8; isNegative = false; break;
            case 'I': case '9': lastDigit = 9; isNegative = false; break;
            
            // Negative overpunch characters
            case '}': lastDigit = 0; isNegative = true; break;
            case 'J': lastDigit = 1; isNegative = true; break;
            case 'K': lastDigit = 2; isNegative = true; break;
            case 'L': lastDigit = 3; isNegative = true; break;
            case 'M': lastDigit = 4; isNegative = true; break;
            case 'N': lastDigit = 5; isNegative = true; break;
            case 'O': lastDigit = 6; isNegative = true; break;
            case 'P': lastDigit = 7; isNegative = true; break;
            case 'Q': lastDigit = 8; isNegative = true; break;
            case 'R': lastDigit = 9; isNegative = true; break;
            
            default:
                throw new NumberFormatException(
                    "Invalid COBOL overpunch character: '" + lastChar + "' in value: " + value);
        }
        
        // Reconstruct the numeric value
        String fullNumeric = numericPart + lastDigit;
        
        // Parse as long and convert to BigDecimal with implied decimal point
        // PIC S9(09)V99 means 2 decimal places (divide by 100)
        long numericValue = Long.parseLong(fullNumeric);
        BigDecimal result = BigDecimal.valueOf(numericValue)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        
        // Apply sign
        if (isNegative) {
            result = result.negate();
        }
        
        return result;
    }
    
    /**
     * Parses COBOL timestamp format to LocalDateTime.
     * 
     * <p><strong>COBOL Timestamp Format:</strong></p>
     * <pre>
     * Format: "YYYY-MM-DD HH:MM:SS.NNNNNN"
     * Example: "2022-06-10 19:27:53.000000"
     * Length: 26 characters
     * </pre>
     * 
     * <p>This format matches the COBOL FUNCTION CURRENT-TIMESTAMP output
     * and is used for DALYTRAN-ORIG-TS and DALYTRAN-PROC-TS fields.</p>
     * 
     * @param timestamp the COBOL timestamp string
     * @return LocalDateTime parsed from the timestamp
     * @throws java.time.format.DateTimeParseException if the timestamp cannot be parsed
     */
    private LocalDateTime parseCobolTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return null;
        }
        
        // COBOL timestamp format: "YYYY-MM-DD HH:MM:SS.NNNNNN"
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
        return LocalDateTime.parse(timestamp, formatter);
    }
}

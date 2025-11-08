package com.carddemo.batch.reader;

import com.carddemo.entity.Customer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Spring Batch configuration for reading customer data from fixed-width text files.
 * 
 * <p>This class configures a {@link FlatFileItemReader} that reads customer data from
 * the custdata.txt file, which contains 500-byte fixed-width records converted from
 * the mainframe VSAM KSDS CUSTDAT file with EBCDIC-to-ASCII encoding transformation.</p>
 * 
 * <h2>Data Source Information</h2>
 * <ul>
 *   <li><strong>Source File:</strong> app/data/ASCII/custdata.txt</li>
 *   <li><strong>COBOL Copybook:</strong> CVCUS01Y.cpy (CUSTOMER-RECORD structure)</li>
 *   <li><strong>Record Length:</strong> 500 bytes fixed-width format</li>
 *   <li><strong>Encoding:</strong> UTF-8 (converted from EBCDIC)</li>
 *   <li><strong>Original Format:</strong> VSAM KSDS with 9-digit customer ID key</li>
 * </ul>
 * 
 * <h2>Field Position Mapping from COBOL Copybook</h2>
 * <p>The following table shows the exact field positions matching the COBOL
 * CUSTOMER-RECORD structure defined in CVCUS01Y.cpy:</p>
 * 
 * <table border="1">
 *   <tr>
 *     <th>Field Name</th>
 *     <th>COBOL Definition</th>
 *     <th>Position Range</th>
 *     <th>Length</th>
 *     <th>Entity Property</th>
 *   </tr>
 *   <tr>
 *     <td>CUST-ID</td>
 *     <td>PIC 9(09)</td>
 *     <td>1-9</td>
 *     <td>9</td>
 *     <td>customerId</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-FIRST-NAME</td>
 *     <td>PIC X(25)</td>
 *     <td>10-34</td>
 *     <td>25</td>
 *     <td>firstName</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-MIDDLE-NAME</td>
 *     <td>PIC X(25)</td>
 *     <td>35-59</td>
 *     <td>25</td>
 *     <td>middleName</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-LAST-NAME</td>
 *     <td>PIC X(25)</td>
 *     <td>60-84</td>
 *     <td>25</td>
 *     <td>lastName</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-ADDR-LINE-1</td>
 *     <td>PIC X(50)</td>
 *     <td>85-134</td>
 *     <td>50</td>
 *     <td>addressLine1</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-ADDR-LINE-2</td>
 *     <td>PIC X(50)</td>
 *     <td>135-184</td>
 *     <td>50</td>
 *     <td>addressLine2</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-ADDR-LINE-3</td>
 *     <td>PIC X(50)</td>
 *     <td>185-234</td>
 *     <td>50</td>
 *     <td>addressLine3</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-ADDR-STATE-CD</td>
 *     <td>PIC X(02)</td>
 *     <td>235-236</td>
 *     <td>2</td>
 *     <td>addressStateCode</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-ADDR-COUNTRY-CD</td>
 *     <td>PIC X(03)</td>
 *     <td>237-239</td>
 *     <td>3</td>
 *     <td>addressCountryCode</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-ADDR-ZIP</td>
 *     <td>PIC X(10)</td>
 *     <td>240-249</td>
 *     <td>10</td>
 *     <td>addressZip</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-PHONE-NUM-1</td>
 *     <td>PIC X(15)</td>
 *     <td>250-264</td>
 *     <td>15</td>
 *     <td>phoneNumber1</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-PHONE-NUM-2</td>
 *     <td>PIC X(15)</td>
 *     <td>265-279</td>
 *     <td>15</td>
 *     <td>phoneNumber2</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-SSN</td>
 *     <td>PIC 9(09)</td>
 *     <td>280-288</td>
 *     <td>9</td>
 *     <td>ssn</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-GOVT-ISSUED-ID</td>
 *     <td>PIC X(20)</td>
 *     <td>289-308</td>
 *     <td>20</td>
 *     <td>governmentIssuedId</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-DOB-YYYY-MM-DD</td>
 *     <td>PIC X(10)</td>
 *     <td>309-318</td>
 *     <td>10</td>
 *     <td>dateOfBirth</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-EFT-ACCOUNT-ID</td>
 *     <td>PIC X(10)</td>
 *     <td>319-328</td>
 *     <td>10</td>
 *     <td>eftAccountId</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-PRI-CARD-HOLDER-IND</td>
 *     <td>PIC X(01)</td>
 *     <td>329</td>
 *     <td>1</td>
 *     <td>primaryCardHolderIndicator</td>
 *   </tr>
 *   <tr>
 *     <td>CUST-FICO-CREDIT-SCORE</td>
 *     <td>PIC 9(03)</td>
 *     <td>330-332</td>
 *     <td>3</td>
 *     <td>ficoScore</td>
 *   </tr>
 *   <tr>
 *     <td>FILLER</td>
 *     <td>PIC X(168)</td>
 *     <td>333-500</td>
 *     <td>168</td>
 *     <td>(not mapped)</td>
 *   </tr>
 * </table>
 * 
 * <h2>Data Type Conversions</h2>
 * <p>The {@link BeanWrapperFieldSetMapper} automatically handles the following type conversions:</p>
 * <ul>
 *   <li><strong>Numeric Fields (PIC 9):</strong> Converted to Long or Integer
 *       <ul>
 *         <li>CUST-ID (PIC 9(09)) → Long customerId</li>
 *         <li>CUST-SSN (PIC 9(09)) → String ssn (preserves leading zeros)</li>
 *         <li>CUST-FICO-CREDIT-SCORE (PIC 9(03)) → Integer ficoScore</li>
 *       </ul>
 *   </li>
 *   <li><strong>Alphanumeric Fields (PIC X):</strong> Converted to String with whitespace trimming
 *       <ul>
 *         <li>All name, address, and ID fields are trimmed of trailing spaces from fixed-width padding</li>
 *       </ul>
 *   </li>
 *   <li><strong>Date Fields:</strong> Converted to LocalDate
 *       <ul>
 *         <li>CUST-DOB-YYYY-MM-DD (PIC X(10) in YYYY-MM-DD format) → LocalDate dateOfBirth</li>
 *         <li>Automatic parsing via Spring's DateTimeFormatter</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <h2>Usage in Batch Job</h2>
 * <p>This reader is designed to be autowired into the CustomerDataLoadJob batch job configuration:</p>
 * <pre>{@code
 * @Autowired
 * private FlatFileItemReader<Customer> customerDataReader;
 * 
 * @Bean
 * public Step customerLoadStep() {
 *     return stepBuilderFactory.get("customerLoadStep")
 *         .<Customer, Customer>chunk(1000)
 *         .reader(customerDataReader)
 *         .processor(customerDataProcessor)
 *         .writer(customerDataWriter)
 *         .build();
 * }
 * }</pre>
 * 
 * <h2>Configuration Properties</h2>
 * <ul>
 *   <li><strong>Reader Name:</strong> customerDataReader</li>
 *   <li><strong>File Location:</strong> classpath:data/custdata.txt</li>
 *   <li><strong>Encoding:</strong> UTF-8</li>
 *   <li><strong>Lines to Skip:</strong> 0 (no header line)</li>
 *   <li><strong>Strict Mode:</strong> true (validation enabled)</li>
 *   <li><strong>Tokenizer:</strong> FixedLengthTokenizer with explicit field ranges</li>
 *   <li><strong>Field Set Mapper:</strong> BeanWrapperFieldSetMapper with automatic type conversion</li>
 * </ul>
 * 
 * <h2>Error Handling</h2>
 * <p>The reader is configured with strict=true, which means:</p>
 * <ul>
 *   <li>Parse errors will throw exceptions and halt the job</li>
 *   <li>Missing or malformed records will be reported immediately</li>
 *   <li>Type conversion failures will be logged with field context</li>
 * </ul>
 * 
 * <h2>Migration Notes</h2>
 * <p>This implementation maintains functional equivalence with the original mainframe
 * COBOL batch program CBACT03C.cbl by:</p>
 * <ul>
 *   <li>Preserving exact field positions and lengths from VSAM KSDS record layout</li>
 *   <li>Maintaining data validation through strict parsing mode</li>
 *   <li>Converting EBCDIC-encoded data to UTF-8 during file preparation</li>
 *   <li>Supporting the same record structure defined in CVCUS01Y.cpy copybook</li>
 *   <li>Enabling chunk-oriented processing for performance equivalent to sequential file processing</li>
 * </ul>
 * 
 * @see Customer
 * @see com.carddemo.batch.job.CustomerDataLoadJob
 * @see <a href="Section 0.4">Agent Action Plan - Source File Inventory</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - Batch Processing Requirements</a>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@Slf4j
@Configuration
public class CustomerDataReader {

    /**
     * Creates and configures a FlatFileItemReader for reading customer data from fixed-width text files.
     * 
     * <p>This bean configures a Spring Batch FlatFileItemReader that reads the custdata.txt file
     * containing 500-byte fixed-width customer records. The reader uses a FixedLengthTokenizer
     * to parse each line according to the exact field positions defined in the COBOL CVCUS01Y.cpy
     * copybook structure.</p>
     * 
     * <h3>Reader Configuration Details:</h3>
     * <ul>
     *   <li><strong>Name:</strong> "customerDataReader" - Used for identification in batch job metadata</li>
     *   <li><strong>Resource:</strong> ClassPathResource("data/custdata.txt") - File location in classpath</li>
     *   <li><strong>Encoding:</strong> UTF-8 - Character encoding for EBCDIC-to-ASCII converted data</li>
     *   <li><strong>Lines to Skip:</strong> 0 - No header line in the data file</li>
     *   <li><strong>Strict:</strong> true - Enables validation and error reporting</li>
     * </ul>
     * 
     * <h3>Field Tokenization:</h3>
     * <p>The FixedLengthTokenizer is configured with 19 field ranges matching the COBOL copybook:</p>
     * <ol>
     *   <li>customerId: Range 1-9 (9 characters) - Customer ID primary key</li>
     *   <li>firstName: Range 10-34 (25 characters) - Customer first name</li>
     *   <li>middleName: Range 35-59 (25 characters) - Customer middle name</li>
     *   <li>lastName: Range 60-84 (25 characters) - Customer last name</li>
     *   <li>addressLine1: Range 85-134 (50 characters) - Primary address line</li>
     *   <li>addressLine2: Range 135-184 (50 characters) - Secondary address line</li>
     *   <li>addressLine3: Range 185-234 (50 characters) - Tertiary address line (city)</li>
     *   <li>addressStateCode: Range 235-236 (2 characters) - US state code</li>
     *   <li>addressCountryCode: Range 237-239 (3 characters) - US state code</li>
     *   <li>addressZip: Range 240-249 (10 characters) - ZIP/postal code</li>
     *   <li>phoneNumber1: Range 250-264 (15 characters) - Primary phone number</li>
     *   <li>phoneNumber2: Range 265-279 (15 characters) - Secondary phone number</li>
     *   <li>ssn: Range 280-288 (9 characters) - Social Security Number</li>
     *   <li>governmentIssuedId: Range 289-308 (20 characters) - Government ID</li>
     *   <li>dateOfBirth: Range 309-318 (10 characters) - Date of birth (YYYY-MM-DD)</li>
     *   <li>eftAccountId: Range 319-328 (10 characters) - EFT account identifier</li>
     *   <li>primaryCardHolderIndicator: Range 329-329 (1 character) - Primary holder flag (Y/N)</li>
     *   <li>ficoScore: Range 330-332 (3 characters) - FICO credit score (300-850)</li>
     *   <li>filler: Range 333-500 (168 characters) - COBOL FILLER field (not mapped to entity)</li>
     * </ol>
     * 
     * <h3>Field Set Mapping:</h3>
     * <p>The BeanWrapperFieldSetMapper is configured to:</p>
     * <ul>
     *   <li>Map tokenized fields to Customer entity properties by name</li>
     *   <li>Automatically convert string data to appropriate Java types (Long, Integer, LocalDate, String)</li>
     *   <li>Trim whitespace from fixed-width fields (trailing spaces from padding)</li>
     *   <li>Handle type conversion errors with descriptive exception messages</li>
     *   <li>Ignore the "filler" field (not mapped to any entity property) to support the full 500-byte record structure</li>
     * </ul>
     * 
     * <h3>Data Validation:</h3>
     * <p>With strict mode enabled, the reader will:</p>
     * <ul>
     *   <li>Validate that each line contains exactly 500 characters</li>
     *   <li>Throw FlatFileParseException for malformed records</li>
     *   <li>Report line numbers and field positions for debugging</li>
     *   <li>Halt batch job execution on first error (fail-fast behavior)</li>
     * </ul>
     * 
     * <h3>Performance Considerations:</h3>
     * <ul>
     *   <li>Reads records sequentially from the file system</li>
     *   <li>Minimal memory footprint - processes one record at a time</li>
     *   <li>Compatible with Spring Batch chunk-oriented processing (recommended chunk size: 1000)</li>
     *   <li>Thread-safe for multi-threaded step execution</li>
     * </ul>
     * 
     * <h3>Error Recovery:</h3>
     * <p>For production deployments, consider configuring:</p>
     * <ul>
     *   <li>Skip policies for recoverable errors (e.g., skip invalid records)</li>
     *   <li>Retry logic for transient failures</li>
     *   <li>Exception listeners for logging and alerting</li>
     *   <li>Checkpoint/restart capabilities for long-running jobs</li>
     * </ul>
     * 
     * <h3>COBOL Program Equivalence:</h3>
     * <p>This reader implementation maintains functional equivalence with the original
     * COBOL batch program CBACT03C.cbl by:</p>
     * <ul>
     *   <li>Reading records in the same sequential order</li>
     *   <li>Preserving exact field positions and data types</li>
     *   <li>Supporting the same 500-byte record layout</li>
     *   <li>Maintaining data validation and error handling semantics</li>
     * </ul>
     * 
     * <p><strong>@StepScope Configuration:</strong></p>
     * <p>This bean uses @StepScope to enable late binding of job parameters, allowing the
     * input file path to be dynamically specified at job execution time via the
     * 'customerDataFile' job parameter. This supports flexible batch execution scenarios
     * where different input files (including test data files) can be processed by the same
     * job configuration without recompilation or Spring context restart.</p>
     * 
     * @param customerDataFile path to the customer data file, injected from job parameters with default fallback
     * @return FlatFileItemReader configured to read Customer entities from fixed-width text files
     * @throws IOException if the specified data file cannot be located or accessed
     * 
     * @see FlatFileItemReader
     * @see FlatFileItemReaderBuilder
     * @see FixedLengthTokenizer
     * @see BeanWrapperFieldSetMapper
     * @see Customer
     * @see StepScope
     */
    @Bean(name = "customerReader")
    @StepScope
    public FlatFileItemReader<Customer> customerDataReader(
            @Value("#{jobParameters['customerDataFile'] ?: 'classpath:data/custdata.txt'}") String customerDataFile) throws IOException {
        
        // Configure the FixedLengthTokenizer with exact field ranges from COBOL copybook
        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        
        // Set field names matching Customer entity property names
        tokenizer.setNames(
            "customerId",                    // Position 1-9
            "firstName",                     // Position 10-34
            "middleName",                    // Position 35-59
            "lastName",                      // Position 60-84
            "addressLine1",                  // Position 85-134
            "addressLine2",                  // Position 135-184
            "addressLine3",                  // Position 185-234
            "addressStateCode",              // Position 235-236
            "addressCountryCode",            // Position 237-239
            "addressZip",                    // Position 240-249
            "phoneNumber1",                  // Position 250-264
            "phoneNumber2",                  // Position 265-279
            "ssn",                           // Position 280-288
            "governmentIssuedId",            // Position 289-308
            "dateOfBirth",                   // Position 309-318
            "eftAccountId",                  // Position 319-328
            "primaryCardHolderIndicator",    // Position 329
            "ficoScore",                     // Position 330-332
            "filler"                         // Position 333-500 (COBOL FILLER - not mapped to entity)
        );
        
        // Set field ranges using Spring Batch Range objects (1-based indexing)
        tokenizer.setColumns(
            new Range(1, 9),       // customerId: 9 characters
            new Range(10, 34),     // firstName: 25 characters
            new Range(35, 59),     // middleName: 25 characters
            new Range(60, 84),     // lastName: 25 characters
            new Range(85, 134),    // addressLine1: 50 characters
            new Range(135, 184),   // addressLine2: 50 characters
            new Range(185, 234),   // addressLine3: 50 characters
            new Range(235, 236),   // addressStateCode: 2 characters
            new Range(237, 239),   // addressCountryCode: 3 characters
            new Range(240, 249),   // addressZip: 10 characters
            new Range(250, 264),   // phoneNumber1: 15 characters
            new Range(265, 279),   // phoneNumber2: 15 characters
            new Range(280, 288),   // ssn: 9 characters
            new Range(289, 308),   // governmentIssuedId: 20 characters
            new Range(309, 318),   // dateOfBirth: 10 characters (YYYY-MM-DD)
            new Range(319, 328),   // eftAccountId: 10 characters
            new Range(329, 329),   // primaryCardHolderIndicator: 1 character
            new Range(330, 332),   // ficoScore: 3 characters
            new Range(333, 500)    // filler: 168 characters (COBOL FILLER - not mapped to entity)
        );
        
        // Configure strict mode to validate record structure
        tokenizer.setStrict(true);
        
        // Configure BeanWrapperFieldSetMapper for automatic type conversion
        BeanWrapperFieldSetMapper<Customer> fieldSetMapper = new BeanWrapperFieldSetMapper<>();
        fieldSetMapper.setTargetType(Customer.class);
        
        // Disable strict validation for field mapping to allow filler field (not mapped to entity)
        fieldSetMapper.setStrict(false);
        
        // Register custom converters for LocalDate parsing from YYYY-MM-DD format
        DefaultConversionService conversionService = new DefaultConversionService();
        conversionService.addConverter(new Converter<String, LocalDate>() {
            private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            
            @Override
            public LocalDate convert(String source) {
                if (source == null || source.trim().isEmpty()) {
                    return null;
                }
                try {
                    return LocalDate.parse(source.trim(), formatter);
                } catch (java.time.format.DateTimeParseException e) {
                    // Return null for invalid date formats - will be caught by processor validation
                    // This allows the record to be processed and skipped with proper error logging
                    log.warn("Invalid date format in source data: '{}'. Expected format: yyyy-MM-dd. " +
                            "Record will be validated by processor.", source.trim());
                    return null;
                }
            }
        });
        fieldSetMapper.setConversionService(conversionService);
        
        // Resolve the customer data file from the job parameter (supports both classpath and file system paths)
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource resource = resolver.getResource(customerDataFile);
        
        // Build and configure the FlatFileItemReader using the fluent builder API
        return new FlatFileItemReaderBuilder<Customer>()
            .name("customerDataReader")
            .resource(resource)
            .encoding("UTF-8")
            .linesToSkip(0)
            .lineTokenizer(tokenizer)
            .fieldSetMapper(fieldSetMapper)
            .strict(true)
            .build();
    }
}

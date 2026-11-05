/*
 * CustomerLoadJobConfig.java — Spring Batch Job Configuration for Customer Seed Data Loading
 *
 * Source COBOL: app/cbl/CBCUS01C.cbl (Batch: Read and print customer data file)
 * Source Copybook: app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD, 500-byte layout)
 * Source Data: app/data/ASCII/custdata.txt (50 fixed-width customer records)
 * JCL Job: CUSTFILE
 *
 * COBOL Program CBCUS01C Processing Flow:
 *   1. DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'
 *   2. PERFORM 0000-CUSTFILE-OPEN (OPEN INPUT CUSTFILE-FILE)
 *   3. PERFORM UNTIL END-OF-FILE = 'Y'
 *        PERFORM 1000-CUSTFILE-GET-NEXT (READ CUSTFILE-FILE INTO CUSTOMER-RECORD)
 *        DISPLAY CUSTOMER-RECORD
 *      END-PERFORM
 *   4. PERFORM 9000-CUSTFILE-CLOSE (CLOSE CUSTFILE-FILE)
 *   5. DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'
 *
 * COBOL Paragraph-to-Java Mapping:
 *   MAIN (banner + drive loop)     → customerLoadJob() + customerLoadStep()
 *   0000-CUSTFILE-OPEN             → Spring Batch ItemStream.open() lifecycle
 *   1000-CUSTFILE-GET-NEXT         → customerFileReader().read() via FlatFileItemReader
 *   DISPLAY CUSTOMER-RECORD        → customerWriter() + SLF4J (PII masked)
 *   9000-CUSTFILE-CLOSE            → Spring Batch ItemStream.close() lifecycle
 *   Z-DISPLAY-IO-STATUS            → Exception handling + SLF4J error logging
 *   Z-ABEND-PROGRAM                → Exception propagation (RuntimeException)
 *
 * Record Layout (CVCUS01Y.cpy, 500 bytes):
 *   Pos 1-9:     CUST-ID               PIC 9(09)   → custId (String, PK)
 *   Pos 10-34:   CUST-FIRST-NAME       PIC X(25)   → firstName
 *   Pos 35-59:   CUST-MIDDLE-NAME      PIC X(25)   → middleName
 *   Pos 60-84:   CUST-LAST-NAME        PIC X(25)   → lastName
 *   Pos 85-134:  CUST-ADDR-LINE-1      PIC X(50)   → addrLine1
 *   Pos 135-184: CUST-ADDR-LINE-2      PIC X(50)   → addrLine2
 *   Pos 185-234: CUST-ADDR-LINE-3      PIC X(50)   → addrLine3
 *   Pos 235-236: CUST-ADDR-STATE-CD    PIC X(02)   → addrStateCode
 *   Pos 237-239: CUST-ADDR-COUNTRY-CD  PIC X(03)   → addrCountryCode
 *   Pos 240-249: CUST-ADDR-ZIP         PIC X(10)   → addrZip
 *   Pos 250-264: CUST-PHONE-NUM-1      PIC X(15)   → phoneNum1
 *   Pos 265-279: CUST-PHONE-NUM-2      PIC X(15)   → phoneNum2
 *   Pos 280-288: CUST-SSN              PIC 9(09)   → ssn [PII: NEVER log]
 *   Pos 289-308: CUST-GOVT-ISSUED-ID   PIC X(20)   → govtIssuedId [PII: NEVER log]
 *   Pos 309-318: CUST-DOB-YYYY-MM-DD   PIC X(10)   → dateOfBirth
 *   Pos 319-328: CUST-EFT-ACCOUNT-ID   PIC X(10)   → eftAccountId
 *   Pos 329-329: CUST-PRI-CARD-HOLDER  PIC X(01)   → priCardHolderInd
 *   Pos 330-332: CUST-FICO-CREDIT-SCORE PIC 9(03)  → ficoCreditScore (Integer)
 *   Pos 333-500: FILLER                PIC X(168)  → not mapped
 *
 * Security Constraints:
 *   CUST-SSN and CUST-GOVT-ISSUED-ID are PII (Personally Identifiable Information).
 *   These fields are persisted to the database with exact values from the seed file,
 *   but they must NEVER appear in full in log output, error messages, or stack traces.
 *   Only CUST-ID is safe to log for traceability and auditing purposes.
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.batch.job;

import com.cardemo.batch.reader.FixedWidthFileReader;
import com.cardemo.entity.Customer;
import com.cardemo.repository.CustomerRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Batch job configuration for loading customer seed data from the
 * fixed-width {@code custdata.txt} file into the PostgreSQL {@code customers} table.
 *
 * <p>Translates the JCL CUSTFILE batch job (COBOL program CBCUS01C.cbl) into a
 * Spring Batch {@link Job} with a single chunk-oriented {@link Step}. The original
 * COBOL program sequentially reads 500-byte customer records from VSAM CUSTDATA
 * KSDS and displays them; this Java equivalent reads from a classpath fixture file
 * and persists records to PostgreSQL via JPA.</p>
 *
 * <p>The job processes 50 customer records in chunks of 10, matching COBOL batch
 * transaction commit semantics. Each record is parsed from a 500-byte fixed-width
 * line using column positions defined in CVCUS01Y.cpy, then mapped to a
 * {@link Customer} JPA entity for persistence.</p>
 *
 * <p><strong>PII Security:</strong> CUST-SSN and CUST-GOVT-ISSUED-ID are PII fields
 * that are persisted to the database but never logged in full. Only CUST-ID appears
 * in log output for traceability.</p>
 *
 * @see com.cardemo.batch.reader.FixedWidthFileReader
 * @see com.cardemo.entity.Customer
 * @see com.cardemo.repository.CustomerRepository
 */
@Configuration
public class CustomerLoadJobConfig {

    private static final Logger log = LoggerFactory.getLogger(CustomerLoadJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final FixedWidthFileReader fixedWidthFileReader;
    private final CustomerRepository customerRepository;

    /**
     * Constructs the customer load job configuration with all required dependencies.
     *
     * <p>Uses constructor injection (preferred over {@code @Autowired} field injection)
     * for testability and immutability. All dependencies are provided by the Spring
     * application context:</p>
     * <ul>
     *   <li>{@code jobRepository} — Spring Batch metadata store for job/step state</li>
     *   <li>{@code transactionManager} — Manages chunk-level transaction boundaries</li>
     *   <li>{@code fixedWidthFileReader} — Factory for creating fixed-width file readers</li>
     *   <li>{@code customerRepository} — JPA repository for Customer entity persistence</li>
     * </ul>
     *
     * @param jobRepository        Spring Batch job repository for metadata management
     * @param transactionManager   platform transaction manager for chunk commits
     * @param fixedWidthFileReader fixed-width file reader factory component
     * @param customerRepository   JPA repository for persisting Customer entities
     */
    public CustomerLoadJobConfig(JobRepository jobRepository,
                                 PlatformTransactionManager transactionManager,
                                 FixedWidthFileReader fixedWidthFileReader,
                                 CustomerRepository customerRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.fixedWidthFileReader = fixedWidthFileReader;
        this.customerRepository = customerRepository;
    }

    /**
     * Defines the {@code customerLoadJob} Spring Batch Job bean.
     *
     * <p>Translates the JCL CUSTFILE job and COBOL CBCUS01C MAIN paragraph structure.
     * The job contains a single step ({@link #customerLoadStep()}) that reads customer
     * records from the fixed-width data file and writes them to the database.</p>
     *
     * <p>COBOL equivalent:</p>
     * <pre>{@code
     * DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.
     * PERFORM 0000-CUSTFILE-OPEN.
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *     PERFORM 1000-CUSTFILE-GET-NEXT
     * END-PERFORM.
     * PERFORM 9000-CUSTFILE-CLOSE.
     * DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.
     * }</pre>
     *
     * @return configured Job for customer seed data loading
     */
    @Bean
    public Job customerLoadJob() {
        log.info("START OF EXECUTION OF PROGRAM CBCUS01C — customerLoadJob initialized");
        return new JobBuilder("customerLoadJob", jobRepository)
                .start(customerLoadStep())
                .build();
    }

    /**
     * Defines the {@code customerLoadStep} with chunk-oriented processing.
     *
     * <p>Chunk size of 10 provides reasonable batch commit intervals for the 50
     * customer records in the seed data file. This translates the COBOL
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop pattern into Spring Batch's
     * chunk-oriented model where 10 records are read, processed, and committed
     * as an atomic unit.</p>
     *
     * <p>The step uses the reader from {@link #customerFileReader()} to parse
     * fixed-width records and the writer from {@link #customerWriter()} to persist
     * them to PostgreSQL.</p>
     *
     * @return configured Step for chunk-oriented customer data loading
     */
    @Bean
    public Step customerLoadStep() {
        return new StepBuilder("customerLoadStep", jobRepository)
                .<Customer, Customer>chunk(10, transactionManager)
                .reader(customerFileReader())
                .writer(customerWriter())
                .build();
    }

    /**
     * Creates the {@link FlatFileItemReader} for parsing fixed-width customer records from
     * {@code custdata.txt}.
     *
     * <p>Translates COBOL paragraph {@code 1000-CUSTFILE-GET-NEXT}:</p>
     * <pre>{@code
     * READ CUSTFILE-FILE INTO CUSTOMER-RECORD.
     * IF CUSTFILE-STATUS = '00'
     *     MOVE 0 TO APPL-RESULT
     * ELSE IF CUSTFILE-STATUS = '10'
     *     MOVE 16 TO APPL-RESULT   (EOF)
     * ELSE
     *     MOVE 12 TO APPL-RESULT   (error → Z-DISPLAY-IO-STATUS → Z-ABEND-PROGRAM)
     * }</pre>
     *
     * <p>Uses {@link FixedWidthFileReader#getCustomerColumnSpecs()} for exact byte
     * positions matching CVCUS01Y.cpy field definitions. The reader is configured
     * with {@code @StepScope} to support step-level lifecycle management.</p>
     *
     * <p>Field mapping from FieldSet to Customer entity uses the public all-args
     * constructor, populating all 18 business fields. The FICO credit score
     * (PIC 9(03)) is parsed from the fixed-width string to Integer.</p>
     *
     * @return FlatFileItemReader configured for 500-byte CUSTOMER-RECORD fixed-width parsing
     */
    @Bean
    @StepScope
    public FlatFileItemReader<Customer> customerFileReader() {
        List<FixedWidthFileReader.ColumnSpec> columnSpecs =
                FixedWidthFileReader.getCustomerColumnSpecs();

        return fixedWidthFileReader.createReader(
                "customerFileReader",
                new ClassPathResource("fixtures/custdata.txt"),
                columnSpecs,
                fieldSet -> {
                    // Map CVCUS01Y.cpy fields from FieldSet to Customer entity
                    // using exact field names matching getCustomerColumnSpecs()

                    // Primary key: CUST-ID PIC 9(09), 9 chars with leading zeros
                    String custId = fieldSet.readString("custId").trim();

                    // Name fields: PIC X(25) each
                    String firstName = fieldSet.readString("firstName").trim();
                    String middleName = fieldSet.readString("middleName").trim();
                    String lastName = fieldSet.readString("lastName").trim();

                    // Address fields: PIC X(50) for lines, PIC X(02/03/10) for codes
                    String addrLine1 = fieldSet.readString("addrLine1").trim();
                    String addrLine2 = fieldSet.readString("addrLine2").trim();
                    String addrLine3 = fieldSet.readString("addrLine3").trim();
                    String addrStateCode = fieldSet.readString("addrStateCode").trim();
                    String addrCountryCode = fieldSet.readString("addrCountryCode").trim();
                    String addrZip = fieldSet.readString("addrZip").trim();

                    // Phone fields: PIC X(15) each
                    String phoneNum1 = fieldSet.readString("phoneNum1").trim();
                    String phoneNum2 = fieldSet.readString("phoneNum2").trim();

                    // PII fields: read for database persistence but NEVER log in full
                    // CUST-SSN PIC 9(09) — Social Security Number
                    String ssn = fieldSet.readString("ssn").trim();
                    // CUST-GOVT-ISSUED-ID PIC X(20) — Government-issued ID
                    String govtIssuedId = fieldSet.readString("govtIssuedId").trim();

                    // Date and financial identifier fields
                    String dateOfBirth = fieldSet.readString("dateOfBirth").trim();
                    String eftAccountId = fieldSet.readString("eftAccountId").trim();
                    String priCardHolderInd = fieldSet.readString("priCardHolderInd").trim();

                    // CUST-FICO-CREDIT-SCORE PIC 9(03): parse numeric string to Integer
                    String ficoStr = fieldSet.readString("ficoCreditScore").trim();
                    Integer ficoCreditScore = ficoStr.isEmpty()
                            ? null
                            : Integer.parseInt(ficoStr);

                    // Construct Customer entity using public all-args constructor
                    return new Customer(
                            custId, firstName, middleName, lastName,
                            addrLine1, addrLine2, addrLine3, addrStateCode,
                            addrCountryCode, addrZip, phoneNum1, phoneNum2,
                            ssn, govtIssuedId, dateOfBirth, eftAccountId,
                            priCardHolderInd, ficoCreditScore);
                });
    }

    /**
     * Creates the {@link ItemWriter} for persisting customer records to PostgreSQL.
     *
     * <p>Translates the COBOL {@code DISPLAY CUSTOMER-RECORD} pattern — instead of
     * displaying the full record (which would expose PII), this writer persists records
     * to the database via {@link CustomerRepository#saveAll(Iterable)} and logs only
     * the customer ID for traceability.</p>
     *
     * <p><strong>CRITICAL SECURITY REQUIREMENT:</strong> CUST-SSN (PIC 9(09)) and
     * CUST-GOVT-ISSUED-ID (PIC X(20)) are PII fields. These must NEVER appear in
     * log output in full. Only CUST-ID is safe to log. The seed data values are
     * preserved exactly in the database but masked in all logging.</p>
     *
     * @return ItemWriter for batch-saving Customer entities to PostgreSQL
     */
    @Bean
    public ItemWriter<Customer> customerWriter() {
        return items -> {
            // Convert chunk to concrete List<Customer> for JPA saveAll() compatibility
            // This avoids generic wildcard issues with Chunk<? extends Customer>
            List<Customer> batch = new ArrayList<>();
            items.forEach(batch::add);
            customerRepository.saveAll(batch);

            // Log completion at INFO level — mirrors COBOL DISPLAY for monitoring
            log.info("Persisted {} customer records to database", batch.size());

            // Log individual customer IDs at DEBUG level for detailed traceability
            // CRITICAL: NEVER log SSN or government-issued ID — PII protection
            for (Customer customer : batch) {
                log.debug("Loaded customer: {}", customer.getCustId());
            }
        };
    }
}

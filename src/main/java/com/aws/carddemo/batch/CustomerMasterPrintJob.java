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
package com.aws.carddemo.batch;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.repository.CustomerRepository;

/**
 * Spring Batch job configuration that reproduces the legacy batch program
 * {@code legacy/cbl/CBCUS01C.cbl} (source {@code app/cbl/CBCUS01C.cbl}),
 * "Read and print customer data file".
 *
 * <h2>Legacy lineage (CBCUS01C)</h2>
 * <ul>
 *   <li><strong>Sequential ascending-key scan.</strong> The program declares
 *       {@code SELECT CUSTFILE-FILE ASSIGN TO CUSTFILE ORGANIZATION IS INDEXED
 *       ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-CUST-ID} (CBCUS01C L29-L33)
 *       and walks the {@code CUSTFILE} KSDS front-to-back in ascending
 *       {@code FD-CUST-ID} (i.e. {@code CUST-ID}) key order via
 *       {@code READ CUSTFILE-FILE} (CBCUS01C L93). This becomes a paged,
 *       key-ordered query over the {@code customer} table.</li>
 *   <li><strong>Print each record.</strong> For every record read the program
 *       performs {@code DISPLAY CUSTOMER-RECORD} (CBCUS01C L78, L96), emitting
 *       the full 500-byte {@code CVCUS01Y} customer layout. This becomes a
 *       logging {@link ItemWriter} that prints every {@link Customer} field.</li>
 *   <li><strong>Termination and return codes.</strong> A {@code FILE STATUS}
 *       of {@code '10'} (end-of-file) ends the scan cleanly (return code 0,
 *       CBCUS01C L98-L108); any other non-{@code '00'} status triggers
 *       {@code Z-ABEND-PROGRAM} / {@code CALL 'CEE3ABD'} (return code 8,
 *       CBCUS01C L110-L114, L154-L158). Under Spring Batch, exhausting the
 *       reader ends the step normally (COMPLETED), and an I/O failure surfaces
 *       as a step/job failure (FAILED), preserving the caller-visible
 *       clean-versus-abend outcome.</li>
 *   <li><strong>Read-only.</strong> The program never writes {@code CUSTFILE}
 *       (opened {@code OPEN INPUT}, CBCUS01C L120); this job performs no
 *       persistence &mdash; the writer only logs.</li>
 * </ul>
 *
 * <h2>Sensitive PII &mdash; documented security deviation</h2>
 * The legacy {@code DISPLAY CUSTOMER-RECORD} prints the entire record,
 * including the social security number ({@code CUST-SSN}), the government-issued
 * identifier ({@code CUST-GOVT-ISSUED-ID}), and the date of birth
 * ({@code CUST-DOB-YYYY-MM-DD}). Reproducing that verbatim would write sensitive
 * personal information to the logs. Per Technical Specification &sect;0.7.3 (L1)
 * and &sect;0.9.3, those three fields are therefore <strong>masked</strong> in
 * the printed output (rendered as {@value #MASKED_VALUE}) and are never logged
 * in the clear. This is an intentional, documented improvement recorded in
 * {@code docs/decision-log.md}; it changes only what is emitted to the log, not
 * which rows are processed or the order in which they are read, so behavioral
 * parity of the scan itself is preserved.
 *
 * <h2>Spring Batch wiring</h2>
 * <ul>
 *   <li>This class is a plain {@link Configuration}; it deliberately does
 *       <strong>not</strong> use {@code @EnableBatchProcessing}, so Spring
 *       Boot's batch auto-configuration remains active and supplies the
 *       {@link JobRepository} and {@link PlatformTransactionManager}.</li>
 *   <li>Automatic job launching at startup is disabled globally via
 *       {@code spring.batch.job.enabled=false} (see {@code application.yml});
 *       this configuration only defines beans and never launches them.</li>
 *   <li>The {@link CorrelationIdJobListener} (same package) is registered on the
 *       job so every batch log line carries a correlation ID
 *       (Technical Specification &sect;0.9.5).</li>
 * </ul>
 *
 * @see Customer
 * @see CustomerRepository
 * @see CorrelationIdJobListener
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Configuration("customerMasterPrintJobConfig")
public class CustomerMasterPrintJob {

    /**
     * Logger used by the print writer. The name is this configuration class so
     * that the customer-master-print output is attributable to the job that
     * produced it.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomerMasterPrintJob.class);

    /**
     * Chunk (commit-interval) size and reader page size. The scan is read-only,
     * so the chunk boundary governs only how often the reader pages the result
     * set and how often the (no-op) transaction commits. The value matches the
     * page size used by the sibling batch readers for consistency.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Placeholder emitted in place of the value of every sensitive PII field
     * ({@code CUST-SSN}, {@code CUST-GOVT-ISSUED-ID}, {@code CUST-DOB-YYYY-MM-DD}).
     * A fixed token is used so the field's presence is preserved in the printed
     * layout while its value is never revealed &mdash; neither the content nor
     * its length is disclosed.
     */
    private static final String MASKED_VALUE = "****";

    /**
     * Field-name label used for the masked social security number field.
     */
    private static final String SSN_LABEL = "custSsn";

    /**
     * Field-name label used for the masked government-issued identifier field.
     */
    private static final String GOVT_ID_LABEL = "custGovtIssuedId";

    /**
     * Field-name label used for the masked date-of-birth field.
     */
    private static final String DOB_LABEL = "custDob";

    /**
     * Defines the {@code customerMasterPrintJob} batch job (the CBCUS01C
     * equivalent).
     *
     * <p>The job consists of the single {@code customerMasterPrintStep} and
     * registers the {@link CorrelationIdJobListener} so that its logs (and the
     * per-record output of the step) carry a stable correlation ID.</p>
     *
     * @param jobRepository             the Boot-provided batch {@link JobRepository}
     *                                  used to persist job/step metadata
     * @param customerMasterPrintStep   the single chunk-oriented step that reads
     *                                  and prints every customer
     * @param correlationIdJobListener  the cross-cutting listener that publishes
     *                                  a correlation ID into the logging MDC for
     *                                  the duration of the job
     * @return the configured {@link Job}; its bean name is
     *         {@code customerMasterPrintJob}
     */
    @Bean
    public Job customerMasterPrintJob(JobRepository jobRepository,
                                      Step customerMasterPrintStep,
                                      CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder("customerMasterPrintJob", jobRepository)
                .listener(correlationIdJobListener)
                .start(customerMasterPrintStep)
                .build();
    }

    /**
     * Defines the single chunk-oriented step of the customer-master-print job.
     *
     * <p>The step reads every {@link Customer} in ascending {@code custId} order
     * (the KSDS ascending-key parity from CBCUS01C) through the
     * {@link #customerItemReader(CustomerRepository) repository-backed reader}
     * and prints each one through the
     * {@link #customerLoggingItemWriter() logging writer}. The item type is
     * {@code Customer} for both input and output because the writer merely logs
     * the item it received; nothing is transformed or persisted.</p>
     *
     * @param jobRepository        the Boot-provided batch {@link JobRepository}
     * @param transactionManager   the Boot-provided {@link PlatformTransactionManager}
     *                             that brackets each chunk
     * @param customerRepository   the Spring Data repository that backs the
     *                             ascending-key reader
     * @return the configured {@link Step}; its bean name is
     *         {@code customerMasterPrintStep}
     */
    @Bean
    public Step customerMasterPrintStep(JobRepository jobRepository,
                                        PlatformTransactionManager transactionManager,
                                        CustomerRepository customerRepository) {
        return new StepBuilder("customerMasterPrintStep", jobRepository)
                .<Customer, Customer>chunk(CHUNK_SIZE, transactionManager)
                .reader(customerItemReader(customerRepository))
                .writer(customerLoggingItemWriter())
                .build();
    }

    /**
     * Builds the repository-backed reader that streams every customer in
     * ascending primary-key order.
     *
     * <p>Reproducing the CBCUS01C sequential KSDS scan, the reader is backed by
     * {@link CustomerRepository} and drives its inherited
     * {@code findAll(Pageable)} method (Spring Data
     * {@code PagingAndSortingRepository}) with a {@code custId} ascending sort.
     * The reader appends a {@code PageRequest} carrying that sort to the method
     * invocation, so the query is ordered by {@code custId} ascending &mdash;
     * the exact analog of reading {@code CUSTFILE} by ascending
     * {@code FD-CUST-ID}. Paging keeps memory bounded regardless of table size,
     * and save-state makes the scan restartable.</p>
     *
     * @param customerRepository the repository whose {@code findAll(Pageable)}
     *                           method supplies the ordered pages
     * @return a configured, restartable {@link RepositoryItemReader} over
     *         {@link Customer}
     */
    private RepositoryItemReader<Customer> customerItemReader(CustomerRepository customerRepository) {
        return new RepositoryItemReaderBuilder<Customer>()
                .name("customerMasterPrintItemReader")
                .repository(customerRepository)
                .methodName("findAll")
                .sorts(Map.of("custId", Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .saveState(true)
                .build();
    }

    /**
     * Builds the read-only logging writer that prints each customer record.
     *
     * <p>This is the {@code DISPLAY CUSTOMER-RECORD} analog: it emits one INFO
     * log line per customer containing every mapped field in copybook
     * ({@code CVCUS01Y}) order. The three sensitive fields &mdash; SSN,
     * government-issued id, and date of birth &mdash; are masked (see
     * {@link #formatCustomer(Customer)}). The writer performs no persistence,
     * preserving the read-only nature of CBCUS01C.</p>
     *
     * @return an {@link ItemWriter} that logs each {@link Customer} with
     *         sensitive PII masked
     */
    private ItemWriter<Customer> customerLoggingItemWriter() {
        return chunk -> {
            for (Customer customer : chunk) {
                LOGGER.info("CUSTOMER-RECORD | {}", formatCustomer(customer));
            }
        };
    }

    /**
     * Renders a single customer as a human-readable line containing every mapped
     * field in copybook ({@code CVCUS01Y}) order, with the sensitive fields
     * masked.
     *
     * <p>The social security number, government-issued identifier, and date of
     * birth are rendered as {@value #MASKED_VALUE} so their values never reach
     * the logs, while their positions in the printed layout are preserved. All
     * other fields are printed as-is. A {@code null} field is rendered as the
     * literal {@code null}, which reveals no sensitive information for the masked
     * fields because their values are never emitted.</p>
     *
     * @param customer the customer to render; never {@code null}
     * @return a masked, single-line representation of the customer suitable for
     *         logging
     */
    private static String formatCustomer(Customer customer) {
        return new StringBuilder(320)
                .append("custId=").append(customer.getCustId())
                .append(", custFirstName=").append(customer.getCustFirstName())
                .append(", custMiddleName=").append(customer.getCustMiddleName())
                .append(", custLastName=").append(customer.getCustLastName())
                .append(", custAddrLine1=").append(customer.getCustAddrLine1())
                .append(", custAddrLine2=").append(customer.getCustAddrLine2())
                .append(", custAddrLine3=").append(customer.getCustAddrLine3())
                .append(", custAddrStateCd=").append(customer.getCustAddrStateCd())
                .append(", custAddrCountryCd=").append(customer.getCustAddrCountryCd())
                .append(", custAddrZip=").append(customer.getCustAddrZip())
                .append(", custPhoneNum1=").append(customer.getCustPhoneNum1())
                .append(", custPhoneNum2=").append(customer.getCustPhoneNum2())
                .append(", ").append(SSN_LABEL).append('=').append(MASKED_VALUE)
                .append(", ").append(GOVT_ID_LABEL).append('=').append(MASKED_VALUE)
                .append(", ").append(DOB_LABEL).append('=').append(MASKED_VALUE)
                .append(", custEftAccountId=").append(customer.getCustEftAccountId())
                .append(", custPriCardHolderInd=").append(customer.getCustPriCardHolderInd())
                .append(", custFicoCreditScore=").append(customer.getCustFicoCreditScore())
                .toString();
    }
}

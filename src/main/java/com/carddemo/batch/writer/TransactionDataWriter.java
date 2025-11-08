package com.carddemo.batch.writer;

import com.carddemo.entity.Transaction;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.database.builder.JpaItemWriterBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch writer configuration for persisting Transaction entities to PostgreSQL.
 * 
 * <p>This configuration class provides a {@link JpaItemWriter} implementation for writing
 * chunks of Transaction entities to the PostgreSQL transaction table during daily transaction
 * batch processing. It replaces the COBOL batch program CBTRN02C.cbl (Daily Transaction
 * Processing) VSAM WRITE operations with JPA-based persistence.</p>
 * 
 * <h2>COBOL to Spring Batch Transformation</h2>
 * <p>Original COBOL Pattern (CBTRN02C.cbl):</p>
 * <pre>
 * EXEC CICS WRITE
 *     FILE('TRANSACT')
 *     FROM(TRAN-RECORD)
 *     RIDFLD(TRAN-ID)
 *     RESP(WS-RESP-CD)
 * END-EXEC.
 * </pre>
 * 
 * <p>Transformed to Spring Batch Pattern:</p>
 * <pre>
 * {@code
 * @Bean
 * public JpaItemWriter<Transaction> transactionWriter(EntityManagerFactory emf) {
 *     return new JpaItemWriterBuilder<Transaction>()
 *         .entityManagerFactory(emf)
 *         .usePersist(false)  // Use merge() for insert/update flexibility
 *         .build();
 * }
 * }
 * </pre>
 * 
 * <h2>Writer Configuration Details</h2>
 * 
 * <h3>EntityManagerFactory Injection</h3>
 * <p>The EntityManagerFactory is injected to provide the JPA persistence context required
 * for entity lifecycle management. This factory is configured in DatabaseConfig.java with:</p>
 * <ul>
 *   <li>PostgreSQL DataSource with connection pooling (HikariCP)</li>
 *   <li>Hibernate as the JPA provider with PostgreSQL dialect</li>
 *   <li>Batch processing optimizations (hibernate.jdbc.batch_size=20)</li>
 *   <li>Transaction management integration with Spring Batch</li>
 * </ul>
 * 
 * <h3>usePersist(false) Configuration</h3>
 * <p>The {@code usePersist(false)} setting configures the writer to use JPA merge()
 * operations instead of persist(). This design choice provides critical flexibility:</p>
 * 
 * <p><strong>Why merge() instead of persist():</strong></p>
 * <ul>
 *   <li><strong>Insert OR Update Semantics:</strong> merge() handles both new transactions
 *       (inserts) and corrections to existing transactions (updates) in a single operation,
 *       matching VSAM WRITE/REWRITE dual behavior</li>
 *   <li><strong>Detached Entity Handling:</strong> Batch readers often produce detached
 *       entities; merge() properly reattaches them to the persistence context</li>
 *   <li><strong>Idempotent Processing:</strong> If a batch job is restarted after partial
 *       completion, merge() safely handles records that may have been written in a previous
 *       attempt, preventing duplicate key violations</li>
 *   <li><strong>Transaction Correction Scenarios:</strong> Daily batch processing may include
 *       correction records that update previously posted transactions; merge() naturally
 *       supports this without separate logic paths</li>
 * </ul>
 * 
 * <p><strong>Behavior Comparison:</strong></p>
 * <table border="1">
 *   <tr>
 *     <th>Scenario</th>
 *     <th>persist() Behavior</th>
 *     <th>merge() Behavior</th>
 *   </tr>
 *   <tr>
 *     <td>New Transaction</td>
 *     <td>INSERT (success)</td>
 *     <td>INSERT (success)</td>
 *   </tr>
 *   <tr>
 *     <td>Existing Transaction</td>
 *     <td>EntityExistsException</td>
 *     <td>UPDATE (success)</td>
 *   </tr>
 *   <tr>
 *     <td>Detached Entity</td>
 *     <td>Error or undefined</td>
 *     <td>Reattach and UPDATE</td>
 *   </tr>
 *   <tr>
 *     <td>Batch Restart</td>
 *     <td>Fails on duplicates</td>
 *     <td>Continues safely</td>
 *   </tr>
 * </table>
 * 
 * <h2>Chunk Processing and Transaction Boundaries</h2>
 * 
 * <p>Spring Batch chunk-oriented processing divides the input data into fixed-size chunks
 * (typically 100-1000 records) and processes each chunk within a database transaction.
 * This approach provides:</p>
 * 
 * <ul>
 *   <li><strong>Atomic Chunk Commits:</strong> Each chunk of Transaction entities is written
 *       atomically - all succeed or all roll back, maintaining ACID properties equivalent
 *       to CICS transaction management</li>
 *   <li><strong>Checkpoint/Restart Capability:</strong> If the batch job fails mid-processing,
 *       Spring Batch can restart from the last successfully committed chunk, preventing
 *       duplicate processing and data loss</li>
 *   <li><strong>Memory Efficiency:</strong> Processing in chunks prevents loading entire
 *       transaction files into memory, supporting high-volume processing within the 4-hour
 *       batch window requirement</li>
 *   <li><strong>Performance Optimization:</strong> Chunk size tuning balances commit frequency
 *       (too small = overhead, too large = rollback cost) for optimal throughput</li>
 * </ul>
 * 
 * <h3>Transaction Boundary Management</h3>
 * <p>The writer operates within Spring Batch transaction boundaries:</p>
 * <ol>
 *   <li><strong>Chunk Transaction Begin:</strong> Spring Batch starts a new transaction at
 *       the beginning of each chunk processing cycle</li>
 *   <li><strong>Item Reading:</strong> TransactionDataReader reads a chunk of transaction
 *       records from the daily feed file (e.g., 100 records)</li>
 *   <li><strong>Item Processing:</strong> TransactionProcessor validates each transaction,
 *       checks card authorization, and prepares Transaction entities</li>
 *   <li><strong>Item Writing:</strong> JpaItemWriter writes the entire chunk of Transaction
 *       entities via merge() operations</li>
 *   <li><strong>Chunk Transaction Commit:</strong> Spring Batch commits the transaction,
 *       persisting all entities in the chunk atomically</li>
 *   <li><strong>Repeat or Rollback:</strong> Process repeats for next chunk, or rolls back
 *       current chunk on any exception</li>
 * </ol>
 * 
 * <p><strong>CICS Transaction Equivalence:</strong></p>
 * <pre>
 * COBOL CICS Pattern:           Spring Batch Pattern:
 * ==================            =================
 * (implicit transaction start)  → Chunk transaction begin
 * PERFORM READ-TRANSACTION      → ItemReader.read()
 * PERFORM VALIDATE-TRANSACTION  → ItemProcessor.process()
 * EXEC CICS WRITE FILE(...)     → ItemWriter.write()
 * EXEC CICS SYNCPOINT           → Chunk transaction commit
 * EXEC CICS ROLLBACK            → Exception triggers rollback
 * </pre>
 * 
 * <h2>Transaction Entity Persistence</h2>
 * 
 * <p>The writer handles Transaction entities with the following critical fields:</p>
 * <ul>
 *   <li><strong>transactionId:</strong> 16-character primary key (VARCHAR(16)),
 *       generated from sequence number, timestamp, and system identifier</li>
 *   <li><strong>amount:</strong> BigDecimal with precision=12, scale=2, preserving
 *       exact COBOL COMP-3 packed decimal precision (PIC S9(10)V99) for monetary
 *       values up to $999,999,999.99</li>
 *   <li><strong>cardNumber:</strong> Foreign key to Card entity (VARCHAR(16)),
 *       enforcing referential integrity with ON DELETE RESTRICT constraint</li>
 *   <li><strong>merchantId:</strong> 9-digit merchant identifier (BIGINT)</li>
 *   <li><strong>merchantName:</strong> Merchant business name (VARCHAR(50))</li>
 *   <li><strong>merchantCity:</strong> Merchant city location (VARCHAR(50))</li>
 *   <li><strong>merchantZip:</strong> Merchant postal code (VARCHAR(10))</li>
 *   <li><strong>typeCode:</strong> Transaction type code (VARCHAR(2)),
 *       linking to TransactionType reference table</li>
 *   <li><strong>categoryCode:</strong> Transaction category code (VARCHAR(4)),
 *       linking to TransactionCategory reference table</li>
 *   <li><strong>transactionSource:</strong> Source system identifier (VARCHAR(10)),
 *       e.g., 'POS', 'ATM', 'ONLINE', 'BATCH'</li>
 *   <li><strong>description:</strong> Transaction description text (VARCHAR(100))</li>
 *   <li><strong>originationTimestamp:</strong> ISO-8601 timestamp with microseconds
 *       (TIMESTAMP(6)) indicating when transaction was initiated at merchant</li>
 *   <li><strong>processingTimestamp:</strong> ISO-8601 timestamp with microseconds
 *       (TIMESTAMP(6)) indicating when transaction was posted to account</li>
 *   <li><strong>version:</strong> Optimistic locking version field (BIGINT),
 *       preventing lost updates during concurrent access</li>
 * </ul>
 * 
 * <h2>Daily Transaction Feed Processing</h2>
 * 
 * <p>The writer processes positional transaction feed records from the daily batch file
 * (app/data/ASCII/dailytran.txt in source, migrated from VSAM TRANSACT file). Each
 * record is 220 characters with the following structure:</p>
 * 
 * <table border="1">
 *   <tr>
 *     <th>Position</th>
 *     <th>Length</th>
 *     <th>Field</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>1-12</td>
 *     <td>12</td>
 *     <td>Sequence Number</td>
 *     <td>Transaction sequence in batch file</td>
 *   </tr>
 *   <tr>
 *     <td>13-19</td>
 *     <td>7</td>
 *     <td>Merchant/Terminal ID</td>
 *     <td>Originating merchant identifier</td>
 *   </tr>
 *   <tr>
 *     <td>20-26</td>
 *     <td>7</td>
 *     <td>Transaction Code</td>
 *     <td>Transaction type and category codes</td>
 *   </tr>
 *   <tr>
 *     <td>27-95</td>
 *     <td>69</td>
 *     <td>Description</td>
 *     <td>Transaction description text</td>
 *   </tr>
 *   <tr>
 *     <td>96-105</td>
 *     <td>10</td>
 *     <td>Transaction Reference</td>
 *     <td>Unique transaction reference number</td>
 *   </tr>
 *   <tr>
 *     <td>106</td>
 *     <td>1</td>
 *     <td>Type Flag</td>
 *     <td>Transaction type indicator</td>
 *   </tr>
 *   <tr>
 *     <td>107-115</td>
 *     <td>9</td>
 *     <td>Account Code</td>
 *     <td>Associated account identifier</td>
 *   </tr>
 *   <tr>
 *     <td>116-140</td>
 *     <td>25</td>
 *     <td>Merchant Name</td>
 *     <td>Business name of merchant</td>
 *   </tr>
 *   <tr>
 *     <td>141-170</td>
 *     <td>30</td>
 *     <td>City</td>
 *     <td>Merchant city location</td>
 *   </tr>
 *   <tr>
 *     <td>171-176</td>
 *     <td>6</td>
 *     <td>Postal Code</td>
 *     <td>Merchant ZIP/postal code</td>
 *   </tr>
 *   <tr>
 *     <td>177-194</td>
 *     <td>18</td>
 *     <td>Amount Block</td>
 *     <td>Transaction amount with EBCDIC decimal encoding</td>
 *   </tr>
 *   <tr>
 *     <td>195-220</td>
 *     <td>26</td>
 *     <td>ISO-8601 Timestamp</td>
 *     <td>Transaction origination timestamp with microseconds</td>
 *   </tr>
 * </table>
 * 
 * <p>TransactionDataReader parses these positional records into Transaction entities,
 * which are then validated by TransactionProcessor before being persisted by this writer.</p>
 * 
 * <h2>Hibernate Batch Processing Optimizations</h2>
 * 
 * <p>The writer leverages Hibernate batch processing settings configured in
 * application.properties to optimize bulk insert performance:</p>
 * 
 * <pre>
 * # Hibernate batch processing configuration
 * spring.jpa.properties.hibernate.jdbc.batch_size=20
 * spring.jpa.properties.hibernate.order_inserts=true
 * spring.jpa.properties.hibernate.order_updates=true
 * spring.jpa.properties.hibernate.jdbc.batch_versioned_data=true
 * </pre>
 * 
 * <p><strong>Configuration Details:</strong></p>
 * <ul>
 *   <li><strong>batch_size=20:</strong> Hibernate batches up to 20 INSERT/UPDATE statements
 *       into a single round-trip to the database, significantly reducing network overhead
 *       and improving throughput during high-volume transaction loading</li>
 *   <li><strong>order_inserts=true:</strong> Hibernate groups INSERT statements by entity type,
 *       enabling more efficient batching and reducing database statement parsing overhead</li>
 *   <li><strong>order_updates=true:</strong> Similarly optimizes UPDATE statement batching
 *       when merge() operations result in updates to existing transactions</li>
 *   <li><strong>batch_versioned_data=true:</strong> Enables batching for entities with
 *       @Version fields (optimistic locking), ensuring Transaction entity updates are batched</li>
 * </ul>
 * 
 * <p><strong>Performance Impact:</strong></p>
 * <ul>
 *   <li>Without batching: 1 database round-trip per transaction = 10,000 trips for 10,000 records</li>
 *   <li>With batch_size=20: 500 database round-trips for 10,000 records (20x reduction)</li>
 *   <li>Combined with chunk processing (chunk_size=100): Optimal balance of commit frequency
 *       and batch efficiency for the 4-hour processing window requirement</li>
 * </ul>
 * 
 * <h2>Integration with DailyTransactionProcessingJob</h2>
 * 
 * <p>This writer is used as a component in the DailyTransactionProcessingJob Spring Batch
 * job configuration:</p>
 * 
 * <pre>
 * {@code
 * @Bean
 * public Step dailyTransactionStep(
 *     JobRepository jobRepository,
 *     PlatformTransactionManager transactionManager,
 *     ItemReader<Transaction> transactionReader,
 *     ItemProcessor<Transaction, Transaction> transactionProcessor,
 *     ItemWriter<Transaction> transactionWriter) {
 *     
 *     return new StepBuilder("dailyTransactionStep", jobRepository)
 *         .<Transaction, Transaction>chunk(100, transactionManager)
 *         .reader(transactionReader)
 *         .processor(transactionProcessor)
 *         .writer(transactionWriter)  // This JpaItemWriter instance
 *         .faultTolerant()
 *         .skipPolicy(customSkipPolicy())
 *         .build();
 * }
 * }
 * </pre>
 * 
 * <h2>Error Handling and Recovery</h2>
 * 
 * <p>The writer participates in Spring Batch's comprehensive error handling framework:</p>
 * 
 * <ul>
 *   <li><strong>Constraint Violations:</strong> If a Transaction entity violates database
 *       constraints (e.g., foreign key violation due to invalid cardNumber, duplicate
 *       transactionId), JPA throws ConstraintViolationException. Spring Batch can be
 *       configured to skip the problematic record, log the error, and continue processing</li>
 *   <li><strong>Optimistic Lock Failures:</strong> If concurrent modification is detected
 *       (version mismatch), OptimisticLockException is thrown. The job can retry the chunk
 *       with refreshed entity versions</li>
 *   <li><strong>Database Connectivity Issues:</strong> Transient database connection failures
 *       can be handled with Spring Batch retry policies, automatically retrying the chunk
 *       write operation</li>
 *   <li><strong>Transaction Rollback:</strong> Any exception during chunk processing triggers
 *       automatic rollback of the chunk transaction, ensuring no partial writes corrupt the
 *       transaction table</li>
 *   <li><strong>Chunk Restart:</strong> After job failure and restart, Spring Batch's
 *       JobRepository tracks which chunks have been successfully committed, restarting
 *       processing from the next unprocessed chunk</li>
 * </ul>
 * 
 * <h2>VSAM to PostgreSQL Migration Context</h2>
 * 
 * <p>This writer replaces VSAM KSDS file operations from the mainframe CBTRN02C.cbl
 * batch program with equivalent PostgreSQL persistence:</p>
 * 
 * <table border="1">
 *   <tr>
 *     <th>Mainframe Approach</th>
 *     <th>Spring Batch Approach</th>
 *   </tr>
 *   <tr>
 *     <td>VSAM KSDS TRANSACT file</td>
 *     <td>PostgreSQL transaction table</td>
 *   </tr>
 *   <tr>
 *     <td>Sequential file processing</td>
 *     <td>Chunk-oriented batch processing</td>
 *   </tr>
 *   <tr>
 *     <td>COBOL WRITE/REWRITE operations</td>
 *     <td>JPA merge() operations</td>
 *   </tr>
 *   <tr>
 *     <td>VSAM alternate index on card number</td>
 *     <td>PostgreSQL foreign key index on card_number</td>
 *   </tr>
 *   <tr>
 *     <td>EBCDIC character encoding</td>
 *     <td>UTF-8 character encoding</td>
 *   </tr>
 *   <tr>
 *     <td>COMP-3 packed decimal amounts</td>
 *     <td>BigDecimal with precision=12, scale=2</td>
 *   </tr>
 *   <tr>
 *     <td>JCL-driven batch scheduling</td>
 *     <td>Kubernetes CronJob scheduling</td>
 *   </tr>
 *   <tr>
 *     <td>Checkpoint/restart via JCL parameters</td>
 *     <td>Spring Batch JobRepository tracking</td>
 *   </tr>
 * </table>
 * 
 * <h2>Performance Requirements</h2>
 * 
 * <p>This writer must support the following performance characteristics per Section 0.2
 * of the migration specifications:</p>
 * 
 * <ul>
 *   <li><strong>Batch Processing Window:</strong> All daily transaction processing must
 *       complete within the 4-hour batch window, maintaining or exceeding mainframe
 *       throughput</li>
 *   <li><strong>Transaction Volume:</strong> Support for daily transaction volumes
 *       comparable to mainframe capacity, estimated at 100,000+ transactions per day</li>
 *   <li><strong>Peak Load Handling:</strong> System must handle peak online transaction
 *       rates of 10,000 TPS during business hours without impacting batch job performance</li>
 *   <li><strong>Database Response Time:</strong> Individual transaction writes should
 *       complete in milliseconds to support chunk processing efficiency</li>
 *   <li><strong>Resource Utilization:</strong> Memory footprint should remain constant
 *       regardless of total transaction volume due to chunk processing design</li>
 * </ul>
 * 
 * <h2>Testing Considerations</h2>
 * 
 * <p>Unit and integration testing for this writer should verify:</p>
 * <ul>
 *   <li>Successful persistence of Transaction entities with all fields populated correctly</li>
 *   <li>BigDecimal amount precision preserved (12 digits, 2 decimal places)</li>
 *   <li>Foreign key constraint enforcement on card_number field</li>
 *   <li>Merge behavior correctly handling both new and existing transactions</li>
 *   <li>Optimistic locking version field incremented on updates</li>
 *   <li>Transaction boundary management with proper rollback on exceptions</li>
 *   <li>Batch processing efficiency with configured Hibernate batch_size</li>
 *   <li>Chunk restart capability after simulated failures</li>
 * </ul>
 * 
 * <p><strong>Example Test:</strong></p>
 * <pre>
 * {@code
 * @Test
 * public void testTransactionWriterPersistsCorrectly() {
 *     // Arrange
 *     Transaction transaction = Transaction.builder()
 *         .transactionId("TXN20240610001")
 *         .amount(new BigDecimal("1234.56"))
 *         .cardNumber("4000123456789012")
 *         .merchantName("Test Merchant")
 *         .originationTimestamp(LocalDateTime.now())
 *         .build();
 *     
 *     // Act
 *     transactionWriter.write(Chunk.of(transaction));
 *     
 *     // Assert
 *     Transaction saved = transactionRepository.findById("TXN20240610001").orElseThrow();
 *     assertThat(saved.getAmount()).isEqualByComparingTo("1234.56");
 *     assertThat(saved.getAmount().scale()).isEqualTo(2);
 * }
 * }
 * </pre>
 * 
 * <h2>Usage Example</h2>
 * 
 * <p>This configuration class is automatically detected by Spring Boot's component scanning
 * and the transactionWriter bean is made available for injection into batch job steps:</p>
 * 
 * <pre>
 * {@code
 * @Configuration
 * public class DailyTransactionProcessingJobConfig {
 *     
 *     @Bean
 *     public Job dailyTransactionProcessingJob(
 *         JobRepository jobRepository,
 *         Step dailyTransactionStep) {
 *         
 *         return new JobBuilder("dailyTransactionProcessingJob", jobRepository)
 *             .start(dailyTransactionStep)
 *             .build();
 *     }
 *     
 *     @Bean
 *     public Step dailyTransactionStep(
 *         JobRepository jobRepository,
 *         PlatformTransactionManager transactionManager,
 *         ItemWriter<Transaction> transactionWriter) {  // Injected from this class
 *         
 *         return new StepBuilder("dailyTransactionStep", jobRepository)
 *             .<Transaction, Transaction>chunk(100, transactionManager)
 *             .reader(transactionReader())
 *             .processor(transactionProcessor())
 *             .writer(transactionWriter)  // Uses JpaItemWriter from this config
 *             .build();
 *     }
 * }
 * }
 * </pre>
 * 
 * @see Transaction
 * @see JpaItemWriter
 * @see JpaItemWriterBuilder
 * @see EntityManagerFactory
 * @author CardDemo Migration Team
 * @since 1.0.0
 */
@Configuration
@RequiredArgsConstructor
public class TransactionDataWriter {
    
    /**
     * Creates and configures a JpaItemWriter for persisting Transaction entities.
     * 
     * <p>This bean is used by the DailyTransactionProcessingJob Spring Batch job to write
     * chunks of Transaction entities to the PostgreSQL transaction table. The writer uses
     * JPA merge() operations (configured via usePersist(false)) to support both insert and
     * update scenarios, providing flexibility for transaction corrections and idempotent
     * batch processing.</p>
     * 
     * <p><strong>Key Configuration Aspects:</strong></p>
     * <ul>
     *   <li><strong>EntityManagerFactory Dependency:</strong> Injected via method parameter,
     *       providing access to the configured JPA persistence context with PostgreSQL
     *       DataSource, Hibernate dialect, and batch processing settings</li>
     *   <li><strong>Merge Operations (usePersist=false):</strong> Writer uses EntityManager.merge()
     *       instead of persist(), enabling it to handle both new transactions (INSERT) and
     *       corrections to existing transactions (UPDATE) seamlessly</li>
     *   <li><strong>Chunk Transaction Integration:</strong> Writer operates within Spring Batch
     *       chunk transactions, automatically committing after each chunk completes successfully
     *       or rolling back the entire chunk on any exception</li>
     *   <li><strong>Hibernate Batch Optimization:</strong> Leverages configured batch_size=20
     *       to batch multiple database operations, reducing round-trips and improving throughput</li>
     * </ul>
     * 
     * <p><strong>Transaction Lifecycle within Chunk Processing:</strong></p>
     * <ol>
     *   <li>Spring Batch begins chunk transaction</li>
     *   <li>Reader provides chunk of Transaction entities</li>
     *   <li>Processor validates and prepares entities</li>
     *   <li>Writer.write() is called with chunk of entities</li>
     *   <li>JpaItemWriter iterates through chunk, calling EntityManager.merge() for each</li>
     *   <li>Hibernate batches the merge operations (up to batch_size=20 per batch)</li>
     *   <li>Spring Batch commits chunk transaction, flushing all pending operations</li>
     *   <li>On exception at any point, entire chunk transaction rolls back</li>
     * </ol>
     * 
     * <p><strong>Merge vs Persist Decision Matrix:</strong></p>
     * <table border="1">
     *   <tr>
     *     <th>Scenario</th>
     *     <th>Entity State</th>
     *     <th>merge() Result</th>
     *     <th>persist() Result</th>
     *   </tr>
     *   <tr>
     *     <td>New transaction from daily feed</td>
     *     <td>Transient (no ID in DB)</td>
     *     <td>INSERT new row</td>
     *     <td>INSERT new row</td>
     *   </tr>
     *   <tr>
     *     <td>Correction record for existing transaction</td>
     *     <td>Detached (ID exists in DB)</td>
     *     <td>UPDATE existing row</td>
     *     <td>EntityExistsException</td>
     *   </tr>
     *   <tr>
     *     <td>Batch job restart after failure</td>
     *     <td>May be transient or detached</td>
     *     <td>INSERT or UPDATE as needed</td>
     *     <td>Fails on duplicates</td>
     *   </tr>
     *   <tr>
     *     <td>Entity from reader (detached state)</td>
     *     <td>Detached (reader produces)</td>
     *     <td>Reattach and persist</td>
     *     <td>May fail or behave undefined</td>
     *   </tr>
     * </table>
     * 
     * <p><strong>Example Usage in Batch Step:</strong></p>
     * <pre>
     * {@code
     * // In DailyTransactionProcessingJob configuration
     * @Bean
     * public Step processTransactionsStep(
     *     JobRepository jobRepository,
     *     PlatformTransactionManager transactionManager,
     *     JpaItemWriter<Transaction> transactionWriter) {
     *     
     *     return new StepBuilder("processTransactionsStep", jobRepository)
     *         .<Transaction, Transaction>chunk(100, transactionManager)
     *         .reader(transactionReader())
     *         .processor(transactionProcessor())
     *         .writer(transactionWriter)  // This bean injected here
     *         .build();
     * }
     * }
     * </pre>
     * 
     * <p><strong>Error Scenarios and Recovery:</strong></p>
     * <ul>
     *   <li><strong>Foreign Key Violation:</strong> If Transaction references invalid
     *       cardNumber, merge() throws ConstraintViolationException. Spring Batch can
     *       skip the record or fail the chunk based on configured skip policy.</li>
     *   <li><strong>Duplicate Transaction ID:</strong> If batch feed contains duplicate
     *       transaction IDs within same chunk, merge() converts the second occurrence to
     *       an UPDATE, avoiding duplicate key error. Across chunks, last-write-wins.</li>
     *   <li><strong>Optimistic Lock Failure:</strong> If Transaction entity has been modified
     *       by concurrent process, merge() throws OptimisticLockException. Chunk can be
     *       retried with retry policy.</li>
     *   <li><strong>Database Connection Lost:</strong> If database becomes unreachable during
     *       merge(), HibernateException is thrown. Spring Batch can retry chunk with
     *       configured retry policy after connection is restored.</li>
     * </ul>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>With chunk_size=100 and batch_size=20: 5 database batches per chunk</li>
     *   <li>For 10,000 transactions: 100 chunks × 5 batches = 500 database round-trips</li>
     *   <li>Estimated throughput: 2,500-5,000 transactions/minute on typical hardware</li>
     *   <li>Memory footprint: O(chunk_size) = constant memory usage regardless of total volume</li>
     *   <li>Scalability: Linear throughput increase with additional batch processing threads</li>
     * </ul>
     * 
     * <p><strong>Monitoring and Observability:</strong></p>
     * <p>Spring Batch automatically tracks writer metrics accessible via JobRepository:</p>
     * <ul>
     *   <li>Total number of items written</li>
     *   <li>Number of write failures</li>
     *   <li>Average write time per chunk</li>
     *   <li>Checkpoint information for restart capability</li>
     * </ul>
     * 
     * @param entityManagerFactory the JPA EntityManagerFactory configured with PostgreSQL
     *        DataSource, Hibernate dialect, connection pooling, and batch processing settings.
     *        Injected automatically by Spring from DatabaseConfig.
     * @return a configured JpaItemWriter<Transaction> instance ready to persist Transaction
     *         entities within Spring Batch chunk processing, using merge() operations for
     *         flexibility with both insert and update scenarios, integrated with chunk
     *         transaction boundaries, and optimized with Hibernate batch processing
     * @see JpaItemWriterBuilder
     * @see EntityManagerFactory
     * @see Transaction
     */
    @Bean
    public JpaItemWriter<Transaction> transactionWriter(EntityManagerFactory entityManagerFactory) {
        return new JpaItemWriterBuilder<Transaction>()
                .entityManagerFactory(entityManagerFactory)
                .usePersist(false)  // Use merge() for insert/update flexibility
                .build();
    }
}

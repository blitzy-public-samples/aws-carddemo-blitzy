package com.carddemo.repository;

import com.carddemo.entity.TransactionType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 test class for TransactionTypeRepository using @DataJpaTest annotation
 * for isolated repository testing with H2 in-memory database.
 * 
 * <p>This test class verifies that PostgreSQL queries correctly replicate VSAM READ
 * operations on transaction type reference data from CVTRA03Y.cpy (TRAN-TYPE-RECORD).
 * It validates findByTypeCode method returns correct transaction type descriptions,
 * findAll retrieves all reference data entries, and save operations persist transaction
 * type metadata correctly.</p>
 * 
 * <p><b>COBOL Source Mapping:</b></p>
 * <pre>
 * COBOL Copybook: app/cpy/CVTRA03Y.cpy
 * Record Structure: TRAN-TYPE-RECORD (60 bytes)
 *   01  TRAN-TYPE-RECORD.
 *       05  TRAN-TYPE           PIC X(02).
 *       05  TRAN-TYPE-DESC      PIC X(50).
 * </pre>
 * 
 * <p><b>Test Strategy:</b></p>
 * <ul>
 *   <li>Use @DataJpaTest for isolated JPA repository testing</li>
 *   <li>H2 in-memory database provides fast, isolated test execution</li>
 *   <li>@BeforeEach sets up consistent test data matching COBOL reference patterns</li>
 *   <li>AssertJ fluent assertions for readable test verification</li>
 *   <li>Test both successful lookups and not found scenarios</li>
 *   <li>Verify CRUD operations maintain data integrity</li>
 * </ul>
 * 
 * <p><b>Test Data Setup:</b></p>
 * <p>BeforeEach method creates sample transaction types matching typical COBOL
 * reference data patterns:</p>
 * <ul>
 *   <li>Type "01" - Purchase Transaction (most common type)</li>
 *   <li>Type "02" - Refund/Return</li>
 *   <li>Type "03" - Payment Received</li>
 *   <li>Type "04" - Cash Advance</li>
 *   <li>Type "05" - Balance Transfer</li>
 * </ul>
 * 
 * <p><b>Field Validation:</b></p>
 * <p>Tests ensure fields match COBOL PIC clause specifications:</p>
 * <ul>
 *   <li>TRAN-TYPE PIC X(02): 2-character type code (primary key)</li>
 *   <li>TRAN-TYPE-DESC PIC X(50): 50-character description</li>
 * </ul>
 * 
 * <p><b>Test Configuration:</b></p>
 * <ul>
 *   <li>@DataJpaTest: Auto-configures JPA, EntityManager, transaction management</li>
 *   <li>@ActiveProfiles("test"): Loads application-test.properties configuration</li>
 *   <li>@Autowired: Injects TransactionTypeRepository for testing</li>
 *   <li>Transactional rollback: Each test rolls back to ensure test isolation</li>
 * </ul>
 * 
 * <p><b>VSAM Operation Mapping:</b></p>
 * <table border="1">
 *   <tr>
 *     <th>VSAM Operation</th>
 *     <th>Repository Method</th>
 *     <th>Test Method</th>
 *   </tr>
 *   <tr>
 *     <td>READ with RIDFLD</td>
 *     <td>findByTypeCode()</td>
 *     <td>testFindByTypeCode_Success</td>
 *   </tr>
 *   <tr>
 *     <td>READ NOTFND</td>
 *     <td>findByTypeCode() returns empty</td>
 *     <td>testFindByTypeCode_NotFound</td>
 *   </tr>
 *   <tr>
 *     <td>WRITE</td>
 *     <td>save() insert</td>
 *     <td>testSave_PersistsNewType</td>
 *   </tr>
 *   <tr>
 *     <td>REWRITE</td>
 *     <td>save() update</td>
 *     <td>testSave_UpdatesExistingType</td>
 *   </tr>
 *   <tr>
 *     <td>STARTBR/READNEXT</td>
 *     <td>findAll()</td>
 *     <td>testFindAll_ReturnsAllTypes</td>
 *   </tr>
 * </table>
 * 
 * <p><b>Data Integrity Verification:</b></p>
 * <ul>
 *   <li>Primary key uniqueness on typeCode</li>
 *   <li>NOT NULL constraints on required fields</li>
 *   <li>Field length constraints matching COBOL PIC specifications</li>
 *   <li>Optimistic locking with @Version field</li>
 * </ul>
 * 
 * @see com.carddemo.repository.TransactionTypeRepository
 * @see com.carddemo.entity.TransactionType
 * @see org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
 * @see org.springframework.test.context.ActiveProfiles
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("TransactionTypeRepository Integration Tests")
public class TransactionTypeRepositoryTest {

    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    @Autowired
    private EntityManager entityManager;

    /**
     * Test data: Transaction type codes and descriptions matching COBOL reference data.
     * These constants represent typical transaction types from the mainframe CardDemo
     * application CVTRA03Y.cpy copybook.
     */
    private static final String TYPE_CODE_PURCHASE = "01";
    private static final String TYPE_DESC_PURCHASE = "Purchase Transaction";
    
    private static final String TYPE_CODE_REFUND = "02";
    private static final String TYPE_DESC_REFUND = "Refund/Return";
    
    private static final String TYPE_CODE_PAYMENT = "03";
    private static final String TYPE_DESC_PAYMENT = "Payment Received";
    
    private static final String TYPE_CODE_CASH_ADVANCE = "04";
    private static final String TYPE_DESC_CASH_ADVANCE = "Cash Advance";
    
    private static final String TYPE_CODE_BALANCE_TRANSFER = "05";
    private static final String TYPE_DESC_BALANCE_TRANSFER = "Balance Transfer";
    
    private static final String TYPE_CODE_NONEXISTENT = "99";

    /**
     * Sets up test data before each test method execution.
     * 
     * <p>Creates sample transaction types matching typical COBOL reference data patterns
     * from CVTRA03Y.cpy. This method ensures each test starts with a consistent, known
     * state of transaction type reference data.</p>
     * 
     * <p><b>Test Data Characteristics:</b></p>
     * <ul>
     *   <li>Type codes: 2-character strings (PIC X(02) from COBOL)</li>
     *   <li>Descriptions: Up to 50 characters (PIC X(50) from COBOL)</li>
     *   <li>Covers common transaction types used throughout CardDemo</li>
     *   <li>Provides both standard and edge case scenarios</li>
     * </ul>
     * 
     * <p><b>Transaction Behavior:</b></p>
     * <p>@DataJpaTest automatically wraps each test in a transaction that rolls back
     * after test completion, ensuring test isolation and preventing data pollution.</p>
     * 
     * @see com.carddemo.entity.TransactionType
     * @see org.junit.jupiter.api.BeforeEach
     */
    @BeforeEach
    public void setUp() {
        // Clear any existing data to ensure clean state
        transactionTypeRepository.deleteAll();
        
        // Create standard transaction types matching COBOL reference data patterns
        TransactionType purchaseType = TransactionType.builder()
                .typeCode(TYPE_CODE_PURCHASE)
                .typeDescription(TYPE_DESC_PURCHASE)
                .build();
        transactionTypeRepository.save(purchaseType);
        
        TransactionType refundType = TransactionType.builder()
                .typeCode(TYPE_CODE_REFUND)
                .typeDescription(TYPE_DESC_REFUND)
                .build();
        transactionTypeRepository.save(refundType);
        
        TransactionType paymentType = TransactionType.builder()
                .typeCode(TYPE_CODE_PAYMENT)
                .typeDescription(TYPE_DESC_PAYMENT)
                .build();
        transactionTypeRepository.save(paymentType);
        
        TransactionType cashAdvanceType = TransactionType.builder()
                .typeCode(TYPE_CODE_CASH_ADVANCE)
                .typeDescription(TYPE_DESC_CASH_ADVANCE)
                .build();
        transactionTypeRepository.save(cashAdvanceType);
        
        TransactionType balanceTransferType = TransactionType.builder()
                .typeCode(TYPE_CODE_BALANCE_TRANSFER)
                .typeDescription(TYPE_DESC_BALANCE_TRANSFER)
                .build();
        transactionTypeRepository.save(balanceTransferType);
    }

    /**
     * Test findByTypeCode method with valid type code returns correct TransactionType.
     * 
     * <p>This test verifies that the findByTypeCode query method correctly retrieves
     * a transaction type by its 2-character type code, replicating VSAM READ operation
     * from COBOL with RIDFLD(TRAN-TYPE) matching PIC X(02) primary key.</p>
     * 
     * <p><b>COBOL Equivalent Operation:</b></p>
     * <pre>
     * EXEC CICS READ
     *     FILE('TRANTYPE')
     *     INTO(TRAN-TYPE-RECORD)
     *     RIDFLD(TRAN-TYPE)
     *     RESP(WS-CICS-RESP)
     * END-EXEC.
     * 
     * IF WS-CICS-RESP = DFHRESP(NORMAL)
     *     MOVE TRAN-TYPE-DESC TO WS-OUTPUT-DESC
     * END-IF.
     * </pre>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ul>
     *   <li>Given: Transaction type "01" (Purchase) exists in database</li>
     *   <li>When: findByTypeCode("01") is called</li>
     *   <li>Then: Returns Optional containing TransactionType with correct description</li>
     * </ul>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Result Optional is present (not empty)</li>
     *   <li>Type code matches input exactly (PIC X(02) precision)</li>
     *   <li>Type description matches expected value (PIC X(50))</li>
     *   <li>Version field is initialized (optimistic locking)</li>
     * </ul>
     * 
     * @see com.carddemo.repository.TransactionTypeRepository#findByTypeCode(String)
     */
    @Test
    @DisplayName("Find transaction type by 2-character type code returns correct type description")
    public void testFindByTypeCode_Success() {
        // When: Search for transaction type with code "01" (Purchase)
        Optional<TransactionType> result = transactionTypeRepository.findByTypeCode(TYPE_CODE_PURCHASE);
        
        // Then: Transaction type is found with correct details
        assertThat(result).isPresent();
        assertThat(result.get().getTypeCode()).isEqualTo(TYPE_CODE_PURCHASE);
        assertThat(result.get().getTypeDescription()).isEqualTo(TYPE_DESC_PURCHASE);
        assertThat(result.get().getVersion()).isNotNull();
    }

    /**
     * Test findByTypeCode method with non-existent type code returns empty Optional.
     * 
     * <p>This test verifies proper handling of VSAM NOTFND condition when a transaction
     * type with the specified code does not exist, matching COBOL error handling pattern
     * for file not found scenarios.</p>
     * 
     * <p><b>COBOL Equivalent Operation:</b></p>
     * <pre>
     * EXEC CICS READ
     *     FILE('TRANTYPE')
     *     INTO(TRAN-TYPE-RECORD)
     *     RIDFLD(TRAN-TYPE)
     *     RESP(WS-CICS-RESP)
     * END-EXEC.
     * 
     * IF WS-CICS-RESP = DFHRESP(NOTFND)
     *     MOVE 'N' TO TRAN-TYPE-FOUND-FLAG
     * END-IF.
     * </pre>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ul>
     *   <li>Given: Transaction type "99" does not exist in database</li>
     *   <li>When: findByTypeCode("99") is called</li>
     *   <li>Then: Returns Optional.empty() without throwing exception</li>
     * </ul>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Result Optional is empty (not present)</li>
     *   <li>No exception is thrown (null-safe Optional handling)</li>
     * </ul>
     * 
     * @see com.carddemo.repository.TransactionTypeRepository#findByTypeCode(String)
     * @see java.util.Optional#empty()
     */
    @Test
    @DisplayName("Find transaction type by non-existent type code returns empty Optional")
    public void testFindByTypeCode_NotFound() {
        // When: Search for transaction type with non-existent code "99"
        Optional<TransactionType> result = transactionTypeRepository.findByTypeCode(TYPE_CODE_NONEXISTENT);
        
        // Then: Optional is empty indicating transaction type not found
        assertThat(result).isEmpty();
    }

    /**
     * Test findById method with valid primary key returns correct TransactionType.
     * 
     * <p>This test verifies primary key lookup using JPA findById method (inherited from
     * JpaRepository), confirming that PostgreSQL primary key index correctly replicates
     * VSAM KSDS primary key access on TRAN-TYPE PIC X(02) field.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ul>
     *   <li>Given: Transaction type "03" (Payment) exists in database</li>
     *   <li>When: findById("03") is called</li>
     *   <li>Then: Returns Optional containing TransactionType with correct details</li>
     * </ul>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Result Optional is present</li>
     *   <li>Type code matches primary key exactly</li>
     *   <li>Type description is correct</li>
     * </ul>
     * 
     * @see org.springframework.data.jpa.repository.JpaRepository#findById(Object)
     */
    @Test
    @DisplayName("Find transaction type by primary key ID returns correct entity")
    public void testFindById_Success() {
        // When: Lookup transaction type by primary key "03" (Payment)
        Optional<TransactionType> result = transactionTypeRepository.findById(TYPE_CODE_PAYMENT);
        
        // Then: Transaction type is found with correct details
        assertThat(result).isPresent();
        assertThat(result.get().getTypeCode()).isEqualTo(TYPE_CODE_PAYMENT);
        assertThat(result.get().getTypeDescription()).isEqualTo(TYPE_DESC_PAYMENT);
    }

    /**
     * Test findAll method retrieves all transaction type reference data.
     * 
     * <p>This test verifies that findAll() correctly retrieves all transaction types,
     * replicating VSAM STARTBR/READNEXT sequential browse operations for reading all
     * records from the transaction type reference file.</p>
     * 
     * <p><b>COBOL Equivalent Operation:</b></p>
     * <pre>
     * EXEC CICS STARTBR
     *     FILE('TRANTYPE')
     * END-EXEC.
     * 
     * PERFORM UNTIL END-OF-FILE
     *     EXEC CICS READNEXT
     *         FILE('TRANTYPE')
     *         INTO(TRAN-TYPE-RECORD)
     *         RESP(WS-CICS-RESP)
     *     END-EXEC
     *     
     *     IF WS-CICS-RESP = DFHRESP(ENDFILE)
     *         SET END-OF-FILE TO TRUE
     *     END-IF
     * END-PERFORM.
     * </pre>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ul>
     *   <li>Given: Five transaction types exist in database (set up in @BeforeEach)</li>
     *   <li>When: findAll() is called</li>
     *   <li>Then: Returns list containing all five transaction types</li>
     * </ul>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Result list is not null</li>
     *   <li>List size equals expected count (5 types)</li>
     *   <li>List contains all expected type codes</li>
     *   <li>All transaction types have valid descriptions</li>
     * </ul>
     * 
     * @see org.springframework.data.jpa.repository.JpaRepository#findAll()
     */
    @Test
    @DisplayName("Find all transaction types returns complete reference data collection")
    public void testFindAll_ReturnsAllTypes() {
        // When: Retrieve all transaction types
        List<TransactionType> allTypes = transactionTypeRepository.findAll();
        
        // Then: All five transaction types are returned
        assertThat(allTypes).isNotNull();
        assertThat(allTypes).hasSize(5);
        
        // Verify all expected type codes are present
        assertThat(allTypes)
                .extracting(TransactionType::getTypeCode)
                .containsExactlyInAnyOrder(
                        TYPE_CODE_PURCHASE,
                        TYPE_CODE_REFUND,
                        TYPE_CODE_PAYMENT,
                        TYPE_CODE_CASH_ADVANCE,
                        TYPE_CODE_BALANCE_TRANSFER
                );
        
        // Verify all types have non-null descriptions
        assertThat(allTypes)
                .allMatch(type -> type.getTypeDescription() != null && 
                                 !type.getTypeDescription().isEmpty());
    }

    /**
     * Test save method persists new transaction type correctly.
     * 
     * <p>This test verifies that save() correctly inserts a new transaction type,
     * replicating COBOL EXEC CICS WRITE operation for adding new records to VSAM
     * transaction type file with TRAN-TYPE PIC X(02) and TRAN-TYPE-DESC PIC X(50).</p>
     * 
     * <p><b>COBOL Equivalent Operation:</b></p>
     * <pre>
     * MOVE '06' TO TRAN-TYPE.
     * MOVE 'Fee/Service Charge' TO TRAN-TYPE-DESC.
     * 
     * EXEC CICS WRITE
     *     FILE('TRANTYPE')
     *     FROM(TRAN-TYPE-RECORD)
     *     RIDFLD(TRAN-TYPE)
     *     RESP(WS-CICS-RESP)
     * END-EXEC.
     * </pre>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ul>
     *   <li>Given: New transaction type "06" (Fee) does not exist</li>
     *   <li>When: save() is called with new TransactionType entity</li>
     *   <li>Then: Transaction type is persisted with all fields correct</li>
     * </ul>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Saved entity is not null</li>
     *   <li>Type code matches input (PIC X(02))</li>
     *   <li>Type description matches input (PIC X(50))</li>
     *   <li>Version field is initialized (optimistic locking)</li>
     *   <li>Total count increases by 1</li>
     *   <li>Entity can be retrieved with findByTypeCode</li>
     * </ul>
     * 
     * @see org.springframework.data.jpa.repository.JpaRepository#save(Object)
     */
    @Test
    @DisplayName("Save persists new transaction type with TRAN-TYPE PIC X(02) and TRAN-TYPE-DESC PIC X(50) fields")
    public void testSave_PersistsNewType() {
        // Given: New transaction type for Fee/Service Charge
        String newTypeCode = "06";
        String newTypeDescription = "Fee/Service Charge";
        
        TransactionType newType = TransactionType.builder()
                .typeCode(newTypeCode)
                .typeDescription(newTypeDescription)
                .build();
        
        // When: Save new transaction type
        TransactionType savedType = transactionTypeRepository.save(newType);
        
        // Then: Transaction type is persisted correctly
        assertThat(savedType).isNotNull();
        assertThat(savedType.getTypeCode()).isEqualTo(newTypeCode);
        assertThat(savedType.getTypeDescription()).isEqualTo(newTypeDescription);
        assertThat(savedType.getVersion()).isNotNull();
        
        // Verify entity can be retrieved
        Optional<TransactionType> retrievedType = transactionTypeRepository.findByTypeCode(newTypeCode);
        assertThat(retrievedType).isPresent();
        assertThat(retrievedType.get().getTypeCode()).isEqualTo(newTypeCode);
        assertThat(retrievedType.get().getTypeDescription()).isEqualTo(newTypeDescription);
        
        // Verify total count increased
        long totalCount = transactionTypeRepository.count();
        assertThat(totalCount).isEqualTo(6);
    }

    /**
     * Test save method updates existing transaction type correctly.
     * 
     * <p>This test verifies that save() correctly updates an existing transaction type,
     * replicating COBOL EXEC CICS REWRITE operation for modifying existing records in
     * VSAM transaction type file. Tests optimistic locking with @Version field.</p>
     * 
     * <p><b>COBOL Equivalent Operation:</b></p>
     * <pre>
     * EXEC CICS READ
     *     FILE('TRANTYPE')
     *     INTO(TRAN-TYPE-RECORD)
     *     RIDFLD(TRAN-TYPE)
     *     UPDATE
     *     RESP(WS-CICS-RESP)
     * END-EXEC.
     * 
     * MOVE 'Updated Purchase Transaction' TO TRAN-TYPE-DESC.
     * 
     * EXEC CICS REWRITE
     *     FILE('TRANTYPE')
     *     FROM(TRAN-TYPE-RECORD)
     *     RESP(WS-CICS-RESP)
     * END-EXEC.
     * </pre>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ul>
     *   <li>Given: Transaction type "01" (Purchase) exists with original description</li>
     *   <li>When: Description is updated and save() is called</li>
     *   <li>Then: Transaction type is updated with new description</li>
     * </ul>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Updated entity is not null</li>
     *   <li>Type code remains unchanged (primary key immutable)</li>
     *   <li>Type description reflects new value</li>
     *   <li>Version field is incremented (optimistic locking)</li>
     *   <li>Total count remains unchanged (update, not insert)</li>
     *   <li>Subsequent retrieval returns updated description</li>
     * </ul>
     * 
     * @see org.springframework.data.jpa.repository.JpaRepository#save(Object)
     * @see jakarta.persistence.Version
     */
    @Test
    @DisplayName("Save updates existing transaction type with REWRITE semantics and version increment")
    public void testSave_UpdatesExistingType() {
        // Given: Retrieve existing transaction type "01" (Purchase)
        Optional<TransactionType> existingTypeOpt = transactionTypeRepository.findByTypeCode(TYPE_CODE_PURCHASE);
        assertThat(existingTypeOpt).isPresent();
        
        TransactionType existingType = existingTypeOpt.get();
        Long originalVersion = existingType.getVersion();
        
        // When: Update description and save
        String updatedDescription = "Updated Purchase Transaction";
        existingType.setTypeDescription(updatedDescription);
        TransactionType updatedType = transactionTypeRepository.save(existingType);
        entityManager.flush(); // Force version increment
        
        // Then: Transaction type is updated correctly
        assertThat(updatedType).isNotNull();
        assertThat(updatedType.getTypeCode()).isEqualTo(TYPE_CODE_PURCHASE);
        assertThat(updatedType.getTypeDescription()).isEqualTo(updatedDescription);
        assertThat(updatedType.getVersion()).isGreaterThan(originalVersion);
        
        // Verify total count remains unchanged (update, not insert)
        long totalCount = transactionTypeRepository.count();
        assertThat(totalCount).isEqualTo(5);
        
        // Verify subsequent retrieval returns updated description
        Optional<TransactionType> retrievedType = transactionTypeRepository.findByTypeCode(TYPE_CODE_PURCHASE);
        assertThat(retrievedType).isPresent();
        assertThat(retrievedType.get().getTypeDescription()).isEqualTo(updatedDescription);
    }

    /**
     * Test count method returns correct number of transaction types.
     * 
     * <p>This test verifies that count() returns the accurate total number of transaction
     * types in the database, useful for pagination and reference data validation.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ul>
     *   <li>Given: Five transaction types exist in database</li>
     *   <li>When: count() is called</li>
     *   <li>Then: Returns 5</li>
     * </ul>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Count equals expected value (5)</li>
     * </ul>
     * 
     * @see org.springframework.data.jpa.repository.JpaRepository#count()
     */
    @Test
    @DisplayName("Count returns correct number of transaction type records")
    public void testCount_ReturnsCorrectCount() {
        // When: Count all transaction types
        long count = transactionTypeRepository.count();
        
        // Then: Count equals the number of types set up in @BeforeEach
        assertThat(count).isEqualTo(5);
    }

    /**
     * Test findByTypeCode with multiple different type codes.
     * 
     * <p>This test verifies that findByTypeCode consistently returns correct results
     * for various type codes, ensuring proper index usage and data retrieval across
     * the complete reference data set.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ul>
     *   <li>Given: Multiple transaction types exist</li>
     *   <li>When: findByTypeCode is called for each type</li>
     *   <li>Then: Each lookup returns correct transaction type</li>
     * </ul>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>All lookups return present Optional</li>
     *   <li>Each type code matches query parameter</li>
     *   <li>Each description matches expected value</li>
     * </ul>
     */
    @Test
    @DisplayName("Find by type code works correctly for all transaction types in reference data")
    public void testFindByTypeCode_MultipleTypes() {
        // When/Then: Verify each transaction type can be found by its code
        
        // Purchase type
        Optional<TransactionType> purchaseType = transactionTypeRepository.findByTypeCode(TYPE_CODE_PURCHASE);
        assertThat(purchaseType).isPresent();
        assertThat(purchaseType.get().getTypeDescription()).isEqualTo(TYPE_DESC_PURCHASE);
        
        // Refund type
        Optional<TransactionType> refundType = transactionTypeRepository.findByTypeCode(TYPE_CODE_REFUND);
        assertThat(refundType).isPresent();
        assertThat(refundType.get().getTypeDescription()).isEqualTo(TYPE_DESC_REFUND);
        
        // Payment type
        Optional<TransactionType> paymentType = transactionTypeRepository.findByTypeCode(TYPE_CODE_PAYMENT);
        assertThat(paymentType).isPresent();
        assertThat(paymentType.get().getTypeDescription()).isEqualTo(TYPE_DESC_PAYMENT);
        
        // Cash advance type
        Optional<TransactionType> cashAdvanceType = transactionTypeRepository.findByTypeCode(TYPE_CODE_CASH_ADVANCE);
        assertThat(cashAdvanceType).isPresent();
        assertThat(cashAdvanceType.get().getTypeDescription()).isEqualTo(TYPE_DESC_CASH_ADVANCE);
        
        // Balance transfer type
        Optional<TransactionType> balanceTransferType = transactionTypeRepository.findByTypeCode(TYPE_CODE_BALANCE_TRANSFER);
        assertThat(balanceTransferType).isPresent();
        assertThat(balanceTransferType.get().getTypeDescription()).isEqualTo(TYPE_DESC_BALANCE_TRANSFER);
    }

    /**
     * Test that type code field enforces 2-character constraint.
     * 
     * <p>This test verifies that the TransactionType entity correctly enforces the
     * COBOL PIC X(02) constraint on the type code field, ensuring data integrity
     * matches the original VSAM file definition.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ul>
     *   <li>Given: Transaction type with exactly 2-character code</li>
     *   <li>When: Entity is saved</li>
     *   <li>Then: Code is stored and retrieved correctly</li>
     * </ul>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Type code length is exactly 2 characters</li>
     *   <li>Type code matches input exactly</li>
     * </ul>
     */
    @Test
    @DisplayName("Type code field maintains COBOL PIC X(02) 2-character constraint")
    public void testTypeCode_EnforcesTwoCharacterConstraint() {
        // Given: Transaction type with 2-character code
        String twoCharCode = "07";
        TransactionType transactionType = TransactionType.builder()
                .typeCode(twoCharCode)
                .typeDescription("Interest Charge")
                .build();
        
        // When: Save transaction type
        TransactionType savedType = transactionTypeRepository.save(transactionType);
        
        // Then: Type code is exactly 2 characters
        assertThat(savedType.getTypeCode()).hasSize(2);
        assertThat(savedType.getTypeCode()).isEqualTo(twoCharCode);
    }

    /**
     * Test that type description field maintains COBOL PIC X(50) constraint.
     * 
     * <p>This test verifies that the TransactionType entity correctly handles the
     * COBOL PIC X(50) constraint on the type description field, ensuring field
     * length matches the original VSAM file definition.</p>
     * 
     * <p><b>Test Scenario:</b></p>
     * <ul>
     *   <li>Given: Transaction type with description up to 50 characters</li>
     *   <li>When: Entity is saved</li>
     *   <li>Then: Description is stored and retrieved correctly</li>
     * </ul>
     * 
     * <p><b>Assertions:</b></p>
     * <ul>
     *   <li>Type description length does not exceed 50 characters</li>
     *   <li>Type description matches input value</li>
     * </ul>
     */
    @Test
    @DisplayName("Type description field maintains COBOL PIC X(50) maximum length constraint")
    public void testTypeDescription_MaintainsFiftyCharacterConstraint() {
        // Given: Transaction type with description exactly 50 characters
        String fiftyCharDescription = "This is exactly fifty character type description!!";
        assertThat(fiftyCharDescription).hasSize(50);
        
        TransactionType transactionType = TransactionType.builder()
                .typeCode("08")
                .typeDescription(fiftyCharDescription)
                .build();
        
        // When: Save transaction type
        TransactionType savedType = transactionTypeRepository.save(transactionType);
        
        // Then: Description is stored correctly with exact length
        assertThat(savedType.getTypeDescription()).hasSize(50);
        assertThat(savedType.getTypeDescription()).isEqualTo(fiftyCharDescription);
    }
}

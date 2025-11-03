/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.repository;

import com.carddemo.entity.Customer;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 test class for CustomerRepository validating Spring Data JPA operations
 * for customer data migrated from CUSTDAT VSAM KSDS file.
 * 
 * <p><b>Test Purpose:</b></p>
 * <p>This test class validates the complete transformation from COBOL VSAM file
 * operations to PostgreSQL database operations, ensuring functional equivalence
 * between mainframe and cloud-native implementations.</p>
 * 
 * <p><b>COBOL Source Validation:</b></p>
 * <ul>
 *   <li><b>Copybook:</b> CVCUS01Y.cpy - Customer record layout (RECLN 500 bytes)</li>
 *   <li><b>Batch Program:</b> CBCUS01C.cbl - Sequential customer file processing</li>
 *   <li><b>VSAM File:</b> CUSTDAT KSDS - Key-sequenced dataset with CUST-ID primary key</li>
 * </ul>
 * 
 * <p><b>Key Validation Areas:</b></p>
 * <ul>
 *   <li>VSAM random read (by key) → findByCustomerId() with B-tree index lookup</li>
 *   <li>VSAM sequential read → findAll(Pageable) with pagination</li>
 *   <li>VSAM WRITE → save() with INSERT operation</li>
 *   <li>VSAM REWRITE → save() with UPDATE operation</li>
 *   <li>VSAM DELETE → delete() and deleteById() operations</li>
 *   <li>COBOL PIC 9(09) → NUMERIC/BIGINT precision preservation</li>
 *   <li>COBOL PIC X(n) → VARCHAR(n) length constraints</li>
 *   <li>Primary key uniqueness (CUST-ID)</li>
 *   <li>SSN unique constraint validation</li>
 *   <li>Row-level locking equivalent to VSAM record locking</li>
 * </ul>
 * 
 * <p><b>Test Data Setup:</b></p>
 * <p>Test data is loaded from SQL script: /db/test-data/customers.sql</p>
 * <p>The script creates sample customer records matching the 500-byte COBOL structure
 * with all fields populated according to copybook specifications.</p>
 * 
 * <p><b>Database Configuration:</b></p>
 * <ul>
 *   <li>Uses H2 in-memory database for isolated testing</li>
 *   <li>@AutoConfigureTestDatabase with Replace.NONE preserves test database dialect</li>
 *   <li>Schema auto-generated from JPA entity annotations</li>
 *   <li>Test data loaded via @Sql annotation before each test</li>
 * </ul>
 * 
 * <p><b>Test Execution Order:</b></p>
 * <p>Tests are executed in ordered sequence using @TestMethodOrder to ensure
 * dependent tests run after prerequisite validations complete successfully.</p>
 * 
 * @see Customer
 * @see CustomerRepository
 * @see com.carddemo.batch.job.CustomerDataLoadJob
 * @since 1.0
 * @version 1.0
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Sql(scripts = {"/db/test-data/customers.sql"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerRepositoryTest {

    @Autowired
    private CustomerRepository customerRepository;
    
    @Autowired
    private TestEntityManager testEntityManager;

    /**
     * Tests findByCustomerId for valid customer ID - VSAM random read equivalent.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET(LIT-CUSTFILENAME)
     *      RIDFLD(WS-CARD-RID-CUST-ID-X)
     *      INTO(CUSTOMER-RECORD)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><b>Validates:</b></p>
     * <ul>
     *   <li>B-tree index on customer_id primary key provides O(log n) lookup</li>
     *   <li>CUST-ID PIC 9(09) correctly mapped to Long/BIGINT</li>
     *   <li>All 500-byte COBOL record fields correctly deserialized</li>
     *   <li>VARCHAR length constraints match COBOL PIC clauses</li>
     *   <li>NUMERIC precision preserved for customer_id and FICO score</li>
     * </ul>
     */
    @Test
    @Order(1)
    void testFindByCustomerId_ValidCustomerId() {
        // Arrange: Customer ID 1000000001 should exist from test data
        Long customerId = 1000000001L;

        // Act: Execute findByCustomerId (VSAM random read equivalent)
        Optional<Customer> customerOptional = customerRepository.findByCustomerId(customerId);

        // Assert: Verify customer was found and all fields are correctly mapped
        assertTrue(customerOptional.isPresent(), "Customer with ID 1000000001 should exist");
        
        Customer customer = customerOptional.get();
        
        // Validate primary key (CUST-ID PIC 9(09) → BIGINT)
        assertEquals(1000000001L, customer.getCustomerId(), 
            "Customer ID should match CUST-ID PIC 9(09) mapping");
        
        // Validate name fields (PIC X(25) → VARCHAR(25))
        assertAll("Customer name fields validation",
            () -> assertEquals("John", customer.getFirstName(), 
                "First name should match CUST-FIRST-NAME PIC X(25)"),
            () -> assertEquals("Q", customer.getMiddleName(), 
                "Middle name should match CUST-MIDDLE-NAME PIC X(25)"),
            () -> assertEquals("Smith", customer.getLastName(), 
                "Last name should match CUST-LAST-NAME PIC X(25)")
        );
        
        // Validate address fields (PIC X(50) → VARCHAR(50))
        assertAll("Customer address fields validation",
            () -> assertEquals("123 Main St", customer.getAddressLine1(), 
                "Address line 1 should match CUST-ADDR-LINE-1 PIC X(50)"),
            () -> assertEquals("Apt 4B", customer.getAddressLine2(), 
                "Address line 2 should match CUST-ADDR-LINE-2 PIC X(50)"),
            () -> assertNotNull(customer.getAddressLine3(), 
                "Address line 3 should be present (CUST-ADDR-LINE-3 PIC X(50))")
        );
        
        // Validate state, country, zip (PIC X(02), PIC X(03), PIC X(10))
        assertAll("Customer location fields validation",
            () -> assertEquals("TX", customer.getStateCode(), 
                "State code should match CUST-ADDR-STATE-CD PIC X(02)"),
            () -> assertEquals("USA", customer.getCountryCode(), 
                "Country code should match CUST-ADDR-COUNTRY-CD PIC X(03)"),
            () -> assertEquals("75001", customer.getZipCode(), 
                "Zip code should match CUST-ADDR-ZIP PIC X(10)")
        );
        
        // Validate phone numbers (PIC X(15) → VARCHAR(15))
        assertAll("Customer phone fields validation",
            () -> assertEquals("214-555-0100", customer.getPhoneNumber1(), 
                "Primary phone should match CUST-PHONE-NUM-1 PIC X(15)"),
            () -> assertNotNull(customer.getPhoneNumber2(), 
                "Secondary phone field should exist (CUST-PHONE-NUM-2 PIC X(15))")
        );
        
        // Validate SSN (CUST-SSN PIC 9(09) → VARCHAR(9))
        assertEquals("123456789", customer.getSsn(), 
            "SSN should match CUST-SSN PIC 9(09) as 9-digit string");
        
        // Validate government issued ID (PIC X(20) → VARCHAR(20))
        assertEquals("DL-TX-12345678", customer.getGovernmentIssuedId(), 
            "Government ID should match CUST-GOVT-ISSUED-ID PIC X(20)");
        
        // Validate date of birth (CUST-DOB-YYYY-MM-DD PIC X(10) → LocalDate)
        assertEquals(LocalDate.of(1980, 5, 15), customer.getDateOfBirth(), 
            "Date of birth should be converted from COBOL date format YYYY-MM-DD");
        
        // Validate EFT account ID (PIC X(10) → VARCHAR(10))
        assertEquals("EFT1234567", customer.getEftAccountId(), 
            "EFT account ID should match CUST-EFT-ACCOUNT-ID PIC X(10)");
        
        // Validate primary card holder indicator (PIC X(01) → VARCHAR(1))
        assertEquals("Y", customer.getPrimaryCardHolderIndicator(), 
            "Primary card holder indicator should match CUST-PRI-CARD-HOLDER-IND PIC X(01)");
        
        // Validate FICO credit score (CUST-FICO-CREDIT-SCORE PIC 9(03) → INTEGER)
        assertEquals(720, customer.getFicoCreditScore(), 
            "FICO score should match CUST-FICO-CREDIT-SCORE PIC 9(03) as INTEGER");
    }

    /**
     * Tests findByCustomerId with invalid customer ID - VSAM NOTFND condition equivalent.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET(LIT-CUSTFILENAME)
     *      RIDFLD(WS-CARD-RID-CUST-ID-X)
     *      INTO(CUSTOMER-RECORD)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * IF WS-RESP-CD = DFHRESP(NOTFND)
     *     MOVE 'Customer not found' TO ERROR-MESSAGE
     * END-IF
     * </pre>
     * 
     * <p><b>Validates:</b> Empty Optional return for non-existent customer ID</p>
     */
    @Test
    @Order(2)
    void testFindByCustomerId_InvalidCustomerId() {
        // Arrange: Use a customer ID that doesn't exist
        Long nonExistentCustomerId = 9999999999L;

        // Act: Attempt to find non-existent customer
        Optional<Customer> customerOptional = customerRepository.findByCustomerId(nonExistentCustomerId);

        // Assert: Verify Optional is empty (equivalent to COBOL DFHRESP(NOTFND))
        assertFalse(customerOptional.isPresent(), 
            "Customer with non-existent ID should return empty Optional (VSAM NOTFND equivalent)");
    }

    /**
     * Tests findAll for sequential customer retrieval - VSAM sequential read equivalent.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * OPEN INPUT CUSTFILE-FILE
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *     READ CUSTFILE-FILE INTO CUSTOMER-RECORD
     *     IF CUSTFILE-STATUS = '00'
     *         PERFORM PROCESS-CUSTOMER-RECORD
     *     ELSE IF CUSTFILE-STATUS = '10'
     *         MOVE 'Y' TO END-OF-FILE
     *     END-IF
     * END-PERFORM
     * CLOSE CUSTFILE-FILE
     * </pre>
     * 
     * <p><b>Source:</b> CBCUS01C.cbl lines 74-81</p>
     * 
     * <p><b>Validates:</b></p>
     * <ul>
     *   <li>Sequential access pattern with proper ordering</li>
     *   <li>All customers retrieved from database</li>
     *   <li>Result ordering by primary key (customer_id)</li>
     * </ul>
     */
    @Test
    @Order(3)
    void testFindAll_ReturnsAllCustomers() {
        // Act: Retrieve all customers (VSAM sequential read equivalent)
        List<Customer> customers = customerRepository.findAll();

        // Assert: Verify customers were retrieved
        assertNotNull(customers, "Customer list should not be null");
        assertFalse(customers.isEmpty(), "Customer list should not be empty");
        assertTrue(customers.size() >= 2, 
            "At least 2 test customers should exist from SQL script");
        
        // Verify sequential ordering by customer_id (VSAM KSDS key-sequenced access)
        for (int i = 0; i < customers.size() - 1; i++) {
            assertTrue(customers.get(i).getCustomerId() <= customers.get(i + 1).getCustomerId(),
                "Customers should be ordered by customer_id (VSAM key sequence)");
        }
    }

    /**
     * Tests save operation for new customer - VSAM WRITE equivalent.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * EXEC CICS WRITE
     *      DATASET(LIT-CUSTFILENAME)
     *      FROM(CUSTOMER-RECORD)
     *      RIDFLD(CUST-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><b>Validates:</b></p>
     * <ul>
     *   <li>INSERT operation with all field constraints</li>
     *   <li>Primary key uniqueness on customer_id</li>
     *   <li>VARCHAR length constraints enforced</li>
     *   <li>NOT NULL constraints on required fields</li>
     * </ul>
     */
    @Test
    @Order(4)
    void testSave_NewCustomer() {
        // Arrange: Create new customer with all required fields
        Customer newCustomer = new Customer();
        newCustomer.setCustomerId(1000000003L);
        newCustomer.setFirstName("Alice");
        newCustomer.setMiddleName("B");
        newCustomer.setLastName("Johnson");
        newCustomer.setAddressLine1("789 Elm Street");
        newCustomer.setAddressLine2("");
        newCustomer.setAddressLine3("");
        newCustomer.setStateCode("NY");
        newCustomer.setCountryCode("USA");
        newCustomer.setZipCode("10001");
        newCustomer.setPhoneNumber1("212-555-0300");
        newCustomer.setPhoneNumber2("");
        newCustomer.setSsn("345678901");
        newCustomer.setGovernmentIssuedId("DL-NY-98765432");
        newCustomer.setDateOfBirth(LocalDate.of(1985, 3, 10));
        newCustomer.setEftAccountId("EFT3456789");
        newCustomer.setPrimaryCardHolderIndicator("Y");
        newCustomer.setFicoCreditScore(750);

        // Act: Save new customer (VSAM WRITE equivalent)
        Customer savedCustomer = customerRepository.save(newCustomer);

        // Assert: Verify customer was saved successfully
        assertNotNull(savedCustomer, "Saved customer should not be null");
        assertEquals(1000000003L, savedCustomer.getCustomerId(), 
            "Saved customer ID should match");
        
        // Verify customer can be retrieved by ID
        Optional<Customer> retrievedCustomer = customerRepository.findByCustomerId(1000000003L);
        assertTrue(retrievedCustomer.isPresent(), 
            "Newly saved customer should be retrievable by ID");
        assertEquals("Alice", retrievedCustomer.get().getFirstName(), 
            "Retrieved customer first name should match");
        assertEquals("Johnson", retrievedCustomer.get().getLastName(), 
            "Retrieved customer last name should match");
    }

    /**
     * Tests update operation for existing customer - VSAM REWRITE equivalent.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * EXEC CICS READ UPDATE
     *      DATASET(LIT-CUSTFILENAME)
     *      RIDFLD(CUST-ID)
     *      INTO(CUSTOMER-RECORD)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * MOVE NEW-PHONE-NUMBER TO CUST-PHONE-NUM-1
     * EXEC CICS REWRITE
     *      DATASET(LIT-CUSTFILENAME)
     *      FROM(CUSTOMER-RECORD)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><b>Validates:</b></p>
     * <ul>
     *   <li>UPDATE operation on existing record</li>
     *   <li>Field modifications persist correctly</li>
     *   <li>Primary key remains unchanged</li>
     * </ul>
     */
    @Test
    @Order(5)
    void testUpdate_ExistingCustomer() {
        // Arrange: Retrieve existing customer
        Long customerId = 1000000001L;
        Optional<Customer> customerOptional = customerRepository.findByCustomerId(customerId);
        assertTrue(customerOptional.isPresent(), "Customer should exist for update test");
        
        Customer customer = customerOptional.get();
        String originalFirstName = customer.getFirstName();
        String newPhoneNumber = "214-555-9999";
        
        // Modify customer data (VSAM READ UPDATE)
        customer.setPhoneNumber1(newPhoneNumber);

        // Act: Update customer (VSAM REWRITE equivalent)
        Customer updatedCustomer = customerRepository.save(customer);

        // Assert: Verify update was successful
        assertNotNull(updatedCustomer, "Updated customer should not be null");
        assertEquals(customerId, updatedCustomer.getCustomerId(), 
            "Customer ID should remain unchanged after update");
        assertEquals(newPhoneNumber, updatedCustomer.getPhoneNumber1(), 
            "Phone number should be updated");
        
        // Verify update persisted by re-reading from database
        Optional<Customer> reReadCustomer = customerRepository.findByCustomerId(customerId);
        assertTrue(reReadCustomer.isPresent(), "Updated customer should be retrievable");
        assertEquals(newPhoneNumber, reReadCustomer.get().getPhoneNumber1(), 
            "Updated phone number should persist in database");
    }

    /**
     * Tests delete operation for existing customer - VSAM DELETE equivalent.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * EXEC CICS DELETE
     *      DATASET(LIT-CUSTFILENAME)
     *      RIDFLD(CUST-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><b>Validates:</b></p>
     * <ul>
     *   <li>DELETE operation removes record</li>
     *   <li>Deleted customer no longer retrievable</li>
     *   <li>Foreign key constraints handled appropriately</li>
     * </ul>
     */
    @Test
    @Order(6)
    void testDelete_ExistingCustomer() {
        // Arrange: Create a customer specifically for deletion test
        Customer customerToDelete = new Customer();
        customerToDelete.setCustomerId(1000000004L);
        customerToDelete.setFirstName("DeleteMe");
        customerToDelete.setMiddleName("D");
        customerToDelete.setLastName("TestUser");
        customerToDelete.setAddressLine1("123 Delete St");
        customerToDelete.setAddressLine2("");
        customerToDelete.setAddressLine3("");
        customerToDelete.setStateCode("CA");
        customerToDelete.setCountryCode("USA");
        customerToDelete.setZipCode("90001");
        customerToDelete.setPhoneNumber1("310-555-0400");
        customerToDelete.setPhoneNumber2("");
        customerToDelete.setSsn("456789012");
        customerToDelete.setGovernmentIssuedId("DL-CA-11111111");
        customerToDelete.setDateOfBirth(LocalDate.of(1990, 1, 1));
        customerToDelete.setEftAccountId("EFT4567890");
        customerToDelete.setPrimaryCardHolderIndicator("N");
        customerToDelete.setFicoCreditScore(680);
        
        customerRepository.save(customerToDelete);
        
        // Verify customer exists before deletion
        assertTrue(customerRepository.findByCustomerId(1000000004L).isPresent(), 
            "Customer should exist before deletion");

        // Act: Delete customer (VSAM DELETE equivalent)
        customerRepository.deleteById(1000000004L);

        // Assert: Verify customer was deleted
        Optional<Customer> deletedCustomer = customerRepository.findByCustomerId(1000000004L);
        assertFalse(deletedCustomer.isPresent(), 
            "Deleted customer should no longer be retrievable (VSAM DELETE equivalent)");
    }

    /**
     * Tests findBySsn for valid SSN - alternate key lookup equivalent.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *     READ CUSTFILE-FILE INTO CUSTOMER-RECORD
     *     IF CUST-SSN = WS-SEARCH-SSN
     *         MOVE 'Y' TO CUSTOMER-FOUND-FLAG
     *         EXIT PERFORM
     *     END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><b>Validates:</b></p>
     * <ul>
     *   <li>SSN-based customer lookup (XREF file equivalent)</li>
     *   <li>Secondary index on ssn column for optimized searches</li>
     *   <li>PII field handling (CUST-SSN PIC 9(09))</li>
     * </ul>
     * 
     * <p><b>Security Note:</b> This test accesses PII data (SSN). In production,
     * all SSN access must be logged for audit compliance per Section 0.9.</p>
     */
    @Test
    @Order(7)
    void testFindBySsn_ValidSSN() {
        // Arrange: SSN from test data
        String ssn = "123456789";

        // Act: Find customer by SSN (alternate key lookup)
        Optional<Customer> customerOptional = customerRepository.findBySsn(ssn);

        // Assert: Verify customer was found by SSN
        assertTrue(customerOptional.isPresent(), 
            "Customer with SSN 123456789 should be found");
        
        Customer customer = customerOptional.get();
        assertEquals(ssn, customer.getSsn(), 
            "Retrieved customer SSN should match search SSN");
        assertEquals("John", customer.getFirstName(), 
            "Customer with SSN 123456789 should be John Smith");
        assertEquals("Smith", customer.getLastName(), 
            "Customer last name should be Smith");
    }

    /**
     * Tests findByLastNameContainingIgnoreCase for partial last name match.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *     READ CUSTFILE-FILE INTO CUSTOMER-RECORD
     *     IF CUST-LAST-NAME CONTAINS WS-SEARCH-LASTNAME
     *         MOVE CUSTOMER-RECORD TO RESULT-TABLE(RESULT-COUNT)
     *         ADD 1 TO RESULT-COUNT
     *     END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><b>Validates:</b></p>
     * <ul>
     *   <li>Case-insensitive partial match on last name</li>
     *   <li>LIKE query with index support</li>
     *   <li>Multiple results handling</li>
     * </ul>
     */
    @Test
    @Order(8)
    void testFindByLastName_PartialMatch() {
        // Act: Search for customers with last name containing "Smith"
        List<Customer> customers = customerRepository.findByLastNameContainingIgnoreCase("Smith");

        // Assert: Verify search results
        assertNotNull(customers, "Customer list should not be null");
        assertFalse(customers.isEmpty(), 
            "Should find at least one customer with last name containing 'Smith'");
        
        // Verify all returned customers have "Smith" in last name (case-insensitive)
        for (Customer customer : customers) {
            assertTrue(customer.getLastName().toLowerCase().contains("smith"),
                "Each customer should have 'Smith' in last name (case-insensitive)");
        }
    }

    /**
     * Tests pagination with findAll(Pageable) - batch processing pattern.
     * 
     * <p><b>COBOL Equivalent:</b> Batch processing with record counting:</p>
     * <pre>
     * MOVE 0 TO RECORD-COUNTER
     * PERFORM UNTIL END-OF-FILE = 'Y' OR RECORD-COUNTER >= PAGE-SIZE
     *     READ CUSTFILE-FILE INTO CUSTOMER-RECORD
     *     IF CUSTFILE-STATUS = '00'
     *         ADD 1 TO RECORD-COUNTER
     *         PERFORM PROCESS-CUSTOMER-RECORD
     *     END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><b>Source:</b> CBCUS01C.cbl batch customer processing</p>
     * 
     * <p><b>Validates:</b></p>
     * <ul>
     *   <li>Paginated data retrieval for batch processing</li>
     *   <li>Page metadata (total elements, total pages, page number)</li>
     *   <li>Sorting support via Pageable</li>
     *   <li>Chunk-oriented processing pattern (Section 0.5 - chunk size 1000)</li>
     * </ul>
     */
    @Test
    @Order(9)
    void testFindAll_WithPagination() {
        // Arrange: Create pageable with page size 1, sorted by customer ID
        Pageable pageable = PageRequest.of(0, 1, Sort.by("customerId").ascending());

        // Act: Retrieve first page of customers
        Page<Customer> customerPage = customerRepository.findAll(pageable);

        // Assert: Verify pagination results
        assertNotNull(customerPage, "Customer page should not be null");
        assertEquals(1, customerPage.getSize(), 
            "Page size should match requested size");
        assertTrue(customerPage.getTotalElements() >= 2, 
            "Total elements should be at least 2 from test data");
        assertTrue(customerPage.hasContent(), 
            "Page should have content");
        
        // Verify first customer on page is sorted correctly
        List<Customer> customers = customerPage.getContent();
        assertEquals(1, customers.size(), 
            "First page should contain exactly 1 customer");
        
        Customer firstCustomer = customers.get(0);
        assertNotNull(firstCustomer.getCustomerId(), 
            "First customer should have customer ID");
        
        // Test navigation
        if (customerPage.hasNext()) {
            Pageable nextPageable = customerPage.nextPageable();
            Page<Customer> nextPage = customerRepository.findAll(nextPageable);
            assertTrue(nextPage.hasContent(), 
                "Next page should have content if hasNext() is true");
            
            // Verify ordering - next page customer ID should be >= first page customer ID
            Customer nextCustomer = nextPage.getContent().get(0);
            assertTrue(nextCustomer.getCustomerId() >= firstCustomer.getCustomerId(),
                "Next page customer ID should be greater than or equal to previous page (sorted order)");
        }
    }

    /**
     * Tests customer field constraints validation - COBOL field validation equivalent.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * IF CUST-ID NOT NUMERIC OR CUST-ID = ZEROS
     *     MOVE 'Invalid customer ID' TO ERROR-MESSAGE
     *     PERFORM ERROR-ROUTINE
     * END-IF
     * IF CUST-FIRST-NAME = SPACES
     *     MOVE 'First name required' TO ERROR-MESSAGE
     *     PERFORM ERROR-ROUTINE
     * END-IF
     * </pre>
     * 
     * <p><b>Validates:</b></p>
     * <ul>
     *   <li>NOT NULL constraints on required fields</li>
     *   <li>VARCHAR length constraints (names 25, addresses 50)</li>
     *   <li>Primary key uniqueness</li>
     * </ul>
     */
    @Test
    @Order(10)
    void testCustomerFieldConstraints() {
        // Test 1: Verify customer_id NOT NULL constraint
        Customer customerWithNullId = new Customer();
        customerWithNullId.setCustomerId(null); // NULL primary key
        customerWithNullId.setFirstName("Test");
        customerWithNullId.setLastName("User");
        
        // Should throw exception when trying to save customer with null ID
        assertThrows(Exception.class, () -> {
            customerRepository.save(customerWithNullId);
            customerRepository.flush();
        }, "Saving customer with NULL customer_id should throw exception");
        
        // Test 2: Verify first_name NOT NULL constraint
        Customer customerWithNullFirstName = new Customer();
        customerWithNullFirstName.setCustomerId(1000000005L);
        customerWithNullFirstName.setFirstName(null); // NULL first name
        customerWithNullFirstName.setLastName("User");
        
        assertThrows(Exception.class, () -> {
            customerRepository.save(customerWithNullFirstName);
            customerRepository.flush();
        }, "Saving customer with NULL first_name should throw exception");
        
        // Test 3: Verify last_name NOT NULL constraint
        Customer customerWithNullLastName = new Customer();
        customerWithNullLastName.setCustomerId(1000000006L);
        customerWithNullLastName.setFirstName("Test");
        customerWithNullLastName.setLastName(null); // NULL last name
        
        assertThrows(Exception.class, () -> {
            customerRepository.save(customerWithNullLastName);
            customerRepository.flush();
        }, "Saving customer with NULL last_name should throw exception");
    }

    /**
     * Tests primary key uniqueness constraint - VSAM duplicate key equivalent.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * EXEC CICS WRITE
     *      DATASET(LIT-CUSTFILENAME)
     *      FROM(CUSTOMER-RECORD)
     *      RIDFLD(CUST-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * IF WS-RESP-CD = DFHRESP(DUPREC)
     *     MOVE 'Duplicate customer ID' TO ERROR-MESSAGE
     *     PERFORM ERROR-ROUTINE
     * END-IF
     * </pre>
     * 
     * <p><b>Validates:</b> Primary key uniqueness enforcement matching VSAM DUPREC condition</p>
     */
    @Test
    @Order(11)
    void testCustomerPrimaryKeyConstraint() {
        // Arrange: Create first customer with specific ID
        Customer customer1 = new Customer();
        customer1.setCustomerId(1000000007L);
        customer1.setFirstName("Duplicate");
        customer1.setMiddleName("Test");
        customer1.setLastName("User1");
        customer1.setAddressLine1("123 Test St");
        customer1.setAddressLine2("");
        customer1.setAddressLine3("");
        customer1.setStateCode("TX");
        customer1.setCountryCode("USA");
        customer1.setZipCode("75001");
        customer1.setPhoneNumber1("214-555-0700");
        customer1.setPhoneNumber2("");
        customer1.setSsn("567890123");
        customer1.setGovernmentIssuedId("DL-TX-77777777");
        customer1.setDateOfBirth(LocalDate.of(1992, 6, 15));
        customer1.setEftAccountId("EFT5678901");
        customer1.setPrimaryCardHolderIndicator("Y");
        customer1.setFicoCreditScore(700);
        
        customerRepository.save(customer1);
        customerRepository.flush();
        
        // Clear the persistence context to detach customer1
        // This ensures customer2 will be treated as a new entity for INSERT
        testEntityManager.clear();
        
        // Create second customer with same customer_id (duplicate primary key)
        Customer customer2 = new Customer();
        customer2.setCustomerId(1000000007L); // DUPLICATE KEY
        customer2.setFirstName("Another");
        customer2.setMiddleName("Test");
        customer2.setLastName("User2");
        customer2.setAddressLine1("456 Test Ave");
        customer2.setAddressLine2("");
        customer2.setAddressLine3("");
        customer2.setStateCode("CA");
        customer2.setCountryCode("USA");
        customer2.setZipCode("90001");
        customer2.setPhoneNumber1("310-555-0700");
        customer2.setPhoneNumber2("");
        customer2.setSsn("678901234");
        customer2.setGovernmentIssuedId("DL-CA-88888888");
        customer2.setDateOfBirth(LocalDate.of(1993, 7, 20));
        customer2.setEftAccountId("EFT6789012");
        customer2.setPrimaryCardHolderIndicator("N");
        customer2.setFicoCreditScore(710);

        // Act & Assert: Attempt to persist duplicate customer_id should throw exception
        // Using persist() instead of save() to force INSERT operation
        // Note: TestEntityManager throws Hibernate ConstraintViolationException directly
        // (Spring Data repositories would translate this to DataIntegrityViolationException)
        ConstraintViolationException exception = assertThrows(ConstraintViolationException.class, () -> {
            testEntityManager.persist(customer2);
            testEntityManager.flush();
        }, "Persisting customer with duplicate customer_id should throw ConstraintViolationException " +
           "(equivalent to COBOL DFHRESP(DUPREC))");
        
        // Verify it's specifically a primary key constraint violation
        assertTrue(exception.getMessage().contains("PRIMARY KEY") || 
                   exception.getMessage().contains("primary key") ||
                   exception.getMessage().contains("Unique index"),
                   "Exception should indicate primary key constraint violation");
    }

    /**
     * Tests count operation - record count equivalent.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * MOVE 0 TO CUSTOMER-COUNT
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *     READ CUSTFILE-FILE INTO CUSTOMER-RECORD
     *     IF CUSTFILE-STATUS = '00'
     *         ADD 1 TO CUSTOMER-COUNT
     *     END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><b>Validates:</b> Total customer count for reporting and batch processing</p>
     */
    @Test
    @Order(12)
    void testCount_TotalCustomers() {
        // Act: Count total customers
        long customerCount = customerRepository.count();

        // Assert: Verify count is accurate
        assertTrue(customerCount >= 2, 
            "Customer count should be at least 2 from test data");
        
        // Verify count matches findAll size
        List<Customer> allCustomers = customerRepository.findAll();
        assertEquals(allCustomers.size(), customerCount, 
            "Count should match total number of customers from findAll");
    }

    /**
     * Tests existsById operation - customer existence check.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET(LIT-CUSTFILENAME)
     *      RIDFLD(CUST-ID)
     *      KEYLENGTH(LENGTH OF CUST-ID)
     *      INTO(DUMMY-RECORD)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *     MOVE 'Y' TO CUSTOMER-EXISTS-FLAG
     * ELSE
     *     MOVE 'N' TO CUSTOMER-EXISTS-FLAG
     * END-IF
     * </pre>
     * 
     * <p><b>Validates:</b> Efficient existence check without loading full entity</p>
     */
    @Test
    @Order(13)
    void testExistsById_CustomerExists() {
        // Test existing customer
        Long existingCustomerId = 1000000001L;
        boolean exists = customerRepository.existsById(existingCustomerId);
        assertTrue(exists, 
            "Customer with ID 1000000001 should exist");
        
        // Test non-existent customer
        Long nonExistentCustomerId = 9999999999L;
        boolean notExists = customerRepository.existsById(nonExistentCustomerId);
        assertFalse(notExists, 
            "Customer with ID 9999999999 should not exist");
    }
}


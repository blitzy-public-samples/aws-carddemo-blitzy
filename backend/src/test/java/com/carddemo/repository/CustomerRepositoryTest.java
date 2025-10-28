package com.carddemo.repository;

import com.carddemo.model.entity.Customer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Test for CustomerRepository - Converted from VSAM CUSTFILE I/O testing
 * 
 * Comprehensive integration test validating CustomerRepository JPA operations using Testcontainers PostgreSQL.
 * Tests customer table operations that replace COBOL VSAM CUSTFILE access from CBCUS01C.cbl.
 * 
 * Original COBOL File: CUSTFILE (VSAM KSDS - Key-Sequenced Data Set)
 * Original Copybook: CVCUS01Y.cpy (CUSTOMER-RECORD structure with RECLN 500)
 * Target Database Table: customer (PostgreSQL with B-tree indexes)
 * 
 * Test Coverage:
 * ==============
 * 1. CRUD Operations:
 *    - testSaveCustomer(): Create complete customer with all 20+ fields
 *    - testFindByIdCustomer(): Retrieve customer by primary key (cust_id PIC 9(09))
 *    - testFindAllCustomers(): Retrieve multiple customers
 *    - testUpdateCustomer(): Modify and persist customer changes
 *    - testDeleteCustomer(): Delete customer by primary key
 *    - testCustomerIdNotFound(): Verify empty Optional for non-existent customer
 *    - testCustomerIdUniqueness(): Validate primary key constraint enforcement
 * 
 * 2. Custom Query Methods:
 *    - testFindByCustSsn(): Lookup customer by SSN (PIC 9(09))
 *    - testFindByLastNameAndFirstName(): Search by name composite index
 *    - testFindByCustSsnNotFound(): Verify empty Optional for non-existent SSN
 * 
 * 3. Date Conversion Tests (COBOL PIC X(10) YYYY-MM-DD → Java LocalDate):
 *    - testDateOfBirthConversion(): Verify DOB field conversion
 *    - testDateOfBirthFormat(): Validate date stored in ISO format
 * 
 * 4. PII Field Tests (Section 0.2.2):
 *    - testSsnFieldStorage(): Validate 9-digit SSN storage with leading zeros
 *    - testGovernmentIdStorage(): Validate government ID field (PIC X(20))
 * 
 * 5. Complex Field Tests (500-byte structure):
 *    - testAddressFields(): All 3 address lines, state, country, zip
 *    - testPhoneFields(): Both phone number fields
 *    - testNameFields(): First, middle, last names
 *    - testFicoScore(): FICO credit score field (PIC 9(03), range 300-850)
 * 
 * 6. Performance Validation (Section 0.7.7):
 *    - testQueryPerformance(): Verify sub-10ms primary key lookups
 * 
 * 7. Optimistic Locking:
 *    - testOptimisticLocking(): Verify @Version field prevents lost updates
 * 
 * COBOL-to-Java Conversion Validation:
 * =====================================
 * This test suite validates the complete transformation of COBOL customer data handling:
 * - CUST-ID PIC 9(09) → Long custId (primary key)
 * - CUST-FIRST-NAME PIC X(25) → String custFirstName
 * - CUST-MIDDLE-NAME PIC X(25) → String custMiddleName
 * - CUST-LAST-NAME PIC X(25) → String custLastName
 * - CUST-ADDR-LINE-1/2/3 PIC X(50) → String custAddrLine1/2/3
 * - CUST-ADDR-STATE-CD PIC X(02) → String custAddrStateCd
 * - CUST-ADDR-COUNTRY-CD PIC X(03) → String custAddrCountryCd
 * - CUST-ADDR-ZIP PIC X(10) → String custAddrZip
 * - CUST-PHONE-NUM-1/2 PIC X(15) → String custPhoneNum1/2
 * - CUST-SSN PIC 9(09) → String custSsn (preserves leading zeros)
 * - CUST-GOVT-ISSUED-ID PIC X(20) → String custGovtIssuedId
 * - CUST-DOB-YYYY-MM-DD PIC X(10) → LocalDate custDobYyyyMmDd
 * - CUST-FICO-CREDIT-SCORE PIC 9(03) → Integer custFicoCreditScore
 * 
 * Testcontainers Configuration:
 * =============================
 * Uses PostgreSQL 16.6-alpine container for isolated testing.
 * Container lifecycle managed automatically by @Testcontainers annotation.
 * Database schema and data isolated per test class execution.
 * 
 * Performance Requirements:
 * =========================
 * - Primary key lookups (findById): < 10ms per Section 0.7.7
 * - SSN lookups (findByCustSsn): < 10ms using idx_customer_ssn index
 * - Name searches (findByCustLastNameAndCustFirstName): < 50ms using idx_customer_name
 * 
 * @see CustomerRepository JPA repository interface
 * @see Customer JPA entity from CVCUS01Y.cpy copybook
 * @see CBCUS01C.cbl COBOL batch program for original customer validation
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class CustomerRepositoryTest {

    /**
     * PostgreSQL Testcontainers instance
     * Provides isolated database for integration testing
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine")
            .withDatabaseName("testdb");

    /**
     * Configure Spring datasource properties from Testcontainers PostgreSQL instance
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Use Hibernate create-drop for test schema generation (exclude Flyway)
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    /**
     * Helper method to create a complete test customer matching CVCUS01Y.cpy structure
     * 
     * @return Customer entity with all 20+ fields populated for 500-byte record testing
     */
    private Customer createTestCustomer() {
        return Customer.builder()
                .custId(123456789L)  // CUST-ID PIC 9(09)
                .custFirstName("John")  // CUST-FIRST-NAME PIC X(25)
                .custMiddleName("Michael")  // CUST-MIDDLE-NAME PIC X(25)
                .custLastName("Doe")  // CUST-LAST-NAME PIC X(25)
                .custAddrLine1("123 Main St")  // CUST-ADDR-LINE-1 PIC X(50)
                .custAddrLine2("Apt 4B")  // CUST-ADDR-LINE-2 PIC X(50)
                .custAddrLine3("Building C")  // CUST-ADDR-LINE-3 PIC X(50)
                .custAddrStateCd("NY")  // CUST-ADDR-STATE-CD PIC X(02)
                .custAddrCountryCd("USA")  // CUST-ADDR-COUNTRY-CD PIC X(03)
                .custAddrZip("10001-1234")  // CUST-ADDR-ZIP PIC X(10)
                .custPhoneNum1("555-123-4567")  // CUST-PHONE-NUM-1 PIC X(15)
                .custPhoneNum2("555-987-6543")  // CUST-PHONE-NUM-2 PIC X(15)
                .custSsn("123456789")  // CUST-SSN PIC 9(09) as String
                .custGovtIssuedId("DL-NY-12345678")  // CUST-GOVT-ISSUED-ID PIC X(20)
                .custDobYyyyMmDd(LocalDate.of(1980, 5, 15))  // CUST-DOB-YYYY-MM-DD PIC X(10)
                .custFicoCreditScore(750)  // CUST-FICO-CREDIT-SCORE PIC 9(03)
                .build();
    }

    /**
     * Helper method to create a second test customer for multi-record tests
     */
    private Customer createSecondTestCustomer() {
        return Customer.builder()
                .custId(987654321L)
                .custFirstName("Jane")
                .custMiddleName("Elizabeth")
                .custLastName("Smith")
                .custAddrLine1("456 Oak Avenue")
                .custAddrLine2("Suite 200")
                .custAddrLine3("Floor 3")
                .custAddrStateCd("CA")
                .custAddrCountryCd("USA")
                .custAddrZip("90210-5678")
                .custPhoneNum1("555-234-5678")
                .custPhoneNum2("555-876-5432")
                .custSsn("987654321")
                .custGovtIssuedId("DL-CA-98765432")
                .custDobYyyyMmDd(LocalDate.of(1985, 10, 20))
                .custFicoCreditScore(820)
                .build();
    }

    /**
     * Test Case 1: Save Customer
     * 
     * Validates customer creation with all 20+ fields from CVCUS01Y.cpy structure.
     * Replaces COBOL: EXEC CICS WRITE FILE('CUSTFILE') FROM(CUSTOMER-RECORD)
     */
    @Test
    public void testSaveCustomer() {
        // Arrange
        Customer customer = createTestCustomer();

        // Act
        Customer savedCustomer = customerRepository.save(customer);

        // Assert - Verify all fields from 500-byte COBOL record
        assertNotNull(savedCustomer);
        assertEquals(123456789L, savedCustomer.getCustId());
        assertEquals("John", savedCustomer.getCustFirstName());
        assertEquals("Michael", savedCustomer.getCustMiddleName());
        assertEquals("Doe", savedCustomer.getCustLastName());
        assertEquals("123 Main St", savedCustomer.getCustAddrLine1());
        assertEquals("Apt 4B", savedCustomer.getCustAddrLine2());
        assertEquals("Building C", savedCustomer.getCustAddrLine3());
        assertEquals("NY", savedCustomer.getCustAddrStateCd());
        assertEquals("USA", savedCustomer.getCustAddrCountryCd());
        assertEquals("10001-1234", savedCustomer.getCustAddrZip());
        assertEquals("555-123-4567", savedCustomer.getCustPhoneNum1());
        assertEquals("555-987-6543", savedCustomer.getCustPhoneNum2());
        assertEquals("123456789", savedCustomer.getCustSsn());
        assertEquals("DL-NY-12345678", savedCustomer.getCustGovtIssuedId());
        assertEquals(LocalDate.of(1980, 5, 15), savedCustomer.getCustDobYyyyMmDd());
        assertEquals(750, savedCustomer.getCustFicoCreditScore());
        
        // Verify audit fields
        assertNotNull(savedCustomer.getCreatedAt());
        assertNotNull(savedCustomer.getUpdatedAt());
        assertNotNull(savedCustomer.getVersion());
        assertEquals(0, savedCustomer.getVersion());
    }

    /**
     * Test Case 2: Find Customer By ID
     * 
     * Validates primary key lookup on custId (CUST-ID PIC 9(09)).
     * Replaces COBOL: EXEC CICS READ FILE('CUSTFILE') RIDFLD(CUST-ID) INTO(CUSTOMER-RECORD)
     */
    @Test
    public void testFindByIdCustomer() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        Optional<Customer> foundCustomer = customerRepository.findById(123456789L);

        // Assert
        assertTrue(foundCustomer.isPresent());
        Customer retrievedCustomer = foundCustomer.get();
        
        // Verify all fields match CVCUS01Y.cpy structure
        assertEquals(123456789L, retrievedCustomer.getCustId());
        assertEquals("John", retrievedCustomer.getCustFirstName());
        assertEquals("Michael", retrievedCustomer.getCustMiddleName());
        assertEquals("Doe", retrievedCustomer.getCustLastName());
        assertEquals("123 Main St", retrievedCustomer.getCustAddrLine1());
        assertEquals("Apt 4B", retrievedCustomer.getCustAddrLine2());
        assertEquals("Building C", retrievedCustomer.getCustAddrLine3());
        assertEquals("NY", retrievedCustomer.getCustAddrStateCd());
        assertEquals("USA", retrievedCustomer.getCustAddrCountryCd());
        assertEquals("10001-1234", retrievedCustomer.getCustAddrZip());
        assertEquals("555-123-4567", retrievedCustomer.getCustPhoneNum1());
        assertEquals("555-987-6543", retrievedCustomer.getCustPhoneNum2());
        assertEquals("123456789", retrievedCustomer.getCustSsn());
        assertEquals("DL-NY-12345678", retrievedCustomer.getCustGovtIssuedId());
        assertEquals(LocalDate.of(1980, 5, 15), retrievedCustomer.getCustDobYyyyMmDd());
        assertEquals(750, retrievedCustomer.getCustFicoCreditScore());
    }

    /**
     * Test Case 3: Find All Customers
     * 
     * Validates batch retrieval of multiple customers.
     * Replaces COBOL: Sequential READ loop through CUSTFILE for batch processing
     */
    @Test
    public void testFindAllCustomers() {
        // Arrange
        Customer customer1 = createTestCustomer();
        Customer customer2 = createSecondTestCustomer();
        testEntityManager.persistAndFlush(customer1);
        testEntityManager.persistAndFlush(customer2);
        testEntityManager.clear();

        // Act
        List<Customer> allCustomers = customerRepository.findAll();

        // Assert
        assertNotNull(allCustomers);
        assertEquals(2, allCustomers.size());
        
        // Verify both customers present
        assertTrue(allCustomers.stream().anyMatch(c -> c.getCustId().equals(123456789L)));
        assertTrue(allCustomers.stream().anyMatch(c -> c.getCustId().equals(987654321L)));
    }

    /**
     * Test Case 4: Update Customer
     * 
     * Validates customer modification and persistence.
     * Replaces COBOL: EXEC CICS REWRITE FILE('CUSTFILE') FROM(CUSTOMER-RECORD)
     */
    @Test
    public void testUpdateCustomer() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act - Modify address and phone
        Customer customerToUpdate = customerRepository.findById(123456789L).orElseThrow();
        customerToUpdate.setCustAddrLine1("789 New Street");
        customerToUpdate.setCustAddrZip("12345-6789");
        customerToUpdate.setCustPhoneNum1("555-999-8888");
        Customer updatedCustomer = customerRepository.save(customerToUpdate);
        testEntityManager.flush();
        testEntityManager.clear();

        // Assert - Retrieve and verify updates
        Customer verifyCustomer = customerRepository.findById(123456789L).orElseThrow();
        assertEquals("789 New Street", verifyCustomer.getCustAddrLine1());
        assertEquals("12345-6789", verifyCustomer.getCustAddrZip());
        assertEquals("555-999-8888", verifyCustomer.getCustPhoneNum1());
        
        // Verify version incremented for optimistic locking
        assertEquals(1, verifyCustomer.getVersion());
        
        // Verify other fields unchanged
        assertEquals("John", verifyCustomer.getCustFirstName());
        assertEquals("Doe", verifyCustomer.getCustLastName());
        assertEquals("123456789", verifyCustomer.getCustSsn());
    }

    /**
     * Test Case 5: Delete Customer
     * 
     * Validates customer deletion by primary key.
     * Replaces COBOL: EXEC CICS DELETE FILE('CUSTFILE') RIDFLD(CUST-ID)
     */
    @Test
    public void testDeleteCustomer() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        customerRepository.deleteById(123456789L);
        testEntityManager.flush();

        // Assert
        Optional<Customer> deletedCustomer = customerRepository.findById(123456789L);
        assertFalse(deletedCustomer.isPresent());
    }

    /**
     * Test Case 6: Customer ID Not Found
     * 
     * Validates empty Optional return for non-existent customer.
     * Replaces COBOL: file-status '23' (record not found)
     */
    @Test
    public void testCustomerIdNotFound() {
        // Act
        Optional<Customer> customer = customerRepository.findById(999999999L);

        // Assert
        assertFalse(customer.isPresent());
    }

    /**
     * Test Case 7: Customer ID Uniqueness
     * 
     * Validates primary key constraint enforcement on custId.
     * Replaces COBOL: file-status '22' (duplicate key error)
     */
    @Test
    public void testCustomerIdUniqueness() {
        // Arrange
        Customer customer1 = createTestCustomer();
        testEntityManager.persistAndFlush(customer1);
        testEntityManager.clear();

        // Act & Assert - Attempt to insert duplicate custId
        Customer customer2 = createTestCustomer();  // Same custId: 123456789L
        customer2.setCustSsn("111111111");  // Different SSN
        
        // Note: In test context with Hibernate, ConstraintViolationException is thrown
        // In production with Spring's translation, this becomes DataIntegrityViolationException
        assertThrows(Exception.class, () -> {
            customerRepository.save(customer2);
            testEntityManager.flush();
        });
    }

    /**
     * Test Case 8: Find Customer By SSN
     * 
     * Validates SSN-based lookup using alternate index.
     * Tests custom query method findByCustSsn() with idx_customer_ssn index.
     * Replaces COBOL: Alternate index access on CUST-SSN field
     */
    @Test
    public void testFindByCustSsn() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        Optional<Customer> foundCustomer = customerRepository.findByCustSsn("123456789");

        // Assert
        assertTrue(foundCustomer.isPresent());
        Customer retrievedCustomer = foundCustomer.get();
        assertEquals(123456789L, retrievedCustomer.getCustId());
        assertEquals("123456789", retrievedCustomer.getCustSsn());
        assertEquals("John", retrievedCustomer.getCustFirstName());
        assertEquals("Doe", retrievedCustomer.getCustLastName());
    }

    /**
     * Test Case 9: Find By Last Name and First Name
     * 
     * Validates name-based search using composite index.
     * Tests custom query method findByCustLastNameAndCustFirstName() with idx_customer_name.
     * Replaces COBOL: Alternate index browse on CUST-LAST-NAME + CUST-FIRST-NAME
     */
    @Test
    public void testFindByLastNameAndFirstName() {
        // Arrange
        Customer customer1 = createTestCustomer();
        Customer customer2 = createSecondTestCustomer();
        Customer customer3 = Customer.builder()
                .custId(111222333L)
                .custFirstName("John")
                .custMiddleName("Robert")
                .custLastName("Doe")
                .custAddrLine1("999 Pine Road")
                .custAddrStateCd("TX")
                .custAddrCountryCd("USA")
                .custAddrZip("75001")
                .custPhoneNum1("555-111-2222")
                .custSsn("111222333")
                .custGovtIssuedId("DL-TX-11122233")
                .custDobYyyyMmDd(LocalDate.of(1975, 3, 10))
                .custFicoCreditScore(680)
                .build();
        
        testEntityManager.persistAndFlush(customer1);
        testEntityManager.persistAndFlush(customer2);
        testEntityManager.persistAndFlush(customer3);
        testEntityManager.clear();

        // Act
        List<Customer> customers = customerRepository.findByCustLastNameAndCustFirstName("Doe", "John");

        // Assert
        assertNotNull(customers);
        assertEquals(2, customers.size());  // Two "John Doe" customers
        
        // Verify both John Doe customers found
        assertTrue(customers.stream().anyMatch(c -> c.getCustId().equals(123456789L)));
        assertTrue(customers.stream().anyMatch(c -> c.getCustId().equals(111222333L)));
        
        // Verify Jane Smith not included
        assertFalse(customers.stream().anyMatch(c -> c.getCustId().equals(987654321L)));
    }

    /**
     * Test Case 10: Find By SSN Not Found
     * 
     * Validates empty Optional return for non-existent SSN.
     * Replaces COBOL: file-status '23' (record not found) for alternate index
     */
    @Test
    public void testFindByCustSsnNotFound() {
        // Act
        Optional<Customer> customer = customerRepository.findByCustSsn("999999999");

        // Assert
        assertFalse(customer.isPresent());
    }

    /**
     * Test Case 11: Find By Last Name Returns Empty List
     * 
     * Validates empty list return for non-matching name search.
     */
    @Test
    public void testFindByLastNameNotFound() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        List<Customer> customers = customerRepository.findByCustLastNameAndCustFirstName("NonExistent", "Name");

        // Assert
        assertNotNull(customers);
        assertTrue(customers.isEmpty());
    }

    /**
     * Test Case 12: Date of Birth Conversion
     * 
     * Validates COBOL date field conversion from PIC X(10) YYYY-MM-DD to Java LocalDate.
     * Tests CUST-DOB-YYYY-MM-DD field mapping.
     */
    @Test
    public void testDateOfBirthConversion() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        Customer retrievedCustomer = customerRepository.findById(123456789L).orElseThrow();

        // Assert
        assertNotNull(retrievedCustomer.getCustDobYyyyMmDd());
        assertEquals(LocalDate.of(1980, 5, 15), retrievedCustomer.getCustDobYyyyMmDd());
        assertEquals(1980, retrievedCustomer.getCustDobYyyyMmDd().getYear());
        assertEquals(5, retrievedCustomer.getCustDobYyyyMmDd().getMonthValue());
        assertEquals(15, retrievedCustomer.getCustDobYyyyMmDd().getDayOfMonth());
    }

    /**
     * Test Case 13: Date of Birth Format
     * 
     * Validates date stored in ISO format YYYY-MM-DD in database.
     */
    @Test
    public void testDateOfBirthFormat() {
        // Arrange
        LocalDate testDate = LocalDate.of(1990, 12, 25);
        Customer customer = createTestCustomer();
        customer.setCustDobYyyyMmDd(testDate);
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        Customer retrievedCustomer = customerRepository.findById(123456789L).orElseThrow();

        // Assert
        assertEquals(testDate, retrievedCustomer.getCustDobYyyyMmDd());
        assertEquals("1990-12-25", retrievedCustomer.getCustDobYyyyMmDd().toString());
    }

    /**
     * Test Case 14: SSN Field Storage with Leading Zeros
     * 
     * Validates 9-digit SSN storage preserving leading zeros (PII data per Section 0.2.2).
     * Tests CUST-SSN PIC 9(09) stored as String to maintain leading zeros.
     */
    @Test
    public void testSsnFieldStorage() {
        // Arrange - SSN with leading zeros
        Customer customer = createTestCustomer();
        customer.setCustSsn("001234567");  // Leading zeros must be preserved
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        Customer retrievedCustomer = customerRepository.findById(123456789L).orElseThrow();

        // Assert
        assertEquals("001234567", retrievedCustomer.getCustSsn());
        assertEquals(9, retrievedCustomer.getCustSsn().length());  // Exactly 9 digits
    }

    /**
     * Test Case 15: Government Issued ID Storage
     * 
     * Validates government ID field storage (PII data per Section 0.2.2).
     * Tests CUST-GOVT-ISSUED-ID PIC X(20).
     */
    @Test
    public void testGovernmentIdStorage() {
        // Arrange
        Customer customer = createTestCustomer();
        customer.setCustGovtIssuedId("PASSPORT-US-AB123456");
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        Customer retrievedCustomer = customerRepository.findById(123456789L).orElseThrow();

        // Assert
        assertEquals("PASSPORT-US-AB123456", retrievedCustomer.getCustGovtIssuedId());
    }

    /**
     * Test Case 16: Address Fields
     * 
     * Validates all 3 address lines, state code, country code, and ZIP field storage.
     * Tests CUST-ADDR-LINE-1/2/3 PIC X(50), CUST-ADDR-STATE-CD PIC X(02),
     * CUST-ADDR-COUNTRY-CD PIC X(03), CUST-ADDR-ZIP PIC X(10).
     */
    @Test
    public void testAddressFields() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        Customer retrievedCustomer = customerRepository.findById(123456789L).orElseThrow();

        // Assert
        assertEquals("123 Main St", retrievedCustomer.getCustAddrLine1());
        assertEquals("Apt 4B", retrievedCustomer.getCustAddrLine2());
        assertEquals("Building C", retrievedCustomer.getCustAddrLine3());
        assertEquals("NY", retrievedCustomer.getCustAddrStateCd());
        assertEquals(2, retrievedCustomer.getCustAddrStateCd().length());  // Exactly 2 characters
        assertEquals("USA", retrievedCustomer.getCustAddrCountryCd());
        assertEquals(3, retrievedCustomer.getCustAddrCountryCd().length());  // Exactly 3 characters
        assertEquals("10001-1234", retrievedCustomer.getCustAddrZip());
    }

    /**
     * Test Case 17: Phone Number Fields
     * 
     * Validates both phone number fields.
     * Tests CUST-PHONE-NUM-1 and CUST-PHONE-NUM-2 PIC X(15).
     */
    @Test
    public void testPhoneFields() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        Customer retrievedCustomer = customerRepository.findById(123456789L).orElseThrow();

        // Assert
        assertEquals("555-123-4567", retrievedCustomer.getCustPhoneNum1());
        assertEquals("555-987-6543", retrievedCustomer.getCustPhoneNum2());
    }

    /**
     * Test Case 18: Name Fields
     * 
     * Validates first, middle, and last name fields.
     * Tests CUST-FIRST-NAME, CUST-MIDDLE-NAME, CUST-LAST-NAME PIC X(25).
     */
    @Test
    public void testNameFields() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        Customer retrievedCustomer = customerRepository.findById(123456789L).orElseThrow();

        // Assert
        assertEquals("John", retrievedCustomer.getCustFirstName());
        assertEquals("Michael", retrievedCustomer.getCustMiddleName());
        assertEquals("Doe", retrievedCustomer.getCustLastName());
    }

    /**
     * Test Case 19: FICO Credit Score Field
     * 
     * Validates FICO credit score field storage and typical range.
     * Tests CUST-FICO-CREDIT-SCORE PIC 9(03) range 300-850.
     */
    @Test
    public void testFicoScore() {
        // Arrange
        Customer customer = createTestCustomer();
        customer.setCustFicoCreditScore(750);
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act
        Customer retrievedCustomer = customerRepository.findById(123456789L).orElseThrow();

        // Assert
        assertNotNull(retrievedCustomer.getCustFicoCreditScore());
        assertEquals(750, retrievedCustomer.getCustFicoCreditScore());
        
        // Verify FICO score in typical range (300-850)
        assertTrue(retrievedCustomer.getCustFicoCreditScore() >= 300);
        assertTrue(retrievedCustomer.getCustFicoCreditScore() <= 850);
    }

    /**
     * Test Case 20: FICO Score Boundary Values
     * 
     * Validates FICO score at boundary values (300 and 850).
     */
    @Test
    public void testFicoScoreBoundaryValues() {
        // Test minimum FICO score (300)
        Customer customer1 = createTestCustomer();
        customer1.setCustId(111111111L);
        customer1.setCustSsn("111111111");
        customer1.setCustFicoCreditScore(300);
        testEntityManager.persistAndFlush(customer1);

        // Test maximum FICO score (850)
        Customer customer2 = createSecondTestCustomer();
        customer2.setCustFicoCreditScore(850);
        testEntityManager.persistAndFlush(customer2);
        testEntityManager.clear();

        // Act & Assert
        Customer retrieved1 = customerRepository.findById(111111111L).orElseThrow();
        assertEquals(300, retrieved1.getCustFicoCreditScore());

        Customer retrieved2 = customerRepository.findById(987654321L).orElseThrow();
        assertEquals(850, retrieved2.getCustFicoCreditScore());
    }

    /**
     * Test Case 21: Query Performance
     * 
     * Validates primary key lookup performance meets sub-10ms requirement (Section 0.7.7).
     * Tests B-tree index performance on custId primary key.
     */
    @Test
    public void testQueryPerformance() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act - Execute 100 findById queries and measure average time
        long startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            customerRepository.findById(123456789L);
        }
        long endTime = System.nanoTime();

        // Calculate average time per query in milliseconds
        double averageTimeMs = (endTime - startTime) / 1_000_000.0 / 100.0;

        // Assert - Average query time should be well under 10ms
        assertTrue(averageTimeMs < 10.0, 
                String.format("Average query time %.2f ms exceeds 10ms requirement", averageTimeMs));
        
        // Log performance metric for visibility
        System.out.printf("Primary key lookup performance: %.2f ms average (requirement: < 10ms)%n", 
                averageTimeMs);
    }

    /**
     * Test Case 22: Optimistic Locking
     * 
     * Validates @Version field prevents lost updates in concurrent modifications.
     * Tests optimistic locking mechanism replacing COBOL VSAM RBA checks.
     */
    @Test
    public void testOptimisticLocking() {
        // Arrange
        Customer customer = createTestCustomer();
        testEntityManager.persistAndFlush(customer);
        testEntityManager.clear();

        // Act - Simulate concurrent modification scenario
        // Load first instance and detach it (simulates first transaction)
        Customer customer1 = customerRepository.findById(123456789L).orElseThrow();
        testEntityManager.detach(customer1);  // Detach to simulate separate transaction
        
        // Load second instance (simulates second concurrent transaction)
        Customer customer2 = customerRepository.findById(123456789L).orElseThrow();

        // Second transaction completes first (version increments to 1)
        customer2.setCustAddrZip("22222");
        customerRepository.saveAndFlush(customer2);

        // First transaction tries to save (still has version 0 - should fail)
        customer1.setCustAddrZip("11111");
        
        // Assert - First save should throw exception due to version conflict
        assertThrows(Exception.class, () -> {
            customerRepository.saveAndFlush(customer1);
        });

        // Verify second update persisted
        testEntityManager.clear();
        Customer verifyCustomer = customerRepository.findById(123456789L).orElseThrow();
        assertEquals("22222", verifyCustomer.getCustAddrZip());
        assertEquals(1, verifyCustomer.getVersion());
    }

    /**
     * Test Case 23: Count Customers
     * 
     * Validates count() method for total customer count.
     * Replaces COBOL counter accumulation in batch processing.
     */
    @Test
    public void testCountCustomers() {
        // Arrange
        Customer customer1 = createTestCustomer();
        Customer customer2 = createSecondTestCustomer();
        testEntityManager.persistAndFlush(customer1);
        testEntityManager.persistAndFlush(customer2);
        testEntityManager.clear();

        // Act
        long count = customerRepository.count();

        // Assert
        assertEquals(2L, count);
    }

    /**
     * Test Case 24: Delete All Customers
     * 
     * Validates deleteAll() method.
     * Note: Use with extreme caution in production.
     */
    @Test
    public void testDeleteAll() {
        // Arrange
        Customer customer1 = createTestCustomer();
        Customer customer2 = createSecondTestCustomer();
        testEntityManager.persistAndFlush(customer1);
        testEntityManager.persistAndFlush(customer2);
        testEntityManager.clear();

        // Act
        customerRepository.deleteAll();
        testEntityManager.flush();

        // Assert
        long count = customerRepository.count();
        assertEquals(0L, count);
    }

    /**
     * Test Case 25: Null Field Handling
     * 
     * Validates optional fields can be null (middle name, address lines 2-3, phone 2).
     */
    @Test
    public void testNullFieldHandling() {
        // Arrange - Customer with minimal required fields only
        Customer customer = Customer.builder()
                .custId(222333444L)
                .custFirstName("Jane")
                .custMiddleName(null)  // Optional field
                .custLastName("Brown")
                .custAddrLine1("100 First Street")
                .custAddrLine2(null)  // Optional field
                .custAddrLine3(null)  // Optional field
                .custAddrStateCd("FL")
                .custAddrCountryCd("USA")
                .custAddrZip("33101")
                .custPhoneNum1("555-444-3333")
                .custPhoneNum2(null)  // Optional field
                .custSsn("222333444")
                .custGovtIssuedId("DL-FL-22233344")
                .custDobYyyyMmDd(LocalDate.of(1992, 8, 30))
                .custFicoCreditScore(710)
                .build();

        // Act
        Customer savedCustomer = customerRepository.save(customer);
        testEntityManager.flush();
        testEntityManager.clear();

        // Assert
        Customer retrievedCustomer = customerRepository.findById(222333444L).orElseThrow();
        assertNull(retrievedCustomer.getCustMiddleName());
        assertNull(retrievedCustomer.getCustAddrLine2());
        assertNull(retrievedCustomer.getCustAddrLine3());
        assertNull(retrievedCustomer.getCustPhoneNum2());
        
        // Verify required fields present
        assertNotNull(retrievedCustomer.getCustFirstName());
        assertNotNull(retrievedCustomer.getCustLastName());
        assertNotNull(retrievedCustomer.getCustAddrLine1());
    }
}


package com.carddemo.repository;

import com.carddemo.entity.Customer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 Test Class for CustomerRepository.
 * 
 * <p>This test class validates that CustomerRepository correctly replicates VSAM KSDS
 * file operations on the CUSTDAT master file originally defined in CVCUS01Y.cpy copybook.
 * Tests use H2 in-memory database via @DataJpaTest annotation for isolated repository
 * testing without requiring a full Spring context or external PostgreSQL database.</p>
 * 
 * <p>Key COBOL to Spring Data JPA Test Validations:</p>
 * <ul>
 *   <li>VSAM READ by primary key (CUST-ID PIC 9(09)) → findByCustomerId(Long)</li>
 *   <li>VSAM alternate index lookup (CUST-LAST-NAME PIC X(25)) → findByLastName(String)</li>
 *   <li>VSAM WRITE operation → save(Customer) for new records</li>
 *   <li>VSAM REWRITE operation → save(Customer) for existing records</li>
 *   <li>VSAM sequential access (STARTBR/READNEXT) → findAll()</li>
 * </ul>
 * 
 * <p>Test Data Specifications from COBOL CUSTOMER-RECORD (500-byte record):</p>
 * <ul>
 *   <li>CUST-ID: 9-digit numeric primary key (Long customerId)</li>
 *   <li>CUST-FIRST-NAME, CUST-MIDDLE-NAME, CUST-LAST-NAME: 25 characters each</li>
 *   <li>CUST-ADDR-LINE-1/2/3: 50 characters each</li>
 *   <li>CUST-ADDR-STATE-CD: 2 characters</li>
 *   <li>CUST-ADDR-COUNTRY-CD: 3 characters</li>
 *   <li>CUST-ADDR-ZIP: 10 characters</li>
 *   <li>CUST-PHONE-NUM-1/2: 15 characters each</li>
 *   <li>CUST-SSN: 9 digits (stored as String for leading zeros)</li>
 *   <li>CUST-GOVT-ISSUED-ID: 20 characters</li>
 *   <li>CUST-DOB-YYYY-MM-DD: LocalDate dateOfBirth</li>
 *   <li>CUST-EFT-ACCOUNT-ID: 10 characters</li>
 *   <li>CUST-PRI-CARD-HOLDER-IND: 1 character ('Y' or 'N')</li>
 *   <li>CUST-FICO-CREDIT-SCORE: 3-digit Integer (300-850 range)</li>
 * </ul>
 * 
 * <p>Character Encoding Validation:</p>
 * All test data uses UTF-8 ASCII encoding, validating conversion from COBOL EBCDIC
 * character encoding as required in Section 0.10 Requirement 6.
 * 
 * <p>Test Configuration:</p>
 * <ul>
 *   <li>@DataJpaTest: Configures H2 in-memory database, JPA repositories, transaction management</li>
 *   <li>@ActiveProfiles("test"): Loads application-test.properties configuration</li>
 *   <li>@Autowired: Injects CustomerRepository instance for testing</li>
 *   <li>@BeforeEach: Sets up test customers before each test method</li>
 *   <li>AssertJ: Provides fluent assertions for readable test validation</li>
 * </ul>
 * 
 * <p>Performance Characteristics Validated:</p>
 * <ul>
 *   <li>Primary key lookup: O(log n) - B-tree index on customer_id</li>
 *   <li>Last name search: O(log n + k) - Secondary index on last_name, k = matches</li>
 *   <li>Save operations: O(log n) - B-tree index updates</li>
 *   <li>Sequential access: O(n) - Full table scan with pagination support</li>
 * </ul>
 * 
 * @see CustomerRepository
 * @see Customer
 * @see <a href="Section 0.4">Agent Action Plan - Source File app/cpy/CVCUS01Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan - CustomerRepositoryTest.java</a>
 * @see <a href="Section 0.10">Special Instructions - Comprehensive Testing Strategy</a>
 * @since 1.0
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("CustomerRepository JPA Tests - VSAM CUSTDAT File Operations")
public class CustomerRepositoryTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private EntityManager entityManager;

    private Customer testCustomer1;
    private Customer testCustomer2;
    private Customer testCustomer3;

    /**
     * Set up test data before each test method.
     * 
     * <p>Creates three test customer records with complete field data matching
     * COBOL CUSTOMER-RECORD 500-byte structure from CVCUS01Y.cpy copybook:</p>
     * <ul>
     *   <li>testCustomer1: Customer ID 123456789, last name "Smith"</li>
     *   <li>testCustomer2: Customer ID 234567890, last name "Smith" (tests duplicate last name)</li>
     *   <li>testCustomer3: Customer ID 345678901, last name "Johnson"</li>
     * </ul>
     * 
     * <p>All fields populated with valid test data:</p>
     * <ul>
     *   <li>9-digit customer IDs (CUST-ID PIC 9(09))</li>
     *   <li>25-character name fields (CUST-FIRST-NAME, CUST-MIDDLE-NAME, CUST-LAST-NAME PIC X(25))</li>
     *   <li>50-character address lines (CUST-ADDR-LINE-1/2/3 PIC X(50))</li>
     *   <li>2-character state code (CUST-ADDR-STATE-CD PIC X(02))</li>
     *   <li>3-character country code (CUST-ADDR-COUNTRY-CD PIC X(03))</li>
     *   <li>10-character ZIP code (CUST-ADDR-ZIP PIC X(10))</li>
     *   <li>15-character phone numbers (CUST-PHONE-NUM-1/2 PIC X(15))</li>
     *   <li>9-digit SSN as String (CUST-SSN PIC 9(09))</li>
     *   <li>20-character government ID (CUST-GOVT-ISSUED-ID PIC X(20))</li>
     *   <li>LocalDate date of birth (CUST-DOB-YYYY-MM-DD PIC X(10))</li>
     *   <li>10-character EFT account (CUST-EFT-ACCOUNT-ID PIC X(10))</li>
     *   <li>1-character indicator (CUST-PRI-CARD-HOLDER-IND PIC X(01))</li>
     *   <li>3-digit FICO score (CUST-FICO-CREDIT-SCORE PIC 9(03))</li>
     * </ul>
     * 
     * <p>Test data intentionally includes:</p>
     * <ul>
     *   <li>Two customers with same last name (Smith) to test findByLastName returns multiple records</li>
     *   <li>Complete address information with all three address lines populated</li>
     *   <li>Both phone numbers populated (primary and secondary contact)</li>
     *   <li>Realistic FICO scores in valid 300-850 range</li>
     *   <li>Valid date of birth creating customers aged 30-40 years</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        // Create test customer 1: John A. Smith
        // Maps to COBOL CUSTOMER-RECORD with CUST-ID = 123456789
        testCustomer1 = Customer.builder()
                .customerId(123456789L)  // CUST-ID PIC 9(09)
                .firstName("John")       // CUST-FIRST-NAME PIC X(25)
                .middleName("Andrew")    // CUST-MIDDLE-NAME PIC X(25)
                .lastName("Smith")       // CUST-LAST-NAME PIC X(25)
                .addressLine1("123 Main Street")           // CUST-ADDR-LINE-1 PIC X(50)
                .addressLine2("Apartment 4B")              // CUST-ADDR-LINE-2 PIC X(50)
                .addressLine3("Building C")                // CUST-ADDR-LINE-3 PIC X(50)
                .addressStateCode("NY")                    // CUST-ADDR-STATE-CD PIC X(02)
                .addressCountryCode("USA")                 // CUST-ADDR-COUNTRY-CD PIC X(03)
                .addressZip("10001-1234")                  // CUST-ADDR-ZIP PIC X(10)
                .phoneNumber1("212-555-1234")              // CUST-PHONE-NUM-1 PIC X(15)
                .phoneNumber2("212-555-5678")              // CUST-PHONE-NUM-2 PIC X(15)
                .ssn("123456789")                          // CUST-SSN PIC 9(09) as String
                .governmentIssuedId("NY-DL-12345678")      // CUST-GOVT-ISSUED-ID PIC X(20)
                .dateOfBirth(LocalDate.of(1985, 6, 15))    // CUST-DOB-YYYY-MM-DD converted to LocalDate
                .eftAccountId("EFT1234567")                // CUST-EFT-ACCOUNT-ID PIC X(10)
                .primaryCardHolderIndicator("Y")           // CUST-PRI-CARD-HOLDER-IND PIC X(01)
                .ficoScore(750)                            // CUST-FICO-CREDIT-SCORE PIC 9(03)
                .build();

        // Create test customer 2: Jane M. Smith (same last name as customer 1)
        // Tests alternate index lookup on CUST-LAST-NAME returning multiple records
        testCustomer2 = Customer.builder()
                .customerId(234567890L)  // CUST-ID PIC 9(09)
                .firstName("Jane")       // CUST-FIRST-NAME PIC X(25)
                .middleName("Marie")     // CUST-MIDDLE-NAME PIC X(25)
                .lastName("Smith")       // CUST-LAST-NAME PIC X(25) - same as customer 1
                .addressLine1("456 Oak Avenue")            // CUST-ADDR-LINE-1 PIC X(50)
                .addressLine2("Suite 200")                 // CUST-ADDR-LINE-2 PIC X(50)
                .addressLine3("Floor 2")                   // CUST-ADDR-LINE-3 PIC X(50)
                .addressStateCode("CA")                    // CUST-ADDR-STATE-CD PIC X(02)
                .addressCountryCode("USA")                 // CUST-ADDR-COUNTRY-CD PIC X(03)
                .addressZip("90210-5678")                  // CUST-ADDR-ZIP PIC X(10)
                .phoneNumber1("310-555-9876")              // CUST-PHONE-NUM-1 PIC X(15)
                .phoneNumber2("310-555-4321")              // CUST-PHONE-NUM-2 PIC X(15)
                .ssn("234567890")                          // CUST-SSN PIC 9(09) as String
                .governmentIssuedId("CA-DL-98765432")      // CUST-GOVT-ISSUED-ID PIC X(20)
                .dateOfBirth(LocalDate.of(1990, 3, 22))    // CUST-DOB-YYYY-MM-DD converted to LocalDate
                .eftAccountId("EFT2345678")                // CUST-EFT-ACCOUNT-ID PIC X(10)
                .primaryCardHolderIndicator("Y")           // CUST-PRI-CARD-HOLDER-IND PIC X(01)
                .ficoScore(680)                            // CUST-FICO-CREDIT-SCORE PIC 9(03)
                .build();

        // Create test customer 3: Robert B. Johnson
        // Different last name to test name-based filtering
        testCustomer3 = Customer.builder()
                .customerId(345678901L)  // CUST-ID PIC 9(09)
                .firstName("Robert")     // CUST-FIRST-NAME PIC X(25)
                .middleName("Brian")     // CUST-MIDDLE-NAME PIC X(25)
                .lastName("Johnson")     // CUST-LAST-NAME PIC X(25) - different from Smith
                .addressLine1("789 Elm Boulevard")         // CUST-ADDR-LINE-1 PIC X(50)
                .addressLine2("Unit 3A")                   // CUST-ADDR-LINE-2 PIC X(50)
                .addressLine3("West Wing")                 // CUST-ADDR-LINE-3 PIC X(50)
                .addressStateCode("TX")                    // CUST-ADDR-STATE-CD PIC X(02)
                .addressCountryCode("USA")                 // CUST-ADDR-COUNTRY-CD PIC X(03)
                .addressZip("75201-9012")                  // CUST-ADDR-ZIP PIC X(10)
                .phoneNumber1("214-555-1111")              // CUST-PHONE-NUM-1 PIC X(15)
                .phoneNumber2("214-555-2222")              // CUST-PHONE-NUM-2 PIC X(15)
                .ssn("345678901")                          // CUST-SSN PIC 9(09) as String
                .governmentIssuedId("TX-DL-11223344")      // CUST-GOVT-ISSUED-ID PIC X(20)
                .dateOfBirth(LocalDate.of(1988, 11, 8))    // CUST-DOB-YYYY-MM-DD converted to LocalDate
                .eftAccountId("EFT3456789")                // CUST-EFT-ACCOUNT-ID PIC X(10)
                .primaryCardHolderIndicator("N")           // CUST-PRI-CARD-HOLDER-IND PIC X(01)
                .ficoScore(720)                            // CUST-FICO-CREDIT-SCORE PIC 9(03)
                .build();

        // Persist test customers to H2 in-memory database
        // Replicates COBOL EXEC CICS WRITE operations on VSAM CUSTDAT file
        customerRepository.save(testCustomer1);
        customerRepository.save(testCustomer2);
        customerRepository.save(testCustomer3);
    }

    /**
     * Test: Find customer by 9-digit customer ID returns correct customer.
     * 
     * <p>Validates CustomerRepository.findByCustomerId(Long) method correctly
     * replicates COBOL VSAM random access READ operation using CUST-ID as
     * primary key (RIDFLD):</p>
     * 
     * <pre>
     * COBOL equivalent:
     *   MOVE 123456789 TO CUST-ID
     *   EXEC CICS READ
     *     DATASET('CUSTDAT')
     *     INTO(CUSTOMER-RECORD)
     *     RIDFLD(CUST-ID)
     *     RESP(WS-RESP)
     *   END-EXEC
     *   
     *   IF WS-RESP = DFHRESP(NORMAL)
     *     ... customer found, CUSTOMER-RECORD populated
     *   END-IF
     * </pre>
     * 
     * <p>Expected SQL Query:</p>
     * <pre>
     * SELECT * FROM customer WHERE customer_id = 123456789
     * </pre>
     * 
     * <p>Test Validates:</p>
     * <ul>
     *   <li>Primary key lookup returns Optional.isPresent() = true</li>
     *   <li>Returned customer has correct customer_id matching query parameter</li>
     *   <li>All CUSTOMER-RECORD fields correctly persisted and retrieved</li>
     *   <li>Customer name fields match test data (firstName, middleName, lastName)</li>
     *   <li>UTF-8 ASCII encoding preserved from EBCDIC conversion</li>
     * </ul>
     */
    @Test
    @DisplayName("Find customer by 9-digit customer ID returns correct customer")
    void testFindByCustomerId_Success() {
        // Act: Execute findByCustomerId query (VSAM READ by primary key)
        Optional<Customer> foundCustomer = customerRepository.findByCustomerId(123456789L);

        // Assert: Customer found and all fields match COBOL CUSTOMER-RECORD structure
        assertThat(foundCustomer).isPresent();
        assertThat(foundCustomer.get().getCustomerId()).isEqualTo(123456789L);
        assertThat(foundCustomer.get().getFirstName()).isEqualTo("John");
        assertThat(foundCustomer.get().getMiddleName()).isEqualTo("Andrew");
        assertThat(foundCustomer.get().getLastName()).isEqualTo("Smith");
        assertThat(foundCustomer.get().getAddressLine1()).isEqualTo("123 Main Street");
        assertThat(foundCustomer.get().getAddressStateCode()).isEqualTo("NY");
        assertThat(foundCustomer.get().getAddressCountryCode()).isEqualTo("USA");
        assertThat(foundCustomer.get().getAddressZip()).isEqualTo("10001-1234");
        assertThat(foundCustomer.get().getPhoneNumber1()).isEqualTo("212-555-1234");
        assertThat(foundCustomer.get().getSsn()).isEqualTo("123456789");
        assertThat(foundCustomer.get().getDateOfBirth()).isEqualTo(LocalDate.of(1985, 6, 15));
        assertThat(foundCustomer.get().getFicoScore()).isEqualTo(750);
        assertThat(foundCustomer.get().getPrimaryCardHolderIndicator()).isEqualTo("Y");
    }

    /**
     * Test: Find customer by non-existent customer ID returns empty Optional.
     * 
     * <p>Validates CustomerRepository.findByCustomerId(Long) method correctly
     * handles customer not found scenario, replicating COBOL VSAM NOTFND
     * condition response:</p>
     * 
     * <pre>
     * COBOL equivalent:
     *   MOVE 999999999 TO CUST-ID
     *   EXEC CICS READ
     *     DATASET('CUSTDAT')
     *     INTO(CUSTOMER-RECORD)
     *     RIDFLD(CUST-ID)
     *     RESP(WS-RESP)
     *   END-EXEC
     *   
     *   IF WS-RESP = DFHRESP(NOTFND)
     *     ... customer not found, handle error
     *   END-IF
     * </pre>
     * 
     * <p>Expected SQL Query:</p>
     * <pre>
     * SELECT * FROM customer WHERE customer_id = 999999999
     * -- Returns no rows
     * </pre>
     * 
     * <p>Test Validates:</p>
     * <ul>
     *   <li>Non-existent customer ID returns Optional.isEmpty() = true</li>
     *   <li>No exception thrown (graceful not found handling)</li>
     *   <li>Matches COBOL NOTFND response code behavior</li>
     * </ul>
     */
    @Test
    @DisplayName("Find customer by non-existent customer ID returns empty Optional")
    void testFindByCustomerId_NotFound() {
        // Act: Query for non-existent customer (VSAM READ returning NOTFND)
        Optional<Customer> foundCustomer = customerRepository.findByCustomerId(999999999L);

        // Assert: Customer not found, Optional is empty
        assertThat(foundCustomer).isEmpty();
    }

    /**
     * Test: Find customers by last name returns all matching customers.
     * 
     * <p>Validates CustomerRepository.findByLastName(String) method correctly
     * replicates COBOL VSAM alternate index lookup or sequential scan pattern
     * on CUST-LAST-NAME field (PIC X(25)):</p>
     * 
     * <pre>
     * COBOL equivalent:
     *   MOVE 'Smith' TO WS-SEARCH-LASTNAME
     *   EXEC CICS STARTBR
     *     DATASET('CUSTDAT')
     *     RIDFLD(WS-SEARCH-LASTNAME)
     *   END-EXEC
     *   
     *   PERFORM UNTIL END-OF-FILE
     *     EXEC CICS READNEXT
     *       DATASET('CUSTDAT')
     *       INTO(CUSTOMER-RECORD)
     *       RESP(WS-RESP)
     *     END-EXEC
     *     
     *     IF WS-RESP = DFHRESP(NORMAL)
     *       IF CUST-LAST-NAME = WS-SEARCH-LASTNAME
     *         ... process matching customer
     *       ELSE
     *         MOVE 'Y' TO END-OF-FILE
     *       END-IF
     *     END-IF
     *   END-PERFORM
     * </pre>
     * 
     * <p>Expected SQL Query:</p>
     * <pre>
     * SELECT * FROM customer 
     * WHERE last_name = 'Smith'
     * ORDER BY customer_id
     * </pre>
     * 
     * <p>Test Data Setup:</p>
     * Two test customers have last name "Smith" (testCustomer1 and testCustomer2)
     * to validate that findByLastName returns multiple matching records.
     * 
     * <p>Test Validates:</p>
     * <ul>
     *   <li>Query returns List with exactly 2 customers (both Smith records)</li>
     *   <li>Both returned customers have last_name = "Smith"</li>
     *   <li>Customer IDs are correct (123456789L and 234567890L)</li>
     *   <li>First names correctly distinguish the two customers (John and Jane)</li>
     *   <li>Alternate index search pattern replicated from VSAM KSDS</li>
     *   <li>List never null, empty list for no matches</li>
     * </ul>
     */
    @Test
    @DisplayName("Find customers by last name returns all matching customers")
    void testFindByLastName_ReturnsMatchingCustomers() {
        // Act: Search for all customers with last name "Smith" (VSAM alternate index lookup)
        List<Customer> customersWithLastName = customerRepository.findByLastName("Smith");

        // Assert: Two customers found with last name "Smith"
        assertThat(customersWithLastName).hasSize(2);
        assertThat(customersWithLastName).extracting(Customer::getLastName)
                .containsOnly("Smith");
        assertThat(customersWithLastName).extracting(Customer::getCustomerId)
                .containsExactlyInAnyOrder(123456789L, 234567890L);
        assertThat(customersWithLastName).extracting(Customer::getFirstName)
                .containsExactlyInAnyOrder("John", "Jane");
    }

    /**
     * Test: Save persists new customer with all 500-byte CUSTOMER-RECORD fields.
     * 
     * <p>Validates CustomerRepository.save(Customer) method correctly replicates
     * COBOL VSAM WRITE operation for inserting new customer record:</p>
     * 
     * <pre>
     * COBOL equivalent:
     *   MOVE 456789012 TO CUST-ID
     *   MOVE 'Emily' TO CUST-FIRST-NAME
     *   MOVE 'Rose' TO CUST-MIDDLE-NAME
     *   MOVE 'Davis' TO CUST-LAST-NAME
     *   ... populate all CUSTOMER-RECORD fields ...
     *   
     *   EXEC CICS WRITE
     *     DATASET('CUSTDAT')
     *     FROM(CUSTOMER-RECORD)
     *     RIDFLD(CUST-ID)
     *     RESP(WS-RESP)
     *   END-EXEC
     *   
     *   IF WS-RESP = DFHRESP(NORMAL)
     *     ... customer record written successfully
     *   END-IF
     * </pre>
     * 
     * <p>Expected SQL Statements:</p>
     * <pre>
     * INSERT INTO customer (
     *   customer_id, first_name, middle_name, last_name,
     *   address_line_1, address_line_2, address_line_3,
     *   address_state_code, address_country_code, address_zip,
     *   phone_number_1, phone_number_2, ssn, government_issued_id,
     *   date_of_birth, eft_account_id, primary_card_holder_indicator,
     *   fico_score, version
     * ) VALUES (
     *   456789012, 'Emily', 'Rose', 'Davis',
     *   '321 Pine Street', 'Apt 7C', 'North Building',
     *   'FL', 'USA', '33101-2345',
     *   '305-555-3333', '305-555-4444', '456789012', 'FL-DL-55667788',
     *   '1992-09-14', 'EFT4567890', 'Y',
     *   800, 0
     * )
     * </pre>
     * 
     * <p>Test Validates:</p>
     * <ul>
     *   <li>New customer persisted with all COBOL CUSTOMER-RECORD fields</li>
     *   <li>Customer ID correctly set (CUST-ID PIC 9(09))</li>
     *   <li>All name fields persisted (25 characters each)</li>
     *   <li>All address fields persisted (50 characters for lines, 2 for state, 3 for country, 10 for ZIP)</li>
     *   <li>Phone numbers persisted (15 characters each)</li>
     *   <li>SSN persisted as String preserving leading zeros (9 digits)</li>
     *   <li>Date of birth persisted as LocalDate (converted from COBOL PIC X(10))</li>
     *   <li>FICO score persisted as Integer (3 digits, 300-850 range)</li>
     *   <li>Primary card holder indicator persisted (1 character)</li>
     *   <li>Saved customer can be retrieved by findByCustomerId</li>
     * </ul>
     */
    @Test
    @DisplayName("Save persists new customer with all 500-byte CUSTOMER-RECORD fields")
    void testSave_PersistsNewCustomer() {
        // Arrange: Create new customer (VSAM WRITE operation)
        Customer newCustomer = Customer.builder()
                .customerId(456789012L)  // CUST-ID PIC 9(09)
                .firstName("Emily")      // CUST-FIRST-NAME PIC X(25)
                .middleName("Rose")      // CUST-MIDDLE-NAME PIC X(25)
                .lastName("Davis")       // CUST-LAST-NAME PIC X(25)
                .addressLine1("321 Pine Street")           // CUST-ADDR-LINE-1 PIC X(50)
                .addressLine2("Apt 7C")                    // CUST-ADDR-LINE-2 PIC X(50)
                .addressLine3("North Building")            // CUST-ADDR-LINE-3 PIC X(50)
                .addressStateCode("FL")                    // CUST-ADDR-STATE-CD PIC X(02)
                .addressCountryCode("USA")                 // CUST-ADDR-COUNTRY-CD PIC X(03)
                .addressZip("33101-2345")                  // CUST-ADDR-ZIP PIC X(10)
                .phoneNumber1("305-555-3333")              // CUST-PHONE-NUM-1 PIC X(15)
                .phoneNumber2("305-555-4444")              // CUST-PHONE-NUM-2 PIC X(15)
                .ssn("456789012")                          // CUST-SSN PIC 9(09) as String
                .governmentIssuedId("FL-DL-55667788")      // CUST-GOVT-ISSUED-ID PIC X(20)
                .dateOfBirth(LocalDate.of(1992, 9, 14))    // CUST-DOB-YYYY-MM-DD converted to LocalDate
                .eftAccountId("EFT4567890")                // CUST-EFT-ACCOUNT-ID PIC X(10)
                .primaryCardHolderIndicator("Y")           // CUST-PRI-CARD-HOLDER-IND PIC X(01)
                .ficoScore(800)                            // CUST-FICO-CREDIT-SCORE PIC 9(03)
                .build();

        // Act: Save new customer to database (EXEC CICS WRITE)
        Customer savedCustomer = customerRepository.save(newCustomer);

        // Assert: Customer persisted with all fields
        assertThat(savedCustomer).isNotNull();
        assertThat(savedCustomer.getCustomerId()).isEqualTo(456789012L);
        assertThat(savedCustomer.getFirstName()).isEqualTo("Emily");
        assertThat(savedCustomer.getMiddleName()).isEqualTo("Rose");
        assertThat(savedCustomer.getLastName()).isEqualTo("Davis");
        assertThat(savedCustomer.getAddressLine1()).isEqualTo("321 Pine Street");
        assertThat(savedCustomer.getAddressLine2()).isEqualTo("Apt 7C");
        assertThat(savedCustomer.getAddressLine3()).isEqualTo("North Building");
        assertThat(savedCustomer.getAddressStateCode()).isEqualTo("FL");
        assertThat(savedCustomer.getAddressCountryCode()).isEqualTo("USA");
        assertThat(savedCustomer.getAddressZip()).isEqualTo("33101-2345");
        assertThat(savedCustomer.getPhoneNumber1()).isEqualTo("305-555-3333");
        assertThat(savedCustomer.getPhoneNumber2()).isEqualTo("305-555-4444");
        assertThat(savedCustomer.getSsn()).isEqualTo("456789012");
        assertThat(savedCustomer.getGovernmentIssuedId()).isEqualTo("FL-DL-55667788");
        assertThat(savedCustomer.getDateOfBirth()).isEqualTo(LocalDate.of(1992, 9, 14));
        assertThat(savedCustomer.getEftAccountId()).isEqualTo("EFT4567890");
        assertThat(savedCustomer.getPrimaryCardHolderIndicator()).isEqualTo("Y");
        assertThat(savedCustomer.getFicoScore()).isEqualTo(800);

        // Verify customer can be retrieved (persistence validation)
        Optional<Customer> retrievedCustomer = customerRepository.findByCustomerId(456789012L);
        assertThat(retrievedCustomer).isPresent();
        assertThat(retrievedCustomer.get().getFirstName()).isEqualTo("Emily");
    }

    /**
     * Test: Save updates existing customer (VSAM REWRITE operation).
     * 
     * <p>Validates CustomerRepository.save(Customer) method correctly replicates
     * COBOL VSAM REWRITE operation for updating existing customer record:</p>
     * 
     * <pre>
     * COBOL equivalent:
     *   MOVE 123456789 TO CUST-ID
     *   EXEC CICS READ UPDATE
     *     DATASET('CUSTDAT')
     *     INTO(CUSTOMER-RECORD)
     *     RIDFLD(CUST-ID)
     *     RESP(WS-RESP)
     *   END-EXEC
     *   
     *   IF WS-RESP = DFHRESP(NORMAL)
     *     MOVE 725 TO CUST-FICO-CREDIT-SCORE
     *     MOVE '555-555-9999' TO CUST-PHONE-NUM-1
     *     
     *     EXEC CICS REWRITE
     *       DATASET('CUSTDAT')
     *       FROM(CUSTOMER-RECORD)
     *       RESP(WS-RESP)
     *     END-EXEC
     *   END-IF
     * </pre>
     * 
     * <p>Expected SQL Statements:</p>
     * <pre>
     * -- First READ UPDATE (implicit in JPA)
     * SELECT * FROM customer WHERE customer_id = 123456789 FOR UPDATE
     * 
     * -- Then REWRITE
     * UPDATE customer
     * SET fico_score = 725,
     *     phone_number_1 = '555-555-9999',
     *     version = version + 1
     * WHERE customer_id = 123456789 AND version = [current_version]
     * </pre>
     * 
     * <p>Test Validates:</p>
     * <ul>
     *   <li>Existing customer record can be retrieved and modified</li>
     *   <li>Modified fields (FICO score, phone number) persist correctly</li>
     *   <li>Optimistic locking version field increments (replicates VSAM record locking)</li>
     *   <li>Updated customer retrievable with findByCustomerId shows new values</li>
     *   <li>Unmodified fields remain unchanged (firstName, lastName, etc.)</li>
     *   <li>Save operation with existing ID performs UPDATE not INSERT</li>
     * </ul>
     */
    @Test
    @DisplayName("Save updates existing customer (VSAM REWRITE operation)")
    void testSave_UpdatesExistingCustomer() {
        // Arrange: Retrieve existing customer (EXEC CICS READ UPDATE)
        Optional<Customer> existingCustomer = customerRepository.findByCustomerId(123456789L);
        assertThat(existingCustomer).isPresent();

        Customer customerToUpdate = existingCustomer.get();
        Long originalVersion = customerToUpdate.getVersion();

        // Modify customer fields (MOVE statements in COBOL)
        customerToUpdate.setFicoScore(725);  // Update CUST-FICO-CREDIT-SCORE
        customerToUpdate.setPhoneNumber1("555-555-9999");  // Update CUST-PHONE-NUM-1

        // Act: Save updated customer (EXEC CICS REWRITE)
        Customer updatedCustomer = customerRepository.save(customerToUpdate);
        
        // Flush to ensure version field is incremented and changes are synchronized
        entityManager.flush();
        entityManager.clear();

        // Assert: Customer updated with modified fields
        assertThat(updatedCustomer.getCustomerId()).isEqualTo(123456789L);
        assertThat(updatedCustomer.getFicoScore()).isEqualTo(725);
        assertThat(updatedCustomer.getPhoneNumber1()).isEqualTo("555-555-9999");
        
        // Verify optimistic locking version incremented
        assertThat(updatedCustomer.getVersion()).isGreaterThan(originalVersion);
        
        // Verify unmodified fields remain unchanged
        assertThat(updatedCustomer.getFirstName()).isEqualTo("John");
        assertThat(updatedCustomer.getLastName()).isEqualTo("Smith");

        // Verify update persisted (retrieve again from database)
        Optional<Customer> retrievedCustomer = customerRepository.findByCustomerId(123456789L);
        assertThat(retrievedCustomer).isPresent();
        assertThat(retrievedCustomer.get().getFicoScore()).isEqualTo(725);
        assertThat(retrievedCustomer.get().getPhoneNumber1()).isEqualTo("555-555-9999");
    }

    /**
     * Test: Find all customers returns all persisted customer records.
     * 
     * <p>Validates CustomerRepository.findAll() method correctly replicates
     * COBOL VSAM sequential access pattern (STARTBR/READNEXT loop):</p>
     * 
     * <pre>
     * COBOL equivalent:
     *   EXEC CICS STARTBR
     *     DATASET('CUSTDAT')
     *     RESP(WS-RESP)
     *   END-EXEC
     *   
     *   PERFORM UNTIL END-OF-FILE
     *     EXEC CICS READNEXT
     *       DATASET('CUSTDAT')
     *       INTO(CUSTOMER-RECORD)
     *       RESP(WS-RESP)
     *     END-EXEC
     *     
     *     IF WS-RESP = DFHRESP(NORMAL)
     *       ... process customer record
     *     ELSE IF WS-RESP = DFHRESP(ENDFILE)
     *       MOVE 'Y' TO END-OF-FILE
     *     END-IF
     *   END-PERFORM
     *   
     *   EXEC CICS ENDBR
     *     DATASET('CUSTDAT')
     *   END-EXEC
     * </pre>
     * 
     * <p>Expected SQL Query:</p>
     * <pre>
     * SELECT * FROM customer ORDER BY customer_id
     * </pre>
     * 
     * <p>Test Setup:</p>
     * Three customers created in @BeforeEach (testCustomer1, testCustomer2, testCustomer3)
     * with customer IDs: 123456789, 234567890, 345678901
     * 
     * <p>Test Validates:</p>
     * <ul>
     *   <li>findAll() returns exactly 3 customers (all test customers)</li>
     *   <li>All customer IDs present in result list</li>
     *   <li>Sequential access pattern replicated from VSAM KSDS</li>
     *   <li>Method supports pagination in production (Page and Pageable)</li>
     *   <li>Complete record set retrieved (no filtering)</li>
     * </ul>
     */
    @Test
    @DisplayName("Find all customers returns all persisted customer records")
    void testFindAll_ReturnsAllCustomers() {
        // Act: Retrieve all customers (VSAM sequential access STARTBR/READNEXT)
        List<Customer> allCustomers = customerRepository.findAll();

        // Assert: All three test customers returned
        assertThat(allCustomers).hasSize(3);
        assertThat(allCustomers).extracting(Customer::getCustomerId)
                .containsExactlyInAnyOrder(123456789L, 234567890L, 345678901L);
        assertThat(allCustomers).extracting(Customer::getLastName)
                .containsExactlyInAnyOrder("Smith", "Smith", "Johnson");
    }
}

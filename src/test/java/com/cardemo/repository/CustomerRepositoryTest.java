/*
 * CustomerRepositoryTest.java — JPA Repository Integration Test for Customer CRUD
 *
 * Tests the CustomerRepository data access layer against a real PostgreSQL 16 database
 * using Testcontainers, validating CRUD operations, PII field handling, and correct
 * persistence of all 17 business fields from the 500-byte CVCUS01Y.cpy CUSTOMER-RECORD.
 *
 * COBOL-to-Java Mapping Verified:
 *   EXEC CICS READ DATASET('CUSTDAT') RIDFLD(CUST-ID)  -> findById(String)
 *   EXEC CICS WRITE DATASET('CUSTDAT')                  -> save(Customer)
 *   EXEC CICS REWRITE DATASET('CUSTDAT')                 -> save(Customer) [merge]
 *   EXEC CICS DELETE DATASET('CUSTDAT') RIDFLD(CUST-ID) -> deleteById(String)
 *   CBCUS01C.cbl sequential OPEN/READ NEXT/CLOSE         -> findAll()
 *   FILE STATUS '23' (record not found)                  -> Optional.empty()
 *
 * Test Data Source: app/data/ASCII/custdata.txt records 1-2
 *
 * CardDemo v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.repository;

import com.cardemo.entity.Customer;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link CustomerRepository} using Testcontainers PostgreSQL 16.
 *
 * <p>Validates all CRUD operations inherited from {@code JpaRepository<Customer, String>}
 * against a real PostgreSQL database provisioned via Testcontainers. Tests data access
 * patterns mapped from COBOL CICS VSAM operations on the CUSTDATA KSDS dataset
 * (500-byte records defined in CVCUS01Y.cpy).</p>
 *
 * <p>The CustomerRepository has no custom query methods — it uses standard JpaRepository
 * CRUD inherited methods only. This test class verifies every inherited method used by
 * the application:</p>
 * <ul>
 *   <li>{@code save()} — WRITE/REWRITE equivalent</li>
 *   <li>{@code findById()} — keyed READ by CUST-ID RIDFLD</li>
 *   <li>{@code findAll()} — sequential batch read (CBCUS01C.cbl)</li>
 *   <li>{@code deleteById()} — DELETE by RIDFLD</li>
 *   <li>{@code deleteAll()} — IDCAMS DELETE/DEFINE equivalent (test cleanup)</li>
 *   <li>{@code saveAll()} — batch WRITE (test setup)</li>
 * </ul>
 *
 * <p>Test data derived from {@code app/data/ASCII/custdata.txt} fixture records 1-2.</p>
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(
        statements = {"DELETE FROM card_xrefs", "DELETE FROM customers"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class CustomerRepositoryTest {

    // =========================================================================
    // Testcontainers PostgreSQL 16 — replaces VSAM CUSTDATA KSDS
    // =========================================================================

    /**
     * PostgreSQL 16-alpine container managed by Testcontainers JUnit 5 extension.
     * Replaces the VSAM CUSTDATA KSDS dataset with a real relational database.
     * The container starts before all tests and stops after all tests complete.
     */
    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    /**
     * Injects Testcontainers PostgreSQL connection properties into the Spring context,
     * overriding the jdbc:tc: URL from application-test.yml with the direct JDBC URL
     * from the managed container instance.
     *
     * @param registry dynamic property registry for test context configuration
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    // =========================================================================
    // Repository Under Test
    // =========================================================================

    /** CustomerRepository instance injected by Spring DataJpaTest auto-configuration. */
    @Autowired
    private CustomerRepository customerRepository;

    // =========================================================================
    // Test Setup — @BeforeEach
    // =========================================================================

    /**
     * Clears the customer table and inserts two test customers before each test.
     * Test data derived from app/data/ASCII/custdata.txt records 1 and 2.
     *
     * <p>Maps to COBOL pattern: deleteAll() is analogous to IDCAMS DELETE/DEFINE,
     * saveAll() is analogous to batch WRITE operations from CBCUS01C.cbl.</p>
     */
    @BeforeEach
    void setUp() {
        // Table cleanup handled by @Sql annotation (FK-safe delete order:
        // card_xrefs → customers) to avoid constraint violations
        // from Flyway seed data that creates FK references from card_xrefs to customers.

        // Customer 1: custdata.txt record 1 — Immanuel Kessler (NC)
        Customer cust1 = new Customer(
                "000000001",        // custId — CUST-ID PIC 9(09), 9-char with leading zeros
                "Immanuel",         // firstName — CUST-FIRST-NAME PIC X(25)
                "Madeline",         // middleName — CUST-MIDDLE-NAME PIC X(25)
                "Kessler",          // lastName — CUST-LAST-NAME PIC X(25)
                "618 Deshaun Route", // addrLine1 — CUST-ADDR-LINE-1 PIC X(50)
                "Apt. 802",         // addrLine2 — CUST-ADDR-LINE-2 PIC X(50)
                "Altenwerthshire",  // addrLine3 — CUST-ADDR-LINE-3 PIC X(50)
                "NC",               // addrStateCode — CUST-ADDR-STATE-CD PIC X(02)
                "USA",              // addrCountryCode — CUST-ADDR-COUNTRY-CD PIC X(03)
                "12546",            // addrZip — CUST-ADDR-ZIP PIC X(10)
                "(908)119-8310",    // phoneNum1 — CUST-PHONE-NUM-1 PIC X(15)
                "(373)693-8684",    // phoneNum2 — CUST-PHONE-NUM-2 PIC X(15)
                "020973888",        // ssn — CUST-SSN PIC 9(09) [PII, leading zero]
                "000000000004936843", // govtIssuedId — CUST-GOVT-ISSUED-ID PIC X(20) [PII]
                "1961-06-08",       // dateOfBirth — CUST-DOB-YYYY-MM-DD PIC X(10)
                "0053581756",       // eftAccountId — CUST-EFT-ACCOUNT-ID PIC X(10)
                "Y",                // priCardHolderInd — CUST-PRI-CARD-HOLDER-IND PIC X(01)
                274                 // ficoCreditScore — CUST-FICO-CREDIT-SCORE PIC 9(03)
        );

        // Customer 2: custdata.txt record 2 — Enrico Rosenbaum (IN)
        Customer cust2 = new Customer(
                "000000002",        // custId
                "Enrico",           // firstName
                "April",            // middleName
                "Rosenbaum",        // lastName
                "4917 Myrna Flats", // addrLine1
                "Apt. 453",         // addrLine2
                "West Bernita",     // addrLine3
                "IN",               // addrStateCode
                "USA",              // addrCountryCode
                "22770",            // addrZip
                "(429)706-9510",    // phoneNum1
                "(744)950-5272",    // phoneNum2
                "587518382",        // ssn [PII]
                "00000000005062103711", // govtIssuedId [PII]
                "1961-10-08",       // dateOfBirth
                "0069194009",       // eftAccountId
                "Y",                // priCardHolderInd
                268                 // ficoCreditScore
        );

        customerRepository.saveAll(List.of(cust1, cust2));
    }

    // =========================================================================
    // CRUD Operation Tests — Maps to CICS VSAM READ/WRITE/REWRITE/DELETE
    // =========================================================================

    /**
     * Verifies findById returns a customer by the 9-char CUST-ID primary key.
     * Maps to COBOL: {@code EXEC CICS READ DATASET('CUSTDAT') INTO(CUSTOMER-RECORD)
     * RIDFLD(CUST-ID) RESP(WS-RESP-CD) RESP2(WS-TEFLON-CD) END-EXEC}.
     * Validates all 17 business fields match the inserted test data from record 1.
     */
    @Test
    @DisplayName("findById returns customer by 9-char CUST-ID primary key")
    void testFindByIdReturnsCustomerByPrimaryKey() {
        Optional<Customer> result = customerRepository.findById("000000001");

        assertThat(result).isPresent();
        Customer cust = result.get();

        // Verify all business fields match custdata.txt record 1
        assertThat(cust.getCustId()).isEqualTo("000000001");
        assertThat(cust.getFirstName()).isEqualTo("Immanuel");
        assertThat(cust.getMiddleName()).isEqualTo("Madeline");
        assertThat(cust.getLastName()).isEqualTo("Kessler");
        assertThat(cust.getAddrLine1()).isEqualTo("618 Deshaun Route");
        assertThat(cust.getAddrLine2()).isEqualTo("Apt. 802");
        assertThat(cust.getAddrLine3()).isEqualTo("Altenwerthshire");
        assertThat(cust.getAddrStateCode()).isEqualTo("NC");
        assertThat(cust.getAddrCountryCode()).isEqualTo("USA");
        assertThat(cust.getAddrZip()).isEqualTo("12546");
        assertThat(cust.getPhoneNum1()).isEqualTo("(908)119-8310");
        assertThat(cust.getPhoneNum2()).isEqualTo("(373)693-8684");
        assertThat(cust.getSsn()).isEqualTo("020973888");
        assertThat(cust.getGovtIssuedId()).isEqualTo("000000000004936843");
        assertThat(cust.getDateOfBirth()).isEqualTo("1961-06-08");
        assertThat(cust.getEftAccountId()).isEqualTo("0053581756");
        assertThat(cust.getPriCardHolderInd()).isEqualTo("Y");
        assertThat(cust.getFicoCreditScore()).isEqualTo(274);
    }

    /**
     * Verifies findById returns Optional.empty() for a non-existent customer ID.
     * Maps to VSAM FILE STATUS '23' (record not found) which in the COBOL source
     * triggers error handling via RESP/RESP2 condition checking (DFHRESP(NOTFND)).
     */
    @Test
    @DisplayName("findById returns empty for non-existent customer")
    void testFindByIdReturnsEmptyForNonExistent() {
        Optional<Customer> result = customerRepository.findById("999999999");

        assertThat(result).isNotPresent();
        assertThat(result.isPresent()).isFalse();
    }

    /**
     * Verifies save persists a new customer with all 17 business fields including
     * PII fields (SSN, government-issued ID). Uses setter methods to exercise all
     * field mutation paths, then reads back from DB to verify roundtrip correctness.
     * Maps to COBOL: {@code EXEC CICS WRITE DATASET('CUSTDAT') FROM(CUSTOMER-RECORD)
     * RIDFLD(CUST-ID) END-EXEC}.
     */
    @Test
    @DisplayName("save persists new customer with all fields including PII")
    void testSavePersistsNewCustomerWithAllFieldsIncludingPii() {
        // Create base customer via all-args constructor, then exercise all setters
        Customer newCust = new Customer(
                "000000099", "Initial", "Mid", "Last",
                "Addr1", "Addr2", "Addr3", "CA", "USA", "90210",
                "1111111111", "2222222222", "111111111", "GOVTID_PLACEHOLDER_XX",
                "2000-01-01", "EFTACCT001", "N", 500
        );

        // Exercise all 18 setter methods for schema compliance verification
        newCust.setCustId("000000099");
        newCust.setFirstName("TestFirst");
        newCust.setMiddleName("TestMiddle");
        newCust.setLastName("TestLast");
        newCust.setAddrLine1("789 Test Boulevard");
        newCust.setAddrLine2("Floor 3");
        newCust.setAddrLine3("Testington");
        newCust.setAddrStateCode("TX");
        newCust.setAddrCountryCode("USA");
        newCust.setAddrZip("75001-1234");
        newCust.setPhoneNum1("(555)123-4567");
        newCust.setPhoneNum2("(555)987-6543");
        newCust.setSsn("123456789");
        newCust.setGovtIssuedId("GOVT00000000TESTID01");
        newCust.setDateOfBirth("1990-05-15");
        newCust.setEftAccountId("9876543210");
        newCust.setPriCardHolderInd("Y");
        newCust.setFicoCreditScore(750);

        customerRepository.save(newCust);

        // Re-read from database to verify persistence roundtrip
        Optional<Customer> found = customerRepository.findById("000000099");
        assertThat(found).isPresent();
        Customer retrieved = found.get();

        // Verify all 17 business fields + ficoCreditScore roundtripped correctly
        assertThat(retrieved.getCustId()).isEqualTo("000000099");
        assertThat(retrieved.getFirstName()).isEqualTo("TestFirst");
        assertThat(retrieved.getMiddleName()).isEqualTo("TestMiddle");
        assertThat(retrieved.getLastName()).isEqualTo("TestLast");
        assertThat(retrieved.getAddrLine1()).isEqualTo("789 Test Boulevard");
        assertThat(retrieved.getAddrLine2()).isEqualTo("Floor 3");
        assertThat(retrieved.getAddrLine3()).isEqualTo("Testington");
        assertThat(retrieved.getAddrStateCode()).isEqualTo("TX");
        assertThat(retrieved.getAddrCountryCode()).isEqualTo("USA");
        assertThat(retrieved.getAddrZip()).isEqualTo("75001-1234");
        assertThat(retrieved.getPhoneNum1()).isEqualTo("(555)123-4567");
        assertThat(retrieved.getPhoneNum2()).isEqualTo("(555)987-6543");
        assertThat(retrieved.getSsn()).isEqualTo("123456789");
        assertThat(retrieved.getGovtIssuedId()).isEqualTo("GOVT00000000TESTID01");
        assertThat(retrieved.getDateOfBirth()).isEqualTo("1990-05-15");
        assertThat(retrieved.getEftAccountId()).isEqualTo("9876543210");
        assertThat(retrieved.getPriCardHolderInd()).isEqualTo("Y");
        assertThat(retrieved.getFicoCreditScore()).isEqualTo(750);
    }

    /**
     * Verifies save updates an existing customer record (JPA merge/update).
     * Maps to COBOL: {@code EXEC CICS READ DATASET('CUSTDAT') UPDATE ...}
     * followed by {@code EXEC CICS REWRITE DATASET('CUSTDAT') FROM(CUSTOMER-RECORD)}.
     * The optimistic locking pattern (READ UPDATE then REWRITE) is handled by JPA.
     */
    @Test
    @DisplayName("save updates existing customer")
    void testSaveUpdatesExistingCustomer() {
        // Read existing customer — equivalent to EXEC CICS READ UPDATE
        Optional<Customer> original = customerRepository.findById("000000001");
        assertThat(original).isPresent();

        // Modify a field — equivalent to MOVE 'UpdatedKessler' TO CUST-LAST-NAME
        Customer cust = original.get();
        cust.setLastName("UpdatedKessler");

        // Rewrite — equivalent to EXEC CICS REWRITE
        customerRepository.save(cust);

        // Verify the update persisted correctly
        Optional<Customer> updated = customerRepository.findById("000000001");
        assertThat(updated).isPresent();
        assertThat(updated.get().getLastName()).isEqualTo("UpdatedKessler");

        // Verify other fields remain unchanged after the update
        assertThat(updated.get().getFirstName()).isEqualTo("Immanuel");
        assertThat(updated.get().getCustId()).isEqualTo("000000001");
        assertThat(updated.get().getSsn()).isEqualTo("020973888");
    }

    /**
     * Verifies deleteById removes a customer by primary key.
     * Maps to COBOL: {@code EXEC CICS DELETE DATASET('CUSTDAT')
     * RIDFLD(CUST-ID) END-EXEC}.
     * After deletion, findById should return empty (VSAM STATUS '23').
     */
    @Test
    @DisplayName("delete removes customer by ID")
    void testDeleteRemovesCustomerById() {
        // Verify customer exists before deletion
        assertThat(customerRepository.findById("000000001")).isPresent();

        // Delete — equivalent to EXEC CICS DELETE DATASET('CUSTDAT') RIDFLD(CUST-ID)
        customerRepository.deleteById("000000001");

        // Verify customer no longer exists — equivalent to VSAM STATUS '23' after delete
        Optional<Customer> deleted = customerRepository.findById("000000001");
        assertThat(deleted).isNotPresent();

        // Verify the other customer remains unaffected
        assertThat(customerRepository.findById("000000002")).isPresent();
    }

    // =========================================================================
    // PII Field Persistence Tests
    // Per AAP: "PII fields (CUST-SSN, CUST-GOVT-ISSUED-ID) must be marked with
    // appropriate annotations for sensitive data handling"
    // =========================================================================

    /**
     * Verifies SSN field persists correctly as a 9-character string preserving
     * leading zeros. CUST-SSN PIC 9(09) must not be truncated or converted to
     * integer (which would lose leading zeros: "020973888" would become 20973888).
     * The custId and SSN are both stored as String (not numeric) to preserve
     * leading zeros in the COBOL PIC 9(n) fields.
     */
    @Test
    @DisplayName("SSN field persists correctly as 9-char string preserving leading zeros")
    void testSsnFieldPersistsPreservingLeadingZeros() {
        // SSN "020973888" has a leading zero that must be preserved as VARCHAR
        Optional<Customer> result = customerRepository.findById("000000001");
        assertThat(result).isPresent();

        String ssn = result.get().getSsn();
        assertThat(ssn).isEqualTo("020973888");
        assertThat(ssn).hasSize(9);
        assertThat(ssn).startsWith("0"); // Critical: leading zero preserved, not stored as int
    }

    /**
     * Verifies government-issued ID persists correctly as an alphanumeric string.
     * CUST-GOVT-ISSUED-ID PIC X(20) stores government-issued identification numbers.
     * This is a PII field that must roundtrip through PostgreSQL VARCHAR(20) intact.
     */
    @Test
    @DisplayName("government-issued ID persists correctly as 20-char string")
    void testGovernmentIssuedIdPersistsCorrectly() {
        Optional<Customer> result = customerRepository.findById("000000001");
        assertThat(result).isPresent();

        String govtId = result.get().getGovtIssuedId();
        assertThat(govtId).isEqualTo("000000000004936843");
        assertThat(govtId).isNotNull();
        assertThat(govtId).isNotEmpty();

        // Verify customer 2 has a different govt ID (different PII)
        Optional<Customer> result2 = customerRepository.findById("000000002");
        assertThat(result2).isPresent();
        assertThat(result2.get().getGovtIssuedId()).isEqualTo("00000000005062103711");
    }

    /**
     * Verifies date of birth persists exactly in YYYY-MM-DD format.
     * CUST-DOB-YYYY-MM-DD PIC X(10) stores the date as a 10-character string.
     * The format must survive the roundtrip through PostgreSQL VARCHAR(10) exactly.
     */
    @Test
    @DisplayName("date of birth persists in YYYY-MM-DD format")
    void testDateOfBirthPersistsInYyyyMmDdFormat() {
        Optional<Customer> result = customerRepository.findById("000000001");
        assertThat(result).isPresent();

        String dob = result.get().getDateOfBirth();
        assertThat(dob).isEqualTo("1961-06-08");
        assertThat(dob).hasSize(10);
        assertThat(dob).matches("\\d{4}-\\d{2}-\\d{2}"); // YYYY-MM-DD format regex

        // Verify customer 2 has a different DOB
        Optional<Customer> result2 = customerRepository.findById("000000002");
        assertThat(result2).isPresent();
        assertThat(result2.get().getDateOfBirth()).isEqualTo("1961-10-08");
    }

    // =========================================================================
    // Full Field Coverage Test — Ensures No Entity Mapping Gaps
    // =========================================================================

    /**
     * Comprehensive test verifying all 17 business fields persist and retrieve
     * correctly. Creates a Customer with known unique values for every field,
     * saves it, reads it back via findById, and asserts each field individually.
     * This ensures no field is missed or mistyped in the JPA entity-to-column mapping.
     *
     * <p>Fields verified (mapping from CVCUS01Y.cpy 500-byte CUSTOMER-RECORD):</p>
     * <ol>
     *   <li>custId — CUST-ID PIC 9(09) (PK)</li>
     *   <li>firstName — CUST-FIRST-NAME PIC X(25)</li>
     *   <li>middleName — CUST-MIDDLE-NAME PIC X(25)</li>
     *   <li>lastName — CUST-LAST-NAME PIC X(25)</li>
     *   <li>addrLine1 — CUST-ADDR-LINE-1 PIC X(50)</li>
     *   <li>addrLine2 — CUST-ADDR-LINE-2 PIC X(50)</li>
     *   <li>addrLine3 — CUST-ADDR-LINE-3 PIC X(50)</li>
     *   <li>addrStateCode — CUST-ADDR-STATE-CD PIC X(02)</li>
     *   <li>addrCountryCode — CUST-ADDR-COUNTRY-CD PIC X(03)</li>
     *   <li>addrZip — CUST-ADDR-ZIP PIC X(10)</li>
     *   <li>phoneNum1 — CUST-PHONE-NUM-1 PIC X(15)</li>
     *   <li>phoneNum2 — CUST-PHONE-NUM-2 PIC X(15)</li>
     *   <li>ssn — CUST-SSN PIC 9(09) [PII]</li>
     *   <li>govtIssuedId — CUST-GOVT-ISSUED-ID PIC X(20) [PII]</li>
     *   <li>dateOfBirth — CUST-DOB-YYYY-MM-DD PIC X(10)</li>
     *   <li>eftAccountId — CUST-EFT-ACCOUNT-ID PIC X(10)</li>
     *   <li>priCardHolderInd — CUST-PRI-CARD-HOLDER-IND PIC X(01)</li>
     *   <li>ficoCreditScore — CUST-FICO-CREDIT-SCORE PIC 9(03)</li>
     * </ol>
     */
    @Test
    @DisplayName("all 17 business fields persist and retrieve correctly")
    void testAll17BusinessFieldsPersistAndRetrieveCorrectly() {
        // Create a customer with unique deterministic values for every field
        Customer fullCustomer = new Customer(
                "000000050",           // 1. custId — PIC 9(09)
                "FieldTest",           // 2. firstName — PIC X(25)
                "MiddleCheck",         // 3. middleName — PIC X(25)
                "Verification",        // 4. lastName — PIC X(25)
                "100 Verification Dr", // 5. addrLine1 — PIC X(50)
                "Building B",          // 6. addrLine2 — PIC X(50)
                "ValidationCity",      // 7. addrLine3 — PIC X(50)
                "WA",                  // 8. addrStateCode — PIC X(02)
                "USA",                 // 9. addrCountryCode — PIC X(03)
                "98101-4567",          // 10. addrZip — PIC X(10), ZIP+4 format
                "(206)555-0100",       // 11. phoneNum1 — PIC X(15)
                "(206)555-0200",       // 12. phoneNum2 — PIC X(15)
                "012345678",           // 13. ssn — PIC 9(09) [PII, leading zero]
                "ALLFIELD00000000TEST", // 14. govtIssuedId — PIC X(20) [PII]
                "1995-12-25",          // 15. dateOfBirth — PIC X(10)
                "EFTFLD0001",          // 16. eftAccountId — PIC X(10)
                "N",                   // 17. priCardHolderInd — PIC X(01)
                850                    // ficoCreditScore — PIC 9(03) as Integer
        );

        customerRepository.save(fullCustomer);

        // Read back and assert each field individually for complete coverage
        Optional<Customer> found = customerRepository.findById("000000050");
        assertThat(found).isPresent();
        Customer c = found.get();

        assertThat(c.getCustId()).as("Field 1: custId").isEqualTo("000000050");
        assertThat(c.getFirstName()).as("Field 2: firstName").isEqualTo("FieldTest");
        assertThat(c.getMiddleName()).as("Field 3: middleName").isEqualTo("MiddleCheck");
        assertThat(c.getLastName()).as("Field 4: lastName").isEqualTo("Verification");
        assertThat(c.getAddrLine1()).as("Field 5: addrLine1").isEqualTo("100 Verification Dr");
        assertThat(c.getAddrLine2()).as("Field 6: addrLine2").isEqualTo("Building B");
        assertThat(c.getAddrLine3()).as("Field 7: addrLine3").isEqualTo("ValidationCity");
        assertThat(c.getAddrStateCode()).as("Field 8: addrStateCode").isEqualTo("WA");
        assertThat(c.getAddrCountryCode()).as("Field 9: addrCountryCode").isEqualTo("USA");
        assertThat(c.getAddrZip()).as("Field 10: addrZip").isEqualTo("98101-4567");
        assertThat(c.getPhoneNum1()).as("Field 11: phoneNum1").isEqualTo("(206)555-0100");
        assertThat(c.getPhoneNum2()).as("Field 12: phoneNum2").isEqualTo("(206)555-0200");
        assertThat(c.getSsn()).as("Field 13: ssn [PII]").isEqualTo("012345678");
        assertThat(c.getGovtIssuedId()).as("Field 14: govtIssuedId [PII]").isEqualTo("ALLFIELD00000000TEST");
        assertThat(c.getDateOfBirth()).as("Field 15: dateOfBirth").isEqualTo("1995-12-25");
        assertThat(c.getEftAccountId()).as("Field 16: eftAccountId").isEqualTo("EFTFLD0001");
        assertThat(c.getPriCardHolderInd()).as("Field 17: priCardHolderInd").isEqualTo("N");
        assertThat(c.getFicoCreditScore()).as("ficoCreditScore").isEqualTo(850);
    }

    // =========================================================================
    // findAll / Batch Read Test — Maps to CBCUS01C.cbl sequential VSAM dump
    // =========================================================================

    /**
     * Verifies findAll returns all customer records for sequential batch read.
     * Maps to CBCUS01C.cbl's sequential VSAM dump pattern:
     * <pre>
     *   OPEN INPUT CUSTFILE-FILE
     *   PERFORM UNTIL END-OF-FILE = 'Y'
     *       READ CUSTFILE-FILE INTO CUSTOMER-RECORD
     *       DISPLAY CUSTOMER-RECORD
     *   END-PERFORM
     *   CLOSE CUSTFILE-FILE
     * </pre>
     *
     * <p>The JPA {@code findAll()} method replaces the sequential OPEN/READ NEXT/CLOSE
     * VSAM pattern used in the batch customer file dump program.</p>
     */
    @Test
    @DisplayName("findAll returns all customers for sequential batch read")
    void testFindAllReturnsAllCustomersForSequentialBatchRead() {
        List<Customer> allCustomers = customerRepository.findAll();

        // Verify count — 2 customers inserted in setUp
        assertThat(allCustomers).hasSize(2);
        assertThat(allCustomers.size()).isEqualTo(2);

        // Verify both customers are present (order not guaranteed in SQL without ORDER BY)
        List<String> custIds = allCustomers.stream()
                .map(Customer::getCustId)
                .toList();
        assertThat(custIds).containsExactlyInAnyOrder("000000001", "000000002");
    }
}

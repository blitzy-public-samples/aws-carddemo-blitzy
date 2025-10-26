package com.carddemo.batch.processor;

import com.carddemo.model.entity.Customer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 unit test class for CustomerProcessor ItemProcessor implementation
 * 
 * Converted from COBOL program: CBCUS01C.cbl
 * Original function: Customer file validation batch program
 * JCL Job: CBCUSJ01.jcl
 * 
 * Tests business logic extracted from COBOL customer validation program including:
 * - Data transformation from Customer entity through processor
 * - SSN format validation (9-digit numeric format, no invalid patterns)
 * - Phone number format checks (10-digit with optional formatting)
 * - Address field completeness validation (required fields)
 * - FICO score range validation (300-850)
 * - Date of birth validation (format and reasonable age range 18-120)
 * - Required field presence checks (first name, last name)
 * - Error handling patterns matching COBOL file-status checks
 * - Skip logic for invalid customers (returns null to trigger Spring Batch filtering)
 * 
 * Conversion Notes:
 * - COBOL 1000-CUSTFILE-GET-NEXT paragraph → process() method tests
 * - COBOL CUSTFILE-STATUS checks → validation result verification
 * - COBOL APPL-RESULT codes → null return validation
 * - COBOL field validation logic → parameterized test scenarios
 * - COBOL DISPLAY error messages → assertion failure messages
 * 
 * Testing Strategy:
 * - Uses Mockito 5.x for dependency mocking (none required for this processor)
 * - Uses AssertJ for fluent assertions
 * - Uses JUnit 5 @ParameterizedTest with @MethodSource for validation scenarios
 * - Test data builders for creating Customer entities with various states
 * - Boundary condition testing for all validation rules
 * - Edge case testing matching COBOL validation patterns
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@DisplayName("CustomerProcessor Unit Tests")
class CustomerProcessorTest {

    private final CustomerProcessor processor = new CustomerProcessor();

    /**
     * Test successful processing of valid customer with complete data
     * 
     * COBOL equivalent: CUSTFILE-STATUS = '00' (successful read)
     * Expected behavior: process() returns the customer object unchanged
     */
    @Test
    @DisplayName("Should successfully process valid customer with complete data")
    void testProcessValidCustomerWithCompleteData() throws Exception {
        // Arrange: Create valid customer with all required fields
        Customer validCustomer = createValidCustomer();
        
        // Act: Process the valid customer
        Customer result = processor.process(validCustomer);
        
        // Assert: Customer should pass through unchanged
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(validCustomer);
        assertThat(result.getCustId()).isEqualTo(validCustomer.getCustId());
        assertThat(result.getCustFirstName()).isEqualTo(validCustomer.getCustFirstName());
        assertThat(result.getCustLastName()).isEqualTo(validCustomer.getCustLastName());
    }

    /**
     * Test SSN validation with valid 9-digit format
     * 
     * COBOL equivalent: CUST-SSN PIC 9(09) with numeric validation
     * Expected behavior: process() returns customer for valid SSN
     */
    @Test
    @DisplayName("Should accept valid SSN with 9 digits")
    void testValidSSNFormat() throws Exception {
        // Arrange: Customer with valid 9-digit SSN
        Customer customer = createValidCustomer();
        customer.setCustSsn("123456789");
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Valid SSN should pass validation
        assertThat(result).isNotNull();
    }

    /**
     * Test SSN validation rejects invalid patterns (leading zeros like 000-00-0000)
     * 
     * Business Rule: SSN cannot be all zeros or have invalid patterns
     */
    @Test
    @DisplayName("Should reject SSN with all zeros (000000000)")
    void testRejectSSNAllZeros() throws Exception {
        // Arrange: Customer with invalid all-zeros SSN
        Customer customer = createValidCustomer();
        customer.setCustSsn("000000000");
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Invalid SSN should cause rejection (return null)
        assertThat(result).isNull();
    }

    /**
     * Test SSN validation with parameterized invalid formats
     * 
     * Tests multiple invalid SSN scenarios in single parameterized test
     */
    @ParameterizedTest
    @MethodSource("provideInvalidSSNFormats")
    @DisplayName("Should reject customers with invalid SSN formats")
    void testInvalidSSNFormats(String ssn, String description) throws Exception {
        // Arrange: Customer with invalid SSN format
        Customer customer = createValidCustomer();
        customer.setCustSsn(ssn);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Should return null to skip invalid customer
        assertThat(result)
            .as("SSN validation should fail for: %s", description)
            .isNull();
    }

    /**
     * Provides invalid SSN test data for parameterized test
     */
    private static Stream<Arguments> provideInvalidSSNFormats() {
        return Stream.of(
            Arguments.of(null, "null SSN"),
            Arguments.of("", "empty SSN"),
            Arguments.of("12345678", "8 digits (too short)"),
            Arguments.of("1234567890", "10 digits (too long)"),
            Arguments.of("123-45-6789", "formatted with dashes"),
            Arguments.of("12345678A", "contains letter"),
            Arguments.of("123 456 789", "contains spaces"),
            Arguments.of("666123456", "666 prefix (invalid SSN area)"),
            Arguments.of("999999999", "all nines")
        );
    }

    /**
     * Test phone number validation with valid formats
     */
    @Test
    @DisplayName("Should accept valid phone number formats")
    void testValidPhoneNumberFormat() throws Exception {
        // Arrange: Customer with valid phone number
        Customer customer = createValidCustomer();
        customer.setCustPhoneNum1("1234567890");
        customer.setCustPhoneNum2("(123) 456-7890");
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Valid phone numbers should pass
        assertThat(result).isNotNull();
    }

    /**
     * Test phone number validation accepts null/empty secondary phone
     */
    @Test
    @DisplayName("Should accept null secondary phone number")
    void testNullSecondaryPhoneNumber() throws Exception {
        // Arrange: Customer with only primary phone
        Customer customer = createValidCustomer();
        customer.setCustPhoneNum1("1234567890");
        customer.setCustPhoneNum2(null);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Should pass with only primary phone
        assertThat(result).isNotNull();
    }

    /**
     * Test phone number validation with parameterized invalid formats
     */
    @ParameterizedTest
    @MethodSource("provideInvalidPhoneNumbers")
    @DisplayName("Should reject customers with invalid phone number formats")
    void testInvalidPhoneNumberFormats(String phoneNum, String description) throws Exception {
        // Arrange: Customer with invalid phone number
        Customer customer = createValidCustomer();
        customer.setCustPhoneNum1(phoneNum);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Should return null for invalid phone
        assertThat(result)
            .as("Phone validation should fail for: %s", description)
            .isNull();
    }

    /**
     * Provides invalid phone number test data
     */
    private static Stream<Arguments> provideInvalidPhoneNumbers() {
        return Stream.of(
            Arguments.of("123-456-ABCD", "contains letters"),
            Arguments.of("phone#123", "contains special characters"),
            Arguments.of("1234567890123456", "exceeds 15 characters"),
            Arguments.of("---", "no digits (only formatting)"),
            Arguments.of("(   )    -    ", "spaces only")
        );
    }

    /**
     * Test address validation with complete required fields
     */
    @Test
    @DisplayName("Should accept customer with complete address")
    void testCompleteAddress() throws Exception {
        // Arrange: Customer with complete address
        Customer customer = createValidCustomer();
        customer.setCustAddrLine1("123 Main Street");
        customer.setCustAddrStateCd("CA");
        customer.setCustAddrZip("90210");
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Complete address should pass
        assertThat(result).isNotNull();
    }

    /**
     * Test address validation with parameterized missing required fields
     */
    @ParameterizedTest
    @MethodSource("provideMissingAddressFields")
    @DisplayName("Should reject customers with missing required address fields")
    void testMissingAddressFields(String addrLine1, String stateCd, String zip, String description) throws Exception {
        // Arrange: Customer with incomplete address
        Customer customer = createValidCustomer();
        customer.setCustAddrLine1(addrLine1);
        customer.setCustAddrStateCd(stateCd);
        customer.setCustAddrZip(zip);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Should return null for incomplete address
        assertThat(result)
            .as("Address validation should fail for: %s", description)
            .isNull();
    }

    /**
     * Provides missing address field test data
     */
    private static Stream<Arguments> provideMissingAddressFields() {
        return Stream.of(
            Arguments.of(null, "CA", "90210", "null address line 1"),
            Arguments.of("", "CA", "90210", "empty address line 1"),
            Arguments.of("   ", "CA", "90210", "whitespace address line 1"),
            Arguments.of("123 Main St", null, "90210", "null state code"),
            Arguments.of("123 Main St", "", "90210", "empty state code"),
            Arguments.of("123 Main St", "  ", "90210", "whitespace state code"),
            Arguments.of("123 Main St", "CA", null, "null ZIP code"),
            Arguments.of("123 Main St", "CA", "", "empty ZIP code"),
            Arguments.of("123 Main St", "CA", "     ", "whitespace ZIP code")
        );
    }

    /**
     * Test FICO score validation with valid range (300-850)
     */
    @Test
    @DisplayName("Should accept valid FICO score within range 300-850")
    void testValidFICOScore() throws Exception {
        // Arrange: Customer with valid FICO score
        Customer customer = createValidCustomer();
        customer.setCustFicoCreditScore(700);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Valid FICO score should pass
        assertThat(result).isNotNull();
    }

    /**
     * Test FICO score validation accepts boundary values
     */
    @Test
    @DisplayName("Should accept FICO score at minimum boundary (300)")
    void testFICOScoreMinimumBoundary() throws Exception {
        // Arrange: Customer with minimum valid FICO score
        Customer customer = createValidCustomer();
        customer.setCustFicoCreditScore(300);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Minimum FICO score (300) should pass
        assertThat(result).isNotNull();
    }

    /**
     * Test FICO score validation accepts boundary values
     */
    @Test
    @DisplayName("Should accept FICO score at maximum boundary (850)")
    void testFICOScoreMaximumBoundary() throws Exception {
        // Arrange: Customer with maximum valid FICO score
        Customer customer = createValidCustomer();
        customer.setCustFicoCreditScore(850);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Maximum FICO score (850) should pass
        assertThat(result).isNotNull();
    }

    /**
     * Test FICO score validation accepts null (optional field)
     */
    @Test
    @DisplayName("Should accept null FICO score (optional field)")
    void testNullFICOScore() throws Exception {
        // Arrange: Customer without FICO score
        Customer customer = createValidCustomer();
        customer.setCustFicoCreditScore(null);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Null FICO score should pass (optional)
        assertThat(result).isNotNull();
    }

    /**
     * Test FICO score validation with parameterized out-of-range values
     */
    @ParameterizedTest
    @MethodSource("provideInvalidFICOScores")
    @DisplayName("Should reject customers with FICO score outside valid range")
    void testInvalidFICOScores(Integer ficoScore, String description) throws Exception {
        // Arrange: Customer with invalid FICO score
        Customer customer = createValidCustomer();
        customer.setCustFicoCreditScore(ficoScore);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Should return null for out-of-range FICO score
        assertThat(result)
            .as("FICO score validation should fail for: %s", description)
            .isNull();
    }

    /**
     * Provides invalid FICO score test data
     */
    private static Stream<Arguments> provideInvalidFICOScores() {
        return Stream.of(
            Arguments.of(299, "below minimum (299)"),
            Arguments.of(0, "zero"),
            Arguments.of(-100, "negative value"),
            Arguments.of(851, "above maximum (851)"),
            Arguments.of(1000, "unreasonably high (1000)")
        );
    }

    /**
     * Test date of birth validation with valid age (between 18 and 120)
     */
    @Test
    @DisplayName("Should accept valid date of birth for customer aged 25")
    void testValidDateOfBirth() throws Exception {
        // Arrange: Customer aged 25 years
        Customer customer = createValidCustomer();
        LocalDate dob = LocalDate.now().minusYears(25);
        customer.setCustDobYyyyMmDd(dob);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Valid age should pass
        assertThat(result).isNotNull();
    }

    /**
     * Test date of birth validation at minimum age boundary (18 years)
     */
    @Test
    @DisplayName("Should accept customer at minimum age boundary (exactly 18 years old)")
    void testDateOfBirthMinimumAge() throws Exception {
        // Arrange: Customer exactly 18 years old
        Customer customer = createValidCustomer();
        LocalDate dob = LocalDate.now().minusYears(18);
        customer.setCustDobYyyyMmDd(dob);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Minimum age (18) should pass
        assertThat(result).isNotNull();
    }

    /**
     * Test date of birth validation at maximum age boundary (120 years)
     */
    @Test
    @DisplayName("Should accept customer at maximum age boundary (exactly 120 years old)")
    void testDateOfBirthMaximumAge() throws Exception {
        // Arrange: Customer exactly 120 years old
        Customer customer = createValidCustomer();
        LocalDate dob = LocalDate.now().minusYears(120);
        customer.setCustDobYyyyMmDd(dob);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Maximum age (120) should pass
        assertThat(result).isNotNull();
    }

    /**
     * Test date of birth validation rejects future dates
     */
    @Test
    @DisplayName("Should reject date of birth in the future")
    void testDateOfBirthInFuture() throws Exception {
        // Arrange: Customer with future date of birth
        Customer customer = createValidCustomer();
        LocalDate futureDob = LocalDate.now().plusYears(1);
        customer.setCustDobYyyyMmDd(futureDob);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Future date should be rejected
        assertThat(result).isNull();
    }

    /**
     * Test date of birth validation with parameterized invalid ages
     */
    @ParameterizedTest
    @MethodSource("provideInvalidDatesOfBirth")
    @DisplayName("Should reject customers with invalid date of birth")
    void testInvalidDatesOfBirth(LocalDate dob, String description) throws Exception {
        // Arrange: Customer with invalid date of birth
        Customer customer = createValidCustomer();
        customer.setCustDobYyyyMmDd(dob);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Should return null for invalid date of birth
        assertThat(result)
            .as("Date of birth validation should fail for: %s", description)
            .isNull();
    }

    /**
     * Provides invalid date of birth test data
     */
    private static Stream<Arguments> provideInvalidDatesOfBirth() {
        return Stream.of(
            Arguments.of(null, "null date of birth"),
            Arguments.of(LocalDate.now().minusYears(17), "under 18 years (17 years old)"),
            Arguments.of(LocalDate.now().minusYears(10), "under 18 years (10 years old)"),
            Arguments.of(LocalDate.now(), "born today (0 years old)"),
            Arguments.of(LocalDate.now().plusDays(1), "tomorrow (future date)"),
            Arguments.of(LocalDate.now().plusYears(5), "5 years in future"),
            Arguments.of(LocalDate.now().minusYears(121), "over 120 years (121 years old)"),
            Arguments.of(LocalDate.now().minusYears(150), "over 120 years (150 years old)")
        );
    }

    /**
     * Test name field validation - first name required
     */
    @Test
    @DisplayName("Should reject customer with missing first name")
    void testMissingFirstName() throws Exception {
        // Arrange: Customer without first name
        Customer customer = createValidCustomer();
        customer.setCustFirstName(null);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Missing first name should cause rejection
        assertThat(result).isNull();
    }

    /**
     * Test name field validation - first name cannot be empty
     */
    @Test
    @DisplayName("Should reject customer with empty first name")
    void testEmptyFirstName() throws Exception {
        // Arrange: Customer with empty first name
        Customer customer = createValidCustomer();
        customer.setCustFirstName("");
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Empty first name should cause rejection
        assertThat(result).isNull();
    }

    /**
     * Test name field validation - first name cannot be whitespace only
     */
    @Test
    @DisplayName("Should reject customer with whitespace-only first name")
    void testWhitespaceFirstName() throws Exception {
        // Arrange: Customer with whitespace-only first name
        Customer customer = createValidCustomer();
        customer.setCustFirstName("   ");
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Whitespace first name should cause rejection
        assertThat(result).isNull();
    }

    /**
     * Test name field validation - last name required
     */
    @Test
    @DisplayName("Should reject customer with missing last name")
    void testMissingLastName() throws Exception {
        // Arrange: Customer without last name
        Customer customer = createValidCustomer();
        customer.setCustLastName(null);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Missing last name should cause rejection
        assertThat(result).isNull();
    }

    /**
     * Test name field validation - last name cannot be empty
     */
    @Test
    @DisplayName("Should reject customer with empty last name")
    void testEmptyLastName() throws Exception {
        // Arrange: Customer with empty last name
        Customer customer = createValidCustomer();
        customer.setCustLastName("");
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Empty last name should cause rejection
        assertThat(result).isNull();
    }

    /**
     * Test name field validation with parameterized invalid names
     */
    @ParameterizedTest
    @MethodSource("provideInvalidNames")
    @DisplayName("Should reject customers with invalid name fields")
    void testInvalidNames(String firstName, String lastName, String description) throws Exception {
        // Arrange: Customer with invalid names
        Customer customer = createValidCustomer();
        customer.setCustFirstName(firstName);
        customer.setCustLastName(lastName);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Should return null for invalid names
        assertThat(result)
            .as("Name validation should fail for: %s", description)
            .isNull();
    }

    /**
     * Provides invalid name test data
     */
    private static Stream<Arguments> provideInvalidNames() {
        return Stream.of(
            Arguments.of(null, "Smith", "null first name"),
            Arguments.of("", "Smith", "empty first name"),
            Arguments.of("   ", "Smith", "whitespace first name"),
            Arguments.of("John", null, "null last name"),
            Arguments.of("John", "", "empty last name"),
            Arguments.of("John", "  ", "whitespace last name"),
            Arguments.of(null, null, "both names null"),
            Arguments.of("", "", "both names empty")
        );
    }

    /**
     * Test middle name is optional
     */
    @Test
    @DisplayName("Should accept customer with null middle name (optional field)")
    void testNullMiddleName() throws Exception {
        // Arrange: Customer without middle name
        Customer customer = createValidCustomer();
        customer.setCustMiddleName(null);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Null middle name should pass (optional field)
        assertThat(result).isNotNull();
    }

    /**
     * Test government issued ID is optional
     */
    @Test
    @DisplayName("Should accept customer with null government ID (optional field)")
    void testNullGovernmentId() throws Exception {
        // Arrange: Customer without government ID
        Customer customer = createValidCustomer();
        customer.setCustGovtIssuedId(null);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Null government ID should pass (optional field)
        assertThat(result).isNotNull();
    }

    /**
     * Test address lines 2 and 3 are optional
     */
    @Test
    @DisplayName("Should accept customer with null address lines 2 and 3 (optional fields)")
    void testOptionalAddressLines() throws Exception {
        // Arrange: Customer with only address line 1
        Customer customer = createValidCustomer();
        customer.setCustAddrLine2(null);
        customer.setCustAddrLine3(null);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Optional address lines can be null
        assertThat(result).isNotNull();
    }

    /**
     * Test country code is optional
     */
    @Test
    @DisplayName("Should accept customer with null country code (optional field)")
    void testNullCountryCode() throws Exception {
        // Arrange: Customer without country code
        Customer customer = createValidCustomer();
        customer.setCustAddrCountryCd(null);
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Null country code should pass (optional field)
        assertThat(result).isNotNull();
    }

    /**
     * Test processing handles customers with multiple validation failures
     * 
     * Should fail fast on first validation error per COBOL short-circuit logic
     */
    @Test
    @DisplayName("Should reject customer with multiple validation failures")
    void testMultipleValidationFailures() throws Exception {
        // Arrange: Customer with multiple invalid fields
        Customer customer = Customer.builder()
            .custId(999999L)
            .custFirstName("")              // Invalid: empty
            .custLastName("")               // Invalid: empty
            .custSsn("12345")              // Invalid: too short
            .custAddrLine1("")             // Invalid: empty
            .custAddrStateCd("")           // Invalid: empty
            .custAddrZip("")               // Invalid: empty
            .custPhoneNum1("invalid")      // Invalid: format
            .custFicoCreditScore(100)      // Invalid: below range
            .custDobYyyyMmDd(LocalDate.now().minusYears(10))  // Invalid: under 18
            .build();
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Should reject customer with multiple failures
        assertThat(result).isNull();
    }

    /**
     * Test idempotent processing - processing same customer twice produces same result
     * 
     * COBOL equivalent: Sequential file reading - same record should validate consistently
     */
    @Test
    @DisplayName("Should produce consistent results for idempotent processing")
    void testIdempotentProcessing() throws Exception {
        // Arrange: Valid customer
        Customer customer = createValidCustomer();
        
        // Act: Process same customer twice
        Customer result1 = processor.process(customer);
        Customer result2 = processor.process(customer);
        
        // Assert: Both results should be identical
        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();
        assertThat(result1).isEqualTo(result2);
        assertThat(result1.getCustId()).isEqualTo(result2.getCustId());
    }

    /**
     * Test processing does not modify customer object
     * 
     * Ensures processor is read-only and doesn't mutate input
     */
    @Test
    @DisplayName("Should not modify customer object during processing")
    void testNoCustomerMutation() throws Exception {
        // Arrange: Valid customer with specific values
        Customer customer = createValidCustomer();
        String originalFirstName = customer.getCustFirstName();
        String originalLastName = customer.getCustLastName();
        String originalSsn = customer.getCustSsn();
        
        // Act: Process customer
        Customer result = processor.process(customer);
        
        // Assert: Original customer should remain unchanged
        assertThat(customer.getCustFirstName()).isEqualTo(originalFirstName);
        assertThat(customer.getCustLastName()).isEqualTo(originalLastName);
        assertThat(customer.getCustSsn()).isEqualTo(originalSsn);
        assertThat(result).isSameAs(customer);
    }

    /**
     * Test ZIP code validation accepts 5-digit format
     */
    @Test
    @DisplayName("Should accept 5-digit ZIP code")
    void testFiveDigitZipCode() throws Exception {
        // Arrange: Customer with 5-digit ZIP
        Customer customer = createValidCustomer();
        customer.setCustAddrZip("90210");
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: 5-digit ZIP should pass
        assertThat(result).isNotNull();
    }

    /**
     * Test ZIP code validation accepts ZIP+4 format
     */
    @Test
    @DisplayName("Should accept ZIP+4 format (9-digit)")
    void testZipPlusFourFormat() throws Exception {
        // Arrange: Customer with ZIP+4 format
        Customer customer = createValidCustomer();
        customer.setCustAddrZip("90210-1234");
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: ZIP+4 format should pass
        assertThat(result).isNotNull();
    }

    /**
     * Test validation of all fields with minimum valid data
     */
    @Test
    @DisplayName("Should accept customer with minimum required fields only")
    void testMinimumRequiredFields() throws Exception {
        // Arrange: Customer with only required fields
        Customer customer = Customer.builder()
            .custId(123456789L)
            .custFirstName("John")
            .custLastName("Doe")
            .custSsn("123456789")
            .custAddrLine1("123 Main St")
            .custAddrStateCd("CA")
            .custAddrZip("90210")
            .custDobYyyyMmDd(LocalDate.now().minusYears(30))
            .build();
        
        // Act
        Customer result = processor.process(customer);
        
        // Assert: Should pass with minimum required fields
        assertThat(result).isNotNull();
    }

    /**
     * Test performance with batch of customers
     * 
     * COBOL equivalent: Sequential file processing performance
     * Validates processor can handle batch workloads efficiently
     */
    @Test
    @DisplayName("Should process batch of customers efficiently")
    void testBatchProcessingPerformance() throws Exception {
        // Arrange: Create batch of 1000 valid customers
        int batchSize = 1000;
        long startTime = System.currentTimeMillis();
        
        // Act: Process batch of customers
        int processedCount = 0;
        for (int i = 0; i < batchSize; i++) {
            Customer customer = createValidCustomer();
            customer.setCustId((long) (100000 + i));
            Customer result = processor.process(customer);
            if (result != null) {
                processedCount++;
            }
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // Assert: All customers should be processed successfully
        assertThat(processedCount).isEqualTo(batchSize);
        
        // Performance assertion: Should process 1000 customers in reasonable time (< 5 seconds)
        assertThat(duration)
            .as("Batch processing should complete in under 5000ms")
            .isLessThan(5000);
        
        // Log performance metrics
        double throughput = (double) batchSize / duration * 1000;
        System.out.printf("Processed %d customers in %dms (%.2f customers/sec)%n", 
                         batchSize, duration, throughput);
    }

    /**
     * Test direct validation method: validateCustomerFields
     */
    @Test
    @DisplayName("validateCustomerFields should return true for valid names")
    void testValidateCustomerFieldsValid() {
        // Arrange: Customer with valid names
        Customer customer = createValidCustomer();
        
        // Act
        boolean result = processor.validateCustomerFields(customer);
        
        // Assert: Should return true
        assertThat(result).isTrue();
    }

    /**
     * Test direct validation method: validateCustomerFields with invalid data
     */
    @Test
    @DisplayName("validateCustomerFields should return false for missing first name")
    void testValidateCustomerFieldsInvalid() {
        // Arrange: Customer with missing first name
        Customer customer = createValidCustomer();
        customer.setCustFirstName(null);
        
        // Act
        boolean result = processor.validateCustomerFields(customer);
        
        // Assert: Should return false
        assertThat(result).isFalse();
    }

    /**
     * Test direct validation method: validateSSN
     */
    @Test
    @DisplayName("validateSSN should return true for valid SSN")
    void testValidateSSNValid() {
        // Arrange: Customer with valid SSN
        Customer customer = createValidCustomer();
        customer.setCustSsn("123456789");
        
        // Act
        boolean result = processor.validateSSN(customer);
        
        // Assert: Should return true
        assertThat(result).isTrue();
    }

    /**
     * Test direct validation method: validateSSN with invalid data
     */
    @Test
    @DisplayName("validateSSN should return false for invalid SSN")
    void testValidateSSNInvalid() {
        // Arrange: Customer with invalid SSN
        Customer customer = createValidCustomer();
        customer.setCustSsn("12345");
        
        // Act
        boolean result = processor.validateSSN(customer);
        
        // Assert: Should return false
        assertThat(result).isFalse();
    }

    /**
     * Test direct validation method: validateAddress
     */
    @Test
    @DisplayName("validateAddress should return true for complete address")
    void testValidateAddressValid() {
        // Arrange: Customer with complete address
        Customer customer = createValidCustomer();
        
        // Act
        boolean result = processor.validateAddress(customer);
        
        // Assert: Should return true
        assertThat(result).isTrue();
    }

    /**
     * Test direct validation method: validateAddress with invalid data
     */
    @Test
    @DisplayName("validateAddress should return false for missing ZIP")
    void testValidateAddressInvalid() {
        // Arrange: Customer with missing ZIP
        Customer customer = createValidCustomer();
        customer.setCustAddrZip(null);
        
        // Act
        boolean result = processor.validateAddress(customer);
        
        // Assert: Should return false
        assertThat(result).isFalse();
    }

    /**
     * Test direct validation method: validatePhoneNumber
     */
    @Test
    @DisplayName("validatePhoneNumber should return true for valid phone")
    void testValidatePhoneNumberValid() {
        // Arrange: Customer with valid phone
        Customer customer = createValidCustomer();
        customer.setCustPhoneNum1("1234567890");
        
        // Act
        boolean result = processor.validatePhoneNumber(customer);
        
        // Assert: Should return true
        assertThat(result).isTrue();
    }

    /**
     * Test direct validation method: validatePhoneNumber with invalid data
     */
    @Test
    @DisplayName("validatePhoneNumber should return false for invalid format")
    void testValidatePhoneNumberInvalid() {
        // Arrange: Customer with invalid phone
        Customer customer = createValidCustomer();
        customer.setCustPhoneNum1("invalid-phone");
        
        // Act
        boolean result = processor.validatePhoneNumber(customer);
        
        // Assert: Should return false
        assertThat(result).isFalse();
    }

    /**
     * Test direct validation method: validateFICOScore
     */
    @Test
    @DisplayName("validateFICOScore should return true for valid score")
    void testValidateFICOScoreValid() {
        // Arrange: Customer with valid FICO score
        Customer customer = createValidCustomer();
        customer.setCustFicoCreditScore(700);
        
        // Act
        boolean result = processor.validateFICOScore(customer);
        
        // Assert: Should return true
        assertThat(result).isTrue();
    }

    /**
     * Test direct validation method: validateFICOScore with invalid data
     */
    @Test
    @DisplayName("validateFICOScore should return false for out-of-range score")
    void testValidateFICOScoreInvalid() {
        // Arrange: Customer with invalid FICO score
        Customer customer = createValidCustomer();
        customer.setCustFicoCreditScore(100);
        
        // Act
        boolean result = processor.validateFICOScore(customer);
        
        // Assert: Should return false
        assertThat(result).isFalse();
    }

    /**
     * Test direct validation method: validateDateOfBirth
     */
    @Test
    @DisplayName("validateDateOfBirth should return true for valid age")
    void testValidateDateOfBirthValid() {
        // Arrange: Customer with valid date of birth
        Customer customer = createValidCustomer();
        customer.setCustDobYyyyMmDd(LocalDate.now().minusYears(30));
        
        // Act
        boolean result = processor.validateDateOfBirth(customer);
        
        // Assert: Should return true
        assertThat(result).isTrue();
    }

    /**
     * Test direct validation method: validateDateOfBirth with invalid data
     */
    @Test
    @DisplayName("validateDateOfBirth should return false for under-age customer")
    void testValidateDateOfBirthInvalid() {
        // Arrange: Customer under 18 years old
        Customer customer = createValidCustomer();
        customer.setCustDobYyyyMmDd(LocalDate.now().minusYears(15));
        
        // Act
        boolean result = processor.validateDateOfBirth(customer);
        
        // Assert: Should return false
        assertThat(result).isFalse();
    }

    /**
     * Helper method to create a valid customer with all required fields
     * 
     * Creates a customer that passes all validation rules:
     * - Valid 9-digit SSN
     * - Valid first and last name
     * - Complete address (line 1, state, ZIP)
     * - Valid phone number
     * - Valid FICO score (700)
     * - Valid date of birth (30 years old)
     * 
     * @return A fully populated valid Customer entity
     */
    private Customer createValidCustomer() {
        return Customer.builder()
            .custId(123456789L)
            .custFirstName("John")
            .custMiddleName("Michael")
            .custLastName("Doe")
            .custAddrLine1("123 Main Street")
            .custAddrLine2("Apt 4B")
            .custAddrLine3("Building A")
            .custAddrStateCd("CA")
            .custAddrCountryCd("USA")
            .custAddrZip("90210")
            .custPhoneNum1("1234567890")
            .custPhoneNum2("(123) 456-7890")
            .custSsn("123456789")
            .custGovtIssuedId("DL-CA-12345678")
            .custDobYyyyMmDd(LocalDate.now().minusYears(30))
            .custFicoCreditScore(700)
            .build();
    }
}


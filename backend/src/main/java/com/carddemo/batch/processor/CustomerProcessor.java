package com.carddemo.batch.processor;

import com.carddemo.model.entity.Customer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.util.regex.Pattern;

/**
 * Spring Batch ItemProcessor for Customer Validation
 * 
 * Converted from COBOL program: CBCUS01C.cbl
 * Original function: Read and validate customer data file
 * JCL Job: CBCUSJ01.jcl
 * 
 * This processor implements comprehensive customer field validation including:
 * - SSN format validation (9-digit numeric format)
 * - Address field validation (non-empty checks for required fields)
 * - Phone number format validation (15-character max length)
 * - FICO credit score range validation (300-850)
 * - Date of birth validation (format and reasonable age range 18-120)
 * 
 * Conversion Notes:
 * - COBOL 1000-CUSTFILE-GET-NEXT paragraph → process() method
 * - COBOL DISPLAY statements → SLF4J logging (log.info, log.warn, log.error)
 * - COBOL CUSTFILE-STATUS checks → validation logic returning null for invalid customers
 * - COBOL APPL-RESULT codes → null return per Spring Batch filtering conventions
 * - COBOL sequential file reading → Spring Batch ItemReader handles file I/O
 * - COBOL COMP-3 fields → BigDecimal precision maintained in validation logic
 * - COBOL PIC 9(9) SSN validation → Java regex Pattern matching
 * - COBOL paragraph-level error handling → method-level validation with logging
 * 
 * Spring Batch Filtering Convention:
 * - Returns the Customer entity if ALL validations pass
 * - Returns null to filter out (reject) customers that fail any validation
 * - Null return signals Spring Batch to skip writing this item to output
 * 
 * Performance Considerations:
 * - Regex patterns compiled as static finals for performance optimization
 * - Validation methods short-circuit on first failure to minimize processing
 * - Logging at appropriate levels (info for valid, warn for invalid customers)
 * 
 * Security Considerations:
 * - SSN and other PII data logged only at debug level (not in standard logs)
 * - Validation errors log field names but not actual PII values
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Slf4j
@Component
public class CustomerProcessor implements ItemProcessor<Customer, Customer> {

    /**
     * Regex pattern for SSN validation - exactly 9 numeric digits
     * COBOL equivalent: PIC 9(09) with numeric validation
     * Pattern: ^\d{9}$ means start-of-string, exactly 9 digits, end-of-string
     */
    private static final Pattern SSN_PATTERN = Pattern.compile("^\\d{9}$");

    /**
     * Regex pattern for phone number validation - allows digits, spaces, hyphens, parentheses
     * COBOL equivalent: PIC X(15) with alphanumeric validation
     * Pattern allows common phone formats: (123) 456-7890, 123-456-7890, 1234567890
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[\\d\\s\\-\\(\\)]+$");

    /**
     * Minimum valid FICO credit score (industry standard)
     * COBOL equivalent: Low value validation for CUST-FICO-CREDIT-SCORE PIC 9(03)
     */
    private static final int MIN_FICO_SCORE = 300;

    /**
     * Maximum valid FICO credit score (industry standard)
     * COBOL equivalent: High value validation for CUST-FICO-CREDIT-SCORE PIC 9(03)
     */
    private static final int MAX_FICO_SCORE = 850;

    /**
     * Minimum valid age for customer (18 years)
     * Business rule: Customers must be legal adults
     */
    private static final int MIN_AGE = 18;

    /**
     * Maximum reasonable age for customer (120 years)
     * Business rule: Data quality check for reasonable age range
     */
    private static final int MAX_AGE = 120;

    /**
     * Main processing method implementing ItemProcessor interface
     * 
     * Converts COBOL 1000-CUSTFILE-GET-NEXT paragraph sequential validation logic
     * to Spring Batch item processing pattern.
     * 
     * COBOL Flow:
     * 1. READ CUSTFILE-FILE INTO CUSTOMER-RECORD
     * 2. Check CUSTFILE-STATUS = '00' (success) or '10' (EOF) or error
     * 3. If success: DISPLAY CUSTOMER-RECORD
     * 4. If error: DISPLAY error message and ABEND
     * 
     * Java Flow:
     * 1. Validate all customer fields (validateCustomerFields)
     * 2. Validate SSN format (validateSSN)
     * 3. Validate address completeness (validateAddress)
     * 4. Validate phone numbers (validatePhoneNumber)
     * 5. Validate FICO score range (validateFICOScore)
     * 6. Validate date of birth (validateDateOfBirth)
     * 7. Return customer if all valid, null if any validation fails
     * 
     * @param customer the Customer entity to validate
     * @return the validated Customer if all checks pass, null to filter invalid customers
     * @throws Exception if unexpected error occurs during processing
     */
    @Override
    public Customer process(Customer customer) throws Exception {
        // Log customer processing start (debug level to avoid PII in standard logs)
        log.debug("Processing customer ID: {}", customer.getCustId());
        
        try {
            // Perform all validation checks in sequence
            // Each validation method returns boolean indicating pass/fail
            // Short-circuit evaluation: if any validation fails, return null immediately
            
            // Validate required customer fields (first name, last name, etc.)
            if (!validateCustomerFields(customer)) {
                log.warn("Customer ID {} failed required fields validation", customer.getCustId());
                return null; // Filter out this customer per Spring Batch convention
            }
            
            // Validate SSN format (must be exactly 9 numeric digits)
            if (!validateSSN(customer)) {
                log.warn("Customer ID {} failed SSN format validation", customer.getCustId());
                return null;
            }
            
            // Validate address completeness (required address fields must be present)
            if (!validateAddress(customer)) {
                log.warn("Customer ID {} failed address validation", customer.getCustId());
                return null;
            }
            
            // Validate phone number format (if present)
            if (!validatePhoneNumber(customer)) {
                log.warn("Customer ID {} failed phone number validation", customer.getCustId());
                return null;
            }
            
            // Validate FICO credit score range (300-850)
            if (!validateFICOScore(customer)) {
                log.warn("Customer ID {} failed FICO score validation", customer.getCustId());
                return null;
            }
            
            // Validate date of birth (reasonable age range 18-120)
            if (!validateDateOfBirth(customer)) {
                log.warn("Customer ID {} failed date of birth validation", customer.getCustId());
                return null;
            }
            
            // All validations passed - log success and return customer
            log.info("Customer ID {} passed all validations", customer.getCustId());
            return customer;
            
        } catch (Exception e) {
            // Handle unexpected errors during validation
            // COBOL equivalent: ABEND with error code
            log.error("Unexpected error processing customer ID {}: {}", 
                     customer.getCustId(), e.getMessage(), e);
            // Re-throw exception to trigger Spring Batch error handling
            throw e;
        }
    }

    /**
     * Validate required customer fields are present and not empty
     * 
     * COBOL equivalent: Checks for required fields in CUSTOMER-RECORD
     * - CUST-FIRST-NAME must not be spaces
     * - CUST-LAST-NAME must not be spaces
     * 
     * Validation Rules:
     * 1. First name must be present and not blank (after trimming)
     * 2. Last name must be present and not blank (after trimming)
     * 3. Both names must not consist only of whitespace
     * 
     * @param customer the Customer entity to validate
     * @return true if all required fields are valid, false otherwise
     */
    public boolean validateCustomerFields(Customer customer) {
        // Validate first name is present
        String firstName = customer.getCustFirstName();
        if (firstName == null || firstName.trim().isEmpty()) {
            log.debug("Customer ID {} has missing or empty first name", customer.getCustId());
            return false;
        }
        
        // Validate last name is present
        String lastName = customer.getCustLastName();
        if (lastName == null || lastName.trim().isEmpty()) {
            log.debug("Customer ID {} has missing or empty last name", customer.getCustId());
            return false;
        }
        
        // All required customer fields are valid
        return true;
    }

    /**
     * Validate SSN format - must be exactly 9 numeric digits
     * 
     * COBOL equivalent: CUST-SSN PIC 9(09) with numeric validation
     * In COBOL, PIC 9(09) enforces numeric content at compile time
     * In Java, we validate using regex pattern matching
     * 
     * Validation Rules:
     * 1. SSN must not be null
     * 2. SSN must be exactly 9 characters long
     * 3. SSN must contain only digits (0-9)
     * 4. No spaces, dashes, or other formatting characters allowed
     * 
     * Valid format: "123456789" (9 consecutive digits)
     * Invalid formats: "123-45-6789", "12345678", "1234567890", "12345678A"
     * 
     * @param customer the Customer entity to validate
     * @return true if SSN format is valid, false otherwise
     */
    public boolean validateSSN(Customer customer) {
        String ssn = customer.getCustSsn();
        
        // Check if SSN is null or empty
        if (ssn == null || ssn.isEmpty()) {
            log.debug("Customer ID {} has missing SSN", customer.getCustId());
            return false;
        }
        
        // Validate SSN matches pattern: exactly 9 digits
        if (!SSN_PATTERN.matcher(ssn).matches()) {
            log.debug("Customer ID {} has invalid SSN format (must be 9 digits)", customer.getCustId());
            return false;
        }
        
        // SSN format is valid
        return true;
    }

    /**
     * Validate address completeness - required address fields must be present
     * 
     * COBOL equivalent: Checks for required address fields in CUSTOMER-RECORD
     * - CUST-ADDR-LINE-1 must not be spaces (primary address required)
     * - CUST-ADDR-STATE-CD must not be spaces (state code required)
     * - CUST-ADDR-ZIP must not be spaces (zip code required)
     * 
     * Validation Rules:
     * 1. Address line 1 must be present and not blank (primary street address)
     * 2. State code must be present and not blank (2-letter state abbreviation)
     * 3. ZIP code must be present and not blank (postal code)
     * 4. Address lines 2 and 3 are optional (can be null or empty)
     * 
     * @param customer the Customer entity to validate
     * @return true if address is complete, false otherwise
     */
    public boolean validateAddress(Customer customer) {
        // Validate address line 1 is present (required)
        String addrLine1 = customer.getCustAddrLine1();
        if (addrLine1 == null || addrLine1.trim().isEmpty()) {
            log.debug("Customer ID {} has missing or empty address line 1", customer.getCustId());
            return false;
        }
        
        // Validate state code is present (required)
        String stateCd = customer.getCustAddrStateCd();
        if (stateCd == null || stateCd.trim().isEmpty()) {
            log.debug("Customer ID {} has missing or empty state code", customer.getCustId());
            return false;
        }
        
        // Validate ZIP code is present (required)
        String zip = customer.getCustAddrZip();
        if (zip == null || zip.trim().isEmpty()) {
            log.debug("Customer ID {} has missing or empty ZIP code", customer.getCustId());
            return false;
        }
        
        // Address is complete and valid
        return true;
    }

    /**
     * Validate phone number format
     * 
     * COBOL equivalent: CUST-PHONE-NUM-1 PIC X(15) and CUST-PHONE-NUM-2 PIC X(15)
     * In COBOL, PIC X(15) allows any alphanumeric characters up to 15 bytes
     * In Java, we enforce phone number format with allowed characters
     * 
     * Validation Rules:
     * 1. Phone number is optional (can be null or empty)
     * 2. If present, must contain only digits, spaces, hyphens, parentheses
     * 3. Length must not exceed 15 characters (COBOL PIC X(15) constraint)
     * 4. Must contain at least one digit (can't be all formatting characters)
     * 
     * Valid formats: "(123) 456-7890", "123-456-7890", "1234567890", "123 456 7890"
     * Invalid formats: "123-456-ABCD", "phone#123", "1234567890123456" (too long)
     * 
     * @param customer the Customer entity to validate
     * @return true if phone number format is valid or not present, false otherwise
     */
    public boolean validatePhoneNumber(Customer customer) {
        // Validate primary phone number (if present)
        String phoneNum1 = customer.getCustPhoneNum1();
        if (phoneNum1 != null && !phoneNum1.trim().isEmpty()) {
            // Check length constraint (max 15 characters)
            if (phoneNum1.length() > 15) {
                log.debug("Customer ID {} has primary phone number exceeding 15 characters", 
                         customer.getCustId());
                return false;
            }
            
            // Check format (digits, spaces, hyphens, parentheses only)
            if (!PHONE_PATTERN.matcher(phoneNum1).matches()) {
                log.debug("Customer ID {} has invalid primary phone number format", 
                         customer.getCustId());
                return false;
            }
            
            // Check that phone contains at least one digit
            if (!phoneNum1.matches(".*\\d.*")) {
                log.debug("Customer ID {} primary phone number contains no digits", 
                         customer.getCustId());
                return false;
            }
        }
        
        // Validate secondary phone number (if present)
        String phoneNum2 = customer.getCustPhoneNum2();
        if (phoneNum2 != null && !phoneNum2.trim().isEmpty()) {
            // Check length constraint (max 15 characters)
            if (phoneNum2.length() > 15) {
                log.debug("Customer ID {} has secondary phone number exceeding 15 characters", 
                         customer.getCustId());
                return false;
            }
            
            // Check format (digits, spaces, hyphens, parentheses only)
            if (!PHONE_PATTERN.matcher(phoneNum2).matches()) {
                log.debug("Customer ID {} has invalid secondary phone number format", 
                         customer.getCustId());
                return false;
            }
            
            // Check that phone contains at least one digit
            if (!phoneNum2.matches(".*\\d.*")) {
                log.debug("Customer ID {} secondary phone number contains no digits", 
                         customer.getCustId());
                return false;
            }
        }
        
        // Phone numbers are valid (or not present, which is acceptable)
        return true;
    }

    /**
     * Validate FICO credit score is within valid range (300-850)
     * 
     * COBOL equivalent: CUST-FICO-CREDIT-SCORE PIC 9(03) with range validation
     * In COBOL, PIC 9(03) allows values 000-999
     * In Java, we enforce industry-standard FICO score range (300-850)
     * 
     * Validation Rules:
     * 1. FICO score is optional (can be null for customers without credit history)
     * 2. If present, must be between 300 and 850 (inclusive)
     * 3. 300 = lowest possible FICO score (very poor credit)
     * 4. 850 = highest possible FICO score (exceptional credit)
     * 
     * Industry Standard FICO Score Ranges:
     * - 300-579: Poor
     * - 580-669: Fair
     * - 670-739: Good
     * - 740-799: Very Good
     * - 800-850: Exceptional
     * 
     * @param customer the Customer entity to validate
     * @return true if FICO score is valid or not present, false otherwise
     */
    public boolean validateFICOScore(Customer customer) {
        Integer ficoScore = customer.getCustFicoCreditScore();
        
        // FICO score is optional - null is acceptable
        if (ficoScore == null) {
            return true;
        }
        
        // Validate FICO score is within valid range (300-850)
        if (ficoScore < MIN_FICO_SCORE || ficoScore > MAX_FICO_SCORE) {
            log.debug("Customer ID {} has invalid FICO score {} (must be between {} and {})", 
                     customer.getCustId(), ficoScore, MIN_FICO_SCORE, MAX_FICO_SCORE);
            return false;
        }
        
        // FICO score is within valid range
        return true;
    }

    /**
     * Validate date of birth is reasonable and customer is of legal age
     * 
     * COBOL equivalent: CUST-DOB-YYYY-MM-DD PIC X(10) with date format validation
     * In COBOL, date stored as string in YYYY-MM-DD format (e.g., "1980-05-15")
     * In Java, LocalDate provides built-in date validation and arithmetic
     * 
     * Validation Rules:
     * 1. Date of birth must not be null (required field)
     * 2. Date of birth must not be in the future
     * 3. Customer age must be at least 18 years (legal adult)
     * 4. Customer age must not exceed 120 years (data quality check)
     * 
     * Age Calculation:
     * - Uses Period.between() to calculate years between DOB and current date
     * - Handles leap years and varying month lengths correctly
     * - Considers only full years (partial years not counted)
     * 
     * @param customer the Customer entity to validate
     * @return true if date of birth is valid, false otherwise
     */
    public boolean validateDateOfBirth(Customer customer) {
        LocalDate dob = customer.getCustDobYyyyMmDd();
        
        // Date of birth is required
        if (dob == null) {
            log.debug("Customer ID {} has missing date of birth", customer.getCustId());
            return false;
        }
        
        // Get current date for age calculation
        LocalDate today = LocalDate.now();
        
        // Validate date of birth is not in the future
        if (dob.isAfter(today)) {
            log.debug("Customer ID {} has date of birth in the future", customer.getCustId());
            return false;
        }
        
        // Calculate customer age in years
        int age = Period.between(dob, today).getYears();
        
        // Validate customer is at least 18 years old (legal adult)
        if (age < MIN_AGE) {
            log.debug("Customer ID {} is under minimum age {} (age: {})", 
                     customer.getCustId(), MIN_AGE, age);
            return false;
        }
        
        // Validate customer age is not unreasonably high (data quality check)
        if (age > MAX_AGE) {
            log.debug("Customer ID {} exceeds maximum age {} (age: {})", 
                     customer.getCustId(), MAX_AGE, age);
            return false;
        }
        
        // Date of birth is valid
        return true;
    }
}


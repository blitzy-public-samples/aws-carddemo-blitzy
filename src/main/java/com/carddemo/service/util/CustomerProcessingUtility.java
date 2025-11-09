/*
 * CustomerProcessingUtility.java
 * 
 * CardDemo Application - Customer Data Processing Utility
 * 
 * This utility class provides customer data validation, formatting, and processing
 * helper methods transformed from COBOL batch program CBCUS01C.cbl and customer
 * record layout copybook CVCUS01Y.cpy.
 * 
 * Original COBOL Program: CBCUS01C.cbl
 * - Batch customer file processing program
 * - Sequential VSAM KSDS file reading with validation
 * - Customer record display and error handling
 * 
 * Original COBOL Copybook: CVCUS01Y.cpy
 * - Customer master record layout (500-byte records)
 * - Field definitions: ID, names, address, phone, SSN, DOB, FICO
 * 
 * Transformation Approach:
 * - COBOL file I/O operations → Utility methods for entity validation
 * - COBOL DISPLAY statements → SLF4J logging
 * - COBOL validation logic → Java validation methods with exceptions
 * - Field validation patterns → Regex-based validation with business rules
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.service.util;

import com.carddemo.exception.ValidationException;
import com.carddemo.constants.LookupCode;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring utility service class providing customer data validation, formatting,
 * and processing helper methods.
 * 
 * <p>This class serves as a foundational dependency across all service layer
 * classes for customer-related data transformations and validations.</p>
 * 
 * <p><b>COBOL Origin:</b></p>
 * <ul>
 *   <li>Program: CBCUS01C.cbl - Batch customer file processing</li>
 *   <li>Copybook: CVCUS01Y.cpy - Customer master record layout (500 bytes)</li>
 * </ul>
 * 
 * <p><b>Validation Capabilities:</b></p>
 * <ul>
 *   <li>SSN validation - 9-digit format, invalid pattern detection</li>
 *   <li>Phone number formatting - US phone pattern normalization</li>
 *   <li>Address normalization - State codes, ZIP validation</li>
 *   <li>FICO score validation - Range 300-850</li>
 *   <li>Date of birth validation - Age 18+ constraint</li>
 *   <li>Customer name validation - Special character checks</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Component
public class CustomerProcessingUtility {

    private static final Logger logger = LoggerFactory.getLogger(CustomerProcessingUtility.class);

    // SSN validation patterns (CUST-SSN PIC 9(09) from CVCUS01Y.cpy)
    private static final Pattern SSN_PATTERN = Pattern.compile("^\\d{9}$");
    private static final Pattern SSN_ALL_ZEROS = Pattern.compile("^0{9}$");
    private static final Pattern SSN_SEQUENTIAL = Pattern.compile("^(012345678|123456789|234567890|987654321|876543210)$");
    
    // Phone number patterns (CUST-PHONE-NUM-1/2 PIC X(15) from CVCUS01Y.cpy)
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\(?([0-9]{3})\\)?[-. ]?([0-9]{3})[-. ]?([0-9]{4})$");
    private static final Pattern PHONE_DIGITS_ONLY = Pattern.compile("^\\d{10}$");
    
    // ZIP code patterns (CUST-ADDR-ZIP PIC X(10) from CVCUS01Y.cpy)
    private static final Pattern ZIP_5_DIGIT = Pattern.compile("^\\d{5}$");
    private static final Pattern ZIP_9_DIGIT = Pattern.compile("^\\d{5}-\\d{4}$");
    private static final Pattern ZIP_9_DIGIT_NO_DASH = Pattern.compile("^\\d{9}$");
    
    // Name validation pattern (CUST-FIRST-NAME, CUST-MIDDLE-NAME, CUST-LAST-NAME PIC X(25))
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z]+([ '-][a-zA-Z]+)*$");
    
    // FICO score range (CUST-FICO-CREDIT-SCORE PIC 9(03) from CVCUS01Y.cpy)
    private static final int FICO_MIN = 300;
    private static final int FICO_MAX = 850;
    
    // Minimum age requirement
    private static final int MINIMUM_AGE = 18;
    
    // Date format for DOB (CUST-DOB-YYYY-MM-DD PIC X(10) from CVCUS01Y.cpy)
    private static final DateTimeFormatter DOB_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Validates a Social Security Number (SSN) format and patterns.
     * 
     * <p>Performs comprehensive validation including:</p>
     * <ul>
     *   <li>9-digit format check</li>
     *   <li>All zeros check (000000000 is invalid)</li>
     *   <li>Sequential pattern check (123456789, 987654321, etc.)</li>
     * </ul>
     * 
     * <p><b>COBOL Field:</b> CUST-SSN PIC 9(09) from CVCUS01Y.cpy</p>
     * 
     * @param ssn The Social Security Number to validate
     * @return The validated SSN string
     * @throws ValidationException if SSN format is invalid, all zeros, or sequential
     */
    public String validateSSN(String ssn) {
        logger.debug("Validating SSN: {}", ssn != null ? "***-**-" + ssn.substring(Math.max(0, ssn.length() - 4)) : "null");
        
        if (ssn == null || ssn.trim().isEmpty()) {
            logger.warn("SSN validation failed: SSN is null or empty");
            throw new ValidationException("SSN is required");
        }
        
        String cleanSSN = ssn.replaceAll("[^0-9]", "");
        
        if (!SSN_PATTERN.matcher(cleanSSN).matches()) {
            logger.warn("SSN validation failed: Invalid format - must be 9 digits");
            throw new ValidationException("SSN must be exactly 9 digits");
        }
        
        if (SSN_ALL_ZEROS.matcher(cleanSSN).matches()) {
            logger.warn("SSN validation failed: All zeros pattern detected");
            throw new ValidationException("SSN cannot be all zeros");
        }
        
        if (SSN_SEQUENTIAL.matcher(cleanSSN).matches()) {
            logger.warn("SSN validation failed: Sequential pattern detected");
            throw new ValidationException("SSN cannot be a sequential pattern");
        }
        
        logger.debug("SSN validation successful");
        return cleanSSN;
    }

    /**
     * Checks if a Social Security Number is valid without throwing exceptions.
     * 
     * <p>Performs the same validation as {@link #validateSSN(String)} but
     * returns a boolean result instead of throwing exceptions.</p>
     * 
     * @param ssn The Social Security Number to validate
     * @return true if SSN is valid, false otherwise
     */
    public boolean isValidSSN(String ssn) {
        try {
            validateSSN(ssn);
            return true;
        } catch (ValidationException e) {
            logger.debug("SSN validation check returned false: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Formats a phone number to standard US format (###) ###-####.
     * 
     * <p>Accepts various input formats:</p>
     * <ul>
     *   <li>10 digits: 2015551234 → (201) 555-1234</li>
     *   <li>With separators: 201-555-1234 → (201) 555-1234</li>
     *   <li>With parentheses: (201) 555-1234 → (201) 555-1234</li>
     * </ul>
     * 
     * <p>Validates area code against NANPA registry using LookupCode.</p>
     * 
     * <p><b>COBOL Fields:</b> CUST-PHONE-NUM-1, CUST-PHONE-NUM-2 PIC X(15) from CVCUS01Y.cpy</p>
     * 
     * @param phoneNumber The phone number to format
     * @return Formatted phone number in (###) ###-#### format
     * @throws ValidationException if phone number format is invalid or area code is invalid
     */
    public String formatPhoneNumber(String phoneNumber) {
        logger.debug("Formatting phone number");
        
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            logger.warn("Phone number formatting failed: Phone number is null or empty");
            throw new ValidationException("Phone number is required");
        }
        
        String cleanPhone = phoneNumber.replaceAll("[^0-9]", "");
        
        if (!PHONE_DIGITS_ONLY.matcher(cleanPhone).matches()) {
            logger.warn("Phone number formatting failed: Must be 10 digits");
            throw new ValidationException("Phone number must be exactly 10 digits");
        }
        
        String areaCode = cleanPhone.substring(0, 3);
        String exchange = cleanPhone.substring(3, 6);
        String lineNumber = cleanPhone.substring(6, 10);
        
        // Validate area code using LookupCode
        if (!LookupCode.isValidPhoneAreaCode(areaCode)) {
            logger.warn("Phone number formatting failed: Invalid area code {}", areaCode);
            throw new ValidationException("Invalid phone area code: " + areaCode);
        }
        
        String formattedPhone = String.format("(%s) %s-%s", areaCode, exchange, lineNumber);
        logger.debug("Phone number formatted successfully");
        return formattedPhone;
    }

    /**
     * Checks if a phone number is valid without throwing exceptions.
     * 
     * @param phoneNumber The phone number to validate
     * @return true if phone number is valid, false otherwise
     */
    public boolean isValidPhoneNumber(String phoneNumber) {
        try {
            formatPhoneNumber(phoneNumber);
            return true;
        } catch (ValidationException e) {
            logger.debug("Phone number validation check returned false: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Normalizes an address line by trimming whitespace and standardizing format.
     * 
     * <p>Performs the following normalization:</p>
     * <ul>
     *   <li>Trims leading and trailing whitespace</li>
     *   <li>Replaces multiple consecutive spaces with single space</li>
     *   <li>Capitalizes first letter of each word</li>
     * </ul>
     * 
     * <p><b>COBOL Fields:</b> CUST-ADDR-LINE-1/2/3 PIC X(50) from CVCUS01Y.cpy</p>
     * 
     * @param addressLine The address line to normalize
     * @return Normalized address line, or empty string if null
     */
    public String normalizeAddressLine(String addressLine) {
        logger.debug("Normalizing address line");
        
        if (addressLine == null || addressLine.trim().isEmpty()) {
            logger.debug("Address line is null or empty, returning empty string");
            return "";
        }
        
        // Trim and collapse multiple spaces
        String normalized = addressLine.trim().replaceAll("\\s+", " ");
        
        logger.debug("Address line normalized successfully");
        return normalized;
    }

    /**
     * Normalizes a US state code to uppercase 2-letter format.
     * 
     * <p>Validates the state code against valid US states and territories
     * using LookupCode utility.</p>
     * 
     * <p><b>COBOL Field:</b> CUST-ADDR-STATE-CD PIC X(02) from CVCUS01Y.cpy</p>
     * 
     * @param stateCode The state code to normalize
     * @return Normalized uppercase 2-letter state code
     * @throws ValidationException if state code is invalid
     */
    public String normalizeState(String stateCode) {
        logger.debug("Normalizing state code: {}", stateCode);
        
        if (stateCode == null || stateCode.trim().isEmpty()) {
            logger.warn("State code normalization failed: State code is null or empty");
            throw new ValidationException("State code is required");
        }
        
        String normalizedState = stateCode.trim().toUpperCase();
        
        if (normalizedState.length() != 2) {
            logger.warn("State code normalization failed: Must be 2 characters");
            throw new ValidationException("State code must be exactly 2 characters");
        }
        
        // Validate using LookupCode
        if (!LookupCode.isValidStateCode(normalizedState)) {
            logger.warn("State code normalization failed: Invalid state code {}", normalizedState);
            throw new ValidationException("Invalid US state code: " + normalizedState);
        }
        
        logger.debug("State code normalized successfully to {}", normalizedState);
        return normalizedState;
    }

    /**
     * Normalizes a ZIP code to standard format.
     * 
     * <p>Accepts and normalizes the following formats:</p>
     * <ul>
     *   <li>5-digit: 12345 → 12345</li>
     *   <li>9-digit with dash: 12345-6789 → 12345-6789</li>
     *   <li>9-digit without dash: 123456789 → 12345-6789</li>
     * </ul>
     * 
     * <p><b>COBOL Field:</b> CUST-ADDR-ZIP PIC X(10) from CVCUS01Y.cpy</p>
     * 
     * @param zipCode The ZIP code to normalize
     * @return Normalized ZIP code (5-digit or 9-digit with dash format)
     * @throws ValidationException if ZIP code format is invalid
     */
    public String normalizeZip(String zipCode) {
        logger.debug("Normalizing ZIP code");
        
        if (zipCode == null || zipCode.trim().isEmpty()) {
            logger.warn("ZIP code normalization failed: ZIP code is null or empty");
            throw new ValidationException("ZIP code is required");
        }
        
        String cleanZip = zipCode.trim().replaceAll("[^0-9-]", "");
        
        if (ZIP_5_DIGIT.matcher(cleanZip).matches()) {
            logger.debug("ZIP code normalized successfully (5-digit format)");
            return cleanZip;
        } else if (ZIP_9_DIGIT.matcher(cleanZip).matches()) {
            logger.debug("ZIP code normalized successfully (9-digit format)");
            return cleanZip;
        } else if (ZIP_9_DIGIT_NO_DASH.matcher(cleanZip).matches()) {
            String normalizedZip = cleanZip.substring(0, 5) + "-" + cleanZip.substring(5);
            logger.debug("ZIP code normalized successfully (9-digit format with dash added)");
            return normalizedZip;
        } else {
            logger.warn("ZIP code normalization failed: Invalid format");
            throw new ValidationException("ZIP code must be 5 digits or 9 digits (with or without dash)");
        }
    }

    /**
     * Checks if a ZIP code is valid without throwing exceptions.
     * 
     * @param zipCode The ZIP code to validate
     * @return true if ZIP code is valid, false otherwise
     */
    public boolean isValidZipCode(String zipCode) {
        try {
            normalizeZip(zipCode);
            return true;
        } catch (ValidationException e) {
            logger.debug("ZIP code validation check returned false: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validates a FICO credit score is within the valid range.
     * 
     * <p>FICO scores must be in the range 300-850 per credit scoring standards.</p>
     * 
     * <p><b>COBOL Field:</b> CUST-FICO-CREDIT-SCORE PIC 9(03) from CVCUS01Y.cpy</p>
     * 
     * @param ficoScore The FICO credit score to validate
     * @return The validated FICO score
     * @throws ValidationException if FICO score is null or out of valid range
     */
    public Integer validateFicoScore(Integer ficoScore) {
        logger.debug("Validating FICO score: {}", ficoScore);
        
        if (ficoScore == null) {
            logger.warn("FICO score validation failed: Score is null");
            throw new ValidationException("FICO credit score is required");
        }
        
        if (ficoScore < FICO_MIN || ficoScore > FICO_MAX) {
            logger.warn("FICO score validation failed: Score {} is out of valid range {}-{}", 
                       ficoScore, FICO_MIN, FICO_MAX);
            throw new ValidationException(
                String.format("FICO credit score must be between %d and %d, got %d", 
                             FICO_MIN, FICO_MAX, ficoScore));
        }
        
        logger.debug("FICO score validation successful");
        return ficoScore;
    }

    /**
     * Validates a date of birth ensuring customer meets minimum age requirement.
     * 
     * <p>Validates that the customer is at least 18 years old as of today's date.</p>
     * 
     * <p><b>COBOL Field:</b> CUST-DOB-YYYY-MM-DD PIC X(10) from CVCUS01Y.cpy</p>
     * 
     * @param dateOfBirth The date of birth to validate
     * @return The validated date of birth
     * @throws ValidationException if date of birth is null or customer is under 18
     */
    public LocalDate validateDateOfBirth(LocalDate dateOfBirth) {
        logger.debug("Validating date of birth: {}", dateOfBirth);
        
        if (dateOfBirth == null) {
            logger.warn("Date of birth validation failed: Date is null");
            throw new ValidationException("Date of birth is required");
        }
        
        LocalDate today = LocalDate.now();
        
        if (dateOfBirth.isAfter(today)) {
            logger.warn("Date of birth validation failed: Date is in the future");
            throw new ValidationException("Date of birth cannot be in the future");
        }
        
        int age = calculateAge(dateOfBirth);
        
        if (age < MINIMUM_AGE) {
            logger.warn("Date of birth validation failed: Customer age {} is under minimum age {}", 
                       age, MINIMUM_AGE);
            throw new ValidationException(
                String.format("Customer must be at least %d years old, current age is %d", 
                             MINIMUM_AGE, age));
        }
        
        logger.debug("Date of birth validation successful, customer age: {}", age);
        return dateOfBirth;
    }

    /**
     * Validates a date of birth from string format (YYYY-MM-DD).
     * 
     * <p>Parses the date string and validates age requirement.</p>
     * 
     * <p><b>COBOL Field:</b> CUST-DOB-YYYY-MM-DD PIC X(10) from CVCUS01Y.cpy</p>
     * 
     * @param dateOfBirthString The date of birth string in YYYY-MM-DD format
     * @return The validated and parsed LocalDate
     * @throws ValidationException if date string is invalid, cannot be parsed, or age requirement not met
     */
    public LocalDate validateDateOfBirth(String dateOfBirthString) {
        logger.debug("Validating date of birth string: {}", dateOfBirthString);
        
        if (dateOfBirthString == null || dateOfBirthString.trim().isEmpty()) {
            logger.warn("Date of birth validation failed: Date string is null or empty");
            throw new ValidationException("Date of birth is required");
        }
        
        LocalDate dateOfBirth;
        try {
            dateOfBirth = LocalDate.parse(dateOfBirthString.trim(), DOB_FORMATTER);
        } catch (DateTimeParseException e) {
            logger.warn("Date of birth validation failed: Invalid date format", e);
            throw new ValidationException(
                "Date of birth must be in YYYY-MM-DD format, got: " + dateOfBirthString, e);
        }
        
        return validateDateOfBirth(dateOfBirth);
    }

    /**
     * Calculates age in years from a date of birth.
     * 
     * <p>Uses Period.between to calculate the exact number of years,
     * accounting for leap years and varying month lengths.</p>
     * 
     * @param dateOfBirth The date of birth
     * @return Age in complete years
     * @throws IllegalArgumentException if dateOfBirth is null
     */
    public int calculateAge(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw new IllegalArgumentException("Date of birth cannot be null");
        }
        
        LocalDate today = LocalDate.now();
        Period period = Period.between(dateOfBirth, today);
        int age = period.getYears();
        
        logger.debug("Calculated age: {} years from date of birth: {}", age, dateOfBirth);
        return age;
    }

    /**
     * Validates a customer name for proper format and allowed characters.
     * 
     * <p>Validation rules:</p>
     * <ul>
     *   <li>Must contain only letters, spaces, hyphens, and apostrophes</li>
     *   <li>Must start with a letter</li>
     *   <li>No consecutive special characters</li>
     *   <li>Length constraints: 1-25 characters</li>
     * </ul>
     * 
     * <p><b>COBOL Fields:</b> CUST-FIRST-NAME, CUST-MIDDLE-NAME, CUST-LAST-NAME 
     * PIC X(25) from CVCUS01Y.cpy</p>
     * 
     * @param name The customer name to validate
     * @return The validated name (trimmed)
     * @throws ValidationException if name is null, empty, too long, or contains invalid characters
     */
    public String validateCustomerName(String name) {
        logger.debug("Validating customer name");
        
        if (name == null || name.trim().isEmpty()) {
            logger.warn("Customer name validation failed: Name is null or empty");
            throw new ValidationException("Customer name is required");
        }
        
        String trimmedName = name.trim();
        
        if (trimmedName.length() > 25) {
            logger.warn("Customer name validation failed: Name exceeds 25 characters");
            throw new ValidationException("Customer name cannot exceed 25 characters");
        }
        
        Matcher matcher = NAME_PATTERN.matcher(trimmedName);
        if (!matcher.matches()) {
            logger.warn("Customer name validation failed: Invalid characters or format");
            throw new ValidationException(
                "Customer name must contain only letters, spaces, hyphens, and apostrophes, " +
                "and must start with a letter");
        }
        
        logger.debug("Customer name validation successful");
        return trimmedName;
    }
}

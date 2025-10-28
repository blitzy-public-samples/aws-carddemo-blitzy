package com.carddemo.service;

import com.carddemo.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive JUnit 5 unit test for ValidationService.
 * <p>
 * Tests field-level validation rules extracted from COBOL programs including:
 * <ul>
 *   <li>Alphanumeric validation (PIC X validation)</li>
 *   <li>Numeric validation (PIC 9 validation)</li>
 *   <li>Date format validation (YYYY-MM-DD)</li>
 *   <li>Phone number format validation</li>
 *   <li>Mandatory field validation</li>
 *   <li>US state and country code validation</li>
 *   <li>Credit card number validation (Luhn algorithm)</li>
 * </ul>
 * </p>
 * 
 * <h2>COBOL to Java Test Conversion</h2>
 * <p>
 * Validates that Java validation logic exactly matches COBOL field validation patterns from:
 * <ul>
 *   <li>COACTUPC.cbl - Account update validation (lines 52-200)</li>
 *   <li>COCRDUPC.cbl - Card update validation (lines 57-99)</li>
 *   <li>COTRN02C.cbl - Transaction entry validation</li>
 * </ul>
 * </p>
 * 
 * <h3>BMS Map Attribute Validation Coverage:</h3>
 * <ul>
 *   <li>NUM attribute → validateNumericField tests</li>
 *   <li>ASKIP/PROT attributes → mandatory field tests</li>
 *   <li>Field length (PIC X(n)) → validateFieldLength tests</li>
 * </ul>
 * 
 * <h3>COBOL Validation Flag Coverage:</h3>
 * <ul>
 *   <li>FLG-ALPHA-NOT-OK → testValidateAlphanumericField*</li>
 *   <li>FLG-MANDATORY-NOT-OK → testMandatoryField*</li>
 *   <li>FLG-ALPHNANUM-NOT-OK → testValidateAlphanumericField*</li>
 *   <li>FLG-SIGNED-NUMBER-NOT-OK → testValidateAmount*, testValidateNumericField*</li>
 *   <li>FLG-CARDFILTER-NOT-OK → testValidateCreditCardNumber*</li>
 *   <li>FLG-ACCTFILTER-NOT-OK → testValidateAccountId*</li>
 *   <li>WS-EDIT-US-PHONE-NUM → testValidPhoneNumber*, testInvalidPhoneFormat*</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2025-10-25
 * @see ValidationService
 * @see ValidationException
 */
@ExtendWith(MockitoExtension.class)
class ValidationServiceTest {

    /**
     * ValidationService instance with mocked dependencies injected.
     * Note: ValidationService is stateless with no dependencies, so no @Mock needed.
     */
    @InjectMocks
    private ValidationService validationService;

    // ========================================================================
    // Alphanumeric Field Validation Tests (PIC X validation)
    // Tests COBOL: FLG-ALPHNANUM-NOT-OK from COACTUPC.cbl line 68-71
    // ========================================================================

    /**
     * Test that alphanumeric field with valid characters passes validation.
     * <p>
     * COBOL equivalent: Field passes FLG-ALPHNANUM-ISVALID check
     * </p>
     */
    @Test
    void testValidateAlphanumericFieldValid() {
        // Valid alphanumeric strings with letters, numbers, and spaces
        assertDoesNotThrow(() -> validationService.validateAlphanumericField("John Doe", "name"));
        assertDoesNotThrow(() -> validationService.validateAlphanumericField("Account123", "accountName"));
        assertDoesNotThrow(() -> validationService.validateAlphanumericField("ABC 123 XYZ", "description"));
    }

    /**
     * Test that alphanumeric field with special characters fails validation.
     * <p>
     * COBOL equivalent: Field triggers FLG-ALPHNANUM-NOT-OK
     * </p>
     */
    @Test
    void testValidateAlphanumericFieldInvalid() {
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateAlphanumericField("John@Doe", "name"));
        assertEquals("name", exception.getFieldName());
        assertTrue(exception.getMessage().contains("invalid characters"));
        
        // Test with other special characters
        assertThrows(ValidationException.class,
            () -> validationService.validateAlphanumericField("Test#Field", "testField"));
        assertThrows(ValidationException.class,
            () -> validationService.validateAlphanumericField("Value$100", "amount"));
    }

    /**
     * Test that blank alphanumeric field fails validation.
     * <p>
     * COBOL equivalent: Field triggers FLG-ALPHNANUM-BLANK
     * </p>
     */
    @Test
    void testValidateAlphanumericFieldBlank() {
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateAlphanumericField("", "name"));
        assertEquals("name", exception.getFieldName());
        assertTrue(exception.getMessage().contains("cannot be empty"));
        
        // Test null field
        assertThrows(ValidationException.class,
            () -> validationService.validateAlphanumericField(null, "name"));
        
        // Test whitespace only
        assertThrows(ValidationException.class,
            () -> validationService.validateAlphanumericField("   ", "name"));
    }

    /**
     * Test alphanumeric field length validation (PIC X(50) constraint).
     * <p>
     * COBOL equivalent: PIC X(50) field length constraint
     * </p>
     */
    @Test
    void testValidateAlphanumericFieldMaxLength() {
        // Test field within max length
        String validField = "A".repeat(50);
        assertDoesNotThrow(() -> validationService.validateFieldLength(validField, 50, "testField"));
        
        // Test field exceeding max length
        String invalidField = "A".repeat(51);
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateFieldLength(invalidField, 50, "testField"));
        assertEquals("testField", exception.getFieldName());
        assertTrue(exception.getMessage().contains("cannot exceed 50 characters"));
    }

    // ========================================================================
    // Numeric Field Validation Tests (PIC 9 validation)
    // Tests COBOL: FLG-SIGNED-NUMBER-NOT-OK from COACTUPC.cbl line 56-59
    // ========================================================================

    /**
     * Test that numeric field with only digits passes validation.
     * <p>
     * COBOL equivalent: Field passes FLG-SIGNED-NUMBER-ISVALID check
     * </p>
     */
    @Test
    void testValidateNumericFieldValid() {
        assertDoesNotThrow(() -> validationService.validateNumericField("12345", "accountId"));
        assertDoesNotThrow(() -> validationService.validateNumericField("999999999", "transactionId"));
        assertDoesNotThrow(() -> validationService.validateNumericField("0", "count"));
    }

    /**
     * Test that numeric field with letters or special characters fails validation.
     * <p>
     * COBOL equivalent: Field triggers FLG-SIGNED-NUMBER-NOT-OK
     * </p>
     */
    @Test
    void testValidateNumericFieldInvalid() {
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateNumericField("12A45", "accountId"));
        assertEquals("accountId", exception.getFieldName());
        assertTrue(exception.getMessage().contains("numeric"));
        
        // Test with special characters
        assertThrows(ValidationException.class,
            () -> validationService.validateNumericField("123.45", "amount"));
        assertThrows(ValidationException.class,
            () -> validationService.validateNumericField("12-34", "id"));
    }

    /**
     * Test negative numeric fields (S9 in COBOL).
     * <p>
     * COBOL equivalent: PIC S9 signed numeric field
     * Note: validateNumericField only validates digits, not sign
     * </p>
     */
    @Test
    void testValidateNumericFieldNegative() {
        // The validateNumericField method validates only digits
        // Signed numbers are handled by BigDecimal amount validation
        assertThrows(ValidationException.class,
            () -> validationService.validateNumericField("-123", "signedNumber"));
    }

    /**
     * Test decimal numeric fields (S9V99 in COBOL).
     * <p>
     * COBOL equivalent: PIC S9(09)V99 COMP-3 packed decimal
     * Decimal validation is handled by validateAmount method
     * </p>
     */
    @Test
    void testValidateNumericFieldDecimal() {
        BigDecimal validAmount = new BigDecimal("1234.56");
        assertDoesNotThrow(() -> validationService.validateAmount(validAmount));
        
        BigDecimal validAmountTwoDecimals = new BigDecimal("9999.99");
        assertDoesNotThrow(() -> validationService.validateAmount(validAmountTwoDecimals));
    }

    // ========================================================================
    // Mandatory Field Validation Tests
    // Tests COBOL: FLG-MANDATORY-NOT-OK from COACTUPC.cbl line 72-75
    // ========================================================================

    /**
     * Test that non-empty mandatory field passes validation.
     * <p>
     * COBOL equivalent: Field passes FLG-MANDATORY-ISVALID check
     * </p>
     */
    @Test
    void testMandatoryFieldPresent() {
        assertDoesNotThrow(() -> validationService.validateMandatoryField("ValidValue", "accountName"));
        assertDoesNotThrow(() -> validationService.validateMandatoryField("X", "status"));
    }

    /**
     * Test that blank mandatory field fails validation.
     * <p>
     * COBOL equivalent: Field triggers FLG-MANDATORY-BLANK
     * </p>
     */
    @Test
    void testMandatoryFieldBlank() {
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateMandatoryField("", "accountName"));
        assertEquals("VAL002", exception.getErrorCode());
        assertEquals("accountName", exception.getFieldName());
        assertTrue(exception.getMessage().contains("Required field"));
    }

    /**
     * Test that null mandatory field fails validation.
     * <p>
     * COBOL equivalent: Field triggers FLG-MANDATORY-NOT-OK
     * </p>
     */
    @Test
    void testMandatoryFieldNull() {
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateMandatoryField(null, "accountName"));
        assertEquals("VAL002", exception.getErrorCode());
        assertEquals("accountName", exception.getFieldName());
        assertTrue(exception.getMessage().contains("missing or empty"));
    }

    /**
     * Test that mandatory field with only spaces fails validation.
     * <p>
     * COBOL equivalent: Field triggers FLG-MANDATORY-BLANK after trimming
     * </p>
     */
    @Test
    void testMandatoryFieldSpaces() {
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateMandatoryField("   ", "accountName"));
        assertEquals("accountName", exception.getFieldName());
        assertTrue(exception.getMessage().contains("missing or empty"));
    }

    // ========================================================================
    // Date Validation Tests (YYYY-MM-DD format from COBOL PIC X(10))
    // Tests COBOL: CSUTLDWY copybook date validation
    // ========================================================================

    /**
     * Test valid date format passes validation.
     * <p>
     * COBOL equivalent: Date passes CSUTLDWY validation
     * </p>
     */
    @Test
    void testValidDateFormat() {
        LocalDate validDate = LocalDate.of(2025, 10, 24);
        assertDoesNotThrow(() -> validationService.validateDate(validDate));
        
        LocalDate today = LocalDate.now();
        assertDoesNotThrow(() -> validationService.validateDate(today));
    }

    /**
     * Test invalid date values fail validation.
     * <p>
     * COBOL equivalent: Invalid date triggers date validation error
     * </p>
     */
    @Test
    void testInvalidDateValues() {
        // LocalDate constructor validates dates, so impossible dates throw DateTimeException
        assertThrows(java.time.DateTimeException.class,
            () -> LocalDate.of(2025, 2, 30)); // February 30th doesn't exist
        
        assertThrows(java.time.DateTimeException.class,
            () -> LocalDate.of(2025, 13, 1)); // Month 13 doesn't exist
    }

    /**
     * Test leap year date validation.
     * <p>
     * COBOL equivalent: Leap year validation in CSUTLDWY
     * </p>
     */
    @Test
    void testDateLeapYear() {
        // 2024 is a leap year
        LocalDate leapYearDate = LocalDate.of(2024, 2, 29);
        assertDoesNotThrow(() -> validationService.validateDate(leapYearDate));
        
        // 2025 is not a leap year
        assertThrows(java.time.DateTimeException.class,
            () -> LocalDate.of(2025, 2, 29));
    }

    /**
     * Test date range validation (null check).
     * <p>
     * COBOL equivalent: Date field mandatory check
     * </p>
     */
    @Test
    void testDateRangeValidation() {
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateDate(null));
        assertTrue(exception.getMessage().contains("Date cannot be empty"));
    }

    // ========================================================================
    // Phone Number Validation Tests (US format from COACTUPC)
    // Tests COBOL: WS-EDIT-US-PHONE-NUM from COACTUPC.cbl line 82-115
    // ========================================================================

    /**
     * Test valid phone number format passes validation.
     * <p>
     * COBOL equivalent: Phone passes WS-EDIT-US-PHONE-IS-VALID check
     * </p>
     */
    @Test
    void testValidPhoneNumber() {
        // Phone validation accepts 10-15 digits after removing formatting
        assertDoesNotThrow(() -> validationService.validatePhoneNumber("(555)123-4567"));
        assertDoesNotThrow(() -> validationService.validatePhoneNumber("5551234567"));
        assertDoesNotThrow(() -> validationService.validatePhoneNumber("555-123-4567"));
    }

    /**
     * Test invalid phone format fails validation.
     * <p>
     * COBOL equivalent: Phone triggers WS-EDIT-US-PHONE-IS-INVALID
     * </p>
     */
    @Test
    void testInvalidPhoneFormat() {
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validatePhoneNumber("123")); // Too short
        assertEquals("phoneNumber", exception.getFieldName());
        assertTrue(exception.getMessage().contains("10 and 15 digits"));
        
        // Test with letters
        assertThrows(ValidationException.class,
            () -> validationService.validatePhoneNumber("555-ABC-1234"));
    }

    /**
     * Test phone number length validation (exactly 15 characters per PIC X(15)).
     * <p>
     * COBOL equivalent: PIC X(15) field length constraint
     * </p>
     */
    @Test
    void testPhoneNumberLength() {
        // Valid 10-digit phone
        assertDoesNotThrow(() -> validationService.validatePhoneNumber("(555)123-4567"));
        
        // Valid 15-digit international phone
        assertDoesNotThrow(() -> validationService.validatePhoneNumber("123456789012345"));
        
        // Invalid - too many digits
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validatePhoneNumber("12345678901234567"));
        assertTrue(exception.getMessage().contains("10 and 15 digits"));
    }

    // ========================================================================
    // US State Code Validation Tests (PIC X(02))
    // ========================================================================

    /**
     * Test valid 2-letter state code passes validation.
     * <p>
     * COBOL equivalent: PIC X(02) state code validation
     * </p>
     */
    @Test
    void testValidStateCode() {
        assertDoesNotThrow(() -> validationService.validateStateCode("NY"));
        assertDoesNotThrow(() -> validationService.validateStateCode("CA"));
        assertDoesNotThrow(() -> validationService.validateStateCode("TX"));
    }

    /**
     * Test invalid state code fails validation.
     * <p>
     * COBOL equivalent: Invalid state code pattern
     * </p>
     */
    @Test
    void testInvalidStateCode() {
        // Note: Current implementation only validates format (2 uppercase letters)
        // "ZZ" passes format validation even though it's not a real state code
        // To validate against actual state list, would need reference data check
        
        // Test wrong length
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateStateCode("NYS"));
        assertEquals("VAL003", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("exactly 2 uppercase letters"));
        
        // Test lowercase
        assertThrows(ValidationException.class,
            () -> validationService.validateStateCode("ny"));
    }

    /**
     * Test state code uppercase conversion requirement.
     * <p>
     * COBOL equivalent: State codes stored as uppercase PIC X(02)
     * </p>
     */
    @Test
    void testStateCodeUpperCase() {
        // Lowercase input should fail validation (requires uppercase)
        assertThrows(ValidationException.class,
            () -> validationService.validateStateCode("ny"));
    }

    // ========================================================================
    // Country Code Validation Tests (PIC X(03))
    // ========================================================================

    /**
     * Test valid 3-letter country code passes validation.
     * <p>
     * COBOL equivalent: PIC X(03) country code validation (ISO 3166-1 alpha-3)
     * </p>
     */
    @Test
    void testValidCountryCode() {
        assertDoesNotThrow(() -> validationService.validateCountryCode("USA"));
        assertDoesNotThrow(() -> validationService.validateCountryCode("CAN"));
        assertDoesNotThrow(() -> validationService.validateCountryCode("GBR"));
    }

    /**
     * Test invalid country code fails validation.
     * <p>
     * COBOL equivalent: Invalid country code pattern
     * </p>
     */
    @Test
    void testInvalidCountryCode() {
        // Test wrong length
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateCountryCode("US"));
        assertTrue(exception.getMessage().contains("exactly 3 uppercase letters"));
        
        // Test lowercase
        assertThrows(ValidationException.class,
            () -> validationService.validateCountryCode("usa"));
        
        // Test with numbers
        assertThrows(ValidationException.class,
            () -> validationService.validateCountryCode("U2A"));
    }

    // ========================================================================
    // Credit Card Number Validation Tests (Luhn algorithm)
    // Tests COBOL: FLG-CARDFILTER-NOT-OK from COCRDUPC.cbl line 62
    // ========================================================================

    /**
     * Test valid 16-digit card number passes Luhn checksum validation.
     * <p>
     * COBOL equivalent: Card passes FLG-CARDFILTER-ISVALID check
     * </p>
     */
    @Test
    void testValidCreditCardNumber() {
        // Valid test card numbers (pass Luhn algorithm and are exactly 16 digits)
        assertDoesNotThrow(() -> validationService.validateCardNumber("4532015112830366")); // Visa (16 digits)
        assertDoesNotThrow(() -> validationService.validateCardNumber("5425233430109903")); // Mastercard (16 digits)
        // Note: Amex cards are 15 digits, but ValidationService requires exactly 16 digits per COBOL PIC X(16)
    }

    /**
     * Test invalid card number fails Luhn checksum validation.
     * <p>
     * COBOL equivalent: Card triggers FLG-CARDFILTER-NOT-OK
     * </p>
     */
    @Test
    void testInvalidCreditCardNumber() {
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateCardNumber("1234567890123456")); // Invalid Luhn
        assertEquals("VAL006", exception.getErrorCode());
        assertEquals("cardNumber", exception.getFieldName());
        assertTrue(exception.getMessage().contains("checksum validation failed"));
    }

    /**
     * Test card number length validation (must be 16 digits per PIC X(16)).
     * <p>
     * COBOL equivalent: PIC X(16) field length constraint
     * </p>
     */
    @Test
    void testCreditCardLength() {
        // Too short
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateCardNumber("123456789012345"));
        assertTrue(exception.getMessage().contains("16 digits"));
        
        // Too long
        assertThrows(ValidationException.class,
            () -> validationService.validateCardNumber("12345678901234567"));
        
        // Empty
        assertThrows(ValidationException.class,
            () -> validationService.validateCardNumber(""));
        
        // Null
        assertThrows(ValidationException.class,
            () -> validationService.validateCardNumber(null));
    }

    // ========================================================================
    // COBOL Pattern Matching Tests (88-level conditions)
    // ========================================================================

    /**
     * Test Yes/No validation (Y or N values from COBOL 88-level conditions).
     * <p>
     * COBOL equivalent: 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N' from COACTUPC.cbl line 78
     * Note: This would require a dedicated validateYesNo method in ValidationService
     * </p>
     */
    @Test
    void testYesNoValidation() {
        // ValidateAlphanumericField can be used for basic yes/no validation
        assertDoesNotThrow(() -> validationService.validateAlphanumericField("Y", "activeFlag"));
        assertDoesNotThrow(() -> validationService.validateAlphanumericField("N", "activeFlag"));
    }

    /**
     * Test 11-digit account ID validation (PIC 9(11)).
     * <p>
     * COBOL equivalent: FLG-ACCTFILTER-NOT-OK from COACTUPC.cbl line 183
     * </p>
     */
    @Test
    void testAccountIdValidation() {
        // Valid 11-digit account ID
        Long validAccountId = 12345678901L;
        assertDoesNotThrow(() -> validationService.validateAccountId(validAccountId));
        
        // Invalid - null
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateAccountId(null));
        assertEquals("VAL007", exception.getErrorCode());
        
        // Invalid - zero
        assertThrows(ValidationException.class,
            () -> validationService.validateAccountId(0L));
        
        // Invalid - negative
        assertThrows(ValidationException.class,
            () -> validationService.validateAccountId(-123L));
        
        // Invalid - wrong length (10 digits)
        assertThrows(ValidationException.class,
            () -> validationService.validateAccountId(1234567890L));
    }

    /**
     * Test monetary amount validation with 2 decimal places.
     * <p>
     * COBOL equivalent: PIC S9(09)V99 COMP-3 packed decimal validation
     * </p>
     */
    @Test
    void testAmountValidation() {
        // Valid amounts with 2 decimal places
        assertDoesNotThrow(() -> validationService.validateAmount(new BigDecimal("1234.56")));
        assertDoesNotThrow(() -> validationService.validateAmount(new BigDecimal("9999999.99")));
        assertDoesNotThrow(() -> validationService.validateAmount(new BigDecimal("0.01")));
        
        // Invalid - null
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateAmount(null));
        assertEquals("VAL005", exception.getErrorCode());
        
        // Invalid - zero
        assertThrows(ValidationException.class,
            () -> validationService.validateAmount(BigDecimal.ZERO));
        
        // Invalid - negative
        assertThrows(ValidationException.class,
            () -> validationService.validateAmount(new BigDecimal("-100.00")));
        
        // Invalid - too many decimal places
        assertThrows(ValidationException.class,
            () -> validationService.validateAmount(new BigDecimal("100.123")));
    }

    // ========================================================================
    // Error Message Tests
    // Tests that error messages match COBOL error text per Section 0.7.2
    // ========================================================================

    /**
     * Test that validation error messages match COBOL error text.
     * <p>
     * Per Agent Action Plan Section 0.7.2: Error messages must be preserved
     * exactly from COBOL error message copybooks
     * </p>
     */
    @Test
    void testValidationErrorMessages() {
        // Card number error message
        ValidationException cardException = assertThrows(ValidationException.class,
            () -> validationService.validateCardNumber("123"));
        assertEquals("VAL006", cardException.getErrorCode());
        assertTrue(cardException.getMessage().contains("Invalid card number format"));
        
        // Account ID error message
        ValidationException accountException = assertThrows(ValidationException.class,
            () -> validationService.validateAccountId(null));
        assertEquals("VAL007", accountException.getErrorCode());
        assertTrue(accountException.getMessage().contains("Account ID cannot be empty"));
        
        // Mandatory field error message
        ValidationException mandatoryException = assertThrows(ValidationException.class,
            () -> validationService.validateMandatoryField(null, "testField"));
        assertEquals("VAL002", mandatoryException.getErrorCode());
        assertTrue(mandatoryException.getMessage().contains("Required field"));
    }

    /**
     * Test accumulating multiple validation errors.
     * <p>
     * COBOL equivalent: Multiple validation flags set to NOT-OK
     * Note: Current implementation throws on first error
     * </p>
     */
    @Test
    void testMultipleValidationErrors() {
        // Test that each validation throws immediately
        assertThrows(ValidationException.class,
            () -> validationService.validateCardNumber("invalid"));
        
        assertThrows(ValidationException.class,
            () -> validationService.validateAccountId(-1L));
        
        assertThrows(ValidationException.class,
            () -> validationService.validateMandatoryField("", "field"));
    }

    // ========================================================================
    // Additional Validation Tests
    // ========================================================================

    /**
     * Test user ID validation (8 characters alphanumeric).
     * <p>
     * COBOL equivalent: COSGN00C.cbl line 118-122 user ID validation
     * </p>
     */
    @Test
    void testValidateUserId() {
        // Valid - 8-character user ID (COBOL PIC X(08) maximum length)
        assertDoesNotThrow(() -> validationService.validateUserId("USER1234"));
        assertDoesNotThrow(() -> validationService.validateUserId("ABCD5678"));
        
        // Valid - shorter user IDs (COBOL PIC X(08) allows up to 8 characters)
        // In COBOL, shorter values are right-padded with spaces, e.g. "USER1" becomes "USER1   "
        assertDoesNotThrow(() -> validationService.validateUserId("USER1"));
        assertDoesNotThrow(() -> validationService.validateUserId("USER001")); // 7 chars - matches test data
        assertDoesNotThrow(() -> validationService.validateUserId("A")); // 1 char - minimum valid length
        
        // Invalid - null
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateUserId(null));
        assertEquals("VAL003", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Please enter User ID"));
        
        // Invalid - exceeds maximum length (more than 8 characters)
        assertThrows(ValidationException.class,
            () -> validationService.validateUserId("USER12345")); // 9 chars - too long
        
        // Invalid - special characters
        assertThrows(ValidationException.class,
            () -> validationService.validateUserId("USER@123"));
    }

    /**
     * Test password validation (minimum 8 characters).
     * <p>
     * COBOL equivalent: COSGN00C.cbl line 123-127 password validation
     * </p>
     */
    @Test
    void testValidatePassword() {
        // Valid password (8+ characters)
        assertDoesNotThrow(() -> validationService.validatePassword("Password123"));
        assertDoesNotThrow(() -> validationService.validatePassword("12345678"));
        
        // Invalid - null
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validatePassword(null));
        assertTrue(exception.getMessage().contains("Please enter Password"));
        
        // Invalid - too short
        assertThrows(ValidationException.class,
            () -> validationService.validatePassword("Pass1"));
    }

    /**
     * Test transaction type validation.
     * <p>
     * COBOL equivalent: CVTRA03Y.cpy transaction type code validation
     * </p>
     * <p>
     * Valid types per COBOL copybook: 01-08
     * 01=Purchase, 02=Cash Advance, 03=Balance Transfer, 04=Payment, 
     * 05=Fee, 06=Interest, 07=Credit Adjustment, 08=Debit Adjustment
     * </p>
     */
    @Test
    void testValidateTransactionType() {
        // Valid 2-character type codes (01-08 per COBOL copybook)
        assertDoesNotThrow(() -> validationService.validateTransactionType("01"));
        assertDoesNotThrow(() -> validationService.validateTransactionType("02"));
        assertDoesNotThrow(() -> validationService.validateTransactionType("08"));
        
        // Invalid - null
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateTransactionType(null));
        assertEquals("VAL003", exception.getErrorCode());
        
        // Invalid - wrong length
        assertThrows(ValidationException.class,
            () -> validationService.validateTransactionType("1"));
        assertThrows(ValidationException.class,
            () -> validationService.validateTransactionType("123"));
        
        // Invalid - out of valid range (only 01-08 are valid)
        assertThrows(ValidationException.class,
            () -> validationService.validateTransactionType("99"));
    }

    /**
     * Test transaction category validation.
     * <p>
     * COBOL equivalent: CVTRA04Y.cpy transaction category code validation
     * </p>
     * <p>
     * Note: Category 0 is valid per COBOL logic and is used for payment transactions
     * and cash advances where no specific category applies (see TransactionControllerTest,
     * TransactionIntegrationTest, and ReportIntegrationTest for usage examples).
     * </p>
     */
    @Test
    void testValidateTransactionCategory() {
        // Valid positive categories
        assertDoesNotThrow(() -> validationService.validateTransactionCategory(1));
        assertDoesNotThrow(() -> validationService.validateTransactionCategory(999));
        
        // Valid - zero (used for payment transactions with no specific category)
        assertDoesNotThrow(() -> validationService.validateTransactionCategory(0));
        
        // Invalid - null
        ValidationException exception = assertThrows(ValidationException.class,
            () -> validationService.validateTransactionCategory(null));
        assertEquals("VAL003", exception.getErrorCode());
        
        // Invalid - negative
        assertThrows(ValidationException.class,
            () -> validationService.validateTransactionCategory(-1));
        assertThrows(ValidationException.class,
            () -> validationService.validateTransactionCategory(-100));
    }

    /**
     * Test SSN validation (9 digits with invalid pattern checks).
     * <p>
     * COBOL equivalent: WS-EDIT-US-SSN from COACTUPC.cbl line 117-146
     * </p>
     */
    @Test
    void testValidateSSN() {
        // Valid SSN
        assertDoesNotThrow(() -> validationService.validateSSN("123456789"));
        assertDoesNotThrow(() -> validationService.validateSSN("123-45-6789")); // With formatting
        
        // Invalid - null
        assertThrows(ValidationException.class,
            () -> validationService.validateSSN(null));
        
        // Invalid - wrong length
        assertThrows(ValidationException.class,
            () -> validationService.validateSSN("12345678"));
        
        // Invalid - starts with 000 (INVALID-SSN-PART1 VALUE 0)
        assertThrows(ValidationException.class,
            () -> validationService.validateSSN("000123456"));
        
        // Invalid - starts with 666 (INVALID-SSN-PART1 VALUE 666)
        assertThrows(ValidationException.class,
            () -> validationService.validateSSN("666123456"));
        
        // Invalid - starts with 900-999 (INVALID-SSN-PART1 900 THRU 999)
        assertThrows(ValidationException.class,
            () -> validationService.validateSSN("900123456"));
        assertThrows(ValidationException.class,
            () -> validationService.validateSSN("999123456"));
    }

    /**
     * Test credit limit validation.
     * <p>
     * COBOL equivalent: WS-EDIT-CREDIT-LIMIT from COACTUPC.cbl line 196-199
     * </p>
     */
    @Test
    void testValidateCreditLimit() {
        // Valid credit limit
        assertDoesNotThrow(() -> validationService.validateCreditLimit(new BigDecimal("10000.00")));
        assertDoesNotThrow(() -> validationService.validateCreditLimit(new BigDecimal("50000.00")));
        
        // Invalid - null
        assertThrows(ValidationException.class,
            () -> validationService.validateCreditLimit(null));
        
        // Invalid - zero or negative
        assertThrows(ValidationException.class,
            () -> validationService.validateCreditLimit(BigDecimal.ZERO));
        assertThrows(ValidationException.class,
            () -> validationService.validateCreditLimit(new BigDecimal("-1000.00")));
        
        // Invalid - exceeds maximum
        assertThrows(ValidationException.class,
            () -> validationService.validateCreditLimit(new BigDecimal("100000.00")));
    }

    /**
     * Test email validation.
     * <p>
     * Note: Email validation is not in COBOL but added for modern web requirements
     * </p>
     */
    @Test
    void testValidateEmail() {
        // Valid email
        assertDoesNotThrow(() -> validationService.validateEmail("user@example.com"));
        assertDoesNotThrow(() -> validationService.validateEmail("test.user@domain.co.uk"));
        
        // Invalid - null
        assertThrows(ValidationException.class,
            () -> validationService.validateEmail(null));
        
        // Invalid - wrong format
        assertThrows(ValidationException.class,
            () -> validationService.validateEmail("invalid-email"));
        assertThrows(ValidationException.class,
            () -> validationService.validateEmail("@example.com"));
        assertThrows(ValidationException.class,
            () -> validationService.validateEmail("user@"));
    }
}


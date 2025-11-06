/*
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

package com.carddemo.util;

/**
 * String utility class providing thread-safe stateless helper methods for common string
 * manipulation operations matching COBOL string processing semantics used throughout the
 * CardDemo application.
 * 
 * <p>This utility class provides COBOL-equivalent string operations including:
 * <ul>
 *   <li>Padding operations (left, right, center) matching COBOL MOVE and JUSTIFIED clauses</li>
 *   <li>Fixed-length string conversion for COBOL PIC X(n) field equivalence</li>
 *   <li>String validation for COBOL PIC clause validation (PIC 9, PIC X, PIC A)</li>
 *   <li>Null-safe operations mapping COBOL SPACES and LOW-VALUES</li>
 *   <li>Character replacement matching COBOL INSPECT REPLACING</li>
 *   <li>String truncation with COBOL reference modification semantics</li>
 * </ul>
 * 
 * <p>All methods are public static for utility access pattern, handle null inputs gracefully,
 * and validate parameters throwing IllegalArgumentException for invalid inputs.
 * 
 * <p><b>COBOL Semantics Mapping:</b>
 * <ul>
 *   <li>Java null → COBOL LOW-VALUES</li>
 *   <li>Java empty string ("") → COBOL SPACES</li>
 *   <li>Fixed-length conversion → COBOL PIC X(n) with automatic padding/truncation</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
public class StringUtils {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private StringUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Pads a string on the left side to the specified length with the given padding character.
     * 
     * <p><b>COBOL Equivalent:</b> MOVE value TO field JUSTIFIED RIGHT
     * 
     * <p>If the string is null, it is treated as an empty string. If the string is already
     * longer than or equal to the specified length, it is returned unchanged (not truncated).
     * 
     * @param str the string to pad (may be null)
     * @param length the target length after padding
     * @param padChar the character to use for padding
     * @return the left-padded string, never null
     * @throws IllegalArgumentException if length is negative
     * 
     * @example
     * <pre>
     * leftPad("123", 5, '0')     returns "00123"
     * leftPad("USER", 8, ' ')    returns "    USER"
     * leftPad(null, 3, 'X')      returns "XXX"
     * leftPad("TOOLONG", 3, '0') returns "TOOLONG" (not truncated)
     * </pre>
     */
    public static String leftPad(String str, int length, char padChar) {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length);
        }
        
        if (str == null) {
            str = "";
        }
        
        if (str.length() >= length) {
            return str;
        }
        
        StringBuilder sb = new StringBuilder(length);
        int padCount = length - str.length();
        for (int i = 0; i < padCount; i++) {
            sb.append(padChar);
        }
        sb.append(str);
        
        return sb.toString();
    }

    /**
     * Pads a string on the right side to the specified length with the given padding character.
     * 
     * <p><b>COBOL Equivalent:</b> MOVE value TO PIC X(n) field with automatic space padding
     * 
     * <p>If the string is null, it is treated as an empty string. If the string is already
     * longer than or equal to the specified length, it is returned unchanged (not truncated).
     * This is the most common padding operation in COBOL mainframe applications.
     * 
     * @param str the string to pad (may be null)
     * @param length the target length after padding
     * @param padChar the character to use for padding
     * @return the right-padded string, never null
     * @throws IllegalArgumentException if length is negative
     * 
     * @example
     * <pre>
     * rightPad("USER", 8, ' ')    returns "USER    "
     * rightPad("ACCT", 11, '0')   returns "ACCT0000000"
     * rightPad(null, 5, 'X')      returns "XXXXX"
     * rightPad("TOOLONG", 3, ' ') returns "TOOLONG" (not truncated)
     * </pre>
     */
    public static String rightPad(String str, int length, char padChar) {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length);
        }
        
        if (str == null) {
            str = "";
        }
        
        if (str.length() >= length) {
            return str;
        }
        
        StringBuilder sb = new StringBuilder(length);
        sb.append(str);
        int padCount = length - str.length();
        for (int i = 0; i < padCount; i++) {
            sb.append(padChar);
        }
        
        return sb.toString();
    }

    /**
     * Centers a string within the specified length by padding equally on both sides.
     * 
     * <p><b>COBOL Equivalent:</b> Custom logic for centered field display
     * 
     * <p>If the string is null, it is treated as an empty string. If an odd number of pad
     * characters is needed, the extra character is added to the right side. If the string
     * is already longer than or equal to the specified length, it is returned unchanged.
     * 
     * @param str the string to center (may be null)
     * @param length the target length after padding
     * @param padChar the character to use for padding
     * @return the centered string, never null
     * @throws IllegalArgumentException if length is negative
     * 
     * @example
     * <pre>
     * centerPad("ABC", 7, ' ')     returns "  ABC  "
     * centerPad("TEST", 8, '-')    returns "--TEST--"
     * centerPad("X", 5, '*')       returns "**X**"
     * centerPad(null, 4, ' ')      returns "    "
     * </pre>
     */
    public static String centerPad(String str, int length, char padChar) {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length);
        }
        
        if (str == null) {
            str = "";
        }
        
        if (str.length() >= length) {
            return str;
        }
        
        int totalPadding = length - str.length();
        int leftPadding = totalPadding / 2;
        int rightPadding = totalPadding - leftPadding;
        
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < leftPadding; i++) {
            sb.append(padChar);
        }
        sb.append(str);
        for (int i = 0; i < rightPadding; i++) {
            sb.append(padChar);
        }
        
        return sb.toString();
    }

    /**
     * Truncates a string to the specified maximum length.
     * 
     * <p><b>COBOL Equivalent:</b> Reference modification str(1:maxLength)
     * 
     * <p>If the string is null, empty string is returned. If the string is shorter than
     * or equal to maxLength, it is returned unchanged.
     * 
     * @param str the string to truncate (may be null)
     * @param maxLength the maximum length
     * @return the truncated string, never null
     * @throws IllegalArgumentException if maxLength is negative
     * 
     * @example
     * <pre>
     * truncate("LONGTEXT", 4)      returns "LONG"
     * truncate("SHORT", 10)        returns "SHORT"
     * truncate(null, 5)            returns ""
     * truncate("", 3)              returns ""
     * </pre>
     */
    public static String truncate(String str, int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("Max length cannot be negative: " + maxLength);
        }
        
        if (str == null || str.isEmpty()) {
            return "";
        }
        
        if (str.length() <= maxLength) {
            return str;
        }
        
        return str.substring(0, maxLength);
    }

    /**
     * Truncates a string to the specified maximum length and adds an ellipsis suffix.
     * 
     * <p>This method is useful for display fields where truncated content should be indicated.
     * If maxLength is less than 3, standard truncation is performed without ellipsis.
     * The ellipsis counts toward the total length.
     * 
     * @param str the string to truncate (may be null)
     * @param maxLength the maximum length including ellipsis
     * @return the truncated string with ellipsis if truncated, never null
     * @throws IllegalArgumentException if maxLength is negative
     * 
     * @example
     * <pre>
     * truncateWithEllipsis("Very Long Text", 10)  returns "Very Lo..."
     * truncateWithEllipsis("Short", 10)           returns "Short"
     * truncateWithEllipsis("Test", 2)             returns "Te"
     * truncateWithEllipsis(null, 5)               returns ""
     * </pre>
     */
    public static String truncateWithEllipsis(String str, int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("Max length cannot be negative: " + maxLength);
        }
        
        if (str == null || str.isEmpty()) {
            return "";
        }
        
        if (str.length() <= maxLength) {
            return str;
        }
        
        if (maxLength < 3) {
            return str.substring(0, maxLength);
        }
        
        return str.substring(0, maxLength - 3) + "...";
    }

    /**
     * Converts a string to a fixed-length string by padding with spaces or truncating.
     * 
     * <p><b>COBOL Equivalent:</b> MOVE value TO PIC X(n) field - exact behavior match
     * 
     * <p>This is the most critical method for maintaining COBOL PIC X(n) field semantics:
     * <ul>
     *   <li>If string is shorter than length: right-pad with spaces to reach length</li>
     *   <li>If string is longer than length: truncate to exact length</li>
     *   <li>If string is exactly length: return as-is</li>
     *   <li>If string is null: return string of spaces with specified length</li>
     * </ul>
     * 
     * @param str the string to convert (may be null)
     * @param length the exact target length
     * @return a string of exactly the specified length, never null
     * @throws IllegalArgumentException if length is negative
     * 
     * @example
     * <pre>
     * toFixedLength("USER", 8)       returns "USER    " (8 chars)
     * toFixedLength("VERYLONGNAME", 8) returns "VERYLONG" (8 chars)
     * toFixedLength(null, 5)         returns "     " (5 spaces)
     * toFixedLength("TEST", 4)       returns "TEST" (4 chars)
     * 
     * // COBOL: 05 USER-ID PIC X(8).
     * // Java:  String userId = toFixedLength(value, 8);
     * </pre>
     */
    public static String toFixedLength(String str, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length);
        }
        
        if (str == null) {
            str = "";
        }
        
        if (str.length() == length) {
            return str;
        } else if (str.length() < length) {
            return rightPad(str, length, ' ');
        } else {
            return str.substring(0, length);
        }
    }

    /**
     * Trims whitespace from a string and converts empty result to null.
     * 
     * <p><b>COBOL Equivalent:</b> Converts COBOL SPACES to LOW-VALUES
     * 
     * <p>This method is useful for cleaning user input where blank fields should be
     * treated as null (LOW-VALUES in COBOL terminology).
     * 
     * @param str the string to trim (may be null)
     * @return the trimmed string, or null if the result would be empty
     * 
     * @example
     * <pre>
     * trimToNull("  text  ")  returns "text"
     * trimToNull("   ")       returns null
     * trimToNull("")          returns null
     * trimToNull(null)        returns null
     * </pre>
     */
    public static String trimToNull(String str) {
        if (str == null) {
            return null;
        }
        
        String trimmed = str.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Converts a null string to an empty string.
     * 
     * <p><b>COBOL Equivalent:</b> Converts COBOL LOW-VALUES to SPACES
     * 
     * <p>This method is useful for ensuring non-null strings for display or concatenation.
     * 
     * @param str the string to convert (may be null)
     * @return the original string if not null, empty string otherwise
     * 
     * @example
     * <pre>
     * nullToEmpty("text")  returns "text"
     * nullToEmpty(null)    returns ""
     * nullToEmpty("")      returns ""
     * </pre>
     */
    public static String nullToEmpty(String str) {
        return str == null ? "" : str;
    }

    /**
     * Validates that a string contains only numeric digits.
     * 
     * <p><b>COBOL Equivalent:</b> PIC 9(n) field validation
     * 
     * <p>Returns true if the string is non-null, non-empty, and contains only digits 0-9.
     * This matches COBOL PIC 9 field validation semantics.
     * 
     * @param str the string to validate (may be null)
     * @return true if string contains only digits, false otherwise
     * 
     * @example
     * <pre>
     * isNumeric("12345")     returns true
     * isNumeric("123.45")    returns false (contains decimal point)
     * isNumeric("123A")      returns false (contains letter)
     * isNumeric("")          returns false
     * isNumeric(null)        returns false
     * isNumeric("0000")      returns true
     * 
     * // COBOL: 05 ACCOUNT-ID PIC 9(11).
     * // Java:  if (isNumeric(accountId)) { ... }
     * </pre>
     */
    public static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Validates that a string contains only alphanumeric characters (letters and digits).
     * 
     * <p><b>COBOL Equivalent:</b> PIC X(n) content validation (alphanumeric subset)
     * 
     * <p>Returns true if the string is non-null, non-empty, and contains only letters (A-Z, a-z)
     * and digits (0-9). No spaces or special characters are allowed.
     * 
     * @param str the string to validate (may be null)
     * @return true if string contains only letters and digits, false otherwise
     * 
     * @example
     * <pre>
     * isAlphanumeric("USER123")   returns true
     * isAlphanumeric("Test")      returns true
     * isAlphanumeric("User Name") returns false (contains space)
     * isAlphanumeric("Test!")     returns false (contains special char)
     * isAlphanumeric("")          returns false
     * isAlphanumeric(null)        returns false
     * 
     * // Used for validating user IDs, card numbers without formatting
     * </pre>
     */
    public static boolean isAlphanumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Validates that a string contains only alphabetic characters.
     * 
     * <p><b>COBOL Equivalent:</b> PIC A(n) field validation
     * 
     * <p>Returns true if the string is non-null, non-empty, and contains only letters (A-Z, a-z).
     * No digits, spaces, or special characters are allowed.
     * 
     * @param str the string to validate (may be null)
     * @return true if string contains only letters, false otherwise
     * 
     * @example
     * <pre>
     * isAlphabetic("ABCD")      returns true
     * isAlphabetic("Test")      returns true
     * isAlphabetic("ABC123")    returns false (contains digits)
     * isAlphabetic("Test Name") returns false (contains space)
     * isAlphabetic("")          returns false
     * isAlphabetic(null)        returns false
     * 
     * // COBOL: 05 LAST-NAME PIC A(25).
     * // Java:  if (isAlphabetic(lastName)) { ... }
     * </pre>
     */
    public static boolean isAlphabetic(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!Character.isLetter(c)) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Replaces all occurrences of a character with another character in the string.
     * 
     * <p><b>COBOL Equivalent:</b> INSPECT string REPLACING ALL oldChar BY newChar
     * 
     * <p>If the string is null, empty string is returned. This method creates a new string
     * with all occurrences replaced.
     * 
     * @param str the string to process (may be null)
     * @param oldChar the character to replace
     * @param newChar the replacement character
     * @return a new string with all occurrences replaced, never null
     * 
     * @example
     * <pre>
     * replaceAll("TEST-DATA", '-', '_')  returns "TEST_DATA"
     * replaceAll("1234", '4', '0')       returns "1230"
     * replaceAll(null, 'A', 'B')         returns ""
     * replaceAll("NO-MATCH", 'X', 'Y')   returns "NO-MATCH"
     * 
     * // COBOL: INSPECT WS-STRING REPLACING ALL '-' BY '_'
     * // Java:  result = replaceAll(wsString, '-', '_');
     * </pre>
     */
    public static String replaceAll(String str, char oldChar, char newChar) {
        if (str == null || str.isEmpty()) {
            return "";
        }
        
        return str.replace(oldChar, newChar);
    }

    /**
     * Compares two strings ignoring case considerations.
     * 
     * <p>This method provides null-safe comparison matching COBOL collating sequence behavior.
     * Both null values are considered equal. A null value is considered less than a non-null value.
     * 
     * @param str1 the first string to compare (may be null)
     * @param str2 the second string to compare (may be null)
     * @return negative if str1 < str2, zero if equal, positive if str1 > str2
     * 
     * @example
     * <pre>
     * compareIgnoreCase("ABC", "abc")     returns 0
     * compareIgnoreCase("ABC", "DEF")     returns negative
     * compareIgnoreCase("XYZ", "ABC")     returns positive
     * compareIgnoreCase(null, null)       returns 0
     * compareIgnoreCase(null, "ABC")      returns negative
     * compareIgnoreCase("ABC", null)      returns positive
     * </pre>
     */
    public static int compareIgnoreCase(String str1, String str2) {
        if (str1 == null && str2 == null) {
            return 0;
        }
        if (str1 == null) {
            return -1;
        }
        if (str2 == null) {
            return 1;
        }
        
        return str1.compareToIgnoreCase(str2);
    }

    /**
     * Masks a card number for secure display, showing only the last 4 digits.
     * 
     * <p>This method formats card numbers for display in CardDetailService and CardListService,
     * masking all but the last 4 digits with asterisks and adding space separators every 4 digits
     * for readability.
     * 
     * <p>The input card number should be 16 digits. If the card number is less than 4 characters,
     * it is fully masked. If null or empty, asterisks are returned.
     * 
     * @param cardNumber the card number to mask (may be null, typically 16 digits)
     * @return the masked card number in format "**** **** **** 1234", never null
     * 
     * @example
     * <pre>
     * maskCardNumber("1234567890123456")  returns "**** **** **** 3456"
     * maskCardNumber("1234")              returns "****"
     * maskCardNumber("123")               returns "***"
     * maskCardNumber(null)                returns "**** **** **** ****"
     * maskCardNumber("")                  returns "**** **** **** ****"
     * 
     * // Used in CardResponse DTO for secure card number display
     * </pre>
     */
    public static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.isEmpty()) {
            return "**** **** **** ****";
        }
        
        // Remove any existing spaces or hyphens
        String cleanNumber = cardNumber.replaceAll("[\\s-]", "");
        
        if (cleanNumber.length() <= 4) {
            // If 4 or fewer digits, mask everything (no point showing "last 4" when there are only 4)
            StringBuilder masked = new StringBuilder();
            for (int i = 0; i < cleanNumber.length(); i++) {
                masked.append('*');
            }
            return masked.toString();
        }
        
        // Standard 16-digit card number
        if (cleanNumber.length() == 16) {
            String lastFour = cleanNumber.substring(12);
            return "**** **** **** " + lastFour;
        }
        
        // Variable length - mask all but last 4
        String lastFour = cleanNumber.substring(cleanNumber.length() - 4);
        int maskLength = cleanNumber.length() - 4;
        StringBuilder masked = new StringBuilder();
        
        for (int i = 0; i < maskLength; i++) {
            masked.append('*');
            if ((i + 1) % 4 == 0 && i < maskLength - 1) {
                masked.append(' ');
            }
        }
        
        if (maskLength > 0 && maskLength % 4 != 0) {
            masked.append(' ');
        }
        
        masked.append(lastFour);
        
        return masked.toString();
    }
}


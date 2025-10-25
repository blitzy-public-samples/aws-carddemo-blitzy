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
 * Utility class for text manipulation and formatting operations.
 * <p>
 * Converted from COBOL string formatting utilities (CSSTRPFY.cpy and common
 * COBOL string manipulation patterns found in COACTUPC.cbl and COACTVWC.cbl).
 * </p>
 * <p>
 * This class provides static methods for:
 * <ul>
 *   <li>Padding strings with spaces or zeros (left/right padding to match COBOL PIC X field behavior)</li>
 *   <li>Trimming COBOL-style trailing spaces while preserving leading spaces</li>
 *   <li>Case conversion (matching COBOL FUNCTION UPPER-CASE/LOWER-CASE)</li>
 *   <li>Field masking for sensitive data like card numbers</li>
 *   <li>String inspection operations (COBOL INSPECT REPLACING ALL logic)</li>
 * </ul>
 * </p>
 * <p>
 * All methods are stateless and thread-safe.
 * </p>
 * 
 * <p><b>COBOL Source Patterns:</b></p>
 * <ul>
 *   <li>COBOL PIC X(n) → Right-padded with spaces (padRight)</li>
 *   <li>COBOL PIC 9(n) → Left-padded with zeros (formatNumeric)</li>
 *   <li>COBOL MOVE operation → Automatic right-padding/left-truncation (padRight + trimCobolStyle)</li>
 *   <li>COBOL INSPECT REPLACING ALL → Character replacement (replaceAll)</li>
 *   <li>COBOL FUNCTION UPPER-CASE → Case conversion (toUpperCase)</li>
 *   <li>COBOL FUNCTION LOWER-CASE → Case conversion (toLowerCase)</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 */
public final class FormatUtil {

    /**
     * Private constructor to prevent instantiation of this utility class.
     * This class contains only static methods and should not be instantiated.
     */
    private FormatUtil() {
        throw new UnsupportedOperationException("FormatUtil is a utility class and cannot be instantiated");
    }

    /**
     * Pads a string on the left with the specified character to reach the desired length.
     * <p>
     * <b>COBOL Pattern:</b> Implements left-padding behavior similar to COBOL numeric fields
     * where values are right-aligned and left-padded with zeros or spaces.
     * </p>
     * <p>
     * Example: {@code padLeft("123", 5, '0')} returns {@code "00123"}
     * </p>
     * <p>
     * If the input string is null, returns a string filled with pad characters.
     * If the input string length equals or exceeds the target length, returns the original string.
     * </p>
     *
     * @param str the string to pad (may be null)
     * @param length the desired total length of the result
     * @param padChar the character to use for padding
     * @return the left-padded string, or the original string if it's already long enough
     */
    public static String padLeft(String str, int length, char padChar) {
        if (length <= 0) {
            return str != null ? str : "";
        }
        
        if (str == null) {
            return String.valueOf(padChar).repeat(length);
        }
        
        if (str.length() >= length) {
            return str;
        }
        
        int padCount = length - str.length();
        return String.valueOf(padChar).repeat(padCount) + str;
    }

    /**
     * Pads a string on the right with the specified character to reach the desired length.
     * <p>
     * <b>COBOL Pattern:</b> Replicates COBOL PIC X(n) field behavior where alphanumeric
     * fields are left-aligned and right-padded with spaces. This matches the COBOL MOVE
     * operation which automatically right-pads shorter values.
     * </p>
     * <p>
     * Example: {@code padRight("JOHN", 10, ' ')} returns {@code "JOHN      "}
     * </p>
     * <p>
     * If the input string is null, returns a string filled with pad characters.
     * If the input string length equals or exceeds the target length, returns the original string.
     * </p>
     *
     * @param str the string to pad (may be null)
     * @param length the desired total length of the result
     * @param padChar the character to use for padding
     * @return the right-padded string, or the original string if it's already long enough
     */
    public static String padRight(String str, int length, char padChar) {
        if (length <= 0) {
            return str != null ? str : "";
        }
        
        if (str == null) {
            return String.valueOf(padChar).repeat(length);
        }
        
        if (str.length() >= length) {
            return str;
        }
        
        int padCount = length - str.length();
        return str + String.valueOf(padChar).repeat(padCount);
    }

    /**
     * Trims trailing spaces from a string while preserving leading spaces.
     * <p>
     * <b>COBOL Pattern:</b> Replicates COBOL MOVE behavior where trailing spaces are
     * considered insignificant but leading spaces are preserved. This is the standard
     * COBOL string handling pattern when moving PIC X fields.
     * </p>
     * <p>
     * Unlike {@link String#trim()}, this method only removes trailing spaces and keeps
     * any leading spaces intact, matching COBOL's right-to-left trimming behavior.
     * </p>
     * <p>
     * Example:
     * <pre>
     * trimCobolStyle("  HELLO  ") returns "  HELLO"
     * trimCobolStyle("WORLD   ")  returns "WORLD"
     * </pre>
     * </p>
     *
     * @param str the string to trim (may be null)
     * @return the string with trailing spaces removed, or null if input is null
     */
    public static String trimCobolStyle(String str) {
        if (str == null) {
            return null;
        }
        
        if (str.isEmpty()) {
            return str;
        }
        
        int end = str.length() - 1;
        while (end >= 0 && str.charAt(end) == ' ') {
            end--;
        }
        
        if (end < 0) {
            return ""; // All spaces
        }
        
        return str.substring(0, end + 1);
    }

    /**
     * Masks a card number to show only the last 4 digits for PCI compliance.
     * <p>
     * <b>Security Pattern:</b> Implements PCI DSS requirement to mask primary account
     * numbers (PANs) when displaying card information. All digits except the last 4
     * are replaced with asterisks.
     * </p>
     * <p>
     * Example: {@code maskCardNumber("4111111111111111")} returns {@code "************1111"}
     * </p>
     * <p>
     * If the input is null or has 4 or fewer characters, returns the original string.
     * Non-digit characters are also masked to handle formatted card numbers like "4111-1111-1111-1111".
     * </p>
     *
     * @param cardNumber the card number to mask (may be null)
     * @return the masked card number showing only the last 4 characters, or the original if too short
     */
    public static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() <= 4) {
            return cardNumber;
        }
        
        int length = cardNumber.length();
        int visibleCount = 4;
        int maskCount = length - visibleCount;
        
        return "*".repeat(maskCount) + cardNumber.substring(maskCount);
    }

    /**
     * Converts a string to uppercase.
     * <p>
     * <b>COBOL Pattern:</b> Equivalent to COBOL {@code FUNCTION UPPER-CASE(string)}.
     * Used for case-insensitive comparisons and data normalization.
     * </p>
     * <p>
     * This is a null-safe wrapper around {@link String#toUpperCase()}.
     * </p>
     *
     * @param str the string to convert (may be null)
     * @return the uppercase string, or null if input is null
     */
    public static String toUpperCase(String str) {
        if (str == null) {
            return null;
        }
        return str.toUpperCase();
    }

    /**
     * Converts a string to lowercase.
     * <p>
     * <b>COBOL Pattern:</b> Equivalent to COBOL {@code FUNCTION LOWER-CASE(string)}.
     * Used for case normalization and display formatting.
     * </p>
     * <p>
     * This is a null-safe wrapper around {@link String#toLowerCase()}.
     * </p>
     *
     * @param str the string to convert (may be null)
     * @return the lowercase string, or null if input is null
     */
    public static String toLowerCase(String str) {
        if (str == null) {
            return null;
        }
        return str.toLowerCase();
    }

    /**
     * Replaces all occurrences of a character with another character in a string.
     * <p>
     * <b>COBOL Pattern:</b> Implements the COBOL {@code INSPECT string REPLACING ALL oldChar BY newChar}
     * operation. This is commonly used for data cleansing and format conversion.
     * </p>
     * <p>
     * Example: {@code replaceAll("123-45-6789", '-', ' ')} returns {@code "123 45 6789"}
     * </p>
     *
     * @param str the string in which to replace characters (may be null)
     * @param oldChar the character to replace
     * @param newChar the character to replace with
     * @return the string with all occurrences replaced, or null if input is null
     */
    public static String replaceAll(String str, char oldChar, char newChar) {
        if (str == null) {
            return null;
        }
        return str.replace(oldChar, newChar);
    }

    /**
     * Formats a numeric string by left-padding with zeros to reach the desired length.
     * <p>
     * <b>COBOL Pattern:</b> Replicates COBOL PIC 9(n) field behavior where numeric
     * fields are right-aligned and left-padded with zeros. This matches COBOL's
     * numeric MOVE operation which automatically zero-fills.
     * </p>
     * <p>
     * Example: {@code formatNumeric("123", 7)} returns {@code "0000123"}
     * </p>
     * <p>
     * This method is equivalent to {@code padLeft(str, length, '0')}.
     * </p>
     * <p>
     * If the input string is null, returns a string of zeros.
     * If the input string length equals or exceeds the target length, returns the original string.
     * </p>
     *
     * @param str the numeric string to format (may be null)
     * @param length the desired total length of the result
     * @return the zero-padded numeric string, or the original string if it's already long enough
     */
    public static String formatNumeric(String str, int length) {
        return padLeft(str, length, '0');
    }
}

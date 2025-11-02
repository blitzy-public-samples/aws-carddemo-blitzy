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

import java.util.ArrayList;
import java.util.List;

/**
 * StringUtils - Utility class providing string processing functions that replicate
 * COBOL INSPECT, STRING, UNSTRING, and FUNCTION TRIM operations.
 * 
 * This class maintains COBOL PIC X fixed-length field semantics including:
 * - Space-filling and truncation behavior
 * - Alphabetic (PIC A) and numeric (PIC 9) validation
 * - Character replacement and counting (INSPECT)
 * - String concatenation with delimiters (STRING)
 * - String parsing with delimiters (UNSTRING)
 * - Padding operations for fixed-length fields
 * 
 * All methods preserve COBOL's trailing space handling and fixed-length field behavior.
 * 
 * @version 1.0
 * @since 2024-01-01
 */
public final class StringUtils {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private StringUtils() {
        throw new UnsupportedOperationException("StringUtils is a utility class and cannot be instantiated");
    }

    /**
     * Checks if a string contains only numeric characters (0-9).
     * Replicates COBOL PIC 9 validation.
     * 
     * Null or empty strings return false.
     * Spaces are not considered numeric.
     * 
     * @param str the string to check
     * @return true if the string contains only digits, false otherwise
     */
    public static boolean isNumeric(String str) {
        if (isEmpty(str)) {
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
     * Checks if a string contains only alphabetic characters (A-Z, a-z).
     * Replicates COBOL PIC A validation.
     * 
     * Null or empty strings return false.
     * Spaces are not considered alphabetic.
     * 
     * @param str the string to check
     * @return true if the string contains only letters, false otherwise
     */
    public static boolean isAlphabetic(String str) {
        if (isEmpty(str)) {
            return false;
        }
        
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Checks if a string contains only alphanumeric characters (A-Z, a-z, 0-9).
     * Replicates COBOL PIC X alphanumeric validation.
     * 
     * Null or empty strings return false.
     * Spaces and special characters are not considered alphanumeric.
     * 
     * @param str the string to check
     * @return true if the string contains only letters and digits, false otherwise
     */
    public static boolean isAlphanumeric(String str) {
        if (isEmpty(str)) {
            return false;
        }
        
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Checks if a string is null or has zero length.
     * 
     * @param str the string to check
     * @return true if the string is null or empty, false otherwise
     */
    public static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

    /**
     * Checks if a string is not null and has at least one character.
     * 
     * @param str the string to check
     * @return true if the string is not null and not empty, false otherwise
     */
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    /**
     * Removes leading and trailing whitespace from a string.
     * Replicates COBOL FUNCTION TRIM.
     * 
     * Returns null if input is null.
     * Returns empty string if input is all whitespace.
     * 
     * @param str the string to trim
     * @return the trimmed string, or null if input was null
     */
    public static String trim(String str) {
        if (str == null) {
            return null;
        }
        return str.trim();
    }

    /**
     * Left-pads a string to a specified length with a given character.
     * Replicates COBOL PIC X field left-padding behavior.
     * 
     * If the string is longer than the specified length, it is truncated from the right.
     * If the string is null, it is treated as empty string.
     * 
     * @param str the string to pad
     * @param length the desired total length
     * @param padChar the character to use for padding
     * @return the left-padded string
     */
    public static String leftPad(String str, int length, char padChar) {
        if (str == null) {
            str = "";
        }
        
        if (str.length() >= length) {
            return str.substring(0, length);
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
     * Right-pads a string to a specified length with a given character.
     * Replicates COBOL PIC X field right-padding (space-filling) behavior.
     * 
     * If the string is longer than the specified length, it is truncated.
     * If the string is null, it is treated as empty string.
     * This is the most common COBOL padding pattern for fixed-length fields.
     * 
     * @param str the string to pad
     * @param length the desired total length
     * @param padChar the character to use for padding
     * @return the right-padded string
     */
    public static String rightPad(String str, int length, char padChar) {
        if (str == null) {
            str = "";
        }
        
        if (str.length() >= length) {
            return str.substring(0, length);
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
     * Centers a string within a specified length by padding with spaces on both sides.
     * Replicates COBOL centering logic for display fields.
     * 
     * If the string is longer than the specified length, it is truncated.
     * If the string is null, it is treated as empty string.
     * If padding is uneven, the extra space goes to the right.
     * 
     * @param str the string to center
     * @param length the desired total length
     * @return the centered string
     */
    public static String center(String str, int length) {
        if (str == null) {
            str = "";
        }
        
        if (str.length() >= length) {
            return str.substring(0, length);
        }
        
        int totalPad = length - str.length();
        int leftPad = totalPad / 2;
        int rightPad = totalPad - leftPad;
        
        StringBuilder sb = new StringBuilder(length);
        
        for (int i = 0; i < leftPad; i++) {
            sb.append(' ');
        }
        sb.append(str);
        for (int i = 0; i < rightPad; i++) {
            sb.append(' ');
        }
        
        return sb.toString();
    }

    /**
     * Replaces all occurrences of a character with another character.
     * Replicates COBOL INSPECT REPLACING ALL.
     * 
     * Returns null if input is null.
     * If oldChar is not found, returns the original string unchanged.
     * 
     * @param str the string to process
     * @param oldChar the character to replace
     * @param newChar the replacement character
     * @return the string with replacements made
     */
    public static String replaceAll(String str, char oldChar, char newChar) {
        if (str == null) {
            return null;
        }
        
        return str.replace(oldChar, newChar);
    }

    /**
     * Checks if a string contains a specific character.
     * Replicates COBOL INSPECT TALLYING.
     * 
     * Returns false if input is null.
     * 
     * @param str the string to search
     * @param ch the character to find
     * @return true if the character is found, false otherwise
     */
    public static boolean contains(String str, char ch) {
        if (str == null) {
            return false;
        }
        
        return str.indexOf(ch) >= 0;
    }

    /**
     * Converts all characters in a string to uppercase.
     * Replicates COBOL FUNCTION UPPER-CASE.
     * 
     * Returns null if input is null.
     * 
     * @param str the string to convert
     * @return the uppercase string
     */
    public static String toUpperCase(String str) {
        if (str == null) {
            return null;
        }
        
        return str.toUpperCase();
    }

    /**
     * Converts all characters in a string to lowercase.
     * Replicates COBOL FUNCTION LOWER-CASE.
     * 
     * Returns null if input is null.
     * 
     * @param str the string to convert
     * @return the lowercase string
     */
    public static String toLowerCase(String str) {
        if (str == null) {
            return null;
        }
        
        return str.toLowerCase();
    }

    /**
     * Extracts a substring from a string.
     * Replicates COBOL reference modification (str(start:length)).
     * 
     * COBOL uses 1-based indexing, but this method uses Java's 0-based indexing.
     * Returns null if input is null.
     * If start is beyond the string length, returns empty string.
     * If end is beyond the string length, returns substring to end of string.
     * 
     * @param str the string to extract from
     * @param start the starting index (0-based)
     * @param end the ending index (exclusive, 0-based)
     * @return the extracted substring
     */
    public static String substring(String str, int start, int end) {
        if (str == null) {
            return null;
        }
        
        if (start < 0) {
            start = 0;
        }
        
        if (start >= str.length()) {
            return "";
        }
        
        if (end > str.length()) {
            end = str.length();
        }
        
        if (end <= start) {
            return "";
        }
        
        return str.substring(start, end);
    }

    /**
     * Concatenates multiple strings into a single string.
     * Replicates COBOL STRING statement.
     * 
     * Null strings are treated as empty strings.
     * This is the basic concatenation without delimiters.
     * 
     * @param strings the strings to concatenate
     * @return the concatenated result
     */
    public static String concat(String... strings) {
        if (strings == null || strings.length == 0) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        
        for (String str : strings) {
            if (str != null) {
                sb.append(str);
            }
        }
        
        return sb.toString();
    }

    /**
     * Splits a string into an array of substrings using a delimiter character.
     * Replicates COBOL UNSTRING statement.
     * 
     * Returns null if input is null.
     * Empty strings between consecutive delimiters are included in the result.
     * If delimiter is not found, returns array with single element (the original string).
     * 
     * @param str the string to split
     * @param delimiter the delimiter character
     * @return array of substrings
     */
    public static String[] split(String str, char delimiter) {
        if (str == null) {
            return null;
        }
        
        if (str.length() == 0) {
            return new String[] { "" };
        }
        
        List<String> parts = new ArrayList<>();
        StringBuilder currentPart = new StringBuilder();
        
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == delimiter) {
                parts.add(currentPart.toString());
                currentPart = new StringBuilder();
            } else {
                currentPart.append(c);
            }
        }
        
        // Add the last part
        parts.add(currentPart.toString());
        
        return parts.toArray(new String[0]);
    }

    /**
     * Converts a COBOL-style fixed-length string to a trimmed Java string.
     * Removes trailing spaces that are common in COBOL PIC X fields.
     * 
     * This is a convenience method for converting COBOL fixed-length fields
     * to variable-length Java strings while preserving embedded spaces.
     * 
     * @param cobolField the COBOL fixed-length field
     * @return the trimmed string, or null if input was null
     */
    public static String fromCobolField(String cobolField) {
        if (cobolField == null) {
            return null;
        }
        
        // Remove trailing spaces only
        int endIndex = cobolField.length();
        while (endIndex > 0 && cobolField.charAt(endIndex - 1) == ' ') {
            endIndex--;
        }
        
        return cobolField.substring(0, endIndex);
    }

    /**
     * Converts a Java string to a COBOL-style fixed-length string.
     * Pads with spaces to the specified length or truncates if too long.
     * 
     * This is a convenience method for preparing Java strings for COBOL
     * fixed-length field semantics (PIC X(n)).
     * 
     * @param javaString the Java variable-length string
     * @param length the desired COBOL field length
     * @return the fixed-length string, or null if input was null
     */
    public static String toCobolField(String javaString, int length) {
        return rightPad(javaString, length, ' ');
    }

    /**
     * Counts the number of occurrences of a character in a string.
     * Replicates COBOL INSPECT TALLYING.
     * 
     * Returns 0 if input is null.
     * 
     * @param str the string to search
     * @param ch the character to count
     * @return the count of occurrences
     */
    public static int countOccurrences(String str, char ch) {
        if (str == null) {
            return 0;
        }
        
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) {
                count++;
            }
        }
        
        return count;
    }

    /**
     * Replaces the first occurrence of a character with another character.
     * Replicates COBOL INSPECT REPLACING FIRST.
     * 
     * Returns null if input is null.
     * If oldChar is not found, returns the original string unchanged.
     * 
     * @param str the string to process
     * @param oldChar the character to replace
     * @param newChar the replacement character
     * @return the string with the first replacement made
     */
    public static String replaceFirst(String str, char oldChar, char newChar) {
        if (str == null) {
            return null;
        }
        
        int index = str.indexOf(oldChar);
        if (index < 0) {
            return str;
        }
        
        char[] chars = str.toCharArray();
        chars[index] = newChar;
        return new String(chars);
    }

    /**
     * Checks if a string is blank (null, empty, or contains only whitespace).
     * 
     * This is useful for validating COBOL fields that may contain only spaces.
     * 
     * @param str the string to check
     * @return true if the string is blank, false otherwise
     */
    public static boolean isBlank(String str) {
        if (str == null || str.length() == 0) {
            return true;
        }
        
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Checks if a string is not blank (not null, not empty, and contains non-whitespace).
     * 
     * @param str the string to check
     * @return true if the string is not blank, false otherwise
     */
    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }
}

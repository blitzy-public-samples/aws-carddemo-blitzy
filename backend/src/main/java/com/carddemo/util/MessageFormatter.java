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

import java.util.Locale;
import java.util.Map;

/**
 * Utility class for formatting and managing application messages, error codes, 
 * and abend data structures.
 * 
 * Transformed from COBOL message definition copybooks:
 * - CSMSG01Y.cpy (CCDA-COMMON-MESSAGES)
 * - CSMSG02Y.cpy (ABEND-DATA)
 * 
 * This class replicates COBOL STRING statement behavior for message construction
 * and preserves exact message text values and field lengths from COBOL definitions.
 * 
 * All message formatting maintains functional equivalence with COBOL message 
 * handling patterns used across CICS transaction programs.
 */
public class MessageFormatter {
    
    /**
     * Message constants from CCDA-COMMON-MESSAGES (CSMSG01Y.cpy)
     * Maintains exact text values from COBOL PIC X(50) VALUE definitions
     */
    public static final String CCDA_MSG_THANK_YOU = 
        "Thank you for using CardDemo application...      ";
    
    public static final String CCDA_MSG_INVALID_KEY = 
        "Invalid key pressed. Please see below...         ";
    
    /**
     * Field length constants matching COBOL PIC clauses from ABEND-DATA structure
     */
    public static final int ABEND_CODE_LENGTH = 4;      // PIC X(4)
    public static final int ABEND_CULPRIT_LENGTH = 8;   // PIC X(8)
    public static final int ABEND_REASON_LENGTH = 50;   // PIC X(50)
    public static final int ABEND_MSG_LENGTH = 72;      // PIC X(72)
    
    /**
     * Private constructor to prevent instantiation of utility class
     */
    private MessageFormatter() {
        throw new UnsupportedOperationException("MessageFormatter is a utility class and cannot be instantiated");
    }
    
    /**
     * Formats an abend (abnormal end) message with code, culprit program, reason, and message.
     * 
     * Replicates COBOL STRING statement behavior for constructing formatted error messages.
     * All fields are padded or truncated to maintain COBOL field length specifications.
     * 
     * @param abendCode    Abend code (4 characters max, matching PIC X(4))
     * @param culprit      Program name causing the abend (8 characters max, matching PIC X(8))
     * @param reason       Reason for the abend (50 characters max, matching PIC X(50))
     * @param message      Detailed error message (72 characters max, matching PIC X(72))
     * @return Formatted abend message string with all components concatenated
     */
    public static String formatAbendMessage(String abendCode, String culprit, 
                                           String reason, String message) {
        StringBuilder result = new StringBuilder();
        
        // Format each component to match COBOL field lengths
        String formattedCode = padOrTruncate(abendCode, ABEND_CODE_LENGTH);
        String formattedCulprit = padOrTruncate(culprit, ABEND_CULPRIT_LENGTH);
        String formattedReason = padOrTruncate(reason, ABEND_REASON_LENGTH);
        String formattedMessage = padOrTruncate(message, ABEND_MSG_LENGTH);
        
        // Concatenate with delimiters (matching COBOL STRING ... DELIMITED BY pattern)
        result.append("ABEND-CODE: ").append(formattedCode).append(" | ");
        result.append("CULPRIT: ").append(formattedCulprit).append(" | ");
        result.append("REASON: ").append(formattedReason).append(" | ");
        result.append("MESSAGE: ").append(formattedMessage);
        
        return result.toString();
    }
    
    /**
     * Creates an AbendData object with specified values.
     * 
     * Factory method for creating properly formatted abend data structures
     * matching COBOL ABEND-DATA copybook layout.
     * 
     * @param abendCode    Abend code (4 characters max)
     * @param culprit      Program name (8 characters max)
     * @param reason       Reason description (50 characters max)
     * @param message      Error message (72 characters max)
     * @return AbendData object with formatted field values
     */
    public static AbendData createAbendData(String abendCode, String culprit, 
                                           String reason, String message) {
        AbendData abendData = new AbendData();
        abendData.setAbendCode(abendCode);
        abendData.setCulprit(culprit);
        abendData.setReason(reason);
        abendData.setMessage(message);
        return abendData;
    }
    
    /**
     * Formats a message template with parameters using US locale conventions.
     * 
     * Ensures consistent number and date formatting matching US locale conventions
     * used in COBOL mainframe environment. Prevents locale-dependent formatting
     * variations ensuring identical message text across deployment environments.
     * 
     * @param template Message template with format specifiers (e.g., "Balance: %.2f")
     * @param params   Variable arguments to substitute into template
     * @return Formatted message with parameters substituted using US locale
     */
    public static String formatMessageWithParams(String template, Object... params) {
        if (template == null) {
            return "";
        }
        if (params == null || params.length == 0) {
            return template;
        }
        
        // Use US locale to ensure consistent formatting matching COBOL environment
        return String.format(Locale.US, template, params);
    }
    
    /**
     * Concatenates multiple message strings into a single message.
     * 
     * Implements COBOL STRING statement equivalent for joining multiple
     * message fragments. Mimics COBOL "STRING ... DELIMITED BY SPACE INTO" pattern.
     * 
     * @param messages Variable number of message strings to concatenate
     * @return Concatenated message with spaces between non-empty segments
     */
    public static String concatenateMessages(String... messages) {
        if (messages == null || messages.length == 0) {
            return "";
        }
        
        StringBuilder result = new StringBuilder();
        boolean firstMessage = true;
        
        for (String message : messages) {
            if (message != null && !message.trim().isEmpty()) {
                if (!firstMessage) {
                    result.append(' ');
                }
                result.append(message.trim());
                firstMessage = false;
            }
        }
        
        return result.toString();
    }
    
    /**
     * Pads a string to specified length with trailing spaces.
     * 
     * Replicates COBOL behavior where PIC X(n) fields are automatically
     * space-padded when values are shorter than field definition.
     * 
     * @param value  String to pad
     * @param length Target length for padding
     * @return String padded to specified length with trailing spaces
     */
    public static String padToLength(String value, int length) {
        if (value == null) {
            value = "";
        }
        
        if (value.length() >= length) {
            return value;
        }
        
        StringBuilder padded = new StringBuilder(value);
        while (padded.length() < length) {
            padded.append(' ');
        }
        
        return padded.toString();
    }
    
    /**
     * Truncates a string to specified maximum length.
     * 
     * Replicates COBOL behavior where values exceeding PIC X(n) field
     * definitions are automatically truncated (overflow condition).
     * 
     * @param value  String to truncate
     * @param length Maximum length
     * @return String truncated to specified length
     */
    public static String truncateToLength(String value, int length) {
        if (value == null) {
            return "";
        }
        
        if (value.length() <= length) {
            return value;
        }
        
        return value.substring(0, length);
    }
    
    /**
     * Pads string to length with spaces or truncates if too long.
     * 
     * Combines padding and truncation to ensure exact field length matching
     * COBOL PIC X(n) field behavior: pad if short, truncate if long.
     * 
     * @param value  String to format
     * @param length Exact target length
     * @return String with exact specified length
     */
    public static String padOrTruncate(String value, int length) {
        if (value == null) {
            value = "";
        }
        
        if (value.length() == length) {
            return value;
        } else if (value.length() < length) {
            return padToLength(value, length);
        } else {
            return truncateToLength(value, length);
        }
    }
    
    /**
     * Replaces named parameters in a message template with values from a map.
     * 
     * Supports parameter substitution pattern: "Error in {program}: {reason}"
     * where {program} and {reason} are replaced with map values.
     * 
     * @param template Message template with {paramName} placeholders
     * @param params   Map of parameter names to replacement values
     * @return Message with parameters replaced by values
     */
    public static String replaceParameters(String template, Map<String, String> params) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        
        if (params == null || params.isEmpty()) {
            return template;
        }
        
        String result = template;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }
        
        return result;
    }
    
    /**
     * Builds a multi-line message from multiple message lines.
     * 
     * Concatenates message lines with system line separators for
     * creating formatted multi-line error messages or reports.
     * 
     * @param lines Variable number of message lines
     * @return Multi-line message with platform-specific line separators
     */
    public static String buildMultiLineMessage(String... lines) {
        if (lines == null || lines.length == 0) {
            return "";
        }
        
        StringBuilder result = new StringBuilder();
        boolean firstLine = true;
        
        for (String line : lines) {
            if (line != null) {
                if (!firstLine) {
                    result.append(System.lineSeparator());
                }
                result.append(line);
                firstLine = false;
            }
        }
        
        return result.toString();
    }
    
    /**
     * Formats an error message with error code and description.
     * 
     * Creates standardized error message format used throughout the
     * CardDemo application matching COBOL error message patterns.
     * 
     * @param errorCode Error code identifier
     * @param errorDesc Error description text
     * @return Formatted error message: "ERROR [code]: description"
     */
    public static String formatErrorMessage(String errorCode, String errorDesc) {
        StringBuilder result = new StringBuilder();
        result.append("ERROR");
        
        if (errorCode != null && !errorCode.trim().isEmpty()) {
            result.append(" [").append(errorCode.trim()).append("]");
        }
        
        result.append(": ");
        
        if (errorDesc != null && !errorDesc.trim().isEmpty()) {
            result.append(errorDesc.trim());
        } else {
            result.append("Unknown error");
        }
        
        return result.toString();
    }
    
    /**
     * Appends a string to StringBuilder with optional delimiter.
     * 
     * Helper method for implementing COBOL STRING ... DELIMITED BY pattern.
     * Adds delimiter before appending value if StringBuilder is not empty.
     * 
     * @param builder   StringBuilder to append to
     * @param value     String value to append
     * @param delimiter Delimiter to insert before value (if builder not empty)
     */
    public static void appendWithDelimiter(StringBuilder builder, String value, String delimiter) {
        if (builder == null) {
            throw new IllegalArgumentException("StringBuilder cannot be null");
        }
        
        if (value == null || value.isEmpty()) {
            return;
        }
        
        // Add delimiter if builder already has content and delimiter is specified
        if (builder.length() > 0 && delimiter != null && !delimiter.isEmpty()) {
            builder.append(delimiter);
        }
        
        builder.append(value);
    }
    
    /**
     * AbendData class representing COBOL ABEND-DATA structure from CSMSG02Y.cpy.
     * 
     * Java equivalent of COBOL copybook structure:
     * <pre>
     * 01  ABEND-DATA.
     *   05  ABEND-CODE      PIC X(4)  VALUE SPACES.
     *   05  ABEND-CULPRIT   PIC X(8)  VALUE SPACES.
     *   05  ABEND-REASON    PIC X(50) VALUE SPACES.
     *   05  ABEND-MSG       PIC X(72) VALUE SPACES.
     * </pre>
     * 
     * Maintains exact field lengths and behavior from COBOL definition.
     * All setters enforce field length constraints via padding or truncation.
     */
    public static class AbendData {
        private String abendCode;    // PIC X(4) - 4 characters
        private String culprit;      // PIC X(8) - 8 characters (program name)
        private String reason;       // PIC X(50) - 50 characters
        private String message;      // PIC X(72) - 72 characters
        
        /**
         * Default constructor initializing all fields to spaces matching COBOL VALUE SPACES.
         */
        public AbendData() {
            this.abendCode = padToLength("", ABEND_CODE_LENGTH);
            this.culprit = padToLength("", ABEND_CULPRIT_LENGTH);
            this.reason = padToLength("", ABEND_REASON_LENGTH);
            this.message = padToLength("", ABEND_MSG_LENGTH);
        }
        
        /**
         * Gets the abend code (4 characters).
         * 
         * @return Abend code matching PIC X(4) format
         */
        public String getAbendCode() {
            return abendCode;
        }
        
        /**
         * Sets the abend code, enforcing 4-character length constraint.
         * 
         * @param abendCode Abend code to set (will be padded or truncated to 4 chars)
         */
        public void setAbendCode(String abendCode) {
            this.abendCode = padOrTruncate(abendCode, ABEND_CODE_LENGTH);
        }
        
        /**
         * Gets the culprit program name (8 characters).
         * 
         * @return Culprit program name matching PIC X(8) format
         */
        public String getCulprit() {
            return culprit;
        }
        
        /**
         * Sets the culprit program name, enforcing 8-character length constraint.
         * 
         * @param culprit Program name to set (will be padded or truncated to 8 chars)
         */
        public void setCulprit(String culprit) {
            this.culprit = padOrTruncate(culprit, ABEND_CULPRIT_LENGTH);
        }
        
        /**
         * Gets the abend reason (50 characters).
         * 
         * @return Abend reason matching PIC X(50) format
         */
        public String getReason() {
            return reason;
        }
        
        /**
         * Sets the abend reason, enforcing 50-character length constraint.
         * 
         * @param reason Reason to set (will be padded or truncated to 50 chars)
         */
        public void setReason(String reason) {
            this.reason = padOrTruncate(reason, ABEND_REASON_LENGTH);
        }
        
        /**
         * Gets the abend message (72 characters).
         * 
         * @return Abend message matching PIC X(72) format
         */
        public String getMessage() {
            return message;
        }
        
        /**
         * Sets the abend message, enforcing 72-character length constraint.
         * 
         * @param message Message to set (will be padded or truncated to 72 chars)
         */
        public void setMessage(String message) {
            this.message = padOrTruncate(message, ABEND_MSG_LENGTH);
        }
        
        /**
         * Returns a string representation of the AbendData object.
         * 
         * @return String with all field values
         */
        @Override
        public String toString() {
            return "AbendData{" +
                   "abendCode='" + abendCode + '\'' +
                   ", culprit='" + culprit + '\'' +
                   ", reason='" + reason + '\'' +
                   ", message='" + message + '\'' +
                   '}';
        }
        
        /**
         * Returns a formatted string representation matching COBOL display format.
         * 
         * @return Formatted abend data string with field labels
         */
        public String toFormattedString() {
            return formatAbendMessage(abendCode, culprit, reason, message);
        }
    }
}


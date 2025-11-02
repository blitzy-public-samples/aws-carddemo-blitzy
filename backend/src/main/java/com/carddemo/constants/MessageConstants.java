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
package com.carddemo.constants;

/**
 * Application-wide message constant definitions transformed from COBOL CSMSG01Y copybook.
 * 
 * This class provides centralized message constants used throughout the CardDemo application
 * for user-facing messages, system messages, confirmations, and validation messages.
 * All messages maintain the original 50-character length constraint from the COBOL 
 * PIC X(50) specification to ensure UI compatibility with BMS screen layouts.
 * 
 * Message categories:
 * - User-facing informational messages (THANK_YOU_MESSAGE, MENU_SELECTION_PROMPT)
 * - Error and validation messages (INVALID_KEY_MESSAGE, INVALID_OPTION_MESSAGE, VALIDATION_ERROR_MESSAGE)
 * - System messages (SYSTEM_ERROR_MESSAGE)
 * - Message prefixes (ERROR_MESSAGE_PREFIX, SUCCESS_MESSAGE_PREFIX)
 * 
 * Transformation from COBOL:
 * - CCDA-MSG-THANK-YOU → THANK_YOU_MESSAGE
 * - CCDA-MSG-INVALID-KEY → INVALID_KEY_MESSAGE
 * 
 * All message text is preserved exactly from the original COBOL implementation to maintain
 * user experience continuity during the mainframe-to-cloud migration.
 * 
 * @see CSMSG01Y.cpy Original COBOL copybook
 * @since CardDemo Java Migration 1.0
 */
public final class MessageConstants {

    /**
     * Private constructor to prevent instantiation of this utility class.
     * This class contains only static constants and should never be instantiated.
     */
    private MessageConstants() {
        throw new UnsupportedOperationException("MessageConstants is a utility class and cannot be instantiated");
    }

    /**
     * Thank you message displayed to users upon successful completion of operations
     * or when exiting the application.
     * 
     * Original COBOL: CCDA-MSG-THANK-YOU PIC X(50)
     * Length: 50 characters (padded with spaces)
     * Usage: Application exit screens, operation completion confirmations
     */
    public static final String THANK_YOU_MESSAGE = 
        "Thank you for using CardDemo application...       ";

    /**
     * Invalid key press message displayed when user presses an unrecognized function key
     * or navigation key that is not valid for the current screen context.
     * 
     * Original COBOL: CCDA-MSG-INVALID-KEY PIC X(50)
     * Length: 50 characters (padded with spaces)
     * Usage: BMS screen navigation error handling, invalid PF key detection
     */
    public static final String INVALID_KEY_MESSAGE = 
        "Invalid key pressed. Please see below...          ";

    /**
     * Invalid option message displayed when user enters a menu selection or option
     * that is not available or not valid for their access level.
     * 
     * Length: 50 characters (padded with spaces)
     * Usage: Menu selection validation, option availability checks
     */
    public static final String INVALID_OPTION_MESSAGE = 
        "Invalid option selected. Please try again...      ";

    /**
     * Menu selection prompt message displayed on menu screens to guide users
     * in making a selection from available options.
     * 
     * Length: 50 characters (padded with spaces)
     * Usage: Main menu screen, secondary menu screens, report menu
     */
    public static final String MENU_SELECTION_PROMPT = 
        "Please select an option from the menu above...    ";

    /**
     * Error message prefix used to construct dynamic error messages.
     * Can be concatenated with specific error details to provide context.
     * 
     * Length: 50 characters (padded with spaces)
     * Usage: Error message construction, exception handling display
     */
    public static final String ERROR_MESSAGE_PREFIX = 
        "ERROR:                                            ";

    /**
     * Success message prefix used to construct dynamic success messages.
     * Can be concatenated with specific operation details to confirm completion.
     * 
     * Length: 50 characters (padded with spaces)
     * Usage: Success message construction, operation confirmation display
     */
    public static final String SUCCESS_MESSAGE_PREFIX = 
        "SUCCESS:                                          ";

    /**
     * Validation error message displayed when input field validation fails
     * due to invalid format, missing required data, or constraint violations.
     * 
     * Length: 50 characters (padded with spaces)
     * Usage: Form validation errors, field-level validation feedback
     */
    public static final String VALIDATION_ERROR_MESSAGE = 
        "Validation error. Please check your input...      ";

    /**
     * System error message displayed when an unexpected system-level error occurs,
     * such as database connectivity issues or internal processing failures.
     * 
     * Length: 50 characters (padded with spaces)
     * Usage: Exception handling, system failure scenarios, technical errors
     */
    public static final String SYSTEM_ERROR_MESSAGE = 
        "System error occurred. Please contact support...  ";

    /**
     * Maximum length for all message constants, matching the COBOL PIC X(50) specification.
     * This constant is provided for validation purposes to ensure message length compliance.
     */
    public static final int MAX_MESSAGE_LENGTH = 50;

    /**
     * Validates that a message conforms to the 50-character length constraint.
     * 
     * @param message The message to validate
     * @return true if the message is exactly 50 characters, false otherwise
     */
    public static boolean isValidMessageLength(String message) {
        return message != null && message.length() == MAX_MESSAGE_LENGTH;
    }

    /**
     * Pads a message to the required 50-character length by appending spaces.
     * If the message is already 50 or more characters, it is truncated to 50 characters.
     * 
     * @param message The message to pad
     * @return The message padded or truncated to exactly 50 characters
     * @throws IllegalArgumentException if message is null
     */
    public static String padMessage(String message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return message.substring(0, MAX_MESSAGE_LENGTH);
        }
        return String.format("%-50s", message);
    }

    /**
     * Trims trailing spaces from a message while preserving the original content.
     * This is useful when displaying messages in modern UI where trailing spaces
     * are not desired, while maintaining compatibility with COBOL message formats.
     * 
     * @param message The message to trim
     * @return The message with trailing spaces removed
     * @throws IllegalArgumentException if message is null
     */
    public static String trimMessage(String message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        return message.stripTrailing();
    }
}

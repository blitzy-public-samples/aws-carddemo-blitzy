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

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Utility class for internationalized message retrieval from messages.properties file.
 * 
 * <p>Converted from COBOL copybook: CSMSG01Y.cpy</p>
 * <p>Original structure: CCDA-COMMON-MESSAGES with 05-level PIC X(50) message fields</p>
 * 
 * <p>This utility provides static methods for retrieving error messages, validation messages,
 * and user notifications with parameterized text replacement using MessageFormat. All COBOL
 * VALUE clauses from the original copybook (e.g., 'Thank you for using CardDemo application...')
 * have been converted to property file entries (e.g., ccda.msg.thank.you=Thank you for using
 * CardDemo application...).</p>
 * 
 * <p>Key conversion notes:</p>
 * <ul>
 *   <li>COBOL 01 CCDA-COMMON-MESSAGES structure → Java ResourceBundle-based message loading</li>
 *   <li>COBOL 05-level PIC X(50) fields → Property file key-value pairs</li>
 *   <li>COBOL VALUE clauses → messages.properties entries</li>
 *   <li>COBOL field names (e.g., CCDA-MSG-THANK-YOU) → Property keys (ccda.msg.thank.you)</li>
 * </ul>
 * 
 * <p>Thread-safe implementation using ResourceBundle's thread-safe behavior. All methods are
 * stateless and static, ensuring safe concurrent access from multiple threads. ResourceBundle
 * caching is handled automatically by the JVM for optimal performance.</p>
 * 
 * <p>Example usage:</p>
 * <pre>
 * // Simple message retrieval
 * String message = MessageUtil.getMessage("ccda.msg.thank.you");
 * 
 * // Parameterized message with placeholders
 * String errorMsg = MessageUtil.getMessage("error.account.notfound", "12345", "John Doe");
 * // Produces: "Account 12345 not found for user John Doe"
 * </pre>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 1.0
 */
public class MessageUtil {
    
    /**
     * Base name for the message resource bundle.
     * Corresponds to messages.properties file in classpath (src/main/resources/messages/).
     */
    private static final String BUNDLE_BASE_NAME = "messages.messages";
    
    /**
     * Cached ResourceBundle instance for message retrieval.
     * Thread-safe as per ResourceBundle specification.
     * Initialized lazily on first access via getResourceBundle() method.
     */
    private static ResourceBundle resourceBundle;
    
    /**
     * Default locale for message retrieval.
     * Can be overridden by providing locale parameter in future enhancements.
     */
    private static final Locale DEFAULT_LOCALE = Locale.getDefault();
    
    // ========================================================================
    // Message Key Constants
    // Mapping COBOL copybook field names to property keys
    // ========================================================================
    
    /**
     * Message key for thank you message.
     * Maps to COBOL field: CCDA-MSG-THANK-YOU
     * Original value: 'Thank you for using CardDemo application...'
     */
    public static final String MSG_THANK_YOU = "ccda.msg.thank.you";
    
    /**
     * Message key for invalid key pressed message.
     * Maps to COBOL field: CCDA-MSG-INVALID-KEY
     * Original value: 'Invalid key pressed. Please see below...'
     */
    public static final String MSG_INVALID_KEY = "ccda.msg.invalid.key";
    
    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static methods.
     */
    private MessageUtil() {
        throw new UnsupportedOperationException("MessageUtil is a utility class and cannot be instantiated");
    }
    
    /**
     * Retrieves the ResourceBundle instance, initializing it if necessary.
     * 
     * @return the ResourceBundle for message retrieval
     * @throws MissingResourceException if the resource bundle cannot be found
     */
    private static synchronized ResourceBundle getResourceBundle() {
        if (resourceBundle == null) {
            resourceBundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, DEFAULT_LOCALE);
        }
        return resourceBundle;
    }
    
    /**
     * Retrieves a message from the resource bundle using the specified key.
     * 
     * <p>This method replaces COBOL direct field access to message constants.
     * For example, COBOL code accessing CCDA-MSG-THANK-YOU would now call
     * MessageUtil.getMessage("ccda.msg.thank.you").</p>
     * 
     * <p>If the message key is not found in the properties file, a fallback
     * message is returned instead of throwing an exception. This provides
     * graceful degradation similar to how missing COBOL copybook fields
     * would cause compile-time errors, but Java provides runtime resilience.</p>
     * 
     * @param key the message key (e.g., "ccda.msg.thank.you")
     * @return the message text associated with the key, or a fallback message if key not found
     * @throws IllegalArgumentException if key is null or empty
     */
    public static String getMessage(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Message key cannot be null or empty");
        }
        
        try {
            ResourceBundle bundle = getResourceBundle();
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            // Graceful error handling: return fallback message instead of propagating exception
            // This ensures application remains operational even if message keys are missing
            return "Message not found: [" + key + "]";
        }
    }
    
    /**
     * Retrieves a parameterized message from the resource bundle and formats it with the given parameters.
     * 
     * <p>This method supports messages with placeholders like {0}, {1}, {2}, etc.,
     * which are replaced with the provided parameter values using MessageFormat.
     * This replaces COBOL STRING verb with variable substitution (e.g., STRING 'Account '
     * DELIMITED BY SIZE WS-ACCT-ID DELIMITED BY SIZE ' not found' INTO WS-MSG).</p>
     * 
     * <p>MessageFormat is thread-safe when using the static format() method as implemented here.
     * Parameter ordering can vary by locale, enabling proper internationalization.</p>
     * 
     * <p>Example message in properties file:</p>
     * <pre>
     * error.account.notfound=Account {0} not found for user {1}
     * </pre>
     * 
     * <p>Example usage:</p>
     * <pre>
     * String message = MessageUtil.getMessage("error.account.notfound", "12345", "John Doe");
     * // Returns: "Account 12345 not found for user John Doe"
     * </pre>
     * 
     * @param key the message key (e.g., "error.account.notfound")
     * @param params the parameters to substitute into the message placeholders
     * @return the formatted message text with parameters substituted
     * @throws IllegalArgumentException if key is null or empty
     */
    public static String getMessage(String key, Object... params) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Message key cannot be null or empty");
        }
        
        try {
            ResourceBundle bundle = getResourceBundle();
            String messagePattern = bundle.getString(key);
            
            // If no parameters provided, return the message as-is
            if (params == null || params.length == 0) {
                return messagePattern;
            }
            
            // Format the message with provided parameters using MessageFormat
            // This is thread-safe as we're using the static format() method
            return MessageFormat.format(messagePattern, params);
            
        } catch (MissingResourceException e) {
            // Graceful error handling: return fallback message with key
            return "Message not found: [" + key + "]";
        } catch (IllegalArgumentException e) {
            // Handle MessageFormat errors (e.g., malformed pattern, wrong number of arguments)
            // Return the unformatted message pattern with error indicator
            try {
                ResourceBundle bundle = getResourceBundle();
                String messagePattern = bundle.getString(key);
                return messagePattern + " [Format Error: " + e.getMessage() + "]";
            } catch (MissingResourceException mre) {
                return "Message not found: [" + key + "] [Format Error: " + e.getMessage() + "]";
            }
        }
    }
    
    /**
     * Clears the cached ResourceBundle, forcing reload on next access.
     * 
     * <p>This method is primarily useful for testing scenarios where messages.properties
     * might be modified at runtime, or for switching locales dynamically. Under normal
     * operation, the cached ResourceBundle provides optimal performance.</p>
     * 
     * <p>Thread-safe operation ensured by synchronized keyword.</p>
     */
    public static synchronized void clearCache() {
        resourceBundle = null;
    }
    
    /**
     * Checks if a message key exists in the resource bundle.
     * 
     * <p>This method can be used to validate message keys before retrieval,
     * avoiding fallback messages in scenarios where key existence needs to be
     * verified programmatically.</p>
     * 
     * @param key the message key to check
     * @return true if the key exists in the resource bundle, false otherwise
     */
    public static boolean hasMessage(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        
        try {
            ResourceBundle bundle = getResourceBundle();
            bundle.getString(key);
            return true;
        } catch (MissingResourceException e) {
            return false;
        }
    }
}

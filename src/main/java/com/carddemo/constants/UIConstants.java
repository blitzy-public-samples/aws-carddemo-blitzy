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
 * User Interface Constants for CardDemo Application.
 * 
 * <p>This class contains UI-related constants including screen titles, pagination limits,
 * application branding text, and display configuration values used across React components
 * and REST API responses.</p>
 * 
 * <p><b>Source Mapping:</b> Transformed from COBOL copybook COTTL01Y.cpy which defined
 * screen title literals in the CCDA-SCREEN-TITLE structure. COBOL PIC X(40) literals
 * have been trimmed of trailing spaces while preserving exact text content for brand
 * consistency.</p>
 * 
 * <p><b>Pagination Requirements:</b> As specified in the Agent Action Plan section 0.5,
 * the application requires consistent pagination across the UI:
 * <ul>
 *   <li>Card lists: 7 cards per page</li>
 *   <li>Transaction lists: 10 transactions per page</li>
 * </ul>
 * These values ensure functional equivalence with the original mainframe BMS screen
 * display patterns.</p>
 * 
 * <p><b>Usage:</b> These constants are referenced by REST API controllers for response
 * pagination and by React components for consistent UI display configuration.</p>
 * 
 * @see com.carddemo.controller.CardController
 * @see com.carddemo.controller.TransactionController
 * @since 1.0
 */
public final class UIConstants {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private UIConstants() {
        throw new UnsupportedOperationException("UIConstants is a utility class and cannot be instantiated");
    }

    // ========================================================================
    // SCREEN TITLES
    // ========================================================================
    
    /**
     * Primary application title line.
     * <p>Transformed from COBOL: CCDA-TITLE01 PIC X(40) VALUE 'AWS Mainframe Modernization'</p>
     * <p>Displayed as the first line of screen headers across the application.</p>
     */
    public static final String TITLE_LINE_1 = "AWS Mainframe Modernization";

    /**
     * Secondary application title line - application name.
     * <p>Transformed from COBOL: CCDA-TITLE02 PIC X(40) VALUE 'CardDemo'</p>
     * <p>Displayed as the second line of screen headers, identifying the specific application.</p>
     */
    public static final String TITLE_LINE_2 = "CardDemo";

    /**
     * Application name constant for branding and identification.
     * <p>Used in API responses, logging, and component headers.</p>
     */
    public static final String APPLICATION_NAME = "CardDemo";

    /**
     * Thank you message displayed on sign-out or completion screens.
     * <p>Transformed from COBOL: CCDA-THANK-YOU PIC X(40) VALUE 'Thank you for using CCDA application...'</p>
     * <p>Preserves original COBOL text with trailing ellipsis indicating continuation.</p>
     */
    public static final String THANK_YOU_MESSAGE = "Thank you for using CCDA application...";

    // ========================================================================
    // PAGINATION CONSTANTS
    // ========================================================================
    
    /**
     * Number of cards to display per page in card list views.
     * <p><b>Specification Requirement:</b> Agent Action Plan section 0.5 mandates 7 cards per page
     * to maintain functional equivalence with the original BMS screen layout (COCRDLIM.bms).</p>
     * <p>Used by CardListService and CardController for pagination.</p>
     */
    public static final int CARDS_PER_PAGE = 7;

    /**
     * Number of transactions to display per page in transaction list views.
     * <p><b>Specification Requirement:</b> Agent Action Plan section 0.5 mandates 10 transactions
     * per page to maintain functional equivalence with the original BMS screen layout (COTRN00M.bms).</p>
     * <p>Used by TransactionListService and TransactionController for pagination.</p>
     */
    public static final int TRANSACTIONS_PER_PAGE = 10;

    /**
     * Default page size for generic paginated results.
     * <p>Used when no specific pagination constant is defined for a particular entity type.
     * Set to match the transaction pagination for consistency.</p>
     */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * Maximum number of search results to return in a single response.
     * <p>Prevents excessive data transfer and ensures reasonable response times.
     * Used by search endpoints that don't use standard pagination.</p>
     */
    public static final int MAX_SEARCH_RESULTS = 100;

    // ========================================================================
    // DISPLAY LIMITS AND FIELD CONSTRAINTS
    // ========================================================================
    
    /**
     * Maximum character length for general input fields.
     * <p>Derived from common COBOL field definitions where most descriptive fields
     * were defined as PIC X(40). Used for validation in React form components and
     * API request validation.</p>
     */
    public static final int MAX_FIELD_LENGTH = 40;

    /**
     * Screen timeout duration in seconds for user inactivity.
     * <p>After this duration of inactivity, the user session may be terminated
     * and the user redirected to the login screen. Set to 30 minutes (1800 seconds)
     * to match typical mainframe session timeout patterns.</p>
     * <p>This value should be coordinated with JWT token expiration settings
     * in SecurityConstants.</p>
     */
    public static final int SCREEN_TIMEOUT_SECONDS = 1800;

    // ========================================================================
    // DISPLAY CONFIGURATION
    // ========================================================================
    
    /**
     * Maximum length for customer name fields (first name, middle name, last name).
     * <p>Matches COBOL customer record field definitions.</p>
     */
    public static final int MAX_NAME_LENGTH = 25;

    /**
     * Maximum length for address line fields.
     * <p>Matches COBOL customer address field definitions.</p>
     */
    public static final int MAX_ADDRESS_LENGTH = 50;

    /**
     * Maximum length for city name fields.
     * <p>Matches COBOL customer address field definitions.</p>
     */
    public static final int MAX_CITY_LENGTH = 25;

    /**
     * Maximum length for state code fields.
     * <p>Matches COBOL customer address field definitions (typically 2-character US state codes).</p>
     */
    public static final int MAX_STATE_LENGTH = 2;

    /**
     * Maximum length for postal code fields.
     * <p>Matches COBOL customer address field definitions (supports both 5-digit and 9-digit ZIP codes).</p>
     */
    public static final int MAX_POSTAL_CODE_LENGTH = 10;

    /**
     * Maximum length for phone number fields.
     * <p>Accommodates formatted phone numbers with country code, area code, and extension.</p>
     */
    public static final int MAX_PHONE_LENGTH = 20;

    /**
     * Fixed length for card number fields.
     * <p>Standard 16-digit credit card number format.</p>
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * Fixed length for card CVV fields.
     * <p>Standard 3-digit security code.</p>
     */
    public static final int CVV_LENGTH = 3;

    /**
     * Maximum length for merchant name fields in transaction records.
     * <p>Matches COBOL transaction record field definitions.</p>
     */
    public static final int MAX_MERCHANT_NAME_LENGTH = 50;

    /**
     * Maximum length for transaction description fields.
     * <p>Matches COBOL transaction record field definitions.</p>
     */
    public static final int MAX_TRANSACTION_DESCRIPTION_LENGTH = 100;

    /**
     * Number of visible digits for masked card numbers (last N digits shown).
     * <p>For security, card numbers are displayed as "************1234" showing only
     * the last 4 digits. This constant defines how many digits remain visible.</p>
     */
    public static final int CARD_DISPLAY_VISIBLE_DIGITS = 4;

    /**
     * Default number of decimal places for monetary amount display.
     * <p>All monetary values are displayed with 2 decimal places to match
     * COBOL PIC S9(10)V99 field definitions and standard currency formatting.</p>
     */
    public static final int CURRENCY_DECIMAL_PLACES = 2;

    /**
     * Format string for currency display in UI components.
     * <p>Used by React components to consistently format monetary values.</p>
     */
    public static final String CURRENCY_FORMAT_PATTERN = "$###,###,##0.00";

    /**
     * Format string for date display in UI components.
     * <p>Standard date format used across the application UI.</p>
     */
    public static final String DATE_FORMAT_PATTERN = "MM/dd/yyyy";

    /**
     * Format string for date-time display in UI components.
     * <p>Used for timestamps on transactions and audit logs.</p>
     */
    public static final String DATETIME_FORMAT_PATTERN = "MM/dd/yyyy HH:mm:ss";
}

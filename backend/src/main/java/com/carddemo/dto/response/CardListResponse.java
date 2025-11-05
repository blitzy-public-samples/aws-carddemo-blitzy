/*
 * CardListResponse.java
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.dto.response;

import com.carddemo.constants.CardStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Response DTO for paginated card list display operations transformed from COBOL BMS copybook COCRDLI.CPY.
 * 
 * <p>This class represents the output structure for the card list screen (CICS transaction CCLI / program COCRDLIC)
 * displaying up to 7 cards per page with pagination support, preserving the exact field layout and business logic
 * from the mainframe 3270 terminal screen.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <pre>
 * COBOL Copybook: app/cpy-bms/COCRDLI.CPY (CCRDLIAO output structure)
 * COBOL Program:  app/cbl/COCRDLIC.cbl (Card List business logic)
 * BMS Mapset:     app/bms/COCRDLIM.bms (Card List screen definition)
 * </pre>
 * 
 * <h2>Field Transformations</h2>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Field</th>
 *     <th>Java Field</th>
 *     <th>Type Transformation</th>
 *   </tr>
 *   <tr><td>TRNNAMEO PIC X(4)</td><td>transactionName</td><td>String(4)</td></tr>
 *   <tr><td>TITLE01O PIC X(40)</td><td>title01</td><td>String(40)</td></tr>
 *   <tr><td>CURDATEO PIC X(8)</td><td>currentDate</td><td>LocalDate (MM/DD/YYYY)</td></tr>
 *   <tr><td>PGMNAMEO PIC X(8)</td><td>programName</td><td>String(8)</td></tr>
 *   <tr><td>TITLE02O PIC X(40)</td><td>title02</td><td>String(40)</td></tr>
 *   <tr><td>CURTIMEO PIC X(8)</td><td>currentTime</td><td>LocalTime (HH:MM:SS)</td></tr>
 *   <tr><td>PAGENOO PIC X(3)</td><td>pageNumber</td><td>Integer</td></tr>
 *   <tr><td>ACCTSIDO PIC X(11)</td><td>accountIdFilter</td><td>String(11)</td></tr>
 *   <tr><td>CARDSIDO PIC X(16)</td><td>cardIdFilter</td><td>String(16)</td></tr>
 *   <tr><td>CRDSEL1O-7O PIC X(1)</td><td>cards[n].selectionFlag</td><td>String(1)</td></tr>
 *   <tr><td>ACCTNO1O-7O PIC X(11)</td><td>cards[n].accountNumber</td><td>String(11)</td></tr>
 *   <tr><td>CRDNUM1O-7O PIC X(16)</td><td>cards[n].cardNumber</td><td>String(16)</td></tr>
 *   <tr><td>CRDSTS1O-7O PIC X(1)</td><td>cards[n].cardStatus</td><td>String(1) → CardStatus</td></tr>
 *   <tr><td>INFOMSGO PIC X(45)</td><td>infoMessage</td><td>String(45)</td></tr>
 *   <tr><td>ERRMSGO PIC X(78)</td><td>errorMessage</td><td>String(78)</td></tr>
 * </table>
 * 
 * <h2>Pagination Pattern Preservation</h2>
 * <p>Maintains the legacy 7-cards-per-page pagination pattern from COBOL program COCRDLIC.cbl
 * (WS-MAX-SCREEN-LINES = 7) while adding modern REST pagination metadata (totalPages, totalElements, etc.)
 * to support React frontend pagination controls.</p>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * // Service layer creates response from Page&lt;Card&gt; result
 * CardListResponse response = new CardListResponse();
 * response.setTransactionName("CCLI");
 * response.setProgramName("COCRDLIC");
 * response.setCurrentDate(LocalDate.now());
 * response.setCurrentTime(LocalTime.now());
 * response.setPageNumber(1);
 * response.setCurrentPage(0);
 * response.setPageSize(7);
 * response.setTotalElements(25L);
 * response.setTotalPages(4);
 * response.setHasNext(true);
 * response.setHasPrevious(false);
 * 
 * List&lt;CardItemDTO&gt; cardItems = new ArrayList&lt;&gt;();
 * for (Card card : cardPage.getContent()) {
 *     CardItemDTO item = new CardItemDTO();
 *     item.setAccountNumber(card.getAccountId().toString());
 *     item.setCardNumber(card.getCardNumber());
 *     item.setCardStatus(String.valueOf(card.getStatus().getCode()));
 *     cardItems.add(item);
 * }
 * response.setCards(cardItems);
 * </pre>
 * 
 * <h2>REST API Endpoint</h2>
 * <p>Returned by: GET /api/cards?page=0&size=7&accountId={filter}&cardId={filter}</p>
 * 
 * <h2>Serialization Support</h2>
 * <p>Implements Serializable for Redis-backed Spring Session storage, supporting stateless REST API
 * architecture with session management per Section 0.5 Redis configuration requirements.</p>
 * 
 * @see com.carddemo.service.CardListService
 * @see com.carddemo.controller.CardController
 * @see com.carddemo.constants.CardStatus
 * @since 1.0
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardListResponse implements Serializable {
    
    /**
     * Serialization version UID for compatibility across JVM instances.
     * Critical for Redis session storage and distributed cache operations.
     */
    private static final long serialVersionUID = 1L;
    
    // ==================== Header Fields (Screen Context) ====================
    
    /**
     * Transaction identifier displayed in screen header.
     * COBOL: TRNNAMEO PIC X(4) from COCRDLI.CPY line 296
     * Value: "CCLI" (Card List transaction ID)
     */
    @JsonProperty("transactionName")
    @Size(max = 4, message = "Transaction name cannot exceed 4 characters")
    private String transactionName;
    
    /**
     * Primary screen title displayed at top of card list.
     * COBOL: TITLE01O PIC X(40) from COCRDLI.CPY line 302
     * Typical value: "Card List for Account Selection"
     */
    @JsonProperty("title01")
    @Size(max = 40, message = "Title 01 cannot exceed 40 characters")
    private String title01;
    
    /**
     * Current system date displayed in screen header.
     * COBOL: CURDATEO PIC X(8) from COCRDLI.CPY line 308
     * Format transformation: COBOL MMDDYYYY → Java LocalDate → JSON MM/DD/YYYY
     */
    @JsonProperty("currentDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "MM/dd/yyyy")
    private LocalDate currentDate;
    
    /**
     * Program name identifier displayed in screen header.
     * COBOL: PGMNAMEO PIC X(8) from COCRDLI.CPY line 314
     * Value: "COCRDLIC" (Card List COBOL program name)
     */
    @JsonProperty("programName")
    @Size(max = 8, message = "Program name cannot exceed 8 characters")
    private String programName;
    
    /**
     * Secondary screen title displayed in header.
     * COBOL: TITLE02O PIC X(40) from COCRDLI.CPY line 320
     * Typical value: "Select card for view or update"
     */
    @JsonProperty("title02")
    @Size(max = 40, message = "Title 02 cannot exceed 40 characters")
    private String title02;
    
    /**
     * Current system time displayed in screen header.
     * COBOL: CURTIMEO PIC X(8) from COCRDLI.CPY line 326
     * Format transformation: COBOL HHMMSS → Java LocalTime → JSON HH:MM:SS
     */
    @JsonProperty("currentTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    private LocalTime currentTime;
    
    /**
     * Page number displayed in screen header (1-based).
     * COBOL: PAGENOO PIC X(3) from COCRDLI.CPY line 332
     * Represents the logical page number shown to users (starts at 1).
     * Note: Distinct from zero-based currentPage used in Spring Data pagination.
     */
    @JsonProperty("pageNumber")
    @Min(value = 1, message = "Page number must be at least 1")
    private Integer pageNumber;
    
    // ==================== Filter Fields (User Input Context) ====================
    
    /**
     * Account identifier filter input by user for card search.
     * COBOL: ACCTSIDO PIC X(11) from COCRDLI.CPY line 338
     * When populated, restricts card list to cards associated with this account.
     * Maps to CARDAIX alternate index on CARDDAT VSAM file by account ID.
     */
    @JsonProperty("accountIdFilter")
    @Size(max = 11, message = "Account ID filter cannot exceed 11 characters")
    private String accountIdFilter;
    
    /**
     * Card identifier filter input by user for card search.
     * COBOL: CARDSIDO PIC X(16) from COCRDLI.CPY line 344
     * When populated, restricts card list to cards matching this card number.
     * Maps to CARDDAT VSAM file primary key card number field.
     */
    @JsonProperty("cardIdFilter")
    @Size(max = 16, message = "Card ID filter cannot exceed 16 characters")
    private String cardIdFilter;
    
    // ==================== Card List Data (7-Item Array) ====================
    
    /**
     * List of card items displayed on current page (maximum 7 cards per page).
     * 
     * COBOL Source: Repeating groups CRDSEL1O-7O, ACCTNO1O-7O, CRDNUM1O-7O, CRDSTS1O-7O
     * from COCRDLI.CPY lines 350-548 (7 groups of 4 fields each)
     * 
     * Transformation: COBOL fixed-size array of 7 items → Java List&lt;CardItemDTO&gt; (0-7 items)
     * 
     * Business rule from COCRDLIC.cbl: WS-MAX-SCREEN-LINES = 7 (line 177-178)
     * Preserves legacy pagination pattern: exactly 7 cards per page maximum for
     * consistent user experience matching 3270 terminal screen layout.
     */
    @JsonProperty("cards")
    @Valid
    @Size(max = 7, message = "Card list cannot exceed 7 items per page")
    private List<CardItemDTO> cards = new ArrayList<>();
    
    // ==================== Message Fields (User Feedback) ====================
    
    /**
     * Informational message displayed to guide user actions.
     * COBOL: INFOMSGO PIC X(45) from COCRDLI.CPY line 554
     * Typical values:
     * - "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD" (WS-INFORM-REC-ACTIONS)
     * - Empty when no specific action guidance needed
     */
    @JsonProperty("infoMessage")
    @Size(max = 45, message = "Info message cannot exceed 45 characters")
    private String infoMessage;
    
    /**
     * Error or status message displayed to user.
     * COBOL: ERRMSGO PIC X(78) from COCRDLI.CPY line 560
     * Typical error values from COCRDLIC.cbl:
     * - "PF03 PRESSED.EXITING" (WS-EXIT-MESSAGE)
     * - "NO RECORDS FOUND FOR THIS SEARCH CONDITION." (WS-NO-RECORDS-FOUND)
     * - "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE" (WS-MORE-THAN-1-ACTION)
     * - "INVALID ACTION CODE" (WS-INVALID-ACTION-CODE)
     * - File error messages with RESP/RESP2 codes
     */
    @JsonProperty("errorMessage")
    @Size(max = 78, message = "Error message cannot exceed 78 characters")
    private String errorMessage;
    
    // ==================== Modern Pagination Metadata (Spring Data Page pattern) ====================
    
    /**
     * Total number of pages available for current search criteria.
     * NOT present in COBOL - added for modern REST API pagination support.
     * Calculated from: totalElements / pageSize (rounded up)
     * Used by frontend to render pagination controls (e.g., "Page 1 of 4")
     */
    @JsonProperty("totalPages")
    @Min(value = 0, message = "Total pages cannot be negative")
    private Integer totalPages;
    
    /**
     * Total number of card records matching current search criteria across all pages.
     * NOT present in COBOL - added for modern REST API pagination support.
     * Provides complete result set size to frontend for display (e.g., "Showing 1-7 of 25 results")
     * Replaces COBOL logic that only tracked current page data in WS-FILE-DATA-ARRAY
     */
    @JsonProperty("totalElements")
    @Min(value = 0, message = "Total elements cannot be negative")
    private Long totalElements;
    
    /**
     * Current page index in zero-based pagination (Spring Data Page convention).
     * NOT present in COBOL - added for modern REST API pagination support.
     * Zero-based: 0 = first page, 1 = second page, etc.
     * 
     * Relationship with COBOL pageNumber:
     * - pageNumber (1-based, displayed to user): 1, 2, 3, 4
     * - currentPage (0-based, API/internal use): 0, 1, 2, 3
     * 
     * Used in next/previous page navigation:
     * - Next page URL: /api/cards?page={currentPage + 1}&size=7
     * - Previous page URL: /api/cards?page={currentPage - 1}&size=7
     */
    @JsonProperty("currentPage")
    @Min(value = 0, message = "Current page cannot be negative")
    private Integer currentPage;
    
    /**
     * Number of cards per page (fixed at 7 to match COBOL screen layout).
     * NOT present in COBOL - added for modern REST API pagination support.
     * 
     * Value: Always 7 per legacy business rule WS-MAX-SCREEN-LINES from COCRDLIC.cbl
     * While modern REST APIs often allow variable page size, this implementation
     * maintains fixed 7-card pagination to preserve exact screen behavior from mainframe.
     * 
     * Future enhancement: Could be made configurable while defaulting to 7 for backwards compatibility
     */
    @JsonProperty("pageSize")
    @Min(value = 1, message = "Page size must be at least 1")
    private Integer pageSize = 7;
    
    /**
     * Indicates if additional pages exist after the current page.
     * NOT present in COBOL - added for modern REST API pagination support.
     * 
     * Calculated as: currentPage < (totalPages - 1)
     * 
     * Used by frontend to:
     * - Enable/disable "Next Page" button in pagination controls
     * - Show ">" navigation indicator
     * - Display "More results available" message
     * 
     * COBOL equivalent: CA-NEXT-PAGE-EXISTS flag in WS-THIS-PROGCOMMAREA (COCRDLIC.cbl line 244)
     * but this field is more explicit for REST API consumers
     */
    @JsonProperty("hasNext")
    private Boolean hasNext;
    
    /**
     * Indicates if pages exist before the current page.
     * NOT present in COBOL - added for modern REST API pagination support.
     * 
     * Calculated as: currentPage > 0
     * 
     * Used by frontend to:
     * - Enable/disable "Previous Page" button in pagination controls
     * - Show "<" navigation indicator
     * - Determine if "First Page" navigation should be available
     * 
     * COBOL logic: Implicit in WS-CA-SCREEN-NUM > 1 check (COCRDLIC.cbl line 237-238)
     */
    @JsonProperty("hasPrevious")
    private Boolean hasPrevious;
    
    // ==================== Nested DTO for Card Item ====================
    
    /**
     * Inner DTO representing a single card item in the paginated card list.
     * 
     * <p>Transforms COBOL repeating groups CRDSEL1O-7O, ACCTNO1O-7O, CRDNUM1O-7O, CRDSTS1O-7O
     * from COCRDLI.CPY (7 occurrences) into a type-safe Java object for list element representation.</p>
     * 
     * <h3>COBOL Array Pattern Transformation</h3>
     * <pre>
     * COBOL Fixed Array (COCRDLI.CPY lines 350-548):
     *   02 CRDSEL1O  PIC X(1).   (Card 1 selection flag)
     *   02 ACCTNO1O  PIC X(11).  (Card 1 account number)
     *   02 CRDNUM1O  PIC X(16).  (Card 1 card number)
     *   02 CRDSTS1O  PIC X(1).   (Card 1 status)
     *   ... [repeats for cards 2-7]
     * 
     * Java Dynamic List:
     *   List&lt;CardItemDTO&gt; cards = [
     *     { selectionFlag: "S", accountNumber: "00000000001", cardNumber: "4111111111111111", cardStatus: "Y" },
     *     { selectionFlag: " ", accountNumber: "00000000002", cardNumber: "4222222222222222", cardStatus: "N" },
     *     ...
     *   ]
     * </pre>
     * 
     * <h3>Field Mapping</h3>
     * <table border="1">
     *   <tr><th>COBOL Field</th><th>Java Field</th><th>Purpose</th></tr>
     *   <tr><td>CRDSEL1O-7O</td><td>selectionFlag</td><td>User selection: 'S'=Select, 'U'=Update, ' '=None</td></tr>
     *   <tr><td>CRDSTP2O-7O</td><td>statusProtection</td><td>Field protection attribute (rows 2-7 only)</td></tr>
     *   <tr><td>ACCTNO1O-7O</td><td>accountNumber</td><td>Associated account identifier (11 digits)</td></tr>
     *   <tr><td>CRDNUM1O-7O</td><td>cardNumber</td><td>Card number (16 digits)</td></tr>
     *   <tr><td>CRDSTS1O-7O</td><td>cardStatus</td><td>Card status code: Y/N/E/B/C/P</td></tr>
     * </table>
     * 
     * <h3>Status Code Conversion</h3>
     * <p>The cardStatus field contains single-character codes that can be converted to CardStatus enum:
     * <ul>
     *   <li>'Y' → CardStatus.ACTIVE (card is active)</li>
     *   <li>'N' → CardStatus.INACTIVE (card is temporarily inactive)</li>
     *   <li>'E' → CardStatus.EXPIRED (card has expired)</li>
     *   <li>'B' → CardStatus.BLOCKED (card is blocked for security)</li>
     *   <li>'C' → CardStatus.CLOSED (card is permanently closed)</li>
     *   <li>'P' → CardStatus.PENDING (activation pending)</li>
     * </ul>
     * Conversion: CardStatus.fromCode(cardStatus.charAt(0))</p>
     * 
     * <h3>Selection Flag Values (from COCRDLIC.cbl lines 77-82)</h3>
     * <ul>
     *   <li>'S' - View card detail (SELECT-OK, VIEW-REQUESTED-ON)</li>
     *   <li>'U' - Update card information (SELECT-OK, UPDATE-REQUESTED-ON)</li>
     *   <li>' ' - No action selected (SELECT-BLANK)</li>
     * </ul>
     * 
     * @see CardStatus#fromCode(char)
     * @see CardListResponse#cards
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CardItemDTO implements Serializable {
        
        /**
         * Serialization version UID for nested DTO compatibility.
         */
        private static final long serialVersionUID = 1L;
        
        /**
         * User selection flag indicating requested action for this card.
         * COBOL: CRDSEL1O-7O PIC X(1) from COCRDLI.CPY (lines 350, 374, 404, 434, 464, 494, 524)
         * 
         * Valid values:
         * - 'S' = Select for detail view (navigate to COCRDSLC program)
         * - 'U' = Select for update (navigate to COCRDUPC program)
         * - ' ' = No action (blank or low-values)
         * 
         * Validation: Must be one of {'S', 'U', ' '} per WS-EDIT-SELECT logic in COCRDLIC.cbl
         */
        @JsonProperty("selectionFlag")
        @Size(max = 1, message = "Selection flag must be exactly 1 character")
        private String selectionFlag;
        
        /**
         * Status protection attribute for field display control.
         * COBOL: CRDSTP2O-7O PIC X(1) from COCRDLI.CPY (lines 380, 410, 440, 470, 500, 530)
         * 
         * Note: This field appears only for rows 2-7 in COBOL (no CRDSTP1O for row 1)
         * Represents BMS field protection attribute controlling field editability on 3270 screen.
         * 
         * In modern REST API context, this may be used for:
         * - Conditional field rendering in React frontend
         * - Field enable/disable state based on user permissions
         * - UI hint for field presentation (read-only vs editable)
         */
        @JsonProperty("statusProtection")
        @Size(max = 1, message = "Status protection must be exactly 1 character")
        private String statusProtection;
        
        /**
         * Account identifier associated with this card.
         * COBOL: ACCTNO1O-7O PIC X(11) from COCRDLI.CPY (lines 356, 386, 416, 446, 476, 506, 536)
         * 
         * Foreign key reference to ACCTDAT VSAM file (PostgreSQL account table).
         * Format: 11-digit account number (may have leading zeros)
         * 
         * Business rule: Each card is associated with exactly one account
         * Database: account.account_id column (NUMERIC(11,0))
         */
        @JsonProperty("accountNumber")
        @Size(max = 11, message = "Account number cannot exceed 11 characters")
        private String accountNumber;
        
        /**
         * Unique card number (primary identifier for card).
         * COBOL: CRDNUM1O-7O PIC X(16) from COCRDLI.CPY (lines 362, 392, 422, 452, 482, 512, 542)
         * 
         * Primary key from CARDDAT VSAM file (PostgreSQL card table).
         * Format: 16-digit card number conforming to payment network standards (e.g., Visa, MasterCard)
         * 
         * Security consideration: Real card numbers should be masked in production
         * (e.g., display only last 4 digits: "************1111")
         */
        @JsonProperty("cardNumber")
        @Size(max = 16, message = "Card number cannot exceed 16 characters")
        private String cardNumber;
        
        /**
         * Current status of the card as single-character code.
         * COBOL: CRDSTS1O-7O PIC X(1) from COCRDLI.CPY (lines 368, 398, 428, 458, 488, 518, 548)
         * 
         * Valid status codes (convertible to CardStatus enum via fromCode(char)):
         * - 'Y' = Active (CARD-ACTIVE from COBOL 88-level)
         * - 'N' = Inactive (CARD-INACTIVE from COBOL 88-level)
         * - 'E' = Expired
         * - 'B' = Blocked
         * - 'C' = Closed
         * - 'P' = Pending activation
         * 
         * Conversion example:
         * CardStatus status = CardStatus.fromCode(cardItemDTO.getCardStatus().charAt(0));
         * 
         * Display value: Use status.getDisplayName() for user-friendly presentation
         * (e.g., 'Y' → "Active", 'E' → "Expired")
         * 
         * @see CardStatus#fromCode(char)
         * @see CardStatus#getDisplayName()
         */
        @JsonProperty("cardStatus")
        @Size(min = 1, max = 1, message = "Card status must be exactly 1 character")
        private String cardStatus;
    }
}




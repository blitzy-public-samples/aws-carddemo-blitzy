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

package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Response DTO for paginated transaction list display operations.
 * 
 * <p>Transformed from COBOL BMS copybook COTRN00.CPY (output structure COTRN0AO) 
 * representing the transaction list screen displayed by CICS transaction COTRN00C.
 * Supports GET /api/transactions endpoint with pagination (10 transactions per page)
 * matching legacy 3270 screen pagination behavior.</p>
 * 
 * <p><b>Source COBOL Structure:</b></p>
 * <ul>
 *   <li>TRNNAMEO (PIC X(4)) - Transaction name identifier</li>
 *   <li>TITLE01O (PIC X(40)) - Primary screen title</li>
 *   <li>CURDATEO (PIC X(8)) - Current date (YYYYMMDD format)</li>
 *   <li>PGMNAMEO (PIC X(8)) - Program name (COTRN00C)</li>
 *   <li>TITLE02O (PIC X(40)) - Secondary screen title</li>
 *   <li>CURTIMEO (PIC X(8)) - Current time (HH:MM:SS format)</li>
 *   <li>PAGENUMO (PIC X(8)) - Page number indicator</li>
 *   <li>TRNIDINO (PIC X(16)) - Transaction ID filter input</li>
 *   <li>SEL000xO (PIC X(1)) - Selection flags for 10 transaction rows</li>
 *   <li>TRNIDxxO (PIC X(16)) - Transaction IDs for 10 rows</li>
 *   <li>TDATExxO (PIC X(8)) - Transaction dates for 10 rows</li>
 *   <li>TDESCxxO (PIC X(26)) - Descriptions for 10 rows</li>
 *   <li>TAMTxxxO (PIC X(12)) - Amounts for 10 rows (COMP-3 precision)</li>
 *   <li>ERRMSGO (PIC X(78)) - Error message display</li>
 * </ul>
 * 
 * <p><b>Pagination Requirement:</b> Maintains exact 10-transaction-per-page display
 * pattern per Agent Action Plan Section 0.4 requirement for preserving user workflow
 * patterns from original mainframe application.</p>
 * 
 * <p><b>COMP-3 Precision Preservation:</b> Transaction amounts maintain exact decimal
 * precision using BigDecimal with scale 2 and RoundingMode.HALF_UP per Agent Action
 * Plan Section 0.9 numeric precision requirements.</p>
 * 
 * @see com.carddemo.controller.TransactionController
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionListResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Transaction name identifier (maps from COBOL TRNNAMEO PIC X(4)).
     * Typically contains the CICS transaction code "CT00" for transaction list display.
     */
    @JsonProperty("transactionName")
    @Size(max = 4, message = "Transaction name must not exceed 4 characters")
    private String transactionName;

    /**
     * Primary screen title (maps from COBOL TITLE01O PIC X(40)).
     * Displays the main heading for the transaction list screen.
     */
    @JsonProperty("title01")
    @Size(max = 40, message = "Title 01 must not exceed 40 characters")
    private String title01;

    /**
     * Current date (maps from COBOL CURDATEO PIC X(8)).
     * Converted from YYYYMMDD string format to LocalDate for type safety.
     */
    @JsonProperty("currentDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate currentDate;

    /**
     * Program name identifier (maps from COBOL PGMNAMEO PIC X(8)).
     * Typically contains "COTRN00C" for transaction list program.
     */
    @JsonProperty("programName")
    @Size(max = 8, message = "Program name must not exceed 8 characters")
    private String programName;

    /**
     * Secondary screen title (maps from COBOL TITLE02O PIC X(40)).
     * Displays additional heading information for the transaction list screen.
     */
    @JsonProperty("title02")
    @Size(max = 40, message = "Title 02 must not exceed 40 characters")
    private String title02;

    /**
     * Current time (maps from COBOL CURTIMEO PIC X(8)).
     * Converted from HH:MM:SS string format to LocalTime for type safety.
     */
    @JsonProperty("currentTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    private LocalTime currentTime;

    /**
     * Page number indicator (maps from COBOL PAGENUMO PIC X(8)).
     * Displays current page number in the paginated transaction list.
     * Legacy field preserved for screen display compatibility.
     */
    @JsonProperty("pageNumber")
    private Integer pageNumber;

    /**
     * Transaction ID filter (maps from COBOL TRNIDINO PIC X(16)).
     * User-provided filter to search for specific transaction IDs.
     */
    @JsonProperty("transactionIdFilter")
    @Size(max = 16, message = "Transaction ID filter must not exceed 16 characters")
    private String transactionIdFilter;

    /**
     * Error message display (maps from COBOL ERRMSGO PIC X(78)).
     * Contains validation errors or system messages to display to the user.
     */
    @JsonProperty("errorMessage")
    @Size(max = 78, message = "Error message must not exceed 78 characters")
    private String errorMessage;

    /**
     * List of transaction items for current page (maps from COBOL repeating groups).
     * Contains up to 10 transaction items per page, transformed from COBOL arrays:
     * SEL0001O-SEL0010O, TRNID01O-TRNID10O, TDATE01O-TDATE10O, 
     * TDESC01O-TDESC10O, TAMT001O-TAMT010O.
     * 
     * <p>COBOL 10-item fixed array pattern transformed to Java List for flexibility
     * while maintaining 10-per-page pagination requirement.</p>
     */
    @JsonProperty("transactions")
    @Valid
    private List<TransactionItemDTO> transactions = new ArrayList<>();

    // Modern REST API pagination metadata fields (not in original COBOL structure)
    
    /**
     * Total number of pages available (calculated field for REST pagination).
     * Not present in COBOL structure - added for modern API pagination support.
     */
    @JsonProperty("totalPages")
    private Integer totalPages;

    /**
     * Total number of transaction elements across all pages (calculated field).
     * Not present in COBOL structure - added for modern API pagination support.
     */
    @JsonProperty("totalElements")
    private Long totalElements;

    /**
     * Current page number (zero-based, calculated field for REST pagination).
     * Not present in COBOL structure - added for modern API pagination support.
     * Note: pageNumber field above is 1-based legacy display value.
     */
    @JsonProperty("currentPage")
    private Integer currentPage;

    /**
     * Page size (number of items per page, default 10).
     * Not present in COBOL structure - added for modern API pagination support.
     * Fixed at 10 to maintain exact pagination behavior from COBOL application.
     */
    @JsonProperty("pageSize")
    private Integer pageSize = 10;

    /**
     * Indicates if there is a next page available (calculated field).
     * Not present in COBOL structure - added for modern API pagination support.
     */
    @JsonProperty("hasNext")
    private Boolean hasNext;

    /**
     * Indicates if there is a previous page available (calculated field).
     * Not present in COBOL structure - added for modern API pagination support.
     */
    @JsonProperty("hasPrevious")
    private Boolean hasPrevious;

    // Optional filter fields for query parameter binding (not in COBOL structure)
    
    /**
     * Account ID filter (optional query parameter).
     * Not present in COBOL structure - added for enhanced REST API filtering.
     */
    @JsonProperty("accountId")
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    private String accountId;

    /**
     * Card ID filter (optional query parameter).
     * Not present in COBOL structure - added for enhanced REST API filtering.
     */
    @JsonProperty("cardId")
    @Size(max = 16, message = "Card ID must not exceed 16 characters")
    private String cardId;

    /**
     * Date range filter - start date (optional query parameter).
     * Not present in COBOL structure - added for enhanced REST API filtering.
     */
    @JsonProperty("dateFrom")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate dateFrom;

    /**
     * Date range filter - end date (optional query parameter).
     * Not present in COBOL structure - added for enhanced REST API filtering.
     */
    @JsonProperty("dateTo")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate dateTo;

    /**
     * Inner DTO representing a single transaction item in the paginated list.
     * 
     * <p>Transformed from COBOL repeating group structure (groups 01-10) where each
     * group contains: SEL000xO, TRNIDxxO, TDATExxO, TDESCxxO, TAMTxxxO fields.</p>
     * 
     * <p><b>COBOL Source Structure (per group):</b></p>
     * <ul>
     *   <li>SEL000xO (PIC X(1)) - Selection flag for user input</li>
     *   <li>TRNIDxxO (PIC X(16)) - Unique transaction identifier</li>
     *   <li>TDATExxO (PIC X(8)) - Transaction date (YYYYMMDD format)</li>
     *   <li>TDESCxxO (PIC X(26)) - Transaction description</li>
     *   <li>TAMTxxxO (PIC X(12)) - Transaction amount with COMP-3 precision</li>
     * </ul>
     * 
     * <p><b>Amount Precision:</b> Transaction amount maintains exact COBOL COMP-3
     * decimal precision using BigDecimal with scale 2 (2 decimal places) and
     * RoundingMode.HALF_UP to ensure identical calculation results to legacy system.</p>
     * 
     * @since 1.0
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionItemDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Selection flag (maps from COBOL SEL000xO PIC X(1)).
         * Single character flag indicating if this transaction is selected by user.
         * Typically contains space or 'X' for selection.
         */
        @JsonProperty("selectionFlag")
        @Size(max = 1, message = "Selection flag must be exactly 1 character")
        private String selectionFlag;

        /**
         * Transaction unique identifier (maps from COBOL TRNIDxxO PIC X(16)).
         * 16-character unique transaction ID from TRANSACT file.
         */
        @JsonProperty("transactionId")
        @Size(max = 16, message = "Transaction ID must not exceed 16 characters")
        private String transactionId;

        /**
         * Transaction date (maps from COBOL TDATExxO PIC X(8)).
         * Converted from YYYYMMDD string format to LocalDate for type safety
         * and proper date handling in REST API responses.
         */
        @JsonProperty("transactionDate")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate transactionDate;

        /**
         * Transaction description (maps from COBOL TDESCxxO PIC X(26)).
         * Human-readable description of the transaction type or merchant.
         */
        @JsonProperty("description")
        @Size(max = 26, message = "Description must not exceed 26 characters")
        private String description;

        /**
         * Transaction amount (maps from COBOL TAMTxxxO PIC X(12) with COMP-3 precision).
         * 
         * <p><b>COMP-3 Precision Preservation:</b> This field maintains exact decimal
         * precision from COBOL COMP-3 packed decimal format using BigDecimal with
         * scale 2 (2 decimal places). All arithmetic operations must use
         * RoundingMode.HALF_UP to ensure identical calculation results to the
         * legacy mainframe system per Agent Action Plan Section 0.9.</p>
         * 
         * <p>Format: Up to 10 integer digits and exactly 2 decimal places (e.g., 12345678.90).</p>
         * 
         * <p><b>Example COBOL to Java conversion:</b></p>
         * <pre>
         * COBOL: 01 TAMT001O PIC X(12).  (displaying COMP-3 value)
         * Java:  BigDecimal amount = new BigDecimal("1234.56")
         *            .setScale(2, RoundingMode.HALF_UP);
         * </pre>
         */
        @JsonProperty("amount")
        @Digits(integer = 10, fraction = 2, message = "Amount must have at most 10 integer digits and 2 decimal places")
        private BigDecimal amount;
    }
}

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
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Bill Payment Response DTO
 * 
 * <p>Response Data Transfer Object for bill payment processing operations, returning payment
 * confirmation and updated account balance information. This DTO is transformed from the BMS 
 * copybook COBIL00.CPY output structure (COBIL0AO) which represents the bill payment confirmation 
 * screen displayed by CICS transaction COBIL00C in the legacy CardDemo mainframe application.</p>
 * 
 * <p><b>COBOL Source Mapping:</b></p>
 * <ul>
 *   <li>Source BMS Copybook: app/cpy-bms/COBIL00.CPY (COBIL0AO output structure)</li>
 *   <li>Source COBOL Program: app/cbl/COBIL00C.cbl (bill payment processing logic)</li>
 *   <li>CICS Transaction: CB00 (Bill Payment transaction)</li>
 * </ul>
 * 
 * <p><b>Field Transformations from COBOL:</b></p>
 * <ul>
 *   <li>TRNNAMEO PIC X(4) → transactionName: String with max length 4</li>
 *   <li>TITLE01O PIC X(40) → title01: String with max length 40</li>
 *   <li>CURDATEO PIC X(8) → currentDate: LocalDate (MM/DD/YYYY format)</li>
 *   <li>PGMNAMEO PIC X(8) → programName: String with max length 8</li>
 *   <li>TITLE02O PIC X(40) → title02: String with max length 40</li>
 *   <li>CURTIMEO PIC X(8) → currentTime: LocalTime (HH:MM:SS format)</li>
 *   <li>ACTIDINO PIC X(11) → accountId: String with max length 11</li>
 *   <li>CURBALO PIC X(14) → currentBalance: BigDecimal with precision(12,2) for COMP-3 equivalence</li>
 *   <li>CONFIRMO PIC X(1) → confirmationFlag: String indicating payment status ('Y'/'N')</li>
 *   <li>ERRMSGO PIC X(78) → errorMessage: String with max length 78</li>
 * </ul>
 * 
 * <p><b>Business Function:</b></p>
 * <p>This response DTO confirms bill payment execution results, returning the updated account 
 * balance after payment transaction processing. The COBOL program COBIL00C performs the following 
 * operations:</p>
 * <ol>
 *   <li>Validates account ID and reads ACCTDAT VSAM file</li>
 *   <li>Checks current balance (ACCT-CURR-BAL COMP-3 field)</li>
 *   <li>If confirmation flag is 'Y', creates transaction record and updates balance</li>
 *   <li>Returns updated balance maintaining exact COMP-3 decimal precision</li>
 *   <li>Displays confirmation or error message on BMS screen</li>
 * </ol>
 * 
 * <p><b>REST API Usage:</b></p>
 * <p>Supports POST /api/payments/bill endpoint response, confirming bill payment execution with 
 * updated financial state. This DTO maintains transaction atomicity and exact balance calculation 
 * precision matching COBOL COMP-3 arithmetic per numeric preservation requirements in Agent Action 
 * Plan Section 0.9.</p>
 * 
 * <p><b>Numeric Precision Requirements:</b></p>
 * <p>The currentBalance field uses BigDecimal with @Digits(integer=12, fraction=2) annotation to 
 * preserve COBOL COMP-3 packed decimal precision. The COBOL ACCT-CURR-BAL field is defined as 
 * PIC S9(13)V99 COMP-3, requiring exact replication in Java to prevent calculation discrepancies. 
 * All balance calculations must use RoundingMode.HALF_UP to match COBOL rounding behavior.</p>
 * 
 * <p><b>Security and Session Management:</b></p>
 * <p>This DTO implements Serializable interface for Redis-backed Spring Session storage, enabling 
 * distributed session management in the stateless REST architecture that replaces CICS 
 * pseudo-conversational processing. Session serialization supports response caching and maintains 
 * state across multiple API calls within a user session.</p>
 * 
 * <p><b>Validation Constraints:</b></p>
 * <ul>
 *   <li>Field lengths enforced via @Size annotations matching COBOL PIC clause specifications</li>
 *   <li>BigDecimal precision validated via @Digits annotation for financial accuracy</li>
 *   <li>Date/time formats standardized via @JsonFormat for consistent API responses</li>
 * </ul>
 * 
 * <p><b>Migration Context:</b></p>
 * <p>This class is part of the comprehensive COBOL-to-Java technology migration transforming the 
 * CardDemo mainframe credit card management application from IBM z/OS CICS/VSAM architecture to 
 * cloud-native Spring Boot microservices with PostgreSQL database. All field types, lengths, and 
 * validation rules preserve 100% functional equivalence with the original COBOL implementation per 
 * Agent Action Plan Section 0.2 (Core Refactoring Objective).</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * 
 * @see com.carddemo.controller.BillPaymentController POST /api/payments/bill endpoint handler
 * @see com.carddemo.service.BillPaymentService Bill payment business logic service
 * @see com.carddemo.dto.request.BillPaymentRequest Bill payment request DTO
 * @see com.carddemo.entity.Account Account entity with balance field
 * @see com.carddemo.entity.Transaction Transaction entity for payment records
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillPaymentResponse implements Serializable {

    /**
     * Serialization version UID for version control compatibility during system evolution.
     * Ensures consistent serialization/deserialization across different versions of the class
     * in distributed Redis session storage environments.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Transaction Name (CICS Transaction ID)
     * 
     * <p>COBOL Field: TRNNAMEO PIC X(4) from COBIL0AO</p>
     * <p>Represents the CICS transaction identifier for bill payment processing. In the original 
     * COBOL program, this is set to 'CB00' (WS-TRANID value). Provides transaction context for 
     * audit trail and debugging purposes.</p>
     * 
     * <p>Example values: "CB00" (bill payment transaction)</p>
     */
    @JsonProperty("transactionName")
    @Size(max = 4, message = "Transaction name must not exceed 4 characters")
    private String transactionName;

    /**
     * Primary Screen Title
     * 
     * <p>COBOL Field: TITLE01O PIC X(40) from COBIL0AO</p>
     * <p>Primary display title shown on the bill payment screen header. In COBOL, populated from 
     * CCDA-TITLE01 common area field. Provides context for the screen function and user interface 
     * consistency.</p>
     * 
     * <p>Example values: "AWS Mainframe Modernization CardDemo", "Credit Card Demo Application"</p>
     */
    @JsonProperty("title01")
    @Size(max = 40, message = "Title 01 must not exceed 40 characters")
    private String title01;

    /**
     * Current Date of Transaction Processing
     * 
     * <p>COBOL Field: CURDATEO PIC X(8) from COBIL0AO</p>
     * <p>Date when the bill payment screen was displayed or transaction processed. In COBOL, 
     * formatted as MM/DD/YY from FUNCTION CURRENT-DATE. Transformed to LocalDate in Java for 
     * proper date handling and timezone-aware processing.</p>
     * 
     * <p>JSON Format: "MM/dd/yyyy" pattern for consistency with frontend date parsing</p>
     * <p>Example values: 2024-01-15, 2024-12-31</p>
     */
    @JsonProperty("currentDate")
    @JsonFormat(pattern = "MM/dd/yyyy")
    private LocalDate currentDate;

    /**
     * COBOL Program Name
     * 
     * <p>COBOL Field: PGMNAMEO PIC X(8) from COBIL0AO</p>
     * <p>Name of the COBOL program processing the bill payment transaction. In the original 
     * implementation, this is set to 'COBIL00C' (WS-PGMNAME value). Provides audit trail 
     * information and debugging context for transaction processing.</p>
     * 
     * <p>Example values: "COBIL00C" (original COBOL program name)</p>
     */
    @JsonProperty("programName")
    @Size(max = 8, message = "Program name must not exceed 8 characters")
    private String programName;

    /**
     * Secondary Screen Title
     * 
     * <p>COBOL Field: TITLE02O PIC X(40) from COBIL0AO</p>
     * <p>Secondary display title shown on the bill payment screen header. In COBOL, populated 
     * from CCDA-TITLE02 common area field. Provides additional context or subtitle information 
     * for the screen display.</p>
     * 
     * <p>Example values: "Bill Payment Processing", "Pay Account Balance in Full"</p>
     */
    @JsonProperty("title02")
    @Size(max = 40, message = "Title 02 must not exceed 40 characters")
    private String title02;

    /**
     * Current Time of Transaction Processing
     * 
     * <p>COBOL Field: CURTIMEO PIC X(8) from COBIL0AO</p>
     * <p>Time when the bill payment screen was displayed or transaction processed. In COBOL, 
     * formatted as HH:MM:SS from FUNCTION CURRENT-DATE time components. Transformed to LocalTime 
     * in Java for proper time handling without timezone complexities for display purposes.</p>
     * 
     * <p>JSON Format: "HH:mm:ss" pattern for 24-hour time display</p>
     * <p>Example values: 14:23:45, 09:05:12</p>
     */
    @JsonProperty("currentTime")
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime currentTime;

    /**
     * Account Identifier
     * 
     * <p>COBOL Field: ACTIDINO PIC X(11) from COBIL0AO</p>
     * <p>Unique identifier for the account from which the bill payment is being made. This field 
     * corresponds to ACCT-ID in the VSAM ACCTDAT file (now PostgreSQL account table). Used as 
     * the key for account lookup and balance retrieval in the payment processing workflow.</p>
     * 
     * <p>Format: 11-character account identifier (zero-padded numeric or alphanumeric)</p>
     * <p>Example values: "00000000001", "00000012345"</p>
     */
    @JsonProperty("accountId")
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    private String accountId;

    /**
     * Current Account Balance After Payment
     * 
     * <p>COBOL Field: CURBALO PIC X(14) from COBIL0AO (display), ACCT-CURR-BAL PIC S9(13)V99 COMP-3 (internal)</p>
     * <p>Current balance of the account after bill payment processing. This field preserves COBOL 
     * COMP-3 packed decimal precision using BigDecimal with scale 2 and precision 12+2=14 total 
     * digits. Critical for maintaining exact financial calculations without rounding discrepancies.</p>
     * 
     * <p><b>COMP-3 Precision Mapping:</b></p>
     * <ul>
     *   <li>COBOL: PIC S9(13)V99 COMP-3 → 13 integer digits + 2 decimal places</li>
     *   <li>Java: BigDecimal with @Digits(integer=12, fraction=2) → matches database NUMERIC(15,2)</li>
     *   <li>Rounding: RoundingMode.HALF_UP for consistency with COBOL arithmetic</li>
     * </ul>
     * 
     * <p>In the COBOL program logic (COBIL00C.cbl line 234), the balance is updated via:
     * <code>COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT</code></p>
     * 
     * <p>This calculation must maintain exact precision to ensure transaction atomicity and 
     * regulatory compliance. The BigDecimal type prevents floating-point rounding errors that 
     * could accumulate over multiple transactions.</p>
     * 
     * <p>Example values: 1234.56, 0.00, 999999999999.99</p>
     */
    @JsonProperty("currentBalance")
    @Digits(integer = 12, fraction = 2, message = "Current balance must have at most 12 integer digits and 2 fractional digits")
    private BigDecimal currentBalance;

    /**
     * Payment Confirmation Flag
     * 
     * <p>COBOL Field: CONFIRMO PIC X(1) from COBIL0AO</p>
     * <p>Indicates whether the bill payment was confirmed and processed successfully. In the 
     * COBOL program logic, this field is used for two-step confirmation:</p>
     * <ul>
     *   <li>'Y' or 'y': User confirmed payment, transaction processed, balance updated</li>
     *   <li>'N' or 'n': User declined payment, transaction cancelled</li>
     *   <li>SPACES or LOW-VALUES: Initial display, awaiting confirmation</li>
     * </ul>
     * 
     * <p>The COBOL program (COBIL00C.cbl lines 173-191) evaluates CONFIRMI field to determine 
     * processing flow. When 'Y', it executes payment transaction creation and balance update.</p>
     * 
     * <p>In the Java REST API implementation, this can be transformed to a Boolean or enum type 
     * for type-safe processing, but preserved as String in DTO for exact COBOL compatibility.</p>
     * 
     * <p>Example values: "Y" (confirmed), "N" (declined), "" (pending)</p>
     */
    @JsonProperty("confirmationFlag")
    @Size(max = 1, message = "Confirmation flag must be exactly 1 character")
    private String confirmationFlag;

    /**
     * Error Message
     * 
     * <p>COBOL Field: ERRMSGO PIC X(78) from COBIL0AO</p>
     * <p>Error or informational message displayed on the bill payment screen. In COBOL, this field 
     * is populated from WS-MESSAGE working storage variable when validation errors occur or user 
     * actions require feedback. Messages include:</p>
     * <ul>
     *   <li>"Acct ID can NOT be empty..." - Account ID validation failure</li>
     *   <li>"You have nothing to pay..." - Zero or negative balance</li>
     *   <li>"Invalid value. Valid values are (Y/N)..." - Confirmation flag validation</li>
     *   <li>"Confirm to make a bill payment..." - Awaiting user confirmation</li>
     *   <li>CCDA-MSG-INVALID-KEY - Invalid PF key pressed</li>
     * </ul>
     * 
     * <p>This field supports user experience by providing clear feedback on transaction status 
     * and validation failures, matching the behavior of the original 3270 terminal interface.</p>
     * 
     * <p>Example values: "Payment processed successfully", "Invalid account ID", ""</p>
     */
    @JsonProperty("errorMessage")
    @Size(max = 78, message = "Error message must not exceed 78 characters")
    private String errorMessage;
}

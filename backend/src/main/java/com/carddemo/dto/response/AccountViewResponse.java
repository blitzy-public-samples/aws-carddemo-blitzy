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
 * Response DTO for account details view operations.
 * 
 * <p>Transformed from BMS copybook COACTVW.CPY (CACTVWAO output structure) 
 * representing the account view screen displayed by CICS transaction COACTVWC.
 * Contains comprehensive account profile including account identifier, status, 
 * dates, financial limits and balances, customer demographics, mailing address, 
 * and contact information.</p>
 * 
 * <p><b>COBOL Source Mapping:</b></p>
 * <ul>
 *   <li>Source Copybook: app/cpy-bms/COACTVW.CPY</li>
 *   <li>Source Program: app/cbl/COACTVWC.cbl</li>
 *   <li>CICS Transaction: CAVW</li>
 *   <li>Original Structure: CACTVWAO (Output fields)</li>
 * </ul>
 * 
 * <p><b>Financial Precision Requirements:</b></p>
 * <p>All financial amounts (creditLimit, cashLimit, currentBalance, cycleCreditTotal, 
 * cycleDebitTotal) preserve COBOL COMP-3 decimal precision using BigDecimal with 
 * precision (15,2) matching COBOL PIC +ZZZ,ZZZ,ZZZ.99 semantics per Section 0.2 
 * and Section 0.9 requirements to prevent calculation discrepancies.</p>
 * 
 * <p><b>Security and PII Handling:</b></p>
 * <p>Customer SSN field contains sensitive PII data. Implementations consuming this 
 * DTO should apply masking for display purposes and restrict access based on role 
 * authorization per Section 0.9 data integrity requirements.</p>
 * 
 * <p>Supports GET /api/accounts/{id} endpoint for account detail retrieval with 
 * full data presentation matching legacy 3270 COACTVW screen layout.</p>
 * 
 * @see com.carddemo.service.AccountViewService
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountViewResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Transaction name identifier (COBOL: TRNNAMEO PIC X(4)).
     * Represents the CICS transaction code that generated this view.
     */
    @JsonProperty("transactionName")
    @Size(max = 4, message = "Transaction name must not exceed 4 characters")
    private String transactionName;
    
    /**
     * Screen title line 1 (COBOL: TITLE01O PIC X(40)).
     * Primary header text displayed at top of account view screen.
     */
    @JsonProperty("title01")
    @Size(max = 40, message = "Title01 must not exceed 40 characters")
    private String title01;
    
    /**
     * Screen title line 2 (COBOL: TITLE02O PIC X(40)).
     * Secondary header text for additional screen context.
     */
    @JsonProperty("title02")
    @Size(max = 40, message = "Title02 must not exceed 40 characters")
    private String title02;
    
    /**
     * Current system date (COBOL: CURDATEO PIC X(8)).
     * Date when the account view was generated, formatted as LocalDate.
     * Original COBOL format: YYYYMMDD converted to ISO-8601 date.
     */
    @JsonProperty("currentDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate currentDate;
    
    /**
     * Current system time (COBOL: CURTIMEO PIC X(8)).
     * Time when the account view was generated, formatted as LocalTime.
     * Original COBOL format: HHMMSS converted to ISO-8601 time.
     */
    @JsonProperty("currentTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    private LocalTime currentTime;
    
    /**
     * Program name identifier (COBOL: PGMNAMEO PIC X(8)).
     * Name of the COBOL program (COACTVWC) that generated this response.
     */
    @JsonProperty("programName")
    @Size(max = 8, message = "Program name must not exceed 8 characters")
    private String programName;
    
    /**
     * Account identifier (COBOL: ACCTSIDO PIC X(11)).
     * Unique 11-digit account number, primary key for account records.
     * Corresponds to ACCT-ID in ACCTDAT VSAM file.
     */
    @JsonProperty("accountId")
    @Size(min = 11, max = 11, message = "Account ID must be exactly 11 characters")
    private String accountId;
    
    /**
     * Account status code (COBOL: ACSTTUSO PIC X(1)).
     * Single character code indicating account operational status.
     * Typical values: 'A' (Active), 'C' (Closed), 'S' (Suspended).
     */
    @JsonProperty("accountStatus")
    @Size(max = 1, message = "Account status must be exactly 1 character")
    private String accountStatus;
    
    /**
     * Account opening date (COBOL: ADTOPENO PIC X(10)).
     * Date when the account was originally opened.
     * Format converted from COBOL date to LocalDate ISO-8601.
     */
    @JsonProperty("dateOpened")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate dateOpened;
    
    /**
     * Account expiry date (COBOL: AEXPDTO PIC X(10)).
     * Date when the account is scheduled to expire or be reviewed.
     * Format converted from COBOL date to LocalDate ISO-8601.
     */
    @JsonProperty("expiryDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate expiryDate;
    
    /**
     * Card reissue date (COBOL: AREISDTO PIC X(10)).
     * Date when associated cards were last reissued.
     * Format converted from COBOL date to LocalDate ISO-8601.
     */
    @JsonProperty("reissueDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate reissueDate;
    
    /**
     * Credit limit amount (COBOL: ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99).
     * Maximum credit amount authorized for this account.
     * Preserves COMP-3 precision with BigDecimal(15,2) matching COBOL semantics.
     */
    @JsonProperty("creditLimit")
    @Digits(integer = 13, fraction = 2, message = "Credit limit must have at most 13 integer digits and 2 decimal places")
    private BigDecimal creditLimit;
    
    /**
     * Cash advance limit (COBOL: ACSHLIMO PIC +ZZZ,ZZZ,ZZZ.99).
     * Maximum cash advance amount authorized for this account.
     * Preserves COMP-3 precision with BigDecimal(15,2) matching COBOL semantics.
     */
    @JsonProperty("cashLimit")
    @Digits(integer = 13, fraction = 2, message = "Cash limit must have at most 13 integer digits and 2 decimal places")
    private BigDecimal cashLimit;
    
    /**
     * Current account balance (COBOL: ACURBALO PIC +ZZZ,ZZZ,ZZZ.99).
     * Current outstanding balance including all posted transactions.
     * Preserves COMP-3 precision with BigDecimal(15,2) matching COBOL semantics.
     * Critical field for financial calculations per Section 0.9 precision requirements.
     */
    @JsonProperty("currentBalance")
    @Digits(integer = 13, fraction = 2, message = "Current balance must have at most 13 integer digits and 2 decimal places")
    private BigDecimal currentBalance;
    
    /**
     * Cycle credit total (COBOL: ACRCYCRO PIC +ZZZ,ZZZ,ZZZ.99).
     * Total credits posted during current billing cycle.
     * Preserves COMP-3 precision with BigDecimal(15,2) matching COBOL semantics.
     */
    @JsonProperty("cycleCreditTotal")
    @Digits(integer = 13, fraction = 2, message = "Cycle credit total must have at most 13 integer digits and 2 decimal places")
    private BigDecimal cycleCreditTotal;
    
    /**
     * Cycle debit total (COBOL: ACRCYDBO PIC +ZZZ,ZZZ,ZZZ.99).
     * Total debits posted during current billing cycle.
     * Preserves COMP-3 precision with BigDecimal(15,2) matching COBOL semantics.
     */
    @JsonProperty("cycleDebitTotal")
    @Digits(integer = 13, fraction = 2, message = "Cycle debit total must have at most 13 integer digits and 2 decimal places")
    private BigDecimal cycleDebitTotal;
    
    /**
     * Account group code (COBOL: AADDGRPO PIC X(10)).
     * Classification code grouping accounts for reporting and processing.
     * Links to ACCTDAT ACCT-GROUP-ID field.
     */
    @JsonProperty("accountGroupCode")
    @Size(max = 10, message = "Account group code must not exceed 10 characters")
    private String accountGroupCode;
    
    /**
     * Customer number (COBOL: ACSTNUMO PIC X(9)).
     * Unique 9-digit customer identifier, foreign key to CUSTDAT.
     * Links account to customer master record.
     */
    @JsonProperty("customerNumber")
    @Size(min = 9, max = 9, message = "Customer number must be exactly 9 characters")
    private String customerNumber;
    
    /**
     * Customer Social Security Number (COBOL: ACSTSSNO PIC X(12)).
     * Sensitive PII field containing customer SSN.
     * <b>Security Note:</b> Should be masked in UI displays (e.g., XXX-XX-1234).
     * Access restricted per role-based authorization requirements.
     * Format: NNN-NN-NNNN (12 characters including dashes).
     */
    @JsonProperty("customerSSN")
    @Size(max = 12, message = "Customer SSN must not exceed 12 characters")
    private String customerSSN;
    
    /**
     * Customer date of birth (COBOL: ACSTDOBO PIC X(10)).
     * Customer's birth date for identity verification and age validation.
     * Format converted from COBOL date to LocalDate ISO-8601.
     */
    @JsonProperty("customerDOB")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate customerDOB;
    
    /**
     * Customer FICO credit score (COBOL: ACSTFCOO PIC X(3)).
     * Three-digit credit score (300-850 range typical).
     * Used for credit risk assessment and limit determination.
     */
    @JsonProperty("customerFICO")
    @Size(max = 3, message = "Customer FICO score must not exceed 3 characters")
    private String customerFICO;
    
    /**
     * Customer first name (COBOL: ACSFNAMO PIC X(25)).
     * First name from customer master record.
     */
    @JsonProperty("firstName")
    @Size(max = 25, message = "First name must not exceed 25 characters")
    private String firstName;
    
    /**
     * Customer middle name (COBOL: ACSMNAMO PIC X(25)).
     * Middle name or middle initial from customer master record.
     */
    @JsonProperty("middleName")
    @Size(max = 25, message = "Middle name must not exceed 25 characters")
    private String middleName;
    
    /**
     * Customer last name (COBOL: ACSLNAMO PIC X(25)).
     * Last name (surname) from customer master record.
     */
    @JsonProperty("lastName")
    @Size(max = 25, message = "Last name must not exceed 25 characters")
    private String lastName;
    
    /**
     * Mailing address line 1 (COBOL: ACSADL1O PIC X(50)).
     * Primary address line (street number and name).
     */
    @JsonProperty("addressLine1")
    @Size(max = 50, message = "Address line 1 must not exceed 50 characters")
    private String addressLine1;
    
    /**
     * Mailing address line 2 (COBOL: ACSADL2O PIC X(50)).
     * Secondary address line (apartment, suite, unit number).
     */
    @JsonProperty("addressLine2")
    @Size(max = 50, message = "Address line 2 must not exceed 50 characters")
    private String addressLine2;
    
    /**
     * City name (COBOL: ACSCITYO PIC X(50)).
     * City component of mailing address.
     */
    @JsonProperty("city")
    @Size(max = 50, message = "City must not exceed 50 characters")
    private String city;
    
    /**
     * State code (COBOL: ACSSTTEO PIC X(2)).
     * Two-letter US state abbreviation (e.g., CA, NY, TX).
     */
    @JsonProperty("state")
    @Size(max = 2, message = "State must be exactly 2 characters")
    private String state;
    
    /**
     * ZIP postal code (COBOL: ACSZIPCO PIC X(5)).
     * Five-digit US ZIP code (extended ZIP+4 not included).
     */
    @JsonProperty("zipCode")
    @Size(max = 5, message = "ZIP code must not exceed 5 characters")
    private String zipCode;
    
    /**
     * Country code (COBOL: ACSCTRYO PIC X(3)).
     * Three-character country code (ISO 3166-1 alpha-3 format).
     */
    @JsonProperty("country")
    @Size(max = 3, message = "Country must not exceed 3 characters")
    private String country;
    
    /**
     * Primary phone number (COBOL: ACSPHN1O PIC X(13)).
     * Customer's primary contact phone number.
     * Format: NNN-NNN-NNNN (13 characters including dashes).
     */
    @JsonProperty("phone1")
    @Size(max = 13, message = "Phone 1 must not exceed 13 characters")
    private String phone1;
    
    /**
     * Alternate phone number (COBOL: ACSPHN2O PIC X(13)).
     * Customer's secondary contact phone number.
     * Format: NNN-NNN-NNNN (13 characters including dashes).
     */
    @JsonProperty("phone2")
    @Size(max = 13, message = "Phone 2 must not exceed 13 characters")
    private String phone2;
    
    /**
     * Government-issued ID number (COBOL: ACSGOVTO PIC X(20)).
     * Driver's license, passport, or other government ID number.
     * Used for identity verification purposes.
     */
    @JsonProperty("governmentId")
    @Size(max = 20, message = "Government ID must not exceed 20 characters")
    private String governmentId;
    
    /**
     * EFT account number (COBOL: ACSEFTCO PIC X(10)).
     * Electronic Funds Transfer account identifier for autopay.
     */
    @JsonProperty("eftAccountNumber")
    @Size(max = 10, message = "EFT account number must not exceed 10 characters")
    private String eftAccountNumber;
    
    /**
     * Profile flag (COBOL: ACSPFLGO PIC X(1)).
     * Single character flag for account profile status or special handling.
     * Implementation-specific values defined in business logic.
     */
    @JsonProperty("profileFlag")
    @Size(max = 1, message = "Profile flag must be exactly 1 character")
    private String profileFlag;
    
    /**
     * Informational message (COBOL: INFOMSGO PIC X(45)).
     * User-friendly informational message displayed on the screen.
     * Contains context-specific guidance or confirmation messages.
     */
    @JsonProperty("infoMessage")
    @Size(max = 45, message = "Info message must not exceed 45 characters")
    private String infoMessage;
    
    /**
     * Error message (COBOL: ERRMSGO PIC X(78)).
     * Error or warning message text displayed when validation fails.
     * Contains detailed error description for user remediation.
     */
    @JsonProperty("errorMessage")
    @Size(max = 78, message = "Error message must not exceed 78 characters")
    private String errorMessage;
}

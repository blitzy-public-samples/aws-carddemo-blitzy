/*
 * DataNotFoundException.java
 *
 * Custom runtime exception for entity not found scenarios in the CardDemo application.
 *
 * Converted from COBOL error handling patterns:
 * - CICS DFHRESP(NOTFND) response code (when EXEC CICS READ returns no record)
 * - VSAM file-status 23 (record not found)
 *
 * Original COBOL pattern example from COACTUPC.cbl line 3668:
 *   WHEN DFHRESP(NOTFND)
 *      SET INPUT-ERROR TO TRUE
 *      STRING 'Account:' WS-CARD-RID-ACCT-ID-X ' not found' INTO WS-RETURN-MSG
 *
 * Java equivalent:
 *   throw new DataNotFoundException("Account", accountId);
 *
 * This exception is thrown by repository and service layers when:
 * - JPA findById() returns Optional.empty()
 * - Database query returns no results
 * - Entity lookup fails for provided ID
 *
 * Caught by GlobalExceptionHandler which maps to HTTP 404 Not Found response
 * with standardized ErrorResponse DTO.
 *
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
package com.carddemo.exception;

import lombok.Getter;

/**
 * Custom runtime exception thrown when an entity is not found in the database.
 * 
 * <p>This exception replaces COBOL error handling patterns:</p>
 * <ul>
 *   <li>CICS DFHRESP(NOTFND) - returned when EXEC CICS READ finds no record</li>
 *   <li>VSAM file-status 23 - indicates record not found in VSAM dataset</li>
 * </ul>
 * 
 * <p>Usage examples:</p>
 * <pre>
 * // Simple message constructor
 * throw new DataNotFoundException("Account with ID 12345678901 not found");
 * 
 * // Entity type and ID constructor (recommended)
 * throw new DataNotFoundException("Account", 12345678901L);
 * 
 * // Full constructor with error code
 * throw new DataNotFoundException("DNF001", "Account not found in database", "Account", 12345678901L);
 * </pre>
 * 
 * <p>This exception is caught by GlobalExceptionHandler and mapped to:</p>
 * <ul>
 *   <li>HTTP Status: 404 Not Found</li>
 *   <li>Response Body: ErrorResponse DTO with error details</li>
 * </ul>
 * 
 * @see RuntimeException
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 1.0
 */
@Getter
public class DataNotFoundException extends RuntimeException {

    /**
     * Error code for the not-found error (e.g., "DNF001", "DNF002").
     * Used for client-side error handling and internationalization.
     */
    private final String errorCode;

    /**
     * Type of entity that was not found (e.g., "Account", "Card", "Customer", "Transaction").
     * Corresponds to COBOL file names (ACCTDAT, CARDFILE, CUSTFILE, TRANSACT).
     */
    private final String entityType;

    /**
     * ID value that was searched for but not found.
     * Can be Long (account ID, customer ID), String (card number, transaction ID), etc.
     */
    private final Object entityId;

    /**
     * Constructs a DataNotFoundException with a simple message.
     * 
     * <p>COBOL equivalent pattern:</p>
     * <pre>
     * WHEN DFHRESP(NOTFND)
     *    MOVE 'Record not found' TO WS-RETURN-MSG
     * </pre>
     * 
     * @param message Detailed error message describing what was not found
     */
    public DataNotFoundException(String message) {
        super(message);
        this.errorCode = "DNF000"; // Default error code
        this.entityType = "Unknown";
        this.entityId = null;
    }

    /**
     * Constructs a DataNotFoundException with entity type and ID.
     * Automatically generates a descriptive error message.
     * 
     * <p>COBOL equivalent pattern from COACTUPC.cbl:</p>
     * <pre>
     * WHEN DFHRESP(NOTFND)
     *    STRING 'Account:' WS-CARD-RID-ACCT-ID-X
     *           ' not found in Cross ref file.'
     *           DELIMITED BY SIZE INTO WS-RETURN-MSG
     * </pre>
     * 
     * <p>This is the recommended constructor for most use cases.</p>
     * 
     * @param entityType Type of entity (e.g., "Account", "Card", "Customer")
     * @param entityId   ID of the entity that was not found
     */
    public DataNotFoundException(String entityType, Object entityId) {
        super(String.format("%s with ID %s not found", entityType, entityId));
        this.errorCode = generateErrorCode(entityType);
        this.entityType = entityType;
        this.entityId = entityId;
    }

    /**
     * Constructs a DataNotFoundException with full error details.
     * 
     * <p>COBOL equivalent pattern with custom error codes:</p>
     * <pre>
     * WHEN DFHRESP(NOTFND)
     *    MOVE 'ACCT-001' TO ERROR-CODE
     *    MOVE 'Account not found in master file' TO ERROR-MESSAGE
     *    MOVE WS-CARD-RID-ACCT-ID-X TO ERROR-KEY
     * </pre>
     * 
     * @param errorCode   Specific error code (e.g., "DNF001" for Account not found)
     * @param message     Detailed error message
     * @param entityType  Type of entity (e.g., "Account", "Card", "Customer")
     * @param entityId    ID of the entity that was not found
     */
    public DataNotFoundException(String errorCode, String message, String entityType, Object entityId) {
        super(message);
        this.errorCode = errorCode;
        this.entityType = entityType;
        this.entityId = entityId;
    }

    /**
     * Generates a standard error code based on entity type.
     * Maps COBOL file names to error codes.
     * 
     * <p>Error code mapping:</p>
     * <ul>
     *   <li>Account (ACCTDAT) → DNF001</li>
     *   <li>Card (CARDFILE) → DNF002</li>
     *   <li>Customer (CUSTFILE) → DNF003</li>
     *   <li>Transaction (TRANSACT) → DNF004</li>
     *   <li>CardAccountXref (XREFFILE) → DNF005</li>
     *   <li>User (USRSEC) → DNF006</li>
     *   <li>Other entities → DNF999</li>
     * </ul>
     * 
     * @param entityType The type of entity
     * @return Standard error code for the entity type
     */
    private static String generateErrorCode(String entityType) {
        if (entityType == null) {
            return "DNF999";
        }
        
        // Map entity types to error codes (corresponds to COBOL file names)
        switch (entityType.toLowerCase()) {
            case "account":
                return "DNF001"; // ACCTDAT file not found
            case "card":
                return "DNF002"; // CARDFILE not found
            case "customer":
                return "DNF003"; // CUSTFILE not found
            case "transaction":
                return "DNF004"; // TRANSACT file not found
            case "cardaccountxref":
            case "xref":
                return "DNF005"; // XREFFILE (CXACAIX) not found
            case "user":
            case "usersecurity":
                return "DNF006"; // USRSEC file not found
            case "transactiontype":
                return "DNF007"; // TRANTYPE file not found
            case "transactioncategory":
                return "DNF008"; // TRANCATG file not found
            case "disclosuregroup":
                return "DNF009"; // DISCGRP file not found
            case "transactioncategorybalance":
                return "DNF010"; // TCATBAL file not found
            default:
                return "DNF999"; // Unknown entity type
        }
    }

    /**
     * Returns a detailed error message including entity type and ID.
     * Overrides getMessage() to provide consistent format for logging and error responses.
     * 
     * @return Formatted error message
     */
    @Override
    public String getMessage() {
        String baseMessage = super.getMessage();
        if (entityId != null) {
            return String.format("%s [errorCode=%s, entityType=%s, entityId=%s]",
                    baseMessage, errorCode, entityType, entityId);
        }
        return baseMessage;
    }
}

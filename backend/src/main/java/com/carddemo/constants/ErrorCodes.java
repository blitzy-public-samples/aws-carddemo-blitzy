/*
 * ErrorCodes.java
 * 
 * Enumeration of application error codes and abend codes transformed from COBOL
 * error handling structures. Maps COBOL file status codes, CICS RESP/RESP2 codes,
 * validation error codes, and business rule violation codes to Spring exception hierarchy.
 * 
 * Transformed from:
 * - app/cpy/CSMSG02Y.cpy (ABEND-DATA structure)
 * - app/cbl/COACTUPC.cbl (error handling patterns)
 * - app/cbl/COACTVWC.cbl (CICS RESP code handling)
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 */
package com.carddemo.constants;

/**
 * Comprehensive error code enumeration for CardDemo application.
 * 
 * <p>This enum maps legacy mainframe error codes to modern Java exception patterns:
 * <ul>
 *   <li>COBOL FILE-STATUS codes (00-99) for file I/O operations</li>
 *   <li>CICS RESP codes (NORMAL, NOTFND, DUPREC, etc.) for transaction responses</li>
 *   <li>ABEND codes (AICA, ASRA, AEY9) for system abnormal terminations</li>
 *   <li>Business validation errors for application-level validation failures</li>
 * </ul>
 * 
 * <p>Each error code includes:
 * <ul>
 *   <li>Unique code identifier for logging and tracking</li>
 *   <li>User-friendly message for display</li>
 *   <li>Detailed description for debugging and documentation</li>
 *   <li>Error type classification for exception mapping</li>
 * </ul>
 */
public enum ErrorCodes {
    
    // ========================================================================
    // COBOL FILE-STATUS Codes (Success and Information)
    // ========================================================================
    
    /**
     * FILE-STATUS 00: Successful completion.
     * COBOL equivalent: FILE STATUS = '00'
     */
    FILE_STATUS_SUCCESS(
        "00",
        "Operation successful",
        "File operation completed successfully without errors",
        ErrorType.SUCCESS
    ),
    
    /**
     * FILE-STATUS 10: End of file reached.
     * COBOL equivalent: FILE STATUS = '10'
     */
    FILE_STATUS_EOF(
        "10",
        "End of file",
        "End of file reached during sequential read operation",
        ErrorType.INFORMATION
    ),
    
    /**
     * FILE-STATUS 02: Duplicate key detected but operation successful.
     * COBOL equivalent: FILE STATUS = '02'
     */
    FILE_STATUS_DUPLICATE_KEY_SUCCESS(
        "02",
        "Duplicate key with alternate",
        "Duplicate key value detected but operation completed using alternate key",
        ErrorType.INFORMATION
    ),
    
    // ========================================================================
    // COBOL FILE-STATUS Codes (Errors)
    // ========================================================================
    
    /**
     * FILE-STATUS 22: Duplicate key error.
     * COBOL equivalent: FILE STATUS = '22'
     * Occurs on WRITE or REWRITE when duplicate key value is not allowed.
     */
    FILE_STATUS_DUPLICATE_KEY(
        "22",
        "Duplicate key error",
        "Attempt to write or update record with duplicate key value",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 23: Record not found.
     * COBOL equivalent: FILE STATUS = '23'
     * Occurs on READ when specified record key does not exist.
     */
    FILE_STATUS_RECORD_NOT_FOUND(
        "23",
        "Record not found",
        "Specified record key does not exist in the file",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 24: Boundary violation.
     * COBOL equivalent: FILE STATUS = '24'
     * Occurs when attempting to write beyond file boundaries.
     */
    FILE_STATUS_BOUNDARY_VIOLATION(
        "24",
        "Boundary violation",
        "Attempt to write beyond externally defined file boundaries",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 30: Permanent I/O error.
     * COBOL equivalent: FILE STATUS = '30'
     * Hardware or system-level I/O error occurred.
     */
    FILE_STATUS_IOERR(
        "30",
        "I/O error",
        "Permanent I/O error or hardware failure during file operation",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 34: Boundary violation on sequential write.
     * COBOL equivalent: FILE STATUS = '34'
     */
    FILE_STATUS_SEQ_BOUNDARY(
        "34",
        "Sequential boundary violation",
        "Boundary violation during sequential write operation",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 35: File not found.
     * COBOL equivalent: FILE STATUS = '35'
     * Specified file does not exist.
     */
    FILE_STATUS_FILE_NOT_FOUND(
        "35",
        "File not found",
        "Specified file does not exist or cannot be opened",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 37: File organization mismatch.
     * COBOL equivalent: FILE STATUS = '37'
     * File does not match the organization specified in the program.
     */
    FILE_STATUS_ORGANIZATION_MISMATCH(
        "37",
        "File organization mismatch",
        "File organization does not match program specification",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 39: File attribute conflict.
     * COBOL equivalent: FILE STATUS = '39'
     */
    FILE_STATUS_ATTRIBUTE_CONFLICT(
        "39",
        "File attribute conflict",
        "Conflict in file attributes or access method",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 41: File already open.
     * COBOL equivalent: FILE STATUS = '41'
     */
    FILE_STATUS_ALREADY_OPEN(
        "41",
        "File already open",
        "Attempt to open a file that is already open",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 42: File not open.
     * COBOL equivalent: FILE STATUS = '42'
     */
    FILE_STATUS_NOT_OPEN(
        "42",
        "File not open",
        "Attempt to perform operation on a file that is not open",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 43: Delete or rewrite without prior read.
     * COBOL equivalent: FILE STATUS = '43'
     */
    FILE_STATUS_NO_PRIOR_READ(
        "43",
        "No prior read",
        "DELETE or REWRITE attempted without successful prior READ",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 44: Record length mismatch.
     * COBOL equivalent: FILE STATUS = '44'
     */
    FILE_STATUS_RECORD_LENGTH_MISMATCH(
        "44",
        "Record length mismatch",
        "Record length does not match file definition",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 46: Sequential read after non-sequential access.
     * COBOL equivalent: FILE STATUS = '46'
     */
    FILE_STATUS_SEQ_READ_ERROR(
        "46",
        "Sequential read error",
        "Sequential read attempted after non-sequential access",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 47: File not available.
     * COBOL equivalent: FILE STATUS = '47'
     */
    FILE_STATUS_NOT_AVAILABLE(
        "47",
        "File not available",
        "File is not available for requested operation",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 48: Output operation on input-only file.
     * COBOL equivalent: FILE STATUS = '48'
     */
    FILE_STATUS_OUTPUT_ON_INPUT(
        "48",
        "Output on input file",
        "Output operation attempted on input-only file",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 49: Input operation on output-only file.
     * COBOL equivalent: FILE STATUS = '49'
     */
    FILE_STATUS_INPUT_ON_OUTPUT(
        "49",
        "Input on output file",
        "Input operation attempted on output-only file",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 90: VSAM logical error.
     * COBOL equivalent: FILE STATUS = '90'
     * Generic VSAM logic error code.
     */
    FILE_STATUS_VSAM_LOGIC_ERROR(
        "90",
        "VSAM logic error",
        "VSAM logical error - check VSAM return codes for details",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 91: Password verification failed.
     * COBOL equivalent: FILE STATUS = '91'
     */
    FILE_STATUS_PASSWORD_ERROR(
        "91",
        "Password verification failed",
        "Password verification failed for protected file",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 92: File logic error.
     * COBOL equivalent: FILE STATUS = '92'
     */
    FILE_STATUS_LOGIC_ERROR(
        "92",
        "File logic error",
        "Logic error during file operation",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 93: Resource not available.
     * COBOL equivalent: FILE STATUS = '93'
     */
    FILE_STATUS_RESOURCE_NOT_AVAILABLE(
        "93",
        "Resource not available",
        "Required resource not available for file operation",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 94: Concurrent open error.
     * COBOL equivalent: FILE STATUS = '94'
     */
    FILE_STATUS_CONCURRENT_OPEN_ERROR(
        "94",
        "Concurrent open error",
        "Error during concurrent file access attempt",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 95: Invalid file information.
     * COBOL equivalent: FILE STATUS = '95'
     */
    FILE_STATUS_INVALID_FILE_INFO(
        "95",
        "Invalid file information",
        "File information is invalid or inconsistent",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 96: No file definition.
     * COBOL equivalent: FILE STATUS = '96'
     */
    FILE_STATUS_NO_DEFINITION(
        "96",
        "No file definition",
        "File definition missing or incomplete",
        ErrorType.FILE_ERROR
    ),
    
    /**
     * FILE-STATUS 97: File integrity verification failed.
     * COBOL equivalent: FILE STATUS = '97'
     */
    FILE_STATUS_INTEGRITY_ERROR(
        "97",
        "File integrity error",
        "File integrity verification failed",
        ErrorType.FILE_ERROR
    ),
    
    // ========================================================================
    // CICS RESP Codes
    // ========================================================================
    
    /**
     * CICS RESP NORMAL: Operation completed successfully.
     * COBOL equivalent: DFHRESP(NORMAL)
     */
    RESP_NORMAL(
        "NORMAL",
        "Operation successful",
        "CICS transaction or command completed successfully",
        ErrorType.SUCCESS
    ),
    
    /**
     * CICS RESP NOTFND: Requested resource not found.
     * COBOL equivalent: DFHRESP(NOTFND)
     * Occurs when READ fails to find the specified record.
     */
    RESP_NOTFND(
        "NOTFND",
        "Resource not found",
        "Requested record or resource was not found in the file or table",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP DUPREC: Duplicate record detected.
     * COBOL equivalent: DFHRESP(DUPREC)
     * Occurs when attempting to write a record that already exists.
     */
    RESP_DUPREC(
        "DUPREC",
        "Duplicate record",
        "Record with the same key already exists in the file",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP DUPKEY: Duplicate key detected.
     * COBOL equivalent: DFHRESP(DUPKEY)
     * Occurs when attempting to write a record with a duplicate alternate key.
     */
    RESP_DUPKEY(
        "DUPKEY",
        "Duplicate key",
        "Duplicate alternate key value detected during write operation",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP INVREQ: Invalid request.
     * COBOL equivalent: DFHRESP(INVREQ)
     * Request is invalid for the current state or context.
     */
    RESP_INVREQ(
        "INVREQ",
        "Invalid request",
        "Request is invalid for current transaction state or context",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP IOERR: I/O error occurred.
     * COBOL equivalent: DFHRESP(IOERR)
     * Physical I/O error during file or database access.
     */
    RESP_IOERR(
        "IOERR",
        "I/O error",
        "Physical I/O error occurred during data access operation",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP NOSPACE: No space available.
     * COBOL equivalent: DFHRESP(NOSPACE)
     * Insufficient storage space for the operation.
     */
    RESP_NOSPACE(
        "NOSPACE",
        "No space available",
        "Insufficient storage space available for operation",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP NOTOPEN: File not open.
     * COBOL equivalent: DFHRESP(NOTOPEN)
     * File or dataset is not open for access.
     */
    RESP_NOTOPEN(
        "NOTOPEN",
        "File not open",
        "File or dataset is not open for requested access",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP ENDFILE: End of file reached.
     * COBOL equivalent: DFHRESP(ENDFILE)
     * End of file encountered during browse operation.
     */
    RESP_ENDFILE(
        "ENDFILE",
        "End of file",
        "End of file reached during browse or sequential read",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP DISABLED: Resource disabled.
     * COBOL equivalent: DFHRESP(DISABLED)
     * Requested resource is currently disabled.
     */
    RESP_DISABLED(
        "DISABLED",
        "Resource disabled",
        "Requested resource is currently disabled for access",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP LENGERR: Length error.
     * COBOL equivalent: DFHRESP(LENGERR)
     * Data length does not match expected length.
     */
    RESP_LENGERR(
        "LENGERR",
        "Length error",
        "Data length does not match expected or specified length",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP ITEMERR: Item error.
     * COBOL equivalent: DFHRESP(ITEMERR)
     * Named item not found in container or queue.
     */
    RESP_ITEMERR(
        "ITEMERR",
        "Item error",
        "Named item not found in container or temporary storage",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP PGMIDERR: Program ID error.
     * COBOL equivalent: DFHRESP(PGMIDERR)
     * Specified program not found or not available.
     */
    RESP_PGMIDERR(
        "PGMIDERR",
        "Program not found",
        "Specified program not found or not available for execution",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP TRANSIDERR: Transaction ID error.
     * COBOL equivalent: DFHRESP(TRANSIDERR)
     * Specified transaction ID not defined.
     */
    RESP_TRANSIDERR(
        "TRANSIDERR",
        "Transaction not found",
        "Specified transaction ID is not defined in CICS",
        ErrorType.CICS_ERROR
    ),
    
    /**
     * CICS RESP NOTAUTH: Not authorized.
     * COBOL equivalent: DFHRESP(NOTAUTH)
     * User not authorized for the requested operation.
     */
    RESP_NOTAUTH(
        "NOTAUTH",
        "Not authorized",
        "User not authorized to perform the requested operation",
        ErrorType.CICS_ERROR
    ),
    
    // ========================================================================
    // ABEND Codes (Abnormal Termination)
    // ========================================================================
    
    /**
     * ABEND AICA: Runaway task.
     * COBOL equivalent: ABEND-CODE = 'AICA'
     * Task exceeded maximum execution time.
     */
    ABEND_AICA(
        "AICA",
        "Runaway task",
        "Task exceeded maximum execution time and was terminated",
        ErrorType.ABEND
    ),
    
    /**
     * ABEND ASRA: Program check.
     * COBOL equivalent: ABEND-CODE = 'ASRA'
     * Program check occurred (e.g., data exception, operation exception).
     */
    ABEND_ASRA(
        "ASRA",
        "Program check",
        "Program check occurred - invalid operation or data exception",
        ErrorType.ABEND
    ),
    
    /**
     * ABEND AEY9: Storage violation.
     * COBOL equivalent: ABEND-CODE = 'AEY9'
     * Storage violation or memory access error.
     */
    ABEND_AEY9(
        "AEY9",
        "Storage violation",
        "Storage violation or invalid memory access detected",
        ErrorType.ABEND
    ),
    
    /**
     * ABEND AEYO: Task control error.
     * COBOL equivalent: ABEND-CODE = 'AEYO'
     * Task control error during CICS processing.
     */
    ABEND_AEYO(
        "AEYO",
        "Task control error",
        "Error in CICS task control processing",
        ErrorType.ABEND
    ),
    
    /**
     * ABEND AKCP: Temporary storage error.
     * COBOL equivalent: ABEND-CODE = 'AKCP'
     * Error accessing temporary storage.
     */
    ABEND_AKCP(
        "AKCP",
        "Temporary storage error",
        "Error accessing CICS temporary storage",
        ErrorType.ABEND
    ),
    
    /**
     * ABEND AKCS: Transient data error.
     * COBOL equivalent: ABEND-CODE = 'AKCS'
     * Error accessing transient data.
     */
    ABEND_AKCS(
        "AKCS",
        "Transient data error",
        "Error accessing CICS transient data queue",
        ErrorType.ABEND
    ),
    
    /**
     * ABEND APCT: Program compression table error.
     * COBOL equivalent: ABEND-CODE = 'APCT'
     * Error in program compression table processing.
     */
    ABEND_APCT(
        "APCT",
        "PCT error",
        "Error in program compression table processing",
        ErrorType.ABEND
    ),
    
    // ========================================================================
    // Business Validation Errors
    // ========================================================================
    
    /**
     * Generic input validation error.
     * COBOL equivalent: INPUT-ERROR condition
     * Used when input data fails validation rules.
     */
    INPUT_ERROR(
        "INPUT_ERR",
        "Input validation error",
        "Input data failed validation - check field values and formats",
        ErrorType.VALIDATION_ERROR
    ),
    
    /**
     * Cross-reference file read error.
     * COBOL equivalent: XREF-READ-ERROR condition
     * Error reading account or card cross-reference data.
     */
    XREF_READ_ERROR(
        "XREF_ERR",
        "Cross-reference read error",
        "Error reading account or card cross-reference file",
        ErrorType.VALIDATION_ERROR
    ),
    
    /**
     * General validation error.
     * Used for field-level validation failures.
     */
    VALIDATION_ERROR(
        "VAL_ERR",
        "Validation error",
        "Data validation failed - one or more fields contain invalid values",
        ErrorType.VALIDATION_ERROR
    ),
    
    // ========================================================================
    // Domain-Specific Business Errors
    // ========================================================================
    
    /**
     * Account not found in system.
     * Occurs when requested account ID does not exist.
     */
    ACCOUNT_NOT_FOUND_ERROR(
        "ACCT_NOTFND",
        "Account not found",
        "Requested account ID does not exist in the account master file",
        ErrorType.BUSINESS_ERROR
    ),
    
    /**
     * Card not found in system.
     * Occurs when requested card number does not exist.
     */
    CARD_NOT_FOUND_ERROR(
        "CARD_NOTFND",
        "Card not found",
        "Requested card number does not exist in the card master file",
        ErrorType.BUSINESS_ERROR
    ),
    
    /**
     * Customer not found in system.
     * Occurs when requested customer ID does not exist.
     */
    CUSTOMER_NOT_FOUND_ERROR(
        "CUST_NOTFND",
        "Customer not found",
        "Requested customer ID does not exist in the customer master file",
        ErrorType.BUSINESS_ERROR
    ),
    
    /**
     * Transaction not found in system.
     * Occurs when requested transaction ID does not exist.
     */
    TRANSACTION_NOT_FOUND_ERROR(
        "TRAN_NOTFND",
        "Transaction not found",
        "Requested transaction ID does not exist in the transaction file",
        ErrorType.BUSINESS_ERROR
    ),
    
    /**
     * Insufficient balance for transaction.
     * Occurs when account balance is insufficient for the requested operation.
     */
    INSUFFICIENT_BALANCE_ERROR(
        "INSUF_BAL",
        "Insufficient balance",
        "Account balance is insufficient for the requested transaction amount",
        ErrorType.BUSINESS_ERROR
    ),
    
    /**
     * Concurrent update conflict.
     * Occurs when record was modified by another user/process.
     */
    CONCURRENT_UPDATE_ERROR(
        "CONCUR_UPD",
        "Concurrent update conflict",
        "Record was modified by another user - please refresh and try again",
        ErrorType.BUSINESS_ERROR
    ),
    
    /**
     * Credit limit exceeded.
     * Occurs when transaction would exceed account credit limit.
     */
    CREDIT_LIMIT_EXCEEDED_ERROR(
        "CREDIT_LIM",
        "Credit limit exceeded",
        "Transaction would exceed account credit limit",
        ErrorType.BUSINESS_ERROR
    ),
    
    /**
     * Account status prevents operation.
     * Occurs when account is closed, suspended, or otherwise inactive.
     */
    ACCOUNT_STATUS_ERROR(
        "ACCT_STATUS",
        "Invalid account status",
        "Account status prevents the requested operation",
        ErrorType.BUSINESS_ERROR
    ),
    
    /**
     * Card status prevents operation.
     * Occurs when card is expired, blocked, or inactive.
     */
    CARD_STATUS_ERROR(
        "CARD_STATUS",
        "Invalid card status",
        "Card status prevents the requested operation",
        ErrorType.BUSINESS_ERROR
    ),
    
    /**
     * Authentication failed.
     * User credentials are invalid or authentication process failed.
     */
    AUTHENTICATION_FAILED_ERROR(
        "AUTH_FAIL",
        "Authentication failed",
        "User authentication failed - invalid credentials or authentication error",
        ErrorType.SECURITY_ERROR
    ),
    
    /**
     * Authorization failed.
     * User does not have permission for the requested operation.
     */
    AUTHORIZATION_FAILED_ERROR(
        "AUTHZ_FAIL",
        "Authorization failed",
        "User does not have permission to perform the requested operation",
        ErrorType.SECURITY_ERROR
    ),
    
    /**
     * Session expired.
     * User session has expired and reauthentication is required.
     */
    SESSION_EXPIRED_ERROR(
        "SESS_EXP",
        "Session expired",
        "User session has expired - please login again",
        ErrorType.SECURITY_ERROR
    ),
    
    /**
     * Invalid transaction type.
     * Transaction type code is not recognized or not allowed.
     */
    INVALID_TRANSACTION_TYPE_ERROR(
        "INVAL_TRAN_TYPE",
        "Invalid transaction type",
        "Transaction type code is not recognized or not allowed",
        ErrorType.BUSINESS_ERROR
    ),
    
    /**
     * Date validation error.
     * Date value is invalid or outside acceptable range.
     */
    DATE_VALIDATION_ERROR(
        "DATE_ERR",
        "Date validation error",
        "Date value is invalid or outside acceptable range",
        ErrorType.VALIDATION_ERROR
    ),
    
    /**
     * Mandatory field missing.
     * Required field is empty or not provided.
     */
    MANDATORY_FIELD_ERROR(
        "MAND_FIELD",
        "Mandatory field missing",
        "Required field is empty or not provided",
        ErrorType.VALIDATION_ERROR
    ),
    
    /**
     * Numeric field validation error.
     * Field expected to be numeric contains non-numeric data.
     */
    NUMERIC_FIELD_ERROR(
        "NUM_FIELD_ERR",
        "Numeric field error",
        "Field expected to be numeric contains invalid characters",
        ErrorType.VALIDATION_ERROR
    ),
    
    /**
     * Alphanumeric field validation error.
     * Field contains invalid characters for alphanumeric data.
     */
    ALPHANUMERIC_FIELD_ERROR(
        "ALNUM_FIELD_ERR",
        "Alphanumeric field error",
        "Field contains invalid characters",
        ErrorType.VALIDATION_ERROR
    ),
    
    /**
     * Field length validation error.
     * Field length exceeds maximum or below minimum allowed.
     */
    FIELD_LENGTH_ERROR(
        "FIELD_LEN_ERR",
        "Field length error",
        "Field length exceeds maximum or below minimum allowed",
        ErrorType.VALIDATION_ERROR
    ),
    
    /**
     * Generic system error.
     * Unexpected system error occurred.
     */
    SYSTEM_ERROR(
        "SYS_ERR",
        "System error",
        "Unexpected system error occurred - please contact support",
        ErrorType.SYSTEM_ERROR
    ),
    
    /**
     * Database connection error.
     * Unable to establish or maintain database connection.
     */
    DATABASE_CONNECTION_ERROR(
        "DB_CONN_ERR",
        "Database connection error",
        "Unable to establish or maintain database connection",
        ErrorType.SYSTEM_ERROR
    ),
    
    /**
     * Batch job error.
     * Error occurred during batch processing.
     */
    BATCH_JOB_ERROR(
        "BATCH_ERR",
        "Batch job error",
        "Error occurred during batch processing operation",
        ErrorType.SYSTEM_ERROR
    );
    
    // ========================================================================
    // Enum Fields
    // ========================================================================
    
    /**
     * Error code identifier.
     * Corresponds to COBOL FILE-STATUS, CICS RESP, or application-defined code.
     */
    private final String code;
    
    /**
     * User-friendly error message.
     * Displayed to end users for error conditions.
     * Maximum length: 50 characters (matching ABEND-REASON PIC X(50)).
     */
    private final String message;
    
    /**
     * Detailed error description.
     * Provides technical details for debugging and documentation.
     * Maximum length: 72 characters (matching ABEND-MSG PIC X(72)).
     */
    private final String description;
    
    /**
     * Error type classification.
     * Used for exception mapping and error handling strategy.
     */
    private final ErrorType errorType;
    
    // ========================================================================
    // Constructor
    // ========================================================================
    
    /**
     * Constructs an ErrorCodes enum constant.
     *
     * @param code        unique error code identifier
     * @param message     user-friendly error message (max 50 chars)
     * @param description detailed error description (max 72 chars)
     * @param errorType   error type classification
     */
    ErrorCodes(String code, String message, String description, ErrorType errorType) {
        this.code = code;
        this.message = message;
        this.description = description;
        this.errorType = errorType;
    }
    
    // ========================================================================
    // Accessor Methods
    // ========================================================================
    
    /**
     * Gets the error code identifier.
     *
     * @return error code string
     */
    public String getCode() {
        return code;
    }
    
    /**
     * Gets the user-friendly error message.
     *
     * @return error message string
     */
    public String getMessage() {
        return message;
    }
    
    /**
     * Gets the detailed error description.
     *
     * @return error description string
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Gets the error type classification.
     *
     * @return ErrorType enumeration value
     */
    public ErrorType getErrorType() {
        return errorType;
    }
    
    // ========================================================================
    // Utility Methods
    // ========================================================================
    
    /**
     * Finds an ErrorCodes constant by its code identifier.
     * Performs case-insensitive matching for flexibility.
     *
     * @param code the error code to search for
     * @return matching ErrorCodes constant, or SYSTEM_ERROR if not found
     */
    public static ErrorCodes fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return SYSTEM_ERROR;
        }
        
        String normalizedCode = code.trim().toUpperCase();
        
        for (ErrorCodes errorCode : ErrorCodes.values()) {
            if (errorCode.code.equalsIgnoreCase(normalizedCode)) {
                return errorCode;
            }
        }
        
        // If no exact match found, return generic system error
        return SYSTEM_ERROR;
    }
    
    /**
     * Determines if this error code represents a successful operation.
     *
     * @return true if error type is SUCCESS, false otherwise
     */
    public boolean isSuccess() {
        return errorType == ErrorType.SUCCESS;
    }
    
    /**
     * Determines if this error code represents an error condition.
     *
     * @return true if error type is not SUCCESS or INFORMATION, false otherwise
     */
    public boolean isError() {
        return errorType != ErrorType.SUCCESS && errorType != ErrorType.INFORMATION;
    }
    
    /**
     * Determines if this error code represents a retryable error.
     * Retryable errors include I/O errors, connection errors, and concurrent update conflicts.
     *
     * @return true if the error is potentially retryable, false otherwise
     */
    public boolean isRetryable() {
        switch (this) {
            case FILE_STATUS_IOERR:
            case RESP_IOERR:
            case RESP_NOSPACE:
            case DATABASE_CONNECTION_ERROR:
            case CONCURRENT_UPDATE_ERROR:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Determines if this error code indicates a resource not found condition.
     *
     * @return true if error represents a not found condition, false otherwise
     */
    public boolean isNotFound() {
        switch (this) {
            case FILE_STATUS_RECORD_NOT_FOUND:
            case FILE_STATUS_FILE_NOT_FOUND:
            case RESP_NOTFND:
            case ACCOUNT_NOT_FOUND_ERROR:
            case CARD_NOT_FOUND_ERROR:
            case CUSTOMER_NOT_FOUND_ERROR:
            case TRANSACTION_NOT_FOUND_ERROR:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Determines if this error code indicates a duplicate key/record condition.
     *
     * @return true if error represents a duplicate condition, false otherwise
     */
    public boolean isDuplicate() {
        switch (this) {
            case FILE_STATUS_DUPLICATE_KEY:
            case FILE_STATUS_DUPLICATE_KEY_SUCCESS:
            case RESP_DUPREC:
            case RESP_DUPKEY:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Gets the HTTP status code that should be returned for this error.
     * Maps error codes to appropriate HTTP status codes for REST API responses.
     *
     * @return HTTP status code (200, 400, 401, 403, 404, 409, 500, etc.)
     */
    public int getHttpStatusCode() {
        if (isSuccess()) {
            return 200; // OK
        }
        
        if (isNotFound()) {
            return 404; // Not Found
        }
        
        if (isDuplicate()) {
            return 409; // Conflict
        }
        
        switch (errorType) {
            case VALIDATION_ERROR:
                return 400; // Bad Request
            case SECURITY_ERROR:
                // Distinguish between authentication (401) and authorization (403)
                if (this == AUTHENTICATION_FAILED_ERROR || this == SESSION_EXPIRED_ERROR) {
                    return 401; // Unauthorized
                } else {
                    return 403; // Forbidden
                }
            case BUSINESS_ERROR:
                return 422; // Unprocessable Entity
            case FILE_ERROR:
            case CICS_ERROR:
            case ABEND:
            case SYSTEM_ERROR:
            default:
                return 500; // Internal Server Error
        }
    }
    
    /**
     * Formats error information for logging purposes.
     * Includes code, message, description, and type.
     *
     * @return formatted error string for logging
     */
    public String toLogString() {
        return String.format("[%s] %s - %s (Type: %s)", 
            code, message, description, errorType);
    }
    
    /**
     * Returns a string representation of the error code.
     *
     * @return error code and message
     */
    @Override
    public String toString() {
        return code + ": " + message;
    }
    
    // ========================================================================
    // ErrorType Inner Enum
    // ========================================================================
    
    /**
     * Classification of error types for exception mapping.
     * Maps COBOL error categories to Spring exception hierarchy.
     */
    public enum ErrorType {
        /**
         * Successful operation (no error).
         * HTTP Status: 200 OK
         */
        SUCCESS,
        
        /**
         * Informational status (e.g., EOF).
         * HTTP Status: 200 OK or 204 No Content
         */
        INFORMATION,
        
        /**
         * COBOL file I/O error.
         * Maps to DataAccessException in Spring.
         * HTTP Status: 500 Internal Server Error
         */
        FILE_ERROR,
        
        /**
         * CICS transaction processing error.
         * Maps to TransactionException in Spring.
         * HTTP Status: 500 Internal Server Error
         */
        CICS_ERROR,
        
        /**
         * CICS abnormal termination (ABEND).
         * Maps to SystemException in Spring.
         * HTTP Status: 500 Internal Server Error
         */
        ABEND,
        
        /**
         * Input validation error.
         * Maps to MethodArgumentNotValidException in Spring.
         * HTTP Status: 400 Bad Request
         */
        VALIDATION_ERROR,
        
        /**
         * Business rule violation.
         * Maps to custom business exceptions.
         * HTTP Status: 422 Unprocessable Entity
         */
        BUSINESS_ERROR,
        
        /**
         * Security or authorization error.
         * Maps to AccessDeniedException or AuthenticationException.
         * HTTP Status: 401 Unauthorized or 403 Forbidden
         */
        SECURITY_ERROR,
        
        /**
         * System-level error.
         * Maps to general Exception or RuntimeException.
         * HTTP Status: 500 Internal Server Error
         */
        SYSTEM_ERROR
    }
}

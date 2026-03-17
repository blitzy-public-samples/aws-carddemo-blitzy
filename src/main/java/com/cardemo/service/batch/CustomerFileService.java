/*
 * CustomerFileService.java — Spring @Service (← CBCUS01C.cbl)
 *
 * Batch service for customer file processing. Translates COBOL program
 * CBCUS01C.cbl — a batch utility that opens the customer master VSAM KSDS
 * (CUSTDATA, 500-byte CUSTOMER-RECORD from CVCUS01Y.cpy), sequentially
 * reads all customer records, formats and displays/logs selected fields
 * for operator inspection, then closes the dataset.
 *
 * Source COBOL: app/cbl/CBCUS01C.cbl
 * Source Copybook: app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD, 500-byte layout)
 * Alternate Copybook: app/cpy/CUSTREC.cpy (identical layout, DOB field name differs)
 * VSAM Dataset: AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
 * Record Length: 500 bytes
 * Primary Key: CUST-ID PIC 9(09) — 9-character customer identifier
 *
 * COBOL Paragraph → Java Method Traceability (100% coverage):
 *   ┌──────────────────────────┬───────────────────────────────────────────┐
 *   │ COBOL Paragraph          │ Java Method / Equivalent                 │
 *   ├──────────────────────────┼───────────────────────────────────────────┤
 *   │ PROCEDURE DIVISION       │ refreshCustomers()                       │
 *   │ 0000-CUSTFILE-OPEN       │ N/A — JPA manages connection lifecycle   │
 *   │ 1000-CUSTFILE-GET-NEXT   │ Iterator in refreshCustomers() loop     │
 *   │ 1100-DISPLAY-CUST-RECORD │ displayCustomerRecord(Customer)         │
 *   │ 9000-CUSTFILE-CLOSE      │ N/A — JPA manages connection lifecycle   │
 *   │ Z-DISPLAY-IO-STATUS      │ displayIoStatus(String)                  │
 *   │ Z-ABEND-PROGRAM          │ abendProgram(String) /                   │
 *   │                          │ abendProgram(String, Throwable)          │
 *   └──────────────────────────┴───────────────────────────────────────────┘
 *
 * Design Notes:
 * - COBOL END-OF-FILE flag (PIC X(01) VALUE 'N') is not needed as instance
 *   state because JPA findAll() returns a complete List rather than requiring
 *   a cursor-based read loop. This avoids mutable state in the singleton bean.
 * - COBOL APPL-RESULT (88-level conditions AOK/EOF) is replaced by standard
 *   Java exception handling and list iteration termination.
 * - COBOL DISPLAY CUSTOMER-RECORD dumps the raw 500-byte buffer. The Java
 *   equivalent formats each field with descriptive labels and applies PII
 *   masking to CUST-SSN and CUST-GOVT-ISSUED-ID.
 * - COBOL CALL 'CEE3ABD' (Language Environment ABEND) is replaced by throwing
 *   CardDemoException with ABCODE=999.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.entity.Customer;
import com.cardemo.repository.CustomerRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Batch service for customer file management and inspection.
 *
 * <p>Translates COBOL program CBCUS01C.cbl, which opens the customer master
 * VSAM KSDS file (CUSTDATA), reads all records sequentially, displays each
 * record's fields for operator inspection, and closes the file. This service
 * is used by {@code CustomerLoadJobConfig} for seed data loading and
 * verification from {@code custdata.txt}.</p>
 *
 * <h2>COBOL Program Summary (CBCUS01C.cbl)</h2>
 * <pre>
 * IDENTIFICATION DIVISION.
 * PROGRAM-ID.    CBCUS01C.
 * FUNCTION:      Read and print customer data file.
 *
 * Flow:
 * 1. DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'
 * 2. PERFORM 0000-CUSTFILE-OPEN      → JPA manages
 * 3. PERFORM UNTIL END-OF-FILE = 'Y'
 *        PERFORM 1000-CUSTFILE-GET-NEXT
 *        IF NOT EOF → DISPLAY CUSTOMER-RECORD
 *    END-PERFORM
 * 4. PERFORM 9000-CUSTFILE-CLOSE     → JPA manages
 * 5. DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'
 * 6. GOBACK
 * </pre>
 *
 * <h2>PII Handling</h2>
 * <p>The following fields contain Personally Identifiable Information (PII)
 * and are masked in all log output:</p>
 * <ul>
 *   <li>{@code CUST-SSN} — Social Security Number (9 digits) → shows only
 *       last 4 digits (e.g., {@code ***-**-1234})</li>
 *   <li>{@code CUST-GOVT-ISSUED-ID} — Government-issued ID (up to 20 chars)
 *       → shows only last 4 characters</li>
 * </ul>
 *
 * @see Customer
 * @see CustomerRepository
 * @see CardDemoException
 * @see FileStatusException
 */
@Service
public class CustomerFileService {

    private static final Logger log = LoggerFactory.getLogger(CustomerFileService.class);

    /**
     * COBOL program name used in start/end execution banners.
     * Matches COBOL: PROGRAM-ID. CBCUS01C.
     */
    private static final String PROGRAM_NAME = "CBCUS01C";

    /**
     * Number of trailing characters to show when masking PII values.
     * SSN "020973888" → "***-**-3888" (last 4 visible).
     */
    private static final int PII_VISIBLE_SUFFIX_LENGTH = 4;

    /**
     * Separator line printed after each customer record for readability.
     * Matches the COBOL practice of visually separating records in
     * batch output for operator inspection.
     */
    private static final String RECORD_SEPARATOR =
            "========================================"
                    + "========================================";

    /**
     * Spring Data JPA repository for CUSTDATA VSAM dataset access.
     * Replaces COBOL FILE-CONTROL SELECT CUSTFILE-FILE ASSIGN TO CUSTFILE.
     * Injected via constructor for testability and immutability.
     */
    private final CustomerRepository customerRepository;

    /**
     * Constructs a new {@code CustomerFileService} with the required
     * {@link CustomerRepository} dependency.
     *
     * <p>Uses constructor injection (preferred over field injection) for
     * immutability, testability, and explicit dependency declaration.
     * Replaces the COBOL FILE-CONTROL SELECT binding that associates
     * the logical file name CUSTFILE with the physical VSAM dataset.</p>
     *
     * @param customerRepository the Spring Data JPA repository providing
     *                           access to customer records; must not be null
     */
    public CustomerFileService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    // =========================================================================
    // Public API — COBOL PROCEDURE DIVISION Entry Points
    // =========================================================================

    /**
     * Main entry point for customer file processing.
     *
     * <p>Translates the COBOL PROCEDURE DIVISION of CBCUS01C.cbl. Reads all
     * customer records from the database (equivalent to opening the VSAM KSDS
     * for sequential input, reading until EOF, then closing), and displays
     * each record's fields for operator inspection.</p>
     *
     * <h3>COBOL Flow Reproduced</h3>
     * <ol>
     *   <li>Display start banner: {@code START OF EXECUTION OF PROGRAM CBCUS01C}</li>
     *   <li>Open customer file for input (JPA manages connection lifecycle)</li>
     *   <li>Loop: read next record until EOF, display each record</li>
     *   <li>Close customer file (JPA manages connection lifecycle)</li>
     *   <li>Display end banner: {@code END OF EXECUTION OF PROGRAM CBCUS01C}</li>
     * </ol>
     *
     * <h3>COBOL Working Storage Equivalents</h3>
     * <ul>
     *   <li>{@code APPL-RESULT}: AOK(0) → successful findAll(), EOF(16) → end
     *       of list iteration, Error(12) → exception caught</li>
     *   <li>{@code END-OF-FILE}: Not needed — list iteration terminates naturally</li>
     * </ul>
     *
     * @throws CardDemoException if an unrecoverable error occurs during
     *                           customer file processing (equivalent to
     *                           COBOL Z-ABEND-PROGRAM with ABCODE=999)
     */
    public void refreshCustomers() {
        // DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_NAME);

        // 0000-CUSTFILE-OPEN: JPA manages connection lifecycle.
        // 1000-CUSTFILE-GET-NEXT: findAll() retrieves all records at once,
        // replacing the COBOL READ NEXT loop with CUSTFILE-STATUS checks.
        List<Customer> customers;
        try {
            customers = customerRepository.findAll();
        } catch (RuntimeException ex) {
            // Error path: equivalent to non-'00' CUSTFILE-STATUS after OPEN
            // or a non-'00'/non-'10' status during READ NEXT.
            // COBOL: DISPLAY 'ERROR READING CUSTOMER FILE'
            //        MOVE CUSTFILE-STATUS TO IO-STATUS
            //        PERFORM Z-DISPLAY-IO-STATUS
            //        PERFORM Z-ABEND-PROGRAM
            log.error("ERROR READING CUSTOMER FILE");
            displayIoStatus("12");
            throw abendProgram("Customer file processing failed", ex);
        }

        // PERFORM UNTIL END-OF-FILE = 'Y'
        //     IF END-OF-FILE = 'N'
        //         PERFORM 1000-CUSTFILE-GET-NEXT
        //         IF END-OF-FILE = 'N'
        //             DISPLAY CUSTOMER-RECORD  (← 1100-DISPLAY-CUST-RECORD)
        //         END-IF
        //     END-IF
        // END-PERFORM
        if (customers.isEmpty()) {
            // Equivalent to APPL-EOF (16): first READ returns status '10'
            log.info("No customer records found in CUSTDATA - end of file reached immediately");
        } else {
            log.info("Processing {} customer records from CUSTDATA", customers.size());
            // Sequential iteration replacing COBOL READ NEXT loop.
            // Each iteration: 1000-CUSTFILE-GET-NEXT (implicit) +
            //                 1100-DISPLAY-CUST-RECORD (explicit)
            customers.forEach(this::displayCustomerRecord);
        }

        // 9000-CUSTFILE-CLOSE: JPA manages connection lifecycle.
        // No explicit close needed — Spring manages the EntityManager and
        // database connection pool.

        // DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'
        log.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_NAME);
    }

    /**
     * Displays a single customer record field by field with descriptive labels.
     *
     * <p>Translates the COBOL {@code DISPLAY CUSTOMER-RECORD} statements found
     * in paragraphs 1000-CUSTFILE-GET-NEXT (line 96) and the PROCEDURE DIVISION
     * main loop (line 78) of CBCUS01C.cbl. The COBOL original dumps the raw
     * 500-byte record buffer; this Java equivalent formats each of the 17
     * business fields from CVCUS01Y.cpy with descriptive COBOL field-name labels
     * for structured logging output.</p>
     *
     * <h3>PII Masking</h3>
     * <ul>
     *   <li>{@code CUST-SSN}: 9-digit SSN masked to show only last 4 digits
     *       (e.g., "020973888" → "***-**-3888")</li>
     *   <li>{@code CUST-GOVT-ISSUED-ID}: Masked to show only last 4 characters</li>
     * </ul>
     *
     * <h3>Fields Displayed (from CVCUS01Y.cpy CUSTOMER-RECORD)</h3>
     * <pre>
     * CUST-ID, CUST-FIRST-NAME, CUST-MIDDLE-NAME, CUST-LAST-NAME,
     * CUST-ADDR-LINE-1, CUST-ADDR-LINE-2, CUST-ADDR-LINE-3,
     * CUST-ADDR-STATE-CD, CUST-ADDR-COUNTRY-CD, CUST-ADDR-ZIP,
     * CUST-PHONE-NUM-1, CUST-PHONE-NUM-2, CUST-SSN [masked],
     * CUST-GOVT-ISSUED-ID [masked], CUST-DOB-YYYY-MM-DD,
     * CUST-EFT-ACCOUNT-ID, CUST-PRI-CARD-HOLDER-IND,
     * CUST-FICO-CREDIT-SCORE
     * </pre>
     *
     * @param customer the {@link Customer} entity to display; must not be null
     * @throws CardDemoException if customer is null (ABEND equivalent)
     */
    public void displayCustomerRecord(Customer customer) {
        if (customer == null) {
            // Defensive check — COBOL would never encounter a null record,
            // but Java needs to handle this case. Equivalent to APPL-ERROR(12)
            // followed by Z-ABEND-PROGRAM.
            throw abendProgram("Null customer record encountered during processing");
        }

        // CUST-ID PIC 9(09) — Primary Key
        log.info("CUST-ID                 : {}", customer.getCustId());

        // Name fields — PIC X(25) each
        log.info("CUST-FIRST-NAME         : {}", customer.getFirstName());
        log.info("CUST-MIDDLE-NAME        : {}", customer.getMiddleName());
        log.info("CUST-LAST-NAME          : {}", customer.getLastName());

        // Address fields — PIC X(50) for lines, PIC X(02/03/10) for codes
        log.info("CUST-ADDR-LINE-1        : {}", customer.getAddrLine1());
        log.info("CUST-ADDR-LINE-2        : {}", customer.getAddrLine2());
        log.info("CUST-ADDR-LINE-3        : {}", customer.getAddrLine3());
        log.info("CUST-ADDR-STATE-CD      : {}", customer.getAddrStateCode());
        log.info("CUST-ADDR-COUNTRY-CD    : {}", customer.getAddrCountryCode());
        log.info("CUST-ADDR-ZIP           : {}", customer.getAddrZip());

        // Phone fields — PIC X(15) each
        log.info("CUST-PHONE-NUM-1        : {}", customer.getPhoneNum1());
        log.info("CUST-PHONE-NUM-2        : {}", customer.getPhoneNum2());

        // PII Field: CUST-SSN PIC 9(09) — MASKED for security
        // Original COBOL displays the raw SSN; Java masks to prevent
        // sensitive data exposure in log files.
        log.info("CUST-SSN                : {}", maskSsn(customer.getSsn()));

        // PII Field: CUST-GOVT-ISSUED-ID PIC X(20) — MASKED for security
        log.info("CUST-GOVT-ISSUED-ID     : {}", maskPii(customer.getGovtIssuedId()));

        // Date and financial identifier fields
        log.info("CUST-DOB-YYYY-MM-DD     : {}", customer.getDateOfBirth());
        log.info("CUST-EFT-ACCOUNT-ID     : {}", customer.getEftAccountId());

        // Card holder and credit score fields
        log.info("CUST-PRI-CARD-HOLDER-IND: {}", customer.getPriCardHolderInd());
        log.info("CUST-FICO-CREDIT-SCORE  : {}", customer.getFicoCreditScore());

        // Separator line after each record for operator readability
        log.info("{}", RECORD_SEPARATOR);
    }

    // =========================================================================
    // Private Helper Methods — COBOL Error Handling Equivalents
    // =========================================================================

    /**
     * Masks a Social Security Number for safe log output.
     *
     * <p>Formats the SSN to show only the last 4 digits in standard SSN
     * format: {@code ***-**-NNNN}. If the input is null or blank, returns
     * a fully masked placeholder.</p>
     *
     * @param ssn the raw SSN value (9-digit numeric string)
     * @return masked SSN string (e.g., "***-**-3888")
     */
    private static String maskSsn(String ssn) {
        if (ssn == null || ssn.isBlank()) {
            return "***-**-****";
        }
        String trimmed = ssn.trim();
        if (trimmed.length() <= PII_VISIBLE_SUFFIX_LENGTH) {
            return "***-**-" + trimmed;
        }
        return "***-**-" + trimmed.substring(trimmed.length() - PII_VISIBLE_SUFFIX_LENGTH);
    }

    /**
     * Masks a PII (Personally Identifiable Information) value for safe log output.
     *
     * <p>Shows only the last {@value #PII_VISIBLE_SUFFIX_LENGTH} characters,
     * replacing the rest with asterisks. Used for government-issued ID and
     * similar sensitive fields.</p>
     *
     * @param value the sensitive value to mask
     * @return masked string showing only trailing characters
     */
    private static String maskPii(String value) {
        if (value == null || value.isBlank()) {
            return "****";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= PII_VISIBLE_SUFFIX_LENGTH) {
            return "****" + trimmed;
        }
        return "****" + trimmed.substring(trimmed.length() - PII_VISIBLE_SUFFIX_LENGTH);
    }

    /**
     * Formats and logs the VSAM file I/O status code.
     *
     * <p>Equivalent to COBOL paragraph {@code Z-DISPLAY-IO-STATUS} from
     * CBCUS01C.cbl. The COBOL original formats the two-byte file status
     * into a 4-character display string, handling both numeric and non-numeric
     * (binary) status values:</p>
     *
     * <pre>
     * IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
     *     → special binary conversion to 4-char display
     * ELSE
     *     → MOVE '0000' TO IO-STATUS-04
     *       MOVE IO-STATUS TO IO-STATUS-04(3:2)
     *       DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     * END-IF
     * </pre>
     *
     * <p>Creates a {@link FileStatusException} to capture the VSAM status
     * code semantics for diagnostic traceability, then logs the formatted
     * status information.</p>
     *
     * @param statusCode the two-character VSAM file status code (e.g., "12")
     */
    private void displayIoStatus(String statusCode) {
        // Create FileStatusException to capture VSAM status semantics
        // for diagnostic traceability between Java and COBOL error paths.
        FileStatusException statusRef = new FileStatusException(
                statusCode,
                "VSAM I/O error on CUSTFILE, file status: " + statusCode
        );
        // Format to 4-character display: '00NN' pattern
        // COBOL: MOVE '0000' TO IO-STATUS-04
        //        MOVE IO-STATUS TO IO-STATUS-04(3:2)
        //        DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
        String formattedStatus = ("0000" + statusCode);
        formattedStatus = formattedStatus.substring(formattedStatus.length() - 4);
        log.error("FILE STATUS IS: NNNN{}", formattedStatus);
        log.error("IO status detail: {}", statusRef.getMessage());
    }

    /**
     * Terminates processing with an ABEND-equivalent exception.
     *
     * <p>Equivalent to COBOL paragraph {@code Z-ABEND-PROGRAM} from
     * CBCUS01C.cbl:</p>
     * <pre>
     * Z-ABEND-PROGRAM.
     *     DISPLAY 'ABENDING PROGRAM'
     *     MOVE 0 TO TIMING
     *     MOVE 999 TO ABCODE
     *     CALL 'CEE3ABD'.
     * </pre>
     *
     * <p>The Language Environment (LE) ABEND call {@code CEE3ABD} with
     * {@code ABCODE=999} is translated to throwing a {@link CardDemoException}
     * that propagates up the call stack, equivalent to abnormal termination.</p>
     *
     * @param message descriptive error message for the ABEND condition
     * @param cause   the underlying exception that triggered the ABEND
     * @return the constructed exception (for use with {@code throw} at call site)
     */
    private CardDemoException abendProgram(String message, Throwable cause) {
        log.error("ABENDING PROGRAM");
        return new CardDemoException(message + ", ABCODE=999", cause);
    }

    /**
     * Terminates processing with an ABEND-equivalent exception (no root cause).
     *
     * <p>Overload of {@link #abendProgram(String, Throwable)} for cases where
     * there is no underlying caught exception — e.g., a null record or
     * unexpected state detected during processing.</p>
     *
     * @param message descriptive error message for the ABEND condition
     * @return the constructed exception (for use with {@code throw} at call site)
     */
    private CardDemoException abendProgram(String message) {
        log.error("ABENDING PROGRAM");
        return new CardDemoException(message + ", ABCODE=999");
    }
}

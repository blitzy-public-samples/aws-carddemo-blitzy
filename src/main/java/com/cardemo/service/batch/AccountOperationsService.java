/*
 * AccountOperationsService.java — Spring @Service (← CBACT03C.cbl)
 *
 * Source COBOL Program: app/cbl/CBACT03C.cbl
 *   Program-ID: CBACT03C
 *   Type: Batch COBOL Program
 *   Function: Read and print account cross reference data file.
 *
 * Source Copybook: app/cpy/CVACT03Y.cpy
 *   Record: CARD-XREF-RECORD (50 bytes)
 *   Fields: XREF-CARD-NUM PIC X(16), XREF-CUST-ID PIC 9(09),
 *           XREF-ACCT-ID PIC 9(11), FILLER PIC X(14)
 *
 * VSAM Datasets Opened by CBACT03C.cbl:
 *   XREFFILE  — CARDXREF VSAM KSDS (primary data source, sequential read)
 *   ACCTFILE  — ACCTDATA VSAM KSDS (structural dependency)
 *   CARDFILE  — CARDDATA VSAM KSDS (structural dependency)
 *   TCATBALF  — TCATBALF VSAM KSDS (structural dependency)
 *
 * Transformation:
 *   COBOL OPEN/CLOSE → JPA manages (connection pool)
 *   COBOL READ NEXT  → CardXrefRepository.findAll() + iteration
 *   COBOL DISPLAY     → SLF4J log.info()
 *   COBOL FILE STATUS → FileStatusException with status code
 *   COBOL ABEND       → CardDemoException
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.entity.CardXref;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CategoryBalanceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Batch service for additional account operations — translates CBACT03C.cbl.
 *
 * <p>This service reads and displays all card cross-reference records from the
 * CARDXREF dataset for operator inspection, faithfully reproducing the batch
 * processing logic of the original COBOL program CBACT03C.</p>
 *
 * <h3>COBOL Program: CBACT03C.cbl</h3>
 * <p>Function: Read and print account cross reference data file.
 * The original program opens the XREFFILE (VSAM KSDS, CVACT03Y.cpy record
 * layout), reads sequentially through all records, displays each record's
 * fields, then closes the file. Additional structural dependencies on ACCTFILE,
 * CARDFILE, and TCATBALF are injected via their respective JPA repositories for
 * comprehensive batch operation support.</p>
 *
 * <h3>COBOL Paragraph → Java Method Traceability (100% coverage)</h3>
 * <table>
 *   <caption>Complete paragraph mapping from CBACT03C.cbl</caption>
 *   <tr><th>COBOL Paragraph</th><th>Java Equivalent</th><th>Notes</th></tr>
 *   <tr><td>PROCEDURE DIVISION</td>
 *       <td>{@link #processAccountOperations()}</td>
 *       <td>Main entry: log start, read loop, log end</td></tr>
 *   <tr><td>0000-XREFFILE-OPEN</td><td>N/A — JPA manages</td>
 *       <td>OPEN INPUT XREFFILE → JPA connection pool</td></tr>
 *   <tr><td>0100-ACCTFILE-OPEN</td><td>N/A — JPA manages</td>
 *       <td>OPEN INPUT ACCTFILE → JPA connection pool</td></tr>
 *   <tr><td>0200-CARDFILE-OPEN</td><td>N/A — JPA manages</td>
 *       <td>OPEN INPUT CARDFILE → JPA connection pool</td></tr>
 *   <tr><td>0300-TCATBALF-OPEN</td><td>N/A — JPA manages</td>
 *       <td>OPEN INPUT TCATBAL → JPA connection pool</td></tr>
 *   <tr><td>1000-XREFFILE-GET-NEXT</td>
 *       <td>Iterator in {@link #processAccountOperations()}</td>
 *       <td>READ NEXT → findAll() + for-each iteration</td></tr>
 *   <tr><td>1100-DISPLAY-XREF-RECORD</td>
 *       <td>{@link #displayXrefRecord(CardXref)}</td>
 *       <td>DISPLAY fields → SLF4J log.info() calls</td></tr>
 *   <tr><td>9000-XREFFILE-CLOSE</td><td>N/A — JPA manages</td>
 *       <td>CLOSE XREFFILE → JPA connection pool</td></tr>
 *   <tr><td>9100-ACCTFILE-CLOSE</td><td>N/A — JPA manages</td>
 *       <td>CLOSE ACCTFILE → JPA connection pool</td></tr>
 *   <tr><td>9200-CARDFILE-CLOSE</td><td>N/A — JPA manages</td>
 *       <td>CLOSE CARDFILE → JPA connection pool</td></tr>
 *   <tr><td>9300-TCATBALF-CLOSE</td><td>N/A — JPA manages</td>
 *       <td>CLOSE TCATBAL → JPA connection pool</td></tr>
 *   <tr><td>9910-DISPLAY-IO-STATUS</td>
 *       <td>Error logging via {@link FileStatusException}</td>
 *       <td>FILE STATUS formatting → log.error()</td></tr>
 *   <tr><td>9999-ABEND-PROGRAM</td>
 *       <td>Throw {@link CardDemoException}</td>
 *       <td>CEE3ABD ABCODE=999 → runtime exception</td></tr>
 * </table>
 *
 * <h3>Working Storage Translation</h3>
 * <ul>
 *   <li>{@code END-OF-FILE PIC X(01) VALUE 'N'} → Natural termination of
 *       for-each loop over {@code findAll()} result (no explicit flag needed)</li>
 *   <li>{@code APPL-RESULT PIC S9(9) COMP} with 88-level conditions
 *       (APPL-AOK VALUE 0, APPL-EOF VALUE 16) → Exception handling:
 *       success = normal flow, EOF = loop completion, error = exception</li>
 *   <li>{@code IO-STATUS / IO-STATUS-04} → {@link FileStatusException}
 *       carries the formatted status code</li>
 *   <li>{@code ABCODE PIC S9(9) BINARY VALUE 999} →
 *       {@link CardDemoException} message</li>
 * </ul>
 *
 * @see CardXref
 * @see CardXrefRepository
 * @see com.cardemo.common.exception.CardDemoException
 * @see com.cardemo.common.exception.FileStatusException
 */
@Service
public class AccountOperationsService {

    /**
     * SLF4J logger replacing all COBOL DISPLAY statements from CBACT03C.cbl.
     *
     * <p>Translates the following COBOL DISPLAY usages:</p>
     * <ul>
     *   <li>{@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'}</li>
     *   <li>{@code DISPLAY CARD-XREF-RECORD} (field-by-field in 1100-DISPLAY-XREF-RECORD)</li>
     *   <li>{@code DISPLAY 'ERROR READING XREFFILE'} (9910-DISPLAY-IO-STATUS path)</li>
     *   <li>{@code DISPLAY 'FILE STATUS IS: NNNN'} (IO status formatting)</li>
     *   <li>{@code DISPLAY 'ABENDING PROGRAM'} (9999-ABEND-PROGRAM)</li>
     *   <li>{@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'}</li>
     * </ul>
     */
    private static final Logger log = LoggerFactory.getLogger(AccountOperationsService.class);

    /**
     * Repository for XREFFILE (CARDXREF VSAM KSDS, 50-byte records).
     * Primary data source for this service — provides {@code findAll()} for
     * sequential batch reading, translating the COBOL OPEN INPUT / READ NEXT /
     * CLOSE pattern from paragraphs 0000-XREFFILE-OPEN, 1000-XREFFILE-GET-NEXT,
     * and 9000-XREFFILE-CLOSE.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Repository for ACCTFILE (ACCTDATA VSAM KSDS, 300-byte records).
     * Structural dependency corresponding to COBOL paragraphs
     * 0100-ACCTFILE-OPEN and 9100-ACCTFILE-CLOSE — JPA manages the
     * underlying database connection lifecycle.
     */
    private final AccountRepository accountRepository;

    /**
     * Repository for CARDFILE (CARDDATA VSAM KSDS, 150-byte records).
     * Structural dependency corresponding to COBOL paragraphs
     * 0200-CARDFILE-OPEN and 9200-CARDFILE-CLOSE — JPA manages the
     * underlying database connection lifecycle.
     */
    private final CardRepository cardRepository;

    /**
     * Repository for TCATBALF (TCATBALF VSAM KSDS, 50-byte records).
     * Structural dependency corresponding to COBOL paragraphs
     * 0300-TCATBALF-OPEN and 9300-TCATBALF-CLOSE — JPA manages the
     * underlying database connection lifecycle.
     */
    private final CategoryBalanceRepository categoryBalanceRepository;

    /**
     * Constructs the AccountOperationsService with all required repository
     * dependencies.
     *
     * <p>Injects all four repositories corresponding to the VSAM files that the
     * original CBACT03C.cbl program opens in its ENVIRONMENT DIVISION /
     * FILE-CONTROL section: XREFFILE, ACCTFILE, CARDFILE, and TCATBALF.
     * While the current implementation primarily uses {@code cardXrefRepository}
     * for the sequential cross-reference read, all four repositories are injected
     * to maintain structural parity with the COBOL program's file
     * dependencies.</p>
     *
     * @param cardXrefRepository        repository for XREFFILE (CARDXREF VSAM)
     * @param accountRepository         repository for ACCTFILE (ACCTDATA VSAM)
     * @param cardRepository            repository for CARDFILE (CARDDATA VSAM)
     * @param categoryBalanceRepository repository for TCATBALF (TCATBALF VSAM)
     */
    @Autowired
    public AccountOperationsService(CardXrefRepository cardXrefRepository,
                                    AccountRepository accountRepository,
                                    CardRepository cardRepository,
                                    CategoryBalanceRepository categoryBalanceRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.categoryBalanceRepository = categoryBalanceRepository;
    }

    /**
     * Processes all account operations by sequentially reading and displaying
     * every card cross-reference record.
     *
     * <p>Translates the CBACT03C.cbl PROCEDURE DIVISION main logic:</p>
     * <ol>
     *   <li>Log execution start
     *       ({@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'})</li>
     *   <li>Open all VSAM files — N/A, JPA manages connections
     *       (paragraphs 0000/0100/0200/0300)</li>
     *   <li>Read all XREFFILE records sequentially via
     *       {@link CardXrefRepository#findAll()} — translates the
     *       {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop with
     *       {@code 1000-XREFFILE-GET-NEXT}</li>
     *   <li>Display each record's fields for operator inspection via
     *       {@link #displayXrefRecord(CardXref)} — translates
     *       {@code 1100-DISPLAY-XREF-RECORD}</li>
     *   <li>Close all VSAM files — N/A, JPA manages connections
     *       (paragraphs 9000/9100/9200/9300)</li>
     *   <li>Log execution end
     *       ({@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'})</li>
     * </ol>
     *
     * <p><strong>COBOL File Status Mapping:</strong></p>
     * <ul>
     *   <li>XREFFILE-STATUS '00' → success (normal iteration)</li>
     *   <li>XREFFILE-STATUS '10' → EOF (natural end of for-each loop)</li>
     *   <li>Any other status → {@link FileStatusException} with status code '12'
     *       (error), followed by {@link CardDemoException} for abend</li>
     * </ul>
     *
     * <p><strong>COBOL Working Storage Translation:</strong></p>
     * <ul>
     *   <li>{@code END-OF-FILE PIC X(01) VALUE 'N'} → Natural loop termination
     *       (no explicit boolean flag needed; the for-each loop over
     *       {@code findAll()} result handles EOF implicitly)</li>
     *   <li>{@code APPL-RESULT} with 88-level APPL-AOK (0) / APPL-EOF (16) →
     *       Exception handling: success = normal flow, EOF = loop end,
     *       error = catch block</li>
     * </ul>
     *
     * @throws CardDemoException if an unrecoverable error occurs during
     *         processing, translating COBOL paragraph 9999-ABEND-PROGRAM
     *         (CALL CEE3ABD with ABCODE=999)
     */
    public void processAccountOperations() {
        // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'
        log.info("START OF EXECUTION OF PROGRAM CBACT03C");

        try {
            // ---------------------------------------------------------------
            // 0000-XREFFILE-OPEN  — N/A: JPA manages XREFFILE connections
            // 0100-ACCTFILE-OPEN  — N/A: JPA manages ACCTFILE connections
            // 0200-CARDFILE-OPEN  — N/A: JPA manages CARDFILE connections
            // 0300-TCATBALF-OPEN  — N/A: JPA manages TCATBALF connections
            // ---------------------------------------------------------------

            // 1000-XREFFILE-GET-NEXT: PERFORM UNTIL END-OF-FILE = 'Y'
            // The COBOL sequential READ NEXT loop with FILE STATUS checks
            // ('00' = success, '10' = EOF, other = error) is translated to
            // a single findAll() call followed by for-each iteration.
            // EOF (status '10') is handled by natural loop termination.
            List<CardXref> xrefRecords = cardXrefRepository.findAll();

            for (CardXref xref : xrefRecords) {
                // 1100-DISPLAY-XREF-RECORD: Display each record's fields
                // for operator inspection (matches COBOL DISPLAY statements
                // in both the main loop and 1000-XREFFILE-GET-NEXT paragraph)
                displayXrefRecord(xref);
            }

            // ---------------------------------------------------------------
            // 9000-XREFFILE-CLOSE  — N/A: JPA manages XREFFILE connections
            // 9100-ACCTFILE-CLOSE  — N/A: JPA manages ACCTFILE connections
            // 9200-CARDFILE-CLOSE  — N/A: JPA manages CARDFILE connections
            // 9300-TCATBALF-CLOSE  — N/A: JPA manages TCATBALF connections
            // ---------------------------------------------------------------

        } catch (CardDemoException cde) {
            // Re-throw application exceptions without double-wrapping
            throw cde;
        } catch (Exception e) {
            // ---------------------------------------------------------------
            // 9910-DISPLAY-IO-STATUS: Format and display IO status code
            // In COBOL, this paragraph formats the 4-character IO status
            // (IO-STATUS-04) from the two-byte XREFFILE-STATUS field and
            // displays: 'FILE STATUS IS: NNNN' followed by the formatted code.
            // ---------------------------------------------------------------
            FileStatusException fileStatusEx = new FileStatusException("12",
                    "ERROR READING XREFFILE");
            log.error("ERROR READING XREFFILE");
            log.error("FILE STATUS IS: {}", fileStatusEx.getFileStatusCode());

            // ---------------------------------------------------------------
            // 9999-ABEND-PROGRAM: Fatal error termination
            // COBOL: DISPLAY 'ABENDING PROGRAM'
            //        MOVE 0 TO TIMING
            //        MOVE 999 TO ABCODE
            //        CALL 'CEE3ABD'
            // Java: Throw CardDemoException to terminate batch processing
            // ---------------------------------------------------------------
            log.error("ABENDING PROGRAM");
            throw new CardDemoException(
                    "ABENDING PROGRAM - Account operations failed: "
                            + e.getMessage());
        }

        // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'
        log.info("END OF EXECUTION OF PROGRAM CBACT03C");
    }

    /**
     * Displays/logs a single cross-reference record's fields for operator
     * inspection.
     *
     * <p>Translates CBACT03C.cbl paragraph 1100-DISPLAY-XREF-RECORD, which
     * uses COBOL {@code DISPLAY} statements to output each field of the
     * {@code CARD-XREF-RECORD} (CVACT03Y.cpy) to the operator console.
     * In the Java implementation, each COBOL {@code DISPLAY} is mapped to an
     * SLF4J {@code log.info()} call with structured field output.</p>
     *
     * <p>In the original COBOL, the entire 50-byte {@code CARD-XREF-RECORD}
     * is displayed as a raw string via {@code DISPLAY CARD-XREF-RECORD}.
     * The Java translation logs each business field individually for
     * improved readability and structured logging support:</p>
     *
     * <ul>
     *   <li>{@code XREF-CARD-NUM} — 16-character card number
     *       ({@code PIC X(16)})</li>
     *   <li>{@code XREF-CUST-ID} — 9-digit customer identifier
     *       ({@code PIC 9(09)})</li>
     *   <li>{@code XREF-ACCT-ID} — 11-digit account identifier
     *       ({@code PIC 9(11)})</li>
     * </ul>
     *
     * <p>The 14-byte FILLER field from the COBOL record layout is not logged
     * as it contains only padding bytes with no business meaning.</p>
     *
     * @param xref the card cross-reference record to display; must not be null
     */
    public void displayXrefRecord(CardXref xref) {
        log.info("XREF-CARD-NUM: {}", xref.getXrefCardNum());
        log.info("XREF-CUST-ID:  {}", xref.getCustId());
        log.info("XREF-ACCT-ID:  {}", xref.getAccountId());
    }

    /**
     * Returns the account repository for structural access to ACCTDATA.
     *
     * <p>Provides access to the injected {@link AccountRepository} which
     * corresponds to the COBOL ACCTFILE VSAM dataset. This accessor enables
     * extended batch operations that may require account data access beyond
     * the core cross-reference reading logic.</p>
     *
     * @return the account repository instance
     */
    protected AccountRepository getAccountRepository() {
        return accountRepository;
    }

    /**
     * Returns the card repository for structural access to CARDDATA.
     *
     * <p>Provides access to the injected {@link CardRepository} which
     * corresponds to the COBOL CARDFILE VSAM dataset. This accessor enables
     * extended batch operations that may require card data access beyond
     * the core cross-reference reading logic.</p>
     *
     * @return the card repository instance
     */
    protected CardRepository getCardRepository() {
        return cardRepository;
    }

    /**
     * Returns the category balance repository for structural access to TCATBALF.
     *
     * <p>Provides access to the injected {@link CategoryBalanceRepository}
     * which corresponds to the COBOL TCATBALF VSAM dataset. This accessor
     * enables extended batch operations that may require category balance
     * data access beyond the core cross-reference reading logic.</p>
     *
     * @return the category balance repository instance
     */
    protected CategoryBalanceRepository getCategoryBalanceRepository() {
        return categoryBalanceRepository;
    }
}

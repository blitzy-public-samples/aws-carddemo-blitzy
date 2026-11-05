/*
 * AccountProcessingService.java — Spring @Service (← CBACT02C.cbl)
 *
 * Source COBOL program: app/cbl/CBACT02C.cbl
 * Source copybook:      app/cpy/CVACT02Y.cpy (CARD-RECORD, 150-byte KSDS)
 *
 * COBOL Function: Batch program that opens the CARDDATA VSAM indexed KSDS
 *   in sequential mode, reads every card record, displays each record for
 *   operator inspection, and closes the file. If any I/O error occurs at
 *   open, read, or close time, the program formats the VSAM file status,
 *   displays it, and invokes CEE3ABD to abend with ABCODE=999.
 *
 * Java Translation Strategy:
 *   - OPEN INPUT / READ / CLOSE  →  cardRepository.findAll() (JPA manages
 *     the full lifecycle; a single findAll() replaces the open–read-loop–close
 *     pattern from paragraphs 0000-CARDFILE-OPEN, 1000-CARDFILE-GET-NEXT,
 *     and 9000-CARDFILE-CLOSE)
 *   - DISPLAY CARD-RECORD  →  displayCardRecord(Card) with PII masking
 *     on CARD-NUM and CARD-CVV-CD (the original COBOL displays raw values,
 *     but PII protection is a migration requirement)
 *   - FILE STATUS error handling  →  FileStatusException with the equivalent
 *     two-character VSAM status code for diagnostic traceability
 *   - 9999-ABEND-PROGRAM (CALL CEE3ABD)  →  CardDemoException for fatal,
 *     non-I/O errors; FileStatusException for I/O-specific abend paths
 *
 * COBOL Paragraph → Java Method Traceability:
 *   PROCEDURE DIVISION         →  processCardFile()
 *   0000-CARDFILE-OPEN         →  (JPA-managed inside openAndReadCardFile())
 *   1000-CARDFILE-GET-NEXT     →  (iterator inside processCardFile() for-each)
 *   1100-DISPLAY-CARD-RECORD   →  displayCardRecord(Card)
 *   9000-CARDFILE-CLOSE        →  (JPA-managed inside openAndReadCardFile())
 *   9910-DISPLAY-IO-STATUS     →  (logging inside openAndReadCardFile() catch)
 *   9999-ABEND-PROGRAM         →  CardDemoException / FileStatusException throw
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.entity.Card;
import com.cardemo.repository.CardRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Batch service that reads and logs all card records from the CARDDATA dataset.
 *
 * <p>This service is a faithful translation of COBOL batch program
 * {@code CBACT02C.cbl}, which opens the CARDDATA VSAM KSDS in sequential
 * access mode, reads every card record (150-byte {@code CARD-RECORD} defined
 * in {@code CVACT02Y.cpy}), displays each record for operator inspection,
 * and closes the file.</p>
 *
 * <h2>COBOL Program Structure</h2>
 * <pre>
 * PROCEDURE DIVISION
 *   DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'
 *   PERFORM 0000-CARDFILE-OPEN
 *   PERFORM UNTIL END-OF-FILE = 'Y'
 *       PERFORM 1000-CARDFILE-GET-NEXT
 *       IF END-OF-FILE = 'N'
 *           DISPLAY CARD-RECORD
 *       END-IF
 *   END-PERFORM
 *   PERFORM 9000-CARDFILE-CLOSE
 *   DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'
 *   GOBACK
 * </pre>
 *
 * <h2>Migration Notes</h2>
 * <ul>
 *   <li>The sequential VSAM file access pattern (OPEN → READ loop → CLOSE)
 *       is replaced by a single {@link CardRepository#findAll()} call. JPA
 *       manages connection lifecycle automatically.</li>
 *   <li>COBOL DISPLAY of the raw 150-byte record is replaced by structured
 *       logging with PII masking on {@code CARD-NUM} and {@code CARD-CVV-CD}.</li>
 *   <li>VSAM FILE STATUS codes ({@code '00'} success, {@code '10'} EOF,
 *       other = error) are mapped to normal flow, loop termination, and
 *       {@link FileStatusException} respectively.</li>
 *   <li>The {@code 9999-ABEND-PROGRAM} paragraph (CALL CEE3ABD with
 *       ABCODE=999) is mapped to throwing {@link CardDemoException} or
 *       {@link FileStatusException}.</li>
 * </ul>
 *
 * @see Card
 * @see CardRepository
 */
@Service
public class AccountProcessingService {

    /**
     * SLF4J logger replacing all COBOL DISPLAY statements in CBACT02C.cbl.
     * Logs start/end execution banners, card record fields (with PII masking),
     * I/O status formatting, and abend messages.
     */
    private static final Logger log =
            LoggerFactory.getLogger(AccountProcessingService.class);

    /**
     * Program identifier matching COBOL {@code PROGRAM-ID. CBACT02C}.
     * Used in log banners to preserve traceability with the original program.
     */
    private static final String PROGRAM_ID = "CBACT02C";

    /**
     * Abend code matching COBOL {@code MOVE 999 TO ABCODE} in the
     * {@code 9999-ABEND-PROGRAM} paragraph. Used in exception messages
     * and log output for diagnostic traceability.
     */
    private static final int ABEND_CODE = 999;

    /**
     * VSAM APPL-RESULT code for unexpected I/O errors (non-'00', non-'10').
     * In COBOL: {@code MOVE 12 TO APPL-RESULT} in both
     * {@code 0000-CARDFILE-OPEN} and {@code 1000-CARDFILE-GET-NEXT}.
     */
    private static final String IO_STATUS_ERROR = "12";

    /**
     * Spring Data JPA repository providing sequential read access to the
     * CARDDATA VSAM KSDS dataset. Injected via constructor injection.
     * Replaces the COBOL {@code SELECT CARDFILE-FILE ASSIGN TO CARDFILE}
     * file definition.
     */
    private final CardRepository cardRepository;

    /**
     * Constructs an {@code AccountProcessingService} with the required
     * {@link CardRepository} dependency.
     *
     * <p>Constructor injection replaces the COBOL {@code FILE-CONTROL} SELECT
     * statement and {@code FD CARDFILE-FILE} declaration that bind the program
     * to the CARDDATA VSAM dataset.</p>
     *
     * @param cardRepository the JPA repository for card data access;
     *                       must not be {@code null}
     */
    @Autowired
    public AccountProcessingService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    // =========================================================================
    // Public Methods — Exported (members_exposed)
    // =========================================================================

    /**
     * Main entry point for batch card file processing.
     *
     * <p>Translates the COBOL {@code PROCEDURE DIVISION} of CBACT02C.cbl.
     * Sequentially reads all card records from the CARDDATA dataset and
     * logs each record for operator inspection with PII masking.</p>
     *
     * <h3>COBOL Paragraph Mapping</h3>
     * <table>
     *   <caption>PROCEDURE DIVISION flow</caption>
     *   <tr><th>COBOL</th><th>Java</th></tr>
     *   <tr><td>{@code DISPLAY 'START OF EXECUTION...'}</td>
     *       <td>{@code log.info("START OF EXECUTION...")}</td></tr>
     *   <tr><td>{@code PERFORM 0000-CARDFILE-OPEN}</td>
     *       <td>{@code openAndReadCardFile()} — JPA manages</td></tr>
     *   <tr><td>{@code PERFORM UNTIL END-OF-FILE = 'Y'}</td>
     *       <td>{@code for (Card card : cards)}</td></tr>
     *   <tr><td>{@code DISPLAY CARD-RECORD}</td>
     *       <td>{@link #displayCardRecord(Card)}</td></tr>
     *   <tr><td>{@code PERFORM 9000-CARDFILE-CLOSE}</td>
     *       <td>JPA manages — implicit on findAll() completion</td></tr>
     *   <tr><td>{@code DISPLAY 'END OF EXECUTION...'}</td>
     *       <td>{@code log.info("END OF EXECUTION...")}</td></tr>
     *   <tr><td>{@code GOBACK}</td><td>Method return</td></tr>
     * </table>
     *
     * @throws FileStatusException if a data access error occurs while reading
     *         the card file (maps to VSAM FILE STATUS error + 9999-ABEND-PROGRAM)
     * @throws CardDemoException if an unexpected fatal error occurs during
     *         processing (maps to 9999-ABEND-PROGRAM with ABCODE=999)
     */
    public void processCardFile() {
        // COBOL: DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_ID);

        // 0000-CARDFILE-OPEN + 1000-CARDFILE-GET-NEXT (full sequential read)
        // + 9000-CARDFILE-CLOSE — all managed by JPA through findAll()
        List<Card> cards = openAndReadCardFile();

        // COBOL: PERFORM UNTIL END-OF-FILE = 'Y'
        //            IF END-OF-FILE = 'N'
        //                PERFORM 1000-CARDFILE-GET-NEXT
        //                IF END-OF-FILE = 'N'
        //                    DISPLAY CARD-RECORD
        //                END-IF
        //            END-IF
        //        END-PERFORM
        //
        // In Java, findAll() returns all records; the for-each loop replaces
        // the PERFORM UNTIL END-OF-FILE = 'Y' pattern. Each iteration
        // corresponds to a successful 1000-CARDFILE-GET-NEXT (APPL-AOK)
        // followed by DISPLAY CARD-RECORD (→ 1100-DISPLAY-CARD-RECORD).
        // The loop naturally terminates at the end of the list, which is
        // equivalent to APPL-EOF setting END-OF-FILE = 'Y'.
        for (Card card : cards) {
            displayCardRecord(card);
        }

        // COBOL: DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'
        log.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
    }

    /**
     * Displays a single card record with PII masking for operator inspection.
     *
     * <p>Maps to the {@code 1100-DISPLAY-CARD-RECORD} paragraph concept and
     * the inline {@code DISPLAY CARD-RECORD} statement at line 78 of
     * CBACT02C.cbl. The original COBOL displays the raw 150-byte record
     * (CARD-NUM + CARD-ACCT-ID + CARD-CVV-CD + CARD-EMBOSSED-NAME +
     * CARD-EXPIRAION-DATE + CARD-ACTIVE-STATUS + FILLER). In the Java
     * translation, individual fields are logged with PII masking on
     * {@code CARD-NUM} and {@code CARD-CVV-CD}.</p>
     *
     * <h3>CVACT02Y.cpy Field Mapping</h3>
     * <table>
     *   <caption>Card record fields logged</caption>
     *   <tr><th>COBOL Field</th><th>Java Getter</th><th>Masking</th></tr>
     *   <tr><td>CARD-NUM PIC X(16)</td><td>getCardNum()</td><td>Last 4 shown</td></tr>
     *   <tr><td>CARD-ACCT-ID PIC 9(11)</td><td>getAccountId()</td><td>None</td></tr>
     *   <tr><td>CARD-CVV-CD PIC 9(03)</td><td>getCvvCode()</td><td>Fully masked</td></tr>
     *   <tr><td>CARD-EMBOSSED-NAME PIC X(50)</td><td>getEmbossedName()</td><td>None</td></tr>
     *   <tr><td>CARD-EXPIRAION-DATE PIC X(10)</td><td>getExpirationDate()</td><td>None</td></tr>
     *   <tr><td>CARD-ACTIVE-STATUS PIC X(01)</td><td>getActiveStatus()</td><td>None</td></tr>
     * </table>
     *
     * @param card the {@link Card} entity to display; must not be {@code null}
     * @throws CardDemoException if the card parameter is {@code null}
     *         (maps to 9999-ABEND-PROGRAM for unexpected null record)
     */
    public void displayCardRecord(Card card) {
        // Defensive null check — COBOL does not encounter null records from
        // VSAM sequential reads, but Java collections could theoretically
        // contain null entries. Maps to 9999-ABEND-PROGRAM for fatal errors.
        if (card == null) {
            log.error("ABENDING PROGRAM - null card record encountered");
            throw new CardDemoException(
                    "Null card record encountered in " + PROGRAM_ID
                    + " (ABCODE=" + ABEND_CODE + ")");
        }

        // COBOL: DISPLAY CARD-RECORD
        // Retrieve all fields from CVACT02Y.cpy CARD-RECORD and log them.
        // PII fields (CARD-NUM, CARD-CVV-CD) are masked for security.
        String maskedCardNum = maskCardNumber(card.getCardNum());
        String maskedCvv = maskCvv(card.getCvvCode());

        log.info(
                "CARD-RECORD: NUM={} ACCT-ID={} CVV={} NAME={} EXP={} STATUS={}",
                maskedCardNum,
                card.getAccountId(),
                maskedCvv,
                card.getEmbossedName(),
                card.getExpirationDate(),
                card.getActiveStatus());
    }

    // =========================================================================
    // Private Helper Methods
    // =========================================================================

    /**
     * Opens and reads all card records from the CARDDATA dataset.
     *
     * <p>Combines the COBOL paragraphs {@code 0000-CARDFILE-OPEN},
     * {@code 1000-CARDFILE-GET-NEXT} (full sequential read), and
     * {@code 9000-CARDFILE-CLOSE} into a single JPA-managed operation.
     * If any data access error occurs, the I/O status is logged (mapping
     * to {@code 9910-DISPLAY-IO-STATUS}) and a {@link FileStatusException}
     * is thrown (mapping to {@code 9999-ABEND-PROGRAM}).</p>
     *
     * <h3>COBOL Error Handling Mapping</h3>
     * <pre>
     * OPEN/READ/CLOSE error:
     *   DISPLAY 'ERROR READING CARDFILE'            →  log.error(...)
     *   MOVE CARDFILE-STATUS TO IO-STATUS            →  statusCode = "12"
     *   PERFORM 9910-DISPLAY-IO-STATUS               →  log.error("FILE STATUS IS: ...")
     *   PERFORM 9999-ABEND-PROGRAM                   →  throw FileStatusException
     * </pre>
     *
     * @return a non-null list of all {@link Card} records in the dataset
     * @throws FileStatusException if data access fails, with status code
     *         {@code "12"} (APPL-RESULT=12 for unexpected I/O errors)
     */
    private List<Card> openAndReadCardFile() {
        try {
            // COBOL equivalent:
            //   OPEN INPUT CARDFILE-FILE        (0000-CARDFILE-OPEN)
            //   READ CARDFILE-FILE INTO CARD-RECORD  (1000-CARDFILE-GET-NEXT, looped)
            //   CLOSE CARDFILE-FILE             (9000-CARDFILE-CLOSE)
            // JPA handles the entire lifecycle through findAll().
            return cardRepository.findAll();
        } catch (Exception ex) {
            // Maps to the error paths in 0000-CARDFILE-OPEN, 1000-CARDFILE-GET-NEXT,
            // or 9000-CARDFILE-CLOSE when CARDFILE-STATUS is not '00' (and not '10'):
            //
            // COBOL: MOVE 12 TO APPL-RESULT
            //        DISPLAY 'ERROR READING CARDFILE'
            //        MOVE CARDFILE-STATUS TO IO-STATUS
            //        PERFORM 9910-DISPLAY-IO-STATUS
            //        PERFORM 9999-ABEND-PROGRAM

            // 9910-DISPLAY-IO-STATUS equivalent: format and log I/O error status
            log.error("ERROR READING CARDFILE");
            log.error("FILE STATUS IS: {}", IO_STATUS_ERROR);

            // 9999-ABEND-PROGRAM equivalent: CALL CEE3ABD with ABCODE=999
            log.error("ABENDING PROGRAM with ABCODE={}", ABEND_CODE);
            throw new FileStatusException(IO_STATUS_ERROR,
                    "Error reading CARDFILE (ABCODE=" + ABEND_CODE + ")");
        }
    }

    /**
     * Masks a credit card number for PII-safe logging.
     *
     * <p>The original COBOL {@code DISPLAY CARD-RECORD} outputs the full
     * 16-character CARD-NUM in plaintext. In the Java migration, PII
     * protection requires masking all but the last 4 characters.</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code "4111111111111111"} → {@code "************1111"}</li>
     *   <li>{@code null} → {@code "****"}</li>
     *   <li>{@code "12"} → {@code "****"} (too short to extract last 4)</li>
     * </ul>
     *
     * @param cardNum the raw card number string, may be {@code null}
     * @return a masked representation safe for log output
     */
    private static String maskCardNumber(String cardNum) {
        if (cardNum == null || cardNum.length() < 4) {
            return "****";
        }
        int length = cardNum.length();
        return "*".repeat(length - 4) + cardNum.substring(length - 4);
    }

    /**
     * Masks a CVV code for PII-safe logging.
     *
     * <p>The original COBOL {@code DISPLAY CARD-RECORD} outputs the full
     * 3-digit CARD-CVV-CD in plaintext. CVV codes must NEVER appear in
     * log output per PCI DSS requirements. The entire value is masked.</p>
     *
     * @param cvvCode the raw CVV code string, may be {@code null}
     * @return a fully masked representation (asterisks matching the
     *         original length, or {@code "***"} if {@code null})
     */
    private static String maskCvv(String cvvCode) {
        if (cvvCode == null) {
            return "***";
        }
        return "*".repeat(cvvCode.length());
    }
}

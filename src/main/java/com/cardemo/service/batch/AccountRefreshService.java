/*
 * AccountRefreshService.java — Spring @Service (← CBACT01C.cbl)
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * =========================================================================
 * COBOL Source: app/cbl/CBACT01C.cbl
 * Copybook:     app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD, 300 bytes)
 * VSAM Dataset: AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
 * Program Type: Batch utility — sequential read and display of all accounts
 * =========================================================================
 *
 * COBOL Paragraph-to-Java Method Traceability:
 *
 *   COBOL Paragraph             | Java Method / Mechanism
 *   ----------------------------|---------------------------------------------
 *   PROCEDURE DIVISION          | refreshAccounts()
 *   0000-ACCTFILE-OPEN          | JPA connection pool (automatic)
 *   1000-ACCTFILE-GET-NEXT      | for-each loop over accountRepository.findAll()
 *   1100-DISPLAY-ACCT-RECORD    | displayAccountRecord(Account)
 *   9000-ACCTFILE-CLOSE         | JPA connection pool (automatic)
 *   9910-DISPLAY-IO-STATUS      | FileStatusException + log.error()
 *   9999-ABEND-PROGRAM          | throw CardDemoException (ABCODE=999)
 *
 * Working Storage Mapping:
 *   ACCTFILE-STATUS PIC X(02)   → JPA exception handling (no explicit status)
 *   IO-STATUS PIC X(02)         → FileStatusException.getFileStatusCode()
 *   APPL-RESULT PIC S9(9) COMP  → Method return / exception flow
 *     88 APPL-AOK VALUE 0       → Normal flow (no exception)
 *     88 APPL-EOF VALUE 16      → End of for-each iteration
 *   END-OF-FILE PIC X(01)       → for-each loop termination (implicit)
 *   ABCODE PIC S9(9) BINARY     → CardDemoException message (999)
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.entity.Account;
import com.cardemo.repository.AccountRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Batch service for sequential reading and logging of all account master records.
 *
 * <p>Translates COBOL program CBACT01C.cbl — a batch utility that opens the
 * ACCTDATA VSAM KSDS file (organization INDEXED, access mode SEQUENTIAL),
 * reads every account record in primary key (ACCT-ID) order, formats and
 * displays 11 business fields per record for operator inspection, and then
 * closes the dataset. In the migrated architecture, this service is invoked
 * by {@code AccountLoadJobConfig} as part of seed data loading and account
 * master refresh operations.</p>
 *
 * <h3>COBOL Control Flow Preserved</h3>
 * <ol>
 *   <li>Display start banner: {@code 'START OF EXECUTION OF PROGRAM CBACT01C'}</li>
 *   <li>Open file (JPA manages connection lifecycle automatically)</li>
 *   <li>Loop: read next record → display 11 fields → repeat until EOF</li>
 *   <li>Close file (JPA manages connection lifecycle automatically)</li>
 *   <li>Display end banner: {@code 'END OF EXECUTION OF PROGRAM CBACT01C'}</li>
 * </ol>
 *
 * <h3>Error Handling</h3>
 * <p>The COBOL error path (non-00/non-10 file status → 9910-DISPLAY-IO-STATUS
 * → 9999-ABEND-PROGRAM with ABCODE=999) is translated into a Java exception
 * chain: any data access failure is caught, the IO status is logged via
 * {@link FileStatusException}, and a {@link CardDemoException} is thrown to
 * abort processing (equivalent to {@code CALL 'CEE3ABD'}).</p>
 *
 * <h3>Account Fields Displayed (from 1100-DISPLAY-ACCT-RECORD)</h3>
 * <table>
 *   <caption>COBOL Field to Account Entity Getter Mapping</caption>
 *   <tr><th>COBOL Field</th><th>Account Getter</th><th>Type</th></tr>
 *   <tr><td>ACCT-ID</td><td>{@code getAcctId()}</td><td>String (11 chars)</td></tr>
 *   <tr><td>ACCT-ACTIVE-STATUS</td><td>{@code getActiveStatus()}</td><td>String (1 char)</td></tr>
 *   <tr><td>ACCT-CURR-BAL</td><td>{@code getCurrBal()}</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-CREDIT-LIMIT</td><td>{@code getCreditLimit()}</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-CASH-CREDIT-LIMIT</td><td>{@code getCashCreditLimit()}</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-OPEN-DATE</td><td>{@code getOpenDate()}</td><td>String (10 chars)</td></tr>
 *   <tr><td>ACCT-EXPIRAION-DATE</td><td>{@code getExpirationDate()}</td><td>String (10 chars)</td></tr>
 *   <tr><td>ACCT-REISSUE-DATE</td><td>{@code getReissueDate()}</td><td>String (10 chars)</td></tr>
 *   <tr><td>ACCT-CURR-CYC-CREDIT</td><td>{@code getCurrCycCredit()}</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-CURR-CYC-DEBIT</td><td>{@code getCurrCycDebit()}</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-GROUP-ID</td><td>{@code getGroupId()}</td><td>String (10 chars)</td></tr>
 * </table>
 *
 * @see Account
 * @see AccountRepository
 * @see CardDemoException
 * @see FileStatusException
 */
@Service
public class AccountRefreshService {

    /**
     * SLF4J logger replacing all COBOL DISPLAY statements in CBACT01C.cbl.
     * Covers: start/end execution banners, field-by-field account record
     * display (1100-DISPLAY-ACCT-RECORD), IO status error formatting
     * (9910-DISPLAY-IO-STATUS), and abend messaging (9999-ABEND-PROGRAM).
     */
    private static final Logger log = LoggerFactory.getLogger(AccountRefreshService.class);

    /**
     * Separator line matching COBOL 1100-DISPLAY-ACCT-RECORD output format.
     * Original COBOL: {@code DISPLAY '-------------------------------------------------'}
     */
    private static final String RECORD_SEPARATOR =
            "-------------------------------------------------";

    /**
     * APPL-RESULT error code for generic I/O errors.
     * Maps COBOL: {@code MOVE 12 TO APPL-RESULT} in paragraphs 0000, 1000, 9000
     * when ACCTFILE-STATUS is neither '00' (success) nor '10' (EOF).
     */
    private static final String IO_ERROR_STATUS = "12";

    /**
     * Spring Data JPA repository providing sequential batch read of all account
     * records. Translates the COBOL OPEN INPUT / READ NEXT / CLOSE pattern from
     * CBACT01C.cbl paragraphs 0000-ACCTFILE-OPEN, 1000-ACCTFILE-GET-NEXT, and
     * 9000-ACCTFILE-CLOSE into a single {@code findAll()} call.
     */
    private final AccountRepository accountRepository;

    /**
     * Constructs an {@code AccountRefreshService} with the required repository
     * dependency.
     *
     * <p>Uses constructor injection (Spring recommended pattern) to receive the
     * {@link AccountRepository} bean. This replaces the COBOL FILE SECTION
     * {@code SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE} binding with Spring's
     * dependency injection container.</p>
     *
     * @param accountRepository the JPA repository for account data access;
     *                          must not be {@code null}
     */
    @Autowired
    public AccountRefreshService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Reads and displays all account master records sequentially.
     *
     * <p>Translates the PROCEDURE DIVISION of CBACT01C.cbl — the main entry
     * point that orchestrates the complete batch account refresh workflow:</p>
     *
     * <ol>
     *   <li><strong>Start banner</strong> — {@code DISPLAY 'START OF EXECUTION
     *       OF PROGRAM CBACT01C'}</li>
     *   <li><strong>Open file</strong> — {@code PERFORM 0000-ACCTFILE-OPEN}
     *       (JPA connection pool handles this automatically)</li>
     *   <li><strong>Read loop</strong> — {@code PERFORM UNTIL END-OF-FILE = 'Y'}
     *       translated to {@code for (Account account : accounts)} iteration
     *       over the result of {@code accountRepository.findAll()}</li>
     *   <li><strong>Display each record</strong> — calls
     *       {@link #displayAccountRecord(Account)} for each record, matching
     *       the 1000-ACCTFILE-GET-NEXT → 1100-DISPLAY-ACCT-RECORD flow</li>
     *   <li><strong>Close file</strong> — {@code PERFORM 9000-ACCTFILE-CLOSE}
     *       (JPA connection pool handles this automatically)</li>
     *   <li><strong>End banner</strong> — {@code DISPLAY 'END OF EXECUTION OF
     *       PROGRAM CBACT01C'}</li>
     * </ol>
     *
     * <h4>Error Handling</h4>
     * <p>If any data access exception occurs during the {@code findAll()} call
     * or record iteration, the error path follows the COBOL pattern:</p>
     * <ol>
     *   <li>{@code DISPLAY 'ERROR READING ACCOUNT FILE'} → {@code log.error()}</li>
     *   <li>{@code 9910-DISPLAY-IO-STATUS} → Create {@link FileStatusException}
     *       with status code "12" and log via
     *       {@link FileStatusException#getFileStatusCode()}</li>
     *   <li>{@code 9999-ABEND-PROGRAM} → Throw {@link CardDemoException} with
     *       {@code ABCODE=999} equivalent message, wrapping the original cause</li>
     * </ol>
     *
     * @throws CardDemoException if a fatal error occurs during account data
     *         access, equivalent to COBOL {@code CALL 'CEE3ABD'} with
     *         {@code ABCODE=999}
     */
    public void refreshAccounts() {
        // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'
        log.info("START OF EXECUTION OF PROGRAM CBACT01C");

        try {
            // 0000-ACCTFILE-OPEN: JPA connection pool manages file open.
            // OPEN INPUT ACCTFILE-FILE → accountRepository.findAll()
            // ACCTFILE-STATUS '00' → APPL-RESULT = 0 (APPL-AOK) — success path
            List<Account> accounts = accountRepository.findAll();

            // PERFORM UNTIL END-OF-FILE = 'Y'
            //   IF END-OF-FILE = 'N'
            //     PERFORM 1000-ACCTFILE-GET-NEXT
            //     IF END-OF-FILE = 'N'
            //       DISPLAY ACCOUNT-RECORD
            //     END-IF
            //   END-IF
            // END-PERFORM
            //
            // In Java, the for-each loop handles:
            //   - 1000-ACCTFILE-GET-NEXT: each iteration retrieves the next record
            //   - ACCTFILE-STATUS '00' → APPL-AOK: normal iteration
            //   - ACCTFILE-STATUS '10' → APPL-EOF: loop terminates naturally
            for (Account account : accounts) {
                // 1100-DISPLAY-ACCT-RECORD — field-by-field display
                displayAccountRecord(account);
            }

            // 9000-ACCTFILE-CLOSE: JPA connection pool manages file close.
            // CLOSE ACCTFILE-FILE → automatic resource management

        } catch (CardDemoException e) {
            // Already a CardDemo application exception — propagate directly.
            // This handles cases where a nested service call throws a
            // CardDemoException or FileStatusException.
            throw e;
        } catch (Exception e) {
            // Translates the COBOL error path for non-00/non-10 file status:
            //   DISPLAY 'ERROR READING ACCOUNT FILE'
            //   MOVE ACCTFILE-STATUS TO IO-STATUS
            //   PERFORM 9910-DISPLAY-IO-STATUS
            //   PERFORM 9999-ABEND-PROGRAM

            // 1. Display error message (COBOL: DISPLAY 'ERROR READING ACCOUNT FILE')
            log.error("ERROR READING ACCOUNT FILE");

            // 2. 9910-DISPLAY-IO-STATUS: Format and display the IO status code.
            //    COBOL formats a 4-character status code from the 2-byte file status.
            //    In Java, we use FileStatusException to carry the status code.
            //    Status "12" = generic I/O error (MOVE 12 TO APPL-RESULT).
            FileStatusException statusException =
                    new FileStatusException(IO_ERROR_STATUS,
                            "Error reading account file");
            log.error("FILE STATUS IS: {}", statusException.getFileStatusCode());

            // 3. 9999-ABEND-PROGRAM: DISPLAY 'ABENDING PROGRAM', CALL 'CEE3ABD'
            //    MOVE 0 TO TIMING, MOVE 999 TO ABCODE
            log.error("ABENDING PROGRAM");
            throw new CardDemoException(
                    "ABENDING PROGRAM - Fatal error during account refresh "
                            + "processing (ABCODE=999)", e);
        }

        // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'
        log.info("END OF EXECUTION OF PROGRAM CBACT01C");
    }

    /**
     * Displays all 11 business fields of a single account record.
     *
     * <p>Translates COBOL paragraph 1100-DISPLAY-ACCT-RECORD from CBACT01C.cbl.
     * Each COBOL {@code DISPLAY} statement is mapped to a {@code log.info()} call
     * preserving the original label format and field alignment. The 5 monetary
     * fields (ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT,
     * ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT) are {@code BigDecimal} values
     * that are formatted by the SLF4J logger via their {@code toString()} method,
     * preserving exact decimal representation without floating-point artifacts.</p>
     *
     * <h4>COBOL Source (1100-DISPLAY-ACCT-RECORD)</h4>
     * <pre>
     * DISPLAY 'ACCT-ID                 :'   ACCT-ID
     * DISPLAY 'ACCT-ACTIVE-STATUS      :'   ACCT-ACTIVE-STATUS
     * DISPLAY 'ACCT-CURR-BAL           :'   ACCT-CURR-BAL
     * DISPLAY 'ACCT-CREDIT-LIMIT       :'   ACCT-CREDIT-LIMIT
     * DISPLAY 'ACCT-CASH-CREDIT-LIMIT  :'   ACCT-CASH-CREDIT-LIMIT
     * DISPLAY 'ACCT-OPEN-DATE          :'   ACCT-OPEN-DATE
     * DISPLAY 'ACCT-EXPIRAION-DATE     :'   ACCT-EXPIRAION-DATE
     * DISPLAY 'ACCT-REISSUE-DATE       :'   ACCT-REISSUE-DATE
     * DISPLAY 'ACCT-CURR-CYC-CREDIT    :'   ACCT-CURR-CYC-CREDIT
     * DISPLAY 'ACCT-CURR-CYC-DEBIT     :'   ACCT-CURR-CYC-DEBIT
     * DISPLAY 'ACCT-GROUP-ID           :'   ACCT-GROUP-ID
     * DISPLAY '-------------------------------------------------'
     * </pre>
     *
     * @param account the Account entity to display; must not be {@code null}
     */
    public void displayAccountRecord(Account account) {
        // DISPLAY 'ACCT-ID                 :'   ACCT-ID
        log.info("ACCT-ID                 : {}", account.getAcctId());

        // DISPLAY 'ACCT-ACTIVE-STATUS      :'   ACCT-ACTIVE-STATUS
        log.info("ACCT-ACTIVE-STATUS      : {}", account.getActiveStatus());

        // DISPLAY 'ACCT-CURR-BAL           :'   ACCT-CURR-BAL
        // BigDecimal → SLF4J parameterized logging (exact decimal representation)
        log.info("ACCT-CURR-BAL           : {}", account.getCurrBal());

        // DISPLAY 'ACCT-CREDIT-LIMIT       :'   ACCT-CREDIT-LIMIT
        log.info("ACCT-CREDIT-LIMIT       : {}", account.getCreditLimit());

        // DISPLAY 'ACCT-CASH-CREDIT-LIMIT  :'   ACCT-CASH-CREDIT-LIMIT
        log.info("ACCT-CASH-CREDIT-LIMIT  : {}", account.getCashCreditLimit());

        // DISPLAY 'ACCT-OPEN-DATE          :'   ACCT-OPEN-DATE
        log.info("ACCT-OPEN-DATE          : {}", account.getOpenDate());

        // DISPLAY 'ACCT-EXPIRAION-DATE     :'   ACCT-EXPIRAION-DATE
        // Note: COBOL source has typo "EXPIRAION"; Java entity uses corrected
        // "expirationDate" but this label preserves COBOL field name for traceability
        log.info("ACCT-EXPIRAION-DATE     : {}", account.getExpirationDate());

        // DISPLAY 'ACCT-REISSUE-DATE       :'   ACCT-REISSUE-DATE
        log.info("ACCT-REISSUE-DATE       : {}", account.getReissueDate());

        // DISPLAY 'ACCT-CURR-CYC-CREDIT    :'   ACCT-CURR-CYC-CREDIT
        log.info("ACCT-CURR-CYC-CREDIT    : {}", account.getCurrCycCredit());

        // DISPLAY 'ACCT-CURR-CYC-DEBIT     :'   ACCT-CURR-CYC-DEBIT
        log.info("ACCT-CURR-CYC-DEBIT     : {}", account.getCurrCycDebit());

        // DISPLAY 'ACCT-GROUP-ID           :'   ACCT-GROUP-ID
        log.info("ACCT-GROUP-ID           : {}", account.getGroupId());

        // DISPLAY '-------------------------------------------------'
        log.info(RECORD_SEPARATOR);
    }
}

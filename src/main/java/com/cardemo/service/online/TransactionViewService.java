/*
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
 */
package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for viewing individual transaction details.
 *
 * <p>Faithfully translates {@code COTRN01C.cbl} — the CICS online transaction
 * detail view program (transaction code CT01). This service reads a single
 * transaction record from the TRANSACT VSAM KSDS dataset (350-byte records,
 * {@code CVTRA05Y.cpy}) by its 16-character primary key ({@code TRAN-ID})
 * and returns the fully populated {@link Transaction} entity for display.</p>
 *
 * <h2>COBOL Program: COTRN01C.cbl — Paragraph-to-Method Traceability</h2>
 * <table>
 *   <caption>100% COBOL paragraph mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th></tr>
 *   <tr><td>MAIN-PARA</td><td>86</td>
 *       <td>{@link #viewTransaction(String)}</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>144</td>
 *       <td>{@link #processEnterKey(String)}</td></tr>
 *   <tr><td>RETURN-TO-PREV-SCREEN</td><td>197</td>
 *       <td>Navigation context in {@link #viewTransaction(String)}</td></tr>
 *   <tr><td>SEND-TRNVIEW-SCREEN</td><td>213</td>
 *       <td>(presentation — controller layer)</td></tr>
 *   <tr><td>RECEIVE-TRNVIEW-SCREEN</td><td>230</td>
 *       <td>(input — controller layer)</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>243</td>
 *       <td>{@link #populateHeaderInfo()}</td></tr>
 *   <tr><td>READ-TRANSACT-FILE</td><td>267</td>
 *       <td>{@link #readTransactFile(String)}</td></tr>
 *   <tr><td>CLEAR-CURRENT-SCREEN</td><td>301</td>
 *       <td>(presentation — controller layer)</td></tr>
 *   <tr><td>INITIALIZE-ALL-FIELDS</td><td>309</td>
 *       <td>(presentation — controller layer)</td></tr>
 * </table>
 *
 * <h2>Key Design Decisions</h2>
 * <ul>
 *   <li>Read-only {@code @Transactional} on data-access methods since this
 *       service performs no writes (view-only flow).</li>
 *   <li>All monetary values are {@link java.math.BigDecimal} — no
 *       floating-point conversion (per COBOL {@code PIC S9(09)V99}
 *       semantics).</li>
 *   <li>Timestamps remain as 26-character ISO-8601 strings
 *       ({@code YYYY-MM-DD-HH.MM.SS.mmmmmm}) preserving exact COBOL
 *       representation.</li>
 *   <li>Card numbers are masked in log output to prevent PII leakage.</li>
 * </ul>
 *
 * @see Transaction
 * @see TransactionRepository
 * @see <a href="legacy/app/cbl/COTRN01C.cbl">Original COBOL source</a>
 * @see <a href="legacy/app/cpy/CVTRA05Y.cpy">TRAN-RECORD copybook</a>
 */
@Service
public class TransactionViewService {

    private static final Logger log = LoggerFactory.getLogger(TransactionViewService.class);

    // ========================================================================
    // COBOL WORKING-STORAGE Constants (COTRN01C.cbl lines 36-37)
    // ========================================================================

    /**
     * Program name constant.
     * Maps {@code WS-PGMNAME PIC X(08) VALUE 'COTRN01C'}.
     */
    private static final String PROGRAM_NAME = "COTRN01C";

    /**
     * CICS transaction ID constant.
     * Maps {@code WS-TRANID PIC X(04) VALUE 'CT01'}.
     */
    private static final String TRAN_ID_CT01 = "CT01";

    // ========================================================================
    // Dependencies — injected via constructor
    // ========================================================================

    private final TransactionRepository transactionRepository;
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs the TransactionViewService with required dependencies.
     *
     * <p>Maps the COBOL program's COPY statement dependencies:
     * <ul>
     *   <li>{@code COPY CVTRA05Y} → {@link TransactionRepository} (TRANSACT
     *       VSAM KSDS access)</li>
     *   <li>{@code COPY COCOM01Y} → {@link CardDemoContext} (1024-byte
     *       CARDDEMO-COMMAREA session state)</li>
     * </ul>
     *
     * @param transactionRepository repository for TRANSACT dataset access
     * @param cardDemoContext        request-scoped session context bean
     */
    public TransactionViewService(TransactionRepository transactionRepository,
                                  CardDemoContext cardDemoContext) {
        this.transactionRepository = transactionRepository;
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // Public Methods — mapped from COBOL paragraphs
    // ========================================================================

    /**
     * View a single transaction by its identifier.
     *
     * <p><strong>Maps MAIN-PARA</strong> (COTRN01C.cbl line 86).</p>
     *
     * <p>This is the primary entry point for the transaction view flow.
     * It coordinates the full MAIN-PARA logic:</p>
     * <ol>
     *   <li>Logs entry with user/session context for observability</li>
     *   <li>Checks navigation source (maps PF3 return logic, lines
     *       116–120)</li>
     *   <li>Sets return navigation context (maps RETURN-TO-PREV-SCREEN,
     *       lines 202–204)</li>
     *   <li>Populates header information (maps POPULATE-HEADER-INFO,
     *       line 243)</li>
     *   <li>Validates the transaction ID is provided</li>
     *   <li>Delegates to {@link #processEnterKey(String)} for the actual
     *       record retrieval and field preparation</li>
     * </ol>
     *
     * <p>The COBOL EVALUATE EIBAID dispatch (ENTER, PF3, PF4, PF5, OTHER)
     * is handled at the controller layer. For unrecognized keys, the
     * controller uses {@link MessageConstants#INVALID_KEY_MESSAGE} (maps
     * line 130: {@code MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE}).</p>
     *
     * @param transactionId the 16-character transaction ID ({@code TRAN-ID
     *                      PIC X(16)}) to look up
     * @return the fully populated {@link Transaction} entity
     * @throws RecordNotFoundException if the transaction ID is blank or the
     *         record does not exist in the database
     */
    @Transactional(readOnly = true)
    public Transaction viewTransaction(String transactionId) {
        // Log entry with session context (observability — correlation IDs)
        log.debug("Transaction view entry [tranId={}, user={}, pgmContext={}]",
                transactionId,
                cardDemoContext.getUserId(),
                cardDemoContext.getPgmContext());

        // Check navigation source program for return routing
        // Maps MAIN-PARA PF3 logic (lines 116-120):
        //   IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
        //       MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
        //   ELSE MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM
        String fromProgram = cardDemoContext.getFromProgram();
        log.debug("Navigation context [fromProgram={}]", fromProgram);

        // Set return navigation context (maps RETURN-TO-PREV-SCREEN lines
        // 202-204):
        //   MOVE WS-TRANID  TO CDEMO-FROM-TRANID
        //   MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
        cardDemoContext.setFromTranId(TRAN_ID_CT01);
        cardDemoContext.setFromProgram(PROGRAM_NAME);

        // Populate header info (maps POPULATE-HEADER-INFO line 243)
        populateHeaderInfo();

        // Validate transaction ID is provided
        // Maps lines 94-96: IF EIBCALEN = 0 → navigate to sign-on
        // and EVALUATE OTHER (lines 128-131) for invalid input
        if (transactionId == null || transactionId.isBlank()) {
            log.warn("Transaction view with empty ID [user={}, msg='{}']",
                    cardDemoContext.getUserId(),
                    MessageConstants.INVALID_KEY_MESSAGE);
            throw new RecordNotFoundException(
                    "Transaction ID must be provided");
        }

        // Delegate to PROCESS-ENTER-KEY for record retrieval
        // Maps lines 107 and 114: PERFORM PROCESS-ENTER-KEY
        return processEnterKey(transactionId);
    }

    /**
     * Process the Enter key action for transaction viewing.
     *
     * <p><strong>Maps PROCESS-ENTER-KEY</strong> (COTRN01C.cbl line
     * 144).</p>
     *
     * <p>Validates the transaction ID is not blank, reads the transaction
     * record from the database, and logs all record fields for
     * observability. The COBOL equivalent moves all {@code TRAN-RECORD}
     * fields to corresponding BMS output fields (lines 177–190).</p>
     *
     * <p>COBOL flow:</p>
     * <pre>
     *   EVALUATE TRUE
     *       WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES
     *           set error, message 'Tran ID can NOT be empty...'
     *       WHEN OTHER
     *           CONTINUE
     *   END-EVALUATE
     *   IF NOT ERR-FLG-ON
     *       MOVE TRNIDINI TO TRAN-ID
     *       PERFORM READ-TRANSACT-FILE
     *       (move all TRAN fields to output)
     *   END-IF
     * </pre>
     *
     * @param transactionId the 16-character transaction ID
     * @return the fully populated {@link Transaction} entity
     * @throws RecordNotFoundException if the transaction ID is blank or
     *         the record does not exist
     */
    public Transaction processEnterKey(String transactionId) {
        // EVALUATE TRUE (lines 146-156)
        // WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES
        if (transactionId == null || transactionId.isBlank()) {
            // Lines 148-150: error flag + message for empty Tran ID
            log.warn("Enter key processed with empty transaction ID");
            throw new RecordNotFoundException(
                    "Tran ID can NOT be empty...");
        }

        // Lines 158-174: read the transaction record
        // MOVE TRNIDINI OF COTRN1AI TO TRAN-ID (line 172)
        // PERFORM READ-TRANSACT-FILE (line 173)
        Transaction transaction = readTransactFile(transactionId);

        // Lines 177-190: move all TRAN-RECORD fields to BMS output fields
        // In Java, the entity is returned directly to the controller.
        // Fields are accessed here for structured logging/observability.
        logTransactionDetails(transaction);

        return transaction;
    }

    /**
     * Read a transaction record by its primary key from the database.
     *
     * <p><strong>Maps READ-TRANSACT-FILE</strong> (COTRN01C.cbl line
     * 267).</p>
     *
     * <p>COBOL equivalent:</p>
     * <pre>
     *   EXEC CICS READ
     *        DATASET   (WS-TRANSACT-FILE)
     *        INTO      (TRAN-RECORD)
     *        LENGTH    (LENGTH OF TRAN-RECORD)
     *        RIDFLD    (TRAN-ID)
     *        KEYLENGTH (LENGTH OF TRAN-ID)
     *        UPDATE
     *        RESP      (WS-RESP-CD)
     *        RESP2     (WS-REAS-CD)
     *   END-EXEC
     * </pre>
     *
     * <p>Response code handling:</p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} — record found, returned
     *       successfully</li>
     *   <li>{@code DFHRESP(NOTFND)} — record not found, throws
     *       {@link RecordNotFoundException} with message
     *       "Transaction ID NOT found..." (lines 283–288)</li>
     *   <li>{@code WHEN OTHER} — unexpected error, logs RESP/REAS codes
     *       and throws {@link RecordNotFoundException} with message
     *       "Unable to lookup Transaction..." (lines 289–295)</li>
     * </ul>
     *
     * @param transactionId the 16-character transaction ID
     *                      ({@code TRAN-ID PIC X(16)})
     * @return the {@link Transaction} entity with all fields from
     *         {@code CVTRA05Y.cpy}: tranId, typeCode, categoryCode,
     *         source, description, amount (BigDecimal), merchantId,
     *         merchantName, merchantCity, merchantZip, cardNum,
     *         origTimestamp, procTimestamp
     * @throws RecordNotFoundException if the transaction is not found
     *         (maps VSAM file status code 23 / DFHRESP(NOTFND)) or if
     *         an unexpected database error occurs
     */
    @Transactional(readOnly = true)
    public Transaction readTransactFile(String transactionId) {
        log.debug("Reading TRANSACT file [tranId={}]", transactionId);

        try {
            // EXEC CICS READ DATASET(WS-TRANSACT-FILE) INTO(TRAN-RECORD)
            //      RIDFLD(TRAN-ID) → transactionRepository.findById()
            return transactionRepository.findById(transactionId)
                    .orElseThrow(() -> {
                        // WHEN DFHRESP(NOTFND) — lines 283-288:
                        //   MOVE 'Y' TO WS-ERR-FLG
                        //   MOVE 'Transaction ID NOT found...' TO WS-MESSAGE
                        log.warn("Transaction ID NOT found [tranId={}]",
                                transactionId);
                        return new RecordNotFoundException(
                                "Transaction ID NOT found...");
                    });
        } catch (RecordNotFoundException ex) {
            // Re-throw NOTFND exceptions unchanged
            throw ex;
        } catch (RuntimeException ex) {
            // WHEN OTHER — lines 289-295:
            //   DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
            //   MOVE 'Unable to lookup Transaction...' TO WS-MESSAGE
            log.error("Unexpected error reading TRANSACT file "
                    + "[tranId={}, error={}]",
                    transactionId, ex.getMessage(), ex);
            throw new RecordNotFoundException(
                    "Unable to lookup Transaction...");
        }
    }

    // ========================================================================
    // Private Methods — mapped from COBOL paragraphs
    // ========================================================================

    /**
     * Populates header information for the transaction view screen.
     *
     * <p><strong>Maps POPULATE-HEADER-INFO</strong> (COTRN01C.cbl line
     * 243).</p>
     *
     * <p>In the COBOL original, this paragraph sets screen header fields:
     * title lines ({@code CCDA-TITLE01}, {@code CCDA-TITLE02}), current
     * date/time ({@code FUNCTION CURRENT-DATE}), transaction name
     * ({@code WS-TRANID}), and program name ({@code WS-PGMNAME}).
     * In the Java service layer, header/metadata population is handled
     * by the controller/presentation layer. This method preserves
     * traceability and logs the equivalent information.</p>
     */
    private void populateHeaderInfo() {
        // Maps lines 243-262: set title, date, time, program name
        // Controller handles actual header population; service logs context
        log.trace("Header info populated [program={}, tranId={}, user={}]",
                PROGRAM_NAME, TRAN_ID_CT01, cardDemoContext.getUserId());
    }

    /**
     * Logs all transaction record fields for structured observability.
     *
     * <p>Maps the field MOVE statements from PROCESS-ENTER-KEY
     * (COTRN01C.cbl lines 177–190) where each {@code TRAN-RECORD} field
     * is transferred to the corresponding BMS output field for screen
     * display. In Java, the entity is returned directly; fields are
     * accessed here for structured logging with correlation IDs per AAP
     * observability requirements.</p>
     *
     * <p>Fields logged (from {@code CVTRA05Y.cpy TRAN-RECORD}):</p>
     * <ul>
     *   <li>{@code TRAN-ID} → {@link Transaction#getTranId()}</li>
     *   <li>{@code TRAN-TYPE-CD} → {@link Transaction#getTypeCode()}</li>
     *   <li>{@code TRAN-CAT-CD} → {@link Transaction#getCategoryCode()}</li>
     *   <li>{@code TRAN-SOURCE} → {@link Transaction#getSource()}</li>
     *   <li>{@code TRAN-DESC} → {@link Transaction#getDescription()}</li>
     *   <li>{@code TRAN-AMT} → {@link Transaction#getAmount()} (BigDecimal)</li>
     *   <li>{@code TRAN-MERCHANT-ID} → {@link Transaction#getMerchantId()}</li>
     *   <li>{@code TRAN-MERCHANT-NAME} → {@link Transaction#getMerchantName()}</li>
     *   <li>{@code TRAN-MERCHANT-CITY} → {@link Transaction#getMerchantCity()}</li>
     *   <li>{@code TRAN-MERCHANT-ZIP} → {@link Transaction#getMerchantZip()}</li>
     *   <li>{@code TRAN-CARD-NUM} → {@link Transaction#getCardNum()} (masked)</li>
     *   <li>{@code TRAN-ORIG-TS} → {@link Transaction#getOrigTimestamp()}</li>
     *   <li>{@code TRAN-PROC-TS} → {@link Transaction#getProcTimestamp()}</li>
     * </ul>
     *
     * @param transaction the transaction entity whose fields are logged
     */
    private void logTransactionDetails(Transaction transaction) {
        if (log.isInfoEnabled()) {
            log.info("Transaction retrieved [tranId={}, typeCode={}, "
                    + "catCode={}, source={}, desc='{}', amount={}, "
                    + "merchantId={}, merchantName='{}', merchantCity='{}', "
                    + "merchantZip='{}', cardNum={}, origTs='{}', "
                    + "procTs='{}']",
                    transaction.getTranId(),
                    transaction.getTypeCode(),
                    transaction.getCategoryCode(),
                    transaction.getSource(),
                    transaction.getDescription(),
                    transaction.getAmount(),
                    transaction.getMerchantId(),
                    transaction.getMerchantName(),
                    transaction.getMerchantCity(),
                    transaction.getMerchantZip(),
                    maskCardNumber(transaction.getCardNum()),
                    transaction.getOrigTimestamp(),
                    transaction.getProcTimestamp());
        }
    }

    /**
     * Masks a card number for safe logging, showing only the last four
     * digits to prevent PII leakage in log output.
     *
     * <p>Maps the security requirement for {@code TRAN-CARD-NUM PIC X(16)}:
     * card numbers must not appear in plaintext in log files or console
     * output.</p>
     *
     * @param cardNum the full card number (up to 16 characters)
     * @return a masked version showing only the last 4 digits, e.g.
     *         "************1234"; returns "****" for null or short input
     */
    private static String maskCardNumber(String cardNum) {
        if (cardNum == null) {
            return "null";
        }
        int length = cardNum.length();
        if (length <= 4) {
            return "****";
        }
        return "****" + cardNum.substring(length - 4);
    }
}

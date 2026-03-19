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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.DuplicateRecordException;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.common.util.DateConversionUtil;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.TransactionRepository;

/**
 * Service implementing the transaction add workflow, faithfully translated from
 * the COBOL online CICS program {@code COTRN02C.cbl}.
 *
 * <p>This service replicates every paragraph of the original COBOL program with
 * 100% business logic parity. The COBOL pseudo-conversational CICS model is
 * mapped to a stateless Spring service layer suitable for headless REST
 * consumption.</p>
 *
 * <h2>COBOL Paragraph-to-Java Method Traceability</h2>
 * <table>
 *   <caption>COTRN02C.cbl paragraph mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th></tr>
 *   <tr><td>MAIN-PARA</td><td>107</td>
 *       <td>{@link #addTransaction(TransactionAddRequest)}</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>164</td>
 *       <td>{@link #processEnterKey(TransactionAddRequest)}</td></tr>
 *   <tr><td>VALIDATE-INPUT-KEY-FIELDS</td><td>193</td>
 *       <td>{@link #validateInputKeyFields(String, String)}</td></tr>
 *   <tr><td>VALIDATE-INPUT-DATA-FIELDS</td><td>235</td>
 *       <td>{@link #validateInputDataFields(TransactionAddRequest)}</td></tr>
 *   <tr><td>ADD-TRANSACTION</td><td>442</td>
 *       <td>{@link #addTransactionRecord(Transaction)}</td></tr>
 *   <tr><td>COPY-LAST-TRAN-DATA</td><td>471</td>
 *       <td>{@link #copyLastTranData()}</td></tr>
 *   <tr><td>READ-CXACAIX-FILE</td><td>576</td>
 *       <td>{@link #readCxacaixFile(String)}</td></tr>
 *   <tr><td>READ-CCXREF-FILE</td><td>609</td>
 *       <td>{@link #readCcxrefFile(String)}</td></tr>
 *   <tr><td>WRITE-TRANSACT-FILE</td><td>711</td>
 *       <td>{@link #writeTransactFile(Transaction)}</td></tr>
 * </table>
 *
 * <h2>Key Implementation Rules (per AAP Section 0.7.4)</h2>
 * <ul>
 *   <li>Transaction ID generation via browse-last technique:
 *       {@code findFirstByOrderByTranIdDesc()} &rarr; increment</li>
 *   <li>All monetary amounts use {@link BigDecimal} with
 *       {@link RoundingMode#HALF_UP}</li>
 *   <li>Timestamps in ISO-8601 format: {@code YYYY-MM-DD-HH.MM.SS.mmmmmm}
 *       (26 characters, microsecond precision)</li>
 *   <li>Date validation via {@link DateConversionUtil#validateDate} replicating
 *       CEEDAYS behaviour</li>
 * </ul>
 *
 * @see com.cardemo.repository.TransactionRepository
 * @see com.cardemo.repository.CardXrefRepository
 * @see com.cardemo.common.util.DateConversionUtil
 */
@Service
public class TransactionAddService {

    private static final Logger logger =
            LoggerFactory.getLogger(TransactionAddService.class);

    // ========================================================================
    // Constants — matching COBOL working storage definitions
    // ========================================================================

    /**
     * Maximum transaction amount matching COBOL display PIC +99999999.99.
     * The underlying TRAN-AMT is {@code PIC S9(09)V99 COMP-3} (precision 11,
     * scale 2). The display format constrains the user input to 8 integer
     * digits plus 2 decimal digits.
     */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    /**
     * Minimum transaction amount (negative of {@link #MAX_AMOUNT}).
     */
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("-99999999.99");

    /**
     * Date format mask for CSUTLDTC validation, matching
     * {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} in COTRN02C.cbl.
     */
    private static final String DATE_FORMAT = "YYYY-MM-DD";

    /**
     * CEEDAYS feedback message code for "unsupported range" (FC-UNSUPP-RANGE).
     * Per COBOL logic in COTRN02C.cbl, this code is intentionally allowed
     * through: the date is technically valid but outside the CEEDAYS preferred
     * range. Maps to CSUTLDTC.cbl line 66.
     */
    private static final int MSG_UNSUPP_RANGE = 2513;

    /**
     * Length of the TRAN-ID field in the TRANSACT VSAM dataset.
     * {@code PIC X(16)} — 16-character zero-padded numeric string.
     */
    private static final int TRAN_ID_LENGTH = 16;

    /**
     * Formatter for human-readable audit log timestamps.
     * Uses {@link DateTimeFormatter#ofPattern(String)} with microsecond
     * precision for structured logging correlation.
     */
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    // ========================================================================
    // Injected dependencies
    // ========================================================================

    private final TransactionRepository transactionRepository;
    private final CardXrefRepository cardXrefRepository;
    private final CardDemoContext cardDemoContext;

    /**
     * JPA entity manager used for PostgreSQL advisory lock acquisition during
     * transaction ID generation. Advisory locks serialise concurrent
     * {@link #addTransactionRecord} calls to prevent duplicate-key race
     * conditions on the browse-last ID generation pattern.
     *
     * <p>Injected via {@code @PersistenceContext} rather than constructor
     * injection because the {@code EntityManager} is a container-managed
     * proxy that is inherently request-scoped.</p>
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Advisory lock key used by {@link #addTransactionRecord} to serialise
     * transaction ID generation. The value {@code 42} is an arbitrary but
     * stable identifier reserved exclusively for the transaction-add path.
     *
     * <p>PostgreSQL's {@code pg_advisory_xact_lock(bigint)} acquires a
     * transaction-scoped exclusive advisory lock. The lock is automatically
     * released when the enclosing {@code @Transactional} method commits or
     * rolls back — no explicit unlock is required.</p>
     */
    private static final long TRAN_ID_ADVISORY_LOCK_KEY = 42L;

    /**
     * Constructs the service with required dependencies via Spring constructor
     * injection.
     *
     * @param transactionRepository JPA repository for TRANSACT dataset access
     * @param cardXrefRepository    JPA repository for CARDXREF / CXACAIX
     *                              dataset access
     * @param cardDemoContext        request-scoped COMMAREA equivalent
     */
    public TransactionAddService(
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            CardDemoContext cardDemoContext) {
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // Inner DTO — TransactionAddRequest
    // ========================================================================

    /**
     * Request DTO carrying all screen input fields from the COTRN02 BMS map.
     *
     * <p>Field sizes and names map directly to the BMS copybook
     * {@code app/cpy-bms/COTRN02.CPY} (COTRN2AI input structure).</p>
     *
     * @param accountId    ACTIDINI PIC X(11) — account identifier
     * @param cardNum      CARDNINI PIC X(16) — card number
     * @param typeCode     TTYPCDI PIC X(2)   — transaction type code
     * @param categoryCode TCATCDI PIC X(4)   — transaction category code
     * @param source       TRNSRCI PIC X(10)  — transaction source
     * @param description  TDESCI PIC X(60)   — transaction description
     * @param amount       TRNAMTI PIC X(12)  — amount in display format
     * @param origDate     TORIGDTI PIC X(10) — origination date YYYY-MM-DD
     * @param procDate     TPROCDTI PIC X(10) — processing date YYYY-MM-DD
     * @param merchantId   MIDI PIC X(9)      — merchant identifier
     * @param merchantName MNAMEI PIC X(30)   — merchant name
     * @param merchantCity MCITYI PIC X(25)   — merchant city
     * @param merchantZip  MZIPI PIC X(10)    — merchant zip code
     * @param confirm      CONFIRMI PIC X(1)  — confirmation flag Y/N
     */
    public record TransactionAddRequest(
            String accountId,
            String cardNum,
            String typeCode,
            String categoryCode,
            String source,
            String description,
            String amount,
            String origDate,
            String procDate,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String confirm
    ) { }

    // ========================================================================
    // Public API — COBOL paragraph translations
    // ========================================================================

    /**
     * Main entry point for the transaction add workflow.
     *
     * <p>Maps to COBOL paragraph {@code MAIN-PARA} (COTRN02C.cbl line 107).
     * In the original COBOL, this paragraph evaluates the EIBAID (terminal
     * key pressed) in a pseudo-conversational loop. For the headless Java
     * service layer, this method orchestrates the complete add flow:
     * validation, confirmation check, ID generation, and database write.</p>
     *
     * @param request the transaction add request containing all screen fields
     * @return the persisted {@link Transaction} entity with generated ID and
     *         timestamps
     * @throws ValidationException       if key or data field validation fails
     * @throws RecordNotFoundException   if cross-reference lookup fails
     * @throws DuplicateRecordException  if generated transaction ID already
     *                                    exists
     */
    @Transactional
    public Transaction addTransaction(TransactionAddRequest request) {
        // Log full user context from COMMAREA (maps to MAIN-PARA context
        // access: CDEMO-USER-ID, CDEMO-USER-TYPE, CDEMO-PGM-REENTER)
        logger.info("Transaction add initiated by user: {} (type: {})",
                cardDemoContext.getUserId(),
                cardDemoContext.getUserType());
        logger.debug("Session context — pgmContext: {}, contextAcctId: {}, "
                        + "contextCardNum: {}",
                cardDemoContext.getPgmContext(),
                cardDemoContext.getAcctId(),
                isBlank(cardDemoContext.getCardNum()) ? "N/A"
                        : maskCardNumber(cardDemoContext.getCardNum()));

        if (request == null) {
            // Maps to WHEN OTHER in MAIN-PARA EIBAID evaluation:
            // MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
            throw new ValidationException(
                    MessageConstants.INVALID_KEY_MESSAGE.trim());
        }

        // Apply context defaults for account ID and card number when the
        // request fields are blank. In COBOL, the COMMAREA carries
        // CDEMO-ACCT-ID and CDEMO-CARD-NUM pre-populated from the
        // previous screen navigation (e.g. Card Detail → Transaction Add).
        String effectiveAcctId = isBlank(request.accountId())
                ? cardDemoContext.getAcctId() : request.accountId();
        String effectiveCardNum = isBlank(request.cardNum())
                ? cardDemoContext.getCardNum() : request.cardNum();

        TransactionAddRequest effectiveRequest = new TransactionAddRequest(
                effectiveAcctId, effectiveCardNum,
                request.typeCode(), request.categoryCode(),
                request.source(), request.description(), request.amount(),
                request.origDate(), request.procDate(),
                request.merchantId(), request.merchantName(),
                request.merchantCity(), request.merchantZip(),
                request.confirm()
        );

        return processEnterKey(effectiveRequest);
    }

    /**
     * Processes the ENTER key action — validates, confirms, and adds the
     * transaction.
     *
     * <p>Maps to COBOL paragraph {@code PROCESS-ENTER-KEY} (COTRN02C.cbl
     * line 164). This paragraph performs validation in two phases (key fields,
     * then data fields), checks the confirmation flag, and delegates to
     * {@link #addTransactionRecord(Transaction)} when confirmed.</p>
     *
     * <p>The COBOL confirmation semantics (EVALUATE TRAN-CONFIRMI) are
     * faithfully reproduced:</p>
     * <ul>
     *   <li>{@code 'Y'} or {@code 'y'} — proceed with ADD-TRANSACTION</li>
     *   <li>{@code 'N'}, {@code 'n'}, SPACES, LOW-VALUES — prompt to
     *       confirm</li>
     *   <li>Any other value — invalid confirmation error</li>
     * </ul>
     *
     * @param request the transaction add request
     * @return the persisted {@link Transaction} entity
     * @throws ValidationException       if validation or confirmation fails
     * @throws RecordNotFoundException   if cross-reference lookup fails
     * @throws DuplicateRecordException  if transaction ID already exists
     */
    public Transaction processEnterKey(TransactionAddRequest request) {
        logger.debug("Processing ENTER key for transaction add");

        // Step 1: Validate key fields (account ID / card number)
        // Maps to: PERFORM VALIDATE-INPUT-KEY-FIELDS
        List<String> keyErrors = validateInputKeyFields(
                request.accountId(), request.cardNum());
        if (!keyErrors.isEmpty()) {
            logger.warn("Key field validation failed: {}", keyErrors);
            throw new ValidationException(String.join("; ", keyErrors));
        }

        // Step 2: Validate data fields
        // Maps to: PERFORM VALIDATE-INPUT-DATA-FIELDS
        List<String> dataErrors = validateInputDataFields(request);
        if (!dataErrors.isEmpty()) {
            logger.warn("Data field validation failed: {}", dataErrors);
            throw new ValidationException(String.join("; ", dataErrors));
        }

        // Step 3: Check confirmation (maps to EVALUATE TRAN-CONFIRMI)
        String confirm = request.confirm();
        if ("Y".equalsIgnoreCase(confirm)) {
            // Confirmed — resolve cross-reference and build transaction
            String resolvedCardNum;
            String resolvedAcctId;

            if (!isBlank(request.accountId())) {
                // Account ID provided — resolve card number via CXACAIX
                CardXref xref = readCxacaixFile(request.accountId().trim());
                resolvedCardNum = xref.getXrefCardNum();
                resolvedAcctId = request.accountId().trim();
            } else {
                // Card number provided — resolve account ID via CCXREF
                CardXref xref = readCcxrefFile(request.cardNum().trim());
                resolvedCardNum = request.cardNum().trim();
                resolvedAcctId = xref.getAccountId();
            }

            logger.debug("Cross-reference resolved — acctId: {}, cardNum: {}",
                    resolvedAcctId, maskCardNumber(resolvedCardNum));

            // Build transaction entity from request fields
            // Maps to COBOL field MOVEs in ADD-TRANSACTION (lines 442-465)
            BigDecimal amount = new BigDecimal(request.amount().trim())
                    .setScale(2, RoundingMode.HALF_UP);
            Integer categoryCode = Integer.valueOf(
                    Integer.parseInt(request.categoryCode().trim()));

            Transaction transaction = new Transaction(
                    null,                              // tranId — generated later
                    request.typeCode().trim(),         // TRAN-TYPE-CD
                    categoryCode,                      // TRAN-CAT-CD
                    request.source().trim(),           // TRAN-SOURCE
                    request.description().trim(),      // TRAN-DESC
                    amount,                            // TRAN-AMT
                    request.merchantId().trim(),       // TRAN-MERCHANT-ID
                    request.merchantName().trim(),     // TRAN-MERCHANT-NAME
                    request.merchantCity().trim(),     // TRAN-MERCHANT-CITY
                    request.merchantZip().trim(),      // TRAN-MERCHANT-ZIP
                    resolvedCardNum,                   // TRAN-CARD-NUM
                    null,                              // origTimestamp — set later
                    null                               // procTimestamp — set later
            );

            return addTransactionRecord(transaction);

        } else if (confirm == null || confirm.isBlank()
                || "N".equalsIgnoreCase(confirm)) {
            // WHEN 'N' / WHEN 'n' / WHEN SPACES / WHEN LOW-VALUES
            throw new ValidationException("confirm",
                    "Please confirm or cancel the transaction");
        } else {
            // WHEN OTHER
            throw new ValidationException("confirm",
                    "Invalid value for confirmation");
        }
    }

    /**
     * Validates the key input fields: account ID and/or card number.
     *
     * <p>Maps to COBOL paragraph {@code VALIDATE-INPUT-KEY-FIELDS}
     * (COTRN02C.cbl line 193). The COBOL EVALUATE TRUE structure provides
     * three branches:</p>
     * <ol>
     *   <li>Account ID provided — validate numeric, perform CXACAIX read</li>
     *   <li>Card number provided — validate numeric, perform CCXREF read</li>
     *   <li>Neither provided — error</li>
     * </ol>
     *
     * @param accountId the account identifier (may be null or blank)
     * @param cardNum   the card number (may be null or blank)
     * @return list of validation error messages; empty if validation passes
     */
    public List<String> validateInputKeyFields(String accountId,
                                                String cardNum) {
        logger.debug("Validating input key fields — accountId: {}, "
                        + "cardNum present: {}",
                accountId, !isBlank(cardNum));
        List<String> errors = new ArrayList<>();

        boolean hasAccountId = !isBlank(accountId);
        boolean hasCardNum = !isBlank(cardNum);

        if (hasAccountId) {
            // WHEN ACTIDINI NOT = SPACES AND NOT = LOW-VALUES
            if (!isNumeric(accountId.trim())) {
                errors.add("Account ID must be numeric");
            } else {
                // PERFORM READ-CXACAIX-FILE
                try {
                    readCxacaixFile(accountId.trim());
                } catch (RecordNotFoundException e) {
                    errors.add(e.getMessage());
                }
            }
        } else if (hasCardNum) {
            // WHEN CARDNINI NOT = SPACES AND NOT = LOW-VALUES
            if (!isNumeric(cardNum.trim())) {
                errors.add("Card Number must be numeric");
            } else {
                // PERFORM READ-CCXREF-FILE
                try {
                    readCcxrefFile(cardNum.trim());
                } catch (RecordNotFoundException e) {
                    errors.add(e.getMessage());
                }
            }
        } else {
            // WHEN OTHER — neither account ID nor card number provided
            errors.add("Account ID or Card Number is required");
        }

        return errors;
    }

    /**
     * Validates all data input fields for the transaction.
     *
     * <p>Maps to COBOL paragraph {@code VALIDATE-INPUT-DATA-FIELDS}
     * (COTRN02C.cbl line 235). Validation is performed in two phases:</p>
     *
     * <ol>
     *   <li><strong>Required field checks</strong> — all 11 data fields must
     *       be non-blank (maps to COBOL SPACES/LOW-VALUES checks)</li>
     *   <li><strong>Format/type checks</strong> — numeric validation for type
     *       code, category code, and merchant ID; BigDecimal amount range
     *       validation; YYYY-MM-DD date validation for both origination and
     *       processing dates via {@link DateConversionUtil}</li>
     * </ol>
     *
     * <p>The COBOL program exits on the first error (GO TO EXIT). In the
     * headless Java service layer, all errors are collected into the returned
     * list for improved API usability, while preserving the same validation
     * rules.</p>
     *
     * @param request the transaction add request containing screen fields
     * @return list of validation error messages; empty if validation passes
     */
    public List<String> validateInputDataFields(TransactionAddRequest request) {
        logger.debug("Validating input data fields");
        List<String> errors = new ArrayList<>();

        // ------------------------------------------------------------------
        // Phase 1: Required field checks (SPACES / LOW-VALUES equivalents)
        // Maps to COTRN02C.cbl lines 236-330 (11 IF-blocks with GO TO EXIT)
        // ------------------------------------------------------------------
        if (isBlank(request.typeCode())) {
            errors.add("Transaction Type Code is required");
        }
        if (isBlank(request.categoryCode())) {
            errors.add("Transaction Category Code is required");
        }
        if (isBlank(request.source())) {
            errors.add("Transaction Source is required");
        }
        if (isBlank(request.description())) {
            errors.add("Transaction Description is required");
        }
        if (isBlank(request.amount())) {
            errors.add("Transaction Amount is required");
        }
        if (isBlank(request.origDate())) {
            errors.add("Transaction Orig Date is required");
        }
        if (isBlank(request.procDate())) {
            errors.add("Transaction Proc Date is required");
        }
        if (isBlank(request.merchantId())) {
            errors.add("Merchant ID is required");
        }
        if (isBlank(request.merchantName())) {
            errors.add("Merchant Name is required");
        }
        if (isBlank(request.merchantCity())) {
            errors.add("Merchant City is required");
        }
        if (isBlank(request.merchantZip())) {
            errors.add("Merchant Zip is required");
        }

        // If any required fields are missing, return immediately
        // (matches COBOL early-exit behaviour — subsequent checks assume
        //  non-blank values)
        if (!errors.isEmpty()) {
            return errors;
        }

        // ------------------------------------------------------------------
        // Phase 2: Format and type validation
        // Maps to COTRN02C.cbl lines 331-440
        // ------------------------------------------------------------------
        String typeCode = request.typeCode().trim();
        String categoryCode = request.categoryCode().trim();
        String amountStr = request.amount().trim();
        String origDate = request.origDate().trim();
        String procDate = request.procDate().trim();
        String merchantId = request.merchantId().trim();

        // Type Code numeric check (COBOL: IF TTYPCDI IS NOT NUMERIC)
        if (!isNumeric(typeCode)) {
            errors.add("Transaction Type Code must be numeric");
        }

        // Category Code numeric check (COBOL: IF TCATCDI IS NOT NUMERIC)
        if (!isNumeric(categoryCode)) {
            errors.add("Transaction Category Code must be numeric");
        }

        // Amount validation — NUMVAL-C equivalent
        // COBOL: COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI)
        //        ON SIZE ERROR → error
        try {
            BigDecimal amount = new BigDecimal(amountStr)
                    .setScale(2, RoundingMode.HALF_UP);
            if (amount.compareTo(MIN_AMOUNT) < 0
                    || amount.compareTo(MAX_AMOUNT) > 0) {
                errors.add("Transaction Amount out of range "
                        + "(-99999999.99 to 99999999.99)");
            }
        } catch (NumberFormatException e) {
            errors.add("Transaction Amount format is invalid");
        }

        // Origination date validation
        // COBOL: CALL 'CSUTLDTC' USING WS-DATE-FORMAT WS-EDIT-DATE-DATA ...
        // Allow FC-UNSUPP-RANGE (2513) through per COBOL logic
        DateConversionUtil.DateValidationResult origResult =
                DateConversionUtil.validateDate(origDate, DATE_FORMAT);
        if (!origResult.valid()
                && origResult.messageCode() != MSG_UNSUPP_RANGE) {
            errors.add("Transaction Orig Date is invalid "
                    + "(format: YYYY-MM-DD)");
        }

        // Processing date validation — same rules as origination date
        DateConversionUtil.DateValidationResult procResult =
                DateConversionUtil.validateDate(procDate, DATE_FORMAT);
        if (!procResult.valid()
                && procResult.messageCode() != MSG_UNSUPP_RANGE) {
            errors.add("Transaction Proc Date is invalid "
                    + "(format: YYYY-MM-DD)");
        }

        // Merchant ID numeric check (COBOL: IF MIDI IS NOT NUMERIC)
        if (!isNumeric(merchantId)) {
            errors.add("Merchant ID must be numeric");
        }

        return errors;
    }

    /**
     * Generates a transaction ID and persists the transaction record.
     *
     * <p>Maps to COBOL paragraph {@code ADD-TRANSACTION} (COTRN02C.cbl line
     * 442). This paragraph performs three key operations:</p>
     * <ol>
     *   <li>{@code PERFORM COPY-LAST-TRAN-DATA} — generate next transaction
     *       ID via browse-last technique</li>
     *   <li>Populate timestamp fields ({@code TRAN-ORIG-TS},
     *       {@code TRAN-PROC-TS})</li>
     *   <li>{@code PERFORM WRITE-TRANSACT-FILE} — persist to database</li>
     * </ol>
     *
     * @param transaction the transaction entity with all data fields populated;
     *                    tranId and timestamps will be set by this method
     * @return the persisted transaction with generated ID and timestamps
     * @throws DuplicateRecordException if the generated transaction ID already
     *                                   exists
     */
    @Transactional
    public Transaction addTransactionRecord(Transaction transaction) {
        logger.debug("Adding transaction record — generating ID and writing");

        // Step 0: Acquire transaction-scoped advisory lock to serialise
        // concurrent ID generation. In the original COBOL/CICS system,
        // pseudo-conversational task processing was single-threaded, so the
        // browse-last technique (STARTBR HIGH-VALUES → READPREV) never
        // encountered concurrent callers. In Java/Spring Boot, concurrent
        // HTTP requests can execute simultaneously, causing the SELECT
        // MAX(tran_id) → increment → INSERT pattern to race. The advisory
        // lock serialises the critical section (read-max → increment →
        // write) without table-level locking overhead. The lock is
        // automatically released on transaction commit/rollback.
        entityManager.createNativeQuery(
                "SELECT pg_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", TRAN_ID_ADVISORY_LOCK_KEY)
                .getSingleResult();
        logger.debug("Acquired advisory lock {} for transaction ID generation",
                TRAN_ID_ADVISORY_LOCK_KEY);

        // Step 1: Generate transaction ID via browse-last technique
        // Maps to: PERFORM COPY-LAST-TRAN-DATA
        String newTranId = copyLastTranData();
        transaction.setTranId(newTranId);

        // Audit log with human-readable timestamp for correlation
        String auditTime = LocalDateTime.now().format(LOG_TIMESTAMP_FORMAT);
        logger.info("Generated new transaction ID: {} at {}", newTranId,
                auditTime);

        // Step 2: Set timestamps
        // Maps to: COBOL timestamp generation for TRAN-ORIG-TS / TRAN-PROC-TS
        // Format: YYYY-MM-DD-HH.MM.SS.mmmmmm (26 chars, microsecond precision)
        String currentTimestamp = DateConversionUtil.getCurrentTimestamp();
        transaction.setOrigTimestamp(currentTimestamp);
        transaction.setProcTimestamp(currentTimestamp);

        // Step 3: Write transaction record to database
        // Maps to: PERFORM WRITE-TRANSACT-FILE
        return writeTransactFile(transaction);
    }

    /**
     * Reads the CXACAIX alternate index file to find a card cross-reference
     * by account ID.
     *
     * <p>Maps to COBOL paragraph {@code READ-CXACAIX-FILE} (COTRN02C.cbl
     * line 576):</p>
     * <pre>
     * EXEC CICS READ
     *      DATASET(WS-CXACAIX-FILE)
     *      INTO(CARD-XREF-RECORD)
     *      RIDFLD(XREF-ACCT-ID)
     *      RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p>DFHRESP handling:</p>
     * <ul>
     *   <li>NORMAL — returns the found {@link CardXref}</li>
     *   <li>NOTFND — throws {@link RecordNotFoundException}</li>
     *   <li>OTHER — logs error and throws {@link RecordNotFoundException}</li>
     * </ul>
     *
     * @param accountId the 11-character account identifier
     * @return the matching {@link CardXref} entity
     * @throws RecordNotFoundException if no cross-reference record exists
     *                                  for the given account ID
     */
    public CardXref readCxacaixFile(String accountId) {
        logger.debug("Reading CXACAIX file for account ID: {}", accountId);

        List<CardXref> xrefs = cardXrefRepository.findByAccountId(accountId);

        if (xrefs.isEmpty()) {
            // Maps to DFHRESP(NOTFND) — COTRN02C.cbl line 591
            logger.warn("Account ID NOT found in cross-reference: {}",
                    accountId);
            throw new RecordNotFoundException(
                    "Account ID NOT found in cross-reference file");
        }

        // Return the first cross-reference entry (COBOL returns the first
        // matching AIX record)
        CardXref xref = xrefs.get(0);
        logger.debug("Found card cross-reference — cardNum: {}",
                maskCardNumber(xref.getXrefCardNum()));
        return xref;
    }

    /**
     * Reads the CCXREF file to find a card cross-reference by card number.
     *
     * <p>Maps to COBOL paragraph {@code READ-CCXREF-FILE} (COTRN02C.cbl
     * line 609):</p>
     * <pre>
     * EXEC CICS READ
     *      DATASET(WS-CCXREF-FILE)
     *      INTO(CARD-XREF-RECORD)
     *      RIDFLD(XREF-CARD-NUM)
     *      RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p>DFHRESP handling:</p>
     * <ul>
     *   <li>NORMAL — returns the found {@link CardXref}</li>
     *   <li>NOTFND — throws {@link RecordNotFoundException}</li>
     *   <li>OTHER — logs error and throws {@link RecordNotFoundException}</li>
     * </ul>
     *
     * @param cardNum the 16-character card number
     * @return the matching {@link CardXref} entity
     * @throws RecordNotFoundException if no cross-reference record exists
     *                                  for the given card number
     */
    public CardXref readCcxrefFile(String cardNum) {
        logger.debug("Reading CCXREF file for card number: {}",
                maskCardNumber(cardNum));

        Optional<CardXref> xref =
                cardXrefRepository.findByXrefCardNum(cardNum);

        if (xref.isEmpty()) {
            // Maps to DFHRESP(NOTFND) — COTRN02C.cbl line 624
            logger.warn("Card Number NOT found in cross-reference file");
            throw new RecordNotFoundException(
                    "Card Number NOT found in cross-reference file");
        }

        logger.debug("Found cross-reference — accountId: {}",
                xref.get().getAccountId());
        return xref.get();
    }

    /**
     * Writes a transaction record to the TRANSACT dataset (database).
     *
     * <p>Maps to COBOL paragraph {@code WRITE-TRANSACT-FILE} (COTRN02C.cbl
     * line 711):</p>
     * <pre>
     * EXEC CICS WRITE
     *      DATASET(WS-TRANSACT-FILE)
     *      FROM(TRAN-RECORD)
     *      RIDFLD(TRAN-ID)
     *      RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
     * END-EXEC
     * </pre>
     *
     * <p>DFHRESP handling:</p>
     * <ul>
     *   <li>NORMAL — returns saved entity with success log</li>
     *   <li>DUPKEY / DUPREC — throws {@link DuplicateRecordException}</li>
     *   <li>OTHER — re-throws as {@link RuntimeException}</li>
     * </ul>
     *
     * @param transaction the fully populated transaction entity to persist
     * @return the persisted transaction entity
     * @throws DuplicateRecordException if the transaction ID already exists
     */
    public Transaction writeTransactFile(Transaction transaction) {
        logger.debug("Writing transaction record with ID: {}",
                transaction.getTranId());

        try {
            Transaction saved = transactionRepository.save(transaction);
            // Maps to DFHRESP(NORMAL) — success message
            logger.info("Transaction added successfully. "
                    + "Your Tran ID is: {}", saved.getTranId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Maps to DFHRESP(DUPKEY) / DFHRESP(DUPREC)
            // COTRN02C.cbl lines 735-741
            logger.error("Duplicate key writing transaction {}: {}",
                    transaction.getTranId(), e.getMessage());
            throw new DuplicateRecordException(
                    "Tran ID already exist. "
                            + "Please enter a different Tran ID.");
        }
    }

    // ========================================================================
    // Private helper methods
    // ========================================================================

    /**
     * Generates the next transaction ID using the browse-last technique.
     *
     * <p>Maps to COBOL paragraph {@code COPY-LAST-TRAN-DATA} (COTRN02C.cbl
     * line 471). The COBOL logic is:</p>
     * <ol>
     *   <li>{@code MOVE HIGH-VALUES TO WS-TRAN-ID}</li>
     *   <li>{@code PERFORM STARTBR-TRANSACT-FILE} — positions cursor to end
     *       of dataset</li>
     *   <li>{@code PERFORM READPREV-TRANSACT-FILE} — reads the last record;
     *       if ENDFILE (empty dataset), {@code MOVE ZEROS TO WS-TRAN-ID}</li>
     *   <li>{@code PERFORM ENDBR-TRANSACT-FILE} — closes browse</li>
     *   <li>{@code ADD 1 TO WS-TRAN-ID-N} — increment the numeric
     *       redefines</li>
     * </ol>
     *
     * <p>Per AAP Section 0.7.4: "Transaction ID generation must replicate
     * the COBOL browse-last technique."</p>
     *
     * @return the next transaction ID as a 16-character zero-padded string
     */
    private String copyLastTranData() {
        logger.debug("Executing browse-last transaction ID generation");

        // STARTBR at HIGH-VALUES → READPREV → get last TRAN-ID
        Optional<Transaction> lastTransaction =
                transactionRepository.findFirstByOrderByTranIdDesc();

        if (lastTransaction.isPresent()) {
            String lastId = lastTransaction.get().getTranId();
            logger.debug("Last transaction ID found: {}", lastId);

            // ADD 1 TO WS-TRAN-ID-N (numeric REDEFINES of TRAN-ID)
            try {
                long lastIdNum = Long.parseLong(lastId.trim());
                long nextIdNum = lastIdNum + 1;
                return String.format("%0" + TRAN_ID_LENGTH + "d", nextIdNum);
            } catch (NumberFormatException e) {
                // Non-numeric last ID — start from 1 as fallback
                logger.warn("Non-numeric last transaction ID: {}, "
                        + "starting from 1", lastId);
                return String.format("%0" + TRAN_ID_LENGTH + "d", 1L);
            }
        } else {
            // ENDFILE condition — empty dataset
            // COBOL: MOVE ZEROS TO WS-TRAN-ID → ADD 1 → result is 1
            logger.info("No existing transactions found, "
                    + "starting from ID 1");
            return String.format("%0" + TRAN_ID_LENGTH + "d", 1L);
        }
    }

    /**
     * Checks if a string is null, empty, or blank.
     * Maps to COBOL {@code = SPACES OR = LOW-VALUES} condition.
     *
     * @param value the string to check
     * @return {@code true} if the value is null, empty, or blank
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Checks if a string consists entirely of digit characters (0-9).
     * Maps to COBOL {@code IS NUMERIC} condition for {@code PIC X} fields
     * (which is true only if all characters are digits).
     *
     * @param value the string to check
     * @return {@code true} if non-empty and all characters are digits
     */
    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Masks a card number for secure logging, showing only the last 4 digits.
     *
     * @param cardNum the full card number
     * @return masked representation, e.g., {@code "****1234"}
     */
    private static String maskCardNumber(String cardNum) {
        if (cardNum == null || cardNum.length() <= 4) {
            return "****";
        }
        return "****" + cardNum.substring(cardNum.length() - 4);
    }
}

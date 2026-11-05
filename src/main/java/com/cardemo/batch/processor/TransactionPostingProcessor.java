package com.cardemo.batch.processor;

import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.CategoryBalance;
import com.cardemo.entity.DailyTransaction;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CategoryBalanceRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Spring Batch ItemProcessor that validates and posts daily transaction records.
 *
 * <p>Faithfully translates COBOL program CBTRN02C.cbl paragraphs 1500-VALIDATE-TRAN
 * through 2800-UPDATE-ACCOUNT-REC with 100% business logic parity.</p>
 *
 * <p>Validation sequence (4-stage reject code chain):</p>
 * <ol>
 *   <li>Reject 100 — XREF lookup: card number not found in CARDXREF
 *       (← 1500-A-LOOKUP-XREF, lines 380-391)</li>
 *   <li>Reject 101 — Account lookup: account ID not found in ACCTDATA
 *       (← 1500-B-LOOKUP-ACCT, lines 393-399)</li>
 *   <li>Reject 102 — Credit limit exceeded: cycle credit - debit + amount exceeds limit
 *       (← 1500-B-LOOKUP-ACCT, lines 403-413)</li>
 *   <li>Reject 103 — Account expired: expiration date before transaction date
 *       (← 1500-B-LOOKUP-ACCT, lines 414-420)</li>
 * </ol>
 *
 * <p><strong>CRITICAL:</strong> Reject codes 102 and 103 are BOTH evaluated sequentially
 * with no guard between them. If both conditions fail, reject code 103 overwrites 102,
 * matching exact COBOL behavior where there is no ELSE between the two IF statements.</p>
 *
 * <p>All monetary calculations use {@link BigDecimal} with {@link RoundingMode#HALF_UP},
 * matching COBOL COMP-3 packed decimal semantics. No floating-point types are used anywhere
 * in this class.</p>
 *
 * <p>Thread safety: All validation state is method-local. No shared mutable instance fields
 * exist, ensuring safe operation in multi-threaded Spring Batch step configurations.</p>
 *
 * @see DailyTransaction  input record type (← CVTRA06Y.cpy DALYTRAN-RECORD)
 * @see Transaction        output record type (← CVTRA05Y.cpy TRAN-RECORD)
 */
@Component
public class TransactionPostingProcessor implements ItemProcessor<DailyTransaction, Transaction> {

    private static final Logger log = LoggerFactory.getLogger(TransactionPostingProcessor.class);

    /**
     * DB2-format timestamp pattern matching COBOL Z-GET-DB2-FORMAT-TIMESTAMP paragraph
     * (CBTRN02C.cbl lines 692-705). Format: YYYY-MM-DD-HH.MM.SS.mmmmmm (26 characters
     * with microsecond precision). Uses dashes between date components and dots between
     * time components per DB2 convention — NOT the standard ISO-8601 'T' separator or
     * colon time separators.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /**
     * Monetary field scale matching COBOL PIC S9(n)V99 — always 2 decimal places.
     * Applied via {@link BigDecimal#setScale(int, RoundingMode)} after every arithmetic
     * operation to maintain exact COMP-3 precision parity.
     */
    private static final int MONETARY_SCALE = 2;

    /** Reject code: card cross-reference not found (← CBTRN02C.cbl line 385). */
    private static final int REJECT_XREF_NOT_FOUND = 100;

    /** Reject code: account record not found (← CBTRN02C.cbl line 396). */
    private static final int REJECT_ACCOUNT_NOT_FOUND = 101;

    /** Reject code: credit limit exceeded (← CBTRN02C.cbl line 409). */
    private static final int REJECT_OVERLIMIT = 102;

    /** Reject code: account expired (← CBTRN02C.cbl line 417). */
    private static final int REJECT_EXPIRED = 103;

    /** Sentinel value indicating no validation failure — transaction passes all checks. */
    private static final int NO_REJECT = 0;

    /**
     * Length of the date portion extracted from DALYTRAN-ORIG-TS via COBOL
     * substring reference {@code DALYTRAN-ORIG-TS(1:10)}. Extracts 'YYYY-MM-DD'.
     */
    private static final int DATE_PORTION_LENGTH = 10;

    /** CARDXREF VSAM dataset access (← CBTRN02C.cbl SELECT XREF-FILE). */
    private final CardXrefRepository cardXrefRepository;

    /** ACCTDATA VSAM dataset access (← CBTRN02C.cbl SELECT ACCOUNT-FILE). */
    private final AccountRepository accountRepository;

    /** TCATBALF VSAM dataset access (← CBTRN02C.cbl SELECT TCATBAL-FILE). */
    private final CategoryBalanceRepository categoryBalanceRepository;

    /**
     * Writer for the DALYREJS reject file. Injected to allow the processor to
     * directly write rejected records, faithfully translating COBOL paragraph
     * 2500-WRITE-REJECT-REC which is invoked inline during the processing loop.
     * In Spring Batch, the main ItemWriter only receives non-null (valid) items,
     * so rejects must be written explicitly from the processor.
     */
    private final com.cardemo.batch.writer.RejectFileWriter rejectFileWriter;

    /**
     * Constructs the processor with required repository and writer dependencies.
     *
     * <p>Uses constructor injection per Spring best practices. With a single constructor,
     * no {@code @Autowired} annotation is needed — Spring auto-detects and injects.</p>
     *
     * @param cardXrefRepository        XREF-FILE access for card-to-account resolution
     * @param accountRepository         ACCOUNT-FILE access for validation and balance updates
     * @param categoryBalanceRepository TCATBAL-FILE access for category balance maintenance
     * @param rejectFileWriter          DALYREJS reject file writer (← 2500-WRITE-REJECT-REC)
     */
    public TransactionPostingProcessor(CardXrefRepository cardXrefRepository,
                                       AccountRepository accountRepository,
                                       CategoryBalanceRepository categoryBalanceRepository,
                                       com.cardemo.batch.writer.RejectFileWriter rejectFileWriter) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.categoryBalanceRepository = categoryBalanceRepository;
        this.rejectFileWriter = rejectFileWriter;
    }

    /**
     * Processes a single daily transaction through the 4-stage validation pipeline,
     * then posts valid transactions and updates account/category balance records.
     *
     * <p>Translates CBTRN02C.cbl main processing loop (lines 202-219):</p>
     * <pre>
     * PERFORM 1500-VALIDATE-TRAN
     * IF WS-VALIDATION-FAIL-REASON = 0
     *     PERFORM 2000-POST-TRANSACTION
     * ELSE
     *     ADD 1 TO WS-REJECT-COUNT
     *     PERFORM 2500-WRITE-REJECT-REC
     * END-IF
     * </pre>
     *
     * <p>For valid transactions, also executes:</p>
     * <ul>
     *   <li>2700-UPDATE-TCATBAL — category balance create/update</li>
     *   <li>2800-UPDATE-ACCOUNT-REC — account balance and cycle credit/debit update</li>
     * </ul>
     *
     * @param item the daily transaction record from the reader (← DALYTRAN sequential file)
     * @return a posted {@link Transaction} for valid items, or {@code null} for rejected items
     *         (Spring Batch convention: {@code null} signals the item should be filtered/skipped)
     * @throws Exception if an unrecoverable error occurs during processing
     */
    @Override
    public Transaction process(DailyTransaction item) throws Exception {
        log.info("Processing daily transaction: dalytranId={}", item.getDalytranId());

        // Initialize validation state — method-local for thread safety
        // (← MOVE 0 TO WS-VALIDATION-FAIL-REASON)
        // (← MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC)
        int rejectCode = NO_REJECT;
        String rejectDescription = "";

        // ═══════════════════════════════════════════════════════════════════════════
        // Stage 1: XREF lookup (← 1500-A-LOOKUP-XREF, CBTRN02C.cbl lines 380-391)
        // READ XREF-FILE INTO CARD-XREF-RECORD
        //    INVALID KEY → reject 100 'INVALID CARD NUMBER FOUND'
        // ═══════════════════════════════════════════════════════════════════════════
        CardXref xref = lookupXref(item.getCardNum());
        if (xref == null) {
            rejectCode = REJECT_XREF_NOT_FOUND;
            rejectDescription = "INVALID CARD NUMBER FOUND";
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // Stages 2-4: Account lookup + credit limit + expiry checks
        // (← 1500-B-LOOKUP-ACCT, CBTRN02C.cbl lines 393-421)
        //
        // Per 1500-VALIDATE-TRAN (lines 370-378):
        //   PERFORM 1500-A-LOOKUP-XREF
        //   IF WS-VALIDATION-FAIL-REASON = 0
        //       PERFORM 1500-B-LOOKUP-ACCT
        //   END-IF
        //
        // Account-level validation only runs when XREF lookup succeeded.
        // ═══════════════════════════════════════════════════════════════════════════
        Account account = null;
        if (rejectCode == NO_REJECT) {
            account = lookupAccount(xref.getAccountId());

            if (account == null) {
                // Stage 2: Account not found
                // (← line 396: MOVE 101 TO WS-VALIDATION-FAIL-REASON)
                rejectCode = REJECT_ACCOUNT_NOT_FOUND;
                rejectDescription = "ACCOUNT RECORD NOT FOUND";
            } else {
                // ── Stage 3: Credit limit check (← lines 403-413) ──────────────
                // COMPUTE WS-TEMP-BAL =
                //     ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
                BigDecimal currCycCredit = safeDecimal(account.getCurrCycCredit());
                BigDecimal currCycDebit = safeDecimal(account.getCurrCycDebit());
                BigDecimal tranAmount = safeDecimal(item.getAmount());

                BigDecimal tempBal = currCycCredit
                        .subtract(currCycDebit)
                        .add(tranAmount)
                        .setScale(MONETARY_SCALE, RoundingMode.HALF_UP);

                // IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL → CONTINUE (OK)
                // ELSE → MOVE 102 TO WS-VALIDATION-FAIL-REASON
                BigDecimal creditLimit = safeDecimal(account.getCreditLimit());
                if (creditLimit.compareTo(tempBal) < 0) {
                    rejectCode = REJECT_OVERLIMIT;
                    rejectDescription = "OVERLIMIT TRANSACTION";
                }

                // ── Stage 4: Expiration check (← lines 414-420) ────────────────
                //
                // CRITICAL: NO ELSE between stages 3 and 4 in COBOL source.
                // Both checks are evaluated sequentially on the same record.
                // If BOTH fail, reject 103 OVERWRITES reject 102 — this is the
                // exact COBOL behavior being preserved. Do NOT add a guard here.
                //
                // IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10) → CONTINUE (OK)
                // ELSE → MOVE 103 TO WS-VALIDATION-FAIL-REASON
                String acctExpDate = account.getExpirationDate();
                String tranDate = extractDatePortion(item.getOrigTimestamp());

                if (acctExpDate != null && tranDate != null && !tranDate.isEmpty()
                        && acctExpDate.compareTo(tranDate) < 0) {
                    rejectCode = REJECT_EXPIRED;
                    rejectDescription = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // Reject handling — return null per Spring Batch filtering convention
        // (← ADD 1 TO WS-REJECT-COUNT; PERFORM 2500-WRITE-REJECT-REC)
        // Reject file writing is handled by a separate writer/listener mechanism
        // in the Spring Batch job configuration.
        // ═══════════════════════════════════════════════════════════════════════════
        if (rejectCode != NO_REJECT) {
            log.warn("Transaction rejected: dalytranId={}, rejectCode={}, reason={}",
                    item.getDalytranId(), rejectCode, rejectDescription);

            // ── Write reject record to DALYREJS file ──────────────────────────
            // Translates COBOL paragraph 2500-WRITE-REJECT-REC (lines 446-465):
            //   MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
            //   MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER
            //   WRITE FD-REJS-RECORD FROM REJECT-RECORD
            // The processor writes rejects directly because Spring Batch's null
            // return convention means rejected items never reach the step writer.
            try {
                String originalData = buildOriginalTransactionData(item);
                com.cardemo.batch.writer.RejectFileWriter.RejectRecord rejectRecord =
                        new com.cardemo.batch.writer.RejectFileWriter.RejectRecord(
                                originalData, rejectCode, rejectDescription);
                org.springframework.batch.item.Chunk<com.cardemo.batch.writer.RejectFileWriter.RejectRecord> rejectChunk =
                        new org.springframework.batch.item.Chunk<>(java.util.List.of(rejectRecord));
                rejectFileWriter.write(rejectChunk);
            } catch (Exception e) {
                log.error("Failed to write reject record for dalytranId={}: {}",
                        item.getDalytranId(), e.getMessage(), e);
                throw new com.cardemo.common.exception.CardDemoException(
                        "Failed to write reject record", e);
            }

            return null;
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // Post valid transaction (← 2000-POST-TRANSACTION, lines 424-444)
        // ═══════════════════════════════════════════════════════════════════════════
        Transaction posted = createPostedTransaction(item);

        // ═══════════════════════════════════════════════════════════════════════════
        // Update category balance (← 2700-UPDATE-TCATBAL, lines 467-542)
        // ═══════════════════════════════════════════════════════════════════════════
        updateCategoryBalance(item, xref);

        // ═══════════════════════════════════════════════════════════════════════════
        // Update account record (← 2800-UPDATE-ACCOUNT-REC, lines 545-560)
        // ═══════════════════════════════════════════════════════════════════════════
        updateAccountRecord(item, account);

        log.debug("Transaction posted successfully: dalytranId={}", item.getDalytranId());
        return posted;
    }

    /**
     * Looks up the card cross-reference record by card number.
     *
     * <p>Translates COBOL paragraph 1500-A-LOOKUP-XREF (CBTRN02C.cbl lines 380-391):</p>
     * <pre>
     * MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
     * READ XREF-FILE INTO CARD-XREF-RECORD
     *    INVALID KEY
     *      MOVE 100 TO WS-VALIDATION-FAIL-REASON
     *      MOVE 'INVALID CARD NUMBER FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
     *    NOT INVALID KEY
     *      CONTINUE
     * END-READ
     * </pre>
     *
     * @param cardNum the card number from the daily transaction (DALYTRAN-CARD-NUM, 16 chars)
     * @return the {@link CardXref} record if found, or {@code null} if not found (INVALID KEY)
     */
    private CardXref lookupXref(String cardNum) {
        Optional<CardXref> result = cardXrefRepository.findById(cardNum);
        return result.orElse(null);
    }

    /**
     * Looks up the account record by account ID obtained from the XREF lookup.
     *
     * <p>Translates first part of COBOL paragraph 1500-B-LOOKUP-ACCT (lines 393-400):</p>
     * <pre>
     * MOVE XREF-ACCT-ID TO FD-ACCT-ID
     * READ ACCOUNT-FILE INTO ACCOUNT-RECORD
     *    INVALID KEY
     *      MOVE 101 TO WS-VALIDATION-FAIL-REASON
     *      MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
     * </pre>
     *
     * @param acctId the account ID from the CardXref record (XREF-ACCT-ID, 11 chars)
     * @return the {@link Account} record if found, or {@code null} if not found (INVALID KEY)
     */
    private Account lookupAccount(String acctId) {
        Optional<Account> result = accountRepository.findById(acctId);
        return result.orElse(null);
    }

    /**
     * Creates a posted Transaction by mapping all fields 1:1 from the daily transaction.
     *
     * <p>Translates COBOL paragraph 2000-POST-TRANSACTION (CBTRN02C.cbl lines 424-444)
     * which performs 12 field MOVE statements followed by timestamp generation:</p>
     * <pre>
     * MOVE DALYTRAN-ID            TO TRAN-ID
     * MOVE DALYTRAN-TYPE-CD       TO TRAN-TYPE-CD
     * MOVE DALYTRAN-CAT-CD        TO TRAN-CAT-CD
     * MOVE DALYTRAN-SOURCE        TO TRAN-SOURCE
     * MOVE DALYTRAN-DESC          TO TRAN-DESC
     * MOVE DALYTRAN-AMT           TO TRAN-AMT
     * MOVE DALYTRAN-MERCHANT-ID   TO TRAN-MERCHANT-ID
     * MOVE DALYTRAN-MERCHANT-NAME TO TRAN-MERCHANT-NAME
     * MOVE DALYTRAN-MERCHANT-CITY TO TRAN-MERCHANT-CITY
     * MOVE DALYTRAN-MERCHANT-ZIP  TO TRAN-MERCHANT-ZIP
     * MOVE DALYTRAN-CARD-NUM      TO TRAN-CARD-NUM
     * MOVE DALYTRAN-ORIG-TS       TO TRAN-ORIG-TS
     * PERFORM Z-GET-DB2-FORMAT-TIMESTAMP
     * MOVE DB2-FORMAT-TS          TO TRAN-PROC-TS
     * </pre>
     *
     * @param item the daily transaction providing source field values
     * @return a new {@link Transaction} with all fields mapped and processing timestamp set
     */
    private Transaction createPostedTransaction(DailyTransaction item) {
        Transaction posted = new Transaction(
                item.getDalytranId(),       // DALYTRAN-ID       → TRAN-ID
                item.getTypeCode(),         // DALYTRAN-TYPE-CD  → TRAN-TYPE-CD
                item.getCategoryCode(),     // DALYTRAN-CAT-CD   → TRAN-CAT-CD
                item.getSource(),           // DALYTRAN-SOURCE   → TRAN-SOURCE
                item.getDescription(),      // DALYTRAN-DESC     → TRAN-DESC
                item.getAmount(),           // DALYTRAN-AMT      → TRAN-AMT (BigDecimal, no conversion)
                item.getMerchantId(),       // DALYTRAN-MERCHANT-ID   → TRAN-MERCHANT-ID
                item.getMerchantName(),     // DALYTRAN-MERCHANT-NAME → TRAN-MERCHANT-NAME
                item.getMerchantCity(),     // DALYTRAN-MERCHANT-CITY → TRAN-MERCHANT-CITY
                item.getMerchantZip(),      // DALYTRAN-MERCHANT-ZIP  → TRAN-MERCHANT-ZIP
                item.getCardNum(),          // DALYTRAN-CARD-NUM      → TRAN-CARD-NUM
                item.getOrigTimestamp(),    // DALYTRAN-ORIG-TS       → TRAN-ORIG-TS
                generateProcTimestamp()     // Z-GET-DB2-FORMAT-TIMESTAMP → TRAN-PROC-TS
        );
        return posted;
    }

    /**
     * Updates the transaction category balance record for the posted transaction.
     *
     * <p>Translates COBOL paragraph 2700-UPDATE-TCATBAL (CBTRN02C.cbl lines 467-542):</p>
     * <pre>
     * MOVE XREF-ACCT-ID    TO FD-TRANCAT-ACCT-ID
     * MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD
     * MOVE DALYTRAN-CAT-CD  TO FD-TRANCAT-CD
     * READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
     *    INVALID KEY → WS-CREATE-TRANCAT-REC = 'Y'
     * IF WS-CREATE-TRANCAT-REC = 'Y'
     *    PERFORM 2700-A-CREATE-TCATBAL-REC  (INITIALIZE, set key, balance=amount, WRITE)
     * ELSE
     *    PERFORM 2700-B-UPDATE-TCATBAL-REC  (ADD amount TO balance, REWRITE)
     * </pre>
     *
     * <p>Composite key: XREF-ACCT-ID + DALYTRAN-TYPE-CD + DALYTRAN-CAT-CD.
     * All balance arithmetic uses {@link BigDecimal#add(BigDecimal)} — never floating-point.</p>
     *
     * @param item the daily transaction providing amount and category info
     * @param xref the card cross-reference providing the account ID for the composite key
     */
    private void updateCategoryBalance(DailyTransaction item, CardXref xref) {
        String acctId = xref.getAccountId();
        String typeCode = item.getTypeCode();
        Integer categoryCode = item.getCategoryCode();
        BigDecimal tranAmount = safeDecimal(item.getAmount());

        // Composite key lookup: XREF-ACCT-ID + DALYTRAN-TYPE-CD + DALYTRAN-CAT-CD
        Optional<CategoryBalance> existing = categoryBalanceRepository
                .findByAccountIdAndTypeCodeAndCategoryCode(acctId, typeCode, categoryCode);

        if (existing.isPresent()) {
            // ← 2700-B-UPDATE-TCATBAL-REC (lines 526-542):
            // ADD DALYTRAN-AMT TO TRAN-CAT-BAL; REWRITE
            CategoryBalance balance = existing.get();
            BigDecimal updatedBal = safeDecimal(balance.getBalance())
                    .add(tranAmount)
                    .setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
            balance.setBalance(updatedBal);
            categoryBalanceRepository.save(balance);
            log.debug("Updated category balance: acctId={}, typeCode={}, catCode={}, newBalance={}",
                    acctId, typeCode, categoryCode, updatedBal);
        } else {
            // ← 2700-A-CREATE-TCATBAL-REC (lines 503-524):
            // INITIALIZE TRAN-CAT-BAL-RECORD, set key fields,
            // ADD DALYTRAN-AMT TO TRAN-CAT-BAL, WRITE
            CategoryBalance newBalance = new CategoryBalance(
                    acctId,
                    typeCode,
                    categoryCode,
                    tranAmount.setScale(MONETARY_SCALE, RoundingMode.HALF_UP)
            );
            categoryBalanceRepository.save(newBalance);
            log.debug("Created category balance: acctId={}, typeCode={}, catCode={}, balance={}",
                    acctId, typeCode, categoryCode, tranAmount);
        }
    }

    /**
     * Updates the account record with the daily transaction amount.
     *
     * <p>Translates COBOL paragraph 2800-UPDATE-ACCOUNT-REC (CBTRN02C.cbl lines 545-560):</p>
     * <pre>
     * ADD DALYTRAN-AMT TO ACCT-CURR-BAL
     * IF DALYTRAN-AMT &gt;= 0
     *     ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
     * ELSE
     *     ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
     * END-IF
     * REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
     * </pre>
     *
     * <p>The JPA {@code save()} call with the entity's {@code @Version} field handles
     * optimistic locking, equivalent to the COBOL REWRITE semantics.</p>
     *
     * @param item    the daily transaction providing the amount (DALYTRAN-AMT)
     * @param account the account to update (has @Version for optimistic locking)
     */
    private void updateAccountRecord(DailyTransaction item, Account account) {
        BigDecimal tranAmount = safeDecimal(item.getAmount());

        // ADD DALYTRAN-AMT TO ACCT-CURR-BAL
        BigDecimal newBalance = safeDecimal(account.getCurrBal())
                .add(tranAmount)
                .setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
        account.setCurrBal(newBalance);

        // Route to credit or debit cycle accumulator based on amount sign
        if (tranAmount.compareTo(BigDecimal.ZERO) >= 0) {
            // IF DALYTRAN-AMT >= 0 → ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
            BigDecimal newCredit = safeDecimal(account.getCurrCycCredit())
                    .add(tranAmount)
                    .setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
            account.setCurrCycCredit(newCredit);
        } else {
            // ELSE → ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
            BigDecimal newDebit = safeDecimal(account.getCurrCycDebit())
                    .add(tranAmount)
                    .setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
            account.setCurrCycDebit(newDebit);
        }

        // REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
        // JPA save() with @Version handles optimistic locking (REWRITE equivalent)
        accountRepository.save(account);
        log.debug("Updated account: acctId={}, newBalance={}", account.getAcctId(), newBalance);
    }

    /**
     * Generates a DB2-format processing timestamp for the current moment.
     *
     * <p>Translates COBOL paragraph Z-GET-DB2-FORMAT-TIMESTAMP (CBTRN02C.cbl lines 692-705):</p>
     * <pre>
     * MOVE FUNCTION CURRENT-DATE TO COBOL-TS
     * MOVE COB-YYYY TO DB2-YYYY   (date components separated by dashes)
     * MOVE COB-HH   TO DB2-HH     (time components separated by dots)
     * MOVE COB-MIL  TO DB2-MIL    (milliseconds + '0000' padding = 6 digits)
     * </pre>
     *
     * <p>Output format: {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} (26 characters with
     * microsecond precision). The third separator is a dash (not 'T'), and time
     * components use dots (not colons) — matching DB2 timestamp convention exactly.</p>
     *
     * @return the formatted timestamp string (26 characters)
     */
    private String generateProcTimestamp() {
        return LocalDateTime.now().format(DB2_TIMESTAMP_FORMAT);
    }

    /**
     * Extracts the date portion from a DALYTRAN-ORIG-TS timestamp string.
     *
     * <p>Implements the COBOL reference substring {@code DALYTRAN-ORIG-TS(1:10)} which
     * extracts the first 10 characters representing the date portion in YYYY-MM-DD format.
     * This is used in the expiration check (Stage 4) to compare against
     * ACCT-EXPIRAION-DATE.</p>
     *
     * @param origTimestamp the 26-character timestamp, or null
     * @return the first 10 characters (date portion), or empty string if unavailable
     */
    private String extractDatePortion(String origTimestamp) {
        if (origTimestamp != null && origTimestamp.length() >= DATE_PORTION_LENGTH) {
            return origTimestamp.substring(0, DATE_PORTION_LENGTH);
        }
        return "";
    }

    /**
     * Returns the given BigDecimal value, or {@link BigDecimal#ZERO} if null.
     *
     * <p>Defensive null guard matching COBOL behavior where COMP-3 fields in
     * WORKING-STORAGE are initialized to zero by default. Prevents
     * {@link NullPointerException} in arithmetic chains.</p>
     *
     * @param value the BigDecimal value to check
     * @return the original value if non-null, or {@link BigDecimal#ZERO}
     */
    private BigDecimal safeDecimal(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Reconstructs the original daily transaction data as a concatenated string
     * matching the COBOL DALYTRAN-RECORD layout for inclusion in the reject file.
     *
     * <p>Translates COBOL paragraph 2500-WRITE-REJECT-REC reference:
     * {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA PIC X(350)}.
     * The entity fields are concatenated in CVTRA06Y.cpy field order to
     * produce a representation of the original fixed-width record.</p>
     *
     * @param item the daily transaction entity
     * @return a string representation of the original transaction data
     */
    private String buildOriginalTransactionData(DailyTransaction item) {
        StringBuilder sb = new StringBuilder(350);
        sb.append(padRight(item.getDalytranId(), 16));
        sb.append(padRight(item.getTypeCode(), 2));
        sb.append(String.format("%04d", item.getCategoryCode() != null ? item.getCategoryCode() : 0));
        sb.append(padRight(item.getSource(), 10));
        sb.append(padRight(item.getDescription(), 100));
        sb.append(String.format("%012.2f", safeDecimal(item.getAmount())));
        sb.append(padRight(item.getMerchantId(), 9));
        sb.append(padRight(item.getMerchantName(), 50));
        sb.append(padRight(item.getMerchantCity(), 50));
        sb.append(padRight(item.getMerchantZip(), 10));
        sb.append(padRight(item.getCardNum(), 16));
        sb.append(padRight(item.getOrigTimestamp(), 26));
        sb.append(padRight(item.getProcTimestamp(), 26));
        // Pad to 350 total if shorter
        while (sb.length() < 350) {
            sb.append(' ');
        }
        return sb.substring(0, Math.min(sb.length(), 350));
    }

    /**
     * Right-pads a string with spaces to the specified length, matching COBOL
     * {@code PIC X(n)} behavior.
     *
     * @param value  the input string (may be null)
     * @param length the target length
     * @return the padded or truncated string
     */
    private static String padRight(String value, int length) {
        String safe = (value != null) ? value : "";
        if (safe.length() >= length) {
            return safe.substring(0, length);
        }
        return String.format("%-" + length + "s", safe);
    }
}

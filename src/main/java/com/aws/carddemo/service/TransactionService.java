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
package com.aws.carddemo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aws.carddemo.common.util.IdGenerator;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Transaction <em>list</em>, <em>view</em> and <em>add</em> service &mdash; the Java
 * re-platform of the three CardDemo online transaction programs (relocated under
 * {@code legacy/cbl/} during the migration):
 *
 * <ul>
 *   <li>{@code COTRN00C} (CICS transaction {@code CT00}) &mdash; the transaction-list
 *       browse. On the mainframe this used
 *       {@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR} over the
 *       {@code TRANSACT} VSAM KSDS in {@code TRAN-ID} (primary-key) order, ten rows per
 *       page, paged with PF7 (backward) / PF8 (forward). Reproduced by
 *       {@link #listTransactions(Pageable)} (key-ordered) and, for the card-scoped
 *       chronological browse used elsewhere, {@link #listTransactions(String, Pageable)}.</li>
 *   <li>{@code COTRN01C} (CICS transaction {@code CT01}) &mdash; the single-transaction
 *       view; a keyed {@code EXEC CICS READ ... RIDFLD(TRAN-ID)} whose {@code NOTFND}
 *       response surfaced {@code "Transaction ID NOT found..."}. Reproduced by
 *       {@link #viewTransaction(String)}.</li>
 *   <li>{@code COTRN02C} (CICS transaction {@code CT02}) &mdash; the transaction add,
 *       including its key-field and data-field edits and the reverse-browse
 *       transaction-id generation. Reproduced by {@link #addTransaction(AddTransactionCommand)}.</li>
 * </ul>
 *
 * <h2>Pseudo-conversational translation (AAP &sect;0.7.1 H1)</h2>
 * The COBOL programs are pseudo-conversational: they {@code RECEIVE} a 3270 map,
 * validate it, and either re-{@code SEND} the map with a {@code WS-MESSAGE} error or
 * perform the file operation. There is no terminal here, so this service exposes plain
 * method calls; the screen navigation, PF-key routing and confirm/re-display flow are
 * web-layer concerns. Crucially, the <strong>caller-visible messages and their exact
 * short-circuit evaluation order are preserved verbatim</strong> (AAP &sect;0.9.2):
 * every COBOL {@code SEND-...-SCREEN} paragraph ends with {@code EXEC CICS RETURN},
 * which terminates the pseudo-conversational turn, so the <em>first</em> failing edit is
 * the one the operator sees. That "stop on first failure" behavior is reproduced by
 * throwing on the first failing check.
 *
 * <h2>Outcome signalling</h2>
 * <ul>
 *   <li>Input-edit failures (the COBOL {@code WS-MESSAGE} set-and-re-display cases) are
 *       surfaced as {@link TransactionValidationException}, a typed unchecked exception
 *       carrying the exact legacy message text.</li>
 *   <li>A keyed lookup that finds nothing (COBOL {@code NOTFND}) is surfaced as
 *       {@link com.aws.carddemo.exception.RecordNotFoundException}.</li>
 *   <li>A duplicate transaction-id insert (COBOL {@code DUPKEY}/{@code DUPREC}) is
 *       surfaced as {@link com.aws.carddemo.exception.DuplicateKeyException}.</li>
 * </ul>
 *
 * <h2>Transaction-id generation (AAP &sect;0.7.1 H5)</h2>
 * {@code COTRN02C} paragraph {@code ADD-TRANSACTION} generates the next id with a
 * reverse browse from {@code HIGH-VALUES}
 * ({@code MOVE HIGH-VALUES} &rarr; {@code STARTBR} &rarr; {@code READPREV} &rarr;
 * {@code ENDBR}) and then {@code ADD 1}. Per the migration mapping this becomes a
 * repository "max-key lookup plus one":
 * {@link TransactionRepository#findMaxTranId()} feeds
 * {@link IdGenerator#nextTransactionId(String)} (a pure static utility, invoked
 * directly &mdash; it is not a Spring bean). The empty-table case reproduces the COBOL
 * {@code READPREV}/{@code ENDFILE} &rarr; {@code MOVE ZEROS} path, yielding the first id
 * {@code "0000000000000001"}.
 *
 * <h2>Concurrency guard</h2>
 * {@link #addTransaction(AddTransactionCommand)} performs the max-id lookup and the
 * insert inside a single {@code @Transactional} read-write transaction. The primary key
 * on {@code transaction.tran_id} is the database backstop: if two concurrent adds
 * observe the same maximum, one insert wins and the other fails the unique constraint,
 * which is translated to {@link DuplicateKeyException} (the exact
 * {@code "Tran ID already exist..."} outcome of the COBOL {@code WRITE}
 * {@code DUPKEY}/{@code DUPREC} branch) rather than silently duplicating an id. An
 * in-transaction retry is intentionally not attempted, because a failed insert marks the
 * surrounding transaction rollback-only and a retry within it could not commit.
 *
 * <h2>Monetary and timestamp fidelity</h2>
 * The transaction amount is modeled exclusively with {@link BigDecimal} at scale 2
 * (COBOL {@code PIC S9(09)V99}); {@code double}/{@code float} are never used. The
 * origination and processing timestamps reproduce {@code COTRN02C} exactly: that program
 * moves the <em>entered</em> {@code YYYY-MM-DD} date fields
 * ({@code TORIGDTI}/{@code TPROCDTI}, {@code PIC X(10)}) straight into the
 * {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} fields ({@code PIC X(26)}) with no
 * time-of-day synthesis, so this service stores the entered date strings verbatim to
 * preserve behavioral parity (AAP G2). A synthesized timestamp would change the stored
 * value, so timestamp formatting is deliberately not applied on the add path.
 *
 * <h2>Design constraints</h2>
 * Constructor injection only (no field injection, no Lombok); the collaborators are the
 * two Spring Data repositories and the {@link DateValidationService} (the migration of
 * {@code CSUTLDTC}). The service is stateless and therefore thread-safe. It is DTO-free:
 * it accepts a small service-layer {@link AddTransactionCommand} of raw submitted fields
 * and returns domain {@link Transaction} entities and {@link Page} windows &mdash; no
 * web {@code dto}/{@code mapper} types are referenced.
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Service
public class TransactionService {

    /** Date mask passed to {@link DateValidationService}; the only mask the callers used. */
    private static final String DATE_FORMAT_YYYY_MM_DD = "YYYY-MM-DD";

    /** Exact field width of the amount input ({@code TRNAMTI PIC X(12)}): {@code [+-]99999999.99}. */
    private static final int AMOUNT_LENGTH = 12;

    /** Exact field width of a date input ({@code TORIGDTI}/{@code TPROCDTI PIC X(10)}): {@code YYYY-MM-DD}. */
    private static final int DATE_LENGTH = 10;

    /** Fixed width of a normalized card number ({@code XREF-CARD-NUM PIC X(16)}); zero-padded. */
    private static final int CARD_NUMBER_LENGTH = 16;

    /** Zero-padding format for a 16-digit card number, matching the COBOL {@code PIC 9(16)} normalization. */
    private static final String CARD_NUMBER_FORMAT = "%0" + CARD_NUMBER_LENGTH + "d";

    /** Maximum width of the type code ({@code TTYPCD} BMS field, {@code PIC X(2)}). */
    private static final int TYPE_CD_MAX_LENGTH = 2;

    /** Maximum width of the category code ({@code TCATCD} BMS field, {@code PIC X(4)}). */
    private static final int CATEGORY_CD_MAX_LENGTH = 4;

    /** Maximum width of the transaction source ({@code TRNSRC} BMS field, {@code PIC X(10)}). */
    private static final int SOURCE_MAX_LENGTH = 10;

    /** Maximum width of the description ({@code TDESC} BMS field, {@code PIC X(60)}). */
    private static final int DESCRIPTION_MAX_LENGTH = 60;

    /** Maximum width of the merchant id ({@code MID} BMS field, {@code PIC X(9)}). */
    private static final int MERCHANT_ID_MAX_LENGTH = 9;

    /** Maximum width of the merchant name ({@code MNAME} BMS field, {@code PIC X(30)}). */
    private static final int MERCHANT_NAME_MAX_LENGTH = 30;

    /** Maximum width of the merchant city ({@code MCITY} BMS field, {@code PIC X(25)}). */
    private static final int MERCHANT_CITY_MAX_LENGTH = 25;

    /** Maximum width of the merchant ZIP ({@code MZIP} BMS field, {@code PIC X(10)}). */
    private static final int MERCHANT_ZIP_MAX_LENGTH = 10;

    /** Repository over the posted-transaction master ({@code TRANSACT.VSAM.KSDS}). */
    private final TransactionRepository transactionRepository;

    /** Repository over the card cross-reference ({@code CARDXREF.VSAM.KSDS} + account alternate index). */
    private final CardXrefRepository cardXrefRepository;

    /** Date-validation service ({@code CSUTLDTC} re-platform) used by the add-transaction date edits. */
    private final DateValidationService dateValidationService;

    /**
     * Creates the service with its collaborators. Dependencies are assigned directly
     * (no overridable method is invoked) so the constructor is free of any {@code this}
     * escape.
     *
     * @param transactionRepository  repository for the {@link Transaction} master; must not be {@code null}
     * @param cardXrefRepository     repository for the {@link CardXref} cross-reference; must not be {@code null}
     * @param dateValidationService  date-validation service ({@code CSUTLDTC}); must not be {@code null}
     */
    public TransactionService(TransactionRepository transactionRepository,
                              CardXrefRepository cardXrefRepository,
                              DateValidationService dateValidationService) {
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository must not be null");
        this.cardXrefRepository = Objects.requireNonNull(
                cardXrefRepository, "cardXrefRepository must not be null");
        this.dateValidationService = Objects.requireNonNull(
                dateValidationService, "dateValidationService must not be null");
    }

    // ------------------------------------------------------------------------
    // A. Transaction LIST (COTRN00C, CT00)
    // ------------------------------------------------------------------------

    /**
     * Lists the transactions of a single card in chronological (processing-timestamp)
     * order &mdash; the card-scoped browse backed by the legacy {@code TRANSACT}
     * alternate index ({@code proc_ts}, with a {@code tran_id} tie-breaker). This reproduces
     * the forward/backward paged browse ({@code STARTBR}/{@code READNEXT}/{@code READPREV})
     * as a {@link Pageable} window while preserving ascending processing order (AAP &sect;0.4.3).
     *
     * <p>A {@code null} or blank card number yields an empty page (no card selected),
     * rather than issuing a query, mirroring the legacy "nothing to browse" outcome.</p>
     *
     * @param cardNumber the 16-character card number to browse; {@code null}/blank yields an empty page
     * @param pageable   the paging request; {@code null} is treated as unpaged
     * @return a page of the card's transactions in ascending processing-timestamp order; never {@code null}
     */
    @Transactional(readOnly = true)
    public Page<Transaction> listTransactions(String cardNumber, Pageable pageable) {
        Pageable effective = (pageable == null) ? Pageable.unpaged() : pageable;
        if (cardNumber == null || cardNumber.isBlank()) {
            return Page.empty(effective);
        }
        return transactionRepository.findByCardNumOrderByProcTsAscTranIdAsc(cardNumber, effective);
    }

    /**
     * Lists all transactions in ascending {@code tran_id} (primary-key) order &mdash; the
     * faithful reproduction of the {@code COTRN00C} browse, which positions the
     * {@code TRANSACT} VSAM browse on the {@code TRAN-ID} key and reads a page of rows
     * regardless of card.
     *
     * <p>The ascending {@code tran_id} ordering is enforced here (the COBOL browse is
     * always in key order); only the page number and page size are taken from the
     * supplied {@link Pageable}. A {@code null} or unpaged request returns every row in a
     * single key-ordered page.</p>
     *
     * @param pageable the paging request (page number and size); {@code null}/unpaged returns all rows
     * @return a page of transactions in ascending {@code tran_id} order; never {@code null}
     */
    @Transactional(readOnly = true)
    public Page<Transaction> listTransactions(Pageable pageable) {
        return transactionRepository.findAll(withTranIdAscending(pageable));
    }

    // ------------------------------------------------------------------------
    // B. Transaction VIEW (COTRN01C, CT01)
    // ------------------------------------------------------------------------

    /**
     * Reads a single transaction by its 16-character id &mdash; the re-platform of
     * {@code COTRN01C} paragraph {@code PROCESS-ENTER-KEY} plus {@code READ-TRANSACT-FILE}.
     *
     * <p>Evaluation order is preserved: the COBOL first rejects an empty
     * {@code TRNIDINI} with {@code "Tran ID can NOT be empty..."}, then performs the keyed
     * read whose {@code NOTFND} branch sets {@code "Transaction ID NOT found..."}. The
     * key match is exact (the VSAM {@code READ} used the entered value as the record key),
     * so a caller must pass the full 16-character id; a shorter value simply does not
     * match and is reported as not found.</p>
     *
     * @param tranId the 16-character transaction id
     * @return the matching {@link Transaction}
     * @throws TransactionValidationException if {@code tranId} is {@code null} or blank
     *                                        ({@code "Tran ID can NOT be empty..."})
     * @throws RecordNotFoundException        if no transaction has that id
     *                                        ({@code "Transaction ID NOT found..."})
     */
    @Transactional(readOnly = true)
    public Transaction viewTransaction(String tranId) {
        if (tranId == null || tranId.isBlank()) {
            throw new TransactionValidationException("Tran ID can NOT be empty...");
        }
        return transactionRepository.findById(tranId)
                .orElseThrow(() -> new RecordNotFoundException("Transaction ID NOT found..."));
    }

    // ------------------------------------------------------------------------
    // C. Transaction ADD (COTRN02C, CT02)
    // ------------------------------------------------------------------------

    /**
     * Adds a new transaction &mdash; the re-platform of {@code COTRN02C} paragraphs
     * {@code VALIDATE-INPUT-KEY-FIELDS}, {@code VALIDATE-INPUT-DATA-FIELDS} and
     * {@code ADD-TRANSACTION}, executed in that order.
     *
     * <p>This method models the confirmed add (the COBOL {@code CONFIRMI = 'Y'} path);
     * the "confirm to add" prompt and screen re-display are web-layer concerns and are
     * not represented here. All input edits short-circuit on the first failure with the
     * exact legacy message, exactly as the COBOL {@code SEND-...-SCREEN}/{@code RETURN}
     * sequence did (AAP &sect;0.9.2).</p>
     *
     * <p>The whole method is a single read-write transaction so that the max-id lookup
     * and the insert are atomic; see the class-level concurrency note.</p>
     *
     * @param command the raw submitted fields; must not be {@code null}
     * @return the persisted {@link Transaction}, including its generated 16-character id
     * @throws TransactionValidationException if any key-field or data-field edit fails
     *                                        (carries the exact legacy message)
     * @throws RecordNotFoundException        if the supplied account id or card number has
     *                                        no cross-reference row
     * @throws DuplicateKeyException          if the generated id collides with an existing
     *                                        row ({@code "Tran ID already exist..."})
     */
    @Transactional
    public Transaction addTransaction(AddTransactionCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // COTRN02C PROCESS-ENTER-KEY: VALIDATE-INPUT-KEY-FIELDS then
        // VALIDATE-INPUT-DATA-FIELDS, before ADD-TRANSACTION.
        String resolvedCardNumber = validateKeyFieldsAndResolveCard(command);
        BigDecimal amount = validateDataFields(command);

        return persistNewTransaction(command, resolvedCardNumber, amount);
    }

    /**
     * Reproduces {@code VALIDATE-INPUT-KEY-FIELDS}: the operator supplies <em>either</em>
     * an account id <em>or</em> a card number, with account id taking precedence (the
     * COBOL {@code EVALUATE TRUE} tests the account field first). The supplied key is
     * validated numeric and then resolved through the card cross-reference, and the
     * resolved 16-character card number is returned for the new transaction.
     *
     * @param command the submitted fields
     * @return the resolved 16-character card number for the transaction
     * @throws TransactionValidationException if neither key is entered, or the entered key
     *                                        is non-numeric
     * @throws RecordNotFoundException        if the cross-reference has no matching row
     */
    private String validateKeyFieldsAndResolveCard(AddTransactionCommand command) {
        String accountId = command.accountId();
        String cardNumber = command.cardNumber();

        // WHEN account id entered: resolve card via the account alternate index (CXACAIX).
        if (accountId != null && !accountId.isBlank()) {
            if (!isNumeric(accountId)) {
                throw new TransactionValidationException("Account ID must be Numeric...");
            }
            long acctId = Long.parseLong(accountId);
            CardXref xref = cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(acctId)
                    .orElseThrow(() -> new RecordNotFoundException("Account ID NOT found..."));
            return xref.getXrefCardNum();
        }

        // WHEN card number entered: resolve/validate via the card cross-reference (CCXREF).
        if (cardNumber != null && !cardNumber.isBlank()) {
            if (!isNumeric(cardNumber)) {
                throw new TransactionValidationException("Card Number must be Numeric...");
            }
            String normalizedCardNumber = normalizeCardNumber(cardNumber);
            cardXrefRepository.findById(normalizedCardNumber)
                    .orElseThrow(() -> new RecordNotFoundException("Card Number NOT found..."));
            return normalizedCardNumber;
        }

        // WHEN OTHER: neither key supplied.
        throw new TransactionValidationException("Account or Card Number must be entered...");
    }

    /**
     * Reproduces {@code VALIDATE-INPUT-DATA-FIELDS} in the exact COBOL order, returning
     * the parsed transaction amount. The checks, each short-circuiting on the first
     * failure, are:
     * <ol>
     *   <li>empty checks for every data field (Type CD, Category CD, Source, Description,
     *       Amount, Orig Date, Proc Date, Merchant ID, Merchant Name, Merchant City,
     *       Merchant Zip), each yielding {@code "<field> can NOT be empty..."};</li>
     *   <li>Type CD and Category CD must be numeric;</li>
     *   <li>Amount must match the signed fixed-point mask {@code -99999999.99};</li>
     *   <li>Orig Date then Proc Date must be structurally {@code YYYY-MM-DD};</li>
     *   <li>the amount is parsed ({@code FUNCTION NUMVAL-C});</li>
     *   <li>Orig Date then Proc Date must be real calendar dates (via
     *       {@link DateValidationService}, the {@code CSUTLDTC}/{@code CEEDAYS} re-platform);</li>
     *   <li>Merchant ID must be numeric.</li>
     * </ol>
     *
     * @param command the submitted fields
     * @return the parsed amount as a {@link BigDecimal} at scale 2
     * @throws TransactionValidationException on the first failing edit (carries the exact message)
     */
    private BigDecimal validateDataFields(AddTransactionCommand command) {
        // 1) Empty checks, in COBOL order; the first empty field wins.
        requireNotEmpty(command.typeCd(), "Type CD");
        requireNotEmpty(command.categoryCd(), "Category CD");
        requireNotEmpty(command.source(), "Source");
        requireNotEmpty(command.description(), "Description");
        requireNotEmpty(command.amount(), "Amount");
        requireNotEmpty(command.origDate(), "Orig Date");
        requireNotEmpty(command.procDate(), "Proc Date");
        requireNotEmpty(command.merchantId(), "Merchant ID");
        requireNotEmpty(command.merchantName(), "Merchant Name");
        requireNotEmpty(command.merchantCity(), "Merchant City");
        requireNotEmpty(command.merchantZip(), "Merchant Zip");

        // 1.5) Field-length edits, matching the fixed BMS field widths of the
        //      COTRN02 add-transaction map (a 3270 field cannot exceed its map
        //      width, so an overlength value is a client-side contract violation).
        //      Enforcing them here rejects an overlength field with a precise
        //      validation message BEFORE the row reaches the database, so a value
        //      that would overflow a column (for example a description longer than
        //      VARCHAR(100)) is never misreported as a duplicate-key failure.
        requireMaxLength(command.typeCd(), TYPE_CD_MAX_LENGTH, "Type CD");
        requireMaxLength(command.categoryCd(), CATEGORY_CD_MAX_LENGTH, "Category CD");
        requireMaxLength(command.source(), SOURCE_MAX_LENGTH, "Source");
        requireMaxLength(command.description(), DESCRIPTION_MAX_LENGTH, "Description");
        requireMaxLength(command.merchantId(), MERCHANT_ID_MAX_LENGTH, "Merchant ID");
        requireMaxLength(command.merchantName(), MERCHANT_NAME_MAX_LENGTH, "Merchant Name");
        requireMaxLength(command.merchantCity(), MERCHANT_CITY_MAX_LENGTH, "Merchant City");
        requireMaxLength(command.merchantZip(), MERCHANT_ZIP_MAX_LENGTH, "Merchant Zip");

        // 2) Type CD / Category CD numeric edits.
        if (!isNumeric(command.typeCd())) {
            throw new TransactionValidationException("Type CD must be Numeric...");
        }
        if (!isNumeric(command.categoryCd())) {
            throw new TransactionValidationException("Category CD must be Numeric...");
        }

        // 3) Amount format edit: -99999999.99.
        if (!isValidAmountFormat(command.amount())) {
            throw new TransactionValidationException("Amount should be in format -99999999.99");
        }

        // 4) Orig Date / Proc Date structural (YYYY-MM-DD) edits.
        if (!isValidDateFormat(command.origDate())) {
            throw new TransactionValidationException("Orig Date should be in format YYYY-MM-DD");
        }
        if (!isValidDateFormat(command.procDate())) {
            throw new TransactionValidationException("Proc Date should be in format YYYY-MM-DD");
        }

        // 5) Parse the amount (COBOL COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI)).
        BigDecimal amount = parseAmount(command.amount());

        // 6) Orig Date / Proc Date calendar-validity edits (CSUTLDTC). A date that passed
        //    the structural edit but is not a real calendar date is rejected here; this is
        //    equivalent to the COBOL "severity != '0000' AND message != '2513'" rule,
        //    because the structural edit guarantees a fully specified (non-2513) value.
        if (!dateValidationService.isValid(command.origDate(), DATE_FORMAT_YYYY_MM_DD)) {
            throw new TransactionValidationException("Orig Date - Not a valid date...");
        }
        if (!dateValidationService.isValid(command.procDate(), DATE_FORMAT_YYYY_MM_DD)) {
            throw new TransactionValidationException("Proc Date - Not a valid date...");
        }

        // 7) Merchant ID numeric edit.
        if (!isNumeric(command.merchantId())) {
            throw new TransactionValidationException("Merchant ID must be Numeric...");
        }

        return amount;
    }

    /**
     * Reproduces {@code ADD-TRANSACTION}: generates the next transaction id from the
     * current maximum ({@link TransactionRepository#findMaxTranId()} &rarr;
     * {@link IdGenerator#nextTransactionId(String)}), builds the {@link Transaction}
     * record from the (already validated) command fields and the resolved card number,
     * and inserts it.
     *
     * <p>The insert is flushed eagerly ({@code saveAndFlush}) so that a unique-key
     * collision surfaces here as a {@link DataIntegrityViolationException} rather than
     * deferring to transaction commit. Because {@link Transaction} implements
     * {@link org.springframework.data.domain.Persistable} (a freshly-built record
     * reports {@code isNew() == true}), the save performs a genuine {@code INSERT};
     * a colliding {@code MAX(tran_id)+1} therefore fails the {@code pk_transaction}
     * unique constraint instead of merging into (silently overwriting) the racing
     * writer's row.</p>
     *
     * <p>The failure is classified narrowly: only a genuine duplicate {@code tran_id}
     * primary key (SQLState {@code 23505} on {@code pk_transaction}, detected by
     * {@link DuplicateKeyException#isTransactionIdCollision(DataIntegrityViolationException)})
     * is translated to the {@link DuplicateKeyException} the COBOL {@code WRITE}
     * {@code DUPKEY}/{@code DUPREC} branch produced. Any other integrity violation is
     * re-thrown unchanged so it is not mislabelled as a duplicate key.</p>
     *
     * @param command    the validated submitted fields
     * @param cardNumber the resolved 16-character card number
     * @param amount     the parsed amount at scale 2
     * @return the persisted {@link Transaction}
     * @throws DuplicateKeyException if the generated id collides with an existing row
     * @throws DataIntegrityViolationException if a different integrity constraint is violated
     */
    private Transaction persistNewTransaction(AddTransactionCommand command,
                                              String cardNumber,
                                              BigDecimal amount) {
        // Reverse-browse-from-HIGH-VALUES parity: current maximum id, then +1.
        String nextTranId = IdGenerator.nextTransactionId(
                transactionRepository.findMaxTranId().orElse(null));

        // Field order matches the TRAN-RECORD copybook / Transaction constructor. The
        // origination and processing "timestamps" are the entered dates, stored verbatim
        // (COTRN02C MOVE TORIGDTI/TPROCDTI -> TRAN-ORIG-TS/TRAN-PROC-TS); no time-of-day
        // is synthesized (see the class-level timestamp note).
        Transaction transaction = new Transaction(
                nextTranId,
                command.typeCd(),
                Integer.valueOf(command.categoryCd()),
                command.source(),
                command.description(),
                amount,
                Long.valueOf(command.merchantId()),
                command.merchantName(),
                command.merchantCity(),
                command.merchantZip(),
                cardNumber,
                command.origDate(),
                command.procDate());

        try {
            return transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException ex) {
            // Only a genuine duplicate tran_id primary key maps to the COBOL
            // WRITE...DUPKEY outcome. Because Transaction implements Persistable
            // (isNew() == true forces an INSERT), a colliding id reliably fails the
            // pk_transaction unique constraint here instead of silently merging.
            if (DuplicateKeyException.isTransactionIdCollision(ex)) {
                throw new DuplicateKeyException("Tran ID already exist...", ex);
            }
            // Any other integrity violation (foreign key, not-null, check, or a
            // different unique constraint) is NOT a duplicate transaction id; re-throw
            // it unchanged so it is not mislabelled as a duplicate-key failure.
            throw ex;
        }
    }

    // ------------------------------------------------------------------------
    // Internal edit helpers (COBOL IS NUMERIC / reference-modification edits)
    // ------------------------------------------------------------------------

    /**
     * Throws {@link TransactionValidationException} with the message
     * {@code "<label> can NOT be empty..."} when {@code value} is {@code null} or blank,
     * reproducing the COBOL {@code = SPACES OR LOW-VALUES} empty test.
     *
     * @param value the field value to test
     * @param label the exact field label used in the COBOL message
     */
    private static void requireNotEmpty(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new TransactionValidationException(label + " can NOT be empty...");
        }
    }

    /**
     * Throws {@link TransactionValidationException} with the message
     * {@code "<label> must not exceed <maxLength> characters..."} when {@code value}
     * is longer than {@code maxLength}. This enforces the fixed BMS field widths of
     * the {@code COTRN02} add-transaction map: because a 3270 field cannot exceed its
     * map width, an overlength value is a client contract violation and is rejected
     * as a field edit rather than being allowed to reach (and overflow) the database
     * column, where it would otherwise surface as an ambiguous data-integrity error.
     *
     * <p>A {@code null} value is treated as length zero and passes; emptiness is the
     * responsibility of {@link #requireNotEmpty(String, String)}, which always runs
     * first.</p>
     *
     * @param value     the field value to test (may be {@code null})
     * @param maxLength the inclusive maximum number of characters permitted
     * @param label     the exact field label used in the message
     */
    private static void requireMaxLength(String value, int maxLength, String label) {
        if (value != null && value.length() > maxLength) {
            throw new TransactionValidationException(
                    label + " must not exceed " + maxLength + " characters...");
        }
    }

    /**
     * Reproduces the COBOL {@code IS NUMERIC} class test for a display field: the value is
     * numeric only when it is non-empty and every character is an ASCII digit. A value
     * containing a space, sign or decimal point is therefore not numeric, exactly as on
     * the mainframe.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} if {@code value} is a non-empty run of ASCII digits
     */
    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the COBOL amount edit on {@code TRNAMTI PIC X(12)}
     * ({@code (1:1) = '-'/'+'}, {@code (2:8)} numeric, {@code (10:1) = '.'},
     * {@code (11:2)} numeric): the value must be exactly a leading sign, eight integer
     * digits, a decimal point, and two fraction digits ({@code -99999999.99}).
     *
     * @param amount the raw amount field; may be {@code null}
     * @return {@code true} if {@code amount} matches the signed fixed-point mask
     */
    private static boolean isValidAmountFormat(String amount) {
        if (amount == null || amount.length() != AMOUNT_LENGTH) {
            return false;
        }
        char sign = amount.charAt(0);
        if (sign != '-' && sign != '+') {
            return false;
        }
        // Eight integer digits at positions 2-9 (COBOL (2:8)).
        if (!allDigits(amount, 1, 9)) {
            return false;
        }
        // Decimal point at position 10 (COBOL (10:1)).
        if (amount.charAt(9) != '.') {
            return false;
        }
        // Two fraction digits at positions 11-12 (COBOL (11:2)).
        return allDigits(amount, 10, 12);
    }

    /**
     * Reproduces the COBOL date edit on a {@code PIC X(10)} field
     * ({@code (1:4)} numeric, {@code (5:1) = '-'}, {@code (6:2)} numeric,
     * {@code (8:1) = '-'}, {@code (9:2)} numeric): the value must be structurally
     * {@code YYYY-MM-DD}. Calendar validity is checked separately by
     * {@link DateValidationService}.
     *
     * @param date the raw date field; may be {@code null}
     * @return {@code true} if {@code date} is structurally {@code YYYY-MM-DD}
     */
    private static boolean isValidDateFormat(String date) {
        if (date == null || date.length() != DATE_LENGTH) {
            return false;
        }
        if (!allDigits(date, 0, 4)) {
            return false;
        }
        if (date.charAt(4) != '-') {
            return false;
        }
        if (!allDigits(date, 5, 7)) {
            return false;
        }
        if (date.charAt(7) != '-') {
            return false;
        }
        return allDigits(date, 8, 10);
    }

    /**
     * Returns {@code true} when every character in {@code value} over the half-open range
     * {@code [fromInclusive, toExclusive)} is an ASCII digit. The bounds are always within
     * the string length at the call sites (each caller has already checked the exact field
     * width), so no bounds guard is required here.
     *
     * @param value         the string to scan
     * @param fromInclusive the inclusive start index
     * @param toExclusive   the exclusive end index
     * @return {@code true} if the range contains only ASCII digits
     */
    private static boolean allDigits(String value, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses the (already format-validated) amount into a {@link BigDecimal} at scale 2,
     * the Java analog of {@code COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI)}.
     * {@link BigDecimal} preserves the packed-decimal value exactly; no binary
     * floating-point type is used. {@link RoundingMode#HALF_UP} is specified for
     * completeness &mdash; the input always carries exactly two fraction digits, so no
     * rounding actually occurs.
     *
     * @param amount the validated amount string ({@code [+-]99999999.99})
     * @return the amount as a {@link BigDecimal} at scale 2
     */
    private static BigDecimal parseAmount(String amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Normalizes a numeric card number to the 16-digit, zero-padded form used as the
     * {@code card_xref} primary key, reproducing the COBOL move of the entered value
     * through {@code WS-CARD-NUM-N PIC 9(16)}.
     *
     * @param cardNumber a numeric card-number string (already checked with {@link #isNumeric(String)})
     * @return the card number left-padded with zeros to 16 digits
     */
    private static String normalizeCardNumber(String cardNumber) {
        return String.format(CARD_NUMBER_FORMAT, Long.parseLong(cardNumber));
    }

    /**
     * Builds a {@link Pageable} that keeps the caller's page number and size but forces
     * ascending {@code tran_id} ordering, reproducing the {@code COTRN00C} primary-key
     * browse. A {@code null} or unpaged request is turned into a single, key-ordered page
     * over all rows.
     *
     * @param pageable the caller's paging request; may be {@code null} or unpaged
     * @return a {@link Pageable} sorted ascending by {@code tranId}
     */
    private static Pageable withTranIdAscending(Pageable pageable) {
        Sort byTranId = Sort.by(Sort.Direction.ASC, "tranId");
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, Integer.MAX_VALUE, byTranId);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), byTranId);
    }

    // ------------------------------------------------------------------------
    // Nested service-layer types
    // ------------------------------------------------------------------------

    /**
     * Immutable service-layer input for {@link TransactionService#addTransaction(AddTransactionCommand)}.
     *
     * <p>This is a deliberately DTO-free carrier of the <em>raw</em> submitted fields, one
     * per {@code COTRN02C} screen input ({@code COTRN2AI}). Every component is a
     * {@code String} exactly as the field arrives from the client, so the service can
     * reproduce the COBOL edits (numeric class test, positional format edits) on the
     * original text. Type conversion (category code to {@code Integer}, merchant id to
     * {@code Long}, amount to {@link BigDecimal}) happens inside the service only after
     * the corresponding edit has passed. A blank/{@code null} {@code accountId} with a
     * populated {@code cardNumber} (or vice-versa) selects the key path; supplying the
     * account id takes precedence.</p>
     *
     * @param accountId    account id ({@code ACTIDINI}); mutually exclusive with {@code cardNumber}
     * @param cardNumber   card number ({@code CARDNINI}); mutually exclusive with {@code accountId}
     * @param typeCd       transaction type code ({@code TTYPCDI}); numeric
     * @param categoryCd   transaction category code ({@code TCATCDI}); numeric
     * @param source       transaction source ({@code TRNSRCI})
     * @param description  transaction description ({@code TDESCI})
     * @param amount       signed amount text ({@code TRNAMTI}); {@code -99999999.99}
     * @param origDate     origination date ({@code TORIGDTI}); {@code YYYY-MM-DD}
     * @param procDate     processing date ({@code TPROCDTI}); {@code YYYY-MM-DD}
     * @param merchantId   merchant id ({@code MIDI}); numeric
     * @param merchantName merchant name ({@code MNAMEI})
     * @param merchantCity merchant city ({@code MCITYI})
     * @param merchantZip  merchant postal code ({@code MZIPI})
     */
    public record AddTransactionCommand(
            String accountId,
            String cardNumber,
            String typeCd,
            String categoryCd,
            String source,
            String description,
            String amount,
            String origDate,
            String procDate,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip) {
    }

    /**
     * Unchecked exception raised when a transaction-add or transaction-view input edit
     * fails. It carries the exact caller-visible message the COBOL program placed in
     * {@code WS-MESSAGE} before re-displaying the screen (for example
     * {@code "Amount should be in format -99999999.99"}), so the online-layer handler can
     * surface that text unchanged. Distinct from
     * {@link com.aws.carddemo.exception.RecordNotFoundException} (a keyed lookup that found
     * nothing) and {@link com.aws.carddemo.exception.DuplicateKeyException} (a unique-key
     * collision).
     */
    public static final class TransactionValidationException extends RuntimeException {

        /**
         * Serialization version identifier. Declared explicitly because
         * {@link RuntimeException} is {@link java.io.Serializable}; its presence keeps the
         * warning-free build ({@code -Xlint:all} with {@code failOnWarning}) satisfied.
         */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception with the exact legacy edit message.
         *
         * @param message the caller-visible message (a COBOL {@code WS-MESSAGE} literal)
         */
        public TransactionValidationException(String message) {
            super(message);
        }
    }
}

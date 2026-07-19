/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.online;

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COTRN02Form;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolDecimal;
import com.aws.carddemo.util.DateConversionService;
import com.aws.carddemo.util.DateConversionService.DateValidationResult;
import com.aws.carddemo.util.constants.Messages;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Online transaction-add service.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COTRN02C.cbl} (CICS COBOL program
 * {@code COTRN02C}, transaction id {@code CT02}). This class is the faithful Java
 * migration of the online "Add Transaction" program, preserving the COBOL control
 * flow one-for-one: each business {@code PARAGRAPH} becomes exactly one method
 * (AAP &sect;0.3.3, service layer; AAP &sect;0.4.1, online-programs table
 * {@code COTRN02C.cbl -> TransactionAddService + TransactionController (add)}).</p>
 *
 * <h2>Write archetype and the presentation split</h2>
 * <p>{@code COTRN02C} is the <em>write</em> archetype of the AWS CardDemo online
 * tier: it performs heavy field validation followed by a keyed {@code WRITE} of a
 * new {@code TRAN-RECORD}. Its paragraphs divide into business logic (kept here)
 * and 3270/BMS presentation (owned by the paired {@code TransactionController} and
 * the Thymeleaf view). This service therefore implements only the business
 * paragraphs and deliberately contains no {@code SEND}/{@code RECEIVE}/header
 * logic:</p>
 * <table border="1">
 *   <caption>COTRN02C paragraph &rarr; Java mapping</caption>
 *   <tr><th>COBOL paragraph</th><th>Owner</th><th>Java member</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>service</td><td>{@link #mainEntry(COTRN02Form, PfKey)}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>service</td><td>{@link #processEnterKey(COTRN02Form)}</td></tr>
 *   <tr><td>{@code VALIDATE-INPUT-KEY-FIELDS}</td><td>service</td>
 *       <td>{@link #validateInputKeyFields(COTRN02Form)}</td></tr>
 *   <tr><td>{@code VALIDATE-INPUT-DATA-FIELDS}</td><td>service</td>
 *       <td>{@link #validateInputDataFields(COTRN02Form)}</td></tr>
 *   <tr><td>{@code READ-CXACAIX-FILE}</td><td>service</td><td>{@link #readCxacaixFile(long)}</td></tr>
 *   <tr><td>{@code READ-CCXREF-FILE}</td><td>service</td><td>{@link #readCcxrefFile(String)}</td></tr>
 *   <tr><td>{@code STARTBR-TRANSACT-FILE} / {@code READPREV-TRANSACT-FILE} /
 *       {@code ENDBR-TRANSACT-FILE}</td><td>service</td>
 *       <td>{@link #readLastTransaction()} (collapsed max-key browse)</td></tr>
 *   <tr><td>{@code ADD-TRANSACTION}</td><td>service</td><td>{@link #addTransaction(COTRN02Form)}</td></tr>
 *   <tr><td>{@code WRITE-TRANSACT-FILE}</td><td>service</td>
 *       <td>{@link #writeTransactFile(COTRN02Form, Transaction)}</td></tr>
 *   <tr><td>{@code COPY-LAST-TRAN-DATA}</td><td>service</td><td>{@link #copyLastTranData(COTRN02Form)}</td></tr>
 *   <tr><td>{@code CLEAR-CURRENT-SCREEN}</td><td>service</td><td>{@link #clearCurrentScreen(COTRN02Form)}</td></tr>
 *   <tr><td>{@code INITIALIZE-ALL-FIELDS}</td><td>service</td><td>{@link #initializeAllFields(COTRN02Form)}</td></tr>
 *   <tr><td>{@code RETURN-TO-PREV-SCREEN}</td><td>service</td><td>{@link #beginReturnToPrevScreen()}</td></tr>
 *   <tr><td>{@code SEND-TRNADD-SCREEN}</td><td>controller</td><td>&mdash; (view render)</td></tr>
 *   <tr><td>{@code RECEIVE-TRNADD-SCREEN}</td><td>controller</td><td>&mdash; (form binding)</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>controller</td><td>&mdash; (header fields)</td></tr>
 * </table>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>{@code COTRN02C} is pseudo-conversational: every interaction ends with
 * {@code EXEC CICS RETURN TRANSID(CT02) COMMAREA(...)}, and re-entry is driven by
 * the {@code COMMAREA} plus {@code EIBCALEN} and {@code EIBAID}. That state carrier
 * is the session-scoped {@link CardDemoContext}. {@code EIBCALEN = 0} maps to
 * {@link CardDemoContext#isNew()}; the within-program enter/re-enter flag
 * {@code CDEMO-PGM-CONTEXT} maps to {@link CardDemoContext#isProgramEnter()} /
 * {@link CardDemoContext#markReenter()}. The program-specific COMMAREA extension
 * {@code CDEMO-CT02-TRN-SELECTED} (moved into the card-number input on first entry,
 * COBOL lines 124-127) has no field on the shared {@code COCOM01Y}-derived context;
 * it is mapped to the currently-selected card {@link CardDemoContext#getCardNum()}
 * (the value the card-list/detail screens hand off), a documented parity decision
 * (AAP &sect;0.6.8, &sect;0.6.10).</p>
 *
 * <h2>Result contract (CICS RETURN = early termination)</h2>
 * <p>In the COBOL, every validation error runs {@code PERFORM SEND-TRNADD-SCREEN},
 * whose final statement is {@code EXEC CICS RETURN} - so the <em>first</em> failing
 * check terminates the task and no later check runs. That control flow is preserved
 * exactly: the internal validation paragraphs
 * ({@link #validateInputKeyFields(COTRN02Form)} /
 * {@link #validateInputDataFields(COTRN02Form)}) return a <em>nullable</em>
 * {@link TransactionAddResult} - {@code null} meaning "no error, continue" and a
 * non-null value meaning "stop and send this screen". The public methods always
 * return a non-null {@link TransactionAddResult} describing whether the controller
 * should re-display the screen ({@link ScreenAction#SHOW_SCREEN}, the COBOL
 * {@code SEND-TRNADD-SCREEN}) or redirect ({@link ScreenAction#REDIRECT}, the COBOL
 * {@code XCTL}), together with the message, its {@link MessageSeverity}
 * ({@link MessageSeverity#ERROR} = the red {@code WS-ERR-FLG} path,
 * {@link MessageSeverity#INFORMATION} = the green {@code DFHGREEN} success path),
 * and the cursor field (the COBOL {@code MOVE -1 TO xxxxL}).</p>
 *
 * <h2>Decimal fidelity (AAP &sect;0.6.1)</h2>
 * <p>The transaction amount is a fixed-scale {@code S9(9)V99} value. It is parsed
 * and scaled through {@link CobolDecimal} ({@link BigDecimal}, scale 2,
 * {@link java.math.RoundingMode#DOWN}) - the COBOL statements carry no
 * {@code ROUNDED} clause, so truncation is the correct mode. No floating-point type
 * is used for money.</p>
 *
 * <h2>Alternate-index access (AAP &sect;0.6.2)</h2>
 * <p>The CICS {@code READ}s on the {@code CXACAIX} (account) and {@code CCXREF}
 * (card) cross-reference views become the Spring Data derived queries
 * {@link CardXrefRepository#findByXrefAcctId(Long)} and
 * {@link CardXrefRepository#findByXrefCardNum(String)}. The next transaction id is
 * derived by the sanctioned max-key equivalent of the COBOL descending browse
 * ({@code STARTBR} on {@code HIGH-VALUES} + {@code READPREV}) - see
 * {@link #readLastTransaction()}.</p>
 *
 * <h2>Exception mapping (AAP &sect;0.6.5)</h2>
 * <p>{@code NOTFND} on a cross-reference {@code READ} becomes a
 * {@link RecordNotFoundException} surfaced on-screen as the corresponding
 * "... NOT found..." message; a duplicate key on the {@code WRITE}
 * ({@code DFHRESP(DUPKEY)}/{@code DUPREC}) becomes a {@link DuplicateKeyException}
 * surfaced as "Tran ID already exist...".</p>
 *
 * <h2>Collaborators and dependencies</h2>
 * <p>Per the migration plan this service constructor-injects the session-scoped
 * {@link CardDemoContext} (the {@code COMMAREA} replacement) and the
 * {@link TransactionRepository}, {@link CardXrefRepository} and
 * {@link AccountRepository} (the migrated {@code TRANSACT}, {@code CCXREF}/
 * {@code CXACAIX} and {@code ACCTDAT} file handles). It also uses the shared
 * {@link DateConversionService} (the {@code CALL 'CSUTLDTC'} date validator), the
 * {@link PfKey} enumeration (the canonical {@code EIBAID} representation) and the
 * {@link Messages} constants holder, exactly as directed by the file
 * specification. {@link AccountRepository} mirrors the {@code WS-ACCTDAT-FILE}
 * DDNAME that {@code COTRN02C} declares in working storage but never exercises (the
 * program performs no {@code ACCTDAT} I/O); it is injected for structural parity
 * and to satisfy the declared dependency set.</p>
 *
 * <p>Because {@link #addTransaction(COTRN02Form)} is {@link Transactional} and is
 * invoked from another method of the same bean, it is called through the Spring
 * proxy obtained from the injected self {@link ObjectProvider} so the transaction
 * advice is actually applied (an internal {@code this.addTransaction(...)} call
 * would bypass the proxy).</p>
 *
 * <p>Plain Java, explicit accessors, no Lombok, no emoji; compiles warning-free
 * under {@code --release 25} with {@code -Xlint:all}.</p>
 *
 * @see CardDemoContext
 * @see COTRN02Form
 * @see Transaction
 * @see CardXref
 */
@Service
public class TransactionAddService {

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COTRN02C'} - this program's name. */
    private static final String PROGRAM_NAME = "COTRN02C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CT02'} - this program's transaction id. */
    private static final String TRANSACTION_ID = "CT02";

    /** Sign-on program name ({@code COSGN00C}); COBOL {@code XCTL} target on first entry and the PF3 default. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Main-menu program name ({@code COMEN01C}); COBOL PF3 target when {@code CDEMO-FROM-PROGRAM} is blank. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** COBOL {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} - the {@code CSUTLDTC} picture mask. */
    private static final String DATE_FORMAT_MASK = "YYYY-MM-DD";

    /**
     * The {@code CEEDAYS} feedback message number {@code 2513} ({@code FC-UNSUPP-RANGE}: a valid but
     * pre-1582 date). COBOL treats this specific code as acceptable
     * ({@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'}), so it is excluded from the invalid-date test.
     */
    private static final int MSG_NUM_UNSUPP_RANGE = 2513;

    // -- Message literals (byte-exact from legacy/cbl/COTRN02C.cbl) ------------------------------

    /** COBOL line 178: confirm-required prompt (CONFIRM = N/n/spaces/low-values). */
    private static final String MSG_CONFIRM_PROMPT = "Confirm to add this transaction...";

    /** COBOL line 184: invalid CONFIRM value. */
    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

    /** COBOL line 199: account id entered but not numeric. */
    private static final String MSG_ACCT_NOT_NUMERIC = "Account ID must be Numeric...";

    /** COBOL line 213: card number entered but not numeric. */
    private static final String MSG_CARD_NOT_NUMERIC = "Card Number must be Numeric...";

    /** COBOL line 226: neither account id nor card number entered. */
    private static final String MSG_ACCT_OR_CARD_REQUIRED = "Account or Card Number must be entered...";

    /** COBOL line 254: transaction type code empty. */
    private static final String MSG_TYPE_EMPTY = "Type CD can NOT be empty...";

    /** COBOL line 260: transaction category code empty. */
    private static final String MSG_CAT_EMPTY = "Category CD can NOT be empty...";

    /** COBOL line 266: transaction source empty. */
    private static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";

    /** COBOL line 272: description empty. */
    private static final String MSG_DESC_EMPTY = "Description can NOT be empty...";

    /** COBOL line 278: amount empty. */
    private static final String MSG_AMT_EMPTY = "Amount can NOT be empty...";

    /** COBOL line 284: original date empty. */
    private static final String MSG_ORIG_EMPTY = "Orig Date can NOT be empty...";

    /** COBOL line 290: processing date empty. */
    private static final String MSG_PROC_EMPTY = "Proc Date can NOT be empty...";

    /** COBOL line 296: merchant id empty. */
    private static final String MSG_MID_EMPTY = "Merchant ID can NOT be empty...";

    /** COBOL line 302: merchant name empty. */
    private static final String MSG_MNAME_EMPTY = "Merchant Name can NOT be empty...";

    /** COBOL line 308: merchant city empty. */
    private static final String MSG_MCITY_EMPTY = "Merchant City can NOT be empty...";

    /** COBOL line 314: merchant zip empty. */
    private static final String MSG_MZIP_EMPTY = "Merchant Zip can NOT be empty...";

    /** COBOL line 325: transaction type code not numeric. */
    private static final String MSG_TYPE_NOT_NUMERIC = "Type CD must be Numeric...";

    /** COBOL line 331: transaction category code not numeric. */
    private static final String MSG_CAT_NOT_NUMERIC = "Category CD must be Numeric...";

    /** COBOL line 345: amount not in the fixed {@code -99999999.99} layout. */
    private static final String MSG_AMT_FORMAT = "Amount should be in format -99999999.99";

    /** COBOL line 360: original date not in {@code YYYY-MM-DD} layout. */
    private static final String MSG_ORIG_FORMAT = "Orig Date should be in format YYYY-MM-DD";

    /** COBOL line 375: processing date not in {@code YYYY-MM-DD} layout. */
    private static final String MSG_PROC_FORMAT = "Proc Date should be in format YYYY-MM-DD";

    /** COBOL line 401: original date well-formed but not a valid calendar date ({@code CSUTLDTC}). */
    private static final String MSG_ORIG_INVALID = "Orig Date - Not a valid date...";

    /** COBOL line 421: processing date well-formed but not a valid calendar date ({@code CSUTLDTC}). */
    private static final String MSG_PROC_INVALID = "Proc Date - Not a valid date...";

    /** COBOL line 432: merchant id not numeric. */
    private static final String MSG_MID_NOT_NUMERIC = "Merchant ID must be Numeric...";

    /** COBOL line 593: account id not found in the {@code CXACAIX} cross-reference. */
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

    /** COBOL line 600: unexpected error reading the {@code CXACAIX} cross-reference. */
    private static final String MSG_ACCT_LOOKUP_ERR = "Unable to lookup Acct in XREF AIX file...";

    /** COBOL line 626: card number not found in the {@code CCXREF} cross-reference. */
    private static final String MSG_CARD_NOT_FOUND = "Card Number NOT found...";

    /** COBOL line 633: unexpected error reading the {@code CCXREF} cross-reference. */
    private static final String MSG_CARD_LOOKUP_ERR = "Unable to lookup Card # in XREF file...";

    /** COBOL lines 664/693: unexpected error browsing the {@code TRANSACT} file. */
    private static final String MSG_TRAN_LOOKUP_ERR = "Unable to lookup Transaction...";

    /** COBOL line 738: duplicate key on the {@code TRANSACT} {@code WRITE}. */
    private static final String MSG_DUPE = "Tran ID already exist...";

    /** COBOL line 745: unexpected error on the {@code TRANSACT} {@code WRITE}. */
    private static final String MSG_ADD_ERR = "Unable to Add Transaction...";

    /**
     * Leading fragment of the COBOL {@code STRING}-built success message (COBOL line 719), including
     * its single trailing space. Concatenated with {@link #SUCCESS_PART2} this reproduces the exact
     * double space that appears between "successfully." and "Your" in the original.
     */
    private static final String SUCCESS_PART1 = "Transaction added successfully. ";

    /** Middle fragment of the COBOL success message (COBOL line 721): a leading and trailing space. */
    private static final String SUCCESS_PART2 = " Your Tran ID is ";

    /** Trailing fragment of the COBOL success message (COBOL line 723). */
    private static final String SUCCESS_SUFFIX = ".";

    // -- Cursor field names (BMS input field ids; the COBOL MOVE -1 TO xxxxL target) --------------

    /** No cursor override (the COBOL branch performs no {@code MOVE -1 TO xxxxL}). */
    private static final String CURSOR_NONE = "";

    /** Account-id input cursor ({@code ACTIDINL}). */
    private static final String CURSOR_ACTIDIN = "actidin";

    /** Card-number input cursor ({@code CARDNINL}). */
    private static final String CURSOR_CARDNIN = "cardnin";

    /** Type-code input cursor ({@code TTYPCDL}). */
    private static final String CURSOR_TTYPCD = "ttypcd";

    /** Category-code input cursor ({@code TCATCDL}). */
    private static final String CURSOR_TCATCD = "tcatcd";

    /** Source input cursor ({@code TRNSRCL}). */
    private static final String CURSOR_TRNSRC = "trnsrc";

    /** Description input cursor ({@code TDESCL}). */
    private static final String CURSOR_TDESC = "tdesc";

    /** Amount input cursor ({@code TRNAMTL}). */
    private static final String CURSOR_TRNAMT = "trnamt";

    /** Original-date input cursor ({@code TORIGDTL}). */
    private static final String CURSOR_TORIGDT = "torigdt";

    /** Processing-date input cursor ({@code TPROCDTL}). */
    private static final String CURSOR_TPROCDT = "tprocdt";

    /** Merchant-id input cursor ({@code MIDL}). */
    private static final String CURSOR_MID = "mid";

    /** Merchant-name input cursor ({@code MNAMEL}). */
    private static final String CURSOR_MNAME = "mname";

    /** Merchant-city input cursor ({@code MCITYL}). */
    private static final String CURSOR_MCITY = "mcity";

    /** Merchant-zip input cursor ({@code MZIPL}). */
    private static final String CURSOR_MZIP = "mzip";

    /** Confirm input cursor ({@code CONFIRML}). */
    private static final String CURSOR_CONFIRM = "confirm";

    /** Length of the fixed {@code TRNAMTI PIC X(12)} amount field ({@code -99999999.99}). */
    private static final int AMOUNT_FIELD_LENGTH = 12;

    /** Length of the fixed {@code TORIGDTI}/{@code TPROCDTI PIC X(10)} date field ({@code YYYY-MM-DD}). */
    private static final int DATE_FIELD_LENGTH = 10;

    /** Fixed transaction-id width ({@code TRAN-ID PIC X(16)}, populated as {@code 9(16)} zero-padded). */
    private static final int TRAN_ID_WIDTH = 16;

    /** Fixed account-id width ({@code XREF-ACCT-ID}/{@code WS-ACCT-ID-N PIC 9(11)}). */
    private static final int ACCT_ID_WIDTH = 11;

    /** Fixed card-number width ({@code XREF-CARD-NUM}/{@code WS-CARD-NUM-N PIC 9(16)}). */
    private static final int CARD_NUM_WIDTH = 16;

    /** Fixed category-code width ({@code TRAN-CAT-CD PIC 9(04)} echoed to {@code TCATCDI}). */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** Fixed merchant-id width ({@code TRAN-MERCHANT-ID PIC 9(09)} echoed to {@code MIDI}). */
    private static final int MERCHANT_ID_WIDTH = 9;

    /** Immutable {@code YYYY-MM-DD} formatter used for the date-string &harr; {@link LocalDateTime} mapping. */
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    // -- Collaborators (constructor-injected, all private final) ----------------------------------

    /**
     * Session-scoped {@code COMMAREA} replacement ({@code COPY COCOM01Y}). Holds the navigation
     * hand-off, the authenticated identity, the selected card, and the enter/re-enter flags across
     * pseudo-conversational interactions.
     */
    private final CardDemoContext context;

    /** Migrated {@code TRANSACT} KSDS ({@code COPY CVTRA05Y}); read for the max key and written on add. */
    private final TransactionRepository transactionRepository;

    /**
     * Migrated {@code CCXREF} / {@code CXACAIX} cross-reference ({@code COPY CVACT03Y}); resolves an
     * account id to its card number and vice-versa via the derived alternate-index queries.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Migrated {@code ACCTDAT} KSDS ({@code COPY CVACT01Y}). {@code COTRN02C} declares the
     * {@code WS-ACCTDAT-FILE} handle but performs no account I/O; injected for structural parity with
     * the COBOL working-storage file set.
     */
    private final AccountRepository accountRepository;

    /** Migrated {@code CSUTLDTC} date validator ({@code CALL 'CSUTLDTC'}); validates {@code YYYY-MM-DD} input. */
    private final DateConversionService dateConversionService;

    /**
     * Self-reference used to obtain the Spring proxy for {@link #addTransaction(COTRN02Form)} so its
     * {@link Transactional} advice is honored when the method is invoked from within this same bean.
     */
    private final ObjectProvider<TransactionAddService> selfProvider;

    /**
     * Creates the transaction-add service with its injected collaborators.
     *
     * <p>The constructor only stores the references (it invokes no overridable method), so it is free
     * of the {@code this-escape} lint category under the zero-warning build.</p>
     *
     * @param context               the session-scoped {@link CardDemoContext} (COMMAREA replacement)
     * @param transactionRepository the {@link TransactionRepository} (migrated {@code TRANSACT} file)
     * @param cardXrefRepository    the {@link CardXrefRepository} (migrated {@code CCXREF}/{@code CXACAIX})
     * @param accountRepository     the {@link AccountRepository} (declared {@code ACCTDAT} handle; unused I/O)
     * @param dateConversionService the {@link DateConversionService} ({@code CSUTLDTC} date validator)
     * @param selfProvider          the self {@link ObjectProvider} yielding the transactional proxy
     */
    public TransactionAddService(CardDemoContext context,
                                 TransactionRepository transactionRepository,
                                 CardXrefRepository cardXrefRepository,
                                 AccountRepository accountRepository,
                                 DateConversionService dateConversionService,
                                 ObjectProvider<TransactionAddService> selfProvider) {
        this.context = context;
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.dateConversionService = dateConversionService;
        this.selfProvider = selfProvider;
    }

    /**
     * Main entry point - migration of paragraph {@code MAIN-PARA}
     * ({@code legacy/cbl/COTRN02C.cbl} lines 107-159).
     *
     * <p>Reproduces the pseudo-conversational state machine exactly. Each call starts with the error
     * flag off and no message (COBOL {@code SET ERR-FLG-OFF} / {@code MOVE SPACES TO WS-MESSAGE});
     * that pristine state is represented by returning a fresh {@link TransactionAddResult} rather than
     * mutating persistent fields.</p>
     *
     * <ul>
     *   <li><b>{@code EIBCALEN = 0}</b> ({@link CardDemoContext#isNew()}): no COMMAREA - set the
     *       sign-on program as the transfer target and hand off to it (COBOL lines 116-118).</li>
     *   <li><b>First program entry</b> ({@link CardDemoContext#isProgramEnter()}, i.e.
     *       {@code NOT CDEMO-PGM-REENTER}): flip to re-enter ({@link CardDemoContext#markReenter()})
     *       and position the cursor at the account-id field. When a card has been pre-selected
     *       ({@code CDEMO-CT02-TRN-SELECTED}, mapped to {@link CardDemoContext#getCardNum()}) it is
     *       copied into the card-number input and {@link #processEnterKey(COTRN02Form)} runs
     *       immediately (COBOL lines 120-131); otherwise a fresh screen is shown.</li>
     *   <li><b>Re-entry</b>: evaluate the attention id (COBOL lines 132-155) - {@code ENTER} drives
     *       {@link #processEnterKey(COTRN02Form)}; {@code PF3} ({@link PfKey#PFK03}) returns to the
     *       caller ({@code CDEMO-FROM-PROGRAM}, or {@code COMEN01C} when blank); {@code PF4}
     *       ({@link PfKey#PFK04}) clears the screen; {@code PF5} ({@link PfKey#PFK05}) copies the last
     *       transaction; any other key yields {@link Messages#CCDA_MSG_INVALID_KEY}. A {@code null}
     *       key is treated as the {@code WHEN OTHER} branch.</li>
     * </ul>
     *
     * <p>The concluding {@code EXEC CICS RETURN TRANSID(CT02) COMMAREA(...)} (COBOL lines 156-159) is
     * the controller's responsibility: it writes the mutated {@link CardDemoContext} back to the HTTP
     * session and either re-renders the screen or performs the redirect indicated by the returned
     * {@link TransactionAddResult}.</p>
     *
     * @param form the bound add-transaction screen form ({@code COTRN2AI}); never {@code null}
     * @param aid  the resolved attention id (COBOL {@code EIBAID}); {@code null} is treated as
     *             {@link PfKey#OTHER}
     * @return the outcome describing whether to redirect or re-display the screen, plus any message,
     *         its severity, and the cursor field; never {@code null}
     */
    public TransactionAddResult mainEntry(COTRN02Form form, PfKey aid) {
        // COBOL lines 116-118: IF EIBCALEN = 0 -> no COMMAREA; transfer to the sign-on program.
        if (context.isNew()) {
            context.setToProgram(SIGNON_PROGRAM);
            return beginReturnToPrevScreen();
        }

        // COBOL lines 120-131: first display of this conversation (NOT CDEMO-PGM-REENTER).
        if (context.isProgramEnter()) {
            context.markReenter();
            // COBOL lines 124-127: a pre-selected card (CDEMO-CT02-TRN-SELECTED) is loaded into the
            // card-number input and the enter-key path runs immediately.
            String selectedCard = context.getCardNum();
            if (!isBlankOrLowValues(selectedCard)) {
                form.setCardnin(selectedCard);
                return processEnterKey(form);
            }
            // COBOL line 123: MOVE -1 TO ACTIDINL -> cursor at the account-id field.
            return TransactionAddResult.showScreen(CURSOR_ACTIDIN);
        }

        // COBOL lines 132-155: re-entry -> EVALUATE EIBAID. A null key falls through to WHEN OTHER.
        PfKey pressedKey = (aid == null) ? PfKey.OTHER : aid;
        return switch (pressedKey) {
            case ENTER -> processEnterKey(form);
            case PFK03 -> {
                // COBOL lines 138-144: PF3 target is CDEMO-FROM-PROGRAM, or COMEN01C when it is blank.
                String target = isBlankOrLowValues(context.getFromProgram()) ? MENU_PROGRAM : context.getFromProgram();
                context.setToProgram(target);
                yield beginReturnToPrevScreen();
            }
            case PFK04 -> clearCurrentScreen(form);
            case PFK05 -> copyLastTranData(form);
            default -> TransactionAddResult.error(Messages.CCDA_MSG_INVALID_KEY, CURSOR_NONE);
        };
    }

    /**
     * Processes an {@code ENTER} on the add-transaction screen - migration of paragraph
     * {@code PROCESS-ENTER-KEY} ({@code legacy/cbl/COTRN02C.cbl} lines 163-189).
     *
     * <p>Runs the two validation paragraphs in order (COBOL lines 165-166):
     * {@link #validateInputKeyFields(COTRN02Form)} then
     * {@link #validateInputDataFields(COTRN02Form)}. Each returns a non-null
     * {@link TransactionAddResult} on the first failing check, which is returned immediately - this is
     * the Java equivalent of the COBOL {@code PERFORM SEND-TRNADD-SCREEN} whose {@code EXEC CICS
     * RETURN} terminates the task before any later check runs.</p>
     *
     * <p>When both validations pass, the {@code CONFIRM} field is evaluated (COBOL lines 168-188):
     * {@code 'Y'}/{@code 'y'} performs the add ({@link #addTransaction(COTRN02Form)}); {@code 'N'},
     * {@code 'n'}, spaces or low-values yields the {@link #MSG_CONFIRM_PROMPT} prompt with the cursor
     * on {@code CONFIRM}; any other value yields {@link #MSG_INVALID_CONFIRM}.</p>
     *
     * <p>The add is invoked through the Spring proxy ({@link #selfProvider}) so that its
     * {@link Transactional} advice is applied. A duplicate key surfaces as a
     * {@link DuplicateKeyException} ({@link #MSG_DUPE}); any other data-access failure surfaces as
     * {@link #MSG_ADD_ERR} - both are caught here, <em>outside</em> the transaction boundary, so the
     * rolled-back transaction has already completed (COBOL {@code WRITE-TRANSACT-FILE} WHEN
     * {@code DUPKEY}/{@code DUPREC} / WHEN {@code OTHER}).</p>
     *
     * @param form the bound add-transaction screen form; never {@code null}
     * @return the outcome to render; never {@code null}
     */
    public TransactionAddResult processEnterKey(COTRN02Form form) {
        // COBOL line 165: PERFORM VALIDATE-INPUT-KEY-FIELDS.
        TransactionAddResult keyFieldResult = validateInputKeyFields(form);
        if (keyFieldResult != null) {
            return keyFieldResult;
        }
        // COBOL line 166: PERFORM VALIDATE-INPUT-DATA-FIELDS.
        TransactionAddResult dataFieldResult = validateInputDataFields(form);
        if (dataFieldResult != null) {
            return dataFieldResult;
        }

        // COBOL lines 168-188: EVALUATE the CONFIRM value.
        String confirm = form.getConfirm();
        if ("Y".equals(confirm) || "y".equals(confirm)) {
            try {
                // Invoke through the proxy so @Transactional is honored (self-invocation guard).
                return selfProvider.getObject().addTransaction(form);
            } catch (DuplicateKeyException ex) {
                // COBOL WRITE-TRANSACT-FILE WHEN DFHRESP(DUPKEY)/DUPREC (lines 736-742).
                return TransactionAddResult.error(MSG_DUPE, CURSOR_ACTIDIN);
            } catch (DataAccessException ex) {
                // COBOL WRITE-TRANSACT-FILE WHEN OTHER (lines 743-748) and any browse failure during
                // the add: "Unable to Add Transaction...".
                return TransactionAddResult.error(MSG_ADD_ERR, CURSOR_ACTIDIN);
            }
        }
        if (isBlankOrLowValues(confirm) || "N".equals(confirm) || "n".equals(confirm)) {
            // COBOL lines 173-181: N/n/spaces/low-values -> confirmation prompt.
            return TransactionAddResult.error(MSG_CONFIRM_PROMPT, CURSOR_CONFIRM);
        }
        // COBOL lines 182-187: WHEN OTHER -> invalid CONFIRM value.
        return TransactionAddResult.error(MSG_INVALID_CONFIRM, CURSOR_CONFIRM);
    }


    /**
     * Validates the account-id / card-number key fields - migration of paragraph
     * {@code VALIDATE-INPUT-KEY-FIELDS} ({@code legacy/cbl/COTRN02C.cbl} lines 193-231).
     *
     * <p>Reproduces the COBOL {@code EVALUATE TRUE} exactly, in which the account-id branch is
     * evaluated first: when both key fields are supplied the account id wins and the card number is
     * re-derived from the cross-reference (so any card entered by the user is overwritten). The
     * mutually exclusive branches are:</p>
     * <ul>
     *   <li><b>Account id entered</b> (COBOL lines 196-209): it must be numeric
     *       ({@link #MSG_ACCT_NOT_NUMERIC}); the parsed value is echoed back zero-padded to
     *       {@code 9(11)} ({@code MOVE WS-ACCT-ID-N TO ACTIDINI}); {@link #readCxacaixFile(long)}
     *       resolves the cross-reference; and the resolved card number is loaded into the
     *       card-number input ({@code MOVE XREF-CARD-NUM TO CARDNINI}).</li>
     *   <li><b>Card number entered</b> (COBOL lines 210-224): it must be numeric
     *       ({@link #MSG_CARD_NOT_NUMERIC}); the parsed value is echoed back zero-padded to
     *       {@code 9(16)}; {@link #readCcxrefFile(String)} resolves the cross-reference; and the
     *       resolved account id is loaded into the account-id input ({@code MOVE XREF-ACCT-ID TO
     *       ACTIDINI}).</li>
     *   <li><b>Neither entered</b> (COBOL lines 225-229): {@link #MSG_ACCT_OR_CARD_REQUIRED}.</li>
     * </ul>
     *
     * <p>A {@code NOTFND} on either cross-reference {@code READ} (a {@link RecordNotFoundException})
     * becomes the corresponding "... NOT found..." message; any other data-access failure becomes the
     * corresponding "Unable to lookup..." message (COBOL {@code WHEN OTHER} branches).</p>
     *
     * @param form the bound screen form; the account/card inputs are read and re-echoed in place
     * @return {@code null} when the key fields are valid and the cross-reference resolved, otherwise
     *         the error {@link TransactionAddResult} to send (the COBOL {@code SEND-TRNADD-SCREEN})
     */
    private TransactionAddResult validateInputKeyFields(COTRN02Form form) {
        String accountInput = form.getActidin();
        String cardInput = form.getCardnin();

        // COBOL lines 196-209: account id entered (checked first).
        if (!isBlankOrLowValues(accountInput)) {
            if (!isNumeric(accountInput)) {
                return TransactionAddResult.error(MSG_ACCT_NOT_NUMERIC, CURSOR_ACTIDIN);
            }
            long acctId = Long.parseLong(accountInput);
            // COBOL lines 205-207: re-echo the normalized (zero-padded) account id.
            form.setActidin(formatFixedNumber(acctId, ACCT_ID_WIDTH));
            CardXref xref;
            try {
                xref = readCxacaixFile(acctId);
            } catch (RecordNotFoundException ex) {
                return TransactionAddResult.error(MSG_ACCT_NOT_FOUND, CURSOR_ACTIDIN);
            } catch (DataAccessException ex) {
                return TransactionAddResult.error(MSG_ACCT_LOOKUP_ERR, CURSOR_ACTIDIN);
            }
            // COBOL line 209: MOVE XREF-CARD-NUM TO CARDNINI.
            form.setCardnin(xref.getXrefCardNum());
            return null;
        }

        // COBOL lines 210-224: card number entered (account id blank).
        if (!isBlankOrLowValues(cardInput)) {
            if (!isNumeric(cardInput)) {
                return TransactionAddResult.error(MSG_CARD_NOT_NUMERIC, CURSOR_CARDNIN);
            }
            // COBOL lines 220-222: re-echo the normalized (zero-padded) card number.
            String cardNum = formatFixedNumber(Long.parseLong(cardInput), CARD_NUM_WIDTH);
            form.setCardnin(cardNum);
            CardXref xref;
            try {
                xref = readCcxrefFile(cardNum);
            } catch (RecordNotFoundException ex) {
                return TransactionAddResult.error(MSG_CARD_NOT_FOUND, CURSOR_CARDNIN);
            } catch (DataAccessException ex) {
                return TransactionAddResult.error(MSG_CARD_LOOKUP_ERR, CURSOR_CARDNIN);
            }
            // COBOL line 224: MOVE XREF-ACCT-ID TO ACTIDINI.
            form.setActidin(formatOptionalNumber(xref.getXrefAcctId(), ACCT_ID_WIDTH));
            return null;
        }

        // COBOL lines 225-229: WHEN OTHER -> neither key field supplied.
        return TransactionAddResult.error(MSG_ACCT_OR_CARD_REQUIRED, CURSOR_ACTIDIN);
    }

    /**
     * Resolves an account id to its card cross-reference - migration of paragraph
     * {@code READ-CXACAIX-FILE} ({@code legacy/cbl/COTRN02C.cbl} lines 573-602).
     *
     * <p>The COBOL {@code EXEC CICS READ} on the {@code CXACAIX} alternate index becomes the derived
     * query {@link CardXrefRepository#findByXrefAcctId(Long)}. Because the legacy alternate index is
     * keyed on the account id and yields the cross-reference whose base ({@code CCXREF}) key - the
     * card number - sorts first, the lowest card number is selected among the matches for a
     * deterministic equivalent under the {@code C}/{@code POSIX} collation (AAP &sect;0.6.2,
     * &sect;0.6.6).</p>
     *
     * @param acctId the account id to resolve
     * @return the selected {@link CardXref}; never {@code null}
     * @throws RecordNotFoundException when no cross-reference exists for the account
     *         (the COBOL {@code DFHRESP(NOTFND)} branch)
     */
    private CardXref readCxacaixFile(long acctId) {
        List<CardXref> matches = cardXrefRepository.findByXrefAcctId(Long.valueOf(acctId));
        return matches.stream()
                .min(Comparator.comparing(CardXref::getXrefCardNum))
                .orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_FOUND));
    }

    /**
     * Resolves a card number to its cross-reference - migration of paragraph
     * {@code READ-CCXREF-FILE} ({@code legacy/cbl/COTRN02C.cbl} lines 607-635).
     *
     * <p>The COBOL {@code EXEC CICS READ} on the {@code CCXREF} file by card number becomes the
     * derived query {@link CardXrefRepository#findByXrefCardNum(String)}.</p>
     *
     * @param cardNum the zero-padded 16-digit card number to resolve
     * @return the {@link CardXref}; never {@code null}
     * @throws RecordNotFoundException when no cross-reference exists for the card
     *         (the COBOL {@code DFHRESP(NOTFND)} branch)
     */
    private CardXref readCcxrefFile(String cardNum) {
        return cardXrefRepository.findByXrefCardNum(cardNum)
                .orElseThrow(() -> new RecordNotFoundException(MSG_CARD_NOT_FOUND));
    }


    /**
     * Validates the transaction data fields - migration of paragraph
     * {@code VALIDATE-INPUT-DATA-FIELDS} ({@code legacy/cbl/COTRN02C.cbl} lines 236-435).
     *
     * <p>The checks run in the exact COBOL order, each returning immediately on the first failure
     * (the COBOL {@code SEND-TRNADD-SCREEN} / {@code EXEC CICS RETURN} early termination):</p>
     * <ol>
     *   <li><b>Empty checks</b> (COBOL {@code EVALUATE TRUE}, lines 252-317): type code, category
     *       code, source, description, amount, original date, processing date, merchant id, merchant
     *       name, merchant city, merchant zip - in that order.</li>
     *   <li><b>Numeric checks</b> (COBOL {@code EVALUATE TRUE}, lines 321-333): type code then
     *       category code must be numeric.</li>
     *   <li><b>Amount layout</b> (COBOL {@code EVALUATE TRUE}, lines 336-350): the fixed 12-byte
     *       {@code -99999999.99} layout - a sign, eight digits, a dot, two digits.</li>
     *   <li><b>Date layouts</b> (COBOL lines 353-380): the fixed {@code YYYY-MM-DD} layout for the
     *       original and processing dates.</li>
     *   <li><b>Amount normalization</b> (COBOL lines 383-386): the amount is parsed
     *       ({@code FUNCTION NUMVAL-C}) and re-echoed through the {@code +99999999.99} edit mask.</li>
     *   <li><b>Calendar validity</b> (COBOL lines 388-428): each date is validated via
     *       {@link DateConversionService} (the {@code CALL 'CSUTLDTC'}); a non-zero severity that is
     *       not the tolerated pre-1582 code {@value #MSG_NUM_UNSUPP_RANGE} is rejected.</li>
     *   <li><b>Merchant id numeric</b> (COBOL lines 430-434).</li>
     * </ol>
     *
     * <p>The COBOL {@code IF ERR-FLG-ON} field-clearing block at the head of this paragraph (lines
     * 238-249) is unreachable in the migrated control flow - this paragraph runs only after
     * {@link #validateInputKeyFields(COTRN02Form)} returned {@code null} (no error), so the error flag
     * is always off on entry - and is therefore intentionally not reproduced (a documented parity
     * decision, AAP &sect;0.2.2).</p>
     *
     * <p>The empty and numeric checks apply the COBOL semantics to the submitted (variable-length)
     * value; the amount and date layout checks left-pad the value to the field's fixed width before
     * inspecting byte positions, exactly mirroring COBOL reference-modification on the fixed
     * {@code PIC X(12)}/{@code PIC X(10)} fields.</p>
     *
     * @param form the bound screen form; the amount is normalized in place when all checks pass
     * @return {@code null} when every data field is valid, otherwise the error
     *         {@link TransactionAddResult} to send
     */
    private TransactionAddResult validateInputDataFields(COTRN02Form form) {
        // COBOL lines 252-317: empty checks, in order.
        if (isBlankOrLowValues(form.getTtypcd())) {
            return TransactionAddResult.error(MSG_TYPE_EMPTY, CURSOR_TTYPCD);
        }
        if (isBlankOrLowValues(form.getTcatcd())) {
            return TransactionAddResult.error(MSG_CAT_EMPTY, CURSOR_TCATCD);
        }
        if (isBlankOrLowValues(form.getTrnsrc())) {
            return TransactionAddResult.error(MSG_SOURCE_EMPTY, CURSOR_TRNSRC);
        }
        if (isBlankOrLowValues(form.getTdesc())) {
            return TransactionAddResult.error(MSG_DESC_EMPTY, CURSOR_TDESC);
        }
        if (isBlankOrLowValues(form.getTrnamt())) {
            return TransactionAddResult.error(MSG_AMT_EMPTY, CURSOR_TRNAMT);
        }
        if (isBlankOrLowValues(form.getTorigdt())) {
            return TransactionAddResult.error(MSG_ORIG_EMPTY, CURSOR_TORIGDT);
        }
        if (isBlankOrLowValues(form.getTprocdt())) {
            return TransactionAddResult.error(MSG_PROC_EMPTY, CURSOR_TPROCDT);
        }
        if (isBlankOrLowValues(form.getMid())) {
            return TransactionAddResult.error(MSG_MID_EMPTY, CURSOR_MID);
        }
        if (isBlankOrLowValues(form.getMname())) {
            return TransactionAddResult.error(MSG_MNAME_EMPTY, CURSOR_MNAME);
        }
        if (isBlankOrLowValues(form.getMcity())) {
            return TransactionAddResult.error(MSG_MCITY_EMPTY, CURSOR_MCITY);
        }
        if (isBlankOrLowValues(form.getMzip())) {
            return TransactionAddResult.error(MSG_MZIP_EMPTY, CURSOR_MZIP);
        }

        // COBOL lines 321-333: type code then category code must be numeric.
        if (!isNumeric(form.getTtypcd())) {
            return TransactionAddResult.error(MSG_TYPE_NOT_NUMERIC, CURSOR_TTYPCD);
        }
        if (!isNumeric(form.getTcatcd())) {
            return TransactionAddResult.error(MSG_CAT_NOT_NUMERIC, CURSOR_TCATCD);
        }

        // COBOL lines 336-350: fixed -99999999.99 amount layout.
        if (!isValidAmountFormat(form.getTrnamt())) {
            return TransactionAddResult.error(MSG_AMT_FORMAT, CURSOR_TRNAMT);
        }

        // COBOL lines 353-366: fixed YYYY-MM-DD original-date layout.
        if (!isValidDateFormat(form.getTorigdt())) {
            return TransactionAddResult.error(MSG_ORIG_FORMAT, CURSOR_TORIGDT);
        }
        // COBOL lines 368-380: fixed YYYY-MM-DD processing-date layout.
        if (!isValidDateFormat(form.getTprocdt())) {
            return TransactionAddResult.error(MSG_PROC_FORMAT, CURSOR_TPROCDT);
        }

        // COBOL lines 383-386: NUMVAL-C reformat -> WS-TRAN-AMT-E edit mask -> re-echo to TRNAMTI.
        BigDecimal amount = parseAmount(form.getTrnamt());
        form.setTrnamt(formatAmountEditMask(amount));

        // COBOL lines 388-407: validate the original date via CSUTLDTC.
        DateValidationResult origResult = dateConversionService.validateDate(form.getTorigdt(), DATE_FORMAT_MASK);
        if (origResult.severity() != 0 && origResult.msgNo() != MSG_NUM_UNSUPP_RANGE) {
            return TransactionAddResult.error(MSG_ORIG_INVALID, CURSOR_TORIGDT);
        }
        // COBOL lines 409-428: validate the processing date via CSUTLDTC.
        DateValidationResult procResult = dateConversionService.validateDate(form.getTprocdt(), DATE_FORMAT_MASK);
        if (procResult.severity() != 0 && procResult.msgNo() != MSG_NUM_UNSUPP_RANGE) {
            return TransactionAddResult.error(MSG_PROC_INVALID, CURSOR_TPROCDT);
        }

        // COBOL lines 430-434: merchant id must be numeric.
        if (!isNumeric(form.getMid())) {
            return TransactionAddResult.error(MSG_MID_NOT_NUMERIC, CURSOR_MID);
        }

        // All data fields valid: COBOL falls through to the CONFIRM evaluation in PROCESS-ENTER-KEY.
        return null;
    }


    /**
     * Builds and writes the new transaction - migration of paragraph {@code ADD-TRANSACTION}
     * ({@code legacy/cbl/COTRN02C.cbl} lines 443-466).
     *
     * <p>The next transaction id is derived from the current maximum key ({@link #deriveNextTranId()},
     * the COBOL descending browse plus one), the validated form fields are mapped onto a fresh
     * {@link Transaction} (with the COBOL type conversions - category code to {@link Integer}, merchant
     * id to {@link Long}, the {@code YYYY-MM-DD} dates to {@link LocalDateTime} at start-of-day, and
     * the amount to a scale-2 {@link BigDecimal}), and the record is persisted by
     * {@link #writeTransactFile(COTRN02Form, Transaction)}.</p>
     *
     * <p>This method is {@link Transactional} so the max-key read and the insert share one unit of
     * work (AAP &sect;0.3.3, &sect;0.6.3). Because it is invoked from
     * {@link #processEnterKey(COTRN02Form)} within the same bean, it is called through the Spring
     * proxy ({@link #selfProvider}) so the transaction advice is actually applied. A duplicate key is
     * raised as a {@link DuplicateKeyException} which propagates out of this method, rolling the
     * transaction back cleanly; it (and any other {@link DataAccessException}) is handled by the
     * caller, outside the transaction boundary.</p>
     *
     * @param form the validated add-transaction screen form; never {@code null}
     * @return the success outcome to display; never {@code null}
     * @throws DuplicateKeyException when the derived transaction id already exists
     *         (COBOL {@code DFHRESP(DUPKEY)}/{@code DUPREC})
     * @throws DataAccessException on any other persistence failure (COBOL browse/{@code WRITE}
     *         {@code WHEN OTHER})
     */
    @Transactional
    public TransactionAddResult addTransaction(COTRN02Form form) {
        Transaction transaction = new Transaction();
        // COBOL lines 445-451: next id = highest existing id + 1, zero-padded to 16 digits.
        transaction.setTranId(deriveNextTranId());
        // COBOL lines 452-465: map the validated inputs onto the record, with COBOL type conversions.
        transaction.setTranTypeCd(form.getTtypcd());
        transaction.setTranCatCd(Integer.parseInt(form.getTcatcd()));
        transaction.setTranSource(form.getTrnsrc());
        transaction.setTranDesc(form.getTdesc());
        transaction.setTranAmt(parseAmount(form.getTrnamt()));
        transaction.setCardNum(form.getCardnin());
        transaction.setMerchantId(Long.parseLong(form.getMid()));
        transaction.setMerchantName(form.getMname());
        transaction.setMerchantCity(form.getMcity());
        transaction.setMerchantZip(form.getMzip());
        transaction.setOrigTs(parseFormDate(form.getTorigdt()));
        transaction.setProcTs(parseFormDate(form.getTprocdt()));
        // COBOL line 466: PERFORM WRITE-TRANSACT-FILE.
        return writeTransactFile(form, transaction);
    }

    /**
     * Persists the new transaction and reports the outcome - migration of paragraph
     * {@code WRITE-TRANSACT-FILE} ({@code legacy/cbl/COTRN02C.cbl} lines 711-749).
     *
     * <p>Uses {@code saveAndFlush} so a duplicate key is surfaced synchronously (mirroring the CICS
     * {@code WRITE} {@code RESP} check) as a {@link DataIntegrityViolationException}, which is
     * translated to a {@link DuplicateKeyException} and thrown - the COBOL
     * {@code WHEN DFHRESP(DUPKEY)}/{@code DUPREC} path ({@link #MSG_DUPE}). On success (the COBOL
     * {@code WHEN DFHRESP(NORMAL)} path) the screen fields are cleared ({@code INITIALIZE-ALL-FIELDS})
     * and the green success message is returned. Any other persistence failure propagates as a
     * {@link DataAccessException} (the COBOL {@code WHEN OTHER} path, handled by the caller as
     * {@link #MSG_ADD_ERR}).</p>
     *
     * @param form        the screen form, cleared in place on success
     * @param transaction the fully-populated {@link Transaction} to write
     * @return the success outcome (green message) to display; never {@code null}
     * @throws DuplicateKeyException when the record's key already exists
     */
    private TransactionAddResult writeTransactFile(COTRN02Form form, Transaction transaction) {
        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException ex) {
            // COBOL lines 736-742: WHEN DFHRESP(DUPKEY)/DUPREC.
            throw new DuplicateKeyException(MSG_DUPE, ex);
        }
        // COBOL lines 726-735: WHEN DFHRESP(NORMAL) - clear the fields and report success in green.
        initializeAllFields(form);
        return TransactionAddResult.success(buildSuccessMessage(transaction.getTranId()));
    }

    /**
     * Reads the highest-keyed transaction - migration of the descending browse paragraphs
     * {@code STARTBR-TRANSACT-FILE}, {@code READPREV-TRANSACT-FILE} and {@code ENDBR-TRANSACT-FILE}
     * ({@code legacy/cbl/COTRN02C.cbl} lines 640-706).
     *
     * <p>The COBOL positions the browse at {@code HIGH-VALUES} and reads the previous record to obtain
     * the last (highest) transaction id, then ends the browse. That is reproduced by the sanctioned
     * max-key query - a single-row descending page ordered by {@code tranId} - which the file
     * specification endorses as the equivalent of {@code findTop...OrderByTranIdDesc} / a max-id
     * query. An empty result mirrors the COBOL {@code READPREV} {@code ENDFILE} branch (a zero id, so
     * the next id becomes 1).</p>
     *
     * @return the highest-keyed {@link Transaction}, or {@link Optional#empty()} when none exist
     */
    private Optional<Transaction> readLastTransaction() {
        List<Transaction> highest = transactionRepository
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "tranId")))
                .getContent();
        return highest.isEmpty() ? Optional.empty() : Optional.of(highest.get(0));
    }

    /**
     * Derives the next transaction id ({@code last id + 1}, zero-padded to 16 digits) - the COBOL
     * {@code MOVE TRAN-ID TO WS-TRAN-ID-N} / {@code ADD 1} sequence ({@code legacy/cbl/COTRN02C.cbl}
     * lines 447-451). An empty file yields {@code 1} (the {@code READPREV} {@code ENDFILE} path).
     *
     * @return the 16-digit zero-padded next transaction id
     */
    private String deriveNextTranId() {
        long lastId = readLastTransaction()
                .map(transaction -> parseTranId(transaction.getTranId()))
                .orElse(0L);
        return formatFixedNumber(lastId + 1L, TRAN_ID_WIDTH);
    }

    /**
     * Copies the most recent transaction onto the screen and re-processes it - migration of paragraph
     * {@code COPY-LAST-TRAN-DATA} ({@code legacy/cbl/COTRN02C.cbl} lines 471-495), the PF5 action.
     *
     * <p>Runs {@link #validateInputKeyFields(COTRN02Form)} first (an error terminates the task,
     * exactly as the COBOL {@code SEND}), then browses to the <em>global</em> last transaction (the
     * COBOL {@code MOVE HIGH-VALUES TO TRAN-ID}; there is deliberately no card filter - the COBOL is
     * authoritative over the paraphrase "of that card"), copies its fields onto the form via
     * {@link #copyTransactionToForm(Transaction, COTRN02Form)}, and finally re-invokes
     * {@link #processEnterKey(COTRN02Form)}. An empty file (unreachable in the seeded system) simply
     * skips the copy.</p>
     *
     * @param form the bound screen form; populated in place from the last transaction
     * @return the outcome of the subsequent {@link #processEnterKey(COTRN02Form)}, or the key-field
     *         error when key validation fails; never {@code null}
     */
    public TransactionAddResult copyLastTranData(COTRN02Form form) {
        // COBOL line 473: PERFORM VALIDATE-INPUT-KEY-FIELDS.
        TransactionAddResult keyFieldResult = validateInputKeyFields(form);
        if (keyFieldResult != null) {
            return keyFieldResult;
        }
        // COBOL lines 475-478: browse to the global last transaction.
        Optional<Transaction> lastTransaction;
        try {
            lastTransaction = readLastTransaction();
        } catch (DataAccessException ex) {
            return TransactionAddResult.error(MSG_TRAN_LOOKUP_ERR, CURSOR_ACTIDIN);
        }
        // COBOL lines 480-493: IF NOT ERR-FLG-ON copy the record fields onto the screen.
        lastTransaction.ifPresent(transaction -> copyTransactionToForm(transaction, form));
        // COBOL line 495: PERFORM PROCESS-ENTER-KEY.
        return processEnterKey(form);
    }

    /**
     * Copies a transaction record's business fields onto the screen inputs - the field moves of
     * paragraph {@code COPY-LAST-TRAN-DATA} ({@code legacy/cbl/COTRN02C.cbl} lines 481-492).
     *
     * <p>The amount is rendered through the {@code +99999999.99} edit mask, the category code and
     * merchant id are zero-padded to their display widths, and the timestamps are rendered as
     * {@code YYYY-MM-DD}. The card number is intentionally not copied - the COBOL leaves
     * {@code CARDNINI} as set by key-field validation.</p>
     *
     * @param transaction the source record
     * @param form        the destination screen form
     */
    private void copyTransactionToForm(Transaction transaction, COTRN02Form form) {
        form.setTtypcd(transaction.getTranTypeCd());
        form.setTcatcd(formatOptionalNumber(transaction.getTranCatCd(), CATEGORY_CODE_WIDTH));
        form.setTrnsrc(transaction.getTranSource());
        form.setTrnamt(formatAmountEditMask(transaction.getTranAmt()));
        form.setTdesc(transaction.getTranDesc());
        form.setTorigdt(formatFormDate(transaction.getOrigTs()));
        form.setTprocdt(formatFormDate(transaction.getProcTs()));
        form.setMid(formatOptionalNumber(transaction.getMerchantId(), MERCHANT_ID_WIDTH));
        form.setMname(transaction.getMerchantName());
        form.setMcity(transaction.getMerchantCity());
        form.setMzip(transaction.getMerchantZip());
    }

    /**
     * Clears the screen - migration of paragraph {@code CLEAR-CURRENT-SCREEN}
     * ({@code legacy/cbl/COTRN02C.cbl} lines 753-757), the PF4 action.
     *
     * <p>Re-initializes every input ({@link #initializeAllFields(COTRN02Form)}) and re-displays the
     * empty screen with the cursor on the account-id field.</p>
     *
     * @param form the bound screen form, cleared in place
     * @return a {@link ScreenAction#SHOW_SCREEN} outcome with the cursor on the account-id field
     */
    public TransactionAddResult clearCurrentScreen(COTRN02Form form) {
        initializeAllFields(form);
        return TransactionAddResult.showScreen(CURSOR_ACTIDIN);
    }

    /**
     * Blanks every input field and the message line - migration of paragraph
     * {@code INITIALIZE-ALL-FIELDS} ({@code legacy/cbl/COTRN02C.cbl} lines 763-778).
     *
     * <p>Mirrors the COBOL {@code MOVE -1 TO ACTIDINL} (cursor to the account-id field, represented by
     * the caller's {@link #CURSOR_ACTIDIN}) followed by {@code MOVE SPACES} to all inputs and
     * {@code WS-MESSAGE}; here the fields are set to the empty string (a blank BMS field).</p>
     *
     * @param form the bound screen form, cleared in place
     */
    private void initializeAllFields(COTRN02Form form) {
        form.setActidin("");
        form.setCardnin("");
        form.setTtypcd("");
        form.setTcatcd("");
        form.setTrnsrc("");
        form.setTrnamt("");
        form.setTdesc("");
        form.setTorigdt("");
        form.setTprocdt("");
        form.setMid("");
        form.setMname("");
        form.setMcity("");
        form.setMzip("");
        form.setConfirm("");
        form.setErrmsg("");
    }

    /**
     * Prepares the transfer back to the calling program - migration of paragraph
     * {@code RETURN-TO-PREV-SCREEN} ({@code legacy/cbl/COTRN02C.cbl} lines 498-510).
     *
     * <p>Defaults the transfer target to the sign-on program when it is blank (COBOL lines 500-502),
     * records this program's transaction id and name as the caller (COBOL lines 503-504), and resets
     * the program context to "enter" ({@link CardDemoContext#markEnter()}, the COBOL
     * {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}). The actual {@code EXEC CICS XCTL} (COBOL lines
     * 506-509) is the controller's redirect.</p>
     *
     * @return a {@link ScreenAction#REDIRECT} outcome (the target is recorded on the context)
     */
    private TransactionAddResult beginReturnToPrevScreen() {
        if (isBlankOrLowValues(context.getToProgram())) {
            context.setToProgram(SIGNON_PROGRAM);
        }
        context.setFromTranid(TRANSACTION_ID);
        context.setFromProgram(PROGRAM_NAME);
        context.markEnter();
        return TransactionAddResult.redirect();
    }


    // -- Private helpers --------------------------------------------------------------------------

    /**
     * Reproduces the COBOL {@code = SPACES OR LOW-VALUES} test used throughout {@code COTRN02C} for
     * the "field not entered" checks.
     *
     * @param value the candidate value (may be {@code null})
     * @return {@code true} when {@code value} is {@code null}, empty, or consists solely of spaces
     *         and/or low-value ({@code NUL}) characters
     */
    private static boolean isBlankOrLowValues(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ' ' && c != '\0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the COBOL {@code IS NUMERIC} class test for a {@code PIC X} field: {@code true} only
     * when the value is non-empty and every character is an ASCII digit ({@code '0'}-{@code '9'}).
     *
     * <p>The test is applied to the submitted (variable-length) value rather than to a fixed-width,
     * space-padded field; this is a documented consequence of the variable-length screen-form DTO and
     * matches ordinary web input for these numeric code fields (AAP &sect;0.6).</p>
     *
     * @param value the candidate value (may be {@code null})
     * @return {@code true} when {@code value} is a non-empty run of ASCII digits
     */
    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return isAllDigits(value, 0, value.length());
    }

    /**
     * Tests whether every character in {@code [from, to)} of {@code value} is an ASCII digit. The
     * caller guarantees {@code value.length() >= to}.
     *
     * @param value the string to inspect
     * @param from  the inclusive start index
     * @param to    the exclusive end index
     * @return {@code true} when every inspected character is {@code '0'}-{@code '9'}
     */
    private static boolean isAllDigits(String value, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Right-pads {@code value} with spaces to at least {@code length} characters, reproducing the
     * fixed-width, space-filled semantics of a COBOL {@code PIC X(length)} field. A value already at
     * least {@code length} long is returned unchanged; a {@code null} value is treated as empty.
     *
     * @param value  the value to pad (may be {@code null})
     * @param length the minimum resulting length
     * @return the space-padded value
     */
    private static String padRight(String value, int length) {
        String text = (value == null) ? "" : value;
        if (text.length() >= length) {
            return text;
        }
        StringBuilder builder = new StringBuilder(length);
        builder.append(text);
        while (builder.length() < length) {
            builder.append(' ');
        }
        return builder.toString();
    }

    /**
     * Validates the fixed 12-byte amount layout {@code -99999999.99} - the COBOL reference-modification
     * checks {@code TRNAMTI(1:1)}, {@code (2:8)}, {@code (10:1)}, {@code (11:2)}
     * ({@code legacy/cbl/COTRN02C.cbl} lines 337-341). The value is left-padded to 12 bytes first so a
     * short entry fails exactly as the COBOL fixed field would.
     *
     * @param value the raw amount input (may be {@code null})
     * @return {@code true} when byte 1 is a sign, bytes 2-9 and 11-12 are digits, and byte 10 is a dot
     */
    private static boolean isValidAmountFormat(String value) {
        String fixed = padRight(value, AMOUNT_FIELD_LENGTH);
        char sign = fixed.charAt(0);
        if (sign != '-' && sign != '+') {
            return false;
        }
        if (!isAllDigits(fixed, 1, 9)) {
            return false;
        }
        if (fixed.charAt(9) != '.') {
            return false;
        }
        return isAllDigits(fixed, 10, 12);
    }

    /**
     * Validates the fixed 10-byte {@code YYYY-MM-DD} layout - the COBOL reference-modification checks
     * {@code (1:4)}, {@code (5:1)}, {@code (6:2)}, {@code (8:1)}, {@code (9:2)}
     * ({@code legacy/cbl/COTRN02C.cbl} lines 354-358 and 369-373). The value is left-padded to 10
     * bytes first so a short entry fails exactly as the COBOL fixed field would.
     *
     * @param value the raw date input (may be {@code null})
     * @return {@code true} when the positions are digit/digit-digit-digit-digit with dashes at 5 and 8
     */
    private static boolean isValidDateFormat(String value) {
        String fixed = padRight(value, DATE_FIELD_LENGTH);
        if (!isAllDigits(fixed, 0, 4)) {
            return false;
        }
        if (fixed.charAt(4) != '-') {
            return false;
        }
        if (!isAllDigits(fixed, 5, 7)) {
            return false;
        }
        if (fixed.charAt(7) != '-') {
            return false;
        }
        return isAllDigits(fixed, 8, 10);
    }

    /**
     * Parses the validated fixed-layout amount into a scale-2 {@link BigDecimal} - the COBOL
     * {@code COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI)} ({@code legacy/cbl/COTRN02C.cbl}
     * lines 383-384 and 456-457). The value is scaled with {@link CobolDecimal#money(BigDecimal)}
     * ({@link java.math.RoundingMode#DOWN}, matching the {@code S9(9)V99} field with no {@code ROUNDED}
     * clause, AAP &sect;0.6.1). The caller guarantees the value has passed
     * {@link #isValidAmountFormat(String)}, so a plain {@link BigDecimal} parse is exact.
     *
     * @param value the validated 12-byte amount string
     * @return the parsed, scale-2 amount
     */
    private static BigDecimal parseAmount(String value) {
        String fixed = padRight(value, AMOUNT_FIELD_LENGTH).substring(0, AMOUNT_FIELD_LENGTH);
        return CobolDecimal.money(new BigDecimal(fixed));
    }

    /**
     * Renders an amount through the COBOL {@code WS-TRAN-AMT-E PIC +99999999.99} edit mask - a leading
     * sign, eight integer digits, a dot, and two fraction digits ({@code legacy/cbl/COTRN02C.cbl}
     * lines 385-386 and 481). The value is scaled to two places with truncation before formatting.
     *
     * @param amount the amount to render ({@code null} is treated as zero)
     * @return the 12-character edit-masked amount (e.g. {@code +00000012.34})
     */
    private static String formatAmountEditMask(BigDecimal amount) {
        BigDecimal scaled = CobolDecimal.money(CobolDecimal.nullToZero(amount));
        char sign = (scaled.signum() < 0) ? '-' : '+';
        long totalCents = scaled.abs().movePointRight(2).longValueExact();
        long integerDigits = totalCents / 100L;
        long fraction = totalCents % 100L;
        return String.format(Locale.ROOT, "%c%08d.%02d", sign, integerDigits, fraction);
    }

    /**
     * Parses a validated {@code YYYY-MM-DD} string into a {@link LocalDateTime} at start-of-day - the
     * COBOL {@code MOVE TORIGDTI/TPROCDTI TO TRAN-ORIG-TS/TRAN-PROC-TS} ({@code legacy/cbl/COTRN02C.cbl}
     * lines 464-465). The time component is midnight, reproducing the COBOL storage of a bare date in
     * the 26-byte timestamp field. The caller guarantees the value has passed
     * {@link #isValidDateFormat(String)} and {@link DateConversionService} validation.
     *
     * @param value the validated 10-character date string
     * @return the date at start-of-day
     */
    private static LocalDateTime parseFormDate(String value) {
        String fixed = padRight(value, DATE_FIELD_LENGTH).substring(0, DATE_FIELD_LENGTH);
        return LocalDate.parse(fixed, ISO_DATE).atStartOfDay();
    }

    /**
     * Renders the date portion of a {@link LocalDateTime} as {@code YYYY-MM-DD} - the COBOL
     * {@code MOVE TRAN-ORIG-TS/TRAN-PROC-TS TO TORIGDTI/TPROCDTI} of {@code COPY-LAST-TRAN-DATA}
     * ({@code legacy/cbl/COTRN02C.cbl} lines 486-487).
     *
     * @param timestamp the timestamp to render (may be {@code null})
     * @return the {@code YYYY-MM-DD} date string, or the empty string when {@code timestamp} is
     *         {@code null}
     */
    private static String formatFormDate(LocalDateTime timestamp) {
        return (timestamp == null) ? "" : timestamp.toLocalDate().format(ISO_DATE);
    }

    /**
     * Formats a numeric value zero-padded to a fixed width, reproducing a COBOL {@code MOVE} of a
     * numeric value into a display field of {@code PIC 9(width)}.
     *
     * @param value the value to format
     * @param width the fixed field width
     * @return the zero-padded string
     */
    private static String formatFixedNumber(long value, int width) {
        return String.format(Locale.ROOT, "%0" + width + "d", value);
    }

    /**
     * Zero-pads a nullable {@link Long} to a fixed width, yielding the empty string for {@code null}.
     *
     * @param value the value to format (may be {@code null})
     * @param width the fixed field width
     * @return the zero-padded string, or the empty string when {@code value} is {@code null}
     */
    private static String formatOptionalNumber(Long value, int width) {
        return (value == null) ? "" : formatFixedNumber(value.longValue(), width);
    }

    /**
     * Zero-pads a nullable {@link Integer} to a fixed width, yielding the empty string for
     * {@code null}.
     *
     * @param value the value to format (may be {@code null})
     * @param width the fixed field width
     * @return the zero-padded string, or the empty string when {@code value} is {@code null}
     */
    private static String formatOptionalNumber(Integer value, int width) {
        return (value == null) ? "" : formatFixedNumber(value.longValue(), width);
    }

    /**
     * Parses a stored 16-digit transaction id to a {@code long} for the {@code + 1} derivation,
     * tolerating {@code null}/blank (yielding {@code 0}).
     *
     * @param tranId the stored transaction id (may be {@code null})
     * @return the numeric value, or {@code 0} when blank
     */
    private static long parseTranId(String tranId) {
        String trimmed = (tranId == null) ? "" : tranId.trim();
        return trimmed.isEmpty() ? 0L : Long.parseLong(trimmed);
    }

    /**
     * Assembles the COBOL {@code STRING}-built success message byte-for-byte - the concatenation of
     * {@link #SUCCESS_PART1}, {@link #SUCCESS_PART2}, the new transaction id, and
     * {@link #SUCCESS_SUFFIX} ({@code legacy/cbl/COTRN02C.cbl} lines 718-724). The join of parts 1 and
     * 2 reproduces the exact double space between "successfully." and "Your".
     *
     * @param tranId the new transaction id
     * @return the exact success message
     */
    private static String buildSuccessMessage(String tranId) {
        return SUCCESS_PART1 + SUCCESS_PART2 + tranId + SUCCESS_SUFFIX;
    }

    // -- Result contract types --------------------------------------------------------------------

    /**
     * Screen action returned by the business methods, modeling the two terminal actions of
     * {@code COTRN02C}: re-display the add-transaction screen ({@code SEND-TRNADD-SCREEN}) or transfer
     * control to another program ({@code EXEC CICS XCTL}).
     */
    public enum ScreenAction {

        /** Re-display the add-transaction screen (the COBOL {@code SEND-TRNADD-SCREEN}). */
        SHOW_SCREEN,

        /**
         * Hand off to the program recorded in {@link CardDemoContext#getToProgram()} (the COBOL
         * {@code XCTL}); the controller performs the redirect.
         */
        REDIRECT
    }

    /**
     * Severity of the message accompanying a {@link ScreenAction#SHOW_SCREEN}, preserving the COBOL
     * color contract: errors use the default (red) attribute driven by {@code WS-ERR-FLG}, while the
     * successful-add note is explicitly green ({@code MOVE DFHGREEN TO ERRMSGC}, COBOL line 728).
     */
    public enum MessageSeverity {

        /** No message to display. */
        NONE,

        /** Error message (COBOL {@code WS-ERR-FLG} on); rendered in the error color. */
        ERROR,

        /** Informational message (COBOL {@code DFHGREEN}); rendered in green. */
        INFORMATION
    }

    /**
     * Immutable outcome of a business method: the {@link ScreenAction} to take, the message to display
     * (empty when none), the message {@link MessageSeverity}, and the cursor field (the BMS input id
     * of the COBOL {@code MOVE -1 TO xxxxL}; empty when the branch sets no cursor).
     *
     * <p>The controller inspects {@link #action()} to choose between issuing a redirect (using
     * {@link CardDemoContext#getToProgram()}) and re-rendering the screen; when re-rendering it shows
     * {@link #message()} using the styling implied by {@link #severity()} and positions the cursor on
     * {@link #cursorField()}.</p>
     *
     * @param action      the screen action; must not be {@code null}
     * @param message     the message text; normalized to {@code ""} when {@code null}
     * @param severity    the message severity; must not be {@code null}
     * @param cursorField the BMS input id to receive the cursor; normalized to {@code ""} when
     *                    {@code null}
     */
    public record TransactionAddResult(ScreenAction action, String message, MessageSeverity severity,
                                       String cursorField) {

        /**
         * Canonical constructor enforcing non-null {@code action}/{@code severity} and normalizing a
         * {@code null} {@code message}/{@code cursorField} to the empty string.
         *
         * @param action      the screen action; must not be {@code null}
         * @param message     the message text; {@code null} becomes {@code ""}
         * @param severity    the message severity; must not be {@code null}
         * @param cursorField the cursor field id; {@code null} becomes {@code ""}
         */
        public TransactionAddResult {
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            if (severity == null) {
                throw new IllegalArgumentException("severity must not be null");
            }
            if (message == null) {
                message = "";
            }
            if (cursorField == null) {
                cursorField = "";
            }
        }

        /**
         * Creates a plain screen re-display with no message (the COBOL {@code SEND-TRNADD-SCREEN} with
         * no error), positioning the cursor on {@code cursorField}.
         *
         * @param cursorField the BMS input id to receive the cursor
         * @return a {@link ScreenAction#SHOW_SCREEN} result with {@link MessageSeverity#NONE}
         */
        public static TransactionAddResult showScreen(String cursorField) {
            return new TransactionAddResult(ScreenAction.SHOW_SCREEN, "", MessageSeverity.NONE, cursorField);
        }

        /**
         * Creates a screen re-display carrying an error message (the COBOL {@code WS-ERR-FLG} path).
         *
         * @param message     the error text to display
         * @param cursorField the BMS input id to receive the cursor
         * @return a {@link ScreenAction#SHOW_SCREEN} result with {@link MessageSeverity#ERROR}
         */
        public static TransactionAddResult error(String message, String cursorField) {
            return new TransactionAddResult(ScreenAction.SHOW_SCREEN, message, MessageSeverity.ERROR, cursorField);
        }

        /**
         * Creates a screen re-display carrying the green success message (the COBOL {@code DFHGREEN}
         * path). The cursor is placed on the account-id field, matching
         * {@code INITIALIZE-ALL-FIELDS} ({@code MOVE -1 TO ACTIDINL}).
         *
         * @param message the success text to display
         * @return a {@link ScreenAction#SHOW_SCREEN} result with {@link MessageSeverity#INFORMATION}
         */
        public static TransactionAddResult success(String message) {
            return new TransactionAddResult(ScreenAction.SHOW_SCREEN, message, MessageSeverity.INFORMATION,
                    CURSOR_ACTIDIN);
        }

        /**
         * Creates a redirect outcome with no message (the COBOL {@code XCTL}); the target program is
         * recorded on the {@link CardDemoContext}.
         *
         * @return a {@link ScreenAction#REDIRECT} result with {@link MessageSeverity#NONE}
         */
        public static TransactionAddResult redirect() {
            return new TransactionAddResult(ScreenAction.REDIRECT, "", MessageSeverity.NONE, CURSOR_NONE);
        }
    }

}

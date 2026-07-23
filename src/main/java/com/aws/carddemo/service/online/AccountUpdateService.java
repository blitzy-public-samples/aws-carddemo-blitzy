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

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.enums.LookupCodes;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COACTUPForm;
import com.aws.carddemo.exception.LogicError;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.util.CobolDecimal;
import com.aws.carddemo.util.DateConversionSupport;
import com.aws.carddemo.util.DateConversionSupport.DateEditResult;
import com.aws.carddemo.util.DateConversionSupport.FieldFlag;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import io.micrometer.observation.annotation.Observed;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Account-update service - the dual-entity read-update-rewrite archetype.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COACTUPC.cbl} (CICS COBOL program
 * {@code COACTUPC}, transaction id {@code CAUP}). This class is the faithful Java
 * migration of the online <em>Account Update</em> program - the largest and most
 * complex online program in AWS CardDemo (4236 lines, ~78 paragraphs). It
 * preserves the COBOL control flow one-for-one: each numbered business
 * {@code PARAGRAPH} becomes exactly one method (AAP &sect;0.3.3, service layer;
 * AAP &sect;0.4.1, online-programs table {@code COACTUPC.cbl -> AccountUpdateService
 * + AccountController} update, tran {@code CAUP}).</p>
 *
 * <h2>Archetype: dual-entity read-update-rewrite with optimistic lock</h2>
 * <p>{@code COACTUPC} fetches an {@code ACCTDAT} record and its associated
 * {@code CUSTDAT} record (joined through the {@code CXACAIX} account-path
 * alternate index over {@code CCXREF}), lets the operator edit both, and - on
 * confirmation - re-reads both records under an update lock, verifies neither was
 * changed by another actor since the fetch (the COBOL optimistic-lock compare),
 * and rewrites both. The rewrite of {@link Account} and {@link Customer} is a
 * single {@code @Transactional} unit-of-work: both succeed or both roll back,
 * reproducing the CICS {@code REWRITE ... REWRITE ... SYNCPOINT ROLLBACK}
 * sequence (AAP &sect;0.3.3, {@code @Transactional} unit-of-work).</p>
 *
 * <h2>Presentation split (what this service does NOT do)</h2>
 * <p>The {@code 3xxx} paragraphs ({@code 3000-SEND-MAP},
 * {@code SETUP-SCREEN-ATTRS}, {@code PROTECT-ALL-ATTRS},
 * {@code UNPROTECT-FEW-ATTRS}, {@code SHOW-*-VALUES}) are pure 3270/BMS
 * presentation and are owned by the paired {@code AccountController} and the
 * Thymeleaf view. This service contains no {@code SEND}/{@code RECEIVE}/attribute
 * logic; it focuses on field edits, the {@code 2000-DECIDE-ACTION} state machine,
 * the read chain, and the dual-entity transactional write. The controller binds
 * the {@link COACTUPForm} (the {@code RECEIVE MAP}), stores the returned
 * {@link AccountUpdateState} in the HTTP session, and renders the screen using the
 * {@link AccountUpdateResult}.</p>
 *
 * <table border="1">
 *   <caption>COACTUPC paragraph &rarr; Java member mapping (business paragraphs)</caption>
 *   <tr><th>COBOL paragraph</th><th>Java member</th></tr>
 *   <tr><td>{@code 0000-MAIN}</td><td>{@link #process}</td></tr>
 *   <tr><td>{@code 1000-PROCESS-INPUTS}</td><td>{@link #processInputs}</td></tr>
 *   <tr><td>{@code 1100-RECEIVE-MAP} (normalization)</td><td>{@link #captureNewDetails}</td></tr>
 *   <tr><td>{@code 1200-EDIT-MAP-INPUTS}</td><td>{@link #editMapInputs}</td></tr>
 *   <tr><td>{@code 1205-COMPARE-OLD-NEW}</td><td>{@link #compareOldNew}</td></tr>
 *   <tr><td>{@code 1210-EDIT-ACCOUNT}</td><td>{@link #editAccount}</td></tr>
 *   <tr><td>{@code 1215-EDIT-MANDATORY}</td><td>{@link #editMandatory}</td></tr>
 *   <tr><td>{@code 1220-EDIT-YESNO}</td><td>{@link #editYesNo}</td></tr>
 *   <tr><td>{@code 1225-EDIT-ALPHA-REQD}</td><td>{@link #editAlphaReqd}</td></tr>
 *   <tr><td>{@code 1230-EDIT-ALPHANUM-REQD}</td><td>{@link #editAlphanumReqd}</td></tr>
 *   <tr><td>{@code 1235-EDIT-ALPHA-OPT}</td><td>{@link #editAlphaOpt}</td></tr>
 *   <tr><td>{@code 1240-EDIT-ALPHANUM-OPT}</td><td>{@link #editAlphanumOpt}</td></tr>
 *   <tr><td>{@code 1245-EDIT-NUM-REQD}</td><td>{@link #editNumReqd}</td></tr>
 *   <tr><td>{@code 1250-EDIT-SIGNED-9V2}</td><td>{@link #editSigned9v2}</td></tr>
 *   <tr><td>{@code 1260-EDIT-US-PHONE-NUM}</td><td>{@link #editUsPhoneNum}</td></tr>
 *   <tr><td>{@code EDIT-AREA-CODE}</td><td>{@link #editAreaCode}</td></tr>
 *   <tr><td>{@code EDIT-US-PHONE-PREFIX}</td><td>{@link #editUsPhonePrefix}</td></tr>
 *   <tr><td>{@code EDIT-US-PHONE-LINENUM}</td><td>{@link #editUsPhoneLineNum}</td></tr>
 *   <tr><td>{@code 1265-EDIT-US-SSN}</td><td>{@link #editUsSsn}</td></tr>
 *   <tr><td>{@code 1270-EDIT-US-STATE-CD}</td><td>{@link #editUsStateCd}</td></tr>
 *   <tr><td>{@code 1275-EDIT-FICO-SCORE}</td><td>{@link #editFicoScore}</td></tr>
 *   <tr><td>{@code 1280-EDIT-US-STATE-ZIP-CD}</td><td>{@link #editUsStateZipCd}</td></tr>
 *   <tr><td>{@code 2000-DECIDE-ACTION}</td><td>{@link #decideAction}</td></tr>
 *   <tr><td>{@code 9000-READ-ACCT}</td><td>{@link #readAcct}</td></tr>
 *   <tr><td>{@code 9200-GETCARDXREF-BYACCT}</td><td>{@link #getCardXrefByAcct}</td></tr>
 *   <tr><td>{@code 9300-GETACCTDATA-BYACCT}</td><td>{@link #getAcctDataByAcct}</td></tr>
 *   <tr><td>{@code 9400-GETCUSTDATA-BYCUST}</td><td>{@link #getCustDataByCust}</td></tr>
 *   <tr><td>{@code 9500-STORE-FETCHED-DATA}</td><td>{@link #storeFetchedData}</td></tr>
 *   <tr><td>{@code 9600-WRITE-PROCESSING}</td><td>{@link #writeProcessing}</td></tr>
 *   <tr><td>{@code 9700-CHECK-CHANGE-IN-REC}</td><td>{@link #checkChangeInRec}</td></tr>
 *   <tr><td>{@code ABEND-ROUTINE}</td><td>{@link #abendRoutine}</td></tr>
 *   <tr><td>{@code 3xxx-*} (SEND/SETUP/SHOW/attributes)</td><td>&mdash; controller / view</td></tr>
 * </table>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>{@code COACTUPC} is pseudo-conversational: it ends every interaction with
 * {@code EXEC CICS RETURN TRANSID(CAUP) COMMAREA(...)}. The COMMAREA splits into
 * the shared navigation area {@code COCOM01Y} (the session-scoped
 * {@link CardDemoContext}) and the program-private {@code WS-THIS-PROGCOMMAREA}
 * carrying {@code ACUP-CHANGE-ACTION} and the {@code ACUP-OLD-DETAILS} snapshot -
 * modeled here by {@link AccountUpdateState}, which the controller stores in the
 * HTTP session and passes back on the next request. The {@code ACUP-NEW-DETAILS}
 * are <em>not</em> persisted; they are rebuilt from the submitted form on every
 * request ({@link #captureNewDetails(ProcessingState)}), exactly as
 * {@code 1100-RECEIVE-MAP} reconstructs them from the map each time.</p>
 *
 * <h2>Comparison semantics and the transport-artifact parity decision (AAP &sect;0.2.2, &sect;0.6)</h2>
 * <p>{@code 1205-COMPARE-OLD-NEW} and {@code 9700-CHECK-CHANGE-IN-REC} decide
 * "did the operator change any field versus the fetched baseline". The COBOL
 * performs fixed-width byte comparisons on {@code ACUP-*} fields; for money fields
 * both sides hold the identical {@code S9(10)V99} zoned representation (so the byte
 * compare equals a numeric compare), and text fields are compared through
 * {@code FUNCTION UPPER-CASE}/{@code FUNCTION TRIM}. This migration reproduces that
 * <em>business</em> outcome with normalized comparisons - {@link BigDecimal}
 * {@code compareTo} at scale 2 for money, normalized {@code yyyyMMdd} for dates,
 * uppercase+strip for the {@code UPPER-CASE}/{@code TRIM} fields, and strip-equality
 * for the raw digit fields - rather than reproducing the {@code LOW-VALUES}
 * (x'00') versus {@code SPACES} byte artifacts of the 3270 transport, which are
 * meaningless under HTML-form binding. A not-supplied field normalizes to the
 * empty value so a blank-vs-blank comparison yields "no change" and a
 * blank-vs-data comparison yields "change".</p>
 *
 * <h2>Exception mapping (AAP &sect;0.6.5)</h2>
 * <p>The COBOL handles {@code NOTFND} gracefully by setting re-display flags and a
 * message (never abending), so record-not-found is <em>not</em> surfaced as an
 * exception here - it drives the {@code FLG-*FILTER-NOT-OK} re-display path exactly
 * as in the source. {@link LogicError} is reserved for the {@code ABEND-ROUTINE}
 * ("unexpected data scenario") equivalent. Lock/update failures during the write
 * are reported as {@link WriteResult} states that re-display the screen, matching
 * the COBOL {@code COULD-NOT-LOCK-*}/{@code LOCKED-BUT-UPDATE-FAILED} handling.</p>
 *
 * <h2>Collaborators (AAP &sect;0.4.2, &sect;0.5.3)</h2>
 * <p>Constructor-injects five {@code private final} collaborators: the
 * session-scoped {@link CardDemoContext} (COMMAREA replacement), the three
 * repositories {@link CardXrefRepository} ({@code CXACAIX} path -&gt;
 * {@code findByXrefAcctId}), {@link AccountRepository} ({@code ACCTDAT}) and
 * {@link CustomerRepository} ({@code CUSTDAT}), and {@link DateConversionSupport}
 * (the migrated {@code CSUTLDPY}/{@code CSUTLDWY} date edits). Money arithmetic and
 * comparison use the static {@link CobolDecimal} helpers ({@code BigDecimal}, scale
 * 2, {@code RoundingMode.DOWN}); state-code, ZIP-combo and area-code validation use
 * the static {@link LookupCodes} (migrated {@code CSLKPCDY}).</p>
 *
 * <p>Plain Java, explicit accessors, no Lombok, no emoji; compiles warning-free
 * under {@code --release 25} with {@code -Xlint:all}.</p>
 *
 * @see CardDemoContext
 * @see COACTUPForm
 * @see AccountUpdateState
 * @see AccountUpdateResult
 */
@Service
public class AccountUpdateService {

    /** COBOL {@code LIT-THISPGM PIC X(8) VALUE 'COACTUPC'} - this program's name. */
    private static final String PROGRAM_NAME = "COACTUPC";

    /** COBOL {@code LIT-THISTRANID PIC X(4) VALUE 'CAUP'} - this program's transaction id. */
    private static final String TRANSACTION_ID = "CAUP";

    /** COBOL {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTUP '} - this program's BMS mapset. */
    private static final String MAPSET_NAME = "COACTUP";

    /** COBOL {@code LIT-THISMAP PIC X(7) VALUE 'CACTUPA'} - this program's BMS map. */
    private static final String MAP_NAME = "CACTUPA";

    /** COBOL {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} - the main-menu program (PF3 default and first-entry origin). */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** COBOL {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} - the main-menu transaction id (PF3 default target). */
    private static final String MENU_TRANSACTION_ID = "CM00";

    /** {@code ACCTDAT} logical file name (COBOL {@code LIT-ACCTFILENAME}); used in the account read/rewrite error text. */
    private static final String ACCT_FILE_NAME = "ACCTDAT";

    /** {@code CUSTDAT} logical file name (COBOL {@code LIT-CUSTFILENAME}); used in the customer read/rewrite error text. */
    private static final String CUST_FILE_NAME = "CUSTDAT";

    /** {@code CXACAIX} account-path alternate index name (COBOL {@code LIT-CARDXREFNAME-ACCT-PATH}); used in the xref error text. */
    private static final String XREF_ACCT_PATH_NAME = "CXACAIX";

    // ----- Field widths (fixed COBOL PIC lengths driving IS NUMERIC / mandatory tests) -----

    /** {@code ACUP-NEW-ACCT-ID-X PIC X(11)} / {@code ACCT-ID 9(11)} - account key width. */
    private static final int ACCT_ID_WIDTH = 11;

    /** {@code CDEMO-CARD-NUM PIC X(16)} - card key width (used to image {@code MOVE ZEROES}). */
    private static final int CARD_NUM_WIDTH = 16;

    /** {@code ACUP-NEW-CUST-ID-X PIC X(09)} - customer key width. */
    private static final int CUST_ID_WIDTH = 9;

    /** {@code SSN} part 1 width (first 3 chars). */
    private static final int SSN_PART1_WIDTH = 3;

    /** {@code SSN} part 2 width (4th and 5th chars). */
    private static final int SSN_PART2_WIDTH = 2;

    /** {@code SSN} part 3 width (last 4 chars). */
    private static final int SSN_PART3_WIDTH = 4;

    /** {@code FICO Score} width ({@code PIC 9(03)}). */
    private static final int FICO_WIDTH = 3;

    /** First / middle / last name width ({@code PIC X(25)}). */
    private static final int NAME_WIDTH = 25;

    /** Address-line width ({@code PIC X(50)}). */
    private static final int ADDRESS_WIDTH = 50;

    /** State-code width ({@code PIC X(02)}). */
    private static final int STATE_WIDTH = 2;

    /** ZIP width edited by {@code 1245-EDIT-NUM-REQD} ({@code MOVE 5 TO WS-EDIT-ALPHANUM-LENGTH}). */
    private static final int ZIP_EDIT_WIDTH = 5;

    /** Country-code width ({@code PIC X(03)}). */
    private static final int COUNTRY_WIDTH = 3;

    /** EFT account id width ({@code PIC X(10)}). */
    private static final int EFT_WIDTH = 10;

    /** Composed {@code CCYYMMDD} date width ({@code PIC X(08)}) - open / expiry / reissue / date-of-birth. */
    private static final int DATE_WIDTH = 8;

    /** Composed SSN width ({@code PIC X(09)}) - the three SSN parts concatenated. */
    private static final int SSN_WIDTH = 9;

    /** Account active-status width ({@code ACCT-ACTIVE-STATUS PIC X(01)}) - used by the {@code 9700} re-read compare. */
    private static final int STATUS_WIDTH = 1;

    /** Account group-id width ({@code ACCT-GROUP-ID PIC X(10)}) - {@code 9700} compares {@code LOWER-CASE}. */
    private static final int GROUP_WIDTH = 10;

    /** Customer government-issued-id width ({@code CUST-GOVT-ISSUED-ID PIC X(20)}). */
    private static final int GOVT_ID_WIDTH = 20;

    /** Customer phone-number width ({@code CUST-PHONE-NUM-n PIC X(15)}). */
    private static final int PHONE_WIDTH = 15;

    /** Customer address-zip width ({@code CUST-ADDR-ZIP PIC X(10)}) - distinct from the 5-digit edit width. */
    private static final int ADDR_ZIP_WIDTH = 10;

    /** Primary-card-holder indicator width ({@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}). */
    private static final int PRI_HOLDER_WIDTH = 1;

    // ----- Field labels (COBOL WS-EDIT-VARIABLE-NAME literals, verbatim) -----

    private static final String LBL_ACCT_STATUS = "Account Status";
    private static final String LBL_OPEN_DATE = "Open Date";
    private static final String LBL_CREDIT_LIMIT = "Credit Limit";
    private static final String LBL_EXPIRY_DATE = "Expiry Date";
    private static final String LBL_CASH_CREDIT_LIMIT = "Cash Credit Limit";
    private static final String LBL_REISSUE_DATE = "Reissue Date";
    private static final String LBL_CURRENT_BALANCE = "Current Balance";
    private static final String LBL_CURRENT_CYCLE_CREDIT = "Current Cycle Credit Limit";
    private static final String LBL_CURRENT_CYCLE_DEBIT = "Current Cycle Debit Limit";
    private static final String LBL_SSN_PART1 = "SSN: First 3 chars";
    private static final String LBL_SSN_PART2 = "SSN 4th & 5th chars";
    private static final String LBL_SSN_PART3 = "SSN Last 4 chars";
    private static final String LBL_DATE_OF_BIRTH = "Date of Birth";
    private static final String LBL_FICO_SCORE = "FICO Score";
    private static final String LBL_FIRST_NAME = "First Name";
    private static final String LBL_MIDDLE_NAME = "Middle Name";
    private static final String LBL_LAST_NAME = "Last Name";
    private static final String LBL_ADDRESS_LINE_1 = "Address Line 1";
    private static final String LBL_STATE = "State";
    private static final String LBL_ZIP = "Zip";
    private static final String LBL_CITY = "City";
    private static final String LBL_COUNTRY = "Country";
    private static final String LBL_PHONE_NUM_1 = "Phone Number 1";
    private static final String LBL_PHONE_NUM_2 = "Phone Number 2";
    private static final String LBL_EFT_ACCOUNT_ID = "EFT Account Id";
    private static final String LBL_PRIMARY_CARD_HOLDER = "Primary Card Holder";

    // ----- Message literals (COBOL 88-level VALUEs and STRING-built messages, verbatim) -----

    /** {@code WS-PROMPT-FOR-ACCT VALUE 'Account number not provided'} (COBOL line 484). */
    private static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED VALUE 'No input received'} (COBOL line 490). */
    private static final String MSG_NO_INPUT_RECEIVED = "No input received";

    /** {@code NO-CHANGES-DETECTED VALUE 'No change detected with respect to values fetched.'} (COBOL line 492). */
    private static final String MSG_NO_CHANGES_DETECTED = "No change detected with respect to values fetched.";

    /** COBOL {@code STRING}-built account-filter message (COBOL lines 1808-1810). */
    private static final String MSG_ACCT_FILTER_INVALID =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

    /** {@code FOUND-ACCOUNT-DATA VALUE 'Details of selected account shown above'} (COBOL line 467). */
    private static final String MSG_FOUND_ACCOUNT_DATA = "Details of selected account shown above";

    /** {@code PROMPT-FOR-SEARCH-KEYS VALUE 'Enter or update id of account to update'} (COBOL line 469). */
    private static final String MSG_PROMPT_FOR_SEARCH_KEYS = "Enter or update id of account to update";

    /** {@code PROMPT-FOR-CHANGES VALUE 'Update account details presented above.'} (COBOL line 471). */
    private static final String MSG_PROMPT_FOR_CHANGES = "Update account details presented above.";

    /** {@code PROMPT-FOR-CONFIRMATION VALUE 'Changes validated.Press F5 to save'} (COBOL line 473). */
    private static final String MSG_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** {@code CONFIRM-UPDATE-SUCCESS VALUE 'Changes committed to database'} (COBOL line 475). */
    private static final String MSG_CONFIRM_UPDATE_SUCCESS = "Changes committed to database";

    /** {@code INFORM-FAILURE VALUE 'Changes unsuccessful. Please try again'} (COBOL line 477). */
    private static final String MSG_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    /** {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE VALUE 'Could not lock account record for update'} (COBOL line 517). */
    private static final String MSG_COULD_NOT_LOCK_ACCT = "Could not lock account record for update";

    /** {@code COULD-NOT-LOCK-CUST-FOR-UPDATE VALUE 'Could not lock customer record for update'} (COBOL line 519). */
    private static final String MSG_COULD_NOT_LOCK_CUST = "Could not lock customer record for update";

    /** {@code DATA-WAS-CHANGED-BEFORE-UPDATE VALUE 'Record changed by some one else. Please review'} (COBOL line 521). */
    private static final String MSG_DATA_CHANGED = "Record changed by some one else. Please review";

    /** {@code LOCKED-BUT-UPDATE-FAILED VALUE 'Update of record failed'} (COBOL line 523). */
    private static final String MSG_UPDATE_FAILED = "Update of record failed";

    /** {@code WS-EXIT-MESSAGE VALUE 'PF03 pressed.Exiting'} (COBOL line 481). */
    private static final String MSG_PF3_EXITING = "PF03 pressed.Exiting";

    /**
     * Banner shown when a {@code PF5} confirmation arrives with a missing, forged, or
     * replayed confirmation token (review finding #10). This has no COBOL source line -
     * on the mainframe the confirmation fields are protected ({@code ASKIP}) so an
     * out-of-band re-post is physically impossible; the browser cannot enforce that,
     * so the server rejects the write and re-issues a fresh single-use token, asking
     * the operator to confirm again. Kept terse and screen-safe to match the
     * surrounding 78-character {@code WS-RETURN-MSG} banner style.
     */
    private static final String MSG_CONFIRM_INTEGRITY =
            "Confirmation could not be validated. Please review and press F5 again.";

    /**
     * Cryptographically strong source for the single-use confirmation tokens issued
     * by {@link #newConfirmToken()} (review finding #10). One shared
     * {@link SecureRandom} instance is thread-safe and is the standard idiom for
     * anti-replay / anti-double-submit nonces.
     */
    private static final SecureRandom CONFIRM_TOKEN_RNG = new SecureRandom();

    /** Byte length of a raw confirmation token (256 bits) before hex encoding. */
    private static final int CONFIRM_TOKEN_BYTES = 32;

    /**
     * CICS {@code DFHRESP(NOTFND)} response value (13), surfaced in the read
     * not-found banners in place of the runtime {@code WS-RESP-CD}. The CICS
     * response/reason codes have no Java-runtime equivalent, so their
     * {@code DFHRESP} constant value is the faithful mapping.
     */
    private static final String CICS_RESP_NOTFND = "13";

    /** CICS reason code (0) surfaced alongside {@link #CICS_RESP_NOTFND} in the read not-found banners. */
    private static final String CICS_REAS_ZERO = "0";

    /** {@code ABEND-ROUTINE} unexpected-scenario abend code (COBOL {@code MOVE '0001' TO ABEND-CODE}). */
    private static final String ABEND_CODE_UNEXPECTED = "0001";

    /** {@code ABEND-ROUTINE} unexpected-scenario abend message (COBOL {@code MOVE 'UNEXPECTED DATA SCENARIO' TO ABEND-REASON}). */
    private static final String ABEND_MSG_UNEXPECTED = "UNEXPECTED DATA SCENARIO";

    /** {@code ABEND-ROUTINE} default abend message (COBOL {@code MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG} when {@code ABEND-MSG} is {@code LOW-VALUES}, COBOL line 4206). */
    private static final String ABEND_MSG_DEFAULT = "UNEXPECTED ABEND OCCURRED.";

    /** CICS transaction abend code raised by {@code ABEND-ROUTINE} ({@code EXEC CICS ABEND ABCODE('9999')}, COBOL line 4223). */
    private static final String ABEND_ABCODE = "9999";

    // ----- Program / transaction / map literals (COBOL WS-LITERALS, COBOL lines 531-560) -----

    /** {@code LIT-THISPGM VALUE 'COACTUPC'} (COBOL line 533) - this program id (the {@code ABEND-CULPRIT} and the {@code CDEMO-FROM-PROGRAM} hand-off on PF3 exit). */
    private static final String LIT_THISPGM = "COACTUPC";

    /** {@code LIT-THISTRANID VALUE 'CAUP'} (COBOL line 535) - this CICS transaction id. */
    private static final String LIT_THISTRANID = "CAUP";

    /** {@code LIT-THISMAPSET VALUE 'COACTUP '} (COBOL line 537, {@code PIC X(8)}) - the account-update BMS mapset, stored trimmed. */
    private static final String LIT_THISMAPSET = "COACTUP";

    /** {@code LIT-THISMAP VALUE 'CACTUPA'} (COBOL line 539) - the account-update BMS map. */
    private static final String LIT_THISMAP = "CACTUPA";

    /** {@code LIT-MENUPGM VALUE 'COMEN01C'} (COBOL line 557) - the main-menu program (the default PF3 return target and the fresh-entry sentinel). */
    private static final String LIT_MENUPGM = "COMEN01C";

    /** {@code LIT-MENUTRANID VALUE 'CM00'} (COBOL line 559) - the main-menu transaction id (the default PF3 return transaction). */
    private static final String LIT_MENUTRANID = "CM00";

    /** Formatter reproducing the 8-character {@code CCYYMMDD} representation used by the {@code ACUP-*-DATE} fields. */
    private static final DateTimeFormatter CCYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Session-scoped {@code COMMAREA} replacement ({@code COPY COCOM01Y}). Carries
     * the navigation hand-off ({@code CDEMO-FROM/TO-PROGRAM}, {@code CDEMO-*-TRANID}),
     * the selected {@code CDEMO-ACCT-ID}/{@code CDEMO-CUST-ID}, the authenticated
     * identity and the enter/re-enter flags across pseudo-conversational
     * interactions.
     */
    private final CardDemoContext context;

    /**
     * {@code CCXREF} repository. The {@code 9200-GETCARDXREF-BYACCT} read uses the
     * {@code CXACAIX} account-path alternate index, migrated to
     * {@link CardXrefRepository#findByXrefAcctId(Long)}.
     */
    private final CardXrefRepository cardXrefRepository;

    /** {@code ACCTDAT} repository ({@link AccountRepository}); the account master read/rewrite. */
    private final AccountRepository accountRepository;

    /** {@code CUSTDAT} repository ({@link CustomerRepository}); the customer master read/rewrite. */
    private final CustomerRepository customerRepository;

    /**
     * Migrated {@code CSUTLDPY}/{@code CSUTLDWY} date edits ({@link DateConversionSupport}),
     * used for the open/expiry/reissue dates ({@code EDIT-DATE-CCYYMMDD}) and the
     * date of birth ({@code EDIT-DATE-OF-BIRTH}).
     */
    private final DateConversionSupport dateConversionSupport;

    /**
     * Creates the account-update service with its injected collaborators.
     *
     * <p>The constructor only stores the references (it invokes no overridable
     * method), so it is free of the {@code this-escape} lint category under the
     * zero-warning build.</p>
     *
     * @param context               the session-scoped {@link CardDemoContext} (COMMAREA replacement)
     * @param cardXrefRepository    the {@link CardXrefRepository} ({@code CCXREF}, {@code CXACAIX} path)
     * @param accountRepository     the {@link AccountRepository} ({@code ACCTDAT})
     * @param customerRepository    the {@link CustomerRepository} ({@code CUSTDAT})
     * @param dateConversionSupport the migrated date-edit support ({@code CSUTLDPY}/{@code CSUTLDWY})
     */
    public AccountUpdateService(CardDemoContext context,
                                CardXrefRepository cardXrefRepository,
                                AccountRepository accountRepository,
                                CustomerRepository customerRepository,
                                DateConversionSupport dateConversionSupport) {
        this.context = context;
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.dateConversionSupport = dateConversionSupport;
    }

    // ================================================================
    // Public nested types (state, result, enumerations)
    // ================================================================

    /**
     * Terminal action of {@code 0000-MAIN}: hand control back to the caller
     * ({@code EXEC CICS XCTL} / {@code RETURN} to the invoking program) or
     * re-display this program's screen ({@code 3000-SEND-MAP}).
     */
    public enum ScreenOutcome {

        /**
         * Transfer control to the program recorded in
         * {@link CardDemoContext#getToProgram()} (the COBOL {@code XCTL}); the
         * controller performs the redirect.
         */
        EXIT_TO_CALLER,

        /** Re-display the account-update screen (the COBOL {@code 3000-SEND-MAP}). */
        SHOW_SCREEN
    }

    /**
     * The account-update change action - migration of the COBOL
     * {@code ACUP-CHANGE-ACTION PIC X(1)} 88-levels (COBOL lines 651-673) that
     * drive the {@code 2000-DECIDE-ACTION} state machine and persist in the
     * program COMMAREA across pseudo-conversational turns.
     */
    public enum ChangeAction {

        /** {@code ACUP-DETAILS-NOT-FETCHED} ({@code VALUES LOW-VALUES, SPACES}): no record fetched yet. */
        DETAILS_NOT_FETCHED('\u0000'),

        /** {@code ACUP-SHOW-DETAILS} ({@code VALUE 'S'}): a record is fetched and displayed. */
        SHOW_DETAILS('S'),

        /** {@code ACUP-CHANGES-NOT-OK} ({@code VALUE 'E'}): edited data failed validation. */
        CHANGES_NOT_OK('E'),

        /** {@code ACUP-CHANGES-OK-NOT-CONFIRMED} ({@code VALUE 'N'}): edits valid, awaiting PF5 confirm. */
        CHANGES_OK_NOT_CONFIRMED('N'),

        /** {@code ACUP-CHANGES-OKAYED-AND-DONE} ({@code VALUE 'C'}): update committed. */
        CHANGES_OKAYED_AND_DONE('C'),

        /** {@code ACUP-CHANGES-OKAYED-LOCK-ERROR} ({@code VALUE 'L'}): could not lock a record for update. */
        CHANGES_OKAYED_LOCK_ERROR('L'),

        /** {@code ACUP-CHANGES-OKAYED-BUT-FAILED} ({@code VALUE 'F'}): locked but the rewrite failed. */
        CHANGES_OKAYED_BUT_FAILED('F');

        private final char code;

        ChangeAction(char code) {
            this.code = code;
        }

        /**
         * @return the underlying COBOL {@code ACUP-CHANGE-ACTION} character
         *         ({@code '\u0000'} models {@code LOW-VALUES} for the not-fetched state)
         */
        public char code() {
            return code;
        }

        /**
         * Reproduces {@code 88 ACUP-CHANGES-MADE VALUES 'E','N','C','L','F'}.
         *
         * @return {@code true} when a change action is in progress or complete
         */
        public boolean isChangesMade() {
            return this == CHANGES_NOT_OK
                    || this == CHANGES_OK_NOT_CONFIRMED
                    || this == CHANGES_OKAYED_AND_DONE
                    || this == CHANGES_OKAYED_LOCK_ERROR
                    || this == CHANGES_OKAYED_BUT_FAILED;
        }

        /**
         * Reproduces {@code 88 ACUP-CHANGES-FAILED VALUES 'L','F'}.
         *
         * @return {@code true} when a confirmed update failed (lock error or rewrite failure)
         */
        public boolean isChangesFailed() {
            return this == CHANGES_OKAYED_LOCK_ERROR || this == CHANGES_OKAYED_BUT_FAILED;
        }
    }

    /**
     * Severity of the message accompanying a {@link ScreenOutcome#SHOW_SCREEN},
     * preserving the COBOL colour contract: hard edit errors use the red
     * {@code WS-RETURN-MSG}/error attribute, informational notes (for example
     * {@code NO-CHANGES-DETECTED} or the {@code CONFIRM-UPDATE-SUCCESS} banner) use
     * the informational (green) attribute, and {@link #NONE} indicates no message.
     */
    public enum MessageSeverity {

        /** No message to display. */
        NONE,

        /** Error message (COBOL {@code WS-RETURN-MSG} edit failures); rendered in the error colour. */
        ERROR,

        /** Informational message (COBOL info banners); rendered in the informational colour. */
        INFORMATION
    }

    /**
     * Editable screen fields that carry a per-field validity {@link FieldFlag} for
     * the controller's attribute (colour) handling - the Java view of the COBOL
     * {@code WS-NON-KEY-FLAGS} sub-flags set by the {@code 12xx} edit paragraphs.
     * The controller uses these to reproduce the {@code SETUP-SCREEN-ATTRS}
     * red/turquoise field colouring without duplicating the edit logic.
     */
    public enum ScreenField {

        /** Account number filter field ({@code ACCTSIDI}; {@code FLG-ACCTFILTER}). */
        ACCOUNT_FILTER,
        /** Account active status ({@code ACSTTUS}; {@code WS-EDIT-ACCT-STATUS}). */
        ACCT_STATUS,
        /** Open date ({@code OPNyyy}; {@code WS-EDIT-OPEN-DATE-FLGS}). */
        OPEN_DATE,
        /** Credit limit ({@code ACRDLIM}; {@code WS-EDIT-CREDIT-LIMIT}). */
        CREDIT_LIMIT,
        /** Expiry date ({@code EXPyyy}; {@code WS-EXPIRY-DATE-FLGS}). */
        EXPIRY_DATE,
        /** Cash credit limit ({@code ACSHLIM}; {@code WS-EDIT-CASH-CREDIT-LIMIT}). */
        CASH_CREDIT_LIMIT,
        /** Reissue date ({@code RISyyy}; {@code WS-EDIT-REISSUE-DATE-FLGS}). */
        REISSUE_DATE,
        /** Current balance ({@code ACURBAL}; {@code WS-EDIT-CURR-BAL}). */
        CURRENT_BALANCE,
        /** Current cycle credit ({@code ACRCYCR}; {@code WS-EDIT-CURR-CYC-CREDIT}). */
        CURRENT_CYCLE_CREDIT,
        /** Current cycle debit ({@code ACRCYDB}; {@code WS-EDIT-CURR-CYC-DEBIT}). */
        CURRENT_CYCLE_DEBIT,
        /** SSN part 1 ({@code ACTSSN1}; {@code WS-EDIT-US-SSN-PART1-FLGS}). */
        SSN_PART1,
        /** SSN part 2 ({@code ACTSSN2}; {@code WS-EDIT-US-SSN-PART2-FLGS}). */
        SSN_PART2,
        /** SSN part 3 ({@code ACTSSN3}; {@code WS-EDIT-US-SSN-PART3-FLGS}). */
        SSN_PART3,
        /** Date of birth ({@code DOByyy}; {@code WS-EDIT-DT-OF-BIRTH-FLGS}). */
        DATE_OF_BIRTH,
        /** FICO score ({@code ACSTFCO}; {@code WS-EDIT-FICO-SCORE-FLGS}). */
        FICO_SCORE,
        /** First name ({@code ACSFNAM}; {@code WS-EDIT-FIRST-NAME-FLGS}). */
        FIRST_NAME,
        /** Middle name ({@code ACSMNAM}; {@code WS-EDIT-MIDDLE-NAME-FLGS}). */
        MIDDLE_NAME,
        /** Last name ({@code ACSLNAM}; {@code WS-EDIT-LAST-NAME-FLGS}). */
        LAST_NAME,
        /** Address line 1 ({@code ACSADL1}; {@code WS-EDIT-ADDRESS-LINE-1-FLGS}). */
        ADDRESS_LINE_1,
        /** State code ({@code ACSSTTE}; {@code WS-EDIT-STATE-FLGS}). */
        STATE,
        /** ZIP code ({@code ACSZIPC}; {@code WS-EDIT-ZIPCODE-FLGS}). */
        ZIP,
        /** City / address line 3 ({@code ACSCITY}; {@code WS-EDIT-CITY-FLGS}). */
        CITY,
        /** Country code ({@code ACSCTRY}; {@code WS-EDIT-COUNTRY-FLGS}). */
        COUNTRY,
        /** Phone number 1 ({@code ACSPH1x}; {@code WS-EDIT-PHONE-NUM-1-FLGS}). */
        PHONE_NUM_1,
        /** Phone number 2 ({@code ACSPH2x}; {@code WS-EDIT-PHONE-NUM-2-FLGS}). */
        PHONE_NUM_2,
        /** EFT account id ({@code ACSEFTC}; {@code WS-EFT-ACCOUNT-ID-FLGS}). */
        EFT_ACCOUNT_ID,
        /** Primary card holder indicator ({@code ACSPFLG}; {@code WS-EDIT-PRI-CARDHOLDER}). */
        PRIMARY_CARD_HOLDER
    }

    /**
     * Outcome of {@code 9600-WRITE-PROCESSING} - the state consumed by the inner
     * {@code EVALUATE TRUE} of {@code 2000-DECIDE-ACTION} (COBOL lines 2600-2620).
     */
    public enum WriteResult {

        /** All locks acquired, no concurrent change, both records rewritten (the {@code WHEN OTHER} success path). */
        SUCCESS,

        /** {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}: the account could not be locked for update. */
        COULD_NOT_LOCK_ACCT,

        /**
         * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}: the customer could not be locked for update.
         *
         * <p>Parity note (AAP &sect;0.2.2): the COBOL inner {@code EVALUATE} has no
         * explicit {@code WHEN} for the customer-lock failure, so it falls into
         * {@code WHEN OTHER} and yields {@code CHANGES-OKAYED-AND-DONE} even though
         * its "Could not lock customer" message was set. This quirk is preserved in
         * {@link #decideAction(PfKey, AccountUpdateState, ProcessingState)}.</p>
         */
        COULD_NOT_LOCK_CUST,

        /** {@code LOCKED-BUT-UPDATE-FAILED}: locked, but the {@code REWRITE} failed. */
        LOCKED_BUT_UPDATE_FAILED,

        /** {@code DATA-WAS-CHANGED-BEFORE-UPDATE}: a concurrent change was detected, re-display (not an error). */
        DATA_CHANGED
    }

    /**
     * Immutable outcome of {@link #process(COACTUPForm, PfKey, AccountUpdateState)}.
     *
     * <p>Because presentation is externalized to the controller, this record
     * describes everything the controller needs to render the next screen or issue
     * the redirect: the {@link ScreenOutcome}, the message and its
     * {@link MessageSeverity} (the COBOL {@code WS-RETURN-MSG}/info-banner shown on
     * the error line), whether an edit error occurred ({@code INPUT-ERROR}), the
     * resulting {@link ChangeAction} (so the controller can pick the correct screen
     * mode and protect/unprotect fields), and the per-field validity map used for
     * attribute colouring.</p>
     *
     * @param outcome      the terminal action; must not be {@code null}
     * @param message      the message text; normalized to {@code ""} when {@code null}
     * @param severity     the message severity; must not be {@code null}
     * @param inputError   {@code true} when at least one field failed validation ({@code INPUT-ERROR})
     * @param changeAction the resulting change action; must not be {@code null}
     * @param fieldFlags   per-field validity flags; copied defensively and never {@code null}
     */
    public record AccountUpdateResult(ScreenOutcome outcome,
                                      String message,
                                      MessageSeverity severity,
                                      boolean inputError,
                                      ChangeAction changeAction,
                                      Map<ScreenField, FieldFlag> fieldFlags) {

        /**
         * Canonical constructor enforcing non-null {@code outcome}/{@code severity}/
         * {@code changeAction}, normalizing a {@code null} {@code message} to the
         * empty string, and storing an unmodifiable defensive copy of
         * {@code fieldFlags}.
         */
        public AccountUpdateResult {
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
            if (severity == null) {
                throw new IllegalArgumentException("severity must not be null");
            }
            if (changeAction == null) {
                throw new IllegalArgumentException("changeAction must not be null");
            }
            if (message == null) {
                message = "";
            }
            EnumMap<ScreenField, FieldFlag> copy = new EnumMap<>(ScreenField.class);
            if (fieldFlags != null) {
                copy.putAll(fieldFlags);
            }
            fieldFlags = java.util.Collections.unmodifiableMap(copy);
        }

        /**
         * Creates an {@link ScreenOutcome#EXIT_TO_CALLER} outcome (the COBOL
         * {@code XCTL}), carrying an optional exit message.
         *
         * @param message the message text (for example the PF3 "exiting" note); may be {@code null}
         * @return an exit result with {@link MessageSeverity#INFORMATION} and no field flags
         */
        public static AccountUpdateResult exit(String message) {
            return new AccountUpdateResult(ScreenOutcome.EXIT_TO_CALLER, message,
                    MessageSeverity.INFORMATION, false, ChangeAction.DETAILS_NOT_FETCHED, null);
        }

        /**
         * Creates a {@link ScreenOutcome#SHOW_SCREEN} outcome.
         *
         * @param message      the message text; {@code null} becomes {@code ""}
         * @param severity     the message severity
         * @param inputError   whether an edit error occurred
         * @param changeAction the resulting change action
         * @param fieldFlags   per-field validity flags (may be {@code null})
         * @return a show-screen result
         */
        public static AccountUpdateResult show(String message,
                                               MessageSeverity severity,
                                               boolean inputError,
                                               ChangeAction changeAction,
                                               Map<ScreenField, FieldFlag> fieldFlags) {
            return new AccountUpdateResult(ScreenOutcome.SHOW_SCREEN, message, severity,
                    inputError, changeAction, fieldFlags);
        }
    }

    /**
     * Program-private pseudo-conversational state - the Java view of
     * {@code WS-THIS-PROGCOMMAREA} (the tail of the COACTUPC COMMAREA beyond the
     * shared {@code COCOM01Y}). It carries the {@code ACUP-CHANGE-ACTION} and the
     * {@code ACUP-OLD-DETAILS} snapshot across pseudo-conversational turns; the
     * controller stores it in the HTTP session and passes it back on each request.
     *
     * <p><b>Review finding #10 (confirmation integrity, CWE-20/CWE-639).</b> This
     * state now also carries the validated {@code ACUP-NEW-DETAILS} pending
     * snapshot and a single-use confirmation token across the ENTER&rarr;PF5 turns,
     * exactly as the COBOL {@code WS-THIS-PROGCOMMAREA} carries {@code
     * ACUP-NEW-DETAILS} (COACTUPC lines 689+). The earlier translation rebuilt the
     * new details from the submitted form on <em>every</em> request, so a crafted or
     * replayed PF5 could substitute unvalidated identity/values that were never
     * shown for confirmation. Carrying the server-side validated snapshot (and
     * committing it, not the PF5 re-post) restores the source behaviour where the
     * confirmation-state fields are protected and the confirmed values equal the
     * validated ones. The {@link #confirmToken} is issued when the state advances to
     * {@link ChangeAction#CHANGES_OK_NOT_CONFIRMED} and consumed (single-use) on the
     * PF5 commit, defeating replay/double-submit.</p>
     */
    public static final class AccountUpdateState implements Serializable {

        private static final long serialVersionUID = 2L;

        private ChangeAction changeAction;
        private AccountSnapshot oldSnapshot;

        /**
         * The validated {@code ACUP-NEW-DETAILS} pending snapshot captured when the
         * edits passed and the state advanced to
         * {@link ChangeAction#CHANGES_OK_NOT_CONFIRMED}. On the PF5 confirm turn the
         * service commits <em>this</em> server-carried snapshot, never the re-posted
         * client values (review finding #10). {@code null} outside the awaiting-confirm
         * window. Package-visible to the enclosing service only.
         */
        private NewInput pendingInput;

        /**
         * Single-use confirmation token bound to the {@link #pendingInput}. Issued
         * (a fresh random value) when the state advances to
         * {@link ChangeAction#CHANGES_OK_NOT_CONFIRMED}, echoed to the confirm screen
         * as a hidden field, and validated then cleared on the PF5 commit so a
         * replayed or forged confirmation is rejected (review finding #10).
         */
        private String confirmToken;

        /** Creates a fresh state in the {@code ACUP-DETAILS-NOT-FETCHED} condition. */
        public AccountUpdateState() {
            this.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
        }

        /** @return the current {@link ChangeAction} (never {@code null}) */
        public ChangeAction getChangeAction() {
            return changeAction;
        }

        /**
         * Sets the change action; a {@code null} resets to
         * {@link ChangeAction#DETAILS_NOT_FETCHED} (COBOL {@code MOVE LOW-VALUES}).
         *
         * @param changeAction the new change action
         */
        public void setChangeAction(ChangeAction changeAction) {
            this.changeAction = (changeAction == null) ? ChangeAction.DETAILS_NOT_FETCHED : changeAction;
        }

        /** @return the fetched-baseline snapshot ({@code ACUP-OLD-DETAILS}), or {@code null} when none is fetched */
        public AccountSnapshot getOldSnapshot() {
            return oldSnapshot;
        }

        /**
         * Stores the fetched-baseline snapshot.
         *
         * @param oldSnapshot the {@code ACUP-OLD-DETAILS} snapshot (may be {@code null})
         */
        public void setOldSnapshot(AccountSnapshot oldSnapshot) {
            this.oldSnapshot = oldSnapshot;
        }

        /**
         * @return the validated {@code ACUP-NEW-DETAILS} pending snapshot committed
         *     on the PF5 confirm turn, or {@code null} when not awaiting confirmation
         *     (review finding #10)
         */
        NewInput getPendingInput() {
            return pendingInput;
        }

        /**
         * Stores the validated pending snapshot when the state advances to
         * {@link ChangeAction#CHANGES_OK_NOT_CONFIRMED} (review finding #10).
         *
         * @param pendingInput the validated {@code ACUP-NEW-DETAILS} snapshot (may be {@code null})
         */
        void setPendingInput(NewInput pendingInput) {
            this.pendingInput = pendingInput;
        }

        /**
         * @return the single-use confirmation token bound to {@link #getPendingInput()},
         *     or {@code null} when not awaiting confirmation (review finding #10). Public
         *     so the controller can echo it to the confirm screen's hidden field.
         */
        public String getConfirmToken() {
            return confirmToken;
        }

        /**
         * Sets the single-use confirmation token issued when the state advances to
         * {@link ChangeAction#CHANGES_OK_NOT_CONFIRMED} (review finding #10).
         *
         * @param confirmToken the freshly generated token (may be {@code null} to clear)
         */
        void setConfirmToken(String confirmToken) {
            this.confirmToken = confirmToken;
        }

        /**
         * Reproduces {@code INITIALIZE WS-THIS-PROGCOMMAREA} plus
         * {@code SET ACUP-DETAILS-NOT-FETCHED}: clears the snapshot, the pending
         * new-details snapshot, and the confirmation token, and returns to the
         * not-fetched condition. Clearing {@link #pendingInput}/{@link #confirmToken}
         * here keeps the single-use confirmation window from surviving a reset
         * (review finding #10).
         */
        public void reset() {
            this.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
            this.oldSnapshot = null;
            this.pendingInput = null;
            this.confirmToken = null;
        }

        /** @return {@code true} when in the {@code ACUP-DETAILS-NOT-FETCHED} condition */
        public boolean isDetailsNotFetched() {
            return changeAction == ChangeAction.DETAILS_NOT_FETCHED;
        }
    }

    /**
     * Mutable snapshot of the account+customer values as originally fetched - the
     * Java view of {@code ACUP-OLD-DETAILS} ({@code ACUP-OLD-ACCT-DATA} +
     * {@code ACUP-OLD-CUST-DATA}, COACTUPC working storage). Built by
     * {@code 9500-STORE-FETCHED-DATA} and used as the optimistic-lock baseline by
     * both {@code 1205-COMPARE-OLD-NEW} (change detection) and
     * {@code 9700-CHECK-CHANGE-IN-REC} (pre-write concurrency check).
     *
     * <p>Field fidelity mirrors the copybook exactly: the five money fields are
     * {@link BigDecimal} at scale 2 (the {@code S9(10)V99} redefine of the
     * {@code X(12)} display picture - a byte compare of two identical zoned
     * representations is equivalent to {@link BigDecimal#compareTo}); the three
     * account dates and the date of birth are stored as {@code CCYYMMDD}
     * {@code X(08)} strings; both phone numbers are stored as the full
     * {@code X(15)} {@code "(999)999-9999"} picture so the {@code 9700} part-wise
     * comparison can slice them; all remaining fields are stored as their
     * space-normalized fixed-width strings.</p>
     *
     * <p>Origin: {@code legacy/cbl/COACTUPC.cbl}, {@code ACUP-OLD-DETAILS}
     * (working storage).</p>
     */
    public static final class AccountSnapshot implements Serializable {

        private static final long serialVersionUID = 1L;

        // --- ACUP-OLD-ACCT-DATA (account) ---
        private String acctId;             // ACUP-OLD-ACCT-ID-X            PIC X(11)
        private String activeStatus;       // ACUP-OLD-ACTIVE-STATUS        PIC X(01)
        private BigDecimal currBal;        // ACUP-OLD-CURR-BAL-N           PIC S9(10)V99
        private BigDecimal creditLimit;    // ACUP-OLD-CREDIT-LIMIT-N       PIC S9(10)V99
        private BigDecimal cashCreditLimit;// ACUP-OLD-CASH-CREDIT-LIMIT-N  PIC S9(10)V99
        private String openDate;           // ACUP-OLD-OPEN-DATE            PIC X(08) CCYYMMDD
        private String expirationDate;     // ACUP-OLD-EXPIRAION-DATE       PIC X(08) CCYYMMDD
        private String reissueDate;        // ACUP-OLD-REISSUE-DATE         PIC X(08) CCYYMMDD
        private BigDecimal currCycCredit;  // ACUP-OLD-CURR-CYC-CREDIT-N    PIC S9(10)V99
        private BigDecimal currCycDebit;   // ACUP-OLD-CURR-CYC-DEBIT-N     PIC S9(10)V99
        private String groupId;            // ACUP-OLD-GROUP-ID             PIC X(10)

        // --- ACUP-OLD-CUST-DATA (customer) ---
        private String custId;             // ACUP-OLD-CUST-ID-X            PIC X(09)
        private String firstName;          // ACUP-OLD-CUST-FIRST-NAME      PIC X(25)
        private String middleName;         // ACUP-OLD-CUST-MIDDLE-NAME     PIC X(25)
        private String lastName;           // ACUP-OLD-CUST-LAST-NAME       PIC X(25)
        private String addrLine1;          // ACUP-OLD-CUST-ADDR-LINE-1     PIC X(50)
        private String addrLine2;          // ACUP-OLD-CUST-ADDR-LINE-2     PIC X(50)
        private String addrLine3;          // ACUP-OLD-CUST-ADDR-LINE-3     PIC X(50)
        private String addrStateCd;        // ACUP-OLD-CUST-ADDR-STATE-CD   PIC X(02)
        private String addrCountryCd;      // ACUP-OLD-CUST-ADDR-COUNTRY-CD PIC X(03)
        private String addrZip;            // ACUP-OLD-CUST-ADDR-ZIP        PIC X(10)
        private String phoneNum1;          // ACUP-OLD-CUST-PHONE-NUM-1     PIC X(15) "(999)999-9999"
        private String phoneNum2;          // ACUP-OLD-CUST-PHONE-NUM-2     PIC X(15) "(999)999-9999"
        private String ssn;                // ACUP-OLD-CUST-SSN-X           PIC X(09)
        private String govtIssuedId;       // ACUP-OLD-CUST-GOVT-ISSUED-ID  PIC X(20)
        private String dateOfBirth;        // ACUP-OLD-CUST-DOB-YYYY-MM-DD  PIC X(08) CCYYMMDD
        private String eftAccountId;       // ACUP-OLD-CUST-EFT-ACCOUNT-ID  PIC X(10)
        private String priHolderInd;       // ACUP-OLD-CUST-PRI-HOLDER-IND  PIC X(01)
        private String ficoScore;          // ACUP-OLD-CUST-FICO-SCORE-X    PIC X(03)

        /** @return {@code ACUP-OLD-ACCT-ID-X} */
        public String getAcctId() {
            return acctId;
        }

        /** @param acctId {@code ACUP-OLD-ACCT-ID-X} */
        public void setAcctId(String acctId) {
            this.acctId = acctId;
        }

        /** @return {@code ACUP-OLD-ACTIVE-STATUS} */
        public String getActiveStatus() {
            return activeStatus;
        }

        /** @param activeStatus {@code ACUP-OLD-ACTIVE-STATUS} */
        public void setActiveStatus(String activeStatus) {
            this.activeStatus = activeStatus;
        }

        /** @return {@code ACUP-OLD-CURR-BAL-N} */
        public BigDecimal getCurrBal() {
            return currBal;
        }

        /** @param currBal {@code ACUP-OLD-CURR-BAL-N} */
        public void setCurrBal(BigDecimal currBal) {
            this.currBal = currBal;
        }

        /** @return {@code ACUP-OLD-CREDIT-LIMIT-N} */
        public BigDecimal getCreditLimit() {
            return creditLimit;
        }

        /** @param creditLimit {@code ACUP-OLD-CREDIT-LIMIT-N} */
        public void setCreditLimit(BigDecimal creditLimit) {
            this.creditLimit = creditLimit;
        }

        /** @return {@code ACUP-OLD-CASH-CREDIT-LIMIT-N} */
        public BigDecimal getCashCreditLimit() {
            return cashCreditLimit;
        }

        /** @param cashCreditLimit {@code ACUP-OLD-CASH-CREDIT-LIMIT-N} */
        public void setCashCreditLimit(BigDecimal cashCreditLimit) {
            this.cashCreditLimit = cashCreditLimit;
        }

        /** @return {@code ACUP-OLD-OPEN-DATE} (CCYYMMDD) */
        public String getOpenDate() {
            return openDate;
        }

        /** @param openDate {@code ACUP-OLD-OPEN-DATE} (CCYYMMDD) */
        public void setOpenDate(String openDate) {
            this.openDate = openDate;
        }

        /** @return {@code ACUP-OLD-EXPIRAION-DATE} (CCYYMMDD; COBOL spelling retained) */
        public String getExpirationDate() {
            return expirationDate;
        }

        /** @param expirationDate {@code ACUP-OLD-EXPIRAION-DATE} (CCYYMMDD) */
        public void setExpirationDate(String expirationDate) {
            this.expirationDate = expirationDate;
        }

        /** @return {@code ACUP-OLD-REISSUE-DATE} (CCYYMMDD) */
        public String getReissueDate() {
            return reissueDate;
        }

        /** @param reissueDate {@code ACUP-OLD-REISSUE-DATE} (CCYYMMDD) */
        public void setReissueDate(String reissueDate) {
            this.reissueDate = reissueDate;
        }

        /** @return {@code ACUP-OLD-CURR-CYC-CREDIT-N} */
        public BigDecimal getCurrCycCredit() {
            return currCycCredit;
        }

        /** @param currCycCredit {@code ACUP-OLD-CURR-CYC-CREDIT-N} */
        public void setCurrCycCredit(BigDecimal currCycCredit) {
            this.currCycCredit = currCycCredit;
        }

        /** @return {@code ACUP-OLD-CURR-CYC-DEBIT-N} */
        public BigDecimal getCurrCycDebit() {
            return currCycDebit;
        }

        /** @param currCycDebit {@code ACUP-OLD-CURR-CYC-DEBIT-N} */
        public void setCurrCycDebit(BigDecimal currCycDebit) {
            this.currCycDebit = currCycDebit;
        }

        /** @return {@code ACUP-OLD-GROUP-ID} */
        public String getGroupId() {
            return groupId;
        }

        /** @param groupId {@code ACUP-OLD-GROUP-ID} */
        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        /** @return {@code ACUP-OLD-CUST-ID-X} */
        public String getCustId() {
            return custId;
        }

        /** @param custId {@code ACUP-OLD-CUST-ID-X} */
        public void setCustId(String custId) {
            this.custId = custId;
        }

        /** @return {@code ACUP-OLD-CUST-FIRST-NAME} */
        public String getFirstName() {
            return firstName;
        }

        /** @param firstName {@code ACUP-OLD-CUST-FIRST-NAME} */
        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        /** @return {@code ACUP-OLD-CUST-MIDDLE-NAME} */
        public String getMiddleName() {
            return middleName;
        }

        /** @param middleName {@code ACUP-OLD-CUST-MIDDLE-NAME} */
        public void setMiddleName(String middleName) {
            this.middleName = middleName;
        }

        /** @return {@code ACUP-OLD-CUST-LAST-NAME} */
        public String getLastName() {
            return lastName;
        }

        /** @param lastName {@code ACUP-OLD-CUST-LAST-NAME} */
        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        /** @return {@code ACUP-OLD-CUST-ADDR-LINE-1} */
        public String getAddrLine1() {
            return addrLine1;
        }

        /** @param addrLine1 {@code ACUP-OLD-CUST-ADDR-LINE-1} */
        public void setAddrLine1(String addrLine1) {
            this.addrLine1 = addrLine1;
        }

        /** @return {@code ACUP-OLD-CUST-ADDR-LINE-2} */
        public String getAddrLine2() {
            return addrLine2;
        }

        /** @param addrLine2 {@code ACUP-OLD-CUST-ADDR-LINE-2} */
        public void setAddrLine2(String addrLine2) {
            this.addrLine2 = addrLine2;
        }

        /** @return {@code ACUP-OLD-CUST-ADDR-LINE-3} (the city, per the map-input note) */
        public String getAddrLine3() {
            return addrLine3;
        }

        /** @param addrLine3 {@code ACUP-OLD-CUST-ADDR-LINE-3} */
        public void setAddrLine3(String addrLine3) {
            this.addrLine3 = addrLine3;
        }

        /** @return {@code ACUP-OLD-CUST-ADDR-STATE-CD} */
        public String getAddrStateCd() {
            return addrStateCd;
        }

        /** @param addrStateCd {@code ACUP-OLD-CUST-ADDR-STATE-CD} */
        public void setAddrStateCd(String addrStateCd) {
            this.addrStateCd = addrStateCd;
        }

        /** @return {@code ACUP-OLD-CUST-ADDR-COUNTRY-CD} */
        public String getAddrCountryCd() {
            return addrCountryCd;
        }

        /** @param addrCountryCd {@code ACUP-OLD-CUST-ADDR-COUNTRY-CD} */
        public void setAddrCountryCd(String addrCountryCd) {
            this.addrCountryCd = addrCountryCd;
        }

        /** @return {@code ACUP-OLD-CUST-ADDR-ZIP} */
        public String getAddrZip() {
            return addrZip;
        }

        /** @param addrZip {@code ACUP-OLD-CUST-ADDR-ZIP} */
        public void setAddrZip(String addrZip) {
            this.addrZip = addrZip;
        }

        /** @return {@code ACUP-OLD-CUST-PHONE-NUM-1} (full {@code X(15)}) */
        public String getPhoneNum1() {
            return phoneNum1;
        }

        /** @param phoneNum1 {@code ACUP-OLD-CUST-PHONE-NUM-1} (full {@code X(15)}) */
        public void setPhoneNum1(String phoneNum1) {
            this.phoneNum1 = phoneNum1;
        }

        /** @return {@code ACUP-OLD-CUST-PHONE-NUM-2} (full {@code X(15)}) */
        public String getPhoneNum2() {
            return phoneNum2;
        }

        /** @param phoneNum2 {@code ACUP-OLD-CUST-PHONE-NUM-2} (full {@code X(15)}) */
        public void setPhoneNum2(String phoneNum2) {
            this.phoneNum2 = phoneNum2;
        }

        /** @return {@code ACUP-OLD-CUST-SSN-X} */
        public String getSsn() {
            return ssn;
        }

        /** @param ssn {@code ACUP-OLD-CUST-SSN-X} */
        public void setSsn(String ssn) {
            this.ssn = ssn;
        }

        /** @return {@code ACUP-OLD-CUST-GOVT-ISSUED-ID} */
        public String getGovtIssuedId() {
            return govtIssuedId;
        }

        /** @param govtIssuedId {@code ACUP-OLD-CUST-GOVT-ISSUED-ID} */
        public void setGovtIssuedId(String govtIssuedId) {
            this.govtIssuedId = govtIssuedId;
        }

        /** @return {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD} (CCYYMMDD) */
        public String getDateOfBirth() {
            return dateOfBirth;
        }

        /** @param dateOfBirth {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD} (CCYYMMDD) */
        public void setDateOfBirth(String dateOfBirth) {
            this.dateOfBirth = dateOfBirth;
        }

        /** @return {@code ACUP-OLD-CUST-EFT-ACCOUNT-ID} */
        public String getEftAccountId() {
            return eftAccountId;
        }

        /** @param eftAccountId {@code ACUP-OLD-CUST-EFT-ACCOUNT-ID} */
        public void setEftAccountId(String eftAccountId) {
            this.eftAccountId = eftAccountId;
        }

        /** @return {@code ACUP-OLD-CUST-PRI-HOLDER-IND} */
        public String getPriHolderInd() {
            return priHolderInd;
        }

        /** @param priHolderInd {@code ACUP-OLD-CUST-PRI-HOLDER-IND} */
        public void setPriHolderInd(String priHolderInd) {
            this.priHolderInd = priHolderInd;
        }

        /** @return {@code ACUP-OLD-CUST-FICO-SCORE-X} */
        public String getFicoScore() {
            return ficoScore;
        }

        /** @param ficoScore {@code ACUP-OLD-CUST-FICO-SCORE-X} */
        public void setFicoScore(String ficoScore) {
            this.ficoScore = ficoScore;
        }
    }

    // ============================================================================
    // Private per-request working holders (not session-persisted). These are the
    // Java view of the COACTUPC working-storage edit registers and the ACUP-NEW
    // details rebuilt from the submitted map every request.
    // ============================================================================

    /**
     * The shared edit register - the Java view of {@code WS-INPUT-FLG}
     * ({@code INPUT-OK}/{@code INPUT-ERROR}) plus the {@code WS-RETURN-MSG-OFF} /
     * {@code WS-RETURN-MSG} first-error-wins message accumulator used by the
     * {@code 1215}-{@code 1280} generic edit paragraphs.
     *
     * <p>The COBOL idiom is {@code IF WS-RETURN-MSG-OFF MOVE '...' TO WS-RETURN-MSG
     * END-IF} - only the first field error keeps its message; subsequent errors
     * still set {@code INPUT-ERROR} but do not overwrite the banner. One paragraph
     * ({@code NO-CHANGES-DETECTED}, COACTUPC line ~1769) deliberately sets the
     * message unconditionally, reproduced by {@link #forceMessage(String)}.</p>
     */
    private static final class EditContext {

        private boolean inputError;
        private boolean returnMsgOff = true;
        private String returnMsg = "";

        /** @return {@code true} when {@code INPUT-ERROR} is set (at least one field failed) */
        boolean isInputError() {
            return inputError;
        }

        /** @return {@code true} while {@code WS-RETURN-MSG-OFF} holds (no banner set yet) */
        boolean isReturnMsgOff() {
            return returnMsgOff;
        }

        /** @return the current {@code WS-RETURN-MSG} banner text (never {@code null}) */
        String getReturnMsg() {
            return returnMsg;
        }

        /**
         * Reproduces {@code SET INPUT-ERROR TO TRUE} followed by the guarded
         * {@code IF WS-RETURN-MSG-OFF MOVE message TO WS-RETURN-MSG END-IF}: always
         * flags the input error, but records the banner only if none has been set.
         *
         * @param message the field message to record when this is the first error
         * @return {@link FieldFlag#NOT_OK} for convenient assignment at the call site
         */
        FieldFlag fail(String message) {
            inputError = true;
            if (returnMsgOff) {
                returnMsg = (message == null) ? "" : message;
                returnMsgOff = false;
            }
            return FieldFlag.NOT_OK;
        }

        /**
         * Reproduces {@code SET INPUT-ERROR TO TRUE} on its own (the field flag is
         * recorded separately by the caller), keeping the first-error-wins banner
         * guard intact.
         */
        void markInputError() {
            inputError = true;
        }

        /**
         * Reproduces an unconditional {@code MOVE message TO WS-RETURN-MSG} (used by
         * {@code NO-CHANGES-DETECTED}) - overwrites the banner regardless of the
         * {@code WS-RETURN-MSG-OFF} guard and latches the guard off.
         *
         * @param message the message to force onto the banner
         */
        void forceMessage(String message) {
            returnMsg = (message == null) ? "" : message;
            returnMsgOff = false;
        }

        /**
         * Reproduces {@code SET WS-RETURN-MSG-OFF TO TRUE} (COBOL line 2571, inside
         * {@code 2000-DECIDE-ACTION} just before the re-fetch): clears the banner
         * back to its "off" (spaces) state and re-arms the first-error-wins guard.
         *
         * <p>This affects only the {@code WS-RETURN-MSG} banner; it deliberately
         * does not touch {@code INPUT-ERROR}, matching the COBOL where the 88-level
         * {@code WS-RETURN-MSG-OFF} is defined solely on {@code WS-RETURN-MSG}.</p>
         */
        void resetMessage() {
            returnMsg = "";
            returnMsgOff = true;
        }
    }

    /**
     * Search-key validity flag - the Java view of the {@code WS-FLG-ACCTFILTER-*}
     * and {@code WS-FLG-CUSTFILTER-*} 88-levels ({@code -BLANK}, {@code -ISVALID},
     * {@code -NOT-OK}) evaluated by {@code 1210-EDIT-ACCOUNT} and consulted by the
     * {@code 2000-DECIDE-ACTION} state machine.
     */
    private enum FilterFlag {
        /** {@code WS-FLG-ACCTFILTER-BLANK}: the filter key was left empty. */
        BLANK,
        /** {@code WS-FLG-ACCTFILTER-ISVALID}: the filter key passed its edit. */
        VALID,
        /** {@code WS-FLG-ACCTFILTER-NOT-OK}: the filter key failed its edit. */
        NOT_OK
    }

    /**
     * Normalized map input - the Java view of {@code ACUP-NEW-DETAILS}
     * ({@code ACUP-NEW-ACCT-DATA} + {@code ACUP-NEW-CUST-DATA}) as produced by
     * {@code 1100-RECEIVE-MAP}. Each field mirrors the copybook: a map value equal
     * to {@code '*'} or spaces becomes {@code null} (the COBOL {@code LOW-VALUES}
     * "not supplied" sentinel); every other value is carried verbatim. The five
     * money fields additionally retain both the raw entered string (for the
     * {@code 1250} numeric edit and its message) and the parsed {@link BigDecimal}
     * (via {@link CobolDecimal}, scale 2, {@code RoundingMode.DOWN}); a
     * {@code null} decimal beside a non-{@code null} raw string signals an
     * unparseable amount.
     *
     * <p>Origin: {@code legacy/cbl/COACTUPC.cbl}, {@code ACUP-NEW-DETAILS}
     * (working storage) and {@code 1100-RECEIVE-MAP}.</p>
     *
     * <p>Implements {@link Serializable} so the validated snapshot can be carried
     * server-side on {@link AccountUpdateState#pendingInput} across the
     * ENTER&rarr;PF5 pseudo-conversational turns (review finding #10), mirroring the
     * COBOL {@code ACUP-NEW-DETAILS} residing in {@code WS-THIS-PROGCOMMAREA}. All
     * fields are {@link String} or {@link BigDecimal} and therefore serializable.</p>
     */
    private static final class NewInput implements Serializable {

        private static final long serialVersionUID = 1L;

        // --- account data ---
        private String accountFilter;   // ACCTSIDI -> CC-ACCT-ID (search key)
        private String activeStatus;    // ACSTTUSI -> ACUP-NEW-ACTIVE-STATUS
        private String openYear;         // OPNYEARI -> ACUP-NEW-OPEN-YEAR
        private String openMon;          // OPNMONI  -> ACUP-NEW-OPEN-MON
        private String openDay;          // OPNDAYI  -> ACUP-NEW-OPEN-DAY
        private String creditLimitRaw;   // ACRDLIMI -> ACUP-NEW-CREDIT-LIMIT (raw)
        private BigDecimal creditLimit;  // NUMVAL-C -> ACUP-NEW-CREDIT-LIMIT-N
        private String expYear;          // EXPYEARI -> ACUP-NEW-EXP-YEAR
        private String expMon;           // EXPMONI  -> ACUP-NEW-EXP-MON
        private String expDay;           // EXPDAYI  -> ACUP-NEW-EXP-DAY
        private String cashCreditLimitRaw;   // ACSHLIMI -> ACUP-NEW-CASH-CREDIT-LIMIT (raw)
        private BigDecimal cashCreditLimit;  // NUMVAL-C -> ACUP-NEW-CASH-CREDIT-LIMIT-N
        private String reissueYear;      // RISYEARI -> ACUP-NEW-REISSUE-YEAR
        private String reissueMon;       // RISMONI  -> ACUP-NEW-REISSUE-MON
        private String reissueDay;       // RISDAYI  -> ACUP-NEW-REISSUE-DAY
        private String currBalRaw;       // ACURBALI -> ACUP-NEW-CURR-BAL (raw)
        private BigDecimal currBal;      // NUMVAL-C -> ACUP-NEW-CURR-BAL-N
        private String currCycCreditRaw; // ACRCYCRI -> ACUP-NEW-CURR-CYC-CREDIT (raw)
        private BigDecimal currCycCredit;// NUMVAL-C -> ACUP-NEW-CURR-CYC-CREDIT-N
        private String groupId;          // AADDGRPI -> ACUP-NEW-GROUP-ID
        private String currCycDebitRaw;  // ACRCYDBI -> ACUP-NEW-CURR-CYC-DEBIT (raw)
        private BigDecimal currCycDebit; // NUMVAL-C -> ACUP-NEW-CURR-CYC-DEBIT-N

        // --- customer data ---
        private String custId;           // ACSTNUMI -> ACUP-NEW-CUST-ID-X (not editable)
        private String ssn1;             // ACTSSN1I -> ACUP-NEW-CUST-SSN-1
        private String ssn2;             // ACTSSN2I -> ACUP-NEW-CUST-SSN-2
        private String ssn3;             // ACTSSN3I -> ACUP-NEW-CUST-SSN-3
        private String dobYear;          // DOBYEARI -> ACUP-NEW-CUST-DOB-YEAR
        private String dobMon;           // DOBMONI  -> ACUP-NEW-CUST-DOB-MON
        private String dobDay;           // DOBDAYI  -> ACUP-NEW-CUST-DOB-DAY
        private String fico;             // ACSTFCOI -> ACUP-NEW-CUST-FICO-SCORE-X
        private String firstName;        // ACSFNAMI -> ACUP-NEW-CUST-FIRST-NAME
        private String middleName;       // ACSMNAMI -> ACUP-NEW-CUST-MIDDLE-NAME
        private String lastName;         // ACSLNAMI -> ACUP-NEW-CUST-LAST-NAME
        private String addrLine1;        // ACSADL1I -> ACUP-NEW-CUST-ADDR-LINE-1
        private String addrLine2;        // ACSADL2I -> ACUP-NEW-CUST-ADDR-LINE-2
        private String addrLine3;        // ACSCITYI -> ACUP-NEW-CUST-ADDR-LINE-3 (city)
        private String stateCd;          // ACSSTTEI -> ACUP-NEW-CUST-ADDR-STATE-CD
        private String zip;              // ACSZIPCI -> ACUP-NEW-CUST-ADDR-ZIP
        private String countryCd;        // ACSCTRYI -> ACUP-NEW-CUST-ADDR-COUNTRY-CD
        private String phone1a;          // ACSPH1AI -> ACUP-NEW-CUST-PHONE-NUM-1A
        private String phone1b;          // ACSPH1BI -> ACUP-NEW-CUST-PHONE-NUM-1B
        private String phone1c;          // ACSPH1CI -> ACUP-NEW-CUST-PHONE-NUM-1C
        private String govtId;           // ACSGOVTI -> ACUP-NEW-CUST-GOVT-ISSUED-ID
        private String phone2a;          // ACSPH2AI -> ACUP-NEW-CUST-PHONE-NUM-2A
        private String phone2b;          // ACSPH2BI -> ACUP-NEW-CUST-PHONE-NUM-2B
        private String phone2c;          // ACSPH2CI -> ACUP-NEW-CUST-PHONE-NUM-2C
        private String eft;              // ACSEFTCI -> ACUP-NEW-CUST-EFT-ACCOUNT-ID
        private String priHolder;        // ACSPFLGI -> ACUP-NEW-CUST-PRI-HOLDER-IND

        /**
         * Pads or truncates {@code value} to the COBOL fixed width, treating a
         * {@code null} ({@code LOW-VALUES}) segment as spaces so composed keys keep
         * their byte positions.
         *
         * @param value the raw segment (may be {@code null})
         * @param width the target fixed width
         * @return a string exactly {@code width} characters long
         */
        private static String seg(String value, int width) {
            if (value == null) {
                return " ".repeat(width);
            }
            if (value.length() == width) {
                return value;
            }
            if (value.length() > width) {
                return value.substring(0, width);
            }
            return value + " ".repeat(width - value.length());
        }

        /** @return {@code ACUP-NEW-OPEN-DATE} as CCYYMMDD (year+mon+day, 8 chars) */
        String composeOpenDate() {
            return seg(openYear, 4) + seg(openMon, 2) + seg(openDay, 2);
        }

        /** @return {@code ACUP-NEW-EXPIRAION-DATE} as CCYYMMDD (8 chars) */
        String composeExpiryDate() {
            return seg(expYear, 4) + seg(expMon, 2) + seg(expDay, 2);
        }

        /** @return {@code ACUP-NEW-REISSUE-DATE} as CCYYMMDD (8 chars) */
        String composeReissueDate() {
            return seg(reissueYear, 4) + seg(reissueMon, 2) + seg(reissueDay, 2);
        }

        /** @return {@code ACUP-NEW-CUST-DOB-YYYY-MM-DD} as CCYYMMDD (8 chars) */
        String composeDob() {
            return seg(dobYear, 4) + seg(dobMon, 2) + seg(dobDay, 2);
        }

        /** @return {@code ACUP-NEW-CUST-SSN} as a 9-character string (part1+part2+part3) */
        String composeSsn() {
            return seg(ssn1, 3) + seg(ssn2, 2) + seg(ssn3, 4);
        }

        /**
         * Builds the formatted primary phone exactly as {@code 9600-WRITE-PROCESSING}
         * does ({@code STRING '(' 1A ')' 1B '-' 1C}) - {@code "(999)999-9999"}.
         *
         * @return the formatted {@code CUST-UPDATE-PHONE-NUM-1} value
         */
        String composePhone1() {
            return "(" + seg(phone1a, 3) + ")" + seg(phone1b, 3) + "-" + seg(phone1c, 4);
        }

        /**
         * Builds the formatted secondary phone exactly as
         * {@code 9600-WRITE-PROCESSING} does - {@code "(999)999-9999"}.
         *
         * @return the formatted {@code CUST-UPDATE-PHONE-NUM-2} value
         */
        String composePhone2() {
            return "(" + seg(phone2a, 3) + ")" + seg(phone2b, 3) + "-" + seg(phone2c, 4);
        }

        // --- explicit accessors (no Lombok) ---

        String getAccountFilter() {
            return accountFilter;
        }

        void setAccountFilter(String accountFilter) {
            this.accountFilter = accountFilter;
        }

        String getActiveStatus() {
            return activeStatus;
        }

        void setActiveStatus(String activeStatus) {
            this.activeStatus = activeStatus;
        }

        String getOpenYear() {
            return openYear;
        }

        void setOpenYear(String openYear) {
            this.openYear = openYear;
        }

        String getOpenMon() {
            return openMon;
        }

        void setOpenMon(String openMon) {
            this.openMon = openMon;
        }

        String getOpenDay() {
            return openDay;
        }

        void setOpenDay(String openDay) {
            this.openDay = openDay;
        }

        String getCreditLimitRaw() {
            return creditLimitRaw;
        }

        void setCreditLimitRaw(String creditLimitRaw) {
            this.creditLimitRaw = creditLimitRaw;
        }

        BigDecimal getCreditLimit() {
            return creditLimit;
        }

        void setCreditLimit(BigDecimal creditLimit) {
            this.creditLimit = creditLimit;
        }

        String getExpYear() {
            return expYear;
        }

        void setExpYear(String expYear) {
            this.expYear = expYear;
        }

        String getExpMon() {
            return expMon;
        }

        void setExpMon(String expMon) {
            this.expMon = expMon;
        }

        String getExpDay() {
            return expDay;
        }

        void setExpDay(String expDay) {
            this.expDay = expDay;
        }

        String getCashCreditLimitRaw() {
            return cashCreditLimitRaw;
        }

        void setCashCreditLimitRaw(String cashCreditLimitRaw) {
            this.cashCreditLimitRaw = cashCreditLimitRaw;
        }

        BigDecimal getCashCreditLimit() {
            return cashCreditLimit;
        }

        void setCashCreditLimit(BigDecimal cashCreditLimit) {
            this.cashCreditLimit = cashCreditLimit;
        }

        String getReissueYear() {
            return reissueYear;
        }

        void setReissueYear(String reissueYear) {
            this.reissueYear = reissueYear;
        }

        String getReissueMon() {
            return reissueMon;
        }

        void setReissueMon(String reissueMon) {
            this.reissueMon = reissueMon;
        }

        String getReissueDay() {
            return reissueDay;
        }

        void setReissueDay(String reissueDay) {
            this.reissueDay = reissueDay;
        }

        String getCurrBalRaw() {
            return currBalRaw;
        }

        void setCurrBalRaw(String currBalRaw) {
            this.currBalRaw = currBalRaw;
        }

        BigDecimal getCurrBal() {
            return currBal;
        }

        void setCurrBal(BigDecimal currBal) {
            this.currBal = currBal;
        }

        String getCurrCycCreditRaw() {
            return currCycCreditRaw;
        }

        void setCurrCycCreditRaw(String currCycCreditRaw) {
            this.currCycCreditRaw = currCycCreditRaw;
        }

        BigDecimal getCurrCycCredit() {
            return currCycCredit;
        }

        void setCurrCycCredit(BigDecimal currCycCredit) {
            this.currCycCredit = currCycCredit;
        }

        String getGroupId() {
            return groupId;
        }

        void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        String getCurrCycDebitRaw() {
            return currCycDebitRaw;
        }

        void setCurrCycDebitRaw(String currCycDebitRaw) {
            this.currCycDebitRaw = currCycDebitRaw;
        }

        BigDecimal getCurrCycDebit() {
            return currCycDebit;
        }

        void setCurrCycDebit(BigDecimal currCycDebit) {
            this.currCycDebit = currCycDebit;
        }

        String getCustId() {
            return custId;
        }

        void setCustId(String custId) {
            this.custId = custId;
        }

        String getSsn1() {
            return ssn1;
        }

        void setSsn1(String ssn1) {
            this.ssn1 = ssn1;
        }

        String getSsn2() {
            return ssn2;
        }

        void setSsn2(String ssn2) {
            this.ssn2 = ssn2;
        }

        String getSsn3() {
            return ssn3;
        }

        void setSsn3(String ssn3) {
            this.ssn3 = ssn3;
        }

        String getDobYear() {
            return dobYear;
        }

        void setDobYear(String dobYear) {
            this.dobYear = dobYear;
        }

        String getDobMon() {
            return dobMon;
        }

        void setDobMon(String dobMon) {
            this.dobMon = dobMon;
        }

        String getDobDay() {
            return dobDay;
        }

        void setDobDay(String dobDay) {
            this.dobDay = dobDay;
        }

        String getFico() {
            return fico;
        }

        void setFico(String fico) {
            this.fico = fico;
        }

        String getFirstName() {
            return firstName;
        }

        void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        String getMiddleName() {
            return middleName;
        }

        void setMiddleName(String middleName) {
            this.middleName = middleName;
        }

        String getLastName() {
            return lastName;
        }

        void setLastName(String lastName) {
            this.lastName = lastName;
        }

        String getAddrLine1() {
            return addrLine1;
        }

        void setAddrLine1(String addrLine1) {
            this.addrLine1 = addrLine1;
        }

        String getAddrLine2() {
            return addrLine2;
        }

        void setAddrLine2(String addrLine2) {
            this.addrLine2 = addrLine2;
        }

        String getAddrLine3() {
            return addrLine3;
        }

        void setAddrLine3(String addrLine3) {
            this.addrLine3 = addrLine3;
        }

        String getStateCd() {
            return stateCd;
        }

        void setStateCd(String stateCd) {
            this.stateCd = stateCd;
        }

        String getZip() {
            return zip;
        }

        void setZip(String zip) {
            this.zip = zip;
        }

        String getCountryCd() {
            return countryCd;
        }

        void setCountryCd(String countryCd) {
            this.countryCd = countryCd;
        }

        String getPhone1a() {
            return phone1a;
        }

        void setPhone1a(String phone1a) {
            this.phone1a = phone1a;
        }

        String getPhone1b() {
            return phone1b;
        }

        void setPhone1b(String phone1b) {
            this.phone1b = phone1b;
        }

        String getPhone1c() {
            return phone1c;
        }

        void setPhone1c(String phone1c) {
            this.phone1c = phone1c;
        }

        String getGovtId() {
            return govtId;
        }

        void setGovtId(String govtId) {
            this.govtId = govtId;
        }

        String getPhone2a() {
            return phone2a;
        }

        void setPhone2a(String phone2a) {
            this.phone2a = phone2a;
        }

        String getPhone2b() {
            return phone2b;
        }

        void setPhone2b(String phone2b) {
            this.phone2b = phone2b;
        }

        String getPhone2c() {
            return phone2c;
        }

        void setPhone2c(String phone2c) {
            this.phone2c = phone2c;
        }

        String getEft() {
            return eft;
        }

        void setEft(String eft) {
            this.eft = eft;
        }

        String getPriHolder() {
            return priHolder;
        }

        void setPriHolder(String priHolder) {
            this.priHolder = priHolder;
        }
    }

    /**
     * The per-request working state carried through {@code 1000-PROCESS-INPUTS} and
     * its callees ({@code 1100}/{@code 1200}/{@code 1205}/{@code 2000}/{@code 9xxx}).
     * It bundles the submitted form, the session {@link AccountUpdateState}, the
     * normalized {@link NewInput}, the entities fetched by the read chain, the
     * per-field validity map returned to the controller, the shared
     * {@link EditContext} edit register, the account/customer filter flags, and the
     * change/lock outcome flags. It is deliberately not {@link Serializable}: only
     * {@link AccountUpdateState} (change action + old snapshot) survives across
     * pseudo-conversational turns.
     */
    private static final class ProcessingState {

        private final COACTUPForm form;
        private final AccountUpdateState state;
        private final EnumMap<ScreenField, FieldFlag> fieldFlags;
        private final EditContext edit;

        private NewInput newInput;
        private Account fetchedAccount;
        private Customer fetchedCustomer;
        private String infoMessage = "";
        private FilterFlag accountFilter = FilterFlag.BLANK;
        private FilterFlag customerFilter = FilterFlag.BLANK;
        private boolean foundAcctInMaster;
        private boolean foundCustInMaster;
        private boolean changeHasOccurred;
        private boolean dataChanged;
        private boolean xrefNotFound;
        private WriteResult writeResult = WriteResult.SUCCESS;

        ProcessingState(COACTUPForm form, AccountUpdateState state) {
            this.form = form;
            this.state = state;
            this.fieldFlags = new EnumMap<>(ScreenField.class);
            this.edit = new EditContext();
        }

        COACTUPForm getForm() {
            return form;
        }

        AccountUpdateState getState() {
            return state;
        }

        EditContext getEdit() {
            return edit;
        }

        Map<ScreenField, FieldFlag> getFieldFlags() {
            return fieldFlags;
        }

        /**
         * Records the validity flag for a screen field (the COBOL per-field
         * attribute set), storing {@link FieldFlag#VALID} when {@code flag} is
         * {@code null}.
         *
         * @param field the logical screen field
         * @param flag  the validity flag to record
         */
        void setFieldFlag(ScreenField field, FieldFlag flag) {
            fieldFlags.put(field, (flag == null) ? FieldFlag.VALID : flag);
        }

        /**
         * @param field the logical screen field
         * @return the recorded flag, or {@link FieldFlag#VALID} when none was set
         */
        FieldFlag getFieldFlag(ScreenField field) {
            return fieldFlags.getOrDefault(field, FieldFlag.VALID);
        }

        NewInput getNewInput() {
            return newInput;
        }

        void setNewInput(NewInput newInput) {
            this.newInput = newInput;
        }

        Account getFetchedAccount() {
            return fetchedAccount;
        }

        void setFetchedAccount(Account fetchedAccount) {
            this.fetchedAccount = fetchedAccount;
        }

        Customer getFetchedCustomer() {
            return fetchedCustomer;
        }

        void setFetchedCustomer(Customer fetchedCustomer) {
            this.fetchedCustomer = fetchedCustomer;
        }

        String getInfoMessage() {
            return infoMessage;
        }

        void setInfoMessage(String infoMessage) {
            this.infoMessage = (infoMessage == null) ? "" : infoMessage;
        }

        FilterFlag getAccountFilter() {
            return accountFilter;
        }

        void setAccountFilter(FilterFlag accountFilter) {
            this.accountFilter = accountFilter;
        }

        FilterFlag getCustomerFilter() {
            return customerFilter;
        }

        void setCustomerFilter(FilterFlag customerFilter) {
            this.customerFilter = customerFilter;
        }

        boolean isFoundAcctInMaster() {
            return foundAcctInMaster;
        }

        void setFoundAcctInMaster(boolean foundAcctInMaster) {
            this.foundAcctInMaster = foundAcctInMaster;
        }

        boolean isFoundCustInMaster() {
            return foundCustInMaster;
        }

        void setFoundCustInMaster(boolean foundCustInMaster) {
            this.foundCustInMaster = foundCustInMaster;
        }

        boolean isChangeHasOccurred() {
            return changeHasOccurred;
        }

        void setChangeHasOccurred(boolean changeHasOccurred) {
            this.changeHasOccurred = changeHasOccurred;
        }

        boolean isDataChanged() {
            return dataChanged;
        }

        void setDataChanged(boolean dataChanged) {
            this.dataChanged = dataChanged;
        }

        boolean isXrefNotFound() {
            return xrefNotFound;
        }

        void setXrefNotFound(boolean xrefNotFound) {
            this.xrefNotFound = xrefNotFound;
        }

        WriteResult getWriteResult() {
            return writeResult;
        }

        void setWriteResult(WriteResult writeResult) {
            this.writeResult = (writeResult == null) ? WriteResult.SUCCESS : writeResult;
        }

        /** @return {@code true} when {@code INPUT-ERROR} is set (delegates to the edit register) */
        boolean isInputError() {
            return edit.isInputError();
        }
    }


    // ================================================================
    // Phase 3 - Field EDIT validation primitives (COBOL paragraphs
    // 1215-EDIT-MANDATORY through 1280-EDIT-US-STATE-ZIP-CD, plus the
    // 1260 phone sub-paragraphs). Each COBOL edit paragraph maps to one
    // Java method; every validation message is reproduced verbatim.
    // All INPUT-ERROR / WS-RETURN-MSG writes flow through EditContext so
    // the first-error-wins banner guard (WS-RETURN-MSG-OFF) is honored.
    //
    // Origin: legacy/cbl/COACTUPC.cbl, program COACTUPC, tran CAUP.
    // ================================================================

    // ----- Shared edit helpers (COBOL working-storage editing idioms) -----

    /**
     * Reproduces a COBOL fixed-width field window ({@code field(1:length)}): a
     * {@code MOVE} of the supplied value into a left-justified, space-padded
     * {@code PIC X(length)} area. A {@code null} value is treated as
     * {@code LOW-VALUES}/blank and becomes {@code length} spaces; a longer value
     * is truncated to {@code length}.
     *
     * @param value  the raw field content (may be {@code null})
     * @param length the fixed field width in characters
     * @return a string of exactly {@code length} characters
     */
    private static String fixedWindow(String value, int length) {
        String v = (value == null) ? "" : value;
        if (v.length() >= length) {
            return v.substring(0, length);
        }
        StringBuilder sb = new StringBuilder(length);
        sb.append(v);
        while (sb.length() < length) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Reproduces the COBOL "not supplied" test on a fixed window
     * ({@code window EQUAL LOW-VALUES OR SPACES OR LENGTH(TRIM(window)) = 0}).
     *
     * @param window a fixed-width window (never {@code null}; use {@link #fixedWindow})
     * @return {@code true} when the window is entirely blank
     */
    private static boolean isBlankWindow(String window) {
        return window.isBlank();
    }

    /**
     * Reproduces the COBOL blank test on a raw (non-windowed) field
     * ({@code field EQUAL SPACES OR LOW-VALUES}) - used by {@code 1250} and the
     * phone sub-edits where the comparison is against the whole field.
     *
     * @param value the raw field content (may be {@code null})
     * @return {@code true} when {@code null} or all whitespace
     */
    private static boolean isBlankRaw(String value) {
        return value == null || value.strip().isEmpty();
    }

    /**
     * Reproduces the COBOL {@code INSPECT ... CONVERTING LIT-ALL-ALPHA-FROM TO
     * spaces} alphabetic test: valid when every character is an ASCII letter
     * ({@code A-Z}/{@code a-z}) or a space (COBOL lines 586-611).
     *
     * @param window a fixed-width window
     * @return {@code true} when only letters and spaces are present
     */
    private static boolean isAlphaOrSpace(String window) {
        for (int i = 0; i < window.length(); i++) {
            char c = window.charAt(i);
            if (c != ' ' && !(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the COBOL {@code INSPECT ... CONVERTING LIT-ALL-ALPHANUM-FROM TO
     * spaces} alphanumeric test: valid when every character is an ASCII letter,
     * a digit, or a space (COBOL lines 586-611).
     *
     * @param window a fixed-width window
     * @return {@code true} when only letters, digits and spaces are present
     */
    private static boolean isAlphaNumOrSpace(String window) {
        for (int i = 0; i < window.length(); i++) {
            char c = window.charAt(i);
            if (c != ' '
                    && !(c >= 'A' && c <= 'Z')
                    && !(c >= 'a' && c <= 'z')
                    && !(c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the COBOL {@code IS NUMERIC} class test on an unsigned display
     * field: valid only when the window is non-empty and every character is a
     * digit. Trailing spaces from a partially-filled field cause failure exactly
     * as {@code IS NUMERIC} does on a fixed {@code PIC X(n)} field.
     *
     * @param window a fixed-width window
     * @return {@code true} when all characters are digits {@code 0-9}
     */
    private static boolean isAllDigits(String window) {
        if (window.isEmpty()) {
            return false;
        }
        for (int i = 0; i < window.length(); i++) {
            char c = window.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the COBOL {@code FUNCTION NUMVAL(window) = 0} test for a window
     * already known to be all digits: the numeric value is zero exactly when
     * every character is {@code '0'}. Avoids any integer overflow for wide
     * fields by comparing characters rather than parsing.
     *
     * @param window a fixed-width, all-digit window
     * @return {@code true} when every character is {@code '0'}
     */
    private static boolean isAllZeros(String window) {
        for (int i = 0; i < window.length(); i++) {
            if (window.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the numeric value of a COBOL {@code 9(n)} redefinition of a
     * fixed field (for example {@code WS-EDIT-US-PHONE-NUMA-N}); returns
     * {@code -1} when the window is not all digits so callers can treat a
     * non-numeric window as "not zero and not in range".
     *
     * @param value  the raw field content (may be {@code null})
     * @param length the fixed field width in characters
     * @return the parsed integer, or {@code -1} when the window is not numeric
     */
    private static int parseIntWindow(String value, int length) {
        String w = fixedWindow(value, length);
        if (!isAllDigits(w)) {
            return -1;
        }
        return Integer.parseInt(w);
    }

    /**
     * Reproduces {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} used at the front
     * of every dynamically-built edit message.
     *
     * @param name the caller-supplied field label (may be {@code null})
     * @return the trimmed label, or {@code ""} when {@code null}
     */
    private static String label(String name) {
        return name == null ? "" : name.trim();
    }

    /**
     * Reproduces COBOL {@code FUNCTION TEST-NUMVAL-C} returning zero (valid): the
     * value is a well-formed edited numeric literal. Accepts optional leading
     * and/or trailing spaces, one leading and/or trailing sign ({@code + -}), a
     * single leading currency symbol ({@code $}), grouping commas, a single
     * decimal point, and an optional trailing {@code CR}/{@code DB}. At least one
     * digit must be present.
     *
     * @param raw the raw edited numeric string (may be {@code null})
     * @return {@code true} when {@code TEST-NUMVAL-C} would return {@code 0}
     */
    private static boolean testNumvalC(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return false;
        }
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.endsWith("CR") || upper.endsWith("DB")) {
            s = s.substring(0, s.length() - 2).trim();
        }
        if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
            s = s.substring(1);
        }
        if (!s.isEmpty() && (s.charAt(s.length() - 1) == '+' || s.charAt(s.length() - 1) == '-')) {
            s = s.substring(0, s.length() - 1);
        }
        s = s.replace("$", "").replace(",", "").replace(" ", "");
        if (s.isEmpty()) {
            return false;
        }
        int dots = 0;
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                dots++;
                if (dots > 1) {
                    return false;
                }
            } else if (c >= '0' && c <= '9') {
                digits++;
            } else {
                return false;
            }
        }
        return digits > 0;
    }

    /**
     * Reproduces COBOL {@code FUNCTION NUMVAL-C}: parses an edited numeric literal
     * into its {@link BigDecimal} value using the same character grammar as
     * {@link #testNumvalC(String)}. A trailing {@code CR}/{@code DB} or a leading
     * or trailing minus makes the result negative. Returns {@link BigDecimal#ZERO}
     * for blank or unparseable input (matching {@code NUMVAL-C}'s lenient
     * behavior). The caller applies {@link CobolDecimal#money(BigDecimal)} to fix
     * the scale to two digits with {@code RoundingMode.DOWN}.
     *
     * @param raw the raw edited numeric string (may be {@code null})
     * @return the parsed value, never {@code null}
     */
    private static BigDecimal numvalC(String raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return BigDecimal.ZERO;
        }
        boolean negative = false;
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.endsWith("CR") || upper.endsWith("DB")) {
            negative = true;
            s = s.substring(0, s.length() - 2).trim();
        }
        if (!s.isEmpty() && s.charAt(0) == '-') {
            negative = true;
            s = s.substring(1);
        } else if (!s.isEmpty() && s.charAt(0) == '+') {
            s = s.substring(1);
        }
        if (!s.isEmpty() && s.charAt(s.length() - 1) == '-') {
            negative = true;
            s = s.substring(0, s.length() - 1);
        } else if (!s.isEmpty() && s.charAt(s.length() - 1) == '+') {
            s = s.substring(0, s.length() - 1);
        }
        s = s.replace("$", "").replace(",", "").replace(" ", "");
        if (s.isEmpty() || ".".equals(s)) {
            return BigDecimal.ZERO;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(s);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
        return negative ? value.negate() : value;
    }


    // ----- Generic field edits (1215-1250) -----

    /**
     * {@code 1215-EDIT-MANDATORY} - a mandatory free-form field must be supplied.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1215-EDIT-MANDATORY}
     * (COBOL lines 1823-1852). Blank input yields
     * {@code "<field> must be supplied."} and the {@code FLG-MANDATORY-BLANK}
     * state; otherwise the field is valid.
     *
     * @param edit   the shared edit register (first-error-wins banner)
     * @param name   the field label placed at the front of the message
     * @param value  the raw field content
     * @param length the fixed field width
     * @return {@link FieldFlag#VALID} or {@link FieldFlag#BLANK}
     */
    private static FieldFlag editMandatory(EditContext edit, String name, String value, int length) {
        String window = fixedWindow(value, length);
        if (isBlankWindow(window)) {
            edit.fail(label(name) + " must be supplied.");
            return FieldFlag.BLANK;
        }
        return FieldFlag.VALID;
    }

    /**
     * {@code 1220-EDIT-YESNO} - a flag field must be supplied and must be
     * {@code 'Y'} or {@code 'N'}.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1220-EDIT-YESNO}
     * (COBOL lines 1854-1892). {@code LOW-VALUES}/{@code SPACES}/{@code ZEROS}
     * yield {@code "<field> must be supplied."}; any value other than the
     * {@code 88 FLG-YES-NO-ISVALID VALUES 'Y','N'} yields
     * {@code "<field> must be Y or N."}.
     *
     * @param edit  the shared edit register
     * @param name  the field label
     * @param value the raw field content
     * @return the resulting field flag
     */
    private static FieldFlag editYesNo(EditContext edit, String name, String value) {
        String v = (value == null) ? "" : value.trim();
        if (v.isEmpty() || "0".equals(v)) {
            edit.fail(label(name) + " must be supplied.");
            return FieldFlag.BLANK;
        }
        if ("Y".equals(v) || "N".equals(v)) {
            return FieldFlag.VALID;
        }
        return edit.fail(label(name) + " must be Y or N.");
    }

    /**
     * {@code 1225-EDIT-ALPHA-REQD} - a required field that may contain only
     * alphabetic characters and spaces.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1225-EDIT-ALPHA-REQD}
     * (COBOL lines 1894-1948). Blank yields {@code "<field> must be supplied."};
     * a non-alphabetic character yields {@code "<field> can have alphabets only."}.
     *
     * @param edit   the shared edit register
     * @param name   the field label
     * @param value  the raw field content
     * @param length the fixed field width
     * @return the resulting field flag
     */
    private static FieldFlag editAlphaReqd(EditContext edit, String name, String value, int length) {
        String window = fixedWindow(value, length);
        if (isBlankWindow(window)) {
            edit.fail(label(name) + " must be supplied.");
            return FieldFlag.BLANK;
        }
        if (isAlphaOrSpace(window)) {
            return FieldFlag.VALID;
        }
        return edit.fail(label(name) + " can have alphabets only.");
    }

    /**
     * {@code 1230-EDIT-ALPHANUM-REQD} - a required field that may contain only
     * letters, digits and spaces.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1230-EDIT-ALPHANUM-REQD}
     * (COBOL lines 1950-2009). Blank yields {@code "<field> must be supplied."};
     * a disallowed character yields
     * {@code "<field> can have numbers or alphabets only."}.
     *
     * @param edit   the shared edit register
     * @param name   the field label
     * @param value  the raw field content
     * @param length the fixed field width
     * @return the resulting field flag
     */
    private static FieldFlag editAlphanumReqd(EditContext edit, String name, String value, int length) {
        String window = fixedWindow(value, length);
        if (isBlankWindow(window)) {
            edit.fail(label(name) + " must be supplied.");
            return FieldFlag.BLANK;
        }
        if (isAlphaNumOrSpace(window)) {
            return FieldFlag.VALID;
        }
        return edit.fail(label(name) + " can have numbers or alphabets only.");
    }

    /**
     * {@code 1235-EDIT-ALPHA-OPT} - an optional field that, when supplied, may
     * contain only alphabetic characters and spaces.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1235-EDIT-ALPHA-OPT}
     * (COBOL lines 2012-2057). Blank is valid with no error; a non-alphabetic
     * character yields {@code "<field> can have alphabets only."}.
     *
     * @param edit   the shared edit register
     * @param name   the field label
     * @param value  the raw field content
     * @param length the fixed field width
     * @return the resulting field flag
     */
    private static FieldFlag editAlphaOpt(EditContext edit, String name, String value, int length) {
        String window = fixedWindow(value, length);
        if (isBlankWindow(window)) {
            return FieldFlag.VALID;
        }
        if (isAlphaOrSpace(window)) {
            return FieldFlag.VALID;
        }
        return edit.fail(label(name) + " can have alphabets only.");
    }

    /**
     * {@code 1240-EDIT-ALPHANUM-OPT} - an optional field that, when supplied, may
     * contain only letters, digits and spaces.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1240-EDIT-ALPHANUM-OPT}
     * (COBOL lines 2060-2105). Blank is valid with no error; a disallowed
     * character yields {@code "<field> can have numbers or alphabets only."}.
     *
     * @param edit   the shared edit register
     * @param name   the field label
     * @param value  the raw field content
     * @param length the fixed field width
     * @return the resulting field flag
     */
    private static FieldFlag editAlphanumOpt(EditContext edit, String name, String value, int length) {
        String window = fixedWindow(value, length);
        if (isBlankWindow(window)) {
            return FieldFlag.VALID;
        }
        if (isAlphaNumOrSpace(window)) {
            return FieldFlag.VALID;
        }
        return edit.fail(label(name) + " can have numbers or alphabets only.");
    }

    /**
     * {@code 1245-EDIT-NUM-REQD} - a required numeric field that must be all
     * digits and non-zero.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1245-EDIT-NUM-REQD}
     * (COBOL lines 2109-2177). Blank yields {@code "<field> must be supplied."};
     * a non-numeric window yields {@code "<field> must be all numeric."}; an
     * all-zero window yields {@code "<field> must not be zero."}.
     *
     * @param edit   the shared edit register
     * @param name   the field label
     * @param value  the raw field content
     * @param length the fixed field width
     * @return the resulting field flag
     */
    private static FieldFlag editNumReqd(EditContext edit, String name, String value, int length) {
        String window = fixedWindow(value, length);
        if (isBlankWindow(window)) {
            edit.fail(label(name) + " must be supplied.");
            return FieldFlag.BLANK;
        }
        if (!isAllDigits(window)) {
            return edit.fail(label(name) + " must be all numeric.");
        }
        if (isAllZeros(window)) {
            return edit.fail(label(name) + " must not be zero.");
        }
        return FieldFlag.VALID;
    }

    /**
     * {@code 1250-EDIT-SIGNED-9V2} - a signed money field ({@code S9(10)V99})
     * must be supplied and must be a valid edited numeric literal.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1250-EDIT-SIGNED-9V2}
     * (COBOL lines 2179-2223). Blank yields {@code "<field> must be supplied."};
     * a value failing {@code FUNCTION TEST-NUMVAL-C} yields
     * {@code "<field> is not valid"} (deliberately no trailing period, matching
     * the COBOL literal). The numeric value itself is parsed later during input
     * capture via {@link #numvalC(String)} then scaled with
     * {@link CobolDecimal#money(BigDecimal)}; this edit performs validation only.
     *
     * @param edit  the shared edit register
     * @param name  the field label
     * @param value the raw edited money string
     * @return the resulting field flag
     */
    private static FieldFlag editSigned9v2(EditContext edit, String name, String value) {
        if (isBlankRaw(value)) {
            edit.fail(label(name) + " must be supplied.");
            return FieldFlag.BLANK;
        }
        if (testNumvalC(value)) {
            return FieldFlag.VALID;
        }
        return edit.fail(label(name) + " is not valid");
    }


    // ----- US phone number edit (1260 + area/prefix/line sub-paragraphs) -----

    /**
     * {@code 1260-EDIT-US-PHONE-NUM} - validates a North American Numbering Plan
     * telephone number split across area code, prefix and line number.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1260-EDIT-US-PHONE-NUM}
     * (COBOL lines 2225-2429). A phone number is optional: when all three parts
     * are blank the number is valid and skipped. Otherwise each component is
     * edited in turn by {@link #editAreaCode}, {@link #editUsPhonePrefix} and
     * {@link #editUsPhoneLineNum}; the COBOL {@code GO TO} chain that jumps from a
     * failed check to the next component is reproduced by each sub-edit returning
     * on its first failing check while the following sub-edits still run.
     *
     * <p>The COBOL all-blank guard's third conjunct compares
     * {@code WS-EDIT-US-PHONE-NUMA} against {@code SPACES} where it plainly
     * intended {@code NUMC}; after map normalization every blank part is
     * {@code LOW-VALUES}, so the defect is benign and the faithful post-
     * normalization behavior is "all three parts blank implies skip".
     *
     * @param edit  the shared edit register
     * @param name  the field label (for example {@code "Phone Number 1"})
     * @param numa  the 3-digit area code
     * @param numb  the 3-digit prefix
     * @param numc  the 4-digit line number
     * @return {@link FieldFlag#VALID} when all parts pass (or all are blank);
     *         otherwise {@link FieldFlag#NOT_OK}
     */
    private static FieldFlag editUsPhoneNum(EditContext edit, String name,
            String numa, String numb, String numc) {
        if (isBlankRaw(numa) && isBlankRaw(numb) && isBlankRaw(numc)) {
            return FieldFlag.VALID;
        }
        FieldFlag areaFlag = editAreaCode(edit, name, numa);
        FieldFlag prefixFlag = editUsPhonePrefix(edit, name, numb);
        FieldFlag lineFlag = editUsPhoneLineNum(edit, name, numc);
        if (areaFlag == FieldFlag.VALID
                && prefixFlag == FieldFlag.VALID
                && lineFlag == FieldFlag.VALID) {
            return FieldFlag.VALID;
        }
        return FieldFlag.NOT_OK;
    }

    /**
     * {@code EDIT-AREA-CODE} - validates the NANP area code component.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code EDIT-AREA-CODE}
     * (COBOL lines 2246-2318). In sequence: blank yields
     * {@code ": Area code must be supplied."}; a non-3-digit value yields
     * {@code ": Area code must be A 3 digit number."}; a zero value yields
     * {@code ": Area code cannot be zero"}; a code that is not a valid
     * general-purpose area code yields
     * {@code ": Not valid North America general purpose area code"}. Each failing
     * check returns immediately, reproducing the COBOL
     * {@code GO TO EDIT-US-PHONE-PREFIX}.
     *
     * @param edit the shared edit register
     * @param name the phone field label
     * @param numa the raw area-code content
     * @return the resulting field flag
     */
    private static FieldFlag editAreaCode(EditContext edit, String name, String numa) {
        if (isBlankRaw(numa)) {
            edit.fail(label(name) + ": Area code must be supplied.");
            return FieldFlag.BLANK;
        }
        String window = fixedWindow(numa, 3);
        if (!isAllDigits(window)) {
            return edit.fail(label(name) + ": Area code must be A 3 digit number.");
        }
        if (parseIntWindow(numa, 3) == 0) {
            return edit.fail(label(name) + ": Area code cannot be zero");
        }
        if (!LookupCodes.isValidGeneralPurposeCode(window.trim())) {
            return edit.fail(label(name) + ": Not valid North America general purpose area code");
        }
        return FieldFlag.VALID;
    }

    /**
     * {@code EDIT-US-PHONE-PREFIX} - validates the NANP prefix (exchange)
     * component.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code EDIT-US-PHONE-PREFIX}
     * (COBOL lines 2319-2367). In sequence: blank yields
     * {@code ": Prefix code must be supplied."}; a non-3-digit value yields
     * {@code ": Prefix code must be A 3 digit number."}; a zero value yields
     * {@code ": Prefix code cannot be zero"}. Each failing check returns
     * immediately, reproducing the COBOL {@code GO TO EDIT-US-PHONE-LINENUM}.
     *
     * @param edit the shared edit register
     * @param name the phone field label
     * @param numb the raw prefix content
     * @return the resulting field flag
     */
    private static FieldFlag editUsPhonePrefix(EditContext edit, String name, String numb) {
        if (isBlankRaw(numb)) {
            edit.fail(label(name) + ": Prefix code must be supplied.");
            return FieldFlag.BLANK;
        }
        String window = fixedWindow(numb, 3);
        if (!isAllDigits(window)) {
            return edit.fail(label(name) + ": Prefix code must be A 3 digit number.");
        }
        if (parseIntWindow(numb, 3) == 0) {
            return edit.fail(label(name) + ": Prefix code cannot be zero");
        }
        return FieldFlag.VALID;
    }

    /**
     * {@code EDIT-US-PHONE-LINENUM} - validates the NANP line-number component.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code EDIT-US-PHONE-LINENUM}
     * (COBOL lines 2369-2417). In sequence: blank yields
     * {@code ": Line number code must be supplied."}; a non-4-digit value yields
     * {@code ": Line number code must be A 4 digit number."}; a zero value yields
     * {@code ": Line number code cannot be zero"}. Each failing check returns
     * immediately, reproducing the COBOL {@code GO TO EDIT-US-PHONE-EXIT}.
     *
     * @param edit the shared edit register
     * @param name the phone field label
     * @param numc the raw line-number content
     * @return the resulting field flag
     */
    private static FieldFlag editUsPhoneLineNum(EditContext edit, String name, String numc) {
        if (isBlankRaw(numc)) {
            edit.fail(label(name) + ": Line number code must be supplied.");
            return FieldFlag.BLANK;
        }
        String window = fixedWindow(numc, 4);
        if (!isAllDigits(window)) {
            return edit.fail(label(name) + ": Line number code must be A 4 digit number.");
        }
        if (parseIntWindow(numc, 4) == 0) {
            return edit.fail(label(name) + ": Line number code cannot be zero");
        }
        return FieldFlag.VALID;
    }


    // ----- SSN, state, FICO and state+ZIP edits (1265-1280) -----

    /**
     * {@code 1265-EDIT-US-SSN} - validates a US Social Security Number split into
     * three parts (3 + 2 + 4 digits).
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1265-EDIT-US-SSN}
     * (COBOL lines 2431-2491). Part 1 is edited with {@code 1245-EDIT-NUM-REQD}
     * ('SSN: First 3 chars'); when it is valid, the part-1 range rule rejects
     * {@code 000}, {@code 666} and {@code 900-999} with
     * {@code ": should not be 000, 666, or between 900 and 999"}.
     *
     * <p><strong>Missing-{@code END-IF} quirk preserved:</strong> in the COBOL,
     * the {@code IF FLG-EDIT-US-SSN-PART1-ISVALID} opened at line 2448 is closed
     * only by the period at line 2488, so the edits of part 2
     * ('SSN 4th &amp; 5th chars') and part 3 ('SSN Last 4 chars') execute
     * <em>only</em> when part 1 passed its {@code 1245} edit. They still run even
     * if the part-1 range check fails. When part 1 fails {@code 1245}, parts 2 and
     * 3 are left untouched (their per-request flags default to {@link FieldFlag#VALID}).
     *
     * @param edit  the shared edit register
     * @param ssn1  the raw first 3 characters
     * @param ssn2  the raw 4th and 5th characters
     * @param ssn3  the raw last 4 characters
     * @param flags the per-request field-flag map updated for
     *              {@link ScreenField#SSN_PART1}, {@link ScreenField#SSN_PART2}
     *              and {@link ScreenField#SSN_PART3}
     */
    private static void editUsSsn(EditContext edit, String ssn1, String ssn2, String ssn3,
            Map<ScreenField, FieldFlag> flags) {
        FieldFlag part1 = editNumReqd(edit, LBL_SSN_PART1, ssn1, SSN_PART1_WIDTH);
        flags.put(ScreenField.SSN_PART1, part1);
        if (part1 == FieldFlag.VALID) {
            int part1Value = parseIntWindow(ssn1, SSN_PART1_WIDTH);
            if (part1Value == 0 || part1Value == 666 || (part1Value >= 900 && part1Value <= 999)) {
                flags.put(ScreenField.SSN_PART1,
                        edit.fail(LBL_SSN_PART1 + ": should not be 000, 666, or between 900 and 999"));
            }
            FieldFlag part2 = editNumReqd(edit, LBL_SSN_PART2, ssn2, SSN_PART2_WIDTH);
            flags.put(ScreenField.SSN_PART2, part2);
            FieldFlag part3 = editNumReqd(edit, LBL_SSN_PART3, ssn3, SSN_PART3_WIDTH);
            flags.put(ScreenField.SSN_PART3, part3);
        }
    }

    /**
     * {@code 1270-EDIT-US-STATE-CD} - validates a two-character US state or
     * territory code against {@link LookupCodes#isValidUsStateCode(String)}.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1270-EDIT-US-STATE-CD}
     * (COBOL lines 2493-2510). An unrecognized code yields
     * {@code "<field>: is not a valid state code"}.
     *
     * @param edit    the shared edit register
     * @param name    the field label (the COBOL {@code WS-EDIT-VARIABLE-NAME}, "State")
     * @param stateCd the raw state code
     * @return the resulting field flag
     */
    private static FieldFlag editUsStateCd(EditContext edit, String name, String stateCd) {
        if (LookupCodes.isValidUsStateCode(stateCd)) {
            return FieldFlag.VALID;
        }
        return edit.fail(label(name) + ": is not a valid state code");
    }

    /**
     * {@code 1275-EDIT-FICO-SCORE} - validates that the FICO score falls within
     * the inclusive range 300-850.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1275-EDIT-FICO-SCORE}
     * (COBOL lines 2514-2530), reproducing {@code 88 FICO-RANGE-IS-VALID VALUES
     * 300 THROUGH 850}. An out-of-range score yields
     * {@code "<field>: should be between 300 and 850"}. Invoked only after
     * {@code 1245-EDIT-NUM-REQD} has confirmed the field is a non-zero 3-digit
     * number, so the window is parseable.
     *
     * @param edit     the shared edit register
     * @param name     the field label (the COBOL {@code WS-EDIT-VARIABLE-NAME}, "FICO Score")
     * @param ficoRaw  the raw 3-digit FICO score
     * @return the resulting field flag
     */
    private static FieldFlag editFicoScore(EditContext edit, String name, String ficoRaw) {
        int fico = parseIntWindow(ficoRaw, FICO_WIDTH);
        if (fico >= 300 && fico <= 850) {
            return FieldFlag.VALID;
        }
        return edit.fail(label(name) + ": should be between 300 and 850");
    }

    /**
     * {@code 1280-EDIT-US-STATE-ZIP-CD} - cross-checks the state code against the
     * first two digits of the ZIP code.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1280-EDIT-US-STATE-ZIP-CD}
     * (COBOL lines 2536-2557). Builds {@code US-STATE-AND-FIRST-ZIP2} by
     * concatenating the two-character state code with the first two ZIP digits and
     * validates it against {@link LookupCodes#isValidStateZipCombo(String)}. An
     * invalid combination sets both the state and ZIP flags to
     * {@link FieldFlag#NOT_OK} and yields the fixed literal
     * {@code "Invalid zip code for state"} (no field-name prefix). Invoked only
     * when the state and ZIP have already individually validated.
     *
     * @param edit    the shared edit register
     * @param stateCd the raw two-character state code
     * @param zip      the raw ZIP code (first two digits are used)
     * @param flags    the per-request field-flag map updated for
     *                 {@link ScreenField#STATE} and {@link ScreenField#ZIP}
     */
    private static void editUsStateZipCd(EditContext edit, String stateCd, String zip,
            Map<ScreenField, FieldFlag> flags) {
        String combo = fixedWindow(stateCd, 2) + fixedWindow(zip, 2);
        if (LookupCodes.isValidStateZipCombo(combo)) {
            return;
        }
        edit.fail("Invalid zip code for state");
        flags.put(ScreenField.STATE, FieldFlag.NOT_OK);
        flags.put(ScreenField.ZIP, FieldFlag.NOT_OK);
    }


    // ================================================================
    // Phase 4 - Input capture, map-edit orchestration and change
    // detection (COBOL paragraphs 1000-PROCESS-INPUTS,
    // 1100-RECEIVE-MAP, 1200-EDIT-MAP-INPUTS, 1205-COMPARE-OLD-NEW,
    // 1210-EDIT-ACCOUNT). One Java method per COBOL paragraph.
    //
    // Origin: legacy/cbl/COACTUPC.cbl, program COACTUPC, tran CAUP.
    // ================================================================

    /**
     * {@code 1000-PROCESS-INPUTS} - top-level driver for a submitted screen:
     * receive/normalize the map, then edit it.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1000-PROCESS-INPUTS}
     * (COBOL lines 1024-1037). It performs {@code 1100-RECEIVE-MAP} then
     * {@code 1200-EDIT-MAP-INPUTS}. The COBOL tail then copies
     * {@code WS-RETURN-MSG} to {@code CCARD-ERROR-MSG} and sets the
     * pseudo-conversational self-return target ({@code CCARD-NEXT-PROG},
     * {@code -MAPSET}, {@code -MAP} to {@code LIT-THISPGM}/{@code LIT-THISMAPSET}/
     * {@code LIT-THISMAP}); in this migration the banner remains available through
     * {@link EditContext#getReturnMsg()} and the self-return navigation is applied
     * by {@code 0000-MAIN}'s common return (Phase 6) / the controller, so no
     * navigation is written here.</p>
     *
     * @param st the per-request processing state
     */
    private void processInputs(ProcessingState st) {
        // PERFORM 1100-RECEIVE-MAP THRU 1100-RECEIVE-MAP-EXIT
        st.setNewInput(captureNewDetails(st));
        // PERFORM 1200-EDIT-MAP-INPUTS THRU 1200-EDIT-MAP-INPUTS-EXIT
        editMapInputs(st);
    }

    /**
     * {@code 1100-RECEIVE-MAP} - normalizes the received map into the
     * {@link NewInput} view of {@code ACUP-NEW-DETAILS}.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1100-RECEIVE-MAP}
     * (COBOL lines 1038-1427). After {@code INITIALIZE ACUP-NEW-DETAILS} every map
     * field is normalized by the copybook idiom
     * {@code IF field = '*' OR SPACES MOVE LOW-VALUES ELSE MOVE field} - reproduced
     * by {@link #normalizeInput(String)} which yields {@code null} for the
     * {@code LOW-VALUES} "not supplied" sentinel. The account filter is captured
     * first; when the record has not yet been fetched
     * ({@code ACUP-DETAILS-NOT-FETCHED}) the paragraph returns immediately
     * (COBOL {@code GO TO 1100-RECEIVE-MAP-EXIT}), so only the search key is read.
     * The five money fields additionally parse the entered text through the COBOL
     * {@code FUNCTION TEST-NUMVAL-C}/{@code NUMVAL-C} guard: the raw string is kept
     * for the {@code 1250} edit and, only when it is a valid edited numeric, the
     * scaled {@link BigDecimal} is computed via {@link CobolDecimal#money(BigDecimal)}
     * (scale 2, {@code RoundingMode.DOWN}). The {@code ACSCITYI} map field maps to
     * {@code ACUP-NEW-CUST-ADDR-LINE-3} (city is stored in address line 3).</p>
     *
     * @param st the per-request processing state (its form is read; its change
     *           action decides the early return)
     * @return the normalized {@link NewInput}
     */
    private NewInput captureNewDetails(ProcessingState st) {
        COACTUPForm form = st.getForm();
        NewInput ni = new NewInput();

        // Account Master data - account number filter (ACCTSIDI ->
        // CC-ACCT-ID + ACUP-NEW-ACCT-ID-X).
        ni.setAccountFilter(normalizeInput(form.getAcctsid()));

        // IF ACUP-DETAILS-NOT-FETCHED GO TO 1100-RECEIVE-MAP-EXIT.
        if (st.getState().isDetailsNotFetched()) {
            return ni;
        }

        // Active Status (ACSTTUSI -> ACUP-NEW-ACTIVE-STATUS).
        ni.setActiveStatus(normalizeInput(form.getAcsttus()));

        // Credit Limit (ACRDLIMI -> ACUP-NEW-CREDIT-LIMIT-X; NUMVAL-C -> -N).
        String creditRaw = normalizeInput(form.getAcrdlim());
        ni.setCreditLimitRaw(creditRaw);
        if (creditRaw != null && testNumvalC(creditRaw)) {
            ni.setCreditLimit(CobolDecimal.money(numvalC(creditRaw)));
        }

        // Cash Limit (ACSHLIMI -> ACUP-NEW-CASH-CREDIT-LIMIT-X; NUMVAL-C -> -N).
        String cashRaw = normalizeInput(form.getAcshlim());
        ni.setCashCreditLimitRaw(cashRaw);
        if (cashRaw != null && testNumvalC(cashRaw)) {
            ni.setCashCreditLimit(CobolDecimal.money(numvalC(cashRaw)));
        }

        // Current Balance (ACURBALI -> ACUP-NEW-CURR-BAL-X; NUMVAL-C -> -N).
        String balRaw = normalizeInput(form.getAcurbal());
        ni.setCurrBalRaw(balRaw);
        if (balRaw != null && testNumvalC(balRaw)) {
            ni.setCurrBal(CobolDecimal.money(numvalC(balRaw)));
        }

        // Current Cycle Credit (ACRCYCRI -> ACUP-NEW-CURR-CYC-CREDIT-X; -> -N).
        String cycCreditRaw = normalizeInput(form.getAcrcycr());
        ni.setCurrCycCreditRaw(cycCreditRaw);
        if (cycCreditRaw != null && testNumvalC(cycCreditRaw)) {
            ni.setCurrCycCredit(CobolDecimal.money(numvalC(cycCreditRaw)));
        }

        // Current Cycle Debit (ACRCYDBI -> ACUP-NEW-CURR-CYC-DEBIT-X; -> -N).
        String cycDebitRaw = normalizeInput(form.getAcrcydb());
        ni.setCurrCycDebitRaw(cycDebitRaw);
        if (cycDebitRaw != null && testNumvalC(cycDebitRaw)) {
            ni.setCurrCycDebit(CobolDecimal.money(numvalC(cycDebitRaw)));
        }

        // Open date parts (OPNYEARI/OPNMONI/OPNDAYI).
        ni.setOpenYear(normalizeInput(form.getOpnyear()));
        ni.setOpenMon(normalizeInput(form.getOpnmon()));
        ni.setOpenDay(normalizeInput(form.getOpnday()));

        // Expiry date parts (EXPYEARI/EXPMONI/EXPDAYI).
        ni.setExpYear(normalizeInput(form.getExpyear()));
        ni.setExpMon(normalizeInput(form.getExpmon()));
        ni.setExpDay(normalizeInput(form.getExpday()));

        // Reissue date parts (RISYEARI/RISMONI/RISDAYI).
        ni.setReissueYear(normalizeInput(form.getRisyear()));
        ni.setReissueMon(normalizeInput(form.getRismon()));
        ni.setReissueDay(normalizeInput(form.getRisday()));

        // Account Group (AADDGRPI -> ACUP-NEW-GROUP-ID).
        ni.setGroupId(normalizeInput(form.getAaddgrp()));

        // Customer Master data - customer id (ACSTNUMI, not editable).
        ni.setCustId(normalizeInput(form.getAcstnum()));

        // Social Security Number parts (ACTSSN1I/ACTSSN2I/ACTSSN3I).
        ni.setSsn1(normalizeInput(form.getActssn1()));
        ni.setSsn2(normalizeInput(form.getActssn2()));
        ni.setSsn3(normalizeInput(form.getActssn3()));

        // Date of birth parts (DOBYEARI/DOBMONI/DOBDAYI).
        ni.setDobYear(normalizeInput(form.getDobyear()));
        ni.setDobMon(normalizeInput(form.getDobmon()));
        ni.setDobDay(normalizeInput(form.getDobday()));

        // FICO (ACSTFCOI -> ACUP-NEW-CUST-FICO-SCORE-X).
        ni.setFico(normalizeInput(form.getAcstfco()));

        // Names (ACSFNAMI/ACSMNAMI/ACSLNAMI).
        ni.setFirstName(normalizeInput(form.getAcsfnam()));
        ni.setMiddleName(normalizeInput(form.getAcsmnam()));
        ni.setLastName(normalizeInput(form.getAcslnam()));

        // Address: line 1, line 2, city -> address line 3, state, country, zip.
        ni.setAddrLine1(normalizeInput(form.getAcsadl1()));
        ni.setAddrLine2(normalizeInput(form.getAcsadl2()));
        ni.setAddrLine3(normalizeInput(form.getAcscity()));
        ni.setStateCd(normalizeInput(form.getAcsstte()));
        ni.setCountryCd(normalizeInput(form.getAcsctry()));
        ni.setZip(normalizeInput(form.getAcszipc()));

        // Phone number 1 parts (ACSPH1AI/ACSPH1BI/ACSPH1CI).
        ni.setPhone1a(normalizeInput(form.getAcsph1a()));
        ni.setPhone1b(normalizeInput(form.getAcsph1b()));
        ni.setPhone1c(normalizeInput(form.getAcsph1c()));

        // Phone number 2 parts (ACSPH2AI/ACSPH2BI/ACSPH2CI).
        ni.setPhone2a(normalizeInput(form.getAcsph2a()));
        ni.setPhone2b(normalizeInput(form.getAcsph2b()));
        ni.setPhone2c(normalizeInput(form.getAcsph2c()));

        // Government Id (ACSGOVTI), EFT code (ACSEFTCI), primary holder (ACSPFLGI).
        ni.setGovtId(normalizeInput(form.getAcsgovt()));
        ni.setEft(normalizeInput(form.getAcseftc()));
        ni.setPriHolder(normalizeInput(form.getAcspflg()));

        return ni;
    }

    /**
     * Reproduces the COBOL receive-map normalization idiom
     * {@code IF field = '*' OR field = SPACES MOVE LOW-VALUES ELSE MOVE field}.
     *
     * <p>A fixed-width alphanumeric map field equals {@code SPACES} when it is
     * entirely blank and equals {@code '*'} when its first byte is an asterisk and
     * the remainder is blank; either case stores {@code LOW-VALUES} (the
     * "not supplied" sentinel), modeled here as {@code null}. Every other value is
     * carried through verbatim.</p>
     *
     * @param raw the received map field value (may be {@code null})
     * @return {@code null} for the {@code LOW-VALUES} sentinel, otherwise {@code raw}
     */
    private static String normalizeInput(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        if (stripped.isEmpty() || "*".equals(stripped)) {
            return null;
        }
        return raw;
    }

    /**
     * {@code 1210-EDIT-ACCOUNT} - validates the account-number search key.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1210-EDIT-ACCOUNT}
     * (COBOL lines 1785-1821). The flag starts {@code FLG-ACCTFILTER-NOT-OK}. A
     * blank key ({@code LOW-VALUES}/{@code SPACES}) sets {@code INPUT-ERROR} and
     * {@code FLG-ACCTFILTER-BLANK}, records the guarded prompt
     * {@code "Account number not provided"}, zeroes the context account id and
     * exits. Otherwise the key must be an 11-digit non-zero number: a value that is
     * not all digits (COBOL {@code IS NOT NUMERIC}, which a fixed {@code X(11)}
     * field fails on any trailing space) or is all zeros sets {@code INPUT-ERROR}
     * with the guarded message {@code "Account Number if supplied must be a 11
     * digit Non-Zero Number"} and zeroes the context id; a valid key is stored in
     * the context ({@code CDEMO-ACCT-ID}) and flagged {@code FLG-ACCTFILTER-ISVALID}.</p>
     *
     * @param st the per-request processing state
     */
    private void editAccount(ProcessingState st) {
        EditContext edit = st.getEdit();
        // SET FLG-ACCTFILTER-NOT-OK TO TRUE
        st.setAccountFilter(FilterFlag.NOT_OK);

        // CC-ACCT-ID (null models LOW-VALUES/SPACES).
        String ccAcctId = st.getNewInput().getAccountFilter();

        // IF CC-ACCT-ID = LOW-VALUES OR SPACES
        if (ccAcctId == null) {
            edit.fail(MSG_PROMPT_FOR_ACCT);
            st.setAccountFilter(FilterFlag.BLANK);
            context.setAcctId(0L);
            st.setFieldFlag(ScreenField.ACCOUNT_FILTER, FieldFlag.BLANK);
            return;
        }

        // MOVE CC-ACCT-ID TO ACUP-NEW-ACCT-ID (retained in NewInput.accountFilter).
        // IF CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID-N = ZEROS
        String window = fixedWindow(ccAcctId, ACCT_ID_WIDTH);
        if (!isAllDigits(window) || isAllZeros(window)) {
            edit.fail(MSG_ACCT_FILTER_INVALID);
            context.setAcctId(0L);
            st.setFieldFlag(ScreenField.ACCOUNT_FILTER, FieldFlag.NOT_OK);
            return;
        }

        // ELSE valid - store into context and flag valid.
        context.setAcctId(Long.parseLong(window.trim()));
        st.setAccountFilter(FilterFlag.VALID);
        st.setFieldFlag(ScreenField.ACCOUNT_FILTER, FieldFlag.VALID);
    }


    /**
     * {@code 1200-EDIT-MAP-INPUTS} - full field-edit sequence for a submitted map.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1200-EDIT-MAP-INPUTS}
     * (COBOL lines 1429-1677). Control flow preserved 1:1:</p>
     * <ol>
     * <li>{@code SET INPUT-OK} - the per-request {@link EditContext} starts clean.</li>
     * <li>When {@code ACUP-DETAILS-NOT-FETCHED}: edit only the search key
     *     ({@code 1210-EDIT-ACCOUNT}); a blank key forces the banner
     *     {@code NO-SEARCH-CRITERIA-RECEIVED} ({@code "No input received"}) - an
     *     unconditional {@code SET} that overwrites the {@code 1210} prompt - then
     *     exits (nothing else to edit before a fetch).</li>
     * <li>Otherwise mark the account and customer found/valid, then
     *     {@code 1205-COMPARE-OLD-NEW}. If nothing changed, or the action is
     *     already {@code CHANGES-OK-NOT-CONFIRMED} or {@code CHANGES-OKAYED-AND-DONE},
     *     reset the per-field flags ({@code MOVE LOW-VALUES TO WS-NON-KEY-FLAGS})
     *     and exit.</li>
     * <li>Set {@code CHANGES-NOT-OK}, then run the 24 field edits in COBOL order,
     *     each labeled by its {@code WS-EDIT-VARIABLE-NAME}. FICO runs
     *     {@code 1245} then (if valid) {@code 1275}; State runs {@code 1225} then
     *     (if valid) {@code 1270}; the cross-field {@code 1280} state+ZIP edit runs
     *     only when both fields individually validated.</li>
     * <li>Finally, when no field errored, promote the action to
     *     {@code CHANGES-OK-NOT-CONFIRMED} (edits valid, awaiting PF5 confirm).</li>
     * </ol>
     *
     * @param st the per-request processing state
     */
    private void editMapInputs(ProcessingState st) {
        EditContext edit = st.getEdit();
        NewInput ni = st.getNewInput();
        AccountUpdateState session = st.getState();

        // SET INPUT-OK TO TRUE (the fresh EditContext is already INPUT-OK).

        // IF ACUP-DETAILS-NOT-FETCHED
        if (session.isDetailsNotFetched()) {
            // PERFORM 1210-EDIT-ACCOUNT THRU 1210-EDIT-ACCOUNT-EXIT
            editAccount(st);
            // MOVE LOW-VALUES TO ACUP-OLD-ACCT-DATA - no fetched snapshot exists in
            // the not-fetched path, so there is nothing to clear.
            // IF FLG-ACCTFILTER-BLANK SET NO-SEARCH-CRITERIA-RECEIVED
            if (st.getAccountFilter() == FilterFlag.BLANK) {
                edit.forceMessage(MSG_NO_INPUT_RECEIVED);
            }
            // GO TO 1200-EDIT-MAP-INPUTS-EXIT
            return;
        }

        // Search keys already validated and data fetched.
        st.setFoundAcctInMaster(true);          // SET FOUND-ACCOUNT-DATA / FOUND-ACCT-IN-MASTER
        st.setAccountFilter(FilterFlag.VALID);  // SET FLG-ACCTFILTER-ISVALID
        st.setFoundCustInMaster(true);          // SET FOUND-CUST-IN-MASTER
        st.setCustomerFilter(FilterFlag.VALID); // SET FLG-CUSTFILTER-ISVALID

        // PERFORM 1205-COMPARE-OLD-NEW THRU 1205-COMPARE-OLD-NEW-EXIT
        compareOldNew(st);

        // IF NO-CHANGES-FOUND OR ACUP-CHANGES-OK-NOT-CONFIRMED
        //    OR ACUP-CHANGES-OKAYED-AND-DONE
        ChangeAction action = session.getChangeAction();
        if (!st.isChangeHasOccurred()
                || action == ChangeAction.CHANGES_OK_NOT_CONFIRMED
                || action == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            // MOVE LOW-VALUES TO WS-NON-KEY-FLAGS (reset per-field flags to VALID)
            st.getFieldFlags().clear();
            // GO TO 1200-EDIT-MAP-INPUTS-EXIT
            return;
        }

        // SET ACUP-CHANGES-NOT-OK TO TRUE
        session.setChangeAction(ChangeAction.CHANGES_NOT_OK);

        // -------- 24-field edit sequence (WS-EDIT-VARIABLE-NAME labels) --------

        // 'Account Status' -> 1220-EDIT-YESNO
        st.setFieldFlag(ScreenField.ACCT_STATUS,
                editYesNo(edit, LBL_ACCT_STATUS, ni.getActiveStatus()));

        // 'Open Date' -> EDIT-DATE-CCYYMMDD
        editDate(st, ScreenField.OPEN_DATE, ni.composeOpenDate(), LBL_OPEN_DATE, false);

        // 'Credit Limit' -> 1250-EDIT-SIGNED-9V2
        st.setFieldFlag(ScreenField.CREDIT_LIMIT,
                editSigned9v2(edit, LBL_CREDIT_LIMIT, ni.getCreditLimitRaw()));

        // 'Expiry Date' -> EDIT-DATE-CCYYMMDD
        editDate(st, ScreenField.EXPIRY_DATE, ni.composeExpiryDate(), LBL_EXPIRY_DATE, false);

        // 'Cash Credit Limit' -> 1250-EDIT-SIGNED-9V2
        st.setFieldFlag(ScreenField.CASH_CREDIT_LIMIT,
                editSigned9v2(edit, LBL_CASH_CREDIT_LIMIT, ni.getCashCreditLimitRaw()));

        // 'Reissue Date' -> EDIT-DATE-CCYYMMDD
        editDate(st, ScreenField.REISSUE_DATE, ni.composeReissueDate(), LBL_REISSUE_DATE, false);

        // 'Current Balance' -> 1250-EDIT-SIGNED-9V2
        st.setFieldFlag(ScreenField.CURRENT_BALANCE,
                editSigned9v2(edit, LBL_CURRENT_BALANCE, ni.getCurrBalRaw()));

        // 'Current Cycle Credit Limit' -> 1250-EDIT-SIGNED-9V2
        st.setFieldFlag(ScreenField.CURRENT_CYCLE_CREDIT,
                editSigned9v2(edit, LBL_CURRENT_CYCLE_CREDIT, ni.getCurrCycCreditRaw()));

        // 'Current Cycle Debit Limit' -> 1250-EDIT-SIGNED-9V2
        st.setFieldFlag(ScreenField.CURRENT_CYCLE_DEBIT,
                editSigned9v2(edit, LBL_CURRENT_CYCLE_DEBIT, ni.getCurrCycDebitRaw()));

        // 'SSN' -> 1265-EDIT-US-SSN (sets SSN_PART1/2/3 flags with part labels)
        editUsSsn(edit, ni.getSsn1(), ni.getSsn2(), ni.getSsn3(), st.getFieldFlags());

        // 'Date of Birth' -> EDIT-DATE-CCYYMMDD then (if valid) EDIT-DATE-OF-BIRTH
        editDate(st, ScreenField.DATE_OF_BIRTH, ni.composeDob(), LBL_DATE_OF_BIRTH, true);

        // 'FICO Score' -> 1245-EDIT-NUM-REQD then (if valid) 1275-EDIT-FICO-SCORE
        FieldFlag ficoFlag = editNumReqd(edit, LBL_FICO_SCORE, ni.getFico(), FICO_WIDTH);
        st.setFieldFlag(ScreenField.FICO_SCORE, ficoFlag);
        if (ficoFlag == FieldFlag.VALID) {
            st.setFieldFlag(ScreenField.FICO_SCORE,
                    editFicoScore(edit, LBL_FICO_SCORE, ni.getFico()));
        }

        // 'First Name' -> 1225-EDIT-ALPHA-REQD
        st.setFieldFlag(ScreenField.FIRST_NAME,
                editAlphaReqd(edit, LBL_FIRST_NAME, ni.getFirstName(), NAME_WIDTH));

        // 'Middle Name' -> 1235-EDIT-ALPHA-OPT
        st.setFieldFlag(ScreenField.MIDDLE_NAME,
                editAlphaOpt(edit, LBL_MIDDLE_NAME, ni.getMiddleName(), NAME_WIDTH));

        // 'Last Name' -> 1225-EDIT-ALPHA-REQD
        st.setFieldFlag(ScreenField.LAST_NAME,
                editAlphaReqd(edit, LBL_LAST_NAME, ni.getLastName(), NAME_WIDTH));

        // 'Address Line 1' -> 1215-EDIT-MANDATORY
        st.setFieldFlag(ScreenField.ADDRESS_LINE_1,
                editMandatory(edit, LBL_ADDRESS_LINE_1, ni.getAddrLine1(), ADDRESS_WIDTH));

        // 'State' -> 1225-EDIT-ALPHA-REQD then (if valid) 1270-EDIT-US-STATE-CD
        FieldFlag stateFlag = editAlphaReqd(edit, LBL_STATE, ni.getStateCd(), STATE_WIDTH);
        st.setFieldFlag(ScreenField.STATE, stateFlag);
        if (stateFlag == FieldFlag.VALID) {
            st.setFieldFlag(ScreenField.STATE,
                    editUsStateCd(edit, LBL_STATE, ni.getStateCd()));
        }

        // 'Zip' -> 1245-EDIT-NUM-REQD
        st.setFieldFlag(ScreenField.ZIP,
                editNumReqd(edit, LBL_ZIP, ni.getZip(), ZIP_EDIT_WIDTH));

        // 'City' (ACUP-NEW-CUST-ADDR-LINE-3) -> 1225-EDIT-ALPHA-REQD
        st.setFieldFlag(ScreenField.CITY,
                editAlphaReqd(edit, LBL_CITY, ni.getAddrLine3(), ADDRESS_WIDTH));

        // 'Country' -> 1225-EDIT-ALPHA-REQD
        st.setFieldFlag(ScreenField.COUNTRY,
                editAlphaReqd(edit, LBL_COUNTRY, ni.getCountryCd(), COUNTRY_WIDTH));

        // 'Phone Number 1' -> 1260-EDIT-US-PHONE-NUM
        st.setFieldFlag(ScreenField.PHONE_NUM_1,
                editUsPhoneNum(edit, LBL_PHONE_NUM_1, ni.getPhone1a(), ni.getPhone1b(), ni.getPhone1c()));

        // 'Phone Number 2' -> 1260-EDIT-US-PHONE-NUM
        st.setFieldFlag(ScreenField.PHONE_NUM_2,
                editUsPhoneNum(edit, LBL_PHONE_NUM_2, ni.getPhone2a(), ni.getPhone2b(), ni.getPhone2c()));

        // 'EFT Account Id' -> 1245-EDIT-NUM-REQD
        st.setFieldFlag(ScreenField.EFT_ACCOUNT_ID,
                editNumReqd(edit, LBL_EFT_ACCOUNT_ID, ni.getEft(), EFT_WIDTH));

        // 'Primary Card Holder' -> 1220-EDIT-YESNO
        st.setFieldFlag(ScreenField.PRIMARY_CARD_HOLDER,
                editYesNo(edit, LBL_PRIMARY_CARD_HOLDER, ni.getPriHolder()));

        // Cross field edits: IF FLG-STATE-ISVALID AND FLG-ZIPCODE-ISVALID
        //                     PERFORM 1280-EDIT-US-STATE-ZIP-CD
        if (st.getFieldFlag(ScreenField.STATE) == FieldFlag.VALID
                && st.getFieldFlag(ScreenField.ZIP) == FieldFlag.VALID) {
            editUsStateZipCd(edit, ni.getStateCd(), ni.getZip(), st.getFieldFlags());
        }

        // IF INPUT-ERROR CONTINUE ELSE SET ACUP-CHANGES-OK-NOT-CONFIRMED TO TRUE
        if (!edit.isInputError()) {
            session.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
            // Review finding #10: capture the just-validated ACUP-NEW-DETAILS server
            // side and issue the single-use confirmation token. The COBOL leaves the
            // validated new details in WS-THIS-PROGCOMMAREA; the Java equivalent must
            // carry them explicitly so the PF5 commit uses the validated values, not
            // the (protected on the mainframe, but forgeable over HTTP) re-post.
            armConfirmation(session, st);
        }
    }

    /**
     * Reproduces the {@code 1200} date-field editing idiom
     * {@code MOVE ACUP-NEW-*-DATE TO WS-EDIT-DATE-CCYYMMDD; PERFORM EDIT-DATE-CCYYMMDD}
     * (and, for the date of birth, the subsequent
     * {@code IF ...-ISVALID PERFORM EDIT-DATE-OF-BIRTH}) by delegating to
     * {@link DateConversionSupport} - the migration of copybooks {@code CSUTLDPY} /
     * {@code CSUTLDWY} (ultimately {@code CSUTLDTC}).
     *
     * <p>The delegate returns a {@link DateConversionSupport.DateEditResult} whose
     * {@code returnMessage} is already prefixed with the field name, so it is routed
     * through {@link EditContext#fail(String)} to honor the first-error-wins banner
     * guard ({@code WS-RETURN-MSG-OFF}) and set {@code INPUT-ERROR}. When
     * {@code dob} is {@code true} and the calendar edit passed, the future-date
     * check ({@code EDIT-DATE-OF-BIRTH}) is applied and its result supersedes the
     * calendar result, matching the COBOL {@code IF WS-EDIT-DT-OF-BIRTH-ISVALID}
     * gate.</p>
     *
     * @param st       the per-request processing state
     * @param field    the screen field whose validity flag is recorded
     * @param ccyymmdd the composed {@code CCYYMMDD} candidate date
     * @param label    the field label (the COBOL {@code WS-EDIT-VARIABLE-NAME})
     * @param dob      {@code true} to additionally apply the date-of-birth
     *                 future-date check
     */
    private void editDate(ProcessingState st, ScreenField field, String ccyymmdd,
            String label, boolean dob) {
        EditContext edit = st.getEdit();
        DateConversionSupport.DateEditResult result =
                this.dateConversionSupport.editDateCcyymmdd(ccyymmdd, label);
        if (dob && result.isValid()) {
            result = this.dateConversionSupport.editDateOfBirth(ccyymmdd, label);
        }
        if (result.isValid()) {
            st.setFieldFlag(field, FieldFlag.VALID);
        } else {
            edit.fail(result.returnMessage());
            st.setFieldFlag(field, FieldFlag.NOT_OK);
        }
    }


    /**
     * {@code 1205-COMPARE-OLD-NEW} - detects whether the submitted values differ
     * from the fetched baseline (the optimistic-lock change detector).
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 1205-COMPARE-OLD-NEW}
     * (COBOL lines 1680-1782). Starts {@code NO-CHANGES-FOUND}. The account block
     * is compared first as a single {@code AND} chain; any difference sets
     * {@code CHANGE-HAS-OCCURRED} and returns. When the account block matches, the
     * customer block is compared; when it also matches, the informational banner
     * {@code NO-CHANGES-DETECTED} ({@code "No change detected with respect to
     * values fetched."}) is set (an unconditional {@code SET}) and the change flag
     * stays {@code NO-CHANGES-FOUND}; otherwise {@code CHANGE-HAS-OCCURRED} is set.</p>
     *
     * <p>Per-field comparison semantics are reproduced exactly (see the field
     * helpers): the account id, the three dates, both split phone numbers, the SSN,
     * the date of birth, the EFT id and the FICO score are compared raw (byte for
     * byte); the active status is compared upper-cased; the group id and all
     * customer name/address/indicator fields are compared upper-cased and trimmed;
     * the five money fields are compared as scaled {@link BigDecimal}. A
     * {@code null} (COBOL {@code LOW-VALUES}) submitted value never equals the
     * fetched baseline, faithfully reproducing the legacy behavior where a blanked
     * field is treated as a change.</p>
     *
     * @param st the per-request processing state
     */
    private void compareOldNew(ProcessingState st) {
        NewInput ni = st.getNewInput();
        AccountSnapshot old = st.getState().getOldSnapshot();
        if (old == null) {
            // Defensive: the fetched path always has a snapshot; an empty baseline
            // makes every populated field register as changed.
            old = new AccountSnapshot();
        }

        // SET NO-CHANGES-FOUND TO TRUE
        st.setChangeHasOccurred(false);

        // Account block - any difference is a change.
        if (!accountBlockUnchanged(ni, old)) {
            st.setChangeHasOccurred(true);
            return;
        }

        // Customer block - all equal yields NO-CHANGES-DETECTED, else a change.
        if (customerBlockUnchanged(ni, old)) {
            st.getEdit().forceMessage(MSG_NO_CHANGES_DETECTED);
        } else {
            st.setChangeHasOccurred(true);
        }
    }

    /**
     * Reproduces the account-data {@code AND} chain of {@code 1205-COMPARE-OLD-NEW}
     * (COBOL lines 1684-1704): account id (raw), active status (upper-cased), the
     * five money amounts (scaled {@link BigDecimal}), the open/expiry/reissue dates
     * (raw {@code CCYYMMDD}), and the group id (upper-cased and trimmed).
     *
     * @param ni  the normalized submitted values ({@code ACUP-NEW-ACCT-DATA})
     * @param old the fetched baseline ({@code ACUP-OLD-ACCT-DATA})
     * @return {@code true} when every account field is unchanged
     */
    private static boolean accountBlockUnchanged(NewInput ni, AccountSnapshot old) {
        return rawEq(ni.getAccountFilter(), old.getAcctId(), ACCT_ID_WIDTH)
                && upperEq(ni.getActiveStatus(), old.getActiveStatus(), 1)
                && moneyEq(ni.getCurrBal(), old.getCurrBal())
                && moneyEq(ni.getCreditLimit(), old.getCreditLimit())
                && moneyEq(ni.getCashCreditLimit(), old.getCashCreditLimit())
                && rawEq(ni.composeOpenDate(), old.getOpenDate(), DATE_WIDTH)
                && rawEq(ni.composeExpiryDate(), old.getExpirationDate(), DATE_WIDTH)
                && rawEq(ni.composeReissueDate(), old.getReissueDate(), DATE_WIDTH)
                && moneyEq(ni.getCurrCycCredit(), old.getCurrCycCredit())
                && moneyEq(ni.getCurrCycDebit(), old.getCurrCycDebit())
                && upperTrimEq(ni.getGroupId(), old.getGroupId());
    }

    /**
     * Reproduces the customer-data {@code AND} chain of
     * {@code 1205-COMPARE-OLD-NEW} (COBOL lines 1708-1770): customer id, names,
     * address lines, state/country/zip and the primary-holder indicator
     * (upper-cased and trimmed); both split phone numbers, the SSN, the date of
     * birth, the EFT id and the FICO score (raw); and the government id
     * (upper-cased and trimmed).
     *
     * @param ni  the normalized submitted values ({@code ACUP-NEW-CUST-DATA})
     * @param old the fetched baseline ({@code ACUP-OLD-CUST-DATA})
     * @return {@code true} when every customer field is unchanged
     */
    private static boolean customerBlockUnchanged(NewInput ni, AccountSnapshot old) {
        return upperTrimEq(ni.getCustId(), old.getCustId())
                && upperTrimEq(ni.getFirstName(), old.getFirstName())
                && upperTrimEq(ni.getMiddleName(), old.getMiddleName())
                && upperTrimEq(ni.getLastName(), old.getLastName())
                && upperTrimEq(ni.getAddrLine1(), old.getAddrLine1())
                && upperTrimEq(ni.getAddrLine2(), old.getAddrLine2())
                && upperTrimEq(ni.getAddrLine3(), old.getAddrLine3())
                && upperTrimEq(ni.getStateCd(), old.getAddrStateCd())
                && upperTrimEq(ni.getCountryCd(), old.getAddrCountryCd())
                && upperTrimEq(ni.getZip(), old.getAddrZip())
                && rawEq(ni.getPhone1a(), oldPhonePart(old.getPhoneNum1(), 0), 3)
                && rawEq(ni.getPhone1b(), oldPhonePart(old.getPhoneNum1(), 1), 3)
                && rawEq(ni.getPhone1c(), oldPhonePart(old.getPhoneNum1(), 2), 4)
                && rawEq(ni.getPhone2a(), oldPhonePart(old.getPhoneNum2(), 0), 3)
                && rawEq(ni.getPhone2b(), oldPhonePart(old.getPhoneNum2(), 1), 3)
                && rawEq(ni.getPhone2c(), oldPhonePart(old.getPhoneNum2(), 2), 4)
                && rawEq(ni.composeSsn(), old.getSsn(), SSN_WIDTH)
                && upperTrimEq(ni.getGovtId(), old.getGovtIssuedId())
                && rawEq(ni.composeDob(), old.getDateOfBirth(), DATE_WIDTH)
                && rawEq(ni.getEft(), old.getEftAccountId(), EFT_WIDTH)
                && upperTrimEq(ni.getPriHolder(), old.getPriHolderInd())
                && rawEq(ni.getFico(), old.getFicoScore(), FICO_WIDTH);
    }

    /**
     * Raw fixed-width comparison ({@code ACUP-NEW-x = ACUP-OLD-x}). Both sides are
     * imaged to {@code width} characters (the submitted value is space-padded like
     * a COBOL {@code MOVE} to {@code PIC X(width)}; a {@code null} old value images
     * to spaces). A {@code null} submitted value ({@code LOW-VALUES}) never matches
     * the fetched baseline, so it always reports "changed".
     *
     * @param newVal the submitted value (may be {@code null})
     * @param oldVal the fetched baseline value (may be {@code null})
     * @param width  the COBOL fixed field width
     * @return {@code true} when the two fixed-width images are byte-equal
     */
    private static boolean rawEq(String newVal, String oldVal, int width) {
        return newVal != null && fixedWindow(newVal, width).equals(fixedWindow(oldVal, width));
    }

    /**
     * Upper-cased fixed-width comparison
     * ({@code FUNCTION UPPER-CASE(new) = FUNCTION UPPER-CASE(old)}) used for the
     * account active-status field. A {@code null} submitted value always reports
     * "changed".
     *
     * @param newVal the submitted value (may be {@code null})
     * @param oldVal the fetched baseline value (may be {@code null})
     * @param width  the COBOL fixed field width
     * @return {@code true} when the upper-cased images are equal
     */
    private static boolean upperEq(String newVal, String oldVal, int width) {
        return newVal != null
                && fixedWindow(newVal, width).toUpperCase(Locale.ROOT)
                        .equals(fixedWindow(oldVal, width).toUpperCase(Locale.ROOT));
    }

    /**
     * Upper-cased, trimmed comparison
     * ({@code FUNCTION UPPER-CASE(FUNCTION TRIM(new)) = FUNCTION UPPER-CASE(FUNCTION
     * TRIM(old))}) used for the group id and every customer name/address/indicator
     * field. Trimming removes only ASCII spaces (COBOL {@code FUNCTION TRIM}),
     * leaving any interior spacing intact. A {@code null} submitted value always
     * reports "changed"; a {@code null} old value is treated as an empty string.
     *
     * @param newVal the submitted value (may be {@code null})
     * @param oldVal the fetched baseline value (may be {@code null})
     * @return {@code true} when the upper-cased, trimmed values are equal
     */
    private static boolean upperTrimEq(String newVal, String oldVal) {
        return newVal != null
                && cobolTrim(newVal).toUpperCase(Locale.ROOT)
                        .equals(cobolTrim(nz(oldVal)).toUpperCase(Locale.ROOT));
    }

    /**
     * Money comparison ({@code ACUP-NEW-x = ACUP-OLD-x} on the {@code S9(10)V99}
     * redefine). The submitted amount is a parsed {@link BigDecimal} (or
     * {@code null} when the entered text was blank or not a valid edited numeric,
     * in which case the COBOL {@code X(12)} base holds {@code SPACES} and never
     * equals the zoned baseline, so this reports "changed"). Both amounts are
     * scaled to two decimals with {@link CobolDecimal#money(BigDecimal)} and
     * compared numerically, which is equivalent to the COBOL byte comparison of two
     * identically zoned representations.
     *
     * @param newBd the submitted amount (may be {@code null})
     * @param oldBd the fetched baseline amount (may be {@code null})
     * @return {@code true} when both amounts are numerically equal at scale 2
     */
    private static boolean moneyEq(BigDecimal newBd, BigDecimal oldBd) {
        if (newBd == null) {
            return false;
        }
        BigDecimal oldValue = (oldBd == null) ? BigDecimal.ZERO : oldBd;
        return CobolDecimal.money(newBd).compareTo(CobolDecimal.money(oldValue)) == 0;
    }

    /**
     * Extracts a phone-number component from the fetched {@code X(15)}
     * {@code "(999)999-9999"} baseline, mirroring the COBOL
     * {@code ACUP-OLD-CUST-PHONE-NUM-nX} redefine (a leading {@code FILLER} then the
     * area code, {@code FILLER}, prefix, {@code FILLER}, line number). The string is
     * imaged to 15 characters first so a blank baseline yields blank components.
     *
     * @param x15  the fetched formatted phone number (may be {@code null})
     * @param part {@code 0} for the 3-digit area code, {@code 1} for the 3-digit
     *             prefix, otherwise the 4-digit line number
     * @return the requested fixed-width component
     */
    private static String oldPhonePart(String x15, int part) {
        String p = fixedWindow(x15, 15);
        return switch (part) {
            case 0 -> p.substring(1, 4);
            case 1 -> p.substring(5, 8);
            default -> p.substring(9, 13);
        };
    }

    /**
     * Reproduces COBOL {@code FUNCTION TRIM}: removes leading and trailing ASCII
     * spaces only (interior spacing and any non-space characters are preserved).
     *
     * @param value the value to trim (must not be {@code null})
     * @return the value with leading and trailing spaces removed
     */
    private static String cobolTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Null-to-empty helper: maps a {@code null} fetched value to the empty string
     * so it images to spaces during comparison.
     *
     * @param value the value (may be {@code null})
     * @return {@code value}, or the empty string when {@code value} is {@code null}
     */
    private static String nz(String value) {
        return (value == null) ? "" : value;
    }

    /**
     * Renders a monetary {@link BigDecimal} to its display string using
     * {@link BigDecimal#toPlainString()} (value-faithful, no exponent, no
     * floating point), matching {@code COACTVWC}'s {@code formatAmount}. A
     * {@code null} amount renders as the empty string.
     *
     * <p>The COBOL {@code 3202} moves each amount through the
     * {@code WS-EDIT-CURRENCY-9-2-F} edit mask, but the {@code COACTUP} money
     * fields are <em>editable</em> and must round-trip back through
     * {@code NUMVAL-C} ({@link #numvalC(String)}); the plain string is the
     * value-faithful form {@code NUMVAL-C} accepts, so display-then-resubmit is a
     * numeric no-op (documented parity choice - AAP &sect;0.6.1 decimal fidelity).</p>
     *
     * @param amount the monetary value, or {@code null}
     * @return the plain-string representation, or {@code ""} when {@code null}
     */
    private static String formatAmount(BigDecimal amount) {
        return (amount == null) ? "" : amount.toPlainString();
    }

    /**
     * Extracts a date component from the fetched compact {@code CCYYMMDD}
     * baseline, the exact inverse of {@link NewInput#composeOpenDate()} /
     * {@link NewInput#composeDob()} (which concatenate {@code seg(year,4) +
     * seg(mon,2) + seg(day,2)}). The value is imaged to eight characters first, so
     * a {@code null}/blank baseline yields blank components that image back to the
     * same eight spaces during {@code 1205-COMPARE-OLD-NEW}.
     *
     * @param ccyymmdd the fetched compact date (may be {@code null})
     * @param part     {@code 0} for the 4-digit year, {@code 1} for the 2-digit
     *                 month, otherwise the 2-digit day
     * @return the requested fixed-width component
     */
    private static String oldDatePart(String ccyymmdd, int part) {
        String d = fixedWindow(ccyymmdd, 8);
        return switch (part) {
            case 0 -> d.substring(0, 4);
            case 1 -> d.substring(4, 6);
            default -> d.substring(6, 8);
        };
    }

    /**
     * Extracts an SSN component from the fetched 9-digit baseline, the exact
     * inverse of {@link NewInput#composeSsn()} (which concatenates
     * {@code seg(ssn1,3) + seg(ssn2,2) + seg(ssn3,4)}), mirroring the COBOL
     * {@code MOVE ACUP-OLD-CUST-SSN-X(1:3) / (4:2) / (6:4)}. The value is imaged
     * to nine characters first so a blank baseline yields blank components.
     *
     * @param ssn9 the fetched 9-digit SSN (may be {@code null})
     * @param part {@code 0} for the 3-digit area, {@code 1} for the 2-digit group,
     *             otherwise the 4-digit serial
     * @return the requested fixed-width component
     */
    private static String oldSsnPart(String ssn9, int part) {
        String s = fixedWindow(ssn9, 9);
        return switch (part) {
            case 0 -> s.substring(0, 3);
            case 1 -> s.substring(3, 5);
            default -> s.substring(5, 9);
        };
    }

    /**
     * Renders the stored ZIP for the {@code ACSZIPC} display field, reproducing the legacy
     * {@code MOVE ACUP-OLD-CUST-ADDR-ZIP TO ACSZIPCO OF CACTUPAO} (legacy/cbl/COACTUPC.cbl:2843).
     *
     * <p>The persisted ZIP is {@code CUST-ADDR-ZIP PIC X(10)} ({@code CHAR(10)}), but the BMS field
     * {@code ACSZIPCO}/{@code ACSZIPCI} is {@code PIC X(5)} (legacy/cpy-bms/COACTVW.CPY:410,186). A
     * COBOL alphanumeric {@code MOVE} from a longer to a shorter field is left-justified with right
     * truncation, so the map shows only the first five characters. Reproducing that truncation keeps
     * the displayed value within the {@code maxlength="5"} / {@code @Size(max = 5)} contract of
     * {@link com.aws.carddemo.dto.screen.COACTUPForm#getAcszipc()} (finding P5-02), so an unchanged ZIP
     * round-trips to confirmation/commit rather than tripping bean validation. Because
     * {@link #fixedWindow(String, int)} left-truncates, applying this again to an already five-character
     * value is a no-op (idempotent).</p>
     *
     * @param zipX10 the stored ZIP ({@code CUST-ADDR-ZIP PIC X(10)}); may be {@code null}
     * @return the first {@link #ZIP_EDIT_WIDTH} characters, exactly as the legacy {@code X(10) -> X(5)}
     *         {@code MOVE} would display (never {@code null})
     */
    private static String oldZipForDisplay(String zipX10) {
        return fixedWindow(zipX10, ZIP_EDIT_WIDTH);
    }



    // =====================================================================
    // Phase 5 - Read chain (9000/9200/9300/9400/9500) and dual-entity
    //           transactional write (9600/9700).
    // =====================================================================

    /**
     * {@code 9000-READ-ACCT} - reads the account and its owning customer, then
     * snapshots the fetched values as the optimistic-lock baseline.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 9000-READ-ACCT}
     * (COBOL lines 3608-3648). Clears the old-details baseline, turns the info
     * message off, then chains {@code 9200-GETCARDXREF-BYACCT} (resolve the
     * customer via the {@code CXACAIX} account-path alternate index) ->
     * {@code 9300-GETACCTDATA-BYACCT} -> {@code 9400-GETCUSTDATA-BYCUST} ->
     * {@code 9500-STORE-FETCHED-DATA}.</p>
     *
     * <p>Only the first guard ({@code IF FLG-ACCTFILTER-NOT-OK GO TO EXIT} after
     * the cross-reference read) is active. The two subsequent guards test
     * {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} / {@code DID-NOT-FIND-CUST-IN-CUSTDAT},
     * whose {@code SET}s are commented out in the source, so those guards can
     * never fire. This control flow is reproduced faithfully (AAP parity decision
     * (c)): the chain continues even when the account or customer read reports
     * not-found, and {@code 9500}/{@link #buildSnapshot} null-guard the missing
     * entities so no {@code NullPointerException} occurs. When a read fails,
     * {@code INPUT-ERROR} is already set and the caller re-displays the error, so
     * the partial baseline is never consumed.</p>
     *
     * @param st the per-request processing state
     */
    private void readAcct(ProcessingState st) {
        // INITIALIZE ACUP-OLD-DETAILS + MOVE CC-ACCT-ID TO ACUP-OLD-ACCT-ID.
        AccountSnapshot baseline = new AccountSnapshot();
        Long acctId = this.context.getAcctId();
        if (acctId != null) {
            baseline.setAcctId(zeroPad(acctId, ACCT_ID_WIDTH));
        }
        st.getState().setOldSnapshot(baseline);

        // SET WS-NO-INFO-MESSAGE TO TRUE.
        st.setInfoMessage(null);

        // PERFORM 9200-GETCARDXREF-BYACCT.
        getCardXrefByAcct(st);
        // IF FLG-ACCTFILTER-NOT-OK GO TO 9000-READ-ACCT-EXIT (active guard).
        if (st.getAccountFilter() == FilterFlag.NOT_OK) {
            return;
        }

        // PERFORM 9300-GETACCTDATA-BYACCT.
        getAcctDataByAcct(st);
        // IF DID-NOT-FIND-ACCT-IN-ACCTDAT GO TO EXIT - the SET is commented out in
        // the source, so this guard never fires; flow continues intentionally.

        // PERFORM 9400-GETCUSTDATA-BYCUST.
        getCustDataByCust(st);
        // IF DID-NOT-FIND-CUST-IN-CUSTDAT GO TO EXIT - likewise a dead guard.

        // PERFORM 9500-STORE-FETCHED-DATA.
        storeFetchedData(st);
    }

    /**
     * {@code 9200-GETCARDXREF-BYACCT} - resolves the customer and card for an
     * account through the {@code CXACAIX} account-path alternate index.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 9200-GETCARDXREF-BYACCT}
     * (COBOL lines 3650-3697). The CICS {@code READ} on the alternate index is
     * migrated to {@link CardXrefRepository#findByXrefAcctId(Long)} (AAP alt-index
     * derived query, &sect;0.6.2). A found record maps to {@code DFHRESP(NORMAL)} and
     * stores the customer id and card number into the session context
     * ({@code CDEMO-CUST-ID}, {@code CDEMO-CARD-NUM}). The alternate index may
     * return several cards for one account; the first is used, and because every
     * cross-reference row for an account carries the same customer id, the
     * resolved customer is deterministic. An empty result maps to
     * {@code DFHRESP(NOTFND)}: set {@code INPUT-ERROR} and
     * {@code FLG-ACCTFILTER-NOT-OK}, and emit the guarded not-found banner.</p>
     *
     * @param st the per-request processing state
     */
    private void getCardXrefByAcct(ProcessingState st) {
        Long acctId = this.context.getAcctId();
        List<CardXref> xrefs = this.cardXrefRepository.findByXrefAcctId(acctId);
        if (!xrefs.isEmpty()) {
            // WHEN DFHRESP(NORMAL)
            CardXref xref = xrefs.get(0);
            this.context.setCustId(xref.getXrefCustId());   // MOVE XREF-CUST-ID TO CDEMO-CUST-ID
            this.context.setCardNum(xref.getXrefCardNum());  // MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM
            st.setXrefNotFound(false);
        } else {
            // WHEN DFHRESP(NOTFND)
            st.setXrefNotFound(true);
            st.setAccountFilter(FilterFlag.NOT_OK);          // SET FLG-ACCTFILTER-NOT-OK
            st.getEdit().fail(xrefNotFoundMessage(acctId));  // SET INPUT-ERROR + guarded WS-RETURN-MSG
        }
    }

    /**
     * {@code 9300-GETACCTDATA-BYACCT} - reads the account master record by id.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 9300-GETACCTDATA-BYACCT}
     * (COBOL lines 3701-3749). The CICS {@code READ} is migrated to
     * {@link AccountRepository#findById(Object)}. Found maps to
     * {@code DFHRESP(NORMAL)} -> {@code FOUND-ACCT-IN-MASTER} and stores the
     * fetched entity. Empty maps to {@code DFHRESP(NOTFND)}: set
     * {@code INPUT-ERROR} and {@code FLG-ACCTFILTER-NOT-OK} and emit the guarded
     * not-found banner. The commented-out {@code SET DID-NOT-FIND-ACCT-IN-ACCTDAT}
     * is preserved as dead code, so {@link #readAcct} does not short-circuit here
     * (AAP parity decision (c)); the fetched account simply stays unset.</p>
     *
     * @param st the per-request processing state
     */
    private void getAcctDataByAcct(ProcessingState st) {
        Long acctId = this.context.getAcctId();
        Optional<Account> account = this.accountRepository.findById(acctId);
        if (account.isPresent()) {
            // WHEN DFHRESP(NORMAL)
            st.setFetchedAccount(account.get());
            st.setFoundAcctInMaster(true);                  // SET FOUND-ACCT-IN-MASTER
        } else {
            // WHEN DFHRESP(NOTFND) - SET DID-NOT-FIND-ACCT-IN-ACCTDAT is commented out.
            st.setAccountFilter(FilterFlag.NOT_OK);          // SET FLG-ACCTFILTER-NOT-OK
            st.getEdit().fail(acctNotFoundMessage(acctId));  // SET INPUT-ERROR + guarded WS-RETURN-MSG
        }
    }

    /**
     * {@code 9400-GETCUSTDATA-BYCUST} - reads the customer master record by id.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 9400-GETCUSTDATA-BYCUST}
     * (COBOL lines 3752-3799). The customer id was resolved by
     * {@code 9200-GETCARDXREF-BYACCT} and lives in the session context
     * ({@code CDEMO-CUST-ID} -> {@code WS-CARD-RID-CUST-ID}). The CICS {@code READ}
     * is migrated to {@link CustomerRepository#findById(Object)}. Found maps to
     * {@code DFHRESP(NORMAL)} -> {@code FOUND-CUST-IN-MASTER}. Empty maps to
     * {@code DFHRESP(NOTFND)}: set {@code INPUT-ERROR} and
     * {@code FLG-CUSTFILTER-NOT-OK} and emit the guarded not-found banner; the
     * commented-out {@code SET DID-NOT-FIND-CUST-IN-CUSTDAT} is preserved as dead
     * code (AAP parity decision (c)).</p>
     *
     * @param st the per-request processing state
     */
    private void getCustDataByCust(ProcessingState st) {
        Long custId = this.context.getCustId();
        Optional<Customer> customer = (custId == null)
                ? Optional.empty()
                : this.customerRepository.findById(custId);
        if (customer.isPresent()) {
            // WHEN DFHRESP(NORMAL)
            st.setFetchedCustomer(customer.get());
            st.setFoundCustInMaster(true);                  // SET FOUND-CUST-IN-MASTER
        } else {
            // WHEN DFHRESP(NOTFND) - SET DID-NOT-FIND-CUST-IN-CUSTDAT is commented out.
            st.setCustomerFilter(FilterFlag.NOT_OK);         // SET FLG-CUSTFILTER-NOT-OK
            st.getEdit().fail(custNotFoundMessage(custId));  // SET INPUT-ERROR + guarded WS-RETURN-MSG
        }
    }

    /**
     * {@code 9500-STORE-FETCHED-DATA} - copies the fetched account and customer
     * into the session context and builds the {@code ACUP-OLD-DETAILS} baseline
     * used by {@code 1205-COMPARE-OLD-NEW} and {@code 9700-CHECK-CHANGE-IN-REC}.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 9500-STORE-FETCHED-DATA}
     * (COBOL lines 3801-3886). First the navigation context is populated
     * ({@code CDEMO-ACCT-ID}, {@code CDEMO-CUST-ID}, the customer names,
     * {@code CDEMO-ACCT-STATUS}, {@code CDEMO-CARD-NUM}); then
     * {@code ACUP-OLD-DETAILS} is rebuilt via {@link #buildSnapshot}. Both entities
     * are null-guarded so the commented-out not-found guards in
     * {@code 9000-READ-ACCT} cannot cause a {@code NullPointerException} (AAP
     * parity decision (c)).</p>
     *
     * @param st the per-request processing state
     */
    private void storeFetchedData(ProcessingState st) {
        Account acct = st.getFetchedAccount();
        Customer cust = st.getFetchedCustomer();

        // Store Context in Commarea (CDEMO-* navigation fields).
        if (acct != null) {
            this.context.setAcctId(acct.getAcctId());            // CDEMO-ACCT-ID
            this.context.setAcctStatus(acct.getActiveStatus());  // CDEMO-ACCT-STATUS
        }
        if (cust != null) {
            this.context.setCustId(cust.getCustId());            // CDEMO-CUST-ID
            this.context.setCustFirstName(cust.getFirstName());  // CDEMO-CUST-FNAME
            this.context.setCustMiddleName(cust.getMiddleName()); // CDEMO-CUST-MNAME
            this.context.setCustLastName(cust.getLastName());    // CDEMO-CUST-LNAME
        }

        // INITIALIZE ACUP-OLD-DETAILS + MOVE all fetched fields -> baseline snapshot.
        st.getState().setOldSnapshot(buildSnapshot(acct, cust));
    }

    /**
     * Builds an {@link AccountSnapshot} from a fetched account and customer,
     * reproducing the {@code MOVE}s of {@code 9500-STORE-FETCHED-DATA}. Reused by
     * {@code 9700-CHECK-CHANGE-IN-REC} to image the freshly re-read (for-update)
     * records for the optimistic-lock comparison.
     *
     * <p>Monetary amounts are carried as {@link BigDecimal} (the COBOL zoned
     * {@code -N} redefine); dates become {@code CCYYMMDD} eight-character strings
     * (the COBOL part-by-part {@code MOVE} of the {@code YYYY-MM-DD} record,
     * dropping the separators); the numeric SSN, customer id and FICO score are
     * zero-padded to their fixed COBOL widths; every other field is copied as-is.
     * Both entities are null-guarded.</p>
     *
     * @param acct the fetched account (may be {@code null})
     * @param cust the fetched customer (may be {@code null})
     * @return the populated baseline snapshot
     */
    private static AccountSnapshot buildSnapshot(Account acct, Customer cust) {
        AccountSnapshot snap = new AccountSnapshot();
        if (acct != null) {
            snap.setAcctId(zeroPad(acct.getAcctId(), ACCT_ID_WIDTH));
            snap.setActiveStatus(acct.getActiveStatus());
            snap.setCurrBal(acct.getCurrBal());
            snap.setCreditLimit(acct.getCreditLimit());
            snap.setCashCreditLimit(acct.getCashCreditLimit());
            snap.setCurrCycCredit(acct.getCurrCycCredit());
            snap.setCurrCycDebit(acct.getCurrCycDebit());
            snap.setOpenDate(formatCompactDate(acct.getOpenDate()));
            snap.setExpirationDate(formatCompactDate(acct.getExpiraionDate()));
            snap.setReissueDate(formatCompactDate(acct.getReissueDate()));
            snap.setGroupId(acct.getGroupId());
        }
        if (cust != null) {
            snap.setCustId(zeroPad(cust.getCustId(), CUST_ID_WIDTH));
            snap.setSsn(zeroPad(cust.getSsn(), SSN_WIDTH));
            snap.setDateOfBirth(formatCompactDate(cust.getDateOfBirth()));
            snap.setFicoScore(zeroPad(cust.getFicoCreditScore(), FICO_WIDTH));
            snap.setFirstName(cust.getFirstName());
            snap.setMiddleName(cust.getMiddleName());
            snap.setLastName(cust.getLastName());
            snap.setAddrLine1(cust.getAddrLine1());
            snap.setAddrLine2(cust.getAddrLine2());
            snap.setAddrLine3(cust.getAddrLine3());
            snap.setAddrStateCd(cust.getAddrStateCd());
            snap.setAddrCountryCd(cust.getAddrCountryCd());
            snap.setAddrZip(cust.getAddrZip());
            snap.setPhoneNum1(cust.getPhoneNum1());
            snap.setPhoneNum2(cust.getPhoneNum2());
            snap.setGovtIssuedId(cust.getGovtIssuedId());
            snap.setEftAccountId(cust.getEftAccountId());
            snap.setPriHolderInd(cust.getPriCardHolderInd());
        }
        return snap;
    }

    /**
     * Builds the {@code 9200} cross-reference not-found banner (COBOL lines
     * 3665-3684): the literal text with the eleven-digit account key and the CICS
     * {@code NOTFND} response code. The CICS {@code RESP}/{@code RESP2} diagnostics
     * are surfaced as their {@code DFHRESP} constant values, the only meaningful
     * mapping under the Java runtime (there is no CICS control block).
     *
     * @param acctId the account id being read
     * @return the not-found banner text
     */
    private static String xrefNotFoundMessage(Long acctId) {
        return "Account:" + zeroPad(acctId, ACCT_ID_WIDTH)
                + " not found in Cross ref file.  Resp:" + CICS_RESP_NOTFND
                + " Reas:" + CICS_REAS_ZERO;
    }

    /**
     * Builds the {@code 9300} account-master not-found banner (COBOL lines
     * 3715-3733).
     *
     * @param acctId the account id being read
     * @return the not-found banner text
     */
    private static String acctNotFoundMessage(Long acctId) {
        return "Account:" + zeroPad(acctId, ACCT_ID_WIDTH)
                + " not found in Acct Master file.Resp:" + CICS_RESP_NOTFND
                + " Reas:" + CICS_REAS_ZERO;
    }

    /**
     * Builds the {@code 9400} customer-master not-found banner (COBOL lines
     * 3770-3785).
     *
     * @param custId the customer id being read (may be {@code null})
     * @return the not-found banner text
     */
    private static String custNotFoundMessage(Long custId) {
        return "CustId:" + zeroPad(custId, CUST_ID_WIDTH)
                + " not found in customer master.Resp: " + CICS_RESP_NOTFND
                + " REAS:" + CICS_REAS_ZERO;
    }

    /**
     * Formats a {@link LocalDate} as the eight-character {@code CCYYMMDD} baseline
     * image used throughout the snapshot comparisons; a {@code null} date maps to
     * {@code null} (imaged to spaces during comparison).
     *
     * @param date the date to format (may be {@code null})
     * @return the {@code yyyyMMdd} string, or {@code null}
     */
    private static String formatCompactDate(LocalDate date) {
        return (date == null) ? null : date.format(CCYYMMDD);
    }

    /**
     * Zero-pads a numeric key/value to its fixed COBOL display width, mirroring a
     * COBOL {@code MOVE} of a {@code PIC 9(n)} field to a {@code PIC X(n)} field; a
     * {@code null} value maps to {@code null}.
     *
     * @param value the numeric value (may be {@code null})
     * @param width the fixed display width
     * @return the zero-padded string, or {@code null}
     */
    private static String zeroPad(Long value, int width) {
        return (value == null) ? null : String.format("%0" + width + "d", value);
    }

    /**
     * Zero-pads an {@link Integer} value to its fixed COBOL display width; a
     * {@code null} value maps to {@code null}.
     *
     * @param value the numeric value (may be {@code null})
     * @param width the fixed display width
     * @return the zero-padded string, or {@code null}
     */
    private static String zeroPad(Integer value, int width) {
        return (value == null) ? null : String.format("%0" + width + "d", value);
    }


    /**
     * {@code 9600-WRITE-PROCESSING} - the read-lock / change-check / dual rewrite
     * unit-of-work that commits the edited account and customer together.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 9600-WRITE-PROCESSING}
     * (COBOL lines 3888-4107). The CICS {@code READ ... UPDATE} locks map to the
     * pessimistic-write-lock finders
     * {@link AccountRepository#findByIdForUpdate(Long)} /
     * {@link CustomerRepository#findByIdForUpdate(Long)} (PostgreSQL {@code SELECT ...
     * FOR UPDATE}): a present record is the locked baseline, an empty result maps to
     * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} / {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}.
     * The account is locked first and the customer second &mdash; the exact COBOL
     * lock order &mdash; and both locks are held until this unit-of-work commits, so
     * the change-check and the dual rewrite are one atomic critical section with no
     * lost-update window (review finding #14, CWE-362). {@code 9700-CHECK-CHANGE-IN-REC} then
     * compares the freshly re-read records against the fetch-time baseline; a
     * concurrent change aborts the save for re-display. Otherwise the edited values
     * are applied and both records are rewritten via
     * {@link org.springframework.data.jpa.repository.JpaRepository#saveAndFlush}, a
     * failed account rewrite maps to {@code LOCKED-BUT-UPDATE-FAILED} and a failed
     * customer rewrite maps to {@code LOCKED-BUT-UPDATE-FAILED} with a
     * {@code SYNCPOINT ROLLBACK} equivalent (both rewrites roll back together).</p>
     *
     * <p>Transaction boundary: this method is {@code private} and is invoked by the
     * decide-action state machine within the same bean, so it participates in the
     * transaction established on the proxied public entry point (annotated
     * {@link Transactional}); a Spring transaction cannot be started by a private
     * self-invoked method. The two {@code saveAndFlush} calls therefore share one
     * atomic unit-of-work, and {@link #markRollbackOnly()} marks that transaction
     * for rollback, reproducing the CICS {@code SYNCPOINT ROLLBACK}.</p>
     *
     * @param st the per-request processing state
     */
    private void writeProcessing(ProcessingState st) {
        EditContext edit = st.getEdit();

        // Read the account file for update (lock). MOVE CC-ACCT-ID TO WS-CARD-RID-ACCT-ID.
        // EXEC CICS READ FILE(ACCTDAT) UPDATE -> pessimistic write lock (SELECT ... FOR UPDATE),
        // acquired FIRST (the COBOL 9600 lock order: account, then customer), held until this
        // @Transactional unit-of-work commits so the change-check and REWRITE are atomic (#14).
        Long acctId = this.context.getAcctId();
        Optional<Account> acctOpt =
                (acctId == null) ? Optional.empty() : this.accountRepository.findByIdForUpdate(acctId);
        if (acctOpt.isEmpty()) {
            // Could we lock the account record ?  -> no.
            edit.markInputError();                       // SET INPUT-ERROR
            if (edit.isReturnMsgOff()) {                 // IF WS-RETURN-MSG-OFF
                st.setWriteResult(WriteResult.COULD_NOT_LOCK_ACCT);
                edit.forceMessage(MSG_COULD_NOT_LOCK_ACCT);
            }
            return;                                      // GO TO 9600-WRITE-PROCESSING-EXIT
        }
        Account account = acctOpt.get();

        // Read the customer file for update (lock). MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID.
        // EXEC CICS READ FILE(CUSTDAT) UPDATE -> pessimistic write lock, acquired SECOND (after the
        // account) matching the COBOL 9600 lock order; held until commit (#14).
        Long custId = this.context.getCustId();
        Optional<Customer> custOpt =
                (custId == null) ? Optional.empty() : this.customerRepository.findByIdForUpdate(custId);
        if (custOpt.isEmpty()) {
            // Could we lock the customer record ?  -> no.
            edit.markInputError();                       // SET INPUT-ERROR
            if (edit.isReturnMsgOff()) {                 // IF WS-RETURN-MSG-OFF
                st.setWriteResult(WriteResult.COULD_NOT_LOCK_CUST);
                edit.forceMessage(MSG_COULD_NOT_LOCK_CUST);
            }
            return;                                      // GO TO 9600-WRITE-PROCESSING-EXIT
        }
        Customer customer = custOpt.get();

        // Did someone change the record while we were out ?  PERFORM 9700-CHECK-CHANGE-IN-REC.
        if (checkChangeInRec(st, account, customer)) {
            // SET DATA-WAS-CHANGED-BEFORE-UPDATE (message) - re-display, not an error.
            st.setWriteResult(WriteResult.DATA_CHANGED);
            edit.forceMessage(MSG_DATA_CHANGED);
            return;                                      // GO TO 9600-WRITE-PROCESSING-EXIT
        }

        // Prepare the update: INITIALIZE ACCT/CUST-UPDATE-RECORD + MOVE the ACUP-NEW fields.
        NewInput ni = st.getNewInput();
        applyAccountUpdate(account, ni);
        applyCustomerUpdate(customer, ni);

        // Update account (EXEC CICS REWRITE FILE(ACCTDAT)).
        try {
            this.accountRepository.saveAndFlush(account);
        } catch (DataAccessException ex) {
            // Did account update succeed ?  -> no.
            st.setWriteResult(WriteResult.LOCKED_BUT_UPDATE_FAILED);
            edit.forceMessage(MSG_UPDATE_FAILED);
            markRollbackOnly();
            return;                                      // GO TO 9600-WRITE-PROCESSING-EXIT
        }

        // Update customer (EXEC CICS REWRITE FILE(CUSTDAT)).
        try {
            this.customerRepository.saveAndFlush(customer);
        } catch (DataAccessException ex) {
            // Did customer update succeed ?  -> no -> SYNCPOINT ROLLBACK (both roll back).
            st.setWriteResult(WriteResult.LOCKED_BUT_UPDATE_FAILED);
            edit.forceMessage(MSG_UPDATE_FAILED);
            markRollbackOnly();
            return;                                      // GO TO 9600-WRITE-PROCESSING-EXIT
        }

        // Both rewrites succeeded.
        st.setWriteResult(WriteResult.SUCCESS);
    }

    /**
     * Applies the edited account fields to the locked account entity, reproducing
     * the {@code ACCT-UPDATE-RECORD} preparation of {@code 9600-WRITE-PROCESSING}
     * (COBOL lines 3956-4008). The account id is the primary key of the entity that
     * was just fetched by id, so it is not re-assigned. Monetary fields are the
     * scaled {@link BigDecimal} zoned redefines; dates are rebuilt from the edited
     * {@code CCYYMMDD} parts.
     *
     * <p>The COBOL {@code MOVE ACCT-REISSUE-DATE TO ACCT-UPDATE-REISSUE-DATE}
     * immediately preceding the reissue {@code STRING} is a dead move (the
     * {@code STRING} overwrites it); the net effect - the reissue date takes the
     * edited parts - is reproduced, and the dead move is intentionally not carried
     * over.</p>
     *
     * @param account the locked account entity to mutate
     * @param ni      the normalized, edited input values
     */
    private void applyAccountUpdate(Account account, NewInput ni) {
        account.setActiveStatus(ni.getActiveStatus());          // ACCT-UPDATE-ACTIVE-STATUS
        account.setCurrBal(ni.getCurrBal());                    // ACCT-UPDATE-CURR-BAL
        account.setCreditLimit(ni.getCreditLimit());            // ACCT-UPDATE-CREDIT-LIMIT
        account.setCashCreditLimit(ni.getCashCreditLimit());    // ACCT-UPDATE-CASH-CREDIT-LIMIT
        account.setCurrCycCredit(ni.getCurrCycCredit());        // ACCT-UPDATE-CURR-CYC-CREDIT
        account.setCurrCycDebit(ni.getCurrCycDebit());          // ACCT-UPDATE-CURR-CYC-DEBIT
        account.setOpenDate(parseCompactDate(ni.composeOpenDate()));       // ACCT-UPDATE-OPEN-DATE
        account.setExpiraionDate(parseCompactDate(ni.composeExpiryDate())); // ACCT-UPDATE-EXPIRAION-DATE
        account.setReissueDate(parseCompactDate(ni.composeReissueDate()));  // ACCT-UPDATE-REISSUE-DATE
        account.setGroupId(ni.getGroupId());                    // ACCT-UPDATE-GROUP-ID
    }

    /**
     * Applies the edited customer fields to the locked customer entity, reproducing
     * the {@code CUST-UPDATE-RECORD} preparation of {@code 9600-WRITE-PROCESSING}
     * (COBOL lines 4010-4073). The customer id is the primary key of the entity that
     * was just fetched by id, so it is not re-assigned. Phone numbers are rebuilt
     * into the {@code (999)999-9999} image; the SSN and FICO score are parsed back to
     * their numeric column types; the date of birth is rebuilt from the edited
     * {@code CCYYMMDD} parts.
     *
     * @param customer the locked customer entity to mutate
     * @param ni       the normalized, edited input values
     */
    private void applyCustomerUpdate(Customer customer, NewInput ni) {
        customer.setFirstName(ni.getFirstName());               // CUST-UPDATE-FIRST-NAME
        customer.setMiddleName(ni.getMiddleName());             // CUST-UPDATE-MIDDLE-NAME
        customer.setLastName(ni.getLastName());                 // CUST-UPDATE-LAST-NAME
        customer.setAddrLine1(ni.getAddrLine1());               // CUST-UPDATE-ADDR-LINE-1
        customer.setAddrLine2(ni.getAddrLine2());               // CUST-UPDATE-ADDR-LINE-2
        customer.setAddrLine3(ni.getAddrLine3());               // CUST-UPDATE-ADDR-LINE-3
        customer.setAddrStateCd(ni.getStateCd());               // CUST-UPDATE-ADDR-STATE-CD
        customer.setAddrCountryCd(ni.getCountryCd());           // CUST-UPDATE-ADDR-COUNTRY-CD
        customer.setAddrZip(ni.getZip());                       // CUST-UPDATE-ADDR-ZIP
        customer.setPhoneNum1(ni.composePhone1());              // CUST-UPDATE-PHONE-NUM-1 (STRING rebuild)
        customer.setPhoneNum2(ni.composePhone2());              // CUST-UPDATE-PHONE-NUM-2 (STRING rebuild)
        customer.setSsn(parseLongOrNull(ni.composeSsn()));      // CUST-UPDATE-SSN
        customer.setGovtIssuedId(ni.getGovtId());               // CUST-UPDATE-GOVT-ISSUED-ID
        customer.setDateOfBirth(parseCompactDate(ni.composeDob())); // CUST-UPDATE-DOB-YYYY-MM-DD
        customer.setEftAccountId(ni.getEft());                  // CUST-UPDATE-EFT-ACCOUNT-ID
        customer.setPriCardHolderInd(ni.getPriHolder());        // CUST-UPDATE-PRI-CARD-IND
        customer.setFicoCreditScore(parseIntOrNull(ni.getFico())); // CUST-UPDATE-FICO-CREDIT-SCORE
    }

    /**
     * {@code 9700-CHECK-CHANGE-IN-REC} - optimistic-lock check comparing the freshly
     * re-read (for-update) account and customer against the fetch-time baseline.
     *
     * <p>Origin: legacy/cbl/COACTUPC.cbl paragraph {@code 9700-CHECK-CHANGE-IN-REC}
     * (COBOL lines 4109-4196). The comparison ruleset is deliberately different from
     * {@code 1205-COMPARE-OLD-NEW}: the account block compares the active status,
     * the five monetary amounts, and the three dates for exact equality and compares
     * the group id {@code LOWER-CASE}; the customer block compares the names,
     * address lines, state, country and government id {@code UPPER-CASE} and compares
     * the zip, both phone numbers, the SSN, the date of birth, the EFT id, the
     * primary-holder indicator and the FICO score raw. Neither block compares the
     * account or customer id (the keys). Both sides are DB-derived, so a
     * {@code null} field images to spaces (text) or zero (money) symmetrically.</p>
     *
     * <p>When any field differs, the fetch-time baseline is refreshed to the current
     * database state and the fetched entities are updated so the caller re-displays
     * the current values with a matching baseline (per the file specification), and
     * {@code true} is returned to signal {@code DATA-WAS-CHANGED-BEFORE-UPDATE}. This
     * is a re-display, not a hard error.</p>
     *
     * @param st        the per-request processing state (carries the baseline)
     * @param fresh     the freshly re-read account
     * @param freshCust the freshly re-read customer
     * @return {@code true} when a concurrent change was detected
     */
    private boolean checkChangeInRec(ProcessingState st, Account fresh, Customer freshCust) {
        AccountSnapshot old = st.getState().getOldSnapshot();
        if (old == null) {
            old = new AccountSnapshot();
        }
        AccountSnapshot cur = buildSnapshot(fresh, freshCust);

        // Account Master data - exact, except group id compared LOWER-CASE.
        boolean accountSame =
                snapRawEq(cur.getActiveStatus(), old.getActiveStatus(), STATUS_WIDTH)
                && snapMoneyEq(cur.getCurrBal(), old.getCurrBal())
                && snapMoneyEq(cur.getCreditLimit(), old.getCreditLimit())
                && snapMoneyEq(cur.getCashCreditLimit(), old.getCashCreditLimit())
                && snapMoneyEq(cur.getCurrCycCredit(), old.getCurrCycCredit())
                && snapMoneyEq(cur.getCurrCycDebit(), old.getCurrCycDebit())
                && snapRawEq(cur.getOpenDate(), old.getOpenDate(), DATE_WIDTH)
                && snapRawEq(cur.getExpirationDate(), old.getExpirationDate(), DATE_WIDTH)
                && snapRawEq(cur.getReissueDate(), old.getReissueDate(), DATE_WIDTH)
                && snapLowerEq(cur.getGroupId(), old.getGroupId(), GROUP_WIDTH);
        if (!accountSame) {
            refreshBaseline(st, fresh, freshCust, cur);
            return true;
        }

        // Customer data - names/address/state/country/govt id UPPER-CASE; rest raw.
        boolean customerSame =
                snapUpperEq(cur.getFirstName(), old.getFirstName(), NAME_WIDTH)
                && snapUpperEq(cur.getMiddleName(), old.getMiddleName(), NAME_WIDTH)
                && snapUpperEq(cur.getLastName(), old.getLastName(), NAME_WIDTH)
                && snapUpperEq(cur.getAddrLine1(), old.getAddrLine1(), ADDRESS_WIDTH)
                && snapUpperEq(cur.getAddrLine2(), old.getAddrLine2(), ADDRESS_WIDTH)
                && snapUpperEq(cur.getAddrLine3(), old.getAddrLine3(), ADDRESS_WIDTH)
                && snapUpperEq(cur.getAddrStateCd(), old.getAddrStateCd(), STATE_WIDTH)
                && snapUpperEq(cur.getAddrCountryCd(), old.getAddrCountryCd(), COUNTRY_WIDTH)
                && snapRawEq(cur.getAddrZip(), old.getAddrZip(), ADDR_ZIP_WIDTH)
                && snapRawEq(cur.getPhoneNum1(), old.getPhoneNum1(), PHONE_WIDTH)
                && snapRawEq(cur.getPhoneNum2(), old.getPhoneNum2(), PHONE_WIDTH)
                && snapRawEq(cur.getSsn(), old.getSsn(), SSN_WIDTH)
                && snapUpperEq(cur.getGovtIssuedId(), old.getGovtIssuedId(), GOVT_ID_WIDTH)
                && snapRawEq(cur.getDateOfBirth(), old.getDateOfBirth(), DATE_WIDTH)
                && snapRawEq(cur.getEftAccountId(), old.getEftAccountId(), EFT_WIDTH)
                && snapRawEq(cur.getPriHolderInd(), old.getPriHolderInd(), PRI_HOLDER_WIDTH)
                && snapRawEq(cur.getFicoScore(), old.getFicoScore(), FICO_WIDTH);
        if (!customerSame) {
            refreshBaseline(st, fresh, freshCust, cur);
            return true;
        }

        return false;
    }

    /**
     * Refreshes the fetch-time baseline to the current database state after a
     * concurrent change is detected in {@code 9700-CHECK-CHANGE-IN-REC}, so the
     * re-displayed screen shows the current values with a matching optimistic-lock
     * baseline.
     *
     * @param st        the per-request processing state
     * @param fresh     the freshly re-read account
     * @param freshCust the freshly re-read customer
     * @param cur       the snapshot built from the fresh records
     */
    private void refreshBaseline(ProcessingState st, Account fresh, Customer freshCust,
            AccountSnapshot cur) {
        st.getState().setOldSnapshot(cur);
        st.setFetchedAccount(fresh);
        st.setFetchedCustomer(freshCust);
    }

    /**
     * Marks the current transaction for rollback, reproducing the CICS
     * {@code SYNCPOINT ROLLBACK} of the failed dual rewrite. When there is no active
     * transaction (for example, an isolated unit test that invokes the write without
     * the Spring proxy), the rollback is a no-op.
     */
    private void markRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ex) {
            // No active transaction in this context; rollback is a no-op.
        }
    }

    /**
     * Raw fixed-width equality of two baseline (DB-derived) values, imaging both to
     * {@code width} characters (a {@code null} value images to spaces). Used by the
     * {@code 9700} account/customer raw comparisons.
     *
     * @param a     one baseline value (may be {@code null})
     * @param b     the other baseline value (may be {@code null})
     * @param width the fixed COBOL field width
     * @return {@code true} when the fixed-width images are byte-equal
     */
    private static boolean snapRawEq(String a, String b, int width) {
        return fixedWindow(a, width).equals(fixedWindow(b, width));
    }

    /**
     * Upper-cased fixed-width equality of two baseline values
     * ({@code FUNCTION UPPER-CASE(a) = FUNCTION UPPER-CASE(b)}), used by the
     * {@code 9700} customer name/address/state/country/government-id comparisons.
     *
     * @param a     one baseline value (may be {@code null})
     * @param b     the other baseline value (may be {@code null})
     * @param width the fixed COBOL field width
     * @return {@code true} when the upper-cased images are equal
     */
    private static boolean snapUpperEq(String a, String b, int width) {
        return fixedWindow(a, width).toUpperCase(Locale.ROOT)
                .equals(fixedWindow(b, width).toUpperCase(Locale.ROOT));
    }

    /**
     * Lower-cased fixed-width equality of two baseline values
     * ({@code FUNCTION LOWER-CASE(a) = FUNCTION LOWER-CASE(b)}), used by the
     * {@code 9700} account group-id comparison.
     *
     * @param a     one baseline value (may be {@code null})
     * @param b     the other baseline value (may be {@code null})
     * @param width the fixed COBOL field width
     * @return {@code true} when the lower-cased images are equal
     */
    private static boolean snapLowerEq(String a, String b, int width) {
        return fixedWindow(a, width).toLowerCase(Locale.ROOT)
                .equals(fixedWindow(b, width).toLowerCase(Locale.ROOT));
    }

    /**
     * Numeric equality of two baseline monetary amounts, scaled with
     * {@link CobolDecimal#money(BigDecimal)}; a {@code null} amount is treated as
     * zero. Used by the {@code 9700} account money comparisons.
     *
     * @param a one baseline amount (may be {@code null})
     * @param b the other baseline amount (may be {@code null})
     * @return {@code true} when both amounts are numerically equal at scale 2
     */
    private static boolean snapMoneyEq(BigDecimal a, BigDecimal b) {
        BigDecimal x = (a == null) ? BigDecimal.ZERO : a;
        BigDecimal y = (b == null) ? BigDecimal.ZERO : b;
        return CobolDecimal.money(x).compareTo(CobolDecimal.money(y)) == 0;
    }

    /**
     * Parses a compact {@code CCYYMMDD} string into a {@link LocalDate} for the
     * dual-rewrite write-back; a blank or unparseable value maps to {@code null}. By
     * the time the write executes, the dates have already passed the map edits, so
     * the value parses; the guard is defensive.
     *
     * @param compact the eight-character {@code CCYYMMDD} value (may be {@code null})
     * @return the parsed date, or {@code null}
     */
    private static LocalDate parseCompactDate(String compact) {
        if (compact == null) {
            return null;
        }
        String trimmed = compact.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed, CCYYMMDD);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /**
     * Parses a numeric string to {@link Long} for a numeric column write-back
     * ({@code CUST-UPDATE-SSN}); a blank or unparseable value maps to {@code null}.
     * Leading zeros are preserved by the numeric column semantics.
     *
     * @param value the numeric string (may be {@code null})
     * @return the parsed value, or {@code null}
     */
    private static Long parseLongOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Parses a numeric string to {@link Integer} for a numeric column write-back
     * ({@code CUST-UPDATE-FICO-CREDIT-SCORE}); a blank or unparseable value maps to
     * {@code null}.
     *
     * @param value the numeric string (may be {@code null})
     * @return the parsed value, or {@code null}
     */
    private static Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ==================================================================
    // Pseudo-conversational driver, action state machine, and abend
    // (0000-MAIN, 2000-DECIDE-ACTION, ABEND-ROUTINE)
    // ==================================================================

    /**
     * Pseudo-conversational entry point for CICS transaction {@code CAUP} - the
     * public driver reproducing paragraph {@code 0000-MAIN}
     * ({@code legacy/cbl/COACTUPC.cbl} lines 859-1024) together with its
     * {@code COMMON-RETURN} tail (lines 1005-1017).
     *
     * <p>The controller invokes this method once per screen submission, passing the
     * bound {@link COACTUPForm} ({@code CACTUPAI}), the pressed {@link PfKey} (the
     * Java view of {@code EIBAID} as mapped by {@code YYYY-STORE-PFKEY}), and the
     * session-held {@link AccountUpdateState} (the Java view of
     * {@code WS-THIS-PROGCOMMAREA}). All per-request working storage
     * ({@code CC-WORK-AREA}, {@code WS-MISC-STORAGE}, {@code ACUP-NEW-DETAILS}) is
     * rebuilt here as a fresh {@link ProcessingState}, exactly as {@code 0000-MAIN}
     * re-initializes those areas at line 866.</p>
     *
     * <p><b>Transaction boundary.</b> This method is annotated
     * {@link Transactional @Transactional} so it is the effective unit-of-work
     * boundary for the read-update-rewrite performed by the self-invoked
     * {@link #writeProcessing(ProcessingState)} (a private method cannot open its
     * own Spring transaction through the proxy). The dual-entity write of both the
     * {@code ACCTDAT} and {@code CUSTDAT} records therefore commits or rolls back
     * atomically, reproducing the single CICS logical unit of work
     * (AAP &sect;0.3.3, &sect;0.6.5).</p>
     *
     * <p><b>Session lifecycle.</b> Consistent with the sibling online services,
     * this method only <em>reads</em> {@link CardDemoContext#isNew()} (the Java view
     * of {@code EIBCALEN = 0}); the controller is responsible for calling
     * {@link CardDemoContext#markInitialized()} after the response is rendered, so
     * the next turn observes a non-empty COMMAREA - mirroring the
     * {@code EXEC CICS RETURN ... COMMAREA(...)} of {@code COMMON-RETURN}.</p>
     *
     * <p><b>{@code COMMON-RETURN} banner mapping (line 1006).</b> The red error line
     * {@code WS-RETURN-MSG} (moved to {@code CCARD-ERROR-MSG}) is carried on the
     * {@link AccountUpdateResult} with {@link MessageSeverity#ERROR} when non-blank,
     * else {@link MessageSeverity#NONE}. The green informational line
     * ({@code INFOMSGO}, paragraph {@code 3250-SETUP-INFOMSG}) is deliberately not
     * emitted here: it is a pure function of the resulting {@link ChangeAction},
     * which the paired {@code AccountController} renders. A PF3 {@code XCTL} yields
     * {@link AccountUpdateResult#exit(String)} ({@link ScreenOutcome#EXIT_TO_CALLER});
     * every other path yields {@link AccountUpdateResult#show}
     * ({@link ScreenOutcome#SHOW_SCREEN}).</p>
     *
     * @param form  the submitted account-update screen; never {@code null}
     * @param pfKey the pressed program-function key ({@code EIBAID}); a {@code null}
     *              key is treated as {@link PfKey#OTHER} (COBOL {@code WHEN OTHER})
     * @param state the session-scoped program state ({@code WS-THIS-PROGCOMMAREA});
     *              never {@code null}
     * @return the screen outcome carrying the error banner, severity, input-error
     *         flag, resulting change action, and per-field validity flags
     */
    @Transactional
    @Observed(name = "carddemo.account.update", contextualName = "account-update")
    public AccountUpdateResult process(COACTUPForm form, PfKey pfKey, AccountUpdateState state) {
        // 0000-MAIN line 866: INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA.
        // Every per-request work area is rebuilt from the submitted form and the
        // session-held state; nothing survives a turn except WS-THIS-PROGCOMMAREA
        // (the AccountUpdateState) and the shared COMMAREA (the CardDemoContext).
        ProcessingState st = new ProcessingState(form, state);

        // YYYY-STORE-PFKEY (copybook CSSTRPFY): EIBAID -> PfKey. A null key falls to
        // the WHEN OTHER (invalid) branch, which the validity remap coerces to ENTER.
        PfKey aid = (pfKey == null) ? PfKey.OTHER : pfKey;

        // 0000-MAIN body (lines 872-1004): drive the state machine and obtain the
        // screen disposition (SEND-MAP -> SHOW_SCREEN, XCTL -> EXIT_TO_CALLER).
        ScreenOutcome outcome = mainline(aid, st);

        // COMMON-RETURN (lines 1005-1017): MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG,
        // then EXEC CICS RETURN. WS-RETURN-MSG carries only the red error banner.
        String banner = st.getEdit().getReturnMsg();
        if (outcome == ScreenOutcome.EXIT_TO_CALLER) {
            // XCTL to CDEMO-TO-PROGRAM: control leaves COACTUPC; the controller
            // issues the redirect using the target recorded on the context.
            return AccountUpdateResult.exit(banner);
        }
        // 3200-SETUP-SCREEN-VARS (COBOL lines 2698-2727), performed at SEND-MAP
        // time before COMMON-RETURN. The screen disposition is driven purely by
        // ACUP-CHANGE-ACTION:
        //   * WHEN ACUP-SHOW-DETAILS -> 3202-SHOW-ORIGINAL-VALUES: re-present the
        //     fetched ACUP-OLD-DETAILS baseline. This is the only state that needs
        //     a server-side field move, because the map fields are otherwise empty
        //     on the fetch turn (review finding #2: none of the 27 detail fields
        //     were being populated).
        //   * WHEN ACUP-CHANGES-MADE (E/N/C/L/F) -> 3203-SHOW-UPDATED-VALUES:
        //     re-present ACUP-NEW-DETAILS, which the bound COACTUPForm already
        //     carries across the turn, so no move is required here.
        //   * WHEN ACUP-DETAILS-NOT-FETCHED / CC-ACCT-ID-N = 0 -> 3201: the empty
        //     search screen; the fresh form already renders blank.
        if (st.getState().getChangeAction() == ChangeAction.SHOW_DETAILS) {
            populateOriginalDisplay(form, st.getState().getOldSnapshot(), st);
        }
        MessageSeverity severity =
                (banner == null || banner.isBlank()) ? MessageSeverity.NONE : MessageSeverity.ERROR;
        return AccountUpdateResult.show(banner, severity, st.isInputError(),
                st.getState().getChangeAction(), st.getFieldFlags());
    }

    /**
     * {@code 3202-SHOW-ORIGINAL-VALUES} ({@code legacy/cbl/COACTUPC.cbl} lines
     * 2789-2863) - copies the fetched {@code ACUP-OLD-DETAILS} baseline onto the
     * {@code COACTUP} screen when the render state is
     * {@link ChangeAction#SHOW_DETAILS}. This is the account-update analogue of
     * {@code COACTVWC}'s {@code 1200-SETUP-SCREEN-VARS} account/customer moves and
     * closes review finding #2 (the fetched detail fields were never populated, so
     * the screen came back blank after a successful search).
     *
     * <p><b>Guard parity.</b> The account block is emitted under
     * {@code IF FOUND-ACCT-IN-MASTER OR FOUND-CUST-IN-MASTER} and the customer
     * block under {@code IF FOUND-CUST-IN-MASTER}, exactly as the COBOL. On the
     * fetch turn both flags are set by {@code 9300-GETACCTDATA-BYACCT} /
     * {@code 9400-GETCUSTDATA-BYCUST}; on a subsequent no-change re-display
     * {@code 1200-EDIT-MAP-INPUTS} re-asserts them.</p>
     *
     * <p><b>Field-shape parity.</b> Monetary fields are rendered with
     * {@link #formatAmount(BigDecimal)} ({@link BigDecimal#toPlainString()}, no
     * floating point) so the value round-trips through the editable
     * {@code NUMVAL-C} inputs; the compact {@code CCYYMMDD} dates and the 9-digit
     * SSN are split back into their year/month/day and 3/2/4 screen components -
     * the exact inverse of {@link NewInput#composeOpenDate()} /
     * {@link NewInput#composeSsn()} used by {@code 1205-COMPARE-OLD-NEW}, so a
     * populate-then-resubmit with no edits compares equal and never reports a
     * spurious change. Phone components reuse {@link #oldPhonePart(String, int)},
     * and - a preserved legacy quirk - customer address line 3 populates the
     * <em>city</em> field ({@code MOVE ACUP-OLD-CUST-ADDR-LINE-3 TO ACSCITYO}).</p>
     *
     * @param form the bound screen form to populate (the same instance the view
     *             renders)
     * @param old  the fetched {@code ACUP-OLD-DETAILS} baseline, or {@code null}
     *             when no successful fetch has occurred (defensive no-op)
     * @param st   the per-request state carrying the {@code FOUND-*} flags
     */
    private void populateOriginalDisplay(COACTUPForm form, AccountSnapshot old, ProcessingState st) {
        if (old == null) {
            return;
        }

        // Echo the validated account id, zero-filled to 11 (the pre-EVALUATE
        // MOVE CC-ACCT-ID TO ACCTSIDO of 3200), matching COACTVWC's acctsid echo.
        if (old.getAcctId() != null) {
            form.setAcctsid(old.getAcctId());
        }

        // IF FOUND-ACCT-IN-MASTER OR FOUND-CUST-IN-MASTER (account block).
        if (st.isFoundAcctInMaster() || st.isFoundCustInMaster()) {
            form.setAcsttus(nz(old.getActiveStatus()));
            form.setAcurbal(formatAmount(old.getCurrBal()));
            form.setAcrdlim(formatAmount(old.getCreditLimit()));
            form.setAcshlim(formatAmount(old.getCashCreditLimit()));
            form.setAcrcycr(formatAmount(old.getCurrCycCredit()));
            form.setAcrcydb(formatAmount(old.getCurrCycDebit()));
            form.setOpnyear(oldDatePart(old.getOpenDate(), 0));
            form.setOpnmon(oldDatePart(old.getOpenDate(), 1));
            form.setOpnday(oldDatePart(old.getOpenDate(), 2));
            form.setExpyear(oldDatePart(old.getExpirationDate(), 0));
            form.setExpmon(oldDatePart(old.getExpirationDate(), 1));
            form.setExpday(oldDatePart(old.getExpirationDate(), 2));
            form.setRisyear(oldDatePart(old.getReissueDate(), 0));
            form.setRismon(oldDatePart(old.getReissueDate(), 1));
            form.setRisday(oldDatePart(old.getReissueDate(), 2));
            form.setAaddgrp(nz(old.getGroupId()));
        }

        // IF FOUND-CUST-IN-MASTER (customer block).
        if (st.isFoundCustInMaster()) {
            form.setAcstnum(nz(old.getCustId()));
            form.setActssn1(oldSsnPart(old.getSsn(), 0));
            form.setActssn2(oldSsnPart(old.getSsn(), 1));
            form.setActssn3(oldSsnPart(old.getSsn(), 2));
            form.setAcstfco(nz(old.getFicoScore()));
            form.setDobyear(oldDatePart(old.getDateOfBirth(), 0));
            form.setDobmon(oldDatePart(old.getDateOfBirth(), 1));
            form.setDobday(oldDatePart(old.getDateOfBirth(), 2));
            form.setAcsfnam(nz(old.getFirstName()));
            form.setAcsmnam(nz(old.getMiddleName()));
            form.setAcslnam(nz(old.getLastName()));
            form.setAcsadl1(nz(old.getAddrLine1()));
            form.setAcsadl2(nz(old.getAddrLine2()));
            // Legacy quirk: customer address line 3 populates the CITY field
            // (MOVE ACUP-OLD-CUST-ADDR-LINE-3 TO ACSCITYO), mirrored in COACTVWC.
            form.setAcscity(nz(old.getAddrLine3()));
            form.setAcsstte(nz(old.getAddrStateCd()));
            // Finding P5-02: the stored ZIP is CUST-ADDR-ZIP PIC X(10) (CHAR(10), so JPA returns ten
            // characters), but the BMS output field ACSZIPCO - like the input ACSZIPCI - is PIC X(5).
            // Legacy COACTUPC.cbl:2843 (MOVE ACUP-OLD-CUST-ADDR-ZIP TO ACSZIPCO) therefore performs an
            // alphanumeric MOVE X(10) -> X(5): left-justified with right truncation to the first five
            // characters. Reproduce that here so the displayed value fits the maxlength-5 / @Size(max=5)
            // form field and an unchanged ZIP round-trips to confirmation/commit (the TRIM-based change
            // detection at legacy 1745-1747, mirrored by upperTrimEq, then sees it unchanged) instead of
            // failing bean validation with a spurious maximum-length error.
            form.setAcszipc(oldZipForDisplay(old.getAddrZip()));
            form.setAcsctry(nz(old.getAddrCountryCd()));
            form.setAcsph1a(oldPhonePart(old.getPhoneNum1(), 0));
            form.setAcsph1b(oldPhonePart(old.getPhoneNum1(), 1));
            form.setAcsph1c(oldPhonePart(old.getPhoneNum1(), 2));
            form.setAcsph2a(oldPhonePart(old.getPhoneNum2(), 0));
            form.setAcsph2b(oldPhonePart(old.getPhoneNum2(), 1));
            form.setAcsph2c(oldPhonePart(old.getPhoneNum2(), 2));
            form.setAcsgovt(nz(old.getGovtIssuedId()));
            form.setAcseftc(nz(old.getEftAccountId()));
            form.setAcspflg(nz(old.getPriHolderInd()));
        }
    }

    /**
     * Pseudo-conversational mainline - migration of the body of paragraph
     * {@code 0000-MAIN} ({@code legacy/cbl/COACTUPC.cbl} lines 872-1004), covering
     * COMMAREA initialization, the {@code YYYY-STORE-PFKEY} validity remap, and the
     * top-level {@code EVALUATE TRUE} that selects the screen disposition.
     *
     * <p><b>COMMAREA initialization (lines 879-893).</b> On a brand-new conversation
     * ({@code EIBCALEN = 0}, {@link CardDemoContext#isNew()}) or the first pass
     * arriving from the main menu
     * ({@code CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER}), the
     * program state is re-initialized ({@link AccountUpdateState#reset()}, the
     * {@code INITIALIZE ... SET ACUP-DETAILS-NOT-FETCHED} of lines 883-886) and the
     * within-program context is flipped to first-entry
     * ({@link CardDemoContext#markEnter()}). Unlike the COBOL {@code INITIALIZE
     * CARDDEMO-COMMAREA}, the shared {@link CardDemoContext} is deliberately not
     * wiped: the Spring-managed session identity (authenticated user, navigation
     * targets) is authoritative, and because this branch only fires when the origin
     * is the menu - where PF3 returns to the menu regardless - the observable return
     * target is identical (documented parity decision, AAP &sect;0.6.8).</p>
     *
     * <p><b>PF-key validity remap (lines 914-919).</b> Only {@code ENTER},
     * {@code PF3}, {@code PF5} while awaiting confirmation
     * ({@link ChangeAction#CHANGES_OK_NOT_CONFIRMED}), and {@code PF12} once details
     * have been fetched are valid; any other key is coerced to {@code ENTER}
     * ({@code IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE}).</p>
     *
     * <p><b>{@code EVALUATE TRUE} (lines 924-1004).</b></p>
     * <ul>
     *   <li><b>{@code WHEN CCARD-AID-PFK03}</b> (lines 927-960): record the return
     *       target ({@code CDEMO-TO-*}; a blank origin defaults to the main menu),
     *       stamp this program as the caller, reset to first-entry, store the last
     *       map/mapset, and return {@link ScreenOutcome#EXIT_TO_CALLER} - the COBOL
     *       {@code SYNCPOINT} + {@code XCTL}. {@code WS-EXIT-MESSAGE} (the never-set
     *       {@link #MSG_PF3_EXITING} 88 at COBOL line 481) is intentionally not
     *       emitted, matching the source.</li>
     *   <li><b>Fresh entry</b> (lines 964-973): a first display re-initializes the
     *       state, flips the context to re-enter, and shows an empty search
     *       screen.</li>
     *   <li><b>Update done or failed</b> (lines 979-991): after a completed
     *       ({@link ChangeAction#CHANGES_OKAYED_AND_DONE}) or failed
     *       ({@link ChangeAction#isChangesFailed()}) update, clear the search key
     *       ({@code INITIALIZE ... CDEMO-ACCT-ID}) and show a fresh search
     *       screen.</li>
     *   <li><b>{@code WHEN OTHER}</b> (lines 997-1003): the normal path - run
     *       {@link #processInputs(ProcessingState)} then
     *       {@link #decideAction(PfKey, AccountUpdateState, ProcessingState)} and
     *       re-show the screen.</li>
     * </ul>
     *
     * @param aid the remapped program-function key ({@code CCARD-AID-*})
     * @param st  the per-request processing state (wrapping the session state)
     * @return {@link ScreenOutcome#EXIT_TO_CALLER} for the PF3 {@code XCTL},
     *         otherwise {@link ScreenOutcome#SHOW_SCREEN}
     */
    private ScreenOutcome mainline(PfKey aid, ProcessingState st) {
        AccountUpdateState session = st.getState();

        // Lines 862-864: EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE). In Java the
        // abend path is a thrown LogicError (see abendRoutine) that propagates out
        // of the @Transactional process() and rolls the unit of work back - the
        // exact effect of a CICS abend unwinding its logical unit of work.

        // Lines 879-893: store passed data / initialize the COMMAREA on first entry.
        boolean fromMenuFirstPass =
                LIT_MENUPGM.equals(cobolTrim(nz(context.getFromProgram())))
                        && !context.isProgramReenter();
        if (context.isNew() || fromMenuFirstPass) {
            session.reset();          // INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA
            context.markEnter();      // SET CDEMO-PGM-ENTER
            // SET ACUP-DETAILS-NOT-FETCHED is implicit in reset() (the change action
            // returns to DETAILS_NOT_FETCHED and the OLD snapshot is discarded).
        }

        // Lines 914-919: SET PFK-INVALID; validate the AID for this state; an
        // invalid key is coerced to ENTER (re-display the current screen).
        boolean pfkValid = aid == PfKey.ENTER
                || aid == PfKey.PFK03
                || (aid == PfKey.PFK05
                        && session.getChangeAction() == ChangeAction.CHANGES_OK_NOT_CONFIRMED)
                || (aid == PfKey.PFK12 && !session.isDetailsNotFetched());
        PfKey effectiveKey = pfkValid ? aid : PfKey.ENTER;

        // Lines 924-1004: EVALUATE TRUE (first-match).
        if (effectiveKey == PfKey.PFK03) {
            // Lines 927-960: PF3 -> XCTL to caller (or main menu when origin blank).
            if (isBlankRaw(context.getFromTranid())) {
                context.setToTranid(LIT_MENUTRANID);          // blank origin -> CM00
            } else {
                context.setToTranid(context.getFromTranid());
            }
            if (isBlankRaw(context.getFromProgram())) {
                context.setToProgram(LIT_MENUPGM);            // blank origin -> COMEN01C
            } else {
                context.setToProgram(context.getFromProgram());
            }
            context.setFromTranid(LIT_THISTRANID);            // MOVE LIT-THISTRANID TO CDEMO-FROM-TRANID
            context.setFromProgram(LIT_THISPGM);              // MOVE LIT-THISPGM TO CDEMO-FROM-PROGRAM
            context.setUser();                                 // SET CDEMO-USRTYP-USER
            context.markEnter();                               // SET CDEMO-PGM-ENTER
            context.setLastMapset(LIT_THISMAPSET);             // MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET
            context.setLastMap(LIT_THISMAP);                   // MOVE LIT-THISMAP TO CDEMO-LAST-MAP
            return ScreenOutcome.EXIT_TO_CALLER;               // EXEC CICS SYNCPOINT + XCTL
        }

        boolean freshEntry = (session.isDetailsNotFetched() && context.isProgramEnter())
                || fromMenuFirstPass;
        if (freshEntry) {
            // Lines 964-973: fresh entry -> ask the user for the search keys.
            session.reset();                                          // INITIALIZE WS-THIS-PROGCOMMAREA
            context.markReenter();                                    // SET CDEMO-PGM-REENTER
            session.setChangeAction(ChangeAction.DETAILS_NOT_FETCHED); // SET ACUP-DETAILS-NOT-FETCHED
            return ScreenOutcome.SHOW_SCREEN;                         // PERFORM 3000-SEND-MAP
        }

        ChangeAction action = session.getChangeAction();
        if (action == ChangeAction.CHANGES_OKAYED_AND_DONE || action.isChangesFailed()) {
            // Lines 979-991: update done or failed -> reset the search key, then a
            // fresh screen. The COBOL sets PGM-ENTER (so 3250-SETUP-INFOMSG picks the
            // "prompt for search keys" info line) and immediately PGM-REENTER; the net
            // context state is re-enter, and the DETAILS_NOT_FETCHED action drives the
            // same green info line via the controller.
            session.reset();                                          // INITIALIZE WS-THIS-PROGCOMMAREA WS-MISC-STORAGE
            context.setAcctId(0L);                                    // INITIALIZE CDEMO-ACCT-ID (zeroes)
            context.markEnter();                                      // SET CDEMO-PGM-ENTER
            context.markReenter();                                    // SET CDEMO-PGM-REENTER
            session.setChangeAction(ChangeAction.DETAILS_NOT_FETCHED); // SET ACUP-DETAILS-NOT-FETCHED
            return ScreenOutcome.SHOW_SCREEN;                         // PERFORM 3000-SEND-MAP
        }

        // Lines 997-1003: WHEN OTHER -> the normal path.
        processInputs(st);                                            // PERFORM 1000-PROCESS-INPUTS
        decideAction(effectiveKey, session, st);                      // PERFORM 2000-DECIDE-ACTION
        return ScreenOutcome.SHOW_SCREEN;                             // PERFORM 3000-SEND-MAP
    }

    /**
     * Generates a fresh, single-use confirmation token (review finding #10).
     *
     * <p>Returns a 256-bit {@link SecureRandom} value hex-encoded to a 64-character
     * string. The token is issued by {@link #armConfirmation} at the moment the state
     * advances to {@link ChangeAction#CHANGES_OK_NOT_CONFIRMED}, echoed to the confirm
     * screen as a hidden field, and validated then consumed on the {@code PF5} commit
     * so a replayed or forged confirmation cannot re-drive the write.</p>
     *
     * @return a new random hexadecimal confirmation token
     */
    private static String newConfirmToken() {
        byte[] raw = new byte[CONFIRM_TOKEN_BYTES];
        CONFIRM_TOKEN_RNG.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    /**
     * Constant-time comparison of the presented and expected confirmation tokens
     * (review finding #10). Uses {@link MessageDigest#isEqual(byte[], byte[])} so the
     * check does not leak token contents through timing, and treats a {@code null}
     * expected or presented token as a non-match.
     *
     * @param expected  the server-side token bound to the pending snapshot (may be {@code null})
     * @param presented the token echoed back by the client on {@code PF5} (may be {@code null})
     * @return {@code true} only when both are non-{@code null} and byte-equal
     */
    private static boolean confirmTokensMatch(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Arms the confirmation window - captures the validated {@code ACUP-NEW-DETAILS}
     * snapshot server-side and issues a fresh single-use token (review finding #10).
     *
     * <p>Called at each point where the state advances to
     * {@link ChangeAction#CHANGES_OK_NOT_CONFIRMED} (the {@code SET
     * ACUP-CHANGES-OK-NOT-CONFIRMED} of COBOL 1200 line 1674 and 2000 line 2590). The
     * captured {@link NewInput} is the values the operator is shown for confirmation;
     * on the {@code PF5} turn {@link #decideAction} commits exactly this snapshot -
     * never the re-posted client values - reproducing the mainframe behaviour where
     * the confirmation-screen fields are protected and therefore unchangeable. The
     * per-request {@link ProcessingState} rebuilds its {@link NewInput} from the form
     * each turn, so the reference captured here is detached from later turns and is
     * safe to hold on the session-scoped state.</p>
     *
     * @param session the session-scoped state that carries the snapshot and token
     * @param st      the per-request state whose validated {@link NewInput} is captured
     */
    private static void armConfirmation(AccountUpdateState session, ProcessingState st) {
        session.setPendingInput(st.getNewInput());
        session.setConfirmToken(newConfirmToken());
    }

    /**
     * Rejects a {@code PF5} confirmation whose single-use token is missing, forged, or
     * replayed (review finding #10) - no write is performed and the re-posted client
     * values are discarded. When a validated pending snapshot is still held, the
     * confirmation window stays open with a freshly re-issued token and the
     * {@link #MSG_CONFIRM_INTEGRITY} banner, so a legitimate operator can simply
     * confirm again; when no snapshot is held (a {@code PF5} that could not have
     * legitimately reached the confirm state), the action falls back to
     * {@link ChangeAction#SHOW_DETAILS}.
     *
     * @param session the session-scoped state
     * @param st      the per-request state whose red banner is set
     */
    private void rejectConfirmation(AccountUpdateState session, ProcessingState st) {
        if (session.getPendingInput() != null) {
            // Re-issue the token: the stale or forged value is now worthless and the
            // caller cannot learn the replacement, but the operator can retry.
            session.setConfirmToken(newConfirmToken());
        } else {
            session.setConfirmToken(null);
            session.setChangeAction(ChangeAction.SHOW_DETAILS);
        }
        st.getEdit().fail(MSG_CONFIRM_INTEGRITY);
    }

    /**
     * Restores the server-carried validated confirmation before the write (review
     * finding #10). The pending {@code ACUP-NEW-DETAILS} snapshot replaces whatever
     * the {@code PF5} turn re-posted, and the account/customer identity is re-affirmed
     * from the fetched {@code ACUP-OLD-DETAILS} baseline so {@link #writeProcessing}
     * updates exactly the record shown for confirmation. Identity is already
     * server-carried on {@link CardDemoContext} (editAccount runs only in the
     * not-fetched state, so it is never re-derived from the client on the confirm
     * turn); re-affirming it here is defense in depth that also guards against future
     * refactors.
     *
     * @param session the session-scoped state holding the pending snapshot and old baseline
     * @param st      the per-request state whose {@link NewInput} is replaced
     */
    private void restoreServerCarriedConfirmation(AccountUpdateState session, ProcessingState st) {
        st.setNewInput(session.getPendingInput());
        AccountSnapshot old = session.getOldSnapshot();
        if (old != null) {
            Long acctId = parseLongOrNull(old.getAcctId());
            if (acctId != null) {
                context.setAcctId(acctId);
            }
            Long custId = parseLongOrNull(old.getCustId());
            if (custId != null) {
                context.setCustId(custId);
            }
        }
    }

    /**
     * Action state machine - migration of paragraph {@code 2000-DECIDE-ACTION}
     * ({@code legacy/cbl/COACTUPC.cbl} lines 2557-2640). Reproduces the
     * {@code EVALUATE TRUE} (first-match) that, from the current
     * {@code ACUP-CHANGE-ACTION} and the pressed key, either (re-)reads the account,
     * advances to the confirmation prompt, performs the update, or abends on an
     * unexpected state.
     *
     * <ul>
     *   <li><b>{@code WHEN ACUP-DETAILS-NOT-FETCHED} / {@code WHEN CCARD-AID-PFK12}</b>
     *       (lines 2560-2581, two conditions sharing one body): when the account
     *       filter passed its edit ({@code FLG-ACCTFILTER-ISVALID}), clear the banner
     *       ({@code SET WS-RETURN-MSG-OFF}, {@link EditContext#resetMessage()}), read
     *       the account chain ({@link #readAcct(ProcessingState)}), and on a found
     *       customer advance to {@link ChangeAction#SHOW_DETAILS}. PF12 is the "user
     *       cancels the changes" re-fetch.</li>
     *   <li><b>{@code WHEN ACUP-SHOW-DETAILS}</b> (lines 2586-2593): if there is no
     *       edit error and a real change was detected, advance to
     *       {@link ChangeAction#CHANGES_OK_NOT_CONFIRMED}; otherwise
     *       {@code CONTINUE}. {@code NO-CHANGES-DETECTED} is the banner 88-level
     *       (COBOL line 491) equal to {@link #MSG_NO_CHANGES_DETECTED}.</li>
     *   <li><b>{@code WHEN ACUP-CHANGES-NOT-OK}</b> (lines 2598-2599):
     *       {@code CONTINUE} - re-display the recorded edit errors.</li>
     *   <li><b>{@code WHEN ACUP-CHANGES-OK-NOT-CONFIRMED AND CCARD-AID-PFK05}</b>
     *       (lines 2604-2616): confirmation given - perform
     *       {@link #writeProcessing(ProcessingState)} and map the resulting
     *       {@link WriteResult} through the inner {@code EVALUATE} (lines 2606-2615):
     *       {@link WriteResult#COULD_NOT_LOCK_ACCT} &rarr;
     *       {@link ChangeAction#CHANGES_OKAYED_LOCK_ERROR};
     *       {@link WriteResult#LOCKED_BUT_UPDATE_FAILED} &rarr;
     *       {@link ChangeAction#CHANGES_OKAYED_BUT_FAILED};
     *       {@link WriteResult#DATA_CHANGED} &rarr; {@link ChangeAction#SHOW_DETAILS}
     *       (re-display, not an error); {@code WHEN OTHER} &rarr;
     *       {@link ChangeAction#CHANGES_OKAYED_AND_DONE}. The {@code WHEN OTHER}
     *       covers both {@link WriteResult#SUCCESS} and - by a preserved COBOL quirk -
     *       {@link WriteResult#COULD_NOT_LOCK_CUST}, whose banner-literal test in the
     *       source does not match any earlier branch and therefore falls through to
     *       "done" (documented parity, AAP &sect;0.6.5).</li>
     *   <li><b>{@code WHEN ACUP-CHANGES-OK-NOT-CONFIRMED}</b> (lines 2620-2621):
     *       {@code CONTINUE} - keep prompting for confirmation (PF5 not yet
     *       pressed).</li>
     *   <li><b>{@code WHEN ACUP-CHANGES-OKAYED-AND-DONE}</b> (lines 2625-2632):
     *       return to {@link ChangeAction#SHOW_DETAILS} and, when the origin
     *       transaction is blank, clear the identity keys ({@code MOVE ZEROES TO
     *       CDEMO-ACCT-ID CDEMO-CARD-NUM}, {@code MOVE LOW-VALUES TO
     *       CDEMO-ACCT-STATUS}). This branch is defensively unreachable - the
     *       {@code 0000-MAIN} EVALUATE intercepts {@code ACUP-CHANGES-OKAYED-AND-DONE}
     *       before {@code 2000-DECIDE-ACTION} runs - but is preserved for 1:1
     *       fidelity.</li>
     *   <li><b>{@code WHEN OTHER}</b> (lines 2634-2640): an unexpected state -
     *       {@link #abendRoutine(String, String)} with code
     *       {@link #ABEND_CODE_UNEXPECTED} and message
     *       {@link #ABEND_MSG_UNEXPECTED}.</li>
     * </ul>
     *
     * @param aid     the remapped program-function key ({@code CCARD-AID-*})
     * @param session the session-scoped program state (its {@code ACUP-CHANGE-ACTION}
     *                is advanced in place)
     * @param st      the per-request processing state (edit flags, filters, fetched
     *                records, and the write result)
     */
    private void decideAction(PfKey aid, AccountUpdateState session, ProcessingState st) {
        EditContext edit = st.getEdit();
        ChangeAction action = session.getChangeAction();

        // Lines 2560-2581: WHEN ACUP-DETAILS-NOT-FETCHED / WHEN CCARD-AID-PFK12 ->
        // (re-)read the account when the filter is valid. PF12 = user cancels changes.
        if (action == ChangeAction.DETAILS_NOT_FETCHED || aid == PfKey.PFK12) {
            if (st.getAccountFilter() == FilterFlag.VALID) {   // IF FLG-ACCTFILTER-ISVALID
                edit.resetMessage();                            // SET WS-RETURN-MSG-OFF TO TRUE
                readAcct(st);                                   // PERFORM 9000-READ-ACCT
                if (st.isFoundCustInMaster()) {                 // IF FOUND-CUST-IN-MASTER
                    session.setChangeAction(ChangeAction.SHOW_DETAILS);
                }
            }
            return;
        }

        // Lines 2586-2593: WHEN ACUP-SHOW-DETAILS -> advance to confirmation when a
        // clean, real change is present.
        if (action == ChangeAction.SHOW_DETAILS) {
            boolean noChangesDetected = MSG_NO_CHANGES_DETECTED.equals(edit.getReturnMsg());
            if (edit.isInputError() || noChangesDetected) {     // IF INPUT-ERROR OR NO-CHANGES-DETECTED
                return;                                          // CONTINUE
            }
            session.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
            // Review finding #10: arm the confirmation window here too (this WHEN
            // ACUP-SHOW-DETAILS advance is the second SET ACUP-CHANGES-OK-NOT-CONFIRMED
            // site, COBOL line 2590), so the pending snapshot and token are always in
            // place before the confirm screen is shown.
            armConfirmation(session, st);
            return;
        }

        // Lines 2598-2599: WHEN ACUP-CHANGES-NOT-OK -> CONTINUE (re-display errors).
        if (action == ChangeAction.CHANGES_NOT_OK) {
            return;
        }

        // Lines 2604-2616: WHEN ACUP-CHANGES-OK-NOT-CONFIRMED AND CCARD-AID-PFK05 ->
        // confirmation given: perform the update, then map the write result.
        if (action == ChangeAction.CHANGES_OK_NOT_CONFIRMED && aid == PfKey.PFK05) {
            // Review finding #10 (confirmation integrity, CWE-20/CWE-639). On the
            // mainframe the confirmation-screen fields are protected, so the PF5 turn
            // can only re-present the validated ACUP-NEW-DETAILS. Over HTTP a crafted
            // PF5 can re-post arbitrary values, and editMapInputs deliberately skips
            // re-validation in the confirm state (COBOL 1200 line 1464), so the server
            // must (a) reject a missing / forged / replayed single-use token and
            // (b) commit the server-carried validated snapshot and identity - never
            // the re-post.
            boolean tokenOk = confirmTokensMatch(session.getConfirmToken(),
                    st.getForm().getConfirmToken());
            if (!tokenOk || session.getPendingInput() == null) {
                rejectConfirmation(session, st);
                return;
            }
            restoreServerCarriedConfirmation(session, st);      // commit validated snapshot + identity
            writeProcessing(st);                                // PERFORM 9600-WRITE-PROCESSING
            switch (st.getWriteResult()) {                      // inner EVALUATE (lines 2606-2615)
                case COULD_NOT_LOCK_ACCT ->
                        session.setChangeAction(ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
                case LOCKED_BUT_UPDATE_FAILED ->
                        session.setChangeAction(ChangeAction.CHANGES_OKAYED_BUT_FAILED);
                case DATA_CHANGED ->
                        session.setChangeAction(ChangeAction.SHOW_DETAILS);
                default ->
                        // WHEN OTHER: SUCCESS and - by preserved COBOL quirk - the
                        // customer-lock failure both land here (see method Javadoc).
                        session.setChangeAction(ChangeAction.CHANGES_OKAYED_AND_DONE);
            }
            // Consume the single-use confirmation regardless of the write result so a
            // replay cannot re-drive the write; a fresh cycle re-arms via 1200 / 2000.
            session.setPendingInput(null);
            session.setConfirmToken(null);
            return;
        }

        // Lines 2620-2621: WHEN ACUP-CHANGES-OK-NOT-CONFIRMED (no PF5) -> CONTINUE.
        if (action == ChangeAction.CHANGES_OK_NOT_CONFIRMED) {
            return;
        }

        // Lines 2625-2632: WHEN ACUP-CHANGES-OKAYED-AND-DONE -> back to SHOW-DETAILS;
        // clear identity keys when the origin transaction is blank. Defensively
        // unreachable (0000-MAIN intercepts this state), preserved for fidelity.
        if (action == ChangeAction.CHANGES_OKAYED_AND_DONE) {
            session.setChangeAction(ChangeAction.SHOW_DETAILS);
            if (isBlankRaw(context.getFromTranid())) {
                context.setAcctId(0L);                          // MOVE ZEROES TO CDEMO-ACCT-ID
                context.setCardNum(zeroPad(0L, CARD_NUM_WIDTH)); // MOVE ZEROES TO CDEMO-CARD-NUM
                context.setAcctStatus(null);                    // MOVE LOW-VALUES TO CDEMO-ACCT-STATUS
            }
            return;
        }

        // Lines 2634-2640: WHEN OTHER -> unexpected state; abend with code '0001'.
        abendRoutine(ABEND_CODE_UNEXPECTED, ABEND_MSG_UNEXPECTED);
    }

    /**
     * Abend handler - migration of paragraph {@code ABEND-ROUTINE}
     * ({@code legacy/cbl/COACTUPC.cbl} lines 4203-4236). In the source this sends
     * the {@code ABEND-DATA} screen and issues {@code EXEC CICS ABEND ABCODE('9999')},
     * unconditionally terminating the transaction and rolling back its logical unit
     * of work.
     *
     * <p>The Java equivalent throws an unchecked {@link LogicError} (a
     * {@link com.aws.carddemo.exception.FileStatusException}) carrying the culprit
     * program ({@link #LIT_THISPGM}, the {@code MOVE LIT-THISPGM TO ABEND-CULPRIT} of
     * line 4210), the passed abend code, the CICS abend code
     * ({@link #ABEND_ABCODE}, {@code '9999'}), and the diagnostic message. A blank
     * message is defaulted to {@link #ABEND_MSG_DEFAULT}
     * ({@code 'UNEXPECTED ABEND OCCURRED.'}, the {@code IF ABEND-MSG EQUAL LOW-VALUES}
     * guard of lines 4205-4207). Because the exception is unchecked and propagates
     * out of the {@link Transactional @Transactional} {@link #process} method, Spring
     * rolls the unit of work back - reproducing the CICS abend rollback. This method
     * never returns normally.</p>
     *
     * @param abendCode the four-character abend code ({@code ABEND-CODE}, for example
     *                  {@link #ABEND_CODE_UNEXPECTED})
     * @param abendMsg  the diagnostic message ({@code ABEND-MSG}); a blank value is
     *                  replaced with {@link #ABEND_MSG_DEFAULT}
     * @throws LogicError always - the abend-equivalent unchecked exception
     */
    private void abendRoutine(String abendCode, String abendMsg) {
        // Lines 4205-4207: IF ABEND-MSG EQUAL LOW-VALUES MOVE default TO ABEND-MSG.
        String effectiveMsg = isBlankRaw(abendMsg) ? ABEND_MSG_DEFAULT : abendMsg;
        // Lines 4210-4232: MOVE LIT-THISPGM TO ABEND-CULPRIT; SEND ABEND-DATA;
        // HANDLE ABEND CANCEL; EXEC CICS ABEND ABCODE('9999'). Modeled as a thrown
        // LogicError that carries the same diagnostic fields and forces rollback.
        String detail = "ABEND in program " + LIT_THISPGM
                + " (code " + nz(abendCode) + ", abcode " + ABEND_ABCODE + "): " + effectiveMsg;
        throw new LogicError(detail);
    }

}

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

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COCRDUPForm;
import com.aws.carddemo.exception.LogicError;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.CardRepository;
import java.io.Serializable;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit-card update service.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COCRDUPC.cbl} (CICS COBOL program
 * {@code COCRDUPC}, transaction id {@code CCUP}). This class is the faithful Java
 * migration of the online <em>Credit Card Update</em> program, preserving the
 * COBOL control flow one-for-one: each business {@code PARAGRAPH} becomes exactly
 * one method (AAP &sect;0.3.3 service layer; AAP &sect;0.4.1 online-programs table
 * {@code COCRDUPC.cbl -> CardUpdateService + CardController update}).</p>
 *
 * <h2>Archetype: READ-UPDATE-REWRITE with optimistic lock</h2>
 * <p>{@code COCRDUPC} fetches a single card by its 16-digit primary key, presents
 * the editable attributes (embossed name, active status, expiry month/year), and
 * on explicit confirmation re-reads the record, verifies it has not changed since
 * it was fetched (optimistic lock), and rewrites it. The whole request runs inside
 * a single transaction &mdash; see {@link #mainEntry(COCRDUPForm, CardWorkArea,
 * CardUpdateState)}, which carries the {@link Transactional} boundary (AAP
 * &sect;0.6.5 exception mapping; &sect;0.6.8 pseudo-conversational state).</p>
 *
 * <h2>The presentation split</h2>
 * <p>Following the established online-tier convention, this service implements only
 * the business paragraphs and deliberately contains no {@code SEND}/{@code RECEIVE},
 * cursor, colour, or BMS attribute logic; those belong to the paired
 * {@code CardController} and the Thymeleaf view. The {@code 3000}-series
 * presentation paragraphs, {@code YYYY-STORE-PFKEY}, and {@code COMMON-RETURN} are
 * therefore owned by the controller.</p>
 * <table border="1">
 *   <caption>COCRDUPC paragraph &rarr; Java mapping</caption>
 *   <tr><th>COBOL paragraph</th><th>Owner</th><th>Java member</th></tr>
 *   <tr><td>{@code 0000-MAIN}</td><td>service</td>
 *       <td>{@link #mainEntry(COCRDUPForm, CardWorkArea, CardUpdateState)}</td></tr>
 *   <tr><td>{@code 1000-PROCESS-INPUTS}</td><td>service</td>
 *       <td>{@link #processInputs(COCRDUPForm, CardWorkArea, CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code 1100-RECEIVE-MAP}</td><td>controller&nbsp;+&nbsp;service</td>
 *       <td>&mdash; (form binding) + field normalization in {@code processInputs}</td></tr>
 *   <tr><td>{@code 1200-EDIT-MAP-INPUTS}</td><td>service</td>
 *       <td>{@link #editMapInputs(CardWorkArea, CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code 1210-EDIT-ACCOUNT}</td><td>service</td>
 *       <td>{@link #editAccount(CardWorkArea, CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code 1220-EDIT-CARD}</td><td>service</td>
 *       <td>{@link #editCard(CardWorkArea, CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code 1230-EDIT-NAME}</td><td>service</td>
 *       <td>{@link #editName(CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code 1240-EDIT-CARDSTATUS}</td><td>service</td>
 *       <td>{@link #editCardStatus(CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code 1250-EDIT-EXPIRY-MON}</td><td>service</td>
 *       <td>{@link #editExpiryMonth(CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code 1260-EDIT-EXPIRY-YEAR}</td><td>service</td>
 *       <td>{@link #editExpiryYear(CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code 2000-DECIDE-ACTION}</td><td>service</td>
 *       <td>{@link #decideAction(CardWorkArea, CardUpdateState, EditWorkState, PfKey)}</td></tr>
 *   <tr><td>{@code 9000-READ-DATA}</td><td>service</td>
 *       <td>{@link #readData(CardWorkArea, CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code 9100-GETCARD-BYACCTCARD}</td><td>service</td>
 *       <td>{@link #getCardByAccountCard(String)}</td></tr>
 *   <tr><td>{@code 9200-WRITE-PROCESSING}</td><td>service</td>
 *       <td>{@link #writeProcessing(CardWorkArea, CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code 9300-CHECK-CHANGE-IN-REC}</td><td>service</td>
 *       <td>{@link #checkChangeInRec(Card, CardUpdateState, EditWorkState)}</td></tr>
 *   <tr><td>{@code ABEND-ROUTINE}</td><td>service</td>
 *       <td>{@link #abendRoutine(String, String)}</td></tr>
 *   <tr><td>{@code 3000-3400} (SEND / attributes)</td><td>controller</td><td>&mdash; (view render)</td></tr>
 *   <tr><td>{@code YYYY-STORE-PFKEY}</td><td>controller</td><td>&mdash; (PF-key resolution)</td></tr>
 *   <tr><td>{@code COMMON-RETURN}</td><td>controller</td><td>&mdash; (return / redirect)</td></tr>
 * </table>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL {@code COMMAREA} splits into two carriers. The shared navigation
 * state ({@code COCOM01Y}) is the session-scoped {@link CardDemoContext}; the
 * program-private {@code WS-THIS-PROGCOMMAREA} (the change-action flag plus the
 * old/new field snapshots used for the optimistic lock) is the {@link
 * CardUpdateState} the controller persists in the HTTP session between requests.
 * A fresh {@link CardWorkArea} and {@link EditWorkState} are created per request,
 * mirroring the COBOL {@code INITIALIZE CC-WORK-AREA} / {@code WS-MISC-STORAGE}.</p>
 *
 * <h2>Preserved source quirks</h2>
 * <ul>
 *   <li><b>CVV is zeroed on every update.</b> {@code CCUP-NEW-CVV-CD} is never
 *       populated from the screen ({@code COCRDUP} has no CVV field); it is only
 *       {@code INITIALIZE}d to spaces, and the COBOL move through the numeric
 *       redefinition {@code CARD-CVV-CD-X -> CARD-CVV-CD-N} coerces spaces to
 *       {@code 0}. The rewrite therefore always stores CVV {@code 0}. This
 *       behaviour is reproduced exactly (see {@link #writeProcessing}).</li>
 *   <li><b>The account written is the entered filter.</b> {@code 9200} moves
 *       {@code CC-ACCT-ID-N} (the on-screen account filter) into the rewritten
 *       record rather than the card's persisted owning account, so the update can
 *       re-point the card at the filter account. Preserved faithfully.</li>
 * </ul>
 *
 * <p>This program performs no monetary arithmetic; it manipulates card metadata
 * only, so no {@link java.math.BigDecimal} is involved.</p>
 */
@Service
public class CardUpdateService {

    /** COBOL {@code LIT-THISTRANID} &mdash; this program's transaction id. */
    private static final String THIS_TRANID = "CCUP";
    /** COBOL {@code LIT-THISPGM} &mdash; this program's name (abend culprit). */
    private static final String THIS_PGM = "COCRDUPC";
    /** COBOL {@code LIT-THISMAPSET}. */
    private static final String THIS_MAPSET = "COCRDUP";
    /** COBOL {@code LIT-THISMAP}. */
    private static final String THIS_MAP = "CCRDUPA";
    /** COBOL {@code LIT-CCLISTPGM} &mdash; the card-list program we may return from. */
    private static final String CCLIST_PGM = "COCRDLIC";
    /** COBOL {@code LIT-CCLISTMAPSET} &mdash; the card-list mapset. */
    private static final String CCLIST_MAPSET = "COCRDLI";
    /** COBOL {@code LIT-MENUPGM} &mdash; the main-menu program. */
    private static final String MENU_PGM = "COMEN01C";
    /** COBOL {@code LIT-MENUTRANID} &mdash; the main-menu transaction id. */
    private static final String MENU_TRANID = "CM00";

    /**
     * Sixteen ASCII zeros &mdash; the {@code CDEMO-CARD-NUM} reset value used where
     * the COBOL performs {@code MOVE ZEROS TO CDEMO-CARD-NUM} (a {@code PIC X(16)}
     * field, so the zeros are character positions, not a numeric literal).
     */
    private static final String ZEROS_CARD = "0000000000000000";

    // ---------------------------------------------------------------------
    // Error messages (COBOL WS-RETURN-MSG 88-levels + inline MOVE literals).
    // Verbatim text preserved for byte-level screen parity (WS-RETURN-MSG is
    // PIC X(75); the form errmsg field is X(80), so every message fits).
    // ---------------------------------------------------------------------
    /** COBOL {@code WS-PROMPT-FOR-ACCT}. */
    private static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";
    /** COBOL {@code WS-PROMPT-FOR-CARD}. */
    private static final String MSG_PROMPT_FOR_CARD = "Card number not provided";
    /** COBOL {@code WS-PROMPT-FOR-NAME}. */
    private static final String MSG_PROMPT_FOR_NAME = "Card name not provided";
    /** COBOL {@code WS-NAME-MUST-BE-ALPHA}. */
    private static final String MSG_NAME_MUST_BE_ALPHA =
            "Card name can only contain alphabets and spaces";
    /** COBOL {@code NO-SEARCH-CRITERIA-RECEIVED}. */
    private static final String MSG_NO_SEARCH_CRITERIA = "No input received";
    /** COBOL {@code NO-CHANGES-DETECTED}. */
    private static final String MSG_NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";
    /** COBOL {@code CARD-STATUS-MUST-BE-YES-NO}. */
    private static final String MSG_STATUS_MUST_BE_YES_NO = "Card Active Status must be Y or N";
    /** COBOL {@code CARD-EXPIRY-MONTH-NOT-VALID}. */
    private static final String MSG_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";
    /** COBOL {@code CARD-EXPIRY-YEAR-NOT-VALID}. */
    private static final String MSG_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";
    /** COBOL {@code DID-NOT-FIND-ACCTCARD-COMBO}. */
    private static final String MSG_DID_NOT_FIND_ACCTCARD =
            "Did not find cards for this search condition";
    /** COBOL {@code COULD-NOT-LOCK-FOR-UPDATE}. */
    private static final String MSG_COULD_NOT_LOCK = "Could not lock record for update";
    /** COBOL {@code DATA-WAS-CHANGED-BEFORE-UPDATE}. */
    private static final String MSG_DATA_WAS_CHANGED =
            "Record changed by some one else. Please review";
    /** COBOL {@code LOCKED-BUT-UPDATE-FAILED}. */
    private static final String MSG_UPDATE_FAILED = "Update of record failed";
    /** COBOL inline literal in {@code 1210-EDIT-ACCOUNT}. */
    private static final String MSG_ACCT_MUST_BE_11_DIGITS =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    /** COBOL inline literal in {@code 1220-EDIT-CARD}. */
    private static final String MSG_CARD_MUST_BE_16_DIGITS =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * Session-scoped navigation context ({@code COCOM01Y} / {@code COMMAREA}).
     * Injected as a proxy so each HTTP session sees its own instance.
     */
    private final CardDemoContext context;

    /** Card KSDS repository ({@code CARDDAT}); the AWS CardDemo {@code CARDDAT} file. */
    private final CardRepository cardRepository;

    /**
     * Constructor injection of collaborators (AAP &sect;0.3.3; replaces COBOL
     * static {@code CALL}/{@code XCTL} linkage with Spring dependency injection).
     *
     * @param context        the session-scoped {@link CardDemoContext}
     * @param cardRepository  the {@link CardRepository} over {@code CARDDAT}
     */
    public CardUpdateService(CardDemoContext context, CardRepository cardRepository) {
        this.context = context;
        this.cardRepository = cardRepository;
    }

    // =====================================================================
    // Nested contract types
    // =====================================================================

    /**
     * Routing outcome the paired {@code CardController} must honour after
     * {@link #mainEntry} returns. Mirrors the two terminal behaviours of the
     * COBOL {@code 0000-MAIN} {@code EVALUATE}: transfer control to another
     * program ({@code EXEC CICS XCTL}) or re-display this program's screen
     * ({@code 3000-SEND-MAP} then {@code EXEC CICS RETURN TRANSID}).
     */
    public enum RoutingAction {
        /** {@code XCTL} to another program (PF3 exit / done-from-list). */
        REDIRECT,
        /** Re-render the {@code CCRDUPA} screen for this transaction. */
        SHOW_SCREEN
    }

    /**
     * Four-state field-edit flag mirroring the COBOL edit 88-levels
     * ({@code *-NOT-OK} / {@code *-ISVALID} / {@code *-BLANK}) plus a synthetic
     * {@link #UNSET} for the {@code LOW-VALUES} initial state, which is distinct
     * from all three and drives the controller's cursor/colour selection.
     */
    public enum EditFlag {
        /** COBOL {@code LOW-VALUES}: the field has not been edited this request. */
        UNSET,
        /** COBOL {@code *-NOT-OK VALUE '0'}: edited and found invalid. */
        NOT_OK,
        /** COBOL {@code *-ISVALID VALUE '1'}: edited and valid. */
        ISVALID,
        /** COBOL {@code *-BLANK VALUE ' '}: edited but not supplied. */
        BLANK
    }

    /**
     * Change-action state machine ({@code CCUP-CHANGE-ACTION}, {@code PIC X(1)}).
     * Each constant carries the COBOL single-character value; the grouping
     * 88-levels {@code CCUP-CHANGES-MADE} and {@code CCUP-CHANGES-FAILED} are
     * exposed as {@link #isChangesMade()} / {@link #isChangesFailed()}.
     */
    public enum ChangeAction {
        /** {@code CCUP-DETAILS-NOT-FETCHED} ({@code LOW-VALUES}/{@code SPACES}). */
        DETAILS_NOT_FETCHED,
        /** {@code CCUP-SHOW-DETAILS VALUE 'S'}. */
        SHOW_DETAILS,
        /** {@code CCUP-CHANGES-NOT-OK VALUE 'E'}. */
        CHANGES_NOT_OK,
        /** {@code CCUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'}. */
        CHANGES_OK_NOT_CONFIRMED,
        /** {@code CCUP-CHANGES-OKAYED-AND-DONE VALUE 'C'}. */
        CHANGES_OKAYED_AND_DONE,
        /** {@code CCUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'}. */
        CHANGES_OKAYED_LOCK_ERROR,
        /** {@code CCUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'}. */
        CHANGES_OKAYED_BUT_FAILED;

        /**
         * COBOL {@code CCUP-CHANGES-MADE} (values {@code 'E' 'N' 'C' 'L' 'F'}).
         *
         * @return {@code true} for every state other than
         *         {@link #DETAILS_NOT_FETCHED} and {@link #SHOW_DETAILS}
         */
        public boolean isChangesMade() {
            return this == CHANGES_NOT_OK
                    || this == CHANGES_OK_NOT_CONFIRMED
                    || this == CHANGES_OKAYED_AND_DONE
                    || this == CHANGES_OKAYED_LOCK_ERROR
                    || this == CHANGES_OKAYED_BUT_FAILED;
        }

        /**
         * COBOL {@code CCUP-CHANGES-FAILED} (values {@code 'L' 'F'}).
         *
         * @return {@code true} for the lock-error and update-failed states
         */
        public boolean isChangesFailed() {
            return this == CHANGES_OKAYED_LOCK_ERROR || this == CHANGES_OKAYED_BUT_FAILED;
        }
    }

    /**
     * Program-private pseudo-conversational state ({@code WS-THIS-PROGCOMMAREA}).
     *
     * <p>Holds the change-action flag and the {@code CCUP-OLD-*} / {@code CCUP-NEW-*}
     * snapshots. The old snapshot is captured when the card is fetched and is
     * compared against a fresh re-read at save time to implement the COBOL
     * optimistic lock ({@code 9300-CHECK-CHANGE-IN-REC}). The controller persists
     * this object in the HTTP session across the pseudo-conversational turns, so
     * it is {@link Serializable} in line with {@link CardDemoContext}. All value
     * fields are {@link String} to preserve the COBOL {@code PIC X(n)} layouts;
     * a {@code null} value represents COBOL {@code LOW-VALUES} ("not supplied").</p>
     */
    public static final class CardUpdateState implements Serializable {

        private static final long serialVersionUID = 1L;

        private ChangeAction changeAction = ChangeAction.DETAILS_NOT_FETCHED;

        private String oldAcctId;
        private String oldCardId;
        private String oldCvvCd;
        private String oldCrdName;
        private String oldExpYear;
        private String oldExpMon;
        private String oldExpDay;
        private String oldCrdStcd;

        private String newAcctId;
        private String newCardId;
        private String newCvvCd;
        private String newCrdName;
        private String newExpYear;
        private String newExpMon;
        private String newExpDay;
        private String newCrdStcd;

        /** Creates a fresh state in the {@code CCUP-DETAILS-NOT-FETCHED} phase. */
        public CardUpdateState() {
            // Defaults mirror INITIALIZE WS-THIS-PROGCOMMAREA.
        }

        /** COBOL {@code INITIALIZE WS-THIS-PROGCOMMAREA}: reset to first-entry. */
        public void initialize() {
            this.changeAction = ChangeAction.DETAILS_NOT_FETCHED;
            this.oldAcctId = null;
            this.oldCardId = null;
            this.oldCvvCd = null;
            this.oldCrdName = null;
            this.oldExpYear = null;
            this.oldExpMon = null;
            this.oldExpDay = null;
            this.oldCrdStcd = null;
            initializeNewDetails();
        }

        /** COBOL {@code INITIALIZE CCUP-NEW-DETAILS} (performed in {@code 1100}). */
        public void initializeNewDetails() {
            this.newAcctId = null;
            this.newCardId = null;
            this.newCvvCd = null;
            this.newCrdName = null;
            this.newExpYear = null;
            this.newExpMon = null;
            this.newExpDay = null;
            this.newCrdStcd = null;
        }

        /** COBOL {@code INITIALIZE CCUP-OLD-DETAILS} (performed in {@code 9000}). */
        public void initializeOldDetails() {
            this.oldAcctId = null;
            this.oldCardId = null;
            this.oldCvvCd = null;
            this.oldCrdName = null;
            this.oldExpYear = null;
            this.oldExpMon = null;
            this.oldExpDay = null;
            this.oldCrdStcd = null;
        }

        public ChangeAction getChangeAction() {
            return changeAction;
        }

        public void setChangeAction(ChangeAction changeAction) {
            this.changeAction = changeAction;
        }

        /** @return {@code true} for {@code CCUP-DETAILS-NOT-FETCHED} (null-safe). */
        public boolean isDetailsNotFetched() {
            return changeAction == null || changeAction == ChangeAction.DETAILS_NOT_FETCHED;
        }

        /** @return {@code true} for {@code CCUP-SHOW-DETAILS}. */
        public boolean isShowDetails() {
            return changeAction == ChangeAction.SHOW_DETAILS;
        }

        /** @return {@code true} for {@code CCUP-CHANGES-NOT-OK}. */
        public boolean isChangesNotOk() {
            return changeAction == ChangeAction.CHANGES_NOT_OK;
        }

        /** @return {@code true} for {@code CCUP-CHANGES-OK-NOT-CONFIRMED}. */
        public boolean isChangesOkNotConfirmed() {
            return changeAction == ChangeAction.CHANGES_OK_NOT_CONFIRMED;
        }

        /** @return {@code true} for {@code CCUP-CHANGES-OKAYED-AND-DONE}. */
        public boolean isChangesOkayedAndDone() {
            return changeAction == ChangeAction.CHANGES_OKAYED_AND_DONE;
        }

        /** @return {@code true} for {@code CCUP-CHANGES-MADE} (null-safe). */
        public boolean isChangesMade() {
            return changeAction != null && changeAction.isChangesMade();
        }

        /** @return {@code true} for {@code CCUP-CHANGES-FAILED} (null-safe). */
        public boolean isChangesFailed() {
            return changeAction != null && changeAction.isChangesFailed();
        }

        public String getOldAcctId() {
            return oldAcctId;
        }

        public void setOldAcctId(String oldAcctId) {
            this.oldAcctId = oldAcctId;
        }

        public String getOldCardId() {
            return oldCardId;
        }

        public void setOldCardId(String oldCardId) {
            this.oldCardId = oldCardId;
        }

        public String getOldCvvCd() {
            return oldCvvCd;
        }

        public void setOldCvvCd(String oldCvvCd) {
            this.oldCvvCd = oldCvvCd;
        }

        public String getOldCrdName() {
            return oldCrdName;
        }

        public void setOldCrdName(String oldCrdName) {
            this.oldCrdName = oldCrdName;
        }

        public String getOldExpYear() {
            return oldExpYear;
        }

        public void setOldExpYear(String oldExpYear) {
            this.oldExpYear = oldExpYear;
        }

        public String getOldExpMon() {
            return oldExpMon;
        }

        public void setOldExpMon(String oldExpMon) {
            this.oldExpMon = oldExpMon;
        }

        public String getOldExpDay() {
            return oldExpDay;
        }

        public void setOldExpDay(String oldExpDay) {
            this.oldExpDay = oldExpDay;
        }

        public String getOldCrdStcd() {
            return oldCrdStcd;
        }

        public void setOldCrdStcd(String oldCrdStcd) {
            this.oldCrdStcd = oldCrdStcd;
        }

        public String getNewAcctId() {
            return newAcctId;
        }

        public void setNewAcctId(String newAcctId) {
            this.newAcctId = newAcctId;
        }

        public String getNewCardId() {
            return newCardId;
        }

        public void setNewCardId(String newCardId) {
            this.newCardId = newCardId;
        }

        public String getNewCvvCd() {
            return newCvvCd;
        }

        public void setNewCvvCd(String newCvvCd) {
            this.newCvvCd = newCvvCd;
        }

        public String getNewCrdName() {
            return newCrdName;
        }

        public void setNewCrdName(String newCrdName) {
            this.newCrdName = newCrdName;
        }

        public String getNewExpYear() {
            return newExpYear;
        }

        public void setNewExpYear(String newExpYear) {
            this.newExpYear = newExpYear;
        }

        public String getNewExpMon() {
            return newExpMon;
        }

        public void setNewExpMon(String newExpMon) {
            this.newExpMon = newExpMon;
        }

        public String getNewExpDay() {
            return newExpDay;
        }

        public void setNewExpDay(String newExpDay) {
            this.newExpDay = newExpDay;
        }

        public String getNewCrdStcd() {
            return newCrdStcd;
        }

        public void setNewCrdStcd(String newCrdStcd) {
            this.newCrdStcd = newCrdStcd;
        }
    }

    /**
     * Per-request working storage ({@code WS-MISC-STORAGE} edit flags plus the
     * write-outcome indicators). A fresh instance is created for every
     * {@link #mainEntry} invocation, mirroring the COBOL {@code INITIALIZE} of the
     * working-storage edit flags at the top of a pseudo-conversational turn.
     *
     * <p>Fields are private with public read accessors for the controller; the
     * enclosing {@link CardUpdateService} (a nestmate) assigns them directly, just
     * as the COBOL paragraphs {@code SET} the corresponding 88-levels.</p>
     */
    public static final class EditWorkState {

        /** COBOL {@code INPUT-ERROR} (the only tested state of {@code WS-INPUT-FLAG}). */
        private boolean inputError;

        private EditFlag acctFilter = EditFlag.UNSET;
        private EditFlag cardFilter = EditFlag.UNSET;
        private EditFlag cardName = EditFlag.UNSET;
        private EditFlag cardStatus = EditFlag.UNSET;
        private EditFlag cardExpMon = EditFlag.UNSET;
        private EditFlag cardExpYear = EditFlag.UNSET;

        /** COBOL {@code FOUND-CARDS-FOR-ACCOUNT}. */
        private boolean foundCardsForAccount;
        /** COBOL {@code NO-CHANGES-DETECTED}. */
        private boolean noChangesDetected;
        /** COBOL {@code COULD-NOT-LOCK-FOR-UPDATE} (as a post-write signal). */
        private boolean couldNotLockForUpdate;
        /** COBOL {@code LOCKED-BUT-UPDATE-FAILED} (as a post-write signal). */
        private boolean lockedButUpdateFailed;
        /** COBOL {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (optimistic-lock signal). */
        private boolean dataWasChangedBeforeUpdate;

        /** COBOL {@code WS-RETURN-MSG} (the on-screen error line, {@code null} = off). */
        private String returnMessage;

        public boolean isInputError() {
            return inputError;
        }

        public EditFlag getAcctFilter() {
            return acctFilter;
        }

        public EditFlag getCardFilter() {
            return cardFilter;
        }

        public EditFlag getCardName() {
            return cardName;
        }

        public EditFlag getCardStatus() {
            return cardStatus;
        }

        public EditFlag getCardExpMon() {
            return cardExpMon;
        }

        public EditFlag getCardExpYear() {
            return cardExpYear;
        }

        public boolean isFoundCardsForAccount() {
            return foundCardsForAccount;
        }

        public boolean isNoChangesDetected() {
            return noChangesDetected;
        }

        public boolean isCouldNotLockForUpdate() {
            return couldNotLockForUpdate;
        }

        public boolean isLockedButUpdateFailed() {
            return lockedButUpdateFailed;
        }

        public boolean isDataWasChangedBeforeUpdate() {
            return dataWasChangedBeforeUpdate;
        }

        public String getReturnMessage() {
            return returnMessage;
        }

        /** @return COBOL {@code WS-RETURN-MSG-OFF}: no error message is set yet. */
        public boolean isReturnMsgOff() {
            return returnMessage == null || returnMessage.isBlank();
        }
    }

    /**
     * Immutable result returned to the controller by {@link #mainEntry}. Bundles
     * the routing action, the render-time program-enter indicator, and the
     * per-request {@link EditWorkState} the view uses to drive field attributes
     * and the error line.
     */
    public static final class CardUpdateResult {

        private final RoutingAction action;
        private final boolean programEnter;
        private final EditWorkState editState;

        /**
         * @param action        the routing action ({@link RoutingAction})
         * @param programEnter   the render-time {@code CDEMO-PGM-ENTER} value
         * @param editState      the per-request edit/working state
         */
        public CardUpdateResult(RoutingAction action, boolean programEnter, EditWorkState editState) {
            this.action = action;
            this.programEnter = programEnter;
            this.editState = editState;
        }

        public RoutingAction getAction() {
            return action;
        }

        /** @return {@code true} when the screen renders under {@code CDEMO-PGM-ENTER}. */
        public boolean isProgramEnter() {
            return programEnter;
        }

        public EditWorkState getEditState() {
            return editState;
        }
    }

    // =====================================================================
    // Business paragraphs
    // =====================================================================

    /**
     * {@code 0000-MAIN} &mdash; pseudo-conversational entry point and control-flow
     * state machine ({@code legacy/cbl/COCRDUPC.cbl} lines 380-528).
     *
     * <p>This is the single {@link Transactional} boundary for the transaction.
     * The COBOL {@code EXEC CICS SYNCPOINT} that precedes the exit {@code XCTL}
     * (line 470) corresponds to the commit that occurs when this method returns
     * normally; the read-update-rewrite performed by {@link #writeProcessing} runs
     * inside this same transaction. The annotation is placed here rather than on
     * {@code writeProcessing} because {@code writeProcessing} is reached by internal
     * self-invocation, which a Spring proxy would not intercept; the controller
     * calls {@code mainEntry}, so this is the effective transactional entry point
     * (the "single {@code @Transactional}" the archetype requires).</p>
     *
     * <p>The method reproduces the COBOL {@code EVALUATE TRUE} verbatim:</p>
     * <ol>
     *   <li><b>Exit / done-from-list</b> &rarr; {@link RoutingAction#REDIRECT}.</li>
     *   <li><b>Arrived from the card-list screen</b> &rarr; fetch and show details.</li>
     *   <li><b>Fresh entry</b> &rarr; show the empty search screen.</li>
     *   <li><b>Changes done or failed</b> &rarr; reset for a fresh search.</li>
     *   <li><b>Otherwise</b> &rarr; process inputs and decide the next action.</li>
     * </ol>
     *
     * @param form      the bound screen form ({@code CCRDUPA} input fields)
     * @param workArea  a fresh per-request {@link CardWorkArea} carrying the AID
     * @param state     the session-persisted {@link CardUpdateState}
     * @return the {@link CardUpdateResult} instructing the controller how to route
     */
    @Transactional
    public CardUpdateResult mainEntry(COCRDUPForm form, CardWorkArea workArea, CardUpdateState state) {
        final EditWorkState edit = new EditWorkState();

        // ---- Store passed data / detect first entry (COBOL lines 388-401). ----
        // COBOL: IF EIBCALEN = 0 OR (FROM = MENU AND NOT REENTER) -> INITIALIZE +
        // SET PGM-ENTER + SET DETAILS-NOT-FETCHED; ELSE the commarea is restored
        // (here the context and state are live session objects, so nothing to do).
        final boolean firstEntry = context.isNew()
                || (MENU_PGM.equals(context.getFromProgram()) && !context.isProgramReenter());
        if (firstEntry) {
            // Deviation (documented in the decision log): the shared
            // CardDemoContext navigation/selection fields are intentionally not
            // wiped here. The session bean owns their lifecycle and the state
            // machine converges on the same fresh-entry block (branch 3) whether
            // this is a true first entry or a hand-off from the menu; only the
            // program-private state is (re)initialized.
            state.initialize();
            context.markEnter();
            context.markInitialized();
        }

        // ---- Resolve and validate the AID (COBOL lines 406-424). ----
        PfKey aid = workArea.getPfKey();
        final boolean pfkValid = aid == PfKey.ENTER
                || aid == PfKey.PFK03
                || (aid == PfKey.PFK05 && state.isChangesOkNotConfirmed())
                || (aid == PfKey.PFK12 && !state.isDetailsNotFetched());
        if (!pfkValid) {
            aid = PfKey.ENTER;
        }

        // ---- Main EVALUATE TRUE (COBOL lines 429-527). ----

        // Branch 1: PF03 exit, or update completed/failed while on the card-list
        // mapset -> transfer control back (COBOL lines 435-476).
        final boolean onCardListMapset = CCLIST_MAPSET.equals(context.getLastMapset());
        if (aid == PfKey.PFK03
                || (state.isChangesOkayedAndDone() && onCardListMapset)
                || (state.isChangesFailed() && onCardListMapset)) {
            if (isBlankOrLowValues(context.getFromTranid())) {
                context.setToTranid(MENU_TRANID);
            } else {
                context.setToTranid(context.getFromTranid());
            }
            if (isBlankOrLowValues(context.getFromProgram())) {
                context.setToProgram(MENU_PGM);
            } else {
                context.setToProgram(context.getFromProgram());
            }
            context.setFromTranid(THIS_TRANID);
            context.setFromProgram(THIS_PGM);
            if (onCardListMapset) {
                context.setAcctId(0L);
                context.setCardNum(ZEROS_CARD);
            }
            context.setUser();
            context.markEnter();
            context.setLastMapset(THIS_MAPSET);
            context.setLastMap(THIS_MAP);
            // EXEC CICS SYNCPOINT (line 470): committed when this @Transactional
            // method returns; the controller then performs the XCTL redirect.
            return new CardUpdateResult(RoutingAction.REDIRECT, false, edit);
        }

        // Branch 2: arrived from the card-list program (COCRDLIC) with the filter
        // keys already selected -> fetch and display for update (lines 482-497).
        if ((context.isProgramEnter() && CCLIST_PGM.equals(context.getFromProgram()))
                || (aid == PfKey.PFK12 && CCLIST_PGM.equals(context.getFromProgram()))) {
            context.markReenter();
            edit.inputError = false;
            edit.acctFilter = EditFlag.ISVALID;
            edit.cardFilter = EditFlag.ISVALID;
            workArea.setAcctId(formatAccount(context.getAcctId()));
            workArea.setCardNum(context.getCardNum());
            readData(workArea, state, edit);
            state.setChangeAction(ChangeAction.SHOW_DETAILS);
            return new CardUpdateResult(RoutingAction.SHOW_SCREEN, context.isProgramEnter(), edit);
        }

        // Branch 3: fresh entry -> ask for the search keys (lines 502-511).
        if ((state.isDetailsNotFetched() && context.isProgramEnter())
                || (MENU_PGM.equals(context.getFromProgram()) && !context.isProgramReenter())) {
            state.initialize();
            final boolean renderEnter = context.isProgramEnter();
            context.markReenter();
            state.setChangeAction(ChangeAction.DETAILS_NOT_FETCHED);
            return new CardUpdateResult(RoutingAction.SHOW_SCREEN, renderEnter, edit);
        }

        // Branch 4: changes reviewed and completed (or failed) not from the list
        // screen -> reset the search keys and ask afresh (lines 517-527).
        if (state.isChangesOkayedAndDone() || state.isChangesFailed()) {
            state.initialize();
            context.setAcctId(0L);
            context.setCardNum(ZEROS_CARD);
            context.markEnter();
            final boolean renderEnter = context.isProgramEnter();
            context.markReenter();
            state.setChangeAction(ChangeAction.DETAILS_NOT_FETCHED);
            return new CardUpdateResult(RoutingAction.SHOW_SCREEN, renderEnter, edit);
        }

        // Branch 5 (WHEN OTHER): normal input processing (lines 530-...).
        processInputs(form, workArea, state, edit);
        decideAction(workArea, state, edit, aid);
        return new CardUpdateResult(RoutingAction.SHOW_SCREEN, context.isProgramEnter(), edit);
    }

    /**
     * {@code 1000-PROCESS-INPUTS} plus the field-normalization half of
     * {@code 1100-RECEIVE-MAP} ({@code legacy/cbl/COCRDUPC.cbl} lines 564-635).
     *
     * <p>The raw {@code EXEC CICS RECEIVE} is performed by the controller (form
     * binding); this method reproduces the business normalization that follows it:
     * {@code INITIALIZE CCUP-NEW-DETAILS}, then for each field a value of {@code '*'}
     * or spaces becomes COBOL {@code LOW-VALUES} (modelled as {@code null}), and any
     * other value is taken as entered. {@code EXPDAY} is copied unconditionally,
     * exactly as in the source (line 621). Finally the field edits run and the
     * resulting error message / next-map fields are stored on the work area for the
     * controller (lines 569-572).</p>
     *
     * @param form      the bound screen form
     * @param workArea  the per-request work area
     * @param state     the pseudo-conversational state
     * @param edit      the per-request edit working storage
     */
    private void processInputs(COCRDUPForm form, CardWorkArea workArea,
                               CardUpdateState state, EditWorkState edit) {
        state.initializeNewDetails();

        final String acct = normalizeStar(form.getAcctsid());
        workArea.setAcctId(acct);
        state.setNewAcctId(acct);

        final String card = normalizeStar(form.getCardsid());
        workArea.setCardNum(card);
        state.setNewCardId(card);

        state.setNewCrdName(normalizeStar(form.getCrdname()));
        state.setNewCrdStcd(normalizeStar(form.getCrdstcd()));

        // EXPDAY carries no '*'/SPACES sentinel test in the source (line 621).
        state.setNewExpDay(form.getExpday());

        state.setNewExpMon(normalizeStar(form.getExpmon()));
        state.setNewExpYear(normalizeStar(form.getExpyear()));

        editMapInputs(workArea, state, edit);

        workArea.setErrorMsg(edit.getReturnMessage());
        workArea.setNextProg(THIS_PGM);
        workArea.setNextMapset(THIS_MAPSET);
        workArea.setNextMap(THIS_MAP);
    }

    /**
     * {@code 1200-EDIT-MAP-INPUTS} &mdash; validate the received fields
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 641-714).
     *
     * <p>Two distinct modes, exactly as in the source: when no card has been
     * fetched yet ({@code CCUP-DETAILS-NOT-FETCHED}) only the search keys are
     * validated; otherwise the detail fields are compared against the fetched
     * snapshot and, unless nothing changed or confirmation is already pending, the
     * per-field edits run and the state advances to
     * {@code CCUP-CHANGES-OK-NOT-CONFIRMED}.</p>
     *
     * @param workArea  the per-request work area
     * @param state     the pseudo-conversational state
     * @param edit      the per-request edit working storage
     */
    private void editMapInputs(CardWorkArea workArea, CardUpdateState state, EditWorkState edit) {
        edit.inputError = false;

        if (state.isDetailsNotFetched()) {
            editAccount(workArea, state, edit);
            editCard(workArea, state, edit);
            clearNewCardData(state);
            // Both search keys blank -> 'No input received' (unconditional SET,
            // overriding the per-field blank prompts) (lines 656-659).
            if (edit.acctFilter == EditFlag.BLANK && edit.cardFilter == EditFlag.BLANK) {
                edit.returnMessage = MSG_NO_SEARCH_CRITERIA;
            }
            return;
        }

        // Search keys already validated and card fetched (lines 667-677).
        edit.foundCardsForAccount = true;
        edit.acctFilter = EditFlag.ISVALID;
        edit.cardFilter = EditFlag.ISVALID;
        // Restore the context keys from the fetched snapshot (lines 671-672). The
        // parallel COBOL moves into the CARD-* working fields (lines 673-677) are
        // intentionally omitted: 9200 re-reads the card fresh before rewriting, so
        // those working-field values have no observable effect.
        context.setAcctId(parseAccount(state.getOldAcctId()));
        context.setCardNum(state.getOldCardId());

        // No-changes detection: UPPER(new detail data) = UPPER(old detail data)
        // over the CCUP-*-CARDDATA group (name+expiry+status) (lines 680-683).
        if (upperCardData(state, false).equals(upperCardData(state, true))) {
            edit.noChangesDetected = true;
            edit.returnMessage = MSG_NO_CHANGES_DETECTED;
        }

        // No change, or confirmation already pending, or just completed: mark the
        // detail edits valid and skip re-editing (lines 685-693).
        if (edit.noChangesDetected
                || state.isChangesOkNotConfirmed()
                || state.isChangesOkayedAndDone()) {
            edit.cardName = EditFlag.ISVALID;
            edit.cardStatus = EditFlag.ISVALID;
            edit.cardExpMon = EditFlag.ISVALID;
            edit.cardExpYear = EditFlag.ISVALID;
            return;
        }

        // Otherwise validate every detail field (lines 696-714).
        state.setChangeAction(ChangeAction.CHANGES_NOT_OK);
        editName(state, edit);
        editCardStatus(state, edit);
        editExpiryMonth(state, edit);
        editExpiryYear(state, edit);
        if (!edit.inputError) {
            state.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        }
    }

    /**
     * {@code 1210-EDIT-ACCOUNT} &mdash; validate the 11-digit account filter
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 721-755).
     *
     * <p>Reproduces {@code IS NUMERIC} on the fixed {@code PIC X(11)} field: the
     * value must be exactly eleven digit characters, otherwise it is treated as
     * non-numeric ("must be a 11 digit number"). A missing value or a numeric zero
     * is treated as "not supplied".</p>
     *
     * @param workArea  the per-request work area (holds {@code CC-ACCT-ID})
     * @param state     the pseudo-conversational state
     * @param edit      the per-request edit working storage
     */
    private void editAccount(CardWorkArea workArea, CardUpdateState state, EditWorkState edit) {
        edit.acctFilter = EditFlag.NOT_OK;
        final String value = workArea.getAcctId();
        final Long numeric = workArea.getAcctIdNumeric();
        if (value == null || value.isBlank() || (numeric != null && numeric == 0L)) {
            edit.inputError = true;
            edit.acctFilter = EditFlag.BLANK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_PROMPT_FOR_ACCT;
            }
            context.setAcctId(0L);
            state.setNewAcctId(null);
            return;
        }
        if (!isDigits(value, 11)) {
            edit.inputError = true;
            edit.acctFilter = EditFlag.NOT_OK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_ACCT_MUST_BE_11_DIGITS;
            }
            context.setAcctId(0L);
            state.setNewAcctId(null);
            return;
        }
        context.setAcctId(Long.valueOf(value.trim()));
        state.setNewAcctId(value);
        edit.acctFilter = EditFlag.ISVALID;
    }

    /**
     * {@code 1220-EDIT-CARD} &mdash; validate the 16-digit card filter
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 762-799). Mirrors {@link #editAccount}
     * with a 16-digit width and the card-specific prompts.
     *
     * @param workArea  the per-request work area (holds {@code CC-CARD-NUM})
     * @param state     the pseudo-conversational state
     * @param edit      the per-request edit working storage
     */
    private void editCard(CardWorkArea workArea, CardUpdateState state, EditWorkState edit) {
        edit.cardFilter = EditFlag.NOT_OK;
        final String value = workArea.getCardNum();
        final Long numeric = workArea.getCardNumNumeric();
        if (value == null || value.isBlank() || (numeric != null && numeric == 0L)) {
            edit.inputError = true;
            edit.cardFilter = EditFlag.BLANK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_PROMPT_FOR_CARD;
            }
            context.setCardNum(ZEROS_CARD);
            state.setNewCardId(null);
            return;
        }
        if (!isDigits(value, 16)) {
            edit.inputError = true;
            edit.cardFilter = EditFlag.NOT_OK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_CARD_MUST_BE_16_DIGITS;
            }
            context.setCardNum(ZEROS_CARD);
            state.setNewCardId(null);
            return;
        }
        context.setCardNum(value);
        state.setNewCardId(value);
        edit.cardFilter = EditFlag.ISVALID;
    }

    /**
     * {@code 1230-EDIT-NAME} &mdash; validate the embossed name
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 806-839).
     *
     * <p>The COBOL {@code INSPECT ... CONVERTING} maps every alphabetic character
     * to a space and then checks that only spaces remain; equivalently, the name is
     * valid when every character is an ASCII letter or a space. A missing name is
     * rejected with the "not provided" prompt.</p>
     *
     * @param state  the pseudo-conversational state (holds {@code CCUP-NEW-CRDNAME})
     * @param edit   the per-request edit working storage
     */
    private void editName(CardUpdateState state, EditWorkState edit) {
        edit.cardName = EditFlag.NOT_OK;
        final String value = state.getNewCrdName();
        if (value == null || value.isBlank() || isAllZeros(value)) {
            edit.inputError = true;
            edit.cardName = EditFlag.BLANK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_PROMPT_FOR_NAME;
            }
            return;
        }
        if (isAlphaSpaces(value)) {
            edit.cardName = EditFlag.ISVALID;
        } else {
            edit.inputError = true;
            edit.cardName = EditFlag.NOT_OK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_NAME_MUST_BE_ALPHA;
            }
        }
    }

    /**
     * {@code 1240-EDIT-CARDSTATUS} &mdash; validate the active-status flag
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 845-872). Must be a case-sensitive
     * {@code 'Y'} or {@code 'N'} ({@code FLG-YES-NO-VALID VALUES 'Y' 'N'}).
     *
     * @param state  the pseudo-conversational state (holds {@code CCUP-NEW-CRDSTCD})
     * @param edit   the per-request edit working storage
     */
    private void editCardStatus(CardUpdateState state, EditWorkState edit) {
        edit.cardStatus = EditFlag.NOT_OK;
        final String value = state.getNewCrdStcd();
        if (value == null || value.isBlank() || isAllZeros(value)) {
            edit.inputError = true;
            edit.cardStatus = EditFlag.BLANK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_STATUS_MUST_BE_YES_NO;
            }
            return;
        }
        if ("Y".equals(value) || "N".equals(value)) {
            edit.cardStatus = EditFlag.ISVALID;
        } else {
            edit.inputError = true;
            edit.cardStatus = EditFlag.NOT_OK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_STATUS_MUST_BE_YES_NO;
            }
        }
    }

    /**
     * {@code 1250-EDIT-EXPIRY-MON} &mdash; validate the expiry month
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 877-907). Must be numeric in the
     * range 1&ndash;12 ({@code CARD-MONTH-CHECK-N 88 VALID-MONTH 1 THRU 12}).
     *
     * @param state  the pseudo-conversational state (holds {@code CCUP-NEW-EXPMON})
     * @param edit   the per-request edit working storage
     */
    private void editExpiryMonth(CardUpdateState state, EditWorkState edit) {
        edit.cardExpMon = EditFlag.NOT_OK;
        final String value = state.getNewExpMon();
        if (value == null || value.isBlank() || isAllZeros(value)) {
            edit.inputError = true;
            edit.cardExpMon = EditFlag.BLANK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_EXPIRY_MONTH_NOT_VALID;
            }
            return;
        }
        final Integer month = parseIntOrNull(value);
        if (month != null && month >= 1 && month <= 12) {
            edit.cardExpMon = EditFlag.ISVALID;
        } else {
            edit.inputError = true;
            edit.cardExpMon = EditFlag.NOT_OK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_EXPIRY_MONTH_NOT_VALID;
            }
        }
    }

    /**
     * {@code 1260-EDIT-EXPIRY-YEAR} &mdash; validate the expiry year
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 913-943). Must be numeric in the
     * range 1950&ndash;2099 ({@code CARD-YEAR-CHECK-N 88 VALID-YEAR 1950 THRU 2099}).
     * The source performs the not-supplied test before defaulting the flag to
     * {@code NOT-OK}, an ordering preserved here.
     *
     * @param state  the pseudo-conversational state (holds {@code CCUP-NEW-EXPYEAR})
     * @param edit   the per-request edit working storage
     */
    private void editExpiryYear(CardUpdateState state, EditWorkState edit) {
        final String value = state.getNewExpYear();
        if (value == null || value.isBlank() || isAllZeros(value)) {
            edit.inputError = true;
            edit.cardExpYear = EditFlag.BLANK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_EXPIRY_YEAR_NOT_VALID;
            }
            return;
        }
        edit.cardExpYear = EditFlag.NOT_OK;
        final Integer year = parseIntOrNull(value);
        if (year != null && year >= 1950 && year <= 2099) {
            edit.cardExpYear = EditFlag.ISVALID;
        } else {
            edit.inputError = true;
            edit.cardExpYear = EditFlag.NOT_OK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_EXPIRY_YEAR_NOT_VALID;
            }
        }
    }

    /**
     * {@code 2000-DECIDE-ACTION} &mdash; the pseudo-conversational action state
     * machine ({@code legacy/cbl/COCRDUPC.cbl} lines 948-1027).
     *
     * <p>Preserves the COBOL {@code EVALUATE TRUE} exactly: the branch guards are
     * evaluated top-to-bottom and only the first matching branch runs (Java
     * {@code else if} chain, never fall-through). The two leading COBOL {@code WHEN}
     * clauses ({@code CCUP-DETAILS-NOT-FETCHED} and {@code CCARD-AID-PFK12}) share a
     * single body and are combined into one guard here (lines 954-966).</p>
     *
     * <p>When a confirmed save is requested ({@code CCUP-CHANGES-OK-NOT-CONFIRMED}
     * and PF5), {@link #writeProcessing} runs and its post-write signals drive the
     * nested {@code EVALUATE} (lines 992-1001) that selects the resulting action
     * state. Any state not matched by an earlier branch is a logic defect and
     * triggers {@link #abendRoutine} with code {@code '0001'} exactly as the COBOL
     * {@code WHEN OTHER} does (lines 1019-1026).</p>
     *
     * @param workArea  the per-request work area
     * @param state     the pseudo-conversational state
     * @param edit      the per-request edit working storage
     * @param aid       the resolved (and already validated) attention identifier
     */
    private void decideAction(CardWorkArea workArea, CardUpdateState state,
                              EditWorkState edit, PfKey aid) {
        if (state.isDetailsNotFetched() || aid == PfKey.PFK12) {
            // Lines 954-966: no details yet, or user cancelled a pending change.
            // Re-fetch when both search keys are valid, then show the detail screen.
            if (edit.acctFilter == EditFlag.ISVALID && edit.cardFilter == EditFlag.ISVALID) {
                readData(workArea, state, edit);
                if (edit.foundCardsForAccount) {
                    state.setChangeAction(ChangeAction.SHOW_DETAILS);
                }
            }
        } else if (state.isShowDetails()) {
            // Lines 971-977: details shown; ask for confirmation only when the
            // edits were clean and at least one field actually changed.
            if (edit.inputError || edit.noChangesDetected) {
                // CONTINUE (line 974): remain on the detail screen.
                return;
            }
            state.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        } else if (state.isChangesNotOk()) {
            // Lines 982-983: edit errors present; CONTINUE (re-display with errors).
            return;
        } else if (state.isChangesOkNotConfirmed() && aid == PfKey.PFK05) {
            // Lines 988-1001: confirmation given (PF5) -> attempt the rewrite, then
            // translate the post-write signals into the next action state.
            writeProcessing(workArea, state, edit);
            if (edit.couldNotLockForUpdate) {
                state.setChangeAction(ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
            } else if (edit.lockedButUpdateFailed) {
                state.setChangeAction(ChangeAction.CHANGES_OKAYED_BUT_FAILED);
            } else if (edit.dataWasChangedBeforeUpdate) {
                state.setChangeAction(ChangeAction.SHOW_DETAILS);
            } else {
                state.setChangeAction(ChangeAction.CHANGES_OKAYED_AND_DONE);
            }
        } else if (state.isChangesOkNotConfirmed()) {
            // Lines 1006-1007: confirmation pending but not given; CONTINUE.
            return;
        } else if (state.isChangesOkayedAndDone()) {
            // Lines 1011-1018: save completed; keep the detail screen up and, when
            // this program was not entered from another transaction, clear the
            // selected account/card so a fresh search starts next time.
            state.setChangeAction(ChangeAction.SHOW_DETAILS);
            if (isBlankOrLowValues(context.getFromTranid())) {
                context.setAcctId(0L);
                context.setCardNum(ZEROS_CARD);
                context.setAcctStatus(null);
            }
        } else {
            // Lines 1019-1026: WHEN OTHER -> unexpected state, abend '0001'.
            abendRoutine("0001", "UNEXPECTED DATA SCENARIO");
        }
    }

    /**
     * {@code 9000-READ-DATA} &mdash; fetch the card and snapshot its fields
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 1343-1370).
     *
     * <p>Clears the old snapshot, records the requested account/card keys, then
     * reads the card via {@link #getCardByAccountCard(String)}. On a successful
     * read the fetched field values are captured into the {@code CCUP-OLD-*}
     * snapshot &mdash; the baseline the optimistic-lock check ({@code 9300}) later
     * compares against. The embossed name is upper-cased on capture, exactly as the
     * COBOL {@code INSPECT ... CONVERTING} does (lines 1356-1360).</p>
     *
     * <p>A {@code NOTFND} response is modelled as a caught
     * {@link RecordNotFoundException} that yields the soft in-screen "not found"
     * error (lines 1395-1401): {@code INPUT-ERROR} plus both filters
     * {@code NOT-OK}; {@code FOUND-CARDS-FOR-ACCOUNT} stays false. Any other data
     * access failure is allowed to propagate to the global handler, consistent with
     * the AAP exception-translation design (AAP section 0.6.5).</p>
     *
     * @param workArea  the per-request work area (holds the validated keys)
     * @param state     the pseudo-conversational state (receives the snapshot)
     * @param edit      the per-request edit working storage
     */
    private void readData(CardWorkArea workArea, CardUpdateState state, EditWorkState edit) {
        // INITIALIZE CCUP-OLD-DETAILS then seed the requested keys (lines 1345-1347).
        state.initializeOldDetails();
        state.setOldAcctId(workArea.getAcctId());
        state.setOldCardId(workArea.getCardNum());

        final Card card;
        try {
            card = getCardByAccountCard(workArea.getCardNum());
        } catch (RecordNotFoundException notFound) {
            // DFHRESP(NOTFND) (lines 1395-1401): soft in-screen error.
            edit.inputError = true;
            edit.acctFilter = EditFlag.NOT_OK;
            edit.cardFilter = EditFlag.NOT_OK;
            if (edit.isReturnMsgOff()) {
                edit.returnMessage = MSG_DID_NOT_FIND_ACCTCARD;
            }
            return;
        }

        // DFHRESP(NORMAL): card found (line 1394) -> capture the baseline snapshot.
        edit.foundCardsForAccount = true;
        state.setOldCvvCd(formatCvv(card.getCardCvvCd()));
        state.setOldCrdName(upperName(card.getCardEmbossedName()));
        state.setOldExpYear(expiryYear(card.getCardExpiraionDate()));
        state.setOldExpMon(expiryMonth(card.getCardExpiraionDate()));
        state.setOldExpDay(expiryDay(card.getCardExpiraionDate()));
        state.setOldCrdStcd(card.getCardActiveStatus());
    }

    /**
     * {@code 9100-GETCARD-BYACCTCARD} &mdash; read the card by its number
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 1376-1413).
     *
     * <p>The COBOL {@code EXEC CICS READ FILE(CARDDAT) RIDFLD(WS-CARD-RID-CARDNUM)}
     * becomes {@code CardRepository.findById(cardNum)} keyed on the card number
     * (the VSAM primary key). A {@code NOTFND} response (empty {@link Optional}) is
     * surfaced as {@link RecordNotFoundException}; the caller ({@link #readData})
     * translates it into the soft "not found" screen state. The message is
     * deliberately generic so the full card number (PAN) is never echoed.</p>
     *
     * @param cardNum  the 16-digit card number to read
     * @return the located {@link Card}
     * @throws RecordNotFoundException when no card exists for {@code cardNum}
     */
    private Card getCardByAccountCard(String cardNum) {
        return cardRepository.findById(cardNum)
                .orElseThrow(() -> new RecordNotFoundException(
                        "Card record not found for the supplied filter"));
    }

    /**
     * {@code 9200-WRITE-PROCESSING} &mdash; the read-lock / optimistic-check /
     * rewrite path ({@code legacy/cbl/COCRDUPC.cbl} lines 1420-1493).
     *
     * <p>This method is <em>not</em> annotated {@code @Transactional}; the
     * transaction is opened by {@link #mainEntry} (the controller-invoked boundary),
     * so an internal call here participates in that same unit of work rather than a
     * self-invoked, therefore ignored, proxy (AAP section 0.3.3). This preserves the
     * COBOL "single logical unit of work per turn" archetype.</p>
     *
     * <p>Steps, one-for-one with the source:</p>
     * <ol>
     *   <li>Re-read the card for update (lines 1427-1436). An empty result models a
     *       non-{@code NORMAL} lock response: {@code INPUT-ERROR} plus
     *       {@code COULD-NOT-LOCK-FOR-UPDATE}, then exit (lines 1441-1449).</li>
     *   <li>Run {@link #checkChangeInRec} (line 1453); if the record changed since
     *       the fetch, exit without writing so the screen re-displays (lines
     *       1455-1457).</li>
     *   <li>Build the updated record and {@code saveAndFlush} it &mdash; the
     *       {@code REWRITE} equivalent (lines 1461-1483). A failed flush (or an
     *       impossible expiry date) is the {@code LOCKED-BUT-UPDATE-FAILED}
     *       soft-failure signal (lines 1488-1492).</li>
     * </ol>
     *
     * <p><b>Preserved source quirks.</b> The written CVV always coerces to
     * {@code 0}: the screen carries no CVV field, so {@code CCUP-NEW-CVV-CD} is the
     * INITIALIZEd spaces which the COBOL numeric redefine
     * ({@code CARD-CVV-CD-X}/{@code CARD-CVV-CD-N}) turns into zero (lines
     * 1464-1465). The card is reassigned to the <em>entered</em> account filter
     * ({@code CC-ACCT-ID-N}), not the card's current account (line 1463). Both
     * behaviours are reproduced verbatim.</p>
     *
     * @param workArea  the per-request work area (holds the entered account/card)
     * @param state     the pseudo-conversational state (new inputs + old snapshot)
     * @param edit      the per-request edit working storage (receives write signals)
     */
    private void writeProcessing(CardWorkArea workArea, CardUpdateState state, EditWorkState edit) {
        // Re-read for update (lines 1425-1436). Empty == non-NORMAL lock response.
        final Optional<Card> locked = cardRepository.findById(workArea.getCardNum());
        if (locked.isEmpty()) {
            // Lines 1441-1449: could not lock -> input error, exit.
            edit.inputError = true;
            if (edit.isReturnMsgOff()) {
                edit.couldNotLockForUpdate = true;
                edit.returnMessage = MSG_COULD_NOT_LOCK;
            }
            return;
        }

        final Card current = locked.get();

        // Optimistic-lock check against the fetch-time snapshot (lines 1453-1457).
        checkChangeInRec(current, state, edit);
        if (edit.dataWasChangedBeforeUpdate) {
            return;
        }

        // Prepare and apply the update (lines 1461-1483).
        try {
            // CARD-UPDATE-NUM = CCUP-NEW-CARDID (line 1462).
            current.setCardNum(state.getNewCardId());
            // CARD-UPDATE-ACCT-ID = CC-ACCT-ID-N: reassign to entered account (line 1463).
            current.setCardAcctId(workArea.getAcctIdNumeric());
            // CVV coercion quirk: spaces -> 0 (lines 1464-1465).
            current.setCardCvvCd(parseCvv(state.getNewCvvCd()));
            // CARD-UPDATE-EMBOSSED-NAME = CCUP-NEW-CRDNAME (line 1466).
            current.setCardEmbossedName(state.getNewCrdName());
            // STRING year '-' month '-' day -> yyyy-mm-dd (lines 1467-1474).
            current.setCardExpiraionDate(
                    buildExpiry(state.getNewExpYear(), state.getNewExpMon(), state.getNewExpDay()));
            // CARD-UPDATE-ACTIVE-STATUS = CCUP-NEW-CRDSTCD (line 1475).
            current.setCardActiveStatus(state.getNewCrdStcd());

            cardRepository.saveAndFlush(current);
        } catch (DataAccessException | DateTimeException updateFailed) {
            // Lines 1488-1492: REWRITE failed (or an impossible date was assembled).
            edit.lockedButUpdateFailed = true;
            edit.returnMessage = MSG_UPDATE_FAILED;
        }
    }

    /**
     * {@code 9300-CHECK-CHANGE-IN-REC} &mdash; optimistic-lock verification
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 1498-1519).
     *
     * <p>Compares the just-locked (re-read) card against the {@code CCUP-OLD-*}
     * snapshot captured at fetch time. The embossed name is upper-cased before
     * comparison (the COBOL {@code INSPECT ... CONVERTING}, lines 1499-1501), and
     * the CVV, embossed name, expiry year/month/day, and active status are compared
     * field-by-field &mdash; the explicit compare reproduces the exact COBOL
     * condition (lines 1503-1508). When every field matches, processing continues;
     * otherwise the record changed underneath us: set
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, refresh the snapshot from the current
     * record, and abandon the write (lines 1510-1518). A concurrent change is a
     * soft re-display, never a hard error.</p>
     *
     * @param current  the freshly re-read card
     * @param state    the pseudo-conversational state (holds and refreshes the snapshot)
     * @param edit     the per-request edit working storage (receives the change signal)
     */
    private void checkChangeInRec(Card current, CardUpdateState state, EditWorkState edit) {
        // INSPECT CARD-EMBOSSED-NAME CONVERTING lower -> upper (lines 1499-1501).
        final String currentName = upperName(current.getCardEmbossedName());
        final String currentCvv = formatCvv(current.getCardCvvCd());
        final String currentYear = expiryYear(current.getCardExpiraionDate());
        final String currentMon = expiryMonth(current.getCardExpiraionDate());
        final String currentDay = expiryDay(current.getCardExpiraionDate());
        final String currentStatus = current.getCardActiveStatus();

        final boolean unchanged =
                nullSafeEquals(currentCvv, state.getOldCvvCd())
                        && nullSafeEquals(currentName, state.getOldCrdName())
                        && nullSafeEquals(currentYear, state.getOldExpYear())
                        && nullSafeEquals(currentMon, state.getOldExpMon())
                        && nullSafeEquals(currentDay, state.getOldExpDay())
                        && nullSafeEquals(currentStatus, state.getOldCrdStcd());

        if (unchanged) {
            // CONTINUE (line 1509): safe to proceed with the rewrite.
            return;
        }

        // Lines 1511-1518: record changed; flag it and refresh the snapshot.
        edit.dataWasChangedBeforeUpdate = true;
        edit.returnMessage = MSG_DATA_WAS_CHANGED;
        state.setOldCvvCd(currentCvv);
        state.setOldCrdName(currentName);
        state.setOldExpYear(currentYear);
        state.setOldExpMon(currentMon);
        state.setOldExpDay(currentDay);
        state.setOldCrdStcd(currentStatus);
    }

    /**
     * {@code ABEND-ROUTINE} &mdash; abnormal-termination handler
     * ({@code legacy/cbl/COCRDUPC.cbl}, invoked from the {@code 2000-DECIDE-ACTION}
     * {@code WHEN OTHER} branch, lines 1019-1026).
     *
     * <p>The COBOL sets an abend culprit/code/message and issues {@code EXEC CICS
     * ABEND}, unwinding the transaction. The Java equivalent throws
     * {@link LogicError} (FILE STATUS {@code 92}, logic error), which rolls back the
     * surrounding {@link #mainEntry} transaction and is surfaced by the global
     * handler. This path indicates an unreachable action state and should never
     * occur in normal operation.</p>
     *
     * @param abendCode  the COBOL {@code ABEND-CODE} (for example {@code '0001'})
     * @param abendMsg   the COBOL {@code ABEND-MSG} describing the scenario
     */
    private void abendRoutine(String abendCode, String abendMsg) {
        throw new LogicError(THIS_PGM + " abend " + abendCode + ": " + abendMsg);
    }

    // =====================================================================
    // Private helpers
    //
    // Small, pure translations of the COBOL figurative-constant tests,
    // fixed-width MOVE semantics, and numeric redefinitions. Kept static and
    // side-effect free so they are trivially unit-testable and carry no state.
    // =====================================================================

    /**
     * COBOL "{@code = LOW-VALUES OR = SPACES}" test for an alphanumeric field.
     * A COBOL {@code LOW-VALUES} field is modelled as {@code null}; {@code SPACES}
     * as a blank string.
     *
     * @param value  the value to test
     * @return {@code true} when the value is absent (null) or entirely blank
     */
    private static boolean isBlankOrLowValues(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Formats a numeric account id into its eleven-character display form,
     * mirroring the COBOL {@code CC-ACCT-ID PIC X(11)} / {@code CC-ACCT-ID-N PIC
     * 9(11)} pairing (zero-filled on the left). A {@code null} id is treated as
     * zero, matching {@code MOVE ZEROES}.
     *
     * @param value  the numeric account id (may be {@code null})
     * @return the eleven-digit zero-padded display string
     */
    private static String formatAccount(Long value) {
        return String.format("%011d", value == null ? 0L : value);
    }

    /**
     * Reproduces the {@code 1100-RECEIVE-MAP} per-field normalization
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 585-620): a field equal to the
     * placeholder {@code '*'} (padded with trailing spaces) or to {@code SPACES}
     * becomes COBOL {@code LOW-VALUES} (modelled as {@code null}); any other value
     * is taken verbatim, preserving its fixed-width content for the edits.
     *
     * @param raw  the raw received field value
     * @return the entered value, or {@code null} for the star / blank sentinels
     */
    private static String normalizeStar(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if ("*".equals(raw.stripTrailing())) {
            return null;
        }
        return raw;
    }

    /**
     * COBOL {@code IS NUMERIC} test over a fixed {@code PIC X(width)} field: the
     * value must be present and consist of exactly {@code width} ASCII digits.
     * A short or space-padded value therefore fails, exactly as an unfilled 3270
     * field fails the mainframe numeric edit.
     *
     * @param value  the value to test
     * @param width  the required exact number of digit characters
     * @return {@code true} when the value is exactly {@code width} digits
     */
    private static boolean isDigits(String value, int width) {
        if (value == null || value.length() != width) {
            return false;
        }
        for (int i = 0; i < width; i++) {
            final char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests whether an alphanumeric field is non-empty and composed solely of the
     * character {@code '0'} &mdash; the {@code ZEROS} arm of the COBOL blank test on
     * the fixed-width detail fields (status, month, year).
     *
     * @param value  the value to test
     * @return {@code true} when the value is non-empty and every character is {@code '0'}
     */
    private static boolean isAllZeros(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reproduces the {@code 1230-EDIT-NAME} alphabetic check
     * ({@code legacy/cbl/COCRDUPC.cbl} lines 817-833): the COBOL {@code INSPECT ...
     * CONVERTING} maps every letter to a space and then requires only spaces to
     * remain, so the name is valid exactly when every character is an ASCII letter
     * or a space.
     *
     * @param value  the embossed name to test
     * @return {@code true} when the name contains only letters and spaces
     */
    private static boolean isAlphaSpaces(String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            final boolean alpha = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!alpha && c != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses an all-digit token into an {@link Integer}, returning {@code null} for
     * any absent, blank, or non-numeric value. Used by the month/year range edits
     * ({@code 1250}/{@code 1260}) whose COBOL {@code VALID-MONTH}/{@code VALID-YEAR}
     * 88-levels are range tests over a numeric field.
     *
     * @param value  the value to parse
     * @return the parsed integer, or {@code null} when not a plain integer
     */
    private static Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        final String token = value.trim();
        if (token.isEmpty()) {
            return null;
        }
        for (int i = 0; i < token.length(); i++) {
            final char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Integer.valueOf(token);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Parses a stored account display string into its numeric form for the session
     * context ({@code MOVE CCUP-OLD-ACCTID TO CDEMO-ACCT-ID}). A blank or
     * non-numeric value yields zero, matching the COBOL {@code 9(11)} redefinition.
     *
     * @param value  the account display string
     * @return the numeric account id, or {@code 0} when blank/non-numeric
     */
    private static Long parseAccount(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * Formats the numeric CVV into its three-character display form
     * ({@code CARD-CVV-CD PIC 9(03)} moved to a {@code PIC X(3)} snapshot), left
     * zero-filled. A {@code null} CVV formats as {@code "000"}.
     *
     * @param cvv  the numeric CVV (may be {@code null})
     * @return the three-digit zero-padded CVV string
     */
    private static String formatCvv(Integer cvv) {
        return String.format("%03d", cvv == null ? 0 : cvv);
    }

    /**
     * Parses the (never-populated) new CVV string into a number, reproducing the
     * COBOL numeric-redefine coercion where the INITIALIZEd spaces of
     * {@code CCUP-NEW-CVV-CD} become {@code 0} when written to the record. Any
     * absent, blank, or non-numeric value therefore yields {@code 0}.
     *
     * @param value  the new CVV string (in practice always {@code null}/blank)
     * @return the numeric CVV, always {@code 0} for the update path
     */
    private static Integer parseCvv(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Upper-cases an embossed name, reproducing the COBOL {@code INSPECT
     * CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER} applied at fetch
     * ({@code 9000}) and re-verification ({@code 9300}) time. {@code null} is
     * preserved as {@code null}.
     *
     * @param name  the embossed name (may be {@code null})
     * @return the upper-cased name, or {@code null}
     */
    private static String upperName(String name) {
        return name == null ? null : name.toUpperCase(Locale.ROOT);
    }

    /**
     * Extracts the four-character year from an expiry date, mirroring the COBOL
     * {@code CARD-EXPIRAION-DATE(1:4)} substring of the {@code yyyy-mm-dd} layout.
     *
     * @param date  the expiry date (may be {@code null})
     * @return the {@code yyyy} string, or {@code null} when the date is absent
     */
    private static String expiryYear(LocalDate date) {
        return date == null ? null : String.format("%04d", date.getYear());
    }

    /**
     * Extracts the two-character month from an expiry date, mirroring the COBOL
     * {@code CARD-EXPIRAION-DATE(6:2)} substring of the {@code yyyy-mm-dd} layout.
     *
     * @param date  the expiry date (may be {@code null})
     * @return the {@code mm} string, or {@code null} when the date is absent
     */
    private static String expiryMonth(LocalDate date) {
        return date == null ? null : String.format("%02d", date.getMonthValue());
    }

    /**
     * Extracts the two-character day from an expiry date, mirroring the COBOL
     * {@code CARD-EXPIRAION-DATE(9:2)} substring of the {@code yyyy-mm-dd} layout.
     *
     * @param date  the expiry date (may be {@code null})
     * @return the {@code dd} string, or {@code null} when the date is absent
     */
    private static String expiryDay(LocalDate date) {
        return date == null ? null : String.format("%02d", date.getDayOfMonth());
    }

    /**
     * Rebuilds the expiry {@link LocalDate} from the edited year/month/day parts,
     * reproducing the COBOL {@code STRING CCUP-NEW-EXPYEAR '-' CCUP-NEW-EXPMON '-'
     * CCUP-NEW-EXPDAY} assembly of the {@code yyyy-mm-dd} field ({@code 9200} lines
     * 1467-1474). Because the target column is a real {@link LocalDate} (AAP
     * {@code X(10)} date -&gt; {@code LocalDate} mapping), an impossible calendar
     * date (for example the un-edited day 31 with a newly chosen 30-day month)
     * throws {@link DateTimeException}; {@link #writeProcessing} treats that as the
     * {@code LOCKED-BUT-UPDATE-FAILED} soft failure.
     *
     * @param year   the edited four-digit year part
     * @param month  the edited two-digit month part
     * @param day    the (unedited) two-digit day part
     * @return the assembled expiry date
     * @throws DateTimeException when the parts do not form a valid calendar date
     */
    private static LocalDate buildExpiry(String year, String month, String day) {
        return LocalDate.of(requireDatePart(year), requireDatePart(month), requireDatePart(day));
    }

    /**
     * Parses one numeric component of the expiry date, throwing
     * {@link DateTimeException} (rather than an unchecked parse error) for an
     * absent or non-numeric part so {@link #buildExpiry} fails uniformly.
     *
     * @param value  the date component
     * @return the parsed integer value
     * @throws DateTimeException when the component is missing or non-numeric
     */
    private static int requireDatePart(String value) {
        if (value == null) {
            throw new DateTimeException("Missing expiry date component");
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new DateTimeException("Non-numeric expiry date component: " + value, ex);
        }
    }

    /**
     * Builds the upper-cased, fixed-width {@code CCUP-*-CARDDATA} group (embossed
     * name + expiry year/month/day + active status, 59 characters) used by the
     * {@code 1200} no-change detection ({@code legacy/cbl/COCRDUPC.cbl} lines
     * 680-683: {@code FUNCTION UPPER-CASE(new) = FUNCTION UPPER-CASE(old)}). Each
     * component is padded/truncated to its COBOL width and {@code null} is rendered
     * as spaces, so the comparison is byte-for-byte faithful to the group compare.
     *
     * @param state  the pseudo-conversational state holding both snapshots
     * @param old    {@code true} to build the {@code OLD} group, {@code false} for {@code NEW}
     * @return the 59-character upper-cased card-data image
     */
    private static String upperCardData(CardUpdateState state, boolean old) {
        final String name = old ? state.getOldCrdName() : state.getNewCrdName();
        final String year = old ? state.getOldExpYear() : state.getNewExpYear();
        final String month = old ? state.getOldExpMon() : state.getNewExpMon();
        final String day = old ? state.getOldExpDay() : state.getNewExpDay();
        final String status = old ? state.getOldCrdStcd() : state.getNewCrdStcd();
        final StringBuilder image = new StringBuilder(59);
        image.append(fixedWidth(name, 50));
        image.append(fixedWidth(year, 4));
        image.append(fixedWidth(month, 2));
        image.append(fixedWidth(day, 2));
        image.append(fixedWidth(status, 1));
        return image.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * Renders a value as a fixed-width COBOL {@code PIC X(width)} field: a
     * {@code null} becomes all spaces, a shorter value is right-padded with spaces,
     * and a longer value is truncated to {@code width}.
     *
     * @param value  the value to render (may be {@code null})
     * @param width  the fixed field width
     * @return the exactly {@code width}-character representation
     */
    private static String fixedWidth(String value, int width) {
        final String source = value == null ? "" : value;
        if (source.length() >= width) {
            return source.substring(0, width);
        }
        final StringBuilder padded = new StringBuilder(width);
        padded.append(source);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * COBOL {@code MOVE LOW-VALUES TO CCUP-NEW-CARDDATA} ({@code 1200} line 654):
     * clears the editable detail fields (name, expiry parts, status) of the
     * {@code NEW} snapshot without disturbing the received account/card filters.
     *
     * @param state  the pseudo-conversational state to reset
     */
    private static void clearNewCardData(CardUpdateState state) {
        state.setNewCrdName(null);
        state.setNewExpYear(null);
        state.setNewExpMon(null);
        state.setNewExpDay(null);
        state.setNewCrdStcd(null);
    }

    /**
     * Null-safe string equality used by the {@code 9300} optimistic-lock field
     * compare, avoiding a dependency on {@code java.util.Objects} for a single call
     * site.
     *
     * @param left   the first value
     * @param right  the second value
     * @return {@code true} when both are {@code null} or equal
     */
    private static boolean nullSafeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}

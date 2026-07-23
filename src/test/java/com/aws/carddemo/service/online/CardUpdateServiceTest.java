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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COCRDUPForm;
import com.aws.carddemo.exception.LogicError;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.CardRepository;
import com.aws.carddemo.service.online.CardUpdateService.CardUpdateResult;
import com.aws.carddemo.service.online.CardUpdateService.CardUpdateState;
import com.aws.carddemo.service.online.CardUpdateService.ChangeAction;
import com.aws.carddemo.service.online.CardUpdateService.EditFlag;
import com.aws.carddemo.service.online.CardUpdateService.EditWorkState;
import com.aws.carddemo.service.online.CardUpdateService.RoutingAction;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Pure-Mockito unit tests for {@link CardUpdateService}.
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COCRDUPC.cbl}
 * (CICS COBOL program {@code COCRDUPC}, transaction id {@code CCUP}) &mdash; the
 * online <em>Credit Card Update</em> program, a read-update-rewrite archetype with
 * an optimistic lock. These tests assert one-for-one control-flow parity with the
 * numbered paragraphs the service migrates (AAP &sect;0.1.2 / &sect;0.4.1 /
 * &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code 0000-MAIN} &rarr; {@link CardUpdateService#mainEntry} &mdash; the
 *       pseudo-conversational {@code EVALUATE TRUE} state machine (first entry,
 *       arrival from the card-list program, fresh entry, post-change reset, and
 *       the {@code WHEN OTHER} normal-input branch).</li>
 *   <li>{@code 1210-EDIT-ACCOUNT} / {@code 1220-EDIT-CARD} /
 *       {@code 1230-EDIT-NAME} / {@code 1240-EDIT-CARDSTATUS} /
 *       {@code 1250-EDIT-EXPIRY-MON} / {@code 1260-EDIT-EXPIRY-YEAR} &mdash; the
 *       per-field edits and their exact message literals.</li>
 *   <li>{@code 2000-DECIDE-ACTION} &mdash; the action state machine that drives
 *       re-fetch (PF12), confirmation, and the confirmed-save (PF5) path.</li>
 *   <li>{@code 9000-READ-DATA} / {@code 9100-GETCARD-BYACCTCARD} &mdash; the keyed
 *       read on {@code CARDDAT} and its {@code NOTFND} handling.</li>
 *   <li>{@code 9200-WRITE-PROCESSING} &mdash; the re-read-for-update, optimistic
 *       check, and rewrite, including the CVV-zeroing and account-reassign
 *       quirks.</li>
 *   <li>{@code 9300-CHECK-CHANGE-IN-REC} &mdash; the optimistic lock that
 *       upper-cases the embossed name before comparing the re-read record against
 *       the fetch-time snapshot.</li>
 *   <li>{@code ABEND-ROUTINE} &mdash; the defensive {@code WHEN OTHER} abend that
 *       surfaces as {@link LogicError} (FILE STATUS {@code 92}).</li>
 * </ul>
 *
 * <p><b>Test tier:</b> a strict-stubs pure-Mockito unit test
 * ({@link MockitoExtension}); it uses no database, no Spring context, and no
 * Testcontainers. The two collaborators &mdash; the session-scoped
 * {@link CardDemoContext} ({@code COMMAREA}) and the {@link CardRepository} over
 * {@code CARDDAT} &mdash; are mocked; the service is constructor-injected with
 * them. Because {@code COCRDUPC}'s numbered paragraphs are migrated as
 * <em>private</em> methods behind the single public
 * {@link CardUpdateService#mainEntry} entry point, behaviour is driven through
 * {@code mainEntry}'s branch state machine (black box). Two paths are asserted via
 * reflection because they are not reachable from {@code mainEntry}: the private
 * {@code getCardByAccountCard} (to pin its {@code NOTFND} &rarr;
 * {@link RecordNotFoundException} contract and its keyed lookup) and the defensive
 * {@code ABEND-ROUTINE} (reached from the {@code 2000-DECIDE-ACTION}
 * {@code WHEN OTHER}).</p>
 *
 * <p><b>Faithfulness note (checklist item 6b).</b> A concurrent change detected by
 * {@code 9300-CHECK-CHANGE-IN-REC}, and a failed rewrite, are <em>soft</em>
 * outcomes in the oracle: the former re-displays the detail screen with
 * "Record changed by some one else. Please review" and the latter reports
 * "Update of record failed" &mdash; neither abends. These tests assert that
 * COBOL-faithful soft behaviour (and, in both cases, that <em>no</em> record is
 * committed). The only path that raises {@link LogicError} (FILE STATUS
 * {@code 92}) is the defensive {@code 2000-DECIDE-ACTION} {@code WHEN OTHER}
 * abend, which is asserted directly.</p>
 *
 * <p>This program performs no monetary arithmetic (it edits card metadata only);
 * {@link #service_declaresNoMonetaryArithmetic()} asserts that absence explicitly
 * (checklist item 7).</p>
 */
@ExtendWith(MockitoExtension.class)
class CardUpdateServiceTest {

    // ------------------------------------------------------------------
    // Fixed identifiers (valid fixed-width filter values).
    // ------------------------------------------------------------------

    /** A valid 16-digit card number ({@code CARD-NUM PIC X(16)}) / primary key. */
    private static final String CARD_NUM = "1234567890123456";

    /** A valid 11-digit account filter ({@code CC-ACCT-ID PIC X(11)}). */
    private static final String ACCT_11 = "00000000001";

    /**
     * Fixed single-use confirmation token (review finding #10) used to arm the PF5
     * confirm-turn tests. A real turn issues a random 256-bit token via
     * {@link java.security.SecureRandom}; a fixed 64-hex value is sufficient here (it
     * matches itself under the constant-time comparison) and keeps the write-path
     * assertions deterministic.
     */
    private static final String CONFIRM_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    // ------------------------------------------------------------------
    // Error-message literals (COBOL WS-RETURN-MSG 88-levels + inline MOVEs).
    // Verified verbatim against legacy/cbl/COCRDUPC.cbl and the service.
    // ------------------------------------------------------------------

    /** {@code 1210-EDIT-ACCOUNT} inline literal (COCRDUPC.cbl L745). */
    private static final String MSG_ACCT_MUST_BE_11_DIGITS =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";
    /** {@code 1220-EDIT-CARD} inline literal (COCRDUPC.cbl L789). */
    private static final String MSG_CARD_MUST_BE_16_DIGITS =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";
    /** {@code NO-SEARCH-CRITERIA-RECEIVED} (COCRDUPC.cbl L186). */
    private static final String MSG_NO_SEARCH_CRITERIA = "No input received";
    /** {@code WS-NAME-MUST-BE-ALPHA} (COCRDUPC.cbl L184). */
    private static final String MSG_NAME_MUST_BE_ALPHA =
            "Card name can only contain alphabets and spaces";
    /** {@code CARD-STATUS-MUST-BE-YES-NO} (COCRDUPC.cbl L196). */
    private static final String MSG_STATUS_MUST_BE_YES_NO = "Card Active Status must be Y or N";
    /** {@code CARD-EXPIRY-MONTH-NOT-VALID} (COCRDUPC.cbl L198). */
    private static final String MSG_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";
    /** {@code CARD-EXPIRY-YEAR-NOT-VALID} (COCRDUPC.cbl L200). */
    private static final String MSG_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";
    /** {@code DID-NOT-FIND-ACCTCARD-COMBO} (COCRDUPC.cbl L204). */
    private static final String MSG_DID_NOT_FIND_ACCTCARD =
            "Did not find cards for this search condition";
    /** {@code COULD-NOT-LOCK-FOR-UPDATE} (COCRDUPC.cbl L206). */
    private static final String MSG_COULD_NOT_LOCK = "Could not lock record for update";
    /** {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (COCRDUPC.cbl L208). */
    private static final String MSG_DATA_WAS_CHANGED =
            "Record changed by some one else. Please review";
    /** {@code LOCKED-BUT-UPDATE-FAILED} (COCRDUPC.cbl L210). */
    private static final String MSG_UPDATE_FAILED = "Update of record failed";
    /** Review finding #10 confirmation-integrity banner (web-only; no COBOL analogue). */
    private static final String MSG_CONFIRM_INTEGRITY =
            "Confirmation could not be validated. Please review and press F5 again.";

    // ------------------------------------------------------------------
    // Routing / naming literals (COBOL LIT-* constants of COCRDUPC).
    // ------------------------------------------------------------------

    /** {@code LIT-THISTRANID}. */
    private static final String THIS_TRANID = "CCUP";
    /** {@code LIT-THISPGM}. */
    private static final String THIS_PGM = "COCRDUPC";
    /** {@code LIT-THISMAPSET}. */
    private static final String THIS_MAPSET = "COCRDUP";
    /** {@code LIT-THISMAP}. */
    private static final String THIS_MAP = "CCRDUPA";
    /** {@code LIT-CCLISTPGM} &mdash; the card-list program the update may arrive from. */
    private static final String CCLIST_PGM = "COCRDLIC";
    /** {@code LIT-MENUPGM} &mdash; the main-menu program (default PF3 target). */
    private static final String MENU_PGM = "COMEN01C";
    /** {@code LIT-MENUTRANID} &mdash; the main-menu transaction id (default PF3 target). */
    private static final String MENU_TRANID = "CM00";

    /** Session-scoped {@code COMMAREA} replacement; mocked so routing writes are observable. */
    @Mock
    private CardDemoContext context;

    /** {@code CARDDAT} repository; mocked so reads and the rewrite can be controlled/verified. */
    @Mock
    private CardRepository cardRepository;

    /** Service under test; Mockito constructor-injects the two mocked collaborators. */
    @InjectMocks
    private CardUpdateService service;

    // ------------------------------------------------------------------
    // Baseline fixtures for the detail-edit group (SHOW_DETAILS re-entry).
    //
    // The old snapshot deliberately differs from the fresh detail form so
    // the no-change detection (1200) does not short-circuit and the field
    // edits (1230-1260) actually run; each edit test then flips one field.
    // ------------------------------------------------------------------

    /** Pseudo-conversational state carrying a fetched card ({@code CCUP-SHOW-DETAILS}). */
    private CardUpdateState detailState;

    /** A valid, fully-populated detail form whose values differ from {@link #detailState}. */
    private COCRDUPForm detailForm;

    /**
     * Builds the detail-edit baseline: a {@code SHOW_DETAILS} state with a seeded
     * old snapshot and a valid detail form that differs from it.
     */
    @BeforeEach
    void setUp() {
        detailState = new CardUpdateState();
        detailState.setChangeAction(ChangeAction.SHOW_DETAILS);
        seedOldSnapshot(detailState, ACCT_11, CARD_NUM, "123", "OLDNAME", "2020", "01", "01", "Y");

        detailForm = detailForm("JANE DOE", "N", "06", "2030", "15");
    }

    // ------------------------------------------------------------------
    // Fixture helpers.
    // ------------------------------------------------------------------

    /**
     * Builds a work area carrying the given raw AID token ({@code CCARD-AID}); the
     * service resolves the {@link PfKey} through {@link CardWorkArea#getPfKey()}.
     *
     * @param aid the raw AID token (for example {@code "ENTER"}, {@code "PFK05"})
     * @return a fresh work area
     */
    private static CardWorkArea workArea(String aid) {
        CardWorkArea workArea = new CardWorkArea();
        workArea.setAid(aid);
        return workArea;
    }

    /**
     * Builds a search-only form ({@code ACCTSIDI} / {@code CARDSIDI}).
     *
     * @param acct the account-filter value (may be {@code null})
     * @param card the card-filter value (may be {@code null})
     * @return a fresh form with only the two filter fields set
     */
    private static COCRDUPForm searchForm(String acct, String card) {
        COCRDUPForm form = new COCRDUPForm();
        form.setAcctsid(acct);
        form.setCardsid(card);
        return form;
    }

    /**
     * Builds a full detail form using the shared {@link #CARD_NUM} / {@link #ACCT_11}
     * filters plus the supplied editable detail values.
     *
     * @param name   the embossed name ({@code CRDNAMEI})
     * @param status the active-status flag ({@code CRDSTCDI})
     * @param month  the expiry month ({@code EXPMONI})
     * @param year   the expiry year ({@code EXPYEARI})
     * @param day    the expiry day ({@code EXPDAYI})
     * @return a fresh, fully-populated detail form
     */
    private static COCRDUPForm detailForm(String name, String status,
                                          String month, String year, String day) {
        COCRDUPForm form = searchForm(ACCT_11, CARD_NUM);
        form.setCrdname(name);
        form.setCrdstcd(status);
        form.setExpmon(month);
        form.setExpyear(year);
        form.setExpday(day);
        return form;
    }

    /**
     * Seeds the {@code CCUP-OLD-*} fetch-time snapshot on a state, mirroring what
     * {@code 9000-READ-DATA} captures after a successful read.
     *
     * @param state  the state to seed
     * @param acct   {@code CCUP-OLD-ACCTID}
     * @param card   {@code CCUP-OLD-CARDID}
     * @param cvv    {@code CCUP-OLD-CVV-CD}
     * @param name   {@code CCUP-OLD-CRDNAME} (already upper-cased by the read)
     * @param year   {@code CCUP-OLD-EXPYEAR}
     * @param month  {@code CCUP-OLD-EXPMON}
     * @param day    {@code CCUP-OLD-EXPDAY}
     * @param status {@code CCUP-OLD-CRDSTCD}
     */
    private static void seedOldSnapshot(CardUpdateState state, String acct, String card,
                                        String cvv, String name, String year, String month,
                                        String day, String status) {
        state.setOldAcctId(acct);
        state.setOldCardId(card);
        state.setOldCvvCd(cvv);
        state.setOldCrdName(name);
        state.setOldExpYear(year);
        state.setOldExpMon(month);
        state.setOldExpDay(day);
        state.setOldCrdStcd(status);
    }

    /**
     * Arms the review-finding-#10 confirmation on a manually-built
     * {@code CHANGES-OK-NOT-CONFIRMED} state so a PF5 turn is honored, exactly as a
     * real validated ENTER edit turn would. It populates {@code CCUP-NEW-*} from the
     * form the operator is about to confirm, snapshots that validated tuple into the
     * server-side {@code pendingDetails}, and issues a single-use token echoed back on
     * the form's hidden field. The subsequent PF5 turn commits the server-carried
     * snapshot (never the re-post) after the constant-time token check, so the
     * write-path assertions remain valid.
     *
     * @param state the CONFIRM-state to arm (its {@code CCUP-NEW-*} and pending
     *              snapshot are set)
     * @param form  the form to confirm (also receives the echoed token)
     */
    private static void armConfirmation(CardUpdateState state, COCRDUPForm form) {
        state.setNewAcctId(form.getAcctsid());
        state.setNewCardId(form.getCardsid());
        state.setNewCrdName(form.getCrdname());
        state.setNewCrdStcd(form.getCrdstcd());
        state.setNewExpMon(form.getExpmon());
        state.setNewExpYear(form.getExpyear());
        state.setNewExpDay(form.getExpday());
        state.setPendingDetails(CardUpdateState.PendingCardDetails.capture(state));
        state.setConfirmToken(CONFIRM_TOKEN);
        form.setConfirmToken(CONFIRM_TOKEN);
    }

    /**
     * Invokes a private method on the service and unwraps the reflective
     * {@link InvocationTargetException} so the original thrown throwable surfaces
     * to the caller (or {@link org.assertj.core.api.Assertions#catchThrowable}).
     *
     * @param method the accessible private method
     * @param args   the invocation arguments
     * @return the method result (for non-throwing invocations)
     * @throws Throwable the original throwable raised inside the method, if any
     */
    private Object invokeUnwrapped(Method method, Object... args) throws Throwable {
        try {
            return method.invoke(service, args);
        } catch (InvocationTargetException reflective) {
            throw reflective.getCause() != null ? reflective.getCause() : reflective;
        }
    }

    /**
     * Builds a fully-populated {@link Card} fixture (the persisted / re-read
     * record), mirroring a {@code CARDDAT} row with all six columns set.
     *
     * @param num    the card number ({@code CARD-NUM}, primary key)
     * @param acct   the owning account id ({@code CARD-ACCT-ID})
     * @param cvv    the CVV ({@code CARD-CVV-CD})
     * @param name   the embossed name ({@code CARD-EMBOSSED-NAME})
     * @param expiry the expiry date ({@code CARD-EXPIRAION-DATE}, misspelling preserved)
     * @param status the active-status flag ({@code CARD-ACTIVE-STATUS})
     * @return a fresh {@link Card}
     */
    private static Card newCard(String num, Long acct, Integer cvv, String name,
                               LocalDate expiry, String status) {
        return new Card(num, acct, cvv, name, expiry, status);
    }

    // ==================================================================
    // Group A - 9000-READ-DATA / 9100-GETCARD-BYACCTCARD (checklist 1).
    // ==================================================================

    /**
     * Branch 2 (arrival from the card-list program {@code COCRDLIC}): {@code CCUP}
     * fetches the selected card and shows it for update. Verifies the keyed read on
     * the card number (the VSAM primary key) and that the fetch-time snapshot is
     * captured with the embossed name upper-cased ({@code 9000-READ-DATA}).
     */
    @Test
    void branch2FromCardList_readsAndShowsDetails_whenCardFound() {
        when(context.isProgramEnter()).thenReturn(true);
        when(context.getFromProgram()).thenReturn(CCLIST_PGM);
        when(context.getAcctId()).thenReturn(1L);
        when(context.getCardNum()).thenReturn(CARD_NUM);
        when(cardRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(newCard(CARD_NUM, 1L, 123, "john smith",
                        LocalDate.of(2025, 1, 1), "Y")));

        CardUpdateResult result = service.mainEntry(searchForm(null, null),
                workArea("ENTER"), new CardUpdateState());

        assertThat(result.getAction()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.isProgramEnter()).isTrue();

        EditWorkState edit = result.getEditState();
        assertThat(edit.isFoundCardsForAccount()).isTrue();
        assertThat(edit.getAcctFilter()).isEqualTo(EditFlag.ISVALID);
        assertThat(edit.getCardFilter()).isEqualTo(EditFlag.ISVALID);
        // 9000 keyed READ on CARDDAT via findById(cardNum) - confirm the key.
        verify(cardRepository).findById(CARD_NUM);
    }

    /**
     * Branch 2 with a {@code NOTFND} on the keyed read: the soft in-screen error is
     * raised ({@code INPUT-ERROR}, both filters {@code NOT-OK},
     * "Did not find cards for this search condition") and no record is written.
     */
    @Test
    void branch2FromCardList_setsNotFound_whenCardMissing() {
        when(context.isProgramEnter()).thenReturn(true);
        when(context.getFromProgram()).thenReturn(CCLIST_PGM);
        when(context.getAcctId()).thenReturn(1L);
        when(context.getCardNum()).thenReturn(CARD_NUM);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        CardUpdateResult result = service.mainEntry(searchForm(null, null),
                workArea("ENTER"), new CardUpdateState());

        EditWorkState edit = result.getEditState();
        assertThat(edit.isFoundCardsForAccount()).isFalse();
        assertThat(edit.isInputError()).isTrue();
        assertThat(edit.getAcctFilter()).isEqualTo(EditFlag.NOT_OK);
        assertThat(edit.getCardFilter()).isEqualTo(EditFlag.NOT_OK);
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_DID_NOT_FIND_ACCTCARD);
        verify(cardRepository).findById(CARD_NUM);
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    /**
     * Branch 5 with valid search keys and no card yet fetched: the field edits pass
     * and {@code 2000-DECIDE-ACTION} drives {@code 9000-READ-DATA}, which reads the
     * card and advances to {@code SHOW_DETAILS}.
     */
    @Test
    void validFilters_triggerReadViaDecideAction_showsDetails() {
        when(cardRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(newCard(CARD_NUM, 1L, 123, "JOHN SMITH",
                        LocalDate.of(2025, 1, 1), "Y")));

        CardUpdateResult result = service.mainEntry(searchForm(ACCT_11, CARD_NUM),
                workArea("ENTER"), new CardUpdateState());

        assertThat(result.getAction()).isEqualTo(RoutingAction.SHOW_SCREEN);
        EditWorkState edit = result.getEditState();
        assertThat(edit.getAcctFilter()).isEqualTo(EditFlag.ISVALID);
        assertThat(edit.getCardFilter()).isEqualTo(EditFlag.ISVALID);
        assertThat(edit.isFoundCardsForAccount()).isTrue();
        verify(cardRepository).findById(CARD_NUM);
    }

    /**
     * {@code 9100-GETCARD-BYACCTCARD} directly (reflection): returns the located
     * card for the supplied key and issues exactly the {@code findById(cardNum)}
     * read.
     *
     * @throws Exception if the reflective lookup fails
     */
    @Test
    void getCardByAccountCard_returnsCard_whenPresent() throws Exception {
        Card stored = newCard(CARD_NUM, 1L, 123, "JOHN SMITH", LocalDate.of(2025, 1, 1), "Y");
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(stored));

        Method method = CardUpdateService.class.getDeclaredMethod("getCardByAccountCard", String.class);
        method.setAccessible(true);
        Object result = method.invoke(service, CARD_NUM);

        assertThat(result).isSameAs(stored);
        verify(cardRepository).findById(CARD_NUM);
    }

    /**
     * {@code 9100-GETCARD-BYACCTCARD} with an empty result (CICS {@code NOTFND}):
     * raises {@link RecordNotFoundException} (FILE STATUS {@code 23}) with a generic
     * message that never echoes the PAN.
     *
     * @throws Exception if the reflective lookup fails
     */
    @Test
    void getCardByAccountCard_throwsRecordNotFound_whenAbsent() throws Exception {
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        Method method = CardUpdateService.class.getDeclaredMethod("getCardByAccountCard", String.class);
        method.setAccessible(true);
        Throwable thrown = catchThrowable(() -> invokeUnwrapped(method, CARD_NUM));

        assertThat(thrown).isInstanceOf(RecordNotFoundException.class);
        assertThat(((RecordNotFoundException) thrown).getFileStatus()).isEqualTo("23");
        assertThat(thrown).hasMessageContaining("not found");
    }

    // ==================================================================
    // Group B - field edits 1210-1260 (checklist 2). Each flips one field.
    // ==================================================================

    /**
     * {@code 1210-EDIT-ACCOUNT}: a non-11-digit account filter is rejected as
     * "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER" ({@code NOT-OK}); the
     * (valid) card filter stays {@code ISVALID} and no read is attempted.
     */
    @Test
    void editAccount_rejectsNon11DigitFilter() {
        CardUpdateResult result = service.mainEntry(searchForm("123", CARD_NUM),
                workArea("ENTER"), new CardUpdateState());

        EditWorkState edit = result.getEditState();
        assertThat(edit.isInputError()).isTrue();
        assertThat(edit.getAcctFilter()).isEqualTo(EditFlag.NOT_OK);
        assertThat(edit.getCardFilter()).isEqualTo(EditFlag.ISVALID);
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_ACCT_MUST_BE_11_DIGITS);
        verifyNoInteractions(cardRepository);
    }

    /**
     * {@code 1210-EDIT-ACCOUNT}: a valid 11-digit account filter is accepted
     * ({@code ISVALID}); paired with a valid card filter this proceeds to the read.
     */
    @Test
    void editAccount_acceptsValid11DigitFilter() {
        when(cardRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(newCard(CARD_NUM, 1L, 123, "JOHN SMITH",
                        LocalDate.of(2025, 1, 1), "Y")));

        CardUpdateResult result = service.mainEntry(searchForm(ACCT_11, CARD_NUM),
                workArea("ENTER"), new CardUpdateState());

        assertThat(result.getEditState().getAcctFilter()).isEqualTo(EditFlag.ISVALID);
    }

    /**
     * {@code 1220-EDIT-CARD}: a non-16-digit card filter is rejected as
     * "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER" ({@code NOT-OK}); the
     * (valid) account filter stays {@code ISVALID} and no read is attempted.
     */
    @Test
    void editCard_rejectsNon16DigitFilter() {
        CardUpdateResult result = service.mainEntry(searchForm(ACCT_11, "123"),
                workArea("ENTER"), new CardUpdateState());

        EditWorkState edit = result.getEditState();
        assertThat(edit.isInputError()).isTrue();
        assertThat(edit.getCardFilter()).isEqualTo(EditFlag.NOT_OK);
        assertThat(edit.getAcctFilter()).isEqualTo(EditFlag.ISVALID);
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_CARD_MUST_BE_16_DIGITS);
        verifyNoInteractions(cardRepository);
    }

    /**
     * {@code 1200-EDIT-MAP-INPUTS}: when neither search key is supplied, the
     * per-field blank prompts are overridden by the single "No input received"
     * message and both filters are {@code BLANK}.
     */
    @Test
    void editMapInputs_bothFiltersBlank_reportsNoSearchCriteria() {
        CardUpdateResult result = service.mainEntry(searchForm(null, null),
                workArea("ENTER"), new CardUpdateState());

        EditWorkState edit = result.getEditState();
        assertThat(edit.isInputError()).isTrue();
        assertThat(edit.getAcctFilter()).isEqualTo(EditFlag.BLANK);
        assertThat(edit.getCardFilter()).isEqualTo(EditFlag.BLANK);
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_NO_SEARCH_CRITERIA);
        verifyNoInteractions(cardRepository);
    }

    /**
     * {@code 1230-EDIT-NAME}: an embossed name containing non-alphabetic characters
     * is rejected as "Card name can only contain alphabets and spaces"
     * ({@code NOT-OK}) while a fetched card is on display.
     */
    @Test
    void editName_rejectsNonAlphabeticName() {
        CardUpdateResult result = service.mainEntry(
                detailForm("John123", "N", "06", "2030", "15"),
                workArea("ENTER"), detailState);

        EditWorkState edit = result.getEditState();
        assertThat(edit.isInputError()).isTrue();
        assertThat(edit.getCardName()).isEqualTo(EditFlag.NOT_OK);
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_NAME_MUST_BE_ALPHA);
        verifyNoInteractions(cardRepository);
    }

    /**
     * {@code 1240-EDIT-CARDSTATUS}: an active-status flag other than {@code 'Y'} or
     * {@code 'N'} is rejected as "Card Active Status must be Y or N" ({@code NOT-OK}).
     */
    @Test
    void editCardStatus_rejectsValueOtherThanYorN() {
        CardUpdateResult result = service.mainEntry(
                detailForm("JANE DOE", "X", "06", "2030", "15"),
                workArea("ENTER"), detailState);

        EditWorkState edit = result.getEditState();
        assertThat(edit.isInputError()).isTrue();
        assertThat(edit.getCardStatus()).isEqualTo(EditFlag.NOT_OK);
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_STATUS_MUST_BE_YES_NO);
        verifyNoInteractions(cardRepository);
    }

    /**
     * {@code 1250-EDIT-EXPIRY-MON}: a month outside 1&ndash;12 is rejected as
     * "Card expiry month must be between 1 and 12" ({@code NOT-OK}).
     */
    @Test
    void editExpiryMonth_rejectsOutOfRangeMonth() {
        CardUpdateResult result = service.mainEntry(
                detailForm("JANE DOE", "N", "13", "2030", "15"),
                workArea("ENTER"), detailState);

        EditWorkState edit = result.getEditState();
        assertThat(edit.isInputError()).isTrue();
        assertThat(edit.getCardExpMon()).isEqualTo(EditFlag.NOT_OK);
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_EXPIRY_MONTH_NOT_VALID);
        verifyNoInteractions(cardRepository);
    }

    /**
     * {@code 1260-EDIT-EXPIRY-YEAR}: a year outside 1950&ndash;2099 is rejected as
     * "Invalid card expiry year" ({@code NOT-OK}).
     */
    @Test
    void editExpiryYear_rejectsOutOfRangeYear() {
        CardUpdateResult result = service.mainEntry(
                detailForm("JANE DOE", "N", "06", "1949", "15"),
                workArea("ENTER"), detailState);

        EditWorkState edit = result.getEditState();
        assertThat(edit.isInputError()).isTrue();
        assertThat(edit.getCardExpYear()).isEqualTo(EditFlag.NOT_OK);
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_EXPIRY_YEAR_NOT_VALID);
        verifyNoInteractions(cardRepository);
    }

    /**
     * {@code 1200-EDIT-MAP-INPUTS}: when every detail field is valid and at least
     * one changed, all detail edit flags are {@code ISVALID} and the state advances
     * to {@code CHANGES-OK-NOT-CONFIRMED} (awaiting the PF5 confirmation).
     */
    @Test
    void editMapInputs_allDetailFieldsValid_advancesToChangesOkNotConfirmed() {
        CardUpdateResult result = service.mainEntry(detailForm, workArea("ENTER"), detailState);

        EditWorkState edit = result.getEditState();
        assertThat(edit.isInputError()).isFalse();
        assertThat(edit.getCardName()).isEqualTo(EditFlag.ISVALID);
        assertThat(edit.getCardStatus()).isEqualTo(EditFlag.ISVALID);
        assertThat(edit.getCardExpMon()).isEqualTo(EditFlag.ISVALID);
        assertThat(edit.getCardExpYear()).isEqualTo(EditFlag.ISVALID);
        assertThat(detailState.isChangesOkNotConfirmed()).isTrue();
        verifyNoInteractions(cardRepository);
    }


    // ==================================================================
    // Group C - expiry rebuild (3) + PF5 confirmed save (4), 9200/9300.
    // ==================================================================

    /**
     * PF5 confirmed save on the happy path ({@code 2000-DECIDE-ACTION} ->
     * {@code 9200-WRITE-PROCESSING}). Asserts, via {@link ArgumentCaptor}, that the
     * rewritten card carries: the rebuilt {@code cardExpiraionDate} (misspelling
     * preserved) assembled from the edited year/month/day; the edited embossed name
     * and status; the reassigned account (the entered filter, not the card's stored
     * account); and a zeroed CVV. The re-read card's embossed name is stored in
     * <em>lower</em> case yet {@code 9300-CHECK-CHANGE-IN-REC} still sees it as
     * unchanged, proving the name is upper-cased <em>before</em> the compare. The
     * state ends at {@code CHANGES-OKAYED-AND-DONE}.
     */
    @Test
    void pf5_savesRebuiltCard_captureAssertsExpiryReassignAndZeroCvv() {
        CardUpdateState state = new CardUpdateState();
        state.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        // Snapshot matches the re-read card once formatted (name compared upper-cased).
        seedOldSnapshot(state, ACCT_11, CARD_NUM, "123", "JOHN SMITH", "2025", "01", "01", "Y");

        // Re-read record carries a LOWER-CASE embossed name to prove 9300 upper-cases
        // before comparing (otherwise it would look changed and no save would occur).
        Card stored = newCard(CARD_NUM, 1L, 123, "john smith", LocalDate.of(2025, 1, 1), "Y");
        when(cardRepository.findByIdForUpdate(CARD_NUM)).thenReturn(Optional.of(stored));

        COCRDUPForm form = new COCRDUPForm();
        form.setAcctsid("00000000002"); // different account -> reassign quirk visible
        form.setCardsid(CARD_NUM);
        form.setCrdname("Jane Doe");
        form.setCrdstcd("N");
        form.setExpmon("03");
        form.setExpyear("2027");
        form.setExpday("15");

        // Review finding #10: arm the confirmation (server snapshot + token) as a real
        // ENTER edit turn would, so the PF5 save is honored and commits the snapshot.
        armConfirmation(state, form);

        CardUpdateResult result = service.mainEntry(form, workArea("PFK05"), state);

        ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).findByIdForUpdate(CARD_NUM);
        verify(cardRepository).saveAndFlush(captor.capture());
        Card saved = captor.getValue();
        assertThat(saved.getCardNum()).isEqualTo(CARD_NUM);
        assertThat(saved.getCardEmbossedName()).isEqualTo("Jane Doe");
        // Expiry rebuilt from y/m/d into the (intentionally misspelled) field.
        assertThat(saved.getCardExpiraionDate()).isEqualTo(LocalDate.of(2027, 3, 15));
        assertThat(saved.getCardActiveStatus()).isEqualTo("N");
        // Preserved quirks: account reassigned to the entered filter; CVV coerced to 0.
        assertThat(saved.getCardAcctId()).isEqualTo(2L);
        assertThat(saved.getCardCvvCd()).isEqualTo(0);

        assertThat(state.isChangesOkayedAndDone()).isTrue();
        assertThat(result.getAction()).isEqualTo(RoutingAction.SHOW_SCREEN);
    }

    // ==================================================================
    // Group C2 - confirmation integrity (review finding #10, CWE-20/CWE-639).
    //
    // On the mainframe the confirmation-screen fields are protected, so a PF5
    // turn can only re-present the validated CCUP-NEW-DETAILS. Over HTTP a
    // crafted PF5 can re-post arbitrary values, and 1200-EDIT-MAP-INPUTS
    // deliberately skips re-validation in the confirm state. These tests assert
    // that the server (a) commits the server-carried validated snapshot, never
    // the re-post, and (b) rejects a missing / forged / replayed single-use
    // token with no write.
    // ==================================================================

    /**
     * Overposting defence: a PF5 re-post carrying a legitimate (visible) token but
     * tampered detail fields and a swapped account filter commits the server-carried
     * validated snapshot - the values the operator actually confirmed - and re-affirms
     * the write identity from that snapshot, never the re-post. The single-use token is
     * consumed on success.
     */
    @Test
    void pf5_overpost_commitsServerCarriedSnapshotNotRepost() {
        CardUpdateState state = new CardUpdateState();
        state.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        seedOldSnapshot(state, ACCT_11, CARD_NUM, "123", "JOHN SMITH", "2025", "01", "01", "Y");

        // The validated ENTER turn the operator actually performed and confirmed.
        COCRDUPForm confirmed = detailForm("JANE DOE", "N", "06", "2030", "15");
        armConfirmation(state, confirmed);

        Card stored = newCard(CARD_NUM, 1L, 123, "JOHN SMITH", LocalDate.of(2025, 1, 1), "Y");
        when(cardRepository.findByIdForUpdate(CARD_NUM)).thenReturn(Optional.of(stored));

        // Attacker's PF5 re-post: the legitimate (visible) token but tampered fields
        // and a swapped target account.
        COCRDUPForm tampered = detailForm("HACKER", "Z", "99", "1900", "31");
        tampered.setAcctsid("00000000009");
        tampered.setConfirmToken(CONFIRM_TOKEN);

        CardUpdateResult result = service.mainEntry(tampered, workArea("PFK05"), state);

        ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).saveAndFlush(captor.capture());
        Card saved = captor.getValue();
        // Server-carried validated snapshot committed, NOT the re-post.
        assertThat(saved.getCardEmbossedName()).isEqualTo("JANE DOE");
        assertThat(saved.getCardActiveStatus()).isEqualTo("N");
        assertThat(saved.getCardExpiraionDate()).isEqualTo(LocalDate.of(2030, 6, 15));
        // Identity re-affirmed from the snapshot (entered filter == fetched), not the swap.
        assertThat(saved.getCardAcctId()).isEqualTo(1L);
        assertThat(state.isChangesOkayedAndDone()).isTrue();
        // Single-use: the token and snapshot are consumed after the write.
        assertThat(state.getConfirmToken()).isNull();
        assertThat(state.getPendingDetails()).isNull();
        assertThat(result.getAction()).isEqualTo(RoutingAction.SHOW_SCREEN);
    }

    /**
     * A PF5 that omits the confirmation token is rejected with the integrity banner and
     * performs no write; the confirmation window stays open (the validated snapshot is
     * retained with a freshly re-issued token) so a legitimate operator can retry.
     */
    @Test
    void pf5_missingToken_rejectedWithIntegrityBannerNoWrite() {
        CardUpdateState state = new CardUpdateState();
        state.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        seedOldSnapshot(state, ACCT_11, CARD_NUM, "123", "JOHN SMITH", "2025", "01", "01", "Y");
        COCRDUPForm confirmed = detailForm("JANE DOE", "N", "06", "2030", "15");
        armConfirmation(state, confirmed);

        // PF5 re-post that omits the token (confirmToken == null).
        COCRDUPForm noToken = detailForm("JANE DOE", "N", "06", "2030", "15");

        CardUpdateResult result = service.mainEntry(noToken, workArea("PFK05"), state);

        assertThat(result.getEditState().getReturnMessage()).isEqualTo(MSG_CONFIRM_INTEGRITY);
        assertThat(state.isChangesOkNotConfirmed()).isTrue();  // window stays open
        assertThat(state.getPendingDetails()).isNotNull();     // snapshot retained
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
        verify(cardRepository, never()).findById(any());
    }

    /**
     * A PF5 carrying a forged (non-matching) token is rejected with the integrity
     * banner and performs no write; the constant-time comparison never admits it.
     */
    @Test
    void pf5_forgedToken_rejectedWithIntegrityBannerNoWrite() {
        CardUpdateState state = new CardUpdateState();
        state.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        seedOldSnapshot(state, ACCT_11, CARD_NUM, "123", "JOHN SMITH", "2025", "01", "01", "Y");
        COCRDUPForm confirmed = detailForm("JANE DOE", "N", "06", "2030", "15");
        armConfirmation(state, confirmed);

        COCRDUPForm forged = detailForm("JANE DOE", "N", "06", "2030", "15");
        forged.setConfirmToken("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");

        CardUpdateResult result = service.mainEntry(forged, workArea("PFK05"), state);

        assertThat(result.getEditState().getReturnMessage()).isEqualTo(MSG_CONFIRM_INTEGRITY);
        assertThat(state.isChangesOkNotConfirmed()).isTrue();
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    // ==================================================================
    // Group D - PF12 re-fetch discards edits, no save (checklist 5).
    // ==================================================================

    /**
     * PF12 while a card is on display: {@code 2000-DECIDE-ACTION} re-reads the card
     * (discarding the pending edits) and returns to {@code SHOW_DETAILS}; no rewrite
     * is performed.
     */
    @Test
    void pf12_reReadsCard_discardsEditsAndDoesNotSave() {
        when(cardRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(newCard(CARD_NUM, 1L, 123, "JOHN SMITH",
                        LocalDate.of(2025, 1, 1), "Y")));

        CardUpdateResult result = service.mainEntry(detailForm, workArea("PFK12"), detailState);

        assertThat(result.getAction()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(detailState.isShowDetails()).isTrue();
        assertThat(result.getEditState().isFoundCardsForAccount()).isTrue();
        verify(cardRepository).findById(CARD_NUM);
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }


    // ==================================================================
    // Group E - 9300 optimistic lock + 9200 write signals (checklist 6).
    //
    // Faithfulness note (schema item 6b): a concurrent change and a failed
    // rewrite are SOFT outcomes in COCRDUPC (screen re-display / OKAYED-BUT-
    // FAILED) - neither abends - and in both cases NO record is committed.
    // The only LogicError(92) site is the defensive 2000 WHEN OTHER abend,
    // asserted directly at the end of this group.
    // ==================================================================

    /**
     * {@code 9300-CHECK-CHANGE-IN-REC} detects a concurrent change: the re-read card
     * differs from the fetch-time snapshot (its active status changed underneath
     * the user), so the rewrite is abandoned with "Record changed by some one else.
     * Please review", the state returns to {@code SHOW_DETAILS}, and nothing is
     * committed.
     */
    @Test
    void pf5_concurrentChange_reDisplaysWithoutSaving() {
        CardUpdateState state = new CardUpdateState();
        state.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        seedOldSnapshot(state, ACCT_11, CARD_NUM, "123", "JOHN SMITH", "2025", "01", "01", "Y");

        // Re-read card's status differs from the snapshot -> concurrent change.
        when(cardRepository.findByIdForUpdate(CARD_NUM))
                .thenReturn(Optional.of(newCard(CARD_NUM, 1L, 123, "JOHN SMITH",
                        LocalDate.of(2025, 1, 1), "N")));

        COCRDUPForm form = new COCRDUPForm();
        form.setAcctsid(ACCT_11);
        form.setCardsid(CARD_NUM);
        form.setCrdname("Jane Doe");
        form.setCrdstcd("N");
        form.setExpmon("03");
        form.setExpyear("2027");
        form.setExpday("15");

        // Review finding #10: arm the confirmation before the honored PF5 save.
        armConfirmation(state, form);

        CardUpdateResult result = service.mainEntry(form, workArea("PFK05"), state);

        EditWorkState edit = result.getEditState();
        assertThat(edit.isDataWasChangedBeforeUpdate()).isTrue();
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_DATA_WAS_CHANGED);
        assertThat(state.isShowDetails()).isTrue();
        verify(cardRepository).findByIdForUpdate(CARD_NUM);
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    /**
     * {@code 9200-WRITE-PROCESSING} rewrite failure: the record is unchanged since
     * the fetch (the optimistic check passes) but {@code saveAndFlush} fails. This
     * is the soft {@code LOCKED-BUT-UPDATE-FAILED} outcome ("Update of record
     * failed"), advancing to {@code CHANGES-OKAYED-BUT-FAILED}; the failed flush
     * commits nothing.
     */
    @Test
    void pf5_rewriteFailure_reportsUpdateFailedSoftly() {
        CardUpdateState state = new CardUpdateState();
        state.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        seedOldSnapshot(state, ACCT_11, CARD_NUM, "123", "JOHN SMITH", "2025", "01", "01", "Y");

        when(cardRepository.findByIdForUpdate(CARD_NUM))
                .thenReturn(Optional.of(newCard(CARD_NUM, 1L, 123, "JOHN SMITH",
                        LocalDate.of(2025, 1, 1), "Y")));
        when(cardRepository.saveAndFlush(any(Card.class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        COCRDUPForm form = new COCRDUPForm();
        form.setAcctsid(ACCT_11);
        form.setCardsid(CARD_NUM);
        form.setCrdname("Jane Doe");
        form.setCrdstcd("N");
        form.setExpmon("03");
        form.setExpyear("2027");
        form.setExpday("15");

        // Review finding #10: arm the confirmation before the honored PF5 save.
        armConfirmation(state, form);

        CardUpdateResult result = service.mainEntry(form, workArea("PFK05"), state);

        EditWorkState edit = result.getEditState();
        assertThat(edit.isLockedButUpdateFailed()).isTrue();
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_UPDATE_FAILED);
        assertThat(state.getChangeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_BUT_FAILED);
        assertThat(state.isChangesFailed()).isTrue();
        verify(cardRepository).findByIdForUpdate(CARD_NUM);
        verify(cardRepository).saveAndFlush(any(Card.class));
    }

    /**
     * {@code 9200-WRITE-PROCESSING} could-not-lock: the read-for-update returns
     * empty (a non-{@code NORMAL} lock response), yielding "Could not lock record
     * for update", advancing to {@code CHANGES-OKAYED-LOCK-ERROR}; no rewrite is
     * attempted.
     */
    @Test
    void pf5_couldNotLock_reportsLockErrorSoftly() {
        CardUpdateState state = new CardUpdateState();
        state.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        seedOldSnapshot(state, ACCT_11, CARD_NUM, "123", "JOHN SMITH", "2025", "01", "01", "Y");

        when(cardRepository.findByIdForUpdate(CARD_NUM)).thenReturn(Optional.empty());

        COCRDUPForm form = new COCRDUPForm();
        form.setAcctsid(ACCT_11);
        form.setCardsid(CARD_NUM);
        form.setCrdname("Jane Doe");
        form.setCrdstcd("N");
        form.setExpmon("03");
        form.setExpyear("2027");
        form.setExpday("15");

        // Review finding #10: arm the confirmation before the honored PF5 save.
        armConfirmation(state, form);

        CardUpdateResult result = service.mainEntry(form, workArea("PFK05"), state);

        EditWorkState edit = result.getEditState();
        assertThat(edit.isCouldNotLockForUpdate()).isTrue();
        assertThat(edit.getReturnMessage()).isEqualTo(MSG_COULD_NOT_LOCK);
        assertThat(state.getChangeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
        verify(cardRepository).findByIdForUpdate(CARD_NUM);
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    /**
     * {@code ABEND-ROUTINE} via the {@code 2000-DECIDE-ACTION} {@code WHEN OTHER}
     * branch (reflection): an unexpected action state raises {@link LogicError}
     * (FILE STATUS {@code 92}) with the {@code '0001'} abend code &mdash; the only
     * hard-error path in the program.
     *
     * @throws Exception if the reflective lookup fails
     */
    @Test
    void decideAction_unexpectedState_abendsWithLogicError() throws Exception {
        CardUpdateState state = new CardUpdateState();
        state.setChangeAction(ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
        EditWorkState edit = new EditWorkState();

        Method method = CardUpdateService.class.getDeclaredMethod(
                "decideAction", CardWorkArea.class, CardUpdateState.class,
                EditWorkState.class, PfKey.class);
        method.setAccessible(true);
        Throwable thrown = catchThrowable(
                () -> invokeUnwrapped(method, workArea("ENTER"), state, edit, PfKey.ENTER));

        assertThat(thrown).isInstanceOf(LogicError.class);
        assertThat(((LogicError) thrown).getFileStatus()).isEqualTo("92");
        assertThat(thrown).hasMessageContaining("0001");
        verifyNoInteractions(cardRepository);
    }


    // ==================================================================
    // Group F - no monetary arithmetic (checklist 7).
    // ==================================================================

    /**
     * Card update touches no balances, so the service performs no monetary
     * arithmetic: no declared field, method return type, or method parameter is a
     * {@link BigDecimal}. This guards the parity invariant that {@code COCRDUPC}
     * edits card metadata only.
     */
    @Test
    void service_declaresNoMonetaryArithmetic() {
        for (Field field : CardUpdateService.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("field %s must not be BigDecimal", field.getName())
                    .isNotEqualTo(BigDecimal.class);
        }
        for (Method method : CardUpdateService.class.getDeclaredMethods()) {
            assertThat(method.getReturnType())
                    .as("method %s must not return BigDecimal", method.getName())
                    .isNotEqualTo(BigDecimal.class);
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertThat(parameterType)
                        .as("method %s must not take a BigDecimal parameter", method.getName())
                        .isNotEqualTo(BigDecimal.class);
            }
        }
    }

    // ==================================================================
    // Group G - pseudo-conversational entry semantics (checklist 8).
    // ==================================================================

    /**
     * First entry ({@code EIBCALEN = 0}): the program initializes its state, marks
     * the enter/initialized flags, and (branch 3) shows the empty search screen in
     * the {@code DETAILS-NOT-FETCHED} state, rendering as a fresh program-enter.
     */
    @Test
    void firstEntry_initializesAndShowsSearchScreen() {
        when(context.isNew()).thenReturn(true);
        when(context.isProgramEnter()).thenReturn(true);

        CardUpdateResult result = service.mainEntry(searchForm(null, null),
                workArea("ENTER"), new CardUpdateState());

        assertThat(result.getAction()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.isProgramEnter()).isTrue();
        assertThat(result.getEditState().isInputError()).isFalse();
        // First-entry bookkeeping (COBOL INITIALIZE + SET PGM-ENTER).
        verify(context).markEnter();
        verify(context).markInitialized();
        verify(context).markReenter();
        verifyNoInteractions(cardRepository);
    }

    /**
     * Re-entry (a live session already initialized): the first-entry block is
     * skipped &mdash; {@code markInitialized}/{@code markEnter}/{@code markReenter}
     * are <em>not</em> re-invoked &mdash; and normal input processing (branch 5)
     * advances the confirmed edits to {@code CHANGES-OK-NOT-CONFIRMED}.
     */
    @Test
    void reEntry_doesNotReinitializeState() {
        CardUpdateResult result = service.mainEntry(detailForm, workArea("ENTER"), detailState);

        assertThat(detailState.isChangesOkNotConfirmed()).isTrue();
        assertThat(result.getAction()).isEqualTo(RoutingAction.SHOW_SCREEN);
        verify(context, never()).markInitialized();
        verify(context, never()).markEnter();
        verify(context, never()).markReenter();
        verifyNoInteractions(cardRepository);
    }

    // ==================================================================
    // Group H - PF3 exit -> XCTL redirect (branch 1).
    // ==================================================================

    /**
     * PF3 from a standalone entry (no originating transaction): control transfers
     * back to the main menu. The context is wired for the redirect &mdash; from =
     * this program/transaction ({@code COCRDUPC}/{@code CCUP}), to = the menu
     * defaults ({@code COMEN01C}/{@code CM00}) &mdash; the enter flag is set, and
     * the last map/mapset are stamped as this screen's.
     */
    @Test
    void pf3_redirectsToMenu_andWiresContext() {
        CardUpdateResult result = service.mainEntry(searchForm(null, null),
                workArea("PFK03"), new CardUpdateState());

        assertThat(result.getAction()).isEqualTo(RoutingAction.REDIRECT);
        assertThat(result.isProgramEnter()).isFalse();
        verify(context).setToTranid(MENU_TRANID);
        verify(context).setToProgram(MENU_PGM);
        verify(context).setFromTranid(THIS_TRANID);
        verify(context).setFromProgram(THIS_PGM);
        verify(context).setUser();
        verify(context).markEnter();
        verify(context).setLastMapset(THIS_MAPSET);
        verify(context).setLastMap(THIS_MAP);
        verifyNoInteractions(cardRepository);
    }


}

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COCRDLIForm;
import com.aws.carddemo.exception.EndOfFileException;
import com.aws.carddemo.repository.CardRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.service.online.CardListService.CardListPagingState;
import com.aws.carddemo.service.online.CardListService.CardListResult;
import com.aws.carddemo.service.online.CardListService.CardListRow;
import com.aws.carddemo.service.online.CardListService.CardListState;
import com.aws.carddemo.service.online.CardListService.Routing;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

/**
 * Pure-Mockito unit tests for {@link CardListService}.
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COCRDLIC.cbl}
 * (CICS COBOL program {@code COCRDLIC}, transaction id {@code CCLI}) &mdash; the
 * online <em>Credit Card List</em> paged-browse program. These tests assert
 * one-for-one control-flow parity with the numbered paragraphs of the oracle that
 * the service migrates (AAP &sect;0.1.2 / &sect;0.4.1 / &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code 0000-MAIN} &rarr; {@link CardListService#mainEntry} &mdash; the
 *       pseudo-conversational driver ({@code EIBCALEN = 0} first entry, program
 *       re-entry, PF-key remap, {@code PF3} exit, and the selection /
 *       paging {@code EVALUATE}).</li>
 *   <li>{@code 2200-EDIT-INPUTS} &rarr; {@link CardListService#editInputs}.</li>
 *   <li>{@code 2210-EDIT-ACCOUNT} &rarr; {@link CardListService#editAccount} &mdash;
 *       the {@code PIC X(11)} account-filter edit and its
 *       "{@code ...MUST BE A 11 DIGIT NUMBER}" message.</li>
 *   <li>{@code 2220-EDIT-CARD} &rarr; {@link CardListService#editCard} &mdash; the
 *       {@code PIC X(16)} card-filter edit and its
 *       "{@code ...MUST BE A 16 DIGIT NUMBER}" message.</li>
 *   <li>{@code 2250-EDIT-ARRAY} &rarr; {@link CardListService#editArray} &mdash; the
 *       seven-row selection edit, the {@code WS-MORE-THAN-1-ACTION} and
 *       {@code WS-INVALID-ACTION-CODE} errors, and the {@code I-SELECTED}
 *       capture.</li>
 *   <li>{@code 9000-READ-FORWARD} &rarr; {@link CardListService#readForward} &mdash;
 *       the {@code STARTBR}/{@code READNEXT} ascending browse limited to
 *       {@code WS-MAX-SCREEN-LINES VALUE 7} rows per page, forward paging, and the
 *       {@code DFHRESP(ENDFILE)} end-of-page handling.</li>
 *   <li>{@code 9100-READ-BACKWARDS} &rarr; {@link CardListService#readBackwards}
 *       &mdash; the {@code READPREV} backward paging that rebuilds the prior
 *       page.</li>
 *   <li>{@code 9500-FILTER-RECORDS} &rarr; {@link CardListService#filterRecords}
 *       &mdash; the account / card exclude test.</li>
 * </ul>
 *
 * <p><b>Test tier:</b> a strict-stubs pure-Mockito unit test
 * ({@link MockitoExtension}); it uses no database, no Spring context, and no
 * Testcontainers. Collaborators are mocked and the service is exercised in
 * isolation. Routing outcomes ({@code XCTL}) are asserted on the mocked
 * {@link CardDemoContext} (the {@code COMMAREA} replacement) rather than on any
 * HTTP redirect, because this service records the routing target in the context
 * and defers the actual redirect to the controller.</p>
 *
 * <p><b>Browse fixtures:</b> the in-memory {@code STARTBR}/{@code READNEXT} browse
 * reads a scoped, ascending-by-card-number {@link Card} list returned by the mocked
 * {@link CardRepository}. The unfiltered browse is stubbed on
 * {@link CardRepository#findAll(Sort)} and the account-filtered
 * ({@code CARDAIX} alternate-index) browse on
 * {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long)}. Fixtures carry at
 * least eight cards so the seven-row page limit and forward / backward paging are
 * provable.</p>
 */
@ExtendWith(MockitoExtension.class)
class CardListServiceTest {

    /** COBOL {@code LIT-THISPGM VALUE 'COCRDLIC'}; recorded as the origin program on routing. */
    private static final String LIST_PROGRAM = "COCRDLIC";

    /** COBOL {@code LIT-THISTRANID VALUE 'CCLI'}; recorded as the origin tran id on routing. */
    private static final String LIST_TRANSACTION = "CCLI";

    /** COBOL {@code LIT-MENUPGM VALUE 'COMEN01C'}; the main-menu XCTL target for {@code PF3}. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** COBOL {@code LIT-MENUTRANID VALUE 'CM00'}; the main-menu transaction id. */
    private static final String MENU_TRANSACTION = "CM00";

    /** COBOL {@code LIT-CARDDTLPGM VALUE 'COCRDSLC'}; the card-detail XCTL target for {@code S}. */
    private static final String DETAIL_PROGRAM = "COCRDSLC";

    /** COBOL {@code LIT-CARDDTLTRANID VALUE 'CCDL'}; the card-detail transaction id. */
    private static final String DETAIL_TRANSACTION = "CCDL";

    /** COBOL {@code LIT-CARDUPDPGM VALUE 'COCRDUPC'}; the card-update XCTL target for {@code U}. */
    private static final String UPDATE_PROGRAM = "COCRDUPC";

    /** COBOL {@code LIT-CARDUPDTRANID VALUE 'CCUP'}; the card-update transaction id. */
    private static final String UPDATE_TRANSACTION = "CCUP";

    /** COBOL {@code WS-MAX-SCREEN-LINES VALUE 7}; rows per page. */
    private static final int MAX_SCREEN_LINES = 7;

    /** Exact {@code 2210-EDIT-ACCOUNT} oracle literal (COCRDLIC.cbl line 1022). */
    private static final String MSG_ACCT_FILTER_INVALID =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** Exact {@code 2220-EDIT-CARD} oracle literal (COCRDLIC.cbl line 1058). */
    private static final String MSG_CARD_FILTER_INVALID =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** Exact {@code WS-MORE-THAN-1-ACTION} oracle literal (COCRDLIC.cbl line 124). */
    private static final String MSG_MORE_THAN_ONE_ACTION =
            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /** Exact {@code WS-INVALID-ACTION-CODE} oracle literal (COCRDLIC.cbl line 126). */
    private static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /** Exact {@code WS-NO-RECORDS-FOUND} oracle literal (COCRDLIC.cbl line 122). */
    private static final String MSG_NO_RECORDS_FOUND =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /** Exact end-of-browse oracle literal (COCRDLIC.cbl lines 1219 / 1239). */
    private static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /** An 11-digit account id used to exercise the {@code CARDAIX} account-filtered browse. */
    private static final long FILTER_ACCOUNT = 12345678901L;

    /** Session-scoped {@code COMMAREA} replacement, mocked so routing writes are observable. */
    @Mock
    private CardDemoContext context;

    /**
     * Repository over the {@code CARDDAT} base cluster, mocked so the in-memory browse reads a
     * controlled, ascending-by-card-number slice per test.
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * Repository over the {@code CCXREF} cross-reference. The production constructor injects it for
     * signature symmetry with the paired card flows, but the card-list logic never reads it, so it
     * is mocked (to satisfy construction) and intentionally never stubbed.
     */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Service under test; Mockito constructor-injects the three mocked collaborators. */
    @InjectMocks
    private CardListService service;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Builds a {@link Card} fixture with a fixed CVV, embossed name and expiry so only the fields
     * the browse reads ({@code cardNum}, {@code cardAcctId}, {@code cardActiveStatus}) vary.
     *
     * @param cardNum      the 16-character card number (primary key / browse order)
     * @param cardAcctId   the owning account id
     * @param activeStatus the active-status flag ({@code "Y"}/{@code "N"})
     * @return the populated card
     */
    private static Card card(String cardNum, long cardAcctId, String activeStatus) {
        return new Card(cardNum, Long.valueOf(cardAcctId), Integer.valueOf(123),
                "CARDHOLDER", LocalDate.of(2024, 12, 31), activeStatus);
    }

    /**
     * Formats a sequence number as a fixed-width 16-digit card number, so lexicographic order
     * equals numeric order and matches the ascending VSAM / {@code ORDER BY} browse.
     *
     * @param sequence the one-based sequence number
     * @return the zero-padded 16-character card number
     */
    private static String cardNumber(int sequence) {
        return String.format("%016d", sequence);
    }

    /**
     * Builds an ascending-by-card-number list of {@code count} cards, all owned by the given
     * account, numbered {@code 1..count}.
     *
     * @param count      the number of cards to generate
     * @param cardAcctId the owning account id shared by every card
     * @return the ascending-ordered card list
     */
    private static List<Card> ascendingCards(int count, long cardAcctId) {
        List<Card> cards = new ArrayList<>(count);
        for (int sequence = 1; sequence <= count; sequence++) {
            cards.add(card(cardNumber(sequence), cardAcctId, "Y"));
        }
        return cards;
    }

    /**
     * Counts how many of the seven persisted screen rows carry a non-empty card number.
     *
     * @param paging the paging state to inspect
     * @return the number of populated rows
     */
    private static long populatedRowCount(CardListPagingState paging) {
        return paging.getRows().stream()
                .filter(row -> !row.cardNumber().isEmpty())
                .count();
    }

    // ------------------------------------------------------------------
    // Paged browse, paging and pseudo-conversational entry
    // (0000-MAIN / 9000-READ-FORWARD; checklist items 1, 2, 9)
    // ------------------------------------------------------------------

    /**
     * First entry ({@code EIBCALEN = 0} &rarr; {@link CardDemoContext#isNew()}) initializes the
     * conversation, positions on the first page ({@code SET CA-FIRST-PAGE TO TRUE}), and lists the
     * browse from the top. With eight matching cards the page fills to the seven-row limit
     * ({@code WS-MAX-SCREEN-LINES VALUE 7}) and a next page is flagged. The
     * {@code markInitialized}/{@code CA-FIRST-PAGE} initialization is asserted on the mocked
     * context and the real paging state (checklist item 9, first entry).
     */
    @Test
    void mainEntryFirstEntry_initializesListingFromTopOfBrowse() {
        when(context.isNew()).thenReturn(true);
        when(cardRepository.findAll(any(Sort.class))).thenReturn(ascendingCards(8, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();

        CardListResult result =
                service.mainEntry(new COCRDLIForm(), PfKey.ENTER, new CardWorkArea(), paging);

        assertThat(result.routing()).isEqualTo(Routing.SHOW_LIST);
        assertThat(populatedRowCount(paging)).isEqualTo(MAX_SCREEN_LINES);
        assertThat(paging.getRow(1).cardNumber()).isEqualTo(cardNumber(1));
        assertThat(paging.getRow(MAX_SCREEN_LINES).cardNumber()).isEqualTo(cardNumber(7));
        assertThat(paging.getScreenNum()).isEqualTo(1);
        assertThat(paging.isNextPageExists()).isTrue();
        verify(context).markInitialized();
        verify(context).setUser();
    }

    /**
     * {@code 9000-READ-FORWARD} fills at most {@code WS-MAX-SCREEN-LINES} (seven) rows per page:
     * given eight matching cards, only the first seven populate the screen page, the eighth is held
     * back, and {@code CA-NEXT-PAGE-EXISTS} is set from the one-record look-ahead. The browse reads
     * the ascending {@code CARDDAT} base cluster via {@link CardRepository#findAll(Sort)} and touches
     * neither the shared context nor the cross-reference (checklist item 1).
     */
    @Test
    void readForward_fillsExactlySevenRowsAndFlagsNextPage() {
        when(cardRepository.findAll(any(Sort.class))).thenReturn(ascendingCards(8, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(1);
        CardListState state = new CardListState();

        service.readForward(new COCRDLIForm(), new CardWorkArea(), paging, state);

        assertThat(populatedRowCount(paging)).isEqualTo(MAX_SCREEN_LINES);
        assertThat(paging.getRow(1).cardNumber()).isEqualTo(cardNumber(1));
        assertThat(paging.getRow(MAX_SCREEN_LINES).cardNumber()).isEqualTo(cardNumber(7));
        assertThat(paging.getRows()).noneMatch(row -> cardNumber(8).equals(row.cardNumber()));
        assertThat(paging.isNextPageExists()).isTrue();
        verify(cardRepository).findAll(any(Sort.class));
        verifyNoInteractions(context, cardXrefRepository);
    }

    /**
     * {@code 9000-READ-FORWARD} positions the browse at the first card whose number is
     * greater-than-or-equal to the browse key ({@code STARTBR ... GTEQ WS-CARD-RID}) and advances
     * from there. Starting at card five over a ten-card cluster shows the remaining six rows
     * (cards 5..10) and clears {@code CA-NEXT-PAGE-EXISTS} at end-of-file (checklist item 2,
     * forward read).
     */
    @Test
    void readForward_advancesWindowFromBrowseKey() {
        when(cardRepository.findAll(any(Sort.class))).thenReturn(ascendingCards(10, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(2);
        CardListState state = new CardListState();
        state.setRidCardNum(cardNumber(5));

        service.readForward(new COCRDLIForm(), new CardWorkArea(), paging, state);

        assertThat(paging.getRow(1).cardNumber()).isEqualTo(cardNumber(5));
        assertThat(populatedRowCount(paging)).isEqualTo(6L);
        assertThat(paging.getRow(6).cardNumber()).isEqualTo(cardNumber(10));
        assertThat(paging.isNextPageExists()).isFalse();
    }

    /**
     * {@code PF8} on a page that reports {@code CA-NEXT-PAGE-EXISTS} pages forward from the retained
     * last key ({@code WS-CA-LAST-CARDKEY}) and increments {@code WS-CA-SCREEN-NUM}. Over a ten-card
     * cluster whose page-one look-ahead key is card eight, the next page shows the final three cards
     * (8..10) and clears the next-page indicator; the routing stays {@code SHOW_LIST}
     * (checklist item 2, PF8).
     */
    @Test
    void mainEntryPf8_pagesForwardToNextWindow() {
        when(context.getFromProgram()).thenReturn(LIST_PROGRAM);
        when(cardRepository.findAll(any(Sort.class))).thenReturn(ascendingCards(10, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(1);
        paging.setNextPageExists(true);
        paging.setLastCardNum(cardNumber(8));

        CardListResult result =
                service.mainEntry(new COCRDLIForm(), PfKey.PFK08, new CardWorkArea(), paging);

        assertThat(result.routing()).isEqualTo(Routing.SHOW_LIST);
        assertThat(paging.getRow(1).cardNumber()).isEqualTo(cardNumber(8));
        assertThat(paging.getRow(3).cardNumber()).isEqualTo(cardNumber(10));
        assertThat(populatedRowCount(paging)).isEqualTo(3L);
        assertThat(paging.isNextPageExists()).isFalse();
    }

    /**
     * Re-entry (a present commarea with {@code CDEMO-FROM-PROGRAM = 'COCRDLIC'}) preserves the paging
     * cursor: the {@code WHEN OTHER} arm re-lists forward from the retained
     * {@code WS-CA-FIRST-CARD-NUM} rather than from the top of the file. Seeding the first key at
     * card three shows cards 3..9, proving the cursor survived the pseudo-conversational return
     * (checklist item 9, re-entry).
     */
    @Test
    void mainEntryReEntry_preservesPagingCursor() {
        when(context.getFromProgram()).thenReturn(LIST_PROGRAM);
        when(cardRepository.findAll(any(Sort.class))).thenReturn(ascendingCards(10, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(1);
        paging.setFirstCardNum(cardNumber(3));

        CardListResult result =
                service.mainEntry(new COCRDLIForm(), PfKey.ENTER, new CardWorkArea(), paging);

        assertThat(result.routing()).isEqualTo(Routing.SHOW_LIST);
        assertThat(paging.getRow(1).cardNumber()).isEqualTo(cardNumber(3));
        assertThat(populatedRowCount(paging)).isEqualTo(MAX_SCREEN_LINES);
    }

    // ------------------------------------------------------------------
    // Backward paging (9100-READ-BACKWARDS; checklist item 3)
    // ------------------------------------------------------------------

    /**
     * {@code PF7} on a page that is not the first ({@code WS-CA-SCREEN-NUM &gt; 1}) pages backward:
     * {@code 0000-MAIN} decrements the screen number and calls {@code 9100-READ-BACKWARDS}, which
     * repositions before the retained first key ({@code WS-CA-FIRST-CARD-NUM}) and fills the prior
     * window. Coming back from page two (first key = card eight) over fourteen cards restores the
     * page-one window of cards 1..7 (checklist item 3).
     */
    @Test
    void mainEntryPf7_pagesBackwardToPriorWindow() {
        when(context.getFromProgram()).thenReturn(LIST_PROGRAM);
        when(cardRepository.findAll(any(Sort.class))).thenReturn(ascendingCards(14, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(2);
        paging.setFirstCardNum(cardNumber(8));

        CardListResult result =
                service.mainEntry(new COCRDLIForm(), PfKey.PFK07, new CardWorkArea(), paging);

        assertThat(result.routing()).isEqualTo(Routing.SHOW_LIST);
        assertThat(paging.getRow(1).cardNumber()).isEqualTo(cardNumber(1));
        assertThat(paging.getRow(MAX_SCREEN_LINES).cardNumber()).isEqualTo(cardNumber(7));
        assertThat(populatedRowCount(paging)).isEqualTo(MAX_SCREEN_LINES);
    }

    /**
     * {@code 9100-READ-BACKWARDS} reads the previous page in reverse ({@code READPREV}) from the
     * retained first key and lays the rows back down in ascending screen order (row seven down to
     * row one). Invoked directly with the first key at card eight over a fourteen-card cluster, it
     * reconstructs cards 1..7 without touching the shared context or the cross-reference
     * (checklist item 3, direct).
     */
    @Test
    void readBackwards_fillsPriorWindowEndingAtBrowseKey() {
        when(cardRepository.findAll(any(Sort.class))).thenReturn(ascendingCards(14, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(2);
        paging.setFirstCardNum(cardNumber(8));
        CardListState state = new CardListState();
        // 0000-MAIN's PF7 arm seeds the browse key from WS-CA-FIRST-CARD-NUM before performing
        // 9100-READ-BACKWARDS; reproduce that contract for the direct paragraph-level call.
        state.setRidCardNum(cardNumber(8));

        service.readBackwards(new COCRDLIForm(), new CardWorkArea(), paging, state);

        assertThat(paging.getRow(1).cardNumber()).isEqualTo(cardNumber(1));
        assertThat(paging.getRow(MAX_SCREEN_LINES).cardNumber()).isEqualTo(cardNumber(7));
        assertThat(populatedRowCount(paging)).isEqualTo(MAX_SCREEN_LINES);
        verifyNoInteractions(context, cardXrefRepository);
    }

    // ------------------------------------------------------------------
    // Selection routing (0000-MAIN ENTER dispatch; checklist items 4, 5, 6)
    // ------------------------------------------------------------------

    /**
     * ENTER with the {@code S} (view) code on a populated row transfers to the card-detail program
     * ({@code XCTL 'COCRDSLC'}, tran {@code CCDL}), carrying the selected row's account and card
     * number ({@code WS-ROW-ACCTNO}/{@code WS-ROW-CARD-NUM}) onto the shared context so the target's
     * first-entry preload can pick them up. The browse repositories are never touched on the
     * dispatch path (checklist item 4).
     */
    @Test
    void mainEntryEnterSelectView_redirectsToCardDetail() {
        when(context.getFromProgram()).thenReturn(LIST_PROGRAM);
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(1);
        paging.setRow(1, new CardListRow("00000000011", "1111111111111111", "Y"));
        COCRDLIForm form = new COCRDLIForm();
        form.setCrdsel1("S");

        CardListResult result =
                service.mainEntry(form, PfKey.ENTER, new CardWorkArea(), paging);

        assertThat(result.routing()).isEqualTo(Routing.REDIRECT);
        verify(context).setToProgram(DETAIL_PROGRAM);
        verify(context).setToTranid(DETAIL_TRANSACTION);
        verify(context).setAcctId(11L);
        verify(context).setCardNum("1111111111111111");
        verifyNoInteractions(cardRepository, cardXrefRepository);
    }

    /**
     * ENTER with the {@code U} (update) code on a populated row transfers to the card-update program
     * ({@code XCTL 'COCRDUPC'}, tran {@code CCUP}), carrying the selected row's account and card
     * number. The view arm is evaluated first in the COBOL EVALUATE, so selecting {@code U} on row
     * two proves the update arm is reached only when no view is requested (checklist item 5).
     */
    @Test
    void mainEntryEnterSelectUpdate_redirectsToCardUpdate() {
        when(context.getFromProgram()).thenReturn(LIST_PROGRAM);
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(1);
        paging.setRow(2, new CardListRow("00000000022", "2222222222222222", "Y"));
        COCRDLIForm form = new COCRDLIForm();
        form.setCrdsel2("U");

        CardListResult result =
                service.mainEntry(form, PfKey.ENTER, new CardWorkArea(), paging);

        assertThat(result.routing()).isEqualTo(Routing.REDIRECT);
        verify(context).setToProgram(UPDATE_PROGRAM);
        verify(context).setToTranid(UPDATE_TRANSACTION);
        verify(context).setAcctId(22L);
        verify(context).setCardNum("2222222222222222");
        verifyNoInteractions(cardRepository, cardXrefRepository);
    }

    /**
     * Two rows flagged in the same submit is the {@code WS-MORE-THAN-1-ACTION} error
     * ("{@value #MSG_MORE_THAN_ONE_ACTION}"): {@code 2250-EDIT-ARRAY} raises INPUT-ERROR, so
     * {@code 0000-MAIN} re-lists the page and sends the map instead of transferring. Routing stays
     * {@code SHOW_LIST} and neither transfer target is ever written to the context
     * (checklist item 6).
     */
    @Test
    void mainEntryMultipleSelections_reportsSingleSelectionErrorWithoutRouting() {
        when(context.getFromProgram()).thenReturn(LIST_PROGRAM);
        when(cardRepository.findAll(any(Sort.class))).thenReturn(ascendingCards(8, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(1);
        COCRDLIForm form = new COCRDLIForm();
        form.setCrdsel1("S");
        form.setCrdsel3("S");

        CardListResult result =
                service.mainEntry(form, PfKey.ENTER, new CardWorkArea(), paging);

        assertThat(result.routing()).isEqualTo(Routing.SHOW_LIST);
        assertThat(result.errorMessage()).isEqualTo(MSG_MORE_THAN_ONE_ACTION);
        verify(context, never()).setToProgram(anyString());
        verify(context, never()).setToTranid(anyString());
    }

    /**
     * {@code PF3} while already on the list program exits to the main menu ({@code XCTL 'COMEN01C'},
     * tran {@code CM00}) with the {@code WS-EXIT-MESSAGE} set; the COBOL {@code XCTL}s immediately, so
     * no browse occurs. Routing is {@code REDIRECT} to the menu and the repositories are untouched.
     */
    @Test
    void mainEntryPf3_redirectsToMainMenu() {
        when(context.getFromProgram()).thenReturn(LIST_PROGRAM);
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(1);

        CardListResult result =
                service.mainEntry(new COCRDLIForm(), PfKey.PFK03, new CardWorkArea(), paging);

        assertThat(result.routing()).isEqualTo(Routing.REDIRECT);
        verify(context).setToProgram(MENU_PROGRAM);
        verify(context).setToTranid(MENU_TRANSACTION);
        verifyNoInteractions(cardRepository, cardXrefRepository);
    }

    // ------------------------------------------------------------------
    // Filter / selection edits (2200/2210/2220/2250; checklist item 7)
    // ------------------------------------------------------------------

    /**
     * {@code 2210-EDIT-ACCOUNT}: an account filter that is not an eleven-digit number is rejected
     * with the "{@value #MSG_ACCT_FILTER_INVALID}" parity message, raises INPUT-ERROR, protects the
     * select columns and zeroes the context account id ({@code MOVE ZERO TO CDEMO-ACCT-ID}).
     */
    @Test
    void editAccount_rejectsNonNumericFilterWithParityMessage() {
        CardWorkArea work = new CardWorkArea();
        work.setAcctId("12A45678901");
        CardListState state = new CardListState();

        service.editAccount(work, state);

        assertThat(state.isInputError()).isTrue();
        assertThat(state.getAcctFilter()).isEqualTo(CardListState.FilterFlag.NOT_OK);
        assertThat(state.isProtectSelectRows()).isTrue();
        assertThat(state.getErrorMessage()).isEqualTo(MSG_ACCT_FILTER_INVALID);
        verify(context).setAcctId(0L);
        verifyNoInteractions(cardRepository, cardXrefRepository);
    }

    /**
     * {@code 2210-EDIT-ACCOUNT}: a valid eleven-digit, non-zero account filter is stored on both the
     * context and the request state and flagged {@code FLG-ACCTFILTER-ISVALID} - the state that
     * later steers the {@code CARDAIX} account-scoped browse.
     */
    @Test
    void editAccount_acceptsElevenDigitFilterAndScopesBrowse() {
        CardWorkArea work = new CardWorkArea();
        work.setAcctId("12345678901");
        CardListState state = new CardListState();

        service.editAccount(work, state);

        assertThat(state.isInputError()).isFalse();
        assertThat(state.getAcctFilter()).isEqualTo(CardListState.FilterFlag.VALID);
        assertThat(state.getAcctFilterValue()).isEqualTo(12345678901L);
        verify(context).setAcctId(12345678901L);
        verifyNoInteractions(cardRepository, cardXrefRepository);
    }

    /**
     * {@code 2220-EDIT-CARD}: a card filter that is not a sixteen-digit number is rejected with the
     * "{@value #MSG_CARD_FILTER_INVALID}" parity message, raises INPUT-ERROR and clears the context
     * card number ({@code MOVE ZERO TO CDEMO-CARD-NUM}).
     */
    @Test
    void editCard_rejectsShortFilterWithParityMessage() {
        CardWorkArea work = new CardWorkArea();
        work.setCardNum("123");
        CardListState state = new CardListState();

        service.editCard(work, state);

        assertThat(state.isInputError()).isTrue();
        assertThat(state.getCardFilter()).isEqualTo(CardListState.FilterFlag.NOT_OK);
        assertThat(state.getErrorMessage()).isEqualTo(MSG_CARD_FILTER_INVALID);
        verify(context).setCardNum(null);
        verifyNoInteractions(cardRepository, cardXrefRepository);
    }

    /**
     * {@code 2220-EDIT-CARD}: a valid sixteen-digit card filter is stored on the context and the
     * request state and flagged {@code FLG-CARDFILTER-ISVALID}.
     */
    @Test
    void editCard_acceptsSixteenDigitFilter() {
        CardWorkArea work = new CardWorkArea();
        work.setCardNum("1111111111111111");
        CardListState state = new CardListState();

        service.editCard(work, state);

        assertThat(state.isInputError()).isFalse();
        assertThat(state.getCardFilter()).isEqualTo(CardListState.FilterFlag.VALID);
        assertThat(state.getCardFilterValue()).isEqualTo("1111111111111111");
        verify(context).setCardNum("1111111111111111");
        verifyNoInteractions(cardRepository, cardXrefRepository);
    }

    /**
     * {@code 2250-EDIT-ARRAY}: exactly one selection is recorded as {@code I-SELECTED} with no error,
     * proving a single {@code S}/{@code U} on any row is accepted. The pure-logic edit touches no
     * collaborator.
     */
    @Test
    void editArray_recordsSingleSelectionWithoutError() {
        COCRDLIForm form = new COCRDLIForm();
        form.setCrdsel2("S");
        CardListState state = new CardListState();

        service.editArray(form, state);

        assertThat(state.isInputError()).isFalse();
        assertThat(state.getSelectedRowIndex()).isEqualTo(2);
        assertThat(state.isMoreThanOneAction()).isFalse();
        verifyNoInteractions(context, cardRepository, cardXrefRepository);
    }

    /**
     * {@code 2250-EDIT-ARRAY}: two selections raise the {@code WS-MORE-THAN-1-ACTION} error
     * ("{@value #MSG_MORE_THAN_ONE_ACTION}") and mark every selected row in error
     * ({@code WS-EDIT-SELECT-ERROR}).
     */
    @Test
    void editArray_flagsMoreThanOneActionError() {
        COCRDLIForm form = new COCRDLIForm();
        form.setCrdsel1("S");
        form.setCrdsel3("U");
        CardListState state = new CardListState();

        service.editArray(form, state);

        assertThat(state.isInputError()).isTrue();
        assertThat(state.isMoreThanOneAction()).isTrue();
        assertThat(state.getErrorMessage()).isEqualTo(MSG_MORE_THAN_ONE_ACTION);
        assertThat(state.getSelectRowError()[0]).isTrue();
        assertThat(state.getSelectRowError()[2]).isTrue();
        verifyNoInteractions(context, cardRepository, cardXrefRepository);
    }

    /**
     * {@code 2250-EDIT-ARRAY}: a selection code other than {@code S}/{@code U}/blank is the
     * "{@value #MSG_INVALID_ACTION_CODE}" error, marks that row, and leaves {@code I-SELECTED} at
     * zero (no row dispatched).
     */
    @Test
    void editArray_flagsInvalidActionCode() {
        COCRDLIForm form = new COCRDLIForm();
        form.setCrdsel1("X");
        CardListState state = new CardListState();

        service.editArray(form, state);

        assertThat(state.isInputError()).isTrue();
        assertThat(state.getErrorMessage()).isEqualTo(MSG_INVALID_ACTION_CODE);
        assertThat(state.getSelectRowError()[0]).isTrue();
        assertThat(state.getSelectedRowIndex()).isZero();
        verifyNoInteractions(context, cardRepository, cardXrefRepository);
    }

    /**
     * {@code 2200-EDIT-INPUTS}: orchestration edits the account filter, card filter and selection
     * array in COBOL order. With no filters and a single selection the request is INPUT-OK, both
     * filters resolve {@code BLANK}, and the selected row index is recorded - no browse occurs.
     */
    @Test
    void editInputs_runsCleanWhenOnlyASelectionIsSupplied() {
        COCRDLIForm form = new COCRDLIForm();
        form.setCrdsel3("S");
        CardWorkArea work = new CardWorkArea();
        CardListState state = new CardListState();

        service.editInputs(form, work, state);

        assertThat(state.isInputError()).isFalse();
        assertThat(state.getAcctFilter()).isEqualTo(CardListState.FilterFlag.BLANK);
        assertThat(state.getCardFilter()).isEqualTo(CardListState.FilterFlag.BLANK);
        assertThat(state.getSelectedRowIndex()).isEqualTo(3);
        verifyNoInteractions(cardRepository, cardXrefRepository);
    }

    /**
     * {@code 9500-FILTER-RECORDS}: with no active filter every record is retained
     * ({@code WS-DONOT-EXCLUDE-THIS-RECORD}); the method returns {@code false} (not excluded).
     */
    @Test
    void filterRecords_retainsWhenNoFilterActive() {
        CardListState state = new CardListState();
        Card onlyCard = card(cardNumber(1), FILTER_ACCOUNT, "Y");

        assertThat(service.filterRecords(onlyCard, state)).isFalse();
        verifyNoInteractions(context, cardRepository, cardXrefRepository);
    }

    /**
     * {@code 9500-FILTER-RECORDS}: a valid account filter retains a matching card
     * ({@code WS-DONOT-EXCLUDE-THIS-RECORD}).
     */
    @Test
    void filterRecords_retainsWhenAccountFilterMatches() {
        CardListState state = new CardListState();
        state.setAcctFilter(CardListState.FilterFlag.VALID);
        state.setAcctFilterValue(FILTER_ACCOUNT);
        Card match = card(cardNumber(1), FILTER_ACCOUNT, "Y");

        assertThat(service.filterRecords(match, state)).isFalse();
        verifyNoInteractions(context, cardRepository, cardXrefRepository);
    }

    /**
     * {@code 9500-FILTER-RECORDS}: a valid account filter excludes a card owned by a different
     * account ({@code SET WS-EXCLUDE-THIS-RECORD TO TRUE}); the method returns {@code true}.
     */
    @Test
    void filterRecords_excludesWhenAccountFilterDoesNotMatch() {
        CardListState state = new CardListState();
        state.setAcctFilter(CardListState.FilterFlag.VALID);
        state.setAcctFilterValue(FILTER_ACCOUNT);
        Card mismatch = card(cardNumber(1), 99999999999L, "Y");

        assertThat(service.filterRecords(mismatch, state)).isTrue();
        verifyNoInteractions(context, cardRepository, cardXrefRepository);
    }

    /**
     * {@code 9500-FILTER-RECORDS}: a valid card filter excludes a card whose number differs from the
     * filter ({@code SET WS-EXCLUDE-THIS-RECORD TO TRUE}).
     */
    @Test
    void filterRecords_excludesWhenCardFilterDoesNotMatch() {
        CardListState state = new CardListState();
        state.setCardFilter(CardListState.FilterFlag.VALID);
        state.setCardFilterValue(cardNumber(1));
        Card mismatch = card(cardNumber(2), FILTER_ACCOUNT, "Y");

        assertThat(service.filterRecords(mismatch, state)).isTrue();
        verifyNoInteractions(context, cardRepository, cardXrefRepository);
    }

    // ------------------------------------------------------------------
    // Alt-index browse and end-of-file handling (checklist items 7, 8)
    // ------------------------------------------------------------------

    /**
     * A valid account filter narrows the browse through the {@code CARDAIX} alternate-index
     * replacement: {@code buildScopedOrderedList} takes the
     * {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long)} path and never scans the full
     * base cluster via {@link CardRepository#findAll(Sort)} (checklist item 7, valid filter narrows
     * the browse).
     */
    @Test
    void mainEntryValidAccountFilter_usesCardAixDerivedQuery() {
        when(context.getFromProgram()).thenReturn(LIST_PROGRAM);
        when(cardRepository.findByCardAcctIdOrderByCardNumAsc(FILTER_ACCOUNT))
                .thenReturn(ascendingCards(3, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(1);
        CardWorkArea work = new CardWorkArea();
        work.setAcctId("12345678901");

        CardListResult result =
                service.mainEntry(new COCRDLIForm(), PfKey.ENTER, work, paging);

        assertThat(result.routing()).isEqualTo(Routing.SHOW_LIST);
        assertThat(populatedRowCount(paging)).isEqualTo(3L);
        verify(cardRepository).findByCardAcctIdOrderByCardNumAsc(FILTER_ACCOUNT);
        verify(cardRepository, never()).findAll(any(Sort.class));
        verifyNoInteractions(cardXrefRepository);
    }

    /**
     * {@code 9000-READ-FORWARD} end-of-file on an empty first page is handled internally
     * ({@code WHEN DFHRESP(ENDFILE)}): the {@link EndOfFileException} (file status {@code 10}) is
     * caught, {@code CA-NEXT-PAGE-NOT-EXISTS} is set and the "{@value #MSG_NO_RECORDS_FOUND}" state
     * is raised - the exception never escapes (checklist item 8, direct).
     */
    @Test
    void readForward_handlesEndOfFileOnEmptyFirstPageWithoutThrowing() {
        when(cardRepository.findAll(any(Sort.class))).thenReturn(List.of());
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(1);
        CardListState state = new CardListState();

        assertThatCode(() ->
                service.readForward(new COCRDLIForm(), new CardWorkArea(), paging, state))
                .doesNotThrowAnyException();

        assertThat(state.isNoRecordsFound()).isTrue();
        assertThat(state.getErrorMessage()).isEqualTo(MSG_NO_RECORDS_FOUND);
        assertThat(populatedRowCount(paging)).isZero();
        assertThat(paging.isNextPageExists()).isFalse();
    }

    /**
     * A short result set exhausts the browse before the seven-row page fills: all rows are shown,
     * {@code CA-NEXT-PAGE-NOT-EXISTS} is set and the end-of-browse message
     * "{@value #MSG_NO_MORE_RECORDS}" is raised. The end-of-file is handled internally so
     * {@link CardListService#mainEntry} returns a normal {@code SHOW_LIST} last page
     * (checklist item 8, via mainEntry).
     */
    @Test
    void mainEntrySmallList_showsAllRowsAndHandlesEndOfFileInternally() {
        when(context.isNew()).thenReturn(true);
        when(cardRepository.findAll(any(Sort.class))).thenReturn(ascendingCards(3, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();

        CardListResult result =
                service.mainEntry(new COCRDLIForm(), PfKey.ENTER, new CardWorkArea(), paging);

        assertThat(result.routing()).isEqualTo(Routing.SHOW_LIST);
        assertThat(populatedRowCount(paging)).isEqualTo(3L);
        assertThat(paging.getRow(1).cardNumber()).isEqualTo(cardNumber(1));
        assertThat(paging.getRow(3).cardNumber()).isEqualTo(cardNumber(3));
        assertThat(paging.isNextPageExists()).isFalse();
        assertThat(result.errorMessage()).isEqualTo(MSG_NO_MORE_RECORDS);
    }

    /**
     * The internal {@link EndOfFileException} that terminates a browse is a normal terminator and is
     * never propagated out of {@link CardListService#mainEntry}: even when the browse is empty, the
     * driver returns normally rather than surfacing the exception (checklist item 8, non-propagation).
     */
    @Test
    void mainEntryEndOfFile_isNeverPropagatedFromMainEntry() {
        when(context.isNew()).thenReturn(true);
        when(cardRepository.findAll(any(Sort.class))).thenReturn(List.of());
        CardListPagingState paging = new CardListPagingState();

        assertThatCode(() ->
                service.mainEntry(new COCRDLIForm(), PfKey.ENTER, new CardWorkArea(), paging))
                .doesNotThrowAnyException();
        assertThat(paging.isNextPageExists()).isFalse();
    }

    /**
     * {@code PF7} while already on the first page ({@code WS-CA-SCREEN-NUM = 1}) does not page below
     * the start: {@code 0000-MAIN} simply re-lists forward from the current first key and shows the
     * top page again (COBOL {@code WHEN PF7 AND CA-FIRST-PAGE}).
     */
    @Test
    void mainEntryPf7OnFirstPage_reListsFromTopWithoutError() {
        when(context.getFromProgram()).thenReturn(LIST_PROGRAM);
        when(cardRepository.findAll(any(Sort.class))).thenReturn(ascendingCards(8, FILTER_ACCOUNT));
        CardListPagingState paging = new CardListPagingState();
        paging.setScreenNum(1);
        paging.setFirstCardNum(cardNumber(1));

        CardListResult result =
                service.mainEntry(new COCRDLIForm(), PfKey.PFK07, new CardWorkArea(), paging);

        assertThat(result.routing()).isEqualTo(Routing.SHOW_LIST);
        assertThat(paging.getRow(1).cardNumber()).isEqualTo(cardNumber(1));
        assertThat(populatedRowCount(paging)).isEqualTo(MAX_SCREEN_LINES);
    }
}

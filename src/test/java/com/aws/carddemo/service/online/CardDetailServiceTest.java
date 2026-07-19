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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COCRDSLForm;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.CardRepository;
import com.aws.carddemo.service.online.CardDetailService.CardDetailResult;
import com.aws.carddemo.service.online.CardDetailService.MessageSeverity;
import com.aws.carddemo.service.online.CardDetailService.RoutingAction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link CardDetailService}, the Java migration of the CICS COBOL
 * program {@code COCRDSLC} (the AWS CardDemo "Credit Card Detail / View" screen).
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COCRDSLC.cbl} &mdash; program
 * {@code COCRDSLC}, CICS transaction id {@code CCDL}, BMS mapset {@code COCRDSL} / map
 * {@code CCRDSLA}. This is a <em>read-only</em> view screen: it validates the account/card search
 * filters, reads the card store, populates the detail fields and produces a message; it never
 * writes the card store. These tests assert control-flow and message parity with the program's
 * numbered paragraphs:</p>
 * <ul>
 *   <li>{@code 2200-EDIT-MAP-INPUTS} &rarr; {@code editMapInputs} (filter normalization and the
 *       cross-field "No input received" rule)</li>
 *   <li>{@code 2210-EDIT-ACCOUNT} &rarr; {@code editAccount} (the non-zero 11-digit account rule)</li>
 *   <li>{@code 2220-EDIT-CARD} &rarr; {@code editCard} (the 16-digit card rule)</li>
 *   <li>{@code 9000-READ-DATA} &rarr; {@code readData} (performs only the by-card-number read)</li>
 *   <li>{@code 9100-GETCARD-BYACCTCARD} &rarr; {@code getCardByAccountCard} (the primary-key
 *       {@code READ CARDDAT}; the modern {@link CardRepository#findById(Object)})</li>
 *   <li>{@code 9150-GETCARD-BYACCT} &rarr; {@code getCardByAccount} (the {@code CARDAIX}
 *       alternate-index read; {@link CardRepository#findByCardAcctId(Long)})</li>
 * </ul>
 *
 * <h2>COCRDSEC / CDV1 traceability note (100%-coverage requirement, AAP &sect;0.4.1, &sect;0.6.10)</h2>
 * <p>The card-detail <em>security</em> variant {@code COCRDSEC} (CICS transaction {@code CDV1}) has
 * <strong>no</strong> COBOL {@code .cbl} source: it exists only in the CICS resource-definition file
 * {@code legacy/csd/CARDDEMO.CSD} as {@code DEFINE PROGRAM(COCRDSEC)} / {@code DEFINE
 * TRANSACTION(CDV1)}. In the Spring Boot migration it is <strong>not</strong> a standalone service;
 * it is realized as URL/method authorization in {@code com.aws.carddemo.config.SecurityConfig} (the
 * modern equivalent of the RACF/CICS transaction-level protection {@code CDV1} provided over the
 * card-detail screen). It therefore has no service unit test of its own; this note records the
 * mapping so the bidirectional traceability matrix reaches 100% coverage with no gaps. The 1:1 code
 * source for the service under test remains {@code COCRDSLC.cbl}.</p>
 *
 * <h2>Documented COBOL quirks asserted here (AAP &sect;0.7.1 &mdash; parity including quirks)</h2>
 * <ul>
 *   <li><b>Account/card validation literals.</b> The copybook 88-levels
 *       {@code SEARCHED-ACCT-ZEROES}/{@code SEARCHED-ACCT-NOT-NUMERIC}
 *       ({@code "Account number must be a non zero 11 digit number"}) and
 *       {@code SEARCHED-CARD-NOT-NUMERIC}
 *       ({@code "Card number if supplied must be a 16 digit number"}) are declared but
 *       <em>never {@code SET}</em> by {@code COCRDSLC}. At run time {@code 2210-EDIT-ACCOUNT} moves
 *       the inline literal {@code "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"} and
 *       {@code 2220-EDIT-CARD} moves {@code "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"}.
 *       {@link #constants_preserveVerbatimEightyEightLevelLiterals()} asserts the declared literals
 *       verbatim, while the behavioral tests assert the literals actually emitted.</li>
 *   <li><b>Dead-code alternate-index read.</b> {@code 9150-GETCARD-BYACCT} is defined but never
 *       performed by any control path ({@code 9000-READ-DATA} performs only
 *       {@code 9100-GETCARD-BYACCTCARD}), so {@code getCardByAccount} is unreachable through
 *       {@link CardDetailService#mainEntry(COCRDSLForm, PfKey)}. It is nonetheless exercised here
 *       (via reflection) to cover the {@code CARDAIX} read path and its distinct
 *       {@code "Did not find this account in cards database"} miss message, preserving the 1:1
 *       traceability of the migrated paragraph.</li>
 * </ul>
 *
 * <h2>Test tier</h2>
 * <p>This is a strict-stubs pure-Mockito unit test: {@link MockitoExtension} with {@link Mock}
 * collaborators and an {@link InjectMocks} service. It starts no Spring context, opens no database
 * connection, and loads no persistence runtime &mdash; it verifies the service's business logic in
 * complete isolation. The presentation paragraphs ({@code 1000-SEND-MAP}, {@code 1100-SCREEN-INIT},
 * {@code 1200-SETUP-SCREEN-VARS}, {@code 1400-SEND-SCREEN}, {@code 2100-RECEIVE-MAP}) are owned by
 * the paired {@code CardController} and are therefore not exercised here.</p>
 */
@ExtendWith(MockitoExtension.class)
class CardDetailServiceTest {

    // ---------------------------------------------------------------------------------------------
    // Verbatim COBOL literals (independent oracle). Kept in the test so assertions do not merely
    // mirror the production constants; each value is transcribed from legacy/cbl/COCRDSLC.cbl.
    // ---------------------------------------------------------------------------------------------

    /** {@code FOUND-CARDS-FOR-ACCOUNT} (line 129-130). The THREE leading spaces are significant. */
    private static final String MSG_DISPLAYING_DETAILS = "   Displaying requested details";

    /** {@code WS-PROMPT-FOR-INPUT} - the initial info prompt shown on an empty search screen. */
    private static final String MSG_PROMPT_FOR_INPUT = "Please enter Account and Card Number";

    /** {@code WS-PROMPT-FOR-ACCT} (line 139) - account filter left blank. */
    private static final String MSG_ACCT_NOT_PROVIDED = "Account number not provided";

    /** {@code WS-PROMPT-FOR-CARD} (line 141) - card filter left blank. */
    private static final String MSG_CARD_NOT_PROVIDED = "Card number not provided";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED} (line 143) - neither filter supplied. */
    private static final String MSG_NO_INPUT = "No input received";

    /** {@code SEARCHED-ACCT-*} 88-level (line 145/147) - declared but never emitted at run time. */
    private static final String MSG_SEARCHED_ACCT = "Account number must be a non zero 11 digit number";

    /** {@code SEARCHED-CARD-NOT-NUMERIC} 88-level (line 149) - declared but never emitted. */
    private static final String MSG_SEARCHED_CARD = "Card number if supplied must be a 16 digit number";

    /** Inline literal moved by {@code 2210-EDIT-ACCOUNT} (line 670) for a non-numeric account. */
    private static final String MSG_ACCT_FILTER = "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** Inline literal moved by {@code 2220-EDIT-CARD} (line 711) for a non-numeric card. */
    private static final String MSG_CARD_FILTER = "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** {@code DID-NOT-FIND-ACCTCARD-COMBO} (line 154) - primary (by-card) read miss. */
    private static final String MSG_DID_NOT_FIND_CARDS = "Did not find cards for this search condition";

    /** {@code DID-NOT-FIND-ACCT-IN-CARDXREF} (line 152) - alternate-index (by-account) read miss. */
    private static final String MSG_DID_NOT_FIND_ACCT = "Did not find this account in cards database";

    /** {@code WS-EXIT-MESSAGE} - significant text set when PF3 exits. */
    private static final String MSG_EXIT = "PF03 pressed.Exiting";

    /** Abend text for the {@code WHEN OTHER} branch of {@code 0000-MAIN}. */
    private static final String MSG_ABEND = "UNEXPECTED DATA SCENARIO";

    // --- Program / transaction identifiers (COBOL WS-LITERALS) -----------------------------------

    /** This program's CICS transaction id ({@code LIT-THISTRANID}). */
    private static final String THIS_TRANID = "CCDL";

    /** This program's name ({@code LIT-THISPGM}). */
    private static final String THIS_PGM = "COCRDSLC";

    /** Main-menu program ({@code LIT-MENUPGM}) - the from-menu origin and PF3 default target. */
    private static final String MENU_PGM = "COMEN01C";

    /** Main-menu transaction id ({@code LIT-MENUTRANID}) - the PF3 default target tran. */
    private static final String MENU_TRANID = "CM00";

    /** Credit-card list program ({@code LIT-CCLISTPGM}) - the "arrived from selection" origin. */
    private static final String CARDLIST_PGM = "COCRDLIC";

    /** Credit-card list transaction id - a representative known caller for the PF3 return test. */
    private static final String CARDLIST_TRANID = "CCLI";

    // --- Valid fixture keys ----------------------------------------------------------------------

    /** A well-formed 11-digit, non-zero account filter. */
    private static final String VALID_ACCT = "12345678901";

    /** A well-formed 16-digit card filter (also the {@link CardRepository#findById(Object)} key). */
    private static final String VALID_CARD = "1234567890123456";

    /** The numeric ({@code PIC 9(11)}) view of {@link #VALID_ACCT}, used for the alt-index read. */
    private static final long VALID_ACCT_NUM = 12_345_678_901L;

    /** Fully-qualified binary name of the private nested {@code ProcessingState} (for reflection). */
    private static final String PROCESSING_STATE_FQN =
            "com.aws.carddemo.service.online.CardDetailService$ProcessingState";

    // ---------------------------------------------------------------------------------------------
    // Collaborators
    // ---------------------------------------------------------------------------------------------

    /** Session context (the COMMAREA {@code COCOM01Y} replacement); mocked. */
    @Mock
    private CardDemoContext context;

    /** Card repository ({@code CARDDAT} access); mocked so no database is required. */
    @Mock
    private CardRepository cardRepository;

    /** Service under test, wired by constructor injection with the two mocks above. */
    @InjectMocks
    private CardDetailService service;

    // ---------------------------------------------------------------------------------------------
    // Fixtures & helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a real {@link COCRDSLForm} carrying the supplied account/card search filters, mirroring
     * the 3270 map {@code CCRDSLA} the COBOL program receives ({@code ACCTSIDI}/{@code CARDSIDI}).
     *
     * @param acctFilter the account-id filter value (may be {@code null})
     * @param cardFilter the card-number filter value (may be {@code null})
     * @return a populated form fixture
     */
    private static COCRDSLForm formWith(String acctFilter, String cardFilter) {
        COCRDSLForm form = new COCRDSLForm();
        form.setAcctsid(acctFilter);
        form.setCardsid(cardFilter);
        return form;
    }

    /**
     * Builds a real {@link Card} fixture with a December 2027 expiry, matching the
     * {@code CARD-RECORD} layout the by-card read returns on a hit.
     *
     * @return a fully-populated card fixture
     */
    private static Card sampleCard() {
        return new Card(VALID_CARD, VALID_ACCT_NUM, 123, "JOHN Q PUBLIC",
                LocalDate.of(2027, 12, 31), "Y");
    }

    /**
     * Asserts the read-only guarantee: the service never mutates the card store. Covers every write
     * entry point exposed by {@link org.springframework.data.jpa.repository.JpaRepository} that could
     * persist or remove a card (parity checklist item 6).
     */
    private void assertNoCardWrites() {
        verify(cardRepository, never()).save(any());
        verify(cardRepository, never()).saveAll(any());
        verify(cardRepository, never()).delete(any());
        verify(cardRepository, never()).deleteById(any());
        verify(cardRepository, never()).deleteAll();
    }

    /**
     * Invokes the private, unreachable-at-run-time {@code getCardByAccount} paragraph
     * ({@code 9150-GETCARD-BYACCT}) via reflection, constructing the private nested
     * {@code ProcessingState} it requires. Any exception the paragraph throws is unwrapped from the
     * reflective {@link InvocationTargetException} and rethrown so tests can assert on the real
     * cause (for example {@link RecordNotFoundException}).
     *
     * @param work the work area supplying the account filter ({@code getAcctIdNumeric()})
     * @param form the form to populate on a hit
     * @throws Throwable the underlying exception the paragraph raised, if any
     */
    private void invokeGetCardByAccount(CardWorkArea work, COCRDSLForm form) throws Throwable {
        Class<?> stateClass = Class.forName(PROCESSING_STATE_FQN);
        Constructor<?> stateCtor = stateClass.getDeclaredConstructor();
        stateCtor.setAccessible(true);
        Object state = stateCtor.newInstance();
        Method method = CardDetailService.class.getDeclaredMethod(
                "getCardByAccount", CardWorkArea.class, stateClass, COCRDSLForm.class);
        method.setAccessible(true);
        try {
            method.invoke(service, work, state, form);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Verbatim literals (parity checklist items 1-4: byte-exact 88-level / inline messages)
    // ---------------------------------------------------------------------------------------------

    /**
     * Every migrated message literal is preserved byte-for-byte from {@code legacy/cbl/COCRDSLC.cbl},
     * including the three significant leading spaces of the success line
     * {@code "   Displaying requested details"}. Also documents the COBOL quirk that the copybook
     * 88-levels {@code SEARCHED-ACCT-*}/{@code SEARCHED-CARD-NOT-NUMERIC} are declared verbatim but
     * are not the literals emitted at run time (the field edits move the "FILTER" inline literals).
     */
    @Test
    void constants_preserveVerbatimEightyEightLevelLiterals() {
        assertThat(CardDetailService.FOUND_CARDS_FOR_ACCOUNT)
                .isEqualTo(MSG_DISPLAYING_DETAILS)
                .startsWith("   ")
                .hasSize(31);

        // Declared-but-never-emitted 88-level literals, retained verbatim for traceability.
        assertThat(CardDetailService.SEARCHED_ACCT_MSG).isEqualTo(MSG_SEARCHED_ACCT);
        assertThat(CardDetailService.SEARCHED_CARD_MSG).isEqualTo(MSG_SEARCHED_CARD);

        // Literals actually moved by 2210-EDIT-ACCOUNT / 2220-EDIT-CARD at run time.
        assertThat(CardDetailService.ACCT_FILTER_NOT_11_DIGITS).isEqualTo(MSG_ACCT_FILTER);
        assertThat(CardDetailService.CARD_FILTER_NOT_16_DIGITS).isEqualTo(MSG_CARD_FILTER);

        // Blank-filter prompts and the cross-field "no input" literal.
        assertThat(CardDetailService.WS_PROMPT_FOR_ACCT).isEqualTo(MSG_ACCT_NOT_PROVIDED);
        assertThat(CardDetailService.WS_PROMPT_FOR_CARD).isEqualTo(MSG_CARD_NOT_PROVIDED);
        assertThat(CardDetailService.NO_SEARCH_CRITERIA_RECEIVED).isEqualTo(MSG_NO_INPUT);

        // Read-miss messages: by-card and by-account carry DIFFERENT literals.
        assertThat(CardDetailService.DID_NOT_FIND_ACCTCARD_COMBO).isEqualTo(MSG_DID_NOT_FIND_CARDS);
        assertThat(CardDetailService.DID_NOT_FIND_ACCT_IN_CARDXREF).isEqualTo(MSG_DID_NOT_FIND_ACCT);

        // Prompt / exit / identifiers.
        assertThat(CardDetailService.WS_PROMPT_FOR_INPUT).isEqualTo(MSG_PROMPT_FOR_INPUT);
        assertThat(CardDetailService.WS_EXIT_MESSAGE).isEqualTo(MSG_EXIT);
        assertThat(CardDetailService.LIT_THISTRANID).isEqualTo(THIS_TRANID);
        assertThat(CardDetailService.LIT_THISPGM).isEqualTo(THIS_PGM);

        verifyNoInteractions(context, cardRepository);
    }

    // ---------------------------------------------------------------------------------------------
    // Entry semantics (parity checklist item 7: first entry vs re-entry)
    // ---------------------------------------------------------------------------------------------

    /**
     * First entry with ENTER (COBOL {@code EIBCALEN = 0} then {@code WHEN CDEMO-PGM-ENTER}) shows the
     * empty search screen with the initial prompt and performs no read. The first-entry branch runs
     * {@code initializeContext()} (asserted via {@code markInitialized}).
     */
    @Test
    void firstEntry_enterKey_showsPromptForInputAndReadsNothing() {
        when(context.isNew()).thenReturn(true);
        when(context.isProgramEnter()).thenReturn(true);

        CardDetailResult result = service.mainEntry(formWith(null, null), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.INFORMATION);
        assertThat(result.message()).isEqualTo(MSG_PROMPT_FOR_INPUT);
        verify(context).markInitialized();
        verifyNoInteractions(cardRepository);
    }

    /**
     * Arriving fresh from the main menu ({@code CDEMO-FROM-PROGRAM = 'COMEN01C' AND NOT
     * CDEMO-PGM-REENTER}) re-initializes the conversational context even though it is not the very
     * first entry, then shows the search prompt. No read is performed.
     */
    @Test
    void fromMainMenuEnter_initializesContextAndShowsPrompt() {
        when(context.getFromProgram()).thenReturn(MENU_PGM);
        when(context.isProgramEnter()).thenReturn(true);

        CardDetailResult result = service.mainEntry(formWith(null, null), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.INFORMATION);
        assertThat(result.message()).isEqualTo(MSG_PROMPT_FOR_INPUT);
        verify(context).markInitialized();
        verifyNoInteractions(cardRepository);
    }

    // ---------------------------------------------------------------------------------------------
    // Read by account + card (parity checklist items 3, 5, 6)
    // ---------------------------------------------------------------------------------------------

    /**
     * Re-entry with a valid 11-digit account and 16-digit card reads the card by primary key
     * ({@code 9100-GETCARD-BYACCTCARD} &rarr; {@link CardRepository#findById(Object)}); on a hit the
     * detail fields populate and the success line {@code "   Displaying requested details"} is shown.
     * No alternate-index read and no writes occur.
     */
    @Test
    void reentry_validAccountAndCard_cardFound_populatesFormAndDisplaysDetails() {
        when(context.isProgramReenter()).thenReturn(true);
        when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(sampleCard()));

        COCRDSLForm form = formWith(VALID_ACCT, VALID_CARD);
        CardDetailResult result = service.mainEntry(form, PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.INFORMATION);
        assertThat(result.message()).isEqualTo(MSG_DISPLAYING_DETAILS);
        // 1200-SETUP-SCREEN-VARS card-field MOVEs (expiry split into month/year).
        assertThat(form.getCrdname()).isEqualTo("JOHN Q PUBLIC");
        assertThat(form.getCrdstcd()).isEqualTo("Y");
        assertThat(form.getExpmon()).isEqualTo("12");
        assertThat(form.getExpyear()).isEqualTo("2027");

        verify(cardRepository).findById(VALID_CARD);
        verify(cardRepository, never()).findByCardAcctId(any());
        assertNoCardWrites();
        verifyNoMoreInteractions(cardRepository);
    }

    /**
     * Re-entry with valid inputs but no matching card reproduces the CICS {@code NOTFND} of
     * {@code 9100-GETCARD-BYACCTCARD}: the modern design surfaces it as a
     * {@link RecordNotFoundException} carrying the by-card miss literal
     * {@code "Did not find cards for this search condition"}. The read is a {@code findById} and
     * nothing is written.
     */
    @Test
    void reentry_validAccountAndCard_cardNotFound_throwsRecordNotFound() {
        when(context.isProgramReenter()).thenReturn(true);
        when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.empty());

        COCRDSLForm form = formWith(VALID_ACCT, VALID_CARD);

        assertThatThrownBy(() -> service.mainEntry(form, PfKey.ENTER))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_DID_NOT_FIND_CARDS);

        verify(cardRepository).findById(VALID_CARD);
        verify(cardRepository, never()).findByCardAcctId(any());
        assertNoCardWrites();
        verifyNoMoreInteractions(cardRepository);
    }

    // ---------------------------------------------------------------------------------------------
    // Field validation (parity checklist items 1, 2 - assert NO read on any edit failure)
    // ---------------------------------------------------------------------------------------------

    /**
     * A present-but-non-numeric 11-character account fails {@code 2210-EDIT-ACCOUNT}. The emitted
     * error is the inline literal {@code "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"} that
     * the paragraph actually moves &mdash; NOT the declared-but-never-set 88-level
     * {@code "Account number must be a non zero 11 digit number"}. No read is performed.
     */
    @Test
    void reentry_nonNumericAccount_showsAccountFilterErrorAndReadsNothing() {
        when(context.isProgramReenter()).thenReturn(true);

        CardDetailResult result = service.mainEntry(formWith("1234567890A", VALID_CARD), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message())
                .isEqualTo(MSG_ACCT_FILTER)
                .isNotEqualTo(MSG_SEARCHED_ACCT);
        verifyNoInteractions(cardRepository);
    }

    /**
     * A present-but-non-numeric 16-character card fails {@code 2220-EDIT-CARD}. The emitted error is
     * the inline literal {@code "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"} rather than
     * the declared-but-never-set 88-level {@code "Card number if supplied must be a 16 digit
     * number"}. No read is performed.
     */
    @Test
    void reentry_nonNumericCard_showsCardFilterErrorAndReadsNothing() {
        when(context.isProgramReenter()).thenReturn(true);

        CardDetailResult result =
                service.mainEntry(formWith(VALID_ACCT, "123456789012345X"), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message())
                .isEqualTo(MSG_CARD_FILTER)
                .isNotEqualTo(MSG_SEARCHED_CARD);
        verifyNoInteractions(cardRepository);
    }

    /**
     * When both filters are blank the cross-field edit of {@code 2200-EDIT-MAP-INPUTS}
     * unconditionally sets {@code "No input received"}, overriding the per-field prompts. No read is
     * performed.
     */
    @Test
    void reentry_bothFiltersBlank_showsNoInputReceivedAndReadsNothing() {
        when(context.isProgramReenter()).thenReturn(true);

        CardDetailResult result = service.mainEntry(formWith("", ""), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_NO_INPUT);
        verifyNoInteractions(cardRepository);
    }

    /**
     * A blank account with a valid card yields the "Account number not provided" prompt (the blank
     * branch of {@code 2210-EDIT-ACCOUNT}); because only one filter is blank the cross-field
     * "no input" rule does not fire. No read is performed.
     */
    @Test
    void reentry_blankAccountValidCard_showsAccountNotProvidedAndReadsNothing() {
        when(context.isProgramReenter()).thenReturn(true);

        CardDetailResult result = service.mainEntry(formWith("", VALID_CARD), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_ACCT_NOT_PROVIDED);
        verifyNoInteractions(cardRepository);
    }

    /**
     * A valid account with a blank card yields the "Card number not provided" prompt (the blank
     * branch of {@code 2220-EDIT-CARD}). The account edit set no message, so the card prompt is the
     * one emitted. No read is performed.
     */
    @Test
    void reentry_validAccountBlankCard_showsCardNotProvidedAndReadsNothing() {
        when(context.isProgramReenter()).thenReturn(true);

        CardDetailResult result = service.mainEntry(formWith(VALID_ACCT, ""), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_CARD_NOT_PROVIDED);
        verifyNoInteractions(cardRepository);
    }

    // ---------------------------------------------------------------------------------------------
    // Arrival from the card-list screen (re-entry via an already-validated selection)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = 'COCRDLIC'}: the selection was validated
     * by the card-list screen, so the service copies the selected account/card from the context and
     * reads directly by card number, without re-running the field edits. The resolved keys are
     * echoed back onto the map and the detail fields populate.
     */
    @Test
    void arrivingFromCardList_readsSelectedCardDirectlyAndEchoesKeys() {
        when(context.isProgramEnter()).thenReturn(true);
        when(context.getFromProgram()).thenReturn(CARDLIST_PGM);
        when(context.getAcctId()).thenReturn(VALID_ACCT_NUM);
        when(context.getCardNum()).thenReturn(VALID_CARD);
        when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(sampleCard()));

        COCRDSLForm form = formWith(null, null);
        CardDetailResult result = service.mainEntry(form, PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.INFORMATION);
        assertThat(result.message()).isEqualTo(MSG_DISPLAYING_DETAILS);
        // 1200 echo: the resolved key is written back to the map, zero-filled to the field width.
        assertThat(form.getAcctsid()).isEqualTo(VALID_ACCT);
        assertThat(form.getCardsid()).isEqualTo(VALID_CARD);
        assertThat(form.getCrdname()).isEqualTo("JOHN Q PUBLIC");
        assertThat(form.getCrdstcd()).isEqualTo("Y");

        verify(cardRepository).findById(VALID_CARD);
        verify(cardRepository, never()).findByCardAcctId(any());
        assertNoCardWrites();
        verifyNoMoreInteractions(cardRepository);
    }

    // ---------------------------------------------------------------------------------------------
    // PF-key routing (0000-MAIN dispatch) and AID coercion
    // ---------------------------------------------------------------------------------------------

    /**
     * PF3 with no remembered caller performs the {@code XCTL} hand-off to the main menu: the target
     * defaults to {@code CM00}/{@code COMEN01C}, this program is recorded as the new origin, and the
     * exit prompt {@code "PF03 pressed.Exiting"} is carried on a redirect result. No read occurs.
     */
    @Test
    void pf3_blankCaller_redirectsToMainMenuWithExitMessage() {
        CardDetailResult result = service.mainEntry(formWith(null, null), PfKey.PFK03);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.message()).isEqualTo(MSG_EXIT);
        verify(context).setToTranid(MENU_TRANID);
        verify(context).setToProgram(MENU_PGM);
        verify(context).setFromTranid(THIS_TRANID);
        verify(context).setFromProgram(THIS_PGM);
        verifyNoInteractions(cardRepository);
    }

    /**
     * PF3 with a remembered caller ({@code CDEMO-FROM-*} populated) hands off back to that caller
     * rather than the menu default, reproducing {@code CDEMO-TO-* = CDEMO-FROM-*}. No read occurs.
     */
    @Test
    void pf3_knownCaller_redirectsBackToCaller() {
        when(context.getFromTranid()).thenReturn(CARDLIST_TRANID);
        when(context.getFromProgram()).thenReturn(CARDLIST_PGM);

        CardDetailResult result = service.mainEntry(formWith(null, null), PfKey.PFK03);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        assertThat(result.message()).isEqualTo(MSG_EXIT);
        verify(context).setToTranid(CARDLIST_TRANID);
        verify(context).setToProgram(CARDLIST_PGM);
        verifyNoInteractions(cardRepository);
    }

    /**
     * A mocked context that reports neither program-enter nor program-reenter drives the
     * {@code WHEN OTHER} arm of {@code 0000-MAIN} - the unexpected-data-scenario abend - which the
     * service raises as an {@link IllegalStateException}. No read occurs.
     */
    @Test
    void unexpectedContextState_throwsIllegalState() {
        assertThatThrownBy(() -> service.mainEntry(formWith(VALID_ACCT, VALID_CARD), PfKey.ENTER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MSG_ABEND);
        verifyNoInteractions(cardRepository);
    }

    /**
     * A {@code null} AID is tolerated and coerced to ENTER (the COBOL default when {@code EIBAID}
     * matches no PF-key 88-level), so a re-entry with valid inputs still processes and reads.
     */
    @Test
    void nullAid_coercedToEnter_reentryProcessesInputs() {
        when(context.isProgramReenter()).thenReturn(true);
        when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(sampleCard()));

        CardDetailResult result = service.mainEntry(formWith(VALID_ACCT, VALID_CARD), null);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.INFORMATION);
        assertThat(result.message()).isEqualTo(MSG_DISPLAYING_DETAILS);
        verify(cardRepository).findById(VALID_CARD);
        assertNoCardWrites();
        verifyNoMoreInteractions(cardRepository);
    }

    // ---------------------------------------------------------------------------------------------
    // Alternate-index read (9150-GETCARD-BYACCT) - dead code, exercised via reflection to cover the
    // CARDAIX findByCardAcctId path and its distinct miss message (parity checklist item 4).
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code 9150-GETCARD-BYACCT} reads by the {@code CARDAIX} alternate index
     * ({@link CardRepository#findByCardAcctId(Long)}); on a hit it populates the detail fields from
     * the first matching card. A {@code null} expiry covers the branch that leaves the month/year
     * fields untouched. The paragraph is unreachable via {@code mainEntry}, so it is invoked
     * reflectively.
     */
    @Test
    void getCardByAccount_alternateIndexHit_populatesForm() throws Throwable {
        Card card = new Card(VALID_CARD, VALID_ACCT_NUM, 456, "ALICE SMITH", null, "N");
        when(cardRepository.findByCardAcctId(VALID_ACCT_NUM)).thenReturn(List.of(card));

        CardWorkArea work = new CardWorkArea();
        work.setAcctId(VALID_ACCT);
        COCRDSLForm form = formWith(null, null);

        invokeGetCardByAccount(work, form);

        assertThat(form.getCrdname()).isEqualTo("ALICE SMITH");
        assertThat(form.getCrdstcd()).isEqualTo("N");
        assertThat(form.getExpmon()).isNull();
        assertThat(form.getExpyear()).isNull();

        verify(cardRepository).findByCardAcctId(VALID_ACCT_NUM);
        verify(cardRepository, never()).findById(any());
        assertNoCardWrites();
        verifyNoMoreInteractions(cardRepository);
    }

    /**
     * {@code 9150-GETCARD-BYACCT} on an alternate-index miss raises {@link RecordNotFoundException}
     * carrying its own distinct literal {@code "Did not find this account in cards database"} - which
     * differs from the by-card miss literal - reproducing the source's unconditional
     * {@code SET DID-NOT-FIND-ACCT-IN-CARDXREF}. Invoked reflectively (dead code).
     */
    @Test
    void getCardByAccount_alternateIndexMiss_throwsRecordNotFound() {
        when(cardRepository.findByCardAcctId(VALID_ACCT_NUM)).thenReturn(List.<Card>of());

        CardWorkArea work = new CardWorkArea();
        work.setAcctId(VALID_ACCT);
        COCRDSLForm form = formWith(null, null);

        assertThatThrownBy(() -> invokeGetCardByAccount(work, form))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_DID_NOT_FIND_ACCT);

        verify(cardRepository).findByCardAcctId(VALID_ACCT_NUM);
        verify(cardRepository, never()).findById(any());
        assertNoCardWrites();
        verifyNoMoreInteractions(cardRepository);
    }
}

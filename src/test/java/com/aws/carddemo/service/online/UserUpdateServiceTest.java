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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COUSR02Form;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.service.online.UserUpdateService.AidKey;
import com.aws.carddemo.service.online.UserUpdateService.MessageSeverity;
import com.aws.carddemo.service.online.UserUpdateService.RoutingAction;
import com.aws.carddemo.service.online.UserUpdateService.UserUpdateResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;

/**
 * Pure-Mockito unit tests for {@link UserUpdateService}.
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COUSR02C.cbl}
 * (CICS COBOL program {@code COUSR02C}, transaction id {@code CU02}) &mdash; the
 * administrator "Update a user in USRSEC file" transaction. These tests assert
 * one-for-one control-flow parity with the numbered paragraphs of the oracle that
 * the service migrates (AAP &sect;0.1.2 / &sect;0.4.1 / &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link UserUpdateService#mainEntry} &mdash; the
 *       pseudo-conversational state machine ({@code EIBCALEN = 0} first entry,
 *       first program display with an optional pre-selected user id, and the
 *       {@code EVALUATE EIBAID} re-entry branches for {@code DFHENTER},
 *       {@code DFHPF3}, {@code DFHPF4}, {@code DFHPF5}, {@code DFHPF12} and
 *       {@code WHEN OTHER}).</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr;
 *       {@link UserUpdateService#processEnterKey} &mdash; empty user id guard, the
 *       keyed look-up that loads the editable fields, and the not-found / lookup
 *       failure branches.</li>
 *   <li>{@code UPDATE-USER-INFO} &rarr;
 *       {@link UserUpdateService#updateUserInfo} &mdash; the five-field validation
 *       ladder, the read for update, the {@code USR-MODIFIED} change flag (both
 *       branches), and the rewrite-versus-"please modify" decision.</li>
 *   <li>{@code READ-USER-SEC-FILE} &rarr;
 *       {@link UserUpdateService#readUserSecFile} &mdash; the keyed read mapping a
 *       missing record to {@link RecordNotFoundException}.</li>
 *   <li>{@code UPDATE-USER-SEC-FILE} &rarr;
 *       {@link UserUpdateService#updateUserSecFile} &mdash; the rewrite producing
 *       the green success line or the "Unable to Update User..." error.</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} &rarr;
 *       {@link UserUpdateService#clearCurrentScreen} and
 *       {@code INITIALIZE-ALL-FIELDS} &rarr;
 *       {@link UserUpdateService#initializeAllFields} &mdash; the {@code PF4}
 *       field reset.</li>
 * </ul>
 *
 * <p><b>Test tier:</b> a strict-stubs pure-Mockito unit test
 * ({@link MockitoExtension}); it uses no database, no Spring context, and no
 * Testcontainers. Collaborators are mocked and the service is exercised in
 * isolation. Because {@code COUSR02C} is a {@code @Transactional}
 * read-update-rewrite over {@code USRSEC}, the rewrite is asserted with an
 * {@link ArgumentCaptor} over {@link UserSecurityRepository#save(Object)} and
 * routing outcomes are asserted on the mocked {@link CardDemoContext} (the
 * {@code COMMAREA} replacement) rather than on any HTTP redirect, because the
 * service records the routing target in the context and defers the actual
 * {@code XCTL}/redirect to the paired controller.</p>
 *
 * <p><b>Cleartext password parity (AAP &sect;0.6.7):</b> the update path is
 * asserted to persist the submitted password verbatim (no hashing), preserving
 * the legacy {@code USRSEC} behaviour exactly.</p>
 *
 * <p><b>Mock hand-off note:</b> {@code RETURN-TO-PREV-SCREEN} defaults an unset
 * target to {@code COSGN00C}. On a mocked context {@code getToProgram()} returns
 * {@code null}, so that default assignment always fires <em>after</em> any
 * explicit {@code setToProgram(...)}; the PF3/PF12 assertions therefore verify the
 * specific target argument ({@code "COADM01C"}), which is invoked exactly once, and
 * the first-entry assertion uses {@code atLeastOnce()} for {@code "COSGN00C"}.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserUpdateServiceTest {

    /** COBOL {@code WS-PGMNAME} of {@code COUSR02C}; recorded as the origin program on routing. */
    private static final String PROGRAM_NAME = "COUSR02C";

    /** COBOL {@code WS-TRANID} of {@code COUSR02C}; recorded as the origin transaction id on routing. */
    private static final String TRANSACTION_ID = "CU02";

    /** Sign-on program ({@code COSGN00C}); the first-entry ({@code EIBCALEN = 0}) hand-off target. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Admin-menu program ({@code COADM01C}); the PF3 (no origin) and PF12 hand-off target. */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** Exact oracle literal: blank {@code USRIDIN} (PROCESS-ENTER-KEY / UPDATE-USER-INFO). */
    private static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";

    /** Exact oracle literal: blank {@code FNAME} (UPDATE-USER-INFO). */
    private static final String MSG_FNAME_EMPTY = "First Name can NOT be empty...";

    /** Exact oracle literal: blank {@code LNAME} (UPDATE-USER-INFO). */
    private static final String MSG_LNAME_EMPTY = "Last Name can NOT be empty...";

    /** Exact oracle literal: blank {@code PASSWD} (UPDATE-USER-INFO). */
    private static final String MSG_PASSWD_EMPTY = "Password can NOT be empty...";

    /** Exact oracle literal: blank {@code USRTYPE} (UPDATE-USER-INFO). */
    private static final String MSG_USRTYPE_EMPTY = "User Type can NOT be empty...";

    /** Exact oracle literal: neutral save prompt after a successful look-up (READ-USER-SEC-FILE NORMAL). */
    private static final String MSG_PRESS_PF5 = "Press PF5 key to save your updates ...";

    /** Exact oracle literal: red "no change" nudge when nothing was modified (UPDATE-USER-INFO). */
    private static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    /** Exact oracle literal: user-not-found on a keyed read (READ/UPDATE-USER-SEC-FILE NOTFND). */
    private static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /** Exact oracle literal: read failure (READ-USER-SEC-FILE WHEN OTHER). */
    private static final String MSG_UNABLE_LOOKUP = "Unable to lookup User...";

    /** Exact oracle literal: rewrite failure (UPDATE-USER-SEC-FILE WHEN OTHER). */
    private static final String MSG_UNABLE_UPDATE = "Unable to Update User...";

    /** Exact oracle literal: unmapped AID key ({@code CCDA-MSG-INVALID-KEY}, MAIN-PARA WHEN OTHER). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** A stable user id used across fixtures; 8 characters like the {@code SEC-USR-ID PIC X(08)} key. */
    private static final String USER_ID = "USER0001";

    /** Session-scoped {@code COMMAREA} replacement, mocked so routing writes are observable. */
    @Mock
    private CardDemoContext context;

    /** {@code USRSEC} store, mocked so the read-update-rewrite can be driven and captured. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** Service under test; Mockito constructor-injects the two mocked collaborators. */
    @InjectMocks
    private UserUpdateService service;

    /**
     * Builds a real {@link UserSecurity} record fixture (the stored {@code SEC-USER-DATA}).
     *
     * @param usrId    the user id ({@code SEC-USR-ID})
     * @param usrFname the first name ({@code SEC-USR-FNAME})
     * @param usrLname the last name ({@code SEC-USR-LNAME})
     * @param usrPwd   the cleartext password ({@code SEC-USR-PWD})
     * @param usrType  the user type ({@code SEC-USR-TYPE})
     * @return a populated entity
     */
    private static UserSecurity userRecord(String usrId, String usrFname, String usrLname,
            String usrPwd, String usrType) {
        UserSecurity user = new UserSecurity();
        user.setUsrId(usrId);
        user.setUsrFname(usrFname);
        user.setUsrLname(usrLname);
        user.setUsrPwd(usrPwd);
        user.setUsrType(usrType);
        return user;
    }

    /**
     * Builds a real {@link COUSR02Form} fixture with the five editable value fields set
     * ({@code USRIDINI}, {@code FNAMEI}, {@code LNAMEI}, {@code PASSWDI}, {@code USRTYPEI}).
     *
     * @param usridin the user id search key ({@code USRIDINI})
     * @param fname   the first name ({@code FNAMEI})
     * @param lname   the last name ({@code LNAMEI})
     * @param passwd  the cleartext password ({@code PASSWDI})
     * @param usrtype the user type ({@code USRTYPEI})
     * @return a populated form
     */
    private static COUSR02Form formOf(String usridin, String fname, String lname,
            String passwd, String usrtype) {
        COUSR02Form form = new COUSR02Form();
        form.setUsridin(usridin);
        form.setFname(fname);
        form.setLname(lname);
        form.setPasswd(passwd);
        form.setUsrtype(usrtype);
        return form;
    }

    // ------------------------------------------------------------------
    // MAIN-PARA: pseudo-conversational state machine + EVALUATE EIBAID
    // ------------------------------------------------------------------

    /**
     * First entry with no COMMAREA (COBOL {@code IF EIBCALEN = 0},
     * {@link CardDemoContext#isNew()}): the program cannot run without a signed-on
     * conversation, so {@code MAIN-PARA} moves {@code 'COSGN00C'} to
     * {@code CDEMO-TO-PROGRAM} and performs {@code RETURN-TO-PREV-SCREEN}, which
     * stamps this program/transaction as the origin, resets the program context to
     * enter ({@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}) and requests the redirect.
     * {@code isProgramEnter()} is never consulted on this path (checklist item 8).
     */
    @Test
    void mainEntry_firstEntryNoCommareaReturnsToSignon() {
        when(context.isNew()).thenReturn(true);

        UserUpdateResult result = service.mainEntry(new COUSR02Form(), AidKey.ENTER, null);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        assertThat(result.isRedirect()).isTrue();
        verify(context, atLeastOnce()).setToProgram(SIGNON_PROGRAM);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).markEnter();
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * First display of the program (COBOL {@code IF NOT CDEMO-PGM-REENTER}) reached
     * from the user-list with a pre-selected user id
     * ({@code CDEMO-CU02-USR-SELECTED}, supplied as {@code selectedUserId}): the
     * service flips the program context to re-enter, pre-loads {@code USRIDINI}, and
     * immediately performs {@code PROCESS-ENTER-KEY} to look the user up. A
     * successful read loads the four editable fields onto the form and shows the
     * neutral "Press PF5..." prompt (checklist items 1 and 8). The AID key is
     * ignored on this first-display path.
     */
    @Test
    void mainEntry_firstDisplayWithSelectedUserPreloadsAndReads() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);
        UserSecurity stored = userRecord(USER_ID, "John", "Smith", "PASS0001", "U");
        when(userSecurityRepository.findByUsrId(USER_ID)).thenReturn(Optional.of(stored));
        COUSR02Form form = new COUSR02Form();

        UserUpdateResult result = service.mainEntry(form, AidKey.ENTER, USER_ID);

        verify(context).markReenter();
        assertThat(form.getUsridin()).isEqualTo(USER_ID);
        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NEUTRAL);
        assertThat(result.message()).isEqualTo(MSG_PRESS_PF5);
        assertThat(form.getFname()).isEqualTo("John");
        assertThat(form.getLname()).isEqualTo("Smith");
        assertThat(form.getPasswd()).isEqualTo("PASS0001");
        assertThat(form.getUsrtype()).isEqualTo("U");
        verify(userSecurityRepository).findByUsrId(USER_ID);
    }

    /**
     * First display of the program with no pre-selected user id: the service flips
     * the program context to re-enter and re-displays a blank user-update screen with
     * no message, performing no look-up ({@code PERFORM SEND-USRUPD-SCREEN} on the
     * empty map). Covers the re-entry side of checklist item 8.
     */
    @Test
    void mainEntry_firstDisplayWithoutSelectionShowsBlankScreen() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);

        UserUpdateResult result = service.mainEntry(new COUSR02Form(), AidKey.ENTER, null);

        verify(context).markReenter();
        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.message()).isEmpty();
        assertThat(result.hasMessage()).isFalse();
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * On re-entry, {@code ENTER} ({@code DFHENTER}) delegates to
     * {@code PROCESS-ENTER-KEY}; a valid user id loads the record and shows the
     * neutral save prompt, exercising the {@code MAIN-PARA} {@code EVALUATE EIBAID}
     * ENTER branch end-to-end.
     */
    @Test
    void mainEntry_reentryEnterDelegatesToProcessEnterKey() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        UserSecurity stored = userRecord(USER_ID, "John", "Smith", "PASS0001", "U");
        when(userSecurityRepository.findByUsrId(USER_ID)).thenReturn(Optional.of(stored));
        COUSR02Form form = formOf(USER_ID, null, null, null, null);

        UserUpdateResult result = service.mainEntry(form, AidKey.ENTER, null);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NEUTRAL);
        assertThat(result.message()).isEqualTo(MSG_PRESS_PF5);
    }

    /**
     * On re-entry, {@code PF3} ({@code DFHPF3}) performs {@code UPDATE-USER-INFO} and
     * then unconditionally routes back: with no recorded origin
     * ({@code CDEMO-FROM-PROGRAM} blank) the target is {@code COADM01C}
     * (checklist item 6). The update is attempted first &mdash; a changed record is
     * rewritten &mdash; but the redirect supersedes whatever the update would have
     * displayed, so the outcome is a {@link RoutingAction#REDIRECT}.
     */
    @Test
    void mainEntry_pf3UpdatesThenReturnsToAdminMenu() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        UserSecurity stored = userRecord(USER_ID, "John", "Smith", "PASS0001", "U");
        when(userSecurityRepository.findByUsrId(USER_ID)).thenReturn(Optional.of(stored));
        COUSR02Form form = formOf(USER_ID, "Jane", "Smith", "PASS0001", "U");

        UserUpdateResult result = service.mainEntry(form, AidKey.PF3, null);

        verify(userSecurityRepository).save(any(UserSecurity.class));
        verify(context).setToProgram(ADMIN_MENU_PROGRAM);
        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        assertThat(result.isRedirect()).isTrue();
    }

    /**
     * On re-entry, {@code PF4} ({@code DFHPF4}) performs {@code CLEAR-CURRENT-SCREEN}:
     * every editable field is blanked and the empty screen is re-displayed with no
     * message and no persistence.
     */
    @Test
    void mainEntry_pf4ClearsScreen() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        COUSR02Form form = formOf(USER_ID, "Jane", "Doe", "SECRET", "A");

        UserUpdateResult result = service.mainEntry(form, AidKey.PF4, null);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.message()).isEmpty();
        assertThat(form.getUsridin()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getPasswd()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * On re-entry, {@code PF5} ({@code DFHPF5}) performs {@code UPDATE-USER-INFO}: a
     * changed record is rewritten and the green success line is returned. This
     * exercises the explicit update key end-to-end through {@code mainEntry}.
     */
    @Test
    void mainEntry_pf5UpdatesUser() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        UserSecurity stored = userRecord(USER_ID, "John", "Smith", "PASS0001", "U");
        when(userSecurityRepository.findByUsrId(USER_ID)).thenReturn(Optional.of(stored));
        COUSR02Form form = formOf(USER_ID, "Jane", "Smith", "PASS0001", "U");

        UserUpdateResult result = service.mainEntry(form, AidKey.PF5, null);

        verify(userSecurityRepository).save(any(UserSecurity.class));
        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo("User " + USER_ID + " has been updated ...");
    }

    /**
     * On re-entry, {@code PF12} ({@code DFHPF12}) moves {@code 'COADM01C'} to
     * {@code CDEMO-TO-PROGRAM} and performs {@code RETURN-TO-PREV-SCREEN}, handing
     * control back to the admin menu with no persistence (checklist item 6).
     */
    @Test
    void mainEntry_pf12ReturnsToAdminMenu() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        UserUpdateResult result = service.mainEntry(new COUSR02Form(), AidKey.PF12, null);

        verify(context).setToProgram(ADMIN_MENU_PROGRAM);
        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        assertThat(result.isRedirect()).isTrue();
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * On re-entry, any key other than the mapped ones takes the COBOL
     * {@code WHEN OTHER} branch and shows the invalid-key error
     * ({@code CCDA-MSG-INVALID-KEY}); no routing and no persistence occur.
     */
    @Test
    void mainEntry_unrecognizedKeyShowsInvalidKeyError() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        UserUpdateResult result = service.mainEntry(new COUSR02Form(), AidKey.OTHER, null);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.isError()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        verify(context, never()).setToProgram(anyString());
        verifyNoInteractions(userSecurityRepository);
    }


    // ------------------------------------------------------------------
    // PROCESS-ENTER-KEY: empty-id guard + keyed look-up
    // ------------------------------------------------------------------

    /**
     * {@code PROCESS-ENTER-KEY} with a blank {@code USRIDINI} (COBOL
     * {@code WHEN USRIDINI = SPACES OR LOW-VALUES}) short-circuits with the
     * "User ID can NOT be empty..." error and performs no read; the context is not
     * consulted by this paragraph.
     */
    @Test
    void processEnterKey_emptyUserIdReturnsError() {
        COUSR02Form form = formOf("", null, null, null, null);

        UserUpdateResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USERID_EMPTY);
        verifyNoInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-ENTER-KEY} with a present user id reads the record and, on the
     * COBOL {@code NORMAL} branch, loads the four editable fields onto the form and
     * returns the neutral ({@code DFHNEUTR}) "Press PF5..." save prompt. The password
     * is copied verbatim (cleartext) into the form for editing (checklist item 1).
     */
    @Test
    void processEnterKey_userFoundLoadsFieldsAndPromptsToSave() {
        UserSecurity stored = userRecord(USER_ID, "John", "Smith", "PASS0001", "U");
        when(userSecurityRepository.findByUsrId(USER_ID)).thenReturn(Optional.of(stored));
        COUSR02Form form = formOf(USER_ID, null, null, null, null);

        UserUpdateResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NEUTRAL);
        assertThat(result.message()).isEqualTo(MSG_PRESS_PF5);
        assertThat(form.getFname()).isEqualTo("John");
        assertThat(form.getLname()).isEqualTo("Smith");
        assertThat(form.getPasswd()).isEqualTo("PASS0001");
        assertThat(form.getUsrtype()).isEqualTo("U");
    }

    /**
     * {@code PROCESS-ENTER-KEY} on a not-found read (COBOL {@code NOTFND}) surfaces
     * the "User ID NOT found..." error and leaves the editable fields cleared, exactly
     * as the COBOL leaves them (checklist item 2, translated).
     */
    @Test
    void processEnterKey_userNotFoundReturnsError() {
        when(userSecurityRepository.findByUsrId("MISSING")).thenReturn(Optional.empty());
        COUSR02Form form = formOf("MISSING", "STALE", "STALE", "STALE", "U");

        UserUpdateResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USER_NOT_FOUND);
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getPasswd()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
    }

    /**
     * {@code PROCESS-ENTER-KEY} on any other read failure (COBOL {@code WHEN OTHER},
     * modelled as a Spring {@link DataAccessException}) surfaces the
     * "Unable to lookup User..." error.
     */
    @Test
    void processEnterKey_lookupFailureReturnsUnableToLookup() {
        when(userSecurityRepository.findByUsrId(USER_ID))
                .thenThrow(new StubDataAccessException("read failed"));
        COUSR02Form form = formOf(USER_ID, null, null, null, null);

        UserUpdateResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_UNABLE_LOOKUP);
    }

    // ------------------------------------------------------------------
    // UPDATE-USER-INFO: validation ladder, change flag, rewrite decision
    // ------------------------------------------------------------------

    /**
     * {@code UPDATE-USER-INFO} validation ladder, rung 1: a blank {@code USRIDINI}
     * yields "User ID can NOT be empty..." and performs no read.
     */
    @Test
    void updateUserInfo_blankUserIdReturnsError() {
        COUSR02Form form = formOf("", "John", "Smith", "PASS0001", "U");

        UserUpdateResult result = service.updateUserInfo(form, context);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USERID_EMPTY);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * {@code UPDATE-USER-INFO} validation ladder, rung 2: a blank {@code FNAMEI}
     * yields "First Name can NOT be empty..." and performs no read.
     */
    @Test
    void updateUserInfo_blankFirstNameReturnsError() {
        COUSR02Form form = formOf(USER_ID, "", "Smith", "PASS0001", "U");

        UserUpdateResult result = service.updateUserInfo(form, context);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_FNAME_EMPTY);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * {@code UPDATE-USER-INFO} validation ladder, rung 3: a blank {@code LNAMEI}
     * yields "Last Name can NOT be empty..." and performs no read.
     */
    @Test
    void updateUserInfo_blankLastNameReturnsError() {
        COUSR02Form form = formOf(USER_ID, "John", "", "PASS0001", "U");

        UserUpdateResult result = service.updateUserInfo(form, context);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_LNAME_EMPTY);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * {@code UPDATE-USER-INFO} validation ladder, rung 4: a blank {@code PASSWDI}
     * yields "Password can NOT be empty..." and performs no read.
     */
    @Test
    void updateUserInfo_blankPasswordReturnsError() {
        COUSR02Form form = formOf(USER_ID, "John", "Smith", "", "U");

        UserUpdateResult result = service.updateUserInfo(form, context);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_PASSWD_EMPTY);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * {@code UPDATE-USER-INFO} validation ladder, rung 5: a blank {@code USRTYPEI}
     * yields "User Type can NOT be empty..." and performs no read.
     */
    @Test
    void updateUserInfo_blankUserTypeReturnsError() {
        COUSR02Form form = formOf(USER_ID, "John", "Smith", "PASS0001", "");

        UserUpdateResult result = service.updateUserInfo(form, context);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USRTYPE_EMPTY);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * Change-flag branch A (COBOL {@code USR-MODIFIED-NO}): when every submitted field
     * equals the stored record, no field is modified, so the service returns the red
     * "Please modify to update ..." nudge and does <b>not</b> rewrite the record
     * (checklist item 3, exact "no change" literal).
     */
    @Test
    void updateUserInfo_noChangeReturnsPleaseModifyAndDoesNotSave() {
        UserSecurity stored = userRecord(USER_ID, "John", "Smith", "PASS0001", "U");
        when(userSecurityRepository.findByUsrId(USER_ID)).thenReturn(Optional.of(stored));
        COUSR02Form form = formOf(USER_ID, "John", "Smith", "PASS0001", "U");

        UserUpdateResult result = service.updateUserInfo(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_PLEASE_MODIFY);
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
    }

    /**
     * Change-flag branch B (COBOL {@code USR-MODIFIED-YES}) + successful rewrite: when
     * every editable field differs, each new value is applied to the entity, the
     * record is rewritten, and the green ({@code DFHGREEN}) success line is returned.
     * The {@link ArgumentCaptor} asserts the persisted first/last name, password and
     * type are the submitted values, and that the (cleartext) password is stored
     * verbatim with no hashing (checklist items 3, 4 and 5; AAP &sect;0.6.7).
     */
    @Test
    void updateUserInfo_changedFieldsPersistAndReturnSuccess() {
        UserSecurity stored = userRecord(USER_ID, "John", "Smith", "PASS0001", "U");
        when(userSecurityRepository.findByUsrId(USER_ID)).thenReturn(Optional.of(stored));
        COUSR02Form form = formOf(USER_ID, "Jane", "Doe", "NEWPASS", "A");

        UserUpdateResult result = service.updateUserInfo(form, context);

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity saved = captor.getValue();
        assertThat(saved.getUsrId()).isEqualTo(USER_ID);
        assertThat(saved.getUsrFname()).isEqualTo("Jane");
        assertThat(saved.getUsrLname()).isEqualTo("Doe");
        assertThat(saved.getUsrPwd()).isEqualTo("NEWPASS");
        assertThat(saved.getUsrType()).isEqualTo("A");
        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo("User " + USER_ID + " has been updated ...");
    }

    /**
     * Cleartext-password parity focus (AAP &sect;0.6.7): when only the password
     * changes, the change flag is set from that single difference, the record is
     * rewritten, and the captured {@code SEC-USR-PWD} is the exact submitted plaintext
     * &mdash; no hashing, salting or transformation (checklist item 5).
     */
    @Test
    void updateUserInfo_onlyPasswordChangedPersistsCleartext() {
        UserSecurity stored = userRecord(USER_ID, "John", "Smith", "OLDPASS", "U");
        when(userSecurityRepository.findByUsrId(USER_ID)).thenReturn(Optional.of(stored));
        COUSR02Form form = formOf(USER_ID, "John", "Smith", "S3cret!", "U");

        UserUpdateResult result = service.updateUserInfo(form, context);

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        assertThat(captor.getValue().getUsrPwd()).isEqualTo("S3cret!");
        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo("User " + USER_ID + " has been updated ...");
    }

    /**
     * {@code UPDATE-USER-INFO} on a not-found read (COBOL {@code NOTFND}) returns the
     * "User ID NOT found..." error after the validation ladder passes, and never
     * rewrites the record.
     */
    @Test
    void updateUserInfo_userNotFoundReturnsError() {
        when(userSecurityRepository.findByUsrId(USER_ID)).thenReturn(Optional.empty());
        COUSR02Form form = formOf(USER_ID, "John", "Smith", "PASS0001", "U");

        UserUpdateResult result = service.updateUserInfo(form, context);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USER_NOT_FOUND);
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
    }

    /**
     * {@code UPDATE-USER-INFO} on any other read failure (COBOL {@code WHEN OTHER},
     * modelled as a Spring {@link DataAccessException}) returns the
     * "Unable to lookup User..." error and never rewrites the record.
     */
    @Test
    void updateUserInfo_lookupFailureReturnsUnableToLookup() {
        when(userSecurityRepository.findByUsrId(USER_ID))
                .thenThrow(new StubDataAccessException("read failed"));
        COUSR02Form form = formOf(USER_ID, "John", "Smith", "PASS0001", "U");

        UserUpdateResult result = service.updateUserInfo(form, context);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_UNABLE_LOOKUP);
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
    }


    // ------------------------------------------------------------------
    // READ-USER-SEC-FILE: keyed read + NOTFND mapping
    // ------------------------------------------------------------------

    /**
     * {@code READ-USER-SEC-FILE} on the COBOL {@code NORMAL} branch returns the
     * located {@link UserSecurity} record, invoking the repository with the exact key
     * ({@code RIDFLD(SEC-USR-ID)}).
     */
    @Test
    void readUserSecFile_returnsRecordWhenPresent() {
        UserSecurity stored = userRecord(USER_ID, "John", "Smith", "PASS0001", "U");
        when(userSecurityRepository.findByUsrId(USER_ID)).thenReturn(Optional.of(stored));

        UserSecurity result = service.readUserSecFile(USER_ID);

        assertThat(result).isSameAs(stored);
        verify(userSecurityRepository).findByUsrId(USER_ID);
    }

    /**
     * {@code READ-USER-SEC-FILE} on the COBOL {@code NOTFND} branch throws
     * {@link RecordNotFoundException} carrying the "User ID NOT found..." text, the
     * single Java carrier for the legacy record-not-found signal (checklist item 2,
     * raw exception).
     */
    @Test
    void readUserSecFile_throwsRecordNotFoundWhenAbsent() {
        when(userSecurityRepository.findByUsrId("MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readUserSecFile("MISSING"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_USER_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // UPDATE-USER-SEC-FILE: rewrite outcome
    // ------------------------------------------------------------------

    /**
     * {@code UPDATE-USER-SEC-FILE} on the COBOL {@code NORMAL} branch rewrites the
     * record and returns the green ({@code DFHGREEN}) success line, composed by the
     * COBOL {@code STRING 'User ' SEC-USR-ID ' has been updated ...'}.
     */
    @Test
    void updateUserSecFile_savesAndReturnsSuccessMessage() {
        UserSecurity stored = userRecord(USER_ID, "Jane", "Doe", "NEWPASS", "A");

        UserUpdateResult result = service.updateUserSecFile(stored);

        verify(userSecurityRepository).save(stored);
        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo("User " + USER_ID + " has been updated ...");
    }

    /**
     * {@code UPDATE-USER-SEC-FILE} on a rewrite failure (COBOL {@code WHEN OTHER},
     * modelled as a Spring {@link DataAccessException}) returns the
     * "Unable to Update User..." error rather than propagating the exception.
     */
    @Test
    void updateUserSecFile_persistenceFailureReturnsUnableToUpdate() {
        UserSecurity stored = userRecord(USER_ID, "Jane", "Doe", "NEWPASS", "A");
        when(userSecurityRepository.save(stored))
                .thenThrow(new StubDataAccessException("rewrite failed"));

        UserUpdateResult result = service.updateUserSecFile(stored);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_UNABLE_UPDATE);
    }

    // ------------------------------------------------------------------
    // CLEAR-CURRENT-SCREEN / INITIALIZE-ALL-FIELDS: PF4 field reset
    // ------------------------------------------------------------------

    /**
     * {@code CLEAR-CURRENT-SCREEN} performs {@code INITIALIZE-ALL-FIELDS} then
     * re-displays the empty screen: every editable field is blanked and a plain
     * {@link RoutingAction#SHOW_SCREEN} outcome with no message is returned
     * (checklist item 7).
     */
    @Test
    void clearCurrentScreen_resetsFieldsAndShowsBlankScreen() {
        COUSR02Form form = formOf(USER_ID, "Jane", "Doe", "SECRET", "A");
        form.setErrmsg("stale message");

        UserUpdateResult result = service.clearCurrentScreen(form);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.message()).isEmpty();
        assertThat(form.getUsridin()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getPasswd()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        assertThat(form.getErrmsg()).isEmpty();
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} blanks the user id, the four editable fields, and
     * the message line (COBOL {@code MOVE SPACES TO USRIDINI FNAMEI LNAMEI PASSWDI
     * USRTYPEI WS-MESSAGE}). The cursor-positioning {@code MOVE -1 TO USRIDINL} is a
     * presentation concern owned by the controller and is intentionally not
     * reproduced here (checklist item 7).
     */
    @Test
    void initializeAllFields_blanksAllEditableFields() {
        COUSR02Form form = formOf(USER_ID, "Jane", "Doe", "SECRET", "A");
        form.setErrmsg("stale message");

        service.initializeAllFields(form);

        assertThat(form.getUsridin()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getPasswd()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        assertThat(form.getErrmsg()).isEmpty();
    }

    /**
     * Concrete, test-only {@link DataAccessException} used to drive the COBOL
     * {@code WHEN OTHER} error branches of {@code READ-USER-SEC-FILE} and
     * {@code UPDATE-USER-SEC-FILE} (the service catches {@link DataAccessException},
     * which is abstract and cannot be instantiated directly). It carries no state
     * beyond its message and declares an explicit {@code serialVersionUID} so the
     * zero-warning build ({@code -Xlint:all}, {@code failOnWarning}) does not flag the
     * inherited {@link java.io.Serializable} contract.
     */
    private static final class StubDataAccessException extends DataAccessException {

        /** Serialization version identifier (silences the {@code [serial]} lint category). */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the stub with a diagnostic message.
         *
         * @param message the failure description
         */
        StubDataAccessException(String message) {
            super(message);
        }
    }
}


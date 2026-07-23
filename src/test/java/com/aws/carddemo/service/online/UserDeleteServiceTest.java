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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COUSR03Form;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.security.SessionRevocationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link UserDeleteService}, the Java migration of the CICS COBOL
 * program {@code COUSR03C} (the AWS CardDemo administrator "Delete a user from USRSEC file" screen).
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COUSR03C.cbl} &mdash; program
 * {@code COUSR03C}, CICS transaction id {@code CU03}. These tests assert control-flow parity with
 * the program's numbered paragraphs, one test method group per paragraph:</p>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link UserDeleteService#mainEntry(UserDeleteService.AidKey, COUSR03Form)}
 *       &mdash; the {@code EIBCALEN = 0} first-entry bounce, the {@code NOT CDEMO-PGM-REENTER}
 *       first-display branch (with auto-lookup of a list selection), and the re-entry
 *       {@code EVALUATE EIBAID} dispatch (ENTER / PF3 / PF4 / PF5 / PF12 / {@code WHEN OTHER}).</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr;
 *       {@link UserDeleteService#processEnterKey(COUSR03Form, CardDemoContext)} &mdash; the empty-id
 *       guard, the confirmation lookup, and the neutral {@code "Press PF5 ..."} prompt. This is
 *       <b>step 1</b> of the two-step confirm-then-delete and performs <b>no</b> deletion.</li>
 *   <li>{@code DELETE-USER-INFO} &rarr;
 *       {@link UserDeleteService#deleteUserInfo(COUSR03Form, CardDemoContext)} &mdash; the empty-id
 *       guard and the read-then-delete sequence. This is <b>step 2</b> (reached via PF5).</li>
 *   <li>{@code READ-USER-SEC-FILE} &rarr; {@link UserDeleteService#readUserSecFile(String)} &mdash;
 *       the keyed read and the {@code NOTFND} &rarr; {@link RecordNotFoundException} mapping.</li>
 *   <li>{@code DELETE-USER-SEC-FILE} &rarr;
 *       {@link UserDeleteService#deleteUserSecFile(UserSecurity, COUSR03Form)} &mdash; the actual
 *       delete, the green {@code "... has been deleted ..."} success line, and the delete-time
 *       error branches.</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} &rarr;
 *       {@link UserDeleteService#clearCurrentScreen(COUSR03Form)} &mdash; PF4 field reset.</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr;
 *       {@link UserDeleteService#initializeAllFields(COUSR03Form)} &mdash; the field-clearing helper.</li>
 * </ul>
 *
 * <p><b>Test tier.</b> This is a strict-stubs pure-Mockito unit test: {@link MockitoExtension} with
 * {@link Mock} collaborators and an {@link InjectMocks} service. It starts no Spring context, opens no
 * database connection, and loads no persistence runtime &mdash; it verifies the service's business
 * logic in complete isolation. The presentation paragraphs ({@code SEND-USRDEL-SCREEN},
 * {@code RECEIVE-USRDEL-SCREEN}, {@code POPULATE-HEADER-INFO}, {@code RETURN-TO-PREV-SCREEN}) are
 * owned by the paired {@code UserAdminController} and are therefore not exercised here.</p>
 *
 * <p><b>Defining parity behavior &mdash; the two-step gate.</b> Deletion is a deliberate two-step
 * interaction preserved from the COBOL: pressing ENTER (step 1) only looks the user up and shows the
 * verbatim neutral prompt {@code "Press PF5 key to delete this user ..."} with the display fields
 * populated and <b>nothing deleted</b>; only pressing PF5 (step 2) performs the actual delete. The
 * tests assert this gate directly &mdash; the ENTER path verifies neither
 * {@link UserSecurityRepository#delete(Object)} nor
 * {@code deleteById} is ever invoked, and the PF5 path verifies exactly one delete of the selected
 * user.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserDeleteServiceTest {

    /** Byte-exact empty-id literal (COBOL {@code 'User ID can NOT be empty...'}). */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Byte-exact neutral delete-confirmation prompt (COBOL
     * {@code 'Press PF5 key to delete this user ...'}, {@code legacy/cbl/COUSR03C.cbl} line 283 &mdash;
     * note the single space before the trailing ellipsis).
     */
    private static final String MSG_PRESS_PF5_TO_DELETE = "Press PF5 key to delete this user ...";

    /** Byte-exact user-not-found literal (COBOL {@code 'User ID NOT found...'}). */
    private static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    /** Byte-exact read-failure literal (COBOL {@code 'Unable to lookup User...'}). */
    private static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    /**
     * Byte-exact delete-failure literal (COBOL {@code 'Unable to Update User...'} &mdash; the
     * original's capitalized {@code "Update"} is preserved for parity).
     */
    private static final String MSG_UNABLE_TO_UPDATE = "Unable to Update User...";

    /** Byte-exact invalid-key literal (COBOL {@code CCDA-MSG-INVALID-KEY}) for the {@code WHEN OTHER} branch. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Sign-on program (COBOL literal {@code 'COSGN00C'}); the {@code EIBCALEN = 0} first-entry bounce target. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Admin-menu program (COBOL literal {@code 'COADM01C'}); the PF3 fallback and fixed PF12 target. */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /** An originating program other than the admin menu, used to assert the PF3 "return to origin" branch. */
    private static final String ORIGIN_PROGRAM = "COUSR00C";

    /** Standard 8-character user id ({@code SEC-USR-ID PIC X(08)}) used across the fixtures. */
    private static final String USER_ID = "USER0001";

    /** Expected green success line for {@link #USER_ID} (COBOL {@code STRING 'User ' ... ' has been deleted ...'}). */
    private static final String EXPECTED_DELETED_MESSAGE = "User USER0001 has been deleted ...";

    /**
     * Session-scoped navigation/selection context (COMMAREA {@code COCOM01Y} replacement); mocked so
     * hand-off writes ({@code setToProgram}) and the enter/re-enter reads can be verified.
     */
    @Mock
    private CardDemoContext context;

    /**
     * {@code USRSEC} repository (VSAM KSDS replacement); mocked so the read
     * ({@link UserSecurityRepository#findByUsrIdForUpdate(String)}) and delete
     * ({@link UserSecurityRepository#delete(Object)}) access paths can be stubbed and verified.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Session-revocation collaborator (findings #8/#43); mocked so the after-commit revocation on a
     * successful delete can be verified without a live session registry. Because these unit tests run
     * outside any transaction, {@code scheduleSessionRevocation} calls it directly.
     */
    @Mock
    private SessionRevocationService sessionRevocationService;

    /** Service under test, wired by constructor injection with the three mocks above. */
    @InjectMocks
    private UserDeleteService service;

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a real {@link UserSecurity} fixture for the supplied id. The (cleartext) password is
     * deliberately populated so the tests can prove it is <em>never</em> surfaced on the delete
     * screen (parity item 4): the record carries a password, but the delete flow must never read it.
     *
     * @param usrId the user id ({@code SEC-USR-ID})
     * @return a populated {@link UserSecurity} (first name {@code John}, last name {@code Doe},
     *         password {@code SECRET01}, type {@code U})
     */
    private static UserSecurity userFixture(String usrId) {
        UserSecurity user = new UserSecurity();
        user.setUsrId(usrId);
        user.setUsrFname("John");
        user.setUsrLname("Doe");
        user.setUsrPwd("SECRET01");
        user.setUsrType("U");
        return user;
    }

    /**
     * Builds a user-delete form carrying only the search key {@code USRIDIN}, mirroring the 3270 map
     * {@code COUSR3A} the COBOL program receives before a lookup.
     *
     * @param usridin the user id to delete (may be {@code null} or blank)
     * @return a form whose {@code USRIDIN} field is set to {@code usridin}
     */
    private static COUSR03Form formWithUserId(String usridin) {
        COUSR03Form form = new COUSR03Form();
        form.setUsridin(usridin);
        return form;
    }

    /**
     * Builds a fully populated user-delete form (id + display fields), used by the clear/initialize
     * tests to prove every field is reset.
     *
     * @return a form with {@code USRIDIN}, {@code FNAME}, {@code LNAME} and {@code USRTYPE} all set
     */
    private static COUSR03Form populatedForm() {
        COUSR03Form form = new COUSR03Form();
        form.setUsridin(USER_ID);
        form.setFname("John");
        form.setLname("Doe");
        form.setUsrtype("U");
        return form;
    }

    // ==========================================================================================
    // mainEntry - MAIN-PARA (first entry, first display, and EVALUATE EIBAID re-entry dispatch)
    // ==========================================================================================

    /**
     * First entry with no COMMAREA (COBOL {@code IF EIBCALEN = 0}, reproduced by
     * {@link CardDemoContext#isNew()}): {@code MAIN-PARA} moves {@code 'COSGN00C'} to
     * {@code CDEMO-TO-PROGRAM} and performs {@code RETURN-TO-PREV-SCREEN}, producing a redirect back
     * to the sign-on program. No lookup or delete occurs (covers first-entry, parity item 7).
     */
    @Test
    void mainEntry_firstEntryNoCommarea_redirectsToSignon() {
        when(context.isNew()).thenReturn(true);

        UserDeleteService.UserDeleteResult result = service.mainEntry(UserDeleteService.AidKey.ENTER, formWithUserId(USER_ID));

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.action()).isEqualTo(UserDeleteService.RoutingAction.REDIRECT);
        assertThat(result.hasMessage()).isFalse();
        verify(context).isNew();
        verify(context).setToProgram(SIGNON_PROGRAM);
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * First display with no list selection (COBOL {@code IF NOT CDEMO-PGM-REENTER} with
     * {@code CDEMO-CU03-USR-SELECTED = SPACES}): {@code MAIN-PARA} sets re-entry
     * ({@link CardDemoContext#markReenter()}) and performs a plain {@code SEND-USRDEL-SCREEN} with no
     * message and no lookup (covers the first-display/enter branch, parity item 7).
     */
    @Test
    void mainEntry_firstDisplayNoSelection_showsEmptyScreen() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);

        UserDeleteService.UserDeleteResult result = service.mainEntry(UserDeleteService.AidKey.ENTER, formWithUserId("   "));

        assertThat(result.action()).isEqualTo(UserDeleteService.RoutingAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.NONE);
        assertThat(result.hasMessage()).isFalse();
        verify(context).isNew();
        verify(context).isProgramEnter();
        verify(context).markReenter();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * First display <em>with</em> a list selection (COBOL
     * {@code IF CDEMO-CU03-USR-SELECTED NOT = SPACES ... PERFORM PROCESS-ENTER-KEY}): {@code MAIN-PARA}
     * sets re-entry and auto-performs the confirmation lookup, so the user is read and the neutral
     * {@code "Press PF5 ..."} prompt is shown. Still no delete occurs.
     */
    @Test
    void mainEntry_firstDisplayWithSelection_autoLooksUpUser() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID)).thenReturn(Optional.of(userFixture(USER_ID)));

        COUSR03Form form = formWithUserId(USER_ID);
        UserDeleteService.UserDeleteResult result = service.mainEntry(UserDeleteService.AidKey.ENTER, form);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.NEUTRAL);
        assertThat(result.message()).isEqualTo(MSG_PRESS_PF5_TO_DELETE);
        assertThat(form.getFname()).isEqualTo("John");
        assertThat(form.getLname()).isEqualTo("Doe");
        assertThat(form.getUsrtype()).isEqualTo("U");
        verify(context).isNew();
        verify(context).isProgramEnter();
        verify(context).markReenter();
        verifyNoMoreInteractions(context);
        verify(userSecurityRepository).findByUsrIdForUpdate(USER_ID);
        verify(userSecurityRepository, never()).delete(any());
        verifyNoMoreInteractions(userSecurityRepository);
    }

    /**
     * <b>Two-step gate, step 1.</b> Re-entry with ENTER (COBOL {@code EVALUATE EIBAID WHEN DFHENTER}
     * &rarr; {@code PROCESS-ENTER-KEY}) looks the user up, populates the read-only display fields, and
     * shows the verbatim neutral confirm prompt {@code "Press PF5 key to delete this user ..."}. The
     * defining parity assertion: at this step <b>no delete happens</b> &mdash; neither
     * {@link UserSecurityRepository#delete(Object)} nor {@code deleteById} is invoked.
     */
    @Test
    void mainEntry_reentryEnterKey_step1ShowsConfirmPromptAndDoesNotDelete() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID)).thenReturn(Optional.of(userFixture(USER_ID)));

        COUSR03Form form = formWithUserId(USER_ID);
        UserDeleteService.UserDeleteResult result = service.mainEntry(UserDeleteService.AidKey.ENTER, form);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.NEUTRAL);
        assertThat(result.message()).isEqualTo(MSG_PRESS_PF5_TO_DELETE);
        assertThat(form.getFname()).isEqualTo("John");
        assertThat(form.getLname()).isEqualTo("Doe");
        assertThat(form.getUsrtype()).isEqualTo("U");
        verify(userSecurityRepository).findByUsrIdForUpdate(USER_ID);
        verify(userSecurityRepository, never()).delete(any());
        verify(userSecurityRepository, never()).deleteById(any());
        verifyNoMoreInteractions(userSecurityRepository);
        verify(context).isNew();
        verify(context).isProgramEnter();
        verifyNoMoreInteractions(context);
    }

    /**
     * <b>Two-step gate, step 2.</b> Re-entry with PF5 (COBOL {@code EVALUATE EIBAID WHEN DFHPF5}
     * &rarr; {@code DELETE-USER-INFO} &rarr; {@code DELETE-USER-SEC-FILE}) performs the actual delete:
     * exactly one {@link UserSecurityRepository#delete(Object)} of the selected user, and the green
     * {@code "User USER0001 has been deleted ..."} success line (parity item 2).
     */
    @Test
    void mainEntry_reentryPf5Key_step2DeletesUserExactlyOnce() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        UserSecurity user = userFixture(USER_ID);
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID)).thenReturn(Optional.of(user));

        UserDeleteService.UserDeleteResult result = service.mainEntry(UserDeleteService.AidKey.PF5, formWithUserId(USER_ID));

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo(EXPECTED_DELETED_MESSAGE);
        verify(userSecurityRepository).findByUsrIdForUpdate(USER_ID);
        verify(userSecurityRepository).delete(user);
        verifyNoMoreInteractions(userSecurityRepository);
        verify(context).isNew();
        verify(context).isProgramEnter();
        verifyNoMoreInteractions(context);
    }

    /**
     * Review findings #8/#43: a successful delete revokes the deleted user's live sessions. In this
     * pure-Mockito unit no transaction is active, so {@code scheduleSessionRevocation} invokes the
     * collaborator directly and the interaction is asserted here; the after-commit ordering (revoke only
     * once the delete commits) is proven by the integration tests. The revoked id is the deleted
     * record's own id, covering the self-delete case (COUSR03C has no self guard, so it is allowed).
     */
    @Test
    void successfulDelete_revokesDeletedUserSessions() {
        UserSecurity user = userFixture(USER_ID);

        UserDeleteService.UserDeleteResult result = service.deleteUserSecFile(user, formWithUserId(USER_ID));

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.SUCCESS);
        verify(userSecurityRepository).delete(user);
        verify(sessionRevocationService).revokeSessions(USER_ID);
    }

    /**
     * Review findings #8/#43: a delete that fails at the repository must NOT revoke any session (the
     * record was not removed). The revocation collaborator is never touched on the {@code WHEN OTHER}
     * error path.
     */
    @Test
    void failedDelete_doesNotRevokeSessions() {
        UserSecurity user = userFixture(USER_ID);
        doThrow(new DataAccessResourceFailureException("io")).when(userSecurityRepository).delete(user);

        UserDeleteService.UserDeleteResult result =
                service.deleteUserSecFile(user, formWithUserId(USER_ID));

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        verify(sessionRevocationService, never()).revokeSessions(any());
    }

    /**
     * Re-entry with PF3 and no recorded originating program (COBOL
     * {@code IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES -> MOVE 'COADM01C' TO CDEMO-TO-PROGRAM}):
     * the hand-off target defaults to the admin menu {@code COADM01C} and a redirect is produced
     * (parity item 5). A blank ({@code "   "}) from-program exercises the {@code SPACES} branch.
     */
    @Test
    void mainEntry_reentryPf3Key_blankOrigin_returnsToAdminMenu() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(context.getFromProgram()).thenReturn("   ");

        UserDeleteService.UserDeleteResult result = service.mainEntry(UserDeleteService.AidKey.PF3, formWithUserId(USER_ID));

        assertThat(result.isRedirect()).isTrue();
        verify(context).isNew();
        verify(context).isProgramEnter();
        verify(context).getFromProgram();
        verify(context).setToProgram(ADMIN_PROGRAM);
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * Re-entry with PF3 and a recorded originating program (COBOL {@code ELSE MOVE CDEMO-FROM-PROGRAM
     * TO CDEMO-TO-PROGRAM}): the hand-off target is that origin program (here {@code COUSR00C}), not
     * the admin menu, and a redirect is produced.
     */
    @Test
    void mainEntry_reentryPf3Key_withOrigin_returnsToOriginProgram() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(context.getFromProgram()).thenReturn(ORIGIN_PROGRAM);

        UserDeleteService.UserDeleteResult result = service.mainEntry(UserDeleteService.AidKey.PF3, formWithUserId(USER_ID));

        assertThat(result.isRedirect()).isTrue();
        verify(context).isNew();
        verify(context).isProgramEnter();
        verify(context).getFromProgram();
        verify(context).setToProgram(ORIGIN_PROGRAM);
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * Re-entry with PF4 (COBOL {@code EVALUATE EIBAID WHEN DFHPF4} &rarr; {@code CLEAR-CURRENT-SCREEN}):
     * every screen field is reset and a plain screen re-display with no message is produced. No
     * hand-off write and no repository access occur (parity item 6).
     */
    @Test
    void mainEntry_reentryPf4Key_clearsScreen() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        COUSR03Form form = populatedForm();
        UserDeleteService.UserDeleteResult result = service.mainEntry(UserDeleteService.AidKey.PF4, form);

        assertThat(result.action()).isEqualTo(UserDeleteService.RoutingAction.SHOW_SCREEN);
        assertThat(result.hasMessage()).isFalse();
        assertThat(form.getUsridin()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        verify(context).isNew();
        verify(context).isProgramEnter();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * Re-entry with PF12 (COBOL {@code EVALUATE EIBAID WHEN DFHPF12} &rarr; {@code MOVE 'COADM01C' TO
     * CDEMO-TO-PROGRAM}): a fixed redirect to the admin menu {@code COADM01C}, regardless of the
     * originating program.
     */
    @Test
    void mainEntry_reentryPf12Key_returnsToAdminMenu() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        UserDeleteService.UserDeleteResult result = service.mainEntry(UserDeleteService.AidKey.PF12, formWithUserId(USER_ID));

        assertThat(result.isRedirect()).isTrue();
        verify(context).isNew();
        verify(context).isProgramEnter();
        verify(context).setToProgram(ADMIN_PROGRAM);
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * Re-entry with any other key (COBOL {@code EVALUATE EIBAID WHEN OTHER}): the invalid-key error
     * message (COBOL {@code CCDA-MSG-INVALID-KEY}) is returned with no routing and no repository
     * access.
     */
    @Test
    void mainEntry_reentryOtherKey_returnsInvalidKeyMessage() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        UserDeleteService.UserDeleteResult result = service.mainEntry(UserDeleteService.AidKey.OTHER, formWithUserId(USER_ID));

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        assertThat(result.isRedirect()).isFalse();
        verify(context).isNew();
        verify(context).isProgramEnter();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * A {@code null} AID key collapses to the COBOL {@code WHEN OTHER} default, yielding the
     * invalid-key message exactly like an explicitly unmapped key. Guards the null-key edge of the
     * dispatch.
     */
    @Test
    void mainEntry_reentryNullKey_treatedAsInvalidKey() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        UserDeleteService.UserDeleteResult result = service.mainEntry(null, formWithUserId(USER_ID));

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        verify(context).isNew();
        verify(context).isProgramEnter();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    // ==========================================================================================
    // processEnterKey - PROCESS-ENTER-KEY (step 1: confirmation lookup, never deletes)
    // ==========================================================================================

    /**
     * {@code PROCESS-ENTER-KEY} with a keyed user id reads the record, moves
     * {@code SEC-USR-FNAME}/{@code SEC-USR-LNAME}/{@code SEC-USR-TYPE} to the display fields, and
     * returns the neutral {@code "Press PF5 key to delete this user ..."} prompt (COBOL
     * {@code MOVE DFHNEUTR TO ERRMSGC}). This lookup-only paragraph never touches the injected context
     * and never deletes.
     */
    @Test
    void processEnterKey_withValidUser_populatesDisplayFieldsAndReturnsNeutralPrompt() {
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID)).thenReturn(Optional.of(userFixture(USER_ID)));

        COUSR03Form form = formWithUserId(USER_ID);
        UserDeleteService.UserDeleteResult result = service.processEnterKey(form, context);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.NEUTRAL);
        assertThat(result.message()).isEqualTo(MSG_PRESS_PF5_TO_DELETE);
        assertThat(form.getFname()).isEqualTo("John");
        assertThat(form.getLname()).isEqualTo("Doe");
        assertThat(form.getUsrtype()).isEqualTo("U");
        verify(userSecurityRepository).findByUsrIdForUpdate(USER_ID);
        verify(userSecurityRepository, never()).delete(any());
        verify(userSecurityRepository, never()).deleteById(any());
        verifyNoMoreInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-ENTER-KEY} with a blank user id (COBOL {@code WHEN USRIDINI = SPACES OR
     * LOW-VALUES}) yields the {@code "User ID can NOT be empty..."} error and performs no lookup, so
     * the repository is never touched.
     */
    @Test
    void processEnterKey_withBlankUserId_returnsEmptyIdErrorAndDoesNotLookUp() {
        UserDeleteService.UserDeleteResult result = service.processEnterKey(formWithUserId("   "), context);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USER_ID_EMPTY);
        verifyNoInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    /**
     * Finding P13-INPUT-01: an id carrying an embedded NUL (U+0000, COBOL LOW-VALUES) is rejected at
     * the boundary as a controlled keyed NOTFND ("User ID NOT found...") with no repository access, so
     * a value PostgreSQL cannot store (SQLSTATE 22021) can never poison the transaction and escape as
     * UnexpectedRollbackException / HTTP 500. The repository must never be consulted.
     */
    @Test
    void processEnterKey_withEmbeddedNulUserId_returnsNotFoundAndDoesNotLookUp() {
        UserDeleteService.UserDeleteResult result =
                service.processEnterKey(formWithUserId("A\u0000B"), context);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USER_ID_NOT_FOUND);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * A genuine data-access failure during the confirmation read (COBOL {@code READ-USER-SEC-FILE
     * WHEN OTHER}) is caught and reproduced as the {@code "Unable to lookup User..."} error line;
     * the screen is re-displayed without aborting and no delete is attempted.
     */
    @Test
    void processEnterKey_whenLookupThrowsDataAccess_returnsUnableToLookupError() {
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID))
                .thenThrow(new DataAccessResourceFailureException("simulated I/O failure"));

        UserDeleteService.UserDeleteResult result = service.processEnterKey(formWithUserId(USER_ID), context);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_LOOKUP);
        verify(userSecurityRepository).findByUsrIdForUpdate(USER_ID);
        verify(userSecurityRepository, never()).delete(any());
        verifyNoMoreInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    /**
     * Parity item 4 (behavioral): the confirmation display reads only the first name, last name and
     * user type &mdash; it must <b>never</b> read the (cleartext) password. Using a mocked
     * {@link UserSecurity}, the three display getters are stubbed and consumed, while
     * {@link UserSecurity#getUsrPwd()} is verified never to be invoked, matching {@code COUSR03C}
     * which does not show the password on the delete screen.
     */
    @Test
    void processEnterKey_neverReadsThePassword() {
        UserSecurity user = mock(UserSecurity.class);
        when(user.getUsrFname()).thenReturn("John");
        when(user.getUsrLname()).thenReturn("Doe");
        when(user.getUsrType()).thenReturn("U");
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID)).thenReturn(Optional.of(user));

        UserDeleteService.UserDeleteResult result = service.processEnterKey(formWithUserId(USER_ID), context);

        assertThat(result.message()).isEqualTo(MSG_PRESS_PF5_TO_DELETE);
        verify(user).getUsrFname();
        verify(user).getUsrLname();
        verify(user).getUsrType();
        verify(user, never()).getUsrPwd();
        verifyNoInteractions(context);
    }

    // ==========================================================================================
    // deleteUserInfo - DELETE-USER-INFO (step 2: read-then-delete)
    // ==========================================================================================

    /**
     * {@code DELETE-USER-INFO} with a valid keyed user reads the record and performs the delete
     * (COBOL {@code PERFORM READ-USER-SEC-FILE} then {@code PERFORM DELETE-USER-SEC-FILE}): exactly
     * one {@link UserSecurityRepository#delete(Object)} of the read user, the green success line, and
     * the screen fields cleared afterwards ({@code INITIALIZE-ALL-FIELDS}). The lookup-and-delete
     * paragraph does not touch the injected context.
     */
    @Test
    void deleteUserInfo_withValidUser_deletesExactlyOnceAndReturnsSuccess() {
        UserSecurity user = userFixture(USER_ID);
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID)).thenReturn(Optional.of(user));

        COUSR03Form form = formWithUserId(USER_ID);
        UserDeleteService.UserDeleteResult result = service.deleteUserInfo(form, context);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo(EXPECTED_DELETED_MESSAGE);
        assertThat(form.getUsridin()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        verify(userSecurityRepository).findByUsrIdForUpdate(USER_ID);
        verify(userSecurityRepository).delete(user);
        verifyNoMoreInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    /**
     * {@code DELETE-USER-INFO} with a blank user id (COBOL {@code WHEN USRIDINI = SPACES OR
     * LOW-VALUES}) yields the {@code "User ID can NOT be empty..."} error and performs no I/O.
     */
    @Test
    void deleteUserInfo_withBlankUserId_returnsEmptyIdErrorAndDoesNotDelete() {
        UserDeleteService.UserDeleteResult result = service.deleteUserInfo(formWithUserId(null), context);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USER_ID_EMPTY);
        verifyNoInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    /**
     * Finding P13-INPUT-01 via the PF5 commit path: an id carrying an embedded NUL (U+0000, COBOL
     * LOW-VALUES) is rejected at the boundary as a controlled keyed NOTFND ("User ID NOT found...")
     * before the {@code READ ... UPDATE}, so a value PostgreSQL cannot store (SQLSTATE 22021) can
     * never enter the {@code @Transactional} unit-of-work, mark it rollback-only, and escape as
     * UnexpectedRollbackException / HTTP 500. Neither the read nor the delete is reached.
     */
    @Test
    void deleteUserInfo_withEmbeddedNulUserId_returnsNotFoundAndDoesNotDelete() {
        UserDeleteService.UserDeleteResult result =
                service.deleteUserInfo(formWithUserId("A\u0000B"), context);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USER_ID_NOT_FOUND);
        verifyNoInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    /**
     * A genuine data-access failure during the read step of {@code DELETE-USER-INFO} is caught and
     * reproduced as the {@code "Unable to lookup User..."} error line, and the delete is
     * short-circuited (no {@code delete} call).
     */
    @Test
    void deleteUserInfo_whenReadThrowsDataAccess_returnsUnableToLookupErrorAndDoesNotDelete() {
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID))
                .thenThrow(new DataAccessResourceFailureException("simulated I/O failure"));

        UserDeleteService.UserDeleteResult result = service.deleteUserInfo(formWithUserId(USER_ID), context);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_LOOKUP);
        verify(userSecurityRepository).findByUsrIdForUpdate(USER_ID);
        verify(userSecurityRepository, never()).delete(any());
        verifyNoMoreInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    /**
     * Parity item 3 via the PF5 path: when the keyed read finds nothing (COBOL {@code NOTFND}),
     * {@code READ-USER-SEC-FILE} moves {@code "User ID NOT found..."} to {@code WS-MESSAGE}, sets
     * {@code ERR-FLG-ON}, short-circuits the delete, and re-displays the SAME screen inline (the
     * failed CICS {@code READ ... UPDATE} left nothing to delete) - it is not an abend.
     * {@code DELETE-USER-INFO} therefore catches the {@link RecordNotFoundException} and returns an
     * ERROR-severity outcome carrying that literal (AAP &sect;0.6.5 exception parity), and the
     * delete is never reached.
     */
    @Test
    void deleteUserInfo_whenUserNotFound_returnsInlineErrorAndDoesNotDelete() {
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        COUSR03Form form = formWithUserId(USER_ID);
        UserDeleteService.UserDeleteResult result = service.deleteUserInfo(form, context);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USER_ID_NOT_FOUND);

        verify(userSecurityRepository).findByUsrIdForUpdate(USER_ID);
        verify(userSecurityRepository, never()).delete(any());
        verifyNoMoreInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    // ==========================================================================================
    // readUserSecFile - READ-USER-SEC-FILE (keyed read + NOTFND mapping)
    // ==========================================================================================

    /**
     * {@code READ-USER-SEC-FILE} on a present record (COBOL {@code WHEN DFHRESP(NORMAL)}) returns the
     * matching {@link UserSecurity} unchanged for the caller to display or delete.
     */
    @Test
    void readUserSecFile_whenPresent_returnsUser() {
        UserSecurity user = userFixture(USER_ID);
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID)).thenReturn(Optional.of(user));

        UserSecurity found = service.readUserSecFile(USER_ID);

        assertThat(found).isSameAs(user);
        verify(userSecurityRepository).findByUsrIdForUpdate(USER_ID);
        verifyNoMoreInteractions(userSecurityRepository);
    }

    /**
     * {@code READ-USER-SEC-FILE} on a missing record (COBOL {@code WHEN DFHRESP(NOTFND)}) raises
     * {@link RecordNotFoundException} carrying the byte-exact {@code "User ID NOT found..."} message
     * (parity item 3).
     */
    @Test
    void readUserSecFile_whenAbsent_throwsRecordNotFound() {
        when(userSecurityRepository.findByUsrIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readUserSecFile(USER_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_USER_ID_NOT_FOUND);
        verify(userSecurityRepository).findByUsrIdForUpdate(USER_ID);
        verifyNoMoreInteractions(userSecurityRepository);
    }

    // ==========================================================================================
    // deleteUserSecFile - DELETE-USER-SEC-FILE (the actual delete + result branches)
    // ==========================================================================================

    /**
     * {@code DELETE-USER-SEC-FILE} on success (COBOL {@code WHEN DFHRESP(NORMAL)}) deletes the record
     * exactly once, performs {@code INITIALIZE-ALL-FIELDS} (clearing the form), and returns the green
     * success line {@code "User USER0001 has been deleted ..."} (COBOL {@code MOVE DFHGREEN} +
     * {@code STRING}).
     */
    @Test
    void deleteUserSecFile_onSuccess_clearsFieldsAndReturnsGreenSuccess() {
        UserSecurity user = userFixture(USER_ID);
        COUSR03Form form = populatedForm();

        UserDeleteService.UserDeleteResult result = service.deleteUserSecFile(user, form);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo(EXPECTED_DELETED_MESSAGE);
        assertThat(form.getUsridin()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        verify(userSecurityRepository).delete(user);
        verifyNoMoreInteractions(userSecurityRepository);
    }

    /**
     * The success line reproduces the COBOL {@code STRING 'User ' DELIMITED BY SIZE, SEC-USR-ID
     * DELIMITED BY SPACE, ' has been deleted ...'}: the user id is truncated at its first space. A
     * fixture id {@code "AB CD"} therefore yields {@code "User AB has been deleted ..."}.
     */
    @Test
    void deleteUserSecFile_successMessage_truncatesUserIdAtFirstSpace() {
        UserSecurity user = userFixture("AB CD");

        UserDeleteService.UserDeleteResult result = service.deleteUserSecFile(user, populatedForm());

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo("User AB has been deleted ...");
        verify(userSecurityRepository).delete(user);
        verifyNoMoreInteractions(userSecurityRepository);
    }

    /**
     * {@code DELETE-USER-SEC-FILE} when the delete reports an empty result (COBOL {@code WHEN
     * DFHRESP(NOTFND)}, defensive/inert after a same-transaction read) returns the
     * {@code "User ID NOT found..."} error and leaves the form untouched (fields not cleared, since
     * {@code INITIALIZE-ALL-FIELDS} runs only on the {@code NORMAL} branch).
     */
    @Test
    void deleteUserSecFile_whenEmptyResult_returnsNotFoundErrorAndDoesNotClearFields() {
        UserSecurity user = userFixture(USER_ID);
        doThrow(new EmptyResultDataAccessException(1)).when(userSecurityRepository).delete(user);

        COUSR03Form form = populatedForm();
        UserDeleteService.UserDeleteResult result = service.deleteUserSecFile(user, form);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USER_ID_NOT_FOUND);
        assertThat(form.getUsridin()).isEqualTo(USER_ID);
        verify(userSecurityRepository).delete(user);
        verifyNoMoreInteractions(userSecurityRepository);
    }

    /**
     * {@code DELETE-USER-SEC-FILE} on a genuine data-access failure (COBOL {@code WHEN OTHER}) returns
     * the {@code "Unable to Update User..."} error (the original's capitalized {@code "Update"}) and
     * leaves the form untouched.
     */
    @Test
    void deleteUserSecFile_whenDataAccessFailure_returnsUnableToUpdateError() {
        UserSecurity user = userFixture(USER_ID);
        doThrow(new DataAccessResourceFailureException("simulated I/O failure"))
                .when(userSecurityRepository).delete(user);

        COUSR03Form form = populatedForm();
        UserDeleteService.UserDeleteResult result = service.deleteUserSecFile(user, form);

        assertThat(result.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_UPDATE);
        assertThat(form.getUsridin()).isEqualTo(USER_ID);
        verify(userSecurityRepository).delete(user);
        verifyNoMoreInteractions(userSecurityRepository);
    }

    // ==========================================================================================
    // clearCurrentScreen / initializeAllFields - CLEAR-CURRENT-SCREEN / INITIALIZE-ALL-FIELDS
    // ==========================================================================================

    /**
     * {@code CLEAR-CURRENT-SCREEN} (COBOL {@code PERFORM INITIALIZE-ALL-FIELDS} then
     * {@code SEND-USRDEL-SCREEN}) resets every screen field and returns a plain re-display with no
     * message (parity item 6). No repository access occurs.
     */
    @Test
    void clearCurrentScreen_resetsFieldsAndReturnsPlainScreen() {
        COUSR03Form form = populatedForm();

        UserDeleteService.UserDeleteResult result = service.clearCurrentScreen(form);

        assertThat(result.action()).isEqualTo(UserDeleteService.RoutingAction.SHOW_SCREEN);
        assertThat(result.hasMessage()).isFalse();
        assertThat(form.getUsridin()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        verifyNoInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} clears {@code USRIDINI}, {@code FNAMEI}, {@code LNAMEI} and
     * {@code USRTYPEI} to spaces (empty strings in the migrated form).
     */
    @Test
    void initializeAllFields_clearsAllScreenFields() {
        COUSR03Form form = populatedForm();

        service.initializeAllFields(form);

        assertThat(form.getUsridin()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} tolerates a {@code null} form as a no-op (defensive guard), never
     * throwing.
     */
    @Test
    void initializeAllFields_withNullForm_isNoOp() {
        assertThatCode(() -> service.initializeAllFields(null)).doesNotThrowAnyException();
    }

    // ==========================================================================================
    // UserDeleteResult - outcome model (factory methods + canonical-constructor validation)
    // ==========================================================================================

    /**
     * The factory methods produce outcomes with the correct {@link UserDeleteService.RoutingAction},
     * {@link UserDeleteService.MessageSeverity} and message, and the {@code isRedirect()} /
     * {@code hasMessage()} helpers agree, preserving the COBOL color contract
     * (error/neutral/success) and the {@code XCTL}-vs-{@code SEND} decision.
     */
    @Test
    void userDeleteResult_factoryMethods_carryActionSeverityAndMessage() {
        UserDeleteService.UserDeleteResult redirect = UserDeleteService.UserDeleteResult.redirect();
        assertThat(redirect.isRedirect()).isTrue();
        assertThat(redirect.action()).isEqualTo(UserDeleteService.RoutingAction.REDIRECT);
        assertThat(redirect.severity()).isEqualTo(UserDeleteService.MessageSeverity.NONE);
        assertThat(redirect.hasMessage()).isFalse();

        UserDeleteService.UserDeleteResult show = UserDeleteService.UserDeleteResult.showScreen();
        assertThat(show.isRedirect()).isFalse();
        assertThat(show.action()).isEqualTo(UserDeleteService.RoutingAction.SHOW_SCREEN);
        assertThat(show.severity()).isEqualTo(UserDeleteService.MessageSeverity.NONE);
        assertThat(show.hasMessage()).isFalse();

        UserDeleteService.UserDeleteResult error = UserDeleteService.UserDeleteResult.error(MSG_USER_ID_EMPTY);
        assertThat(error.severity()).isEqualTo(UserDeleteService.MessageSeverity.ERROR);
        assertThat(error.message()).isEqualTo(MSG_USER_ID_EMPTY);
        assertThat(error.hasMessage()).isTrue();

        UserDeleteService.UserDeleteResult neutral = UserDeleteService.UserDeleteResult.neutral(MSG_PRESS_PF5_TO_DELETE);
        assertThat(neutral.severity()).isEqualTo(UserDeleteService.MessageSeverity.NEUTRAL);
        assertThat(neutral.message()).isEqualTo(MSG_PRESS_PF5_TO_DELETE);

        UserDeleteService.UserDeleteResult success = UserDeleteService.UserDeleteResult.success(EXPECTED_DELETED_MESSAGE);
        assertThat(success.severity()).isEqualTo(UserDeleteService.MessageSeverity.SUCCESS);
        assertThat(success.message()).isEqualTo(EXPECTED_DELETED_MESSAGE);
    }

    /**
     * The canonical constructor rejects a {@code null} action or severity with
     * {@link IllegalArgumentException} and normalizes a {@code null} message to the empty string.
     */
    @Test
    void userDeleteResult_canonicalConstructor_validatesArguments() {
        assertThatThrownBy(() -> new UserDeleteService.UserDeleteResult(
                null, "x", UserDeleteService.MessageSeverity.ERROR))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new UserDeleteService.UserDeleteResult(
                UserDeleteService.RoutingAction.SHOW_SCREEN, "x", null))
                .isInstanceOf(IllegalArgumentException.class);

        UserDeleteService.UserDeleteResult normalized = new UserDeleteService.UserDeleteResult(
                UserDeleteService.RoutingAction.SHOW_SCREEN, null, UserDeleteService.MessageSeverity.NONE);
        assertThat(normalized.message()).isEmpty();
        assertThat(normalized.hasMessage()).isFalse();
    }

    // ==========================================================================================
    // COUSR03Form structural parity - parity item 4 (NO password field)
    // ==========================================================================================

    /**
     * Parity item 4 (structural): the user-delete screen form has exactly the 11 display fields of
     * the BMS map {@code COUSR3A} and &mdash; unlike the add/update screens &mdash; <b>no password
     * field</b>. Synthetic members (e.g. the {@code $jacocoData} field / {@code $jacocoInit} method
     * added by the on-the-fly coverage agent) are filtered out so the assertion is robust under
     * JaCoCo instrumentation.
     */
    @Test
    void cousr03Form_hasElevenDisplayFieldsAndNoPasswordField() {
        // The hidden single-use confirmation nonce (review finding F12) is a deliberate non-BMS
        // control field (it restores the BMS protected-confirmation-field contract over HTTP);
        // assert it is present, then exclude it from the BMS display-field count.
        assertThat(Arrays.stream(COUSR03Form.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName))
                .contains("confirmToken");

        // The ERRMSG colour attribute errmsgColor (review finding #11) is the migration of the BMS
        // ERRMSGC colour attribute - a non-BMS presentation-control field that drives the
        // severity-driven message-line colour (DFHGREEN success / DFHNEUTR neutral / DFHRED error).
        // Like confirmToken it is not one of the COUSR3A display fields, so assert it is present and
        // then exclude it from the BMS display-field count (matching COUSR03FormTest).
        assertThat(Arrays.stream(COUSR03Form.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName))
                .contains("errmsgColor");

        long declaredFields = Arrays.stream(COUSR03Form.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !"confirmToken".equals(field.getName()))
                .filter(field -> !"errmsgColor".equals(field.getName()))
                .count();
        assertThat(declaredFields).isEqualTo(11L);

        assertThat(Arrays.stream(COUSR03Form.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName))
                .noneMatch(UserDeleteServiceTest::referencesPassword);

        assertThat(Arrays.stream(COUSR03Form.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName))
                .noneMatch(UserDeleteServiceTest::referencesPassword);
    }

    /**
     * Reports whether a field or method name refers to a password, matching either the COBOL-style
     * {@code pwd} abbreviation or the word {@code password} (case-insensitively).
     *
     * @param name the field or method name to test
     * @return {@code true} if {@code name} references a password
     */
    private static boolean referencesPassword(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("pwd") || lower.contains("password");
    }
}

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COSGN00Form;
import com.aws.carddemo.security.CardDemoUserDetails;
import com.aws.carddemo.security.CardDemoUserDetailsService;
import com.aws.carddemo.service.online.SignonService.AidKey;
import com.aws.carddemo.service.online.SignonService.CursorField;
import com.aws.carddemo.service.online.SignonService.MessageSeverity;
import com.aws.carddemo.service.online.SignonService.RoutingAction;
import com.aws.carddemo.service.online.SignonService.SignonResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Pure-Mockito unit tests for {@link SignonService}.
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COSGN00C.cbl}
 * (CICS COBOL program {@code COSGN00C}, transaction id {@code CC00}) &mdash; the AWS
 * CardDemo sign-on screen. These tests assert one-for-one control-flow parity with the
 * numbered COBOL paragraphs that the service migrates (AAP &sect;0.1.2 / &sect;0.4.1 /
 * &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code MAIN-PARA} (COSGN00C L73) &rarr; {@link SignonService#mainEntry} &mdash;
 *       the first-entry empty screen ({@code IF EIBCALEN = 0}, L80) and the
 *       {@code EVALUATE EIBAID} re-entry branches for {@code DFHENTER} (L86),
 *       {@code DFHPF3} (L88), and {@code WHEN OTHER} (L91).</li>
 *   <li>{@code PROCESS-ENTER-KEY} (COSGN00C L108) &rarr;
 *       {@link SignonService#processEnterKey} &mdash; the blank User ID (L118) and blank
 *       Password (L123) validation branches and the {@code FUNCTION UPPER-CASE(USERIDI)}
 *       fold into the context (L132).</li>
 *   <li>{@code READ-USER-SEC-FILE} (COSGN00C L209) &rarr;
 *       {@link SignonService#readUserSecFile} (the {@code WHEN 0} password-match routing,
 *       L222-241, admin {@code XCTL COADM01C} at L231, user {@code XCTL COMEN01C} at L236)
 *       and {@link SignonService#mapAuthenticationFailure} (the failure branches:
 *       {@code WHEN 0} mismatch "Wrong Password" L242, {@code WHEN 13} "User not found"
 *       L247, {@code WHEN OTHER} "Unable to verify" L252).</li>
 * </ul>
 *
 * <p><b>Test tier:</b> a strict-stubs pure-Mockito unit test ({@link MockitoExtension});
 * it uses no database, no Spring {@code ApplicationContext}, and no Testcontainers. The
 * session-scoped {@link CardDemoContext} (the {@code COMMAREA} replacement,
 * {@code COPY COCOM01Y}) and the {@link CardDemoUserDetailsService} user store are mocked,
 * and the service is exercised in isolation. Routing outcomes are asserted on the mocked
 * {@code CardDemoContext} (COBOL {@code MOVE ... TO CDEMO-*} before the {@code XCTL})
 * rather than on any HTTP redirect, because the actual redirect is controller-tier.</p>
 *
 * <p><b>Security delegation note (AAP &sect;0.6.7):</b> the cleartext
 * {@code IF SEC-USR-PWD = WS-USER-PWD} comparison lives in the security layer
 * ({@code CardDemoAuthenticationProvider}), not in this service, so a wrong-password
 * outcome is exercised through {@link SignonService#mapAuthenticationFailure} receiving a
 * {@link BadCredentialsException}, and a missing user through a
 * {@link UsernameNotFoundException} &mdash; both from {@code org.springframework.security}.
 * The upper-casing of the User ID (COBOL {@code FUNCTION UPPER-CASE}) is realized in
 * {@code processEnterKey} and verified with an {@link ArgumentCaptor}.</p>
 */
@ExtendWith(MockitoExtension.class)
class SignonServiceTest {

    /** COBOL {@code 'Please enter User ID ...'} (COSGN00C L119, blank-user-id branch). */
    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /** COBOL {@code 'Please enter Password ...'} (COSGN00C L124, blank-password branch). */
    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /** COBOL {@code 'Wrong Password. Try again ...'} (COSGN00C L242, {@code WHEN 0} mismatch). */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** COBOL {@code 'User not found. Try again ...'} (COSGN00C L249, {@code WHEN 13}). */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /** COBOL {@code 'Unable to verify the User ...'} (COSGN00C L254, {@code WHEN OTHER}). */
    private static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /** COBOL {@code CCDA-MSG-THANK-YOU} (COSGN00C L89, PF3 exit); visible (trimmed) text. */
    private static final String MSG_THANK_YOU = "Thank you for using CardDemo application...";

    /** COBOL {@code CCDA-MSG-INVALID-KEY} (COSGN00C L93, unrecognized key); visible text. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** COBOL {@code WS-PGMNAME 'COSGN00C'}; recorded as the origin program on a successful sign-on. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** COBOL {@code WS-TRANID 'CC00'}; recorded as the origin transaction id on a successful sign-on. */
    private static final String SIGNON_TRANSACTION_ID = "CC00";

    /** Admin {@code XCTL} target program (COBOL {@code XCTL PROGRAM('COADM01C')}, L231). */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /** Admin menu transaction id (tran of {@code COADM01C}). */
    private static final String ADMIN_TRANSACTION_ID = "CA00";

    /** Regular-user {@code XCTL} target program (COBOL {@code XCTL PROGRAM('COMEN01C')}, L236). */
    private static final String USER_PROGRAM = "COMEN01C";

    /** Main menu transaction id (tran of {@code COMEN01C}). */
    private static final String USER_TRANSACTION_ID = "CM00";

    /**
     * Seed cleartext password shared by the reference users ({@code V2__reference_data.sql}); a
     * demo value, never a real credential.
     */
    private static final String SEED_PASSWORD = "PASSWORD";

    /** Session-scoped {@code COMMAREA} replacement, mocked so routing writes are observable. */
    @Mock
    private CardDemoContext context;

    /**
     * The user store / authority producer (COBOL {@code EXEC CICS READ USRSEC}); mocked so the
     * delegated {@code loadUserByUsername} lookup can be stubbed per test.
     */
    @Mock
    private CardDemoUserDetailsService userDetailsService;

    /** Service under test; Mockito constructor-injects the two mocked collaborators. */
    @InjectMocks
    private SignonService service;

    /**
     * Builds a real {@link COSGN00Form} fixture with the User ID and password input fields set,
     * mirroring the {@code COSGN0AI} symbolic input map ({@code USERIDI} / {@code PASSWDI}).
     *
     * @param userid the raw {@code USERIDI} value (may be {@code null} to model a never-submitted field)
     * @param passwd the raw {@code PASSWDI} value (may be {@code null})
     * @return a populated sign-on form
     */
    private static COSGN00Form form(String userid, String passwd) {
        COSGN00Form form = new COSGN00Form();
        form.setUserid(userid);
        form.setPasswd(passwd);
        return form;
    }

    /**
     * Builds a real administrator principal ({@code SEC-USR-TYPE 'A'} &rarr; {@code ROLE_ADMIN}),
     * as {@link CardDemoUserDetailsService} would return on a successful {@code USRSEC} read.
     *
     * @param userId the {@code SEC-USR-ID} (already upper-cased, as the lookup key is)
     * @return a real {@link CardDemoUserDetails} carrying the single {@code ROLE_ADMIN} authority
     */
    private static CardDemoUserDetails adminPrincipal(String userId) {
        return new CardDemoUserDetails(userId, SEED_PASSWORD,
                CardDemoUserDetails.USER_TYPE_ADMIN, "Admin", "User");
    }

    /**
     * Builds a real regular-user principal ({@code SEC-USR-TYPE 'U'} &rarr; {@code ROLE_USER}).
     *
     * @param userId the {@code SEC-USR-ID} (already upper-cased, as the lookup key is)
     * @return a real {@link CardDemoUserDetails} carrying the single {@code ROLE_USER} authority
     */
    private static CardDemoUserDetails userPrincipal(String userId) {
        return new CardDemoUserDetails(userId, SEED_PASSWORD,
                CardDemoUserDetails.USER_TYPE_USER, "Regular", "User");
    }

    // ------------------------------------------------------------------
    // MAIN-PARA (COSGN00C L73) -> mainEntry
    // ------------------------------------------------------------------

    /**
     * Item 1 &mdash; First entry (COBOL {@code IF EIBCALEN = 0}, COSGN00C L80-85). A fresh,
     * never-initialized context reports {@link CardDemoContext#isNew()} {@code true}, so
     * {@code MAIN-PARA} presents the empty sign-on screen with the cursor on the User ID field and
     * <em>no</em> message, and performs no authentication. Because sign-on is the application
     * entry point, {@code EIBCALEN = 0} is the normal initial display (not a bounce), and the AID
     * is irrelevant on this path.
     */
    @Test
    void mainEntry_firstEntry_showsEmptySignonScreenWithNoMessage() {
        when(context.isNew()).thenReturn(true);

        SignonResult result = service.mainEntry(AidKey.ENTER, form("ADMIN001", SEED_PASSWORD));

        assertThat(result.isShowSignon()).isTrue();
        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SIGNON);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.cursorField()).isEqualTo(CursorField.USER_ID);
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
        assertThat(result.isError()).isFalse();
        verifyNoInteractions(userDetailsService);
    }

    /**
     * Item 10 &mdash; PF3 from the sign-on screen (COBOL {@code WHEN DFHPF3} &rarr;
     * {@code MOVE CCDA-MSG-THANK-YOU}, {@code PERFORM SEND-PLAIN-TEXT}, {@code EXEC CICS RETURN},
     * COSGN00C L88-90). On re-entry the service ends the conversation with the neutral thank-you
     * message; the actual plain-text send and terminal return are controller-tier, so only the
     * exposed {@link RoutingAction#EXIT} outcome is asserted.
     */
    @Test
    void mainEntry_pf3_endsConversationWithThankYou() {
        when(context.isNew()).thenReturn(false);

        SignonResult result = service.mainEntry(AidKey.PF3, form("ADMIN001", SEED_PASSWORD));

        assertThat(result.isExit()).isTrue();
        assertThat(result.action()).isEqualTo(RoutingAction.EXIT);
        assertThat(result.severity()).isEqualTo(MessageSeverity.INFORMATION);
        assertThat(result.message()).isEqualTo(MSG_THANK_YOU);
        verifyNoInteractions(userDetailsService);
    }

    /**
     * {@code MAIN-PARA} {@code WHEN OTHER} (COSGN00C L91-94): an unrecognized attention key on
     * re-entry redisplays the sign-on screen with the invalid-key error and, faithful to the
     * COBOL, performs no cursor move ({@link CursorField#NONE}) and no authentication.
     */
    @Test
    void mainEntry_unrecognizedKey_returnsInvalidKeyError() {
        when(context.isNew()).thenReturn(false);

        SignonResult result = service.mainEntry(AidKey.OTHER, form("ADMIN001", SEED_PASSWORD));

        assertThat(result.isShowSignon()).isTrue();
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        assertThat(result.cursorField()).isEqualTo(CursorField.NONE);
        verifyNoInteractions(userDetailsService);
    }

    /**
     * A {@code null} attention key collapses to the COBOL {@code WHEN OTHER} default (COSGN00C
     * L91-94), yielding the same invalid-key error as an explicitly unrecognized key.
     */
    @Test
    void mainEntry_nullKey_treatedAsOtherInvalidKey() {
        when(context.isNew()).thenReturn(false);

        SignonResult result = service.mainEntry(null, form("ADMIN001", SEED_PASSWORD));

        assertThat(result.isShowSignon()).isTrue();
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        assertThat(result.cursorField()).isEqualTo(CursorField.NONE);
        verifyNoInteractions(userDetailsService);
    }

    /**
     * {@code MAIN-PARA} {@code WHEN DFHENTER} (COSGN00C L86-87) delegates to
     * {@code PROCESS-ENTER-KEY} using the injected context. With both fields present, validation
     * passes and the service signals {@link RoutingAction#AUTHENTICATE}, upper-casing the User ID
     * into the context on the way (COBOL {@code MOVE FUNCTION UPPER-CASE(USERIDI) TO CDEMO-USER-ID},
     * L132), which confirms the ENTER branch reaches {@code processEnterKey}.
     */
    @Test
    void mainEntry_enterKeyOnReentry_delegatesToProcessEnterKey() {
        when(context.isNew()).thenReturn(false);

        SignonResult result = service.mainEntry(AidKey.ENTER, form("USER0001", SEED_PASSWORD));

        assertThat(result.isAuthenticate()).isTrue();
        assertThat(result.action()).isEqualTo(RoutingAction.AUTHENTICATE);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        verify(context).setUserId("USER0001");
    }

    // ------------------------------------------------------------------
    // PROCESS-ENTER-KEY (COSGN00C L108) -> processEnterKey
    // ------------------------------------------------------------------

    /**
     * Item 2 &mdash; {@code PROCESS-ENTER-KEY} {@code WHEN USERIDI = SPACES OR LOW-VALUES}
     * (COSGN00C L118-122). A spaces-only User ID yields the "Please enter User ID ..." error with
     * the cursor on the User ID field ({@code MOVE -1 TO USERIDL}); no {@code USRSEC} read is
     * performed and nothing is written to the context (the Java service returns early).
     */
    @Test
    void processEnterKey_blankUserId_returnsEnterUserIdError() {
        SignonResult result = service.processEnterKey(form("        ", SEED_PASSWORD), context);

        assertThat(result.isShowSignon()).isTrue();
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_ENTER_USER_ID);
        assertThat(result.cursorField()).isEqualTo(CursorField.USER_ID);
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(context, never()).setUserId(any());
    }

    /**
     * Item 3 &mdash; {@code PROCESS-ENTER-KEY} {@code WHEN PASSWDI = SPACES OR LOW-VALUES}
     * (COSGN00C L123-127). With a valid User ID but a {@code null} (never-submitted) password, the
     * service returns the "Please enter Password ..." error with the cursor on the Password field
     * ({@code MOVE -1 TO PASSWDL}); no {@code USRSEC} read and no context write occur. Using
     * {@code null} here also exercises the {@code null} arm of the COBOL {@code = SPACES OR
     * LOW-VALUES} emptiness test.
     */
    @Test
    void processEnterKey_blankPassword_returnsEnterPasswordError() {
        SignonResult result = service.processEnterKey(form("USER0001", null), context);

        assertThat(result.isShowSignon()).isTrue();
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_ENTER_PASSWORD);
        assertThat(result.cursorField()).isEqualTo(CursorField.PASSWORD);
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(context, never()).setUserId(any());
    }

    /**
     * Item 4 &mdash; User ID upper-casing (COBOL {@code MOVE FUNCTION UPPER-CASE(USERIDI) TO
     * WS-USER-ID, CDEMO-USER-ID}, COSGN00C L132-134). This fold is realized in
     * {@code PROCESS-ENTER-KEY}: a lower-case {@code "admin001"} is written to the context as
     * {@code "ADMIN001"} (captured via an {@link ArgumentCaptor}), and the result signals
     * {@link RoutingAction#AUTHENTICATE}. The controller then feeds that upper-cased id from the
     * context into {@code readUserSecFile} &rarr; {@code loadUserByUsername}, so the lookup key is
     * upper-cased end-to-end (the delegated-lookup half of the fold is captured in
     * {@link #readUserSecFile_adminUser_routesToAdminMenu()}). The form field itself is left
     * exactly as typed, because the COBOL upper-cases only the working-storage / COMMAREA copies,
     * never {@code USERIDI}.
     */
    @Test
    void processEnterKey_validCredentials_upperCasesUserIdIntoContext() {
        COSGN00Form form = form("admin001", SEED_PASSWORD);

        SignonResult result = service.processEnterKey(form, context);

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(context).setUserId(userIdCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo("ADMIN001");
        assertThat(result.isAuthenticate()).isTrue();
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(form.getUserid()).isEqualTo("admin001");
        verify(userDetailsService, never()).loadUserByUsername(any());
    }

    // ------------------------------------------------------------------
    // READ-USER-SEC-FILE success routing (COSGN00C L209) -> readUserSecFile
    // ------------------------------------------------------------------

    /**
     * Item 5 &mdash; Successful sign-on, administrator (COBOL {@code READ-USER-SEC-FILE}
     * {@code WHEN 0} with password match and {@code IF CDEMO-USRTYP-ADMIN}, COSGN00C L222-233). The
     * loaded principal carries {@code SEC-USR-TYPE 'A'} ({@code ROLE_ADMIN}); the service records
     * the hand-off ({@code CDEMO-FROM-TRANID}/{@code CDEMO-FROM-PROGRAM}) and identity
     * ({@code CDEMO-USER-ID}), resets the target program to the enter state ({@code MOVE ZEROS TO
     * CDEMO-PGM-CONTEXT} &rarr; {@link CardDemoContext#markEnter()}), sets the admin type, and
     * routes to the admin menu {@code COADM01C} / tran {@code CA00} ({@code XCTL PROGRAM('COADM01C')}
     * at L231). The upper-cased id reaches {@code loadUserByUsername} (item 4, captured), and the
     * regular-user type is never set.
     */
    @Test
    void readUserSecFile_adminUser_routesToAdminMenu() {
        when(userDetailsService.loadUserByUsername("ADMIN001"))
                .thenReturn(adminPrincipal("ADMIN001"));

        SignonResult result = service.readUserSecFile("ADMIN001", context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);

        ArgumentCaptor<String> lookupCaptor = ArgumentCaptor.forClass(String.class);
        verify(userDetailsService).loadUserByUsername(lookupCaptor.capture());
        assertThat(lookupCaptor.getValue()).isEqualTo("ADMIN001");

        verify(context).setFromTranid(SIGNON_TRANSACTION_ID);
        verify(context).setFromProgram(SIGNON_PROGRAM);
        verify(context).setUserId("ADMIN001");
        verify(context).markEnter();
        verify(context).setAdmin();
        verify(context).setToProgram(ADMIN_PROGRAM);
        verify(context).setToTranid(ADMIN_TRANSACTION_ID);
        verify(context, never()).setUser();
    }

    /**
     * Item 6 &mdash; Successful sign-on, regular user (COBOL {@code READ-USER-SEC-FILE}
     * {@code WHEN 0} with password match, the {@code ELSE} of {@code IF CDEMO-USRTYP-ADMIN},
     * COSGN00C L234-238). The loaded principal carries {@code SEC-USR-TYPE 'U'} ({@code ROLE_USER});
     * the service records the same hand-off/identity/enter-state and routes to the main menu
     * {@code COMEN01C} / tran {@code CM00} ({@code XCTL PROGRAM('COMEN01C')} at L236). The admin
     * type is never set.
     */
    @Test
    void readUserSecFile_regularUser_routesToMainMenu() {
        when(userDetailsService.loadUserByUsername("USER0001"))
                .thenReturn(userPrincipal("USER0001"));

        SignonResult result = service.readUserSecFile("USER0001", context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        verify(context).setFromTranid(SIGNON_TRANSACTION_ID);
        verify(context).setFromProgram(SIGNON_PROGRAM);
        verify(context).setUserId("USER0001");
        verify(context).markEnter();
        verify(context).setUser();
        verify(context).setToProgram(USER_PROGRAM);
        verify(context).setToTranid(USER_TRANSACTION_ID);
        verify(context, never()).setAdmin();
    }

    /**
     * {@code readUserSecFile} is invoked only on the post-authentication success path, so the
     * record is expected to be present. Defensively, if the delegated {@code USRSEC} read
     * ({@link CardDemoUserDetailsService#loadUserByUsername(String)}) instead reports the user
     * absent (the COBOL {@code WHEN 13} condition), the {@link UsernameNotFoundException}
     * propagates unswallowed and no routing is written to the context.
     */
    @Test
    void readUserSecFile_userVanished_propagatesUsernameNotFound() {
        when(userDetailsService.loadUserByUsername("GONE0001"))
                .thenThrow(new UsernameNotFoundException("User not found: GONE0001"));

        assertThatThrownBy(() -> service.readUserSecFile("GONE0001", context))
                .isInstanceOf(UsernameNotFoundException.class);

        verifyNoInteractions(context);
    }

    // ------------------------------------------------------------------
    // READ-USER-SEC-FILE failure branches (COSGN00C L221-256) -> mapAuthenticationFailure
    // ------------------------------------------------------------------

    /**
     * Item 7 &mdash; {@code READ-USER-SEC-FILE} {@code WHEN 13} (COSGN00C L247-251). A
     * {@link UsernameNotFoundException} (raised by the delegated {@code USRSEC} read when the id
     * has no record) is translated to the "User not found. Try again ..." error with the cursor on
     * the User ID field ({@code MOVE -1 TO USERIDL}). This is the production "catch and set
     * message" behavior; the message is deliberately kept distinct from the wrong-password message,
     * preserving the COBOL split.
     */
    @Test
    void mapAuthenticationFailure_userNotFound_returnsUserNotFoundError() {
        SignonResult result = service.mapAuthenticationFailure(
                new UsernameNotFoundException("User not found: NOSUCH01"));

        assertThat(result.isShowSignon()).isTrue();
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_USER_NOT_FOUND);
        assertThat(result.cursorField()).isEqualTo(CursorField.USER_ID);
    }

    /**
     * Item 8 &mdash; {@code READ-USER-SEC-FILE} {@code WHEN 0} with a cleartext {@code SEC-USR-PWD}
     * mismatch (COSGN00C L242-246). The {@link BadCredentialsException} raised by the
     * authentication provider (which owns the cleartext compare, AAP &sect;0.6.7) is translated to
     * the "Wrong Password. Try again ..." error with the cursor on the Password field
     * ({@code MOVE -1 TO PASSWDL}).
     */
    @Test
    void mapAuthenticationFailure_wrongPassword_returnsWrongPasswordError() {
        SignonResult result = service.mapAuthenticationFailure(
                new BadCredentialsException("Bad credentials"));

        assertThat(result.isShowSignon()).isTrue();
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_WRONG_PASSWORD);
        assertThat(result.cursorField()).isEqualTo(CursorField.PASSWORD);
    }

    /**
     * Item 9 &mdash; {@code READ-USER-SEC-FILE} {@code WHEN OTHER} (COSGN00C L252-256). Any other
     * Spring Security {@code AuthenticationException} (here an {@link AuthenticationServiceException}
     * standing in for an unexpected verify failure, e.g. the COBOL non-zero/non-13 response codes)
     * is translated to the "Unable to verify the User ..." error with the cursor on the User ID
     * field. The production design models this distinct branch, so it is asserted (not skipped).
     */
    @Test
    void mapAuthenticationFailure_otherFailure_returnsUnableToVerifyError() {
        SignonResult result = service.mapAuthenticationFailure(
                new AuthenticationServiceException("authentication backend unavailable"));

        assertThat(result.isShowSignon()).isTrue();
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_VERIFY);
        assertThat(result.cursorField()).isEqualTo(CursorField.USER_ID);
    }

    /**
     * Item 9 (null guard) &mdash; a {@code null} failure also collapses to the COBOL
     * {@code WHEN OTHER} default (COSGN00C L252-256), yielding the "Unable to verify the User ..."
     * error with the cursor on the User ID field.
     */
    @Test
    void mapAuthenticationFailure_nullFailure_returnsUnableToVerifyError() {
        SignonResult result = service.mapAuthenticationFailure(null);

        assertThat(result.isShowSignon()).isTrue();
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_VERIFY);
        assertThat(result.cursorField()).isEqualTo(CursorField.USER_ID);
    }
}

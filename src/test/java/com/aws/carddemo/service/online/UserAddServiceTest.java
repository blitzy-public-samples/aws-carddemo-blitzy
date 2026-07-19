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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COUSR01Form;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.repository.UserSecurityRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link UserAddService}, the Java migration of the CICS COBOL program
 * {@code COUSR01C} (the AWS CardDemo "Add a new Regular/Admin user to the USRSEC file" screen).
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COUSR01C.cbl} &mdash; program
 * {@code COUSR01C}, CICS transaction id {@code CU01}. These tests assert control-flow parity with
 * the program's numbered paragraphs:</p>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link UserAddService#mainEntry(UserAddService.AidKey, COUSR01Form)}
 *       (the {@code EIBCALEN = 0} first-entry bounce to {@code COSGN00C}, the {@code EVALUATE EIBAID}
 *       dispatch, the {@code DFHPF3} return to {@code COADM01C}, the {@code DFHPF4} clear, and the
 *       {@code WHEN OTHER} invalid-key branch)</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr;
 *       {@link UserAddService#processEnterKey(COUSR01Form, CardDemoContext)} (the
 *       {@code EVALUATE TRUE} first-empty-field-wins validation of the five entry fields, then the
 *       form&rarr;{@code SEC-USER-DATA} move)</li>
 *   <li>{@code WRITE-USER-SEC-FILE} &rarr;
 *       {@link UserAddService#writeUserSecFile(UserSecurity, COUSR01Form)} (the
 *       {@code EXEC CICS WRITE} plus the {@code EVALUATE WS-RESP-CD}: {@code NORMAL},
 *       {@code DUPKEY}/{@code DUPREC}, {@code WHEN OTHER})</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} &rarr; {@link UserAddService#clearCurrentScreen(COUSR01Form)}
 *       (PF4: {@code INITIALIZE-ALL-FIELDS} then redisplay)</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr; {@link UserAddService#initializeAllFields(COUSR01Form)}
 *       ({@code MOVE SPACES TO USERIDI, FNAMEI, LNAMEI, PASSWDI, USRTYPEI})</li>
 * </ul>
 *
 * <p><b>Test tier.</b> This is a strict-stubs pure-Mockito unit test:
 * {@link MockitoExtension} with {@link Mock} collaborators and an {@link InjectMocks} service. It
 * starts no Spring context, opens no database connection, and loads no persistence provider &mdash;
 * the {@code @Transactional} annotations on the service are metadata only and are inert here, so the
 * business logic is verified in complete isolation and the suite is green under Surefire without
 * Docker, a database, or Spring. The presentation paragraphs ({@code SEND-USRADD-SCREEN},
 * {@code RECEIVE-USRADD-SCREEN}, {@code POPULATE-HEADER-INFO}) are owned by the paired
 * {@code UserAdminController} and are therefore not exercised here.</p>
 *
 * <p><b>How write parity is asserted.</b> The CICS {@code EXEC CICS WRITE DATASET('USRSEC')} is
 * reproduced by {@link UserSecurityRepository#save(Object)}; the persisted record is captured with an
 * {@link ArgumentCaptor} of {@link UserSecurity} and its fields are asserted field-by-field against
 * the entered form. The duplicate-key condition (COBOL {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)},
 * FILE STATUS {@code "22"}) is asserted as the CardDemo {@link DuplicateKeyException}. The redirect
 * hand-off (COBOL {@code XCTL} via {@code RETURN-TO-PREV-SCREEN}) is asserted through the returned
 * {@link UserAddService.UserAddResult} target program plus the origin writes on the mocked
 * {@link CardDemoContext} ({@code CDEMO-FROM-TRANID = 'CU01'}, {@code CDEMO-FROM-PROGRAM = 'COUSR01C'},
 * {@code CDEMO-PGM-CONTEXT = 0}).</p>
 *
 * <p><b>Cleartext-password parity (AAP &sect;0.6.7).</b> {@code COUSR01C} moved the entered password
 * verbatim into {@code SEC-USR-PWD}, so {@link #processEnterKey_storesPasswordAsCleartext_noHashing()}
 * asserts the captured {@link UserSecurity#getUsrPwd()} equals the exact plaintext entered, with no
 * hashing. Introducing password hashing is a deliberately out-of-scope next task recorded in
 * {@code docs/decision-log.md}; this test locks the current parity behaviour.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserAddServiceTest {

    // ------------------------------------------------------------------------------------------
    // Byte-exact COBOL message literals (parity oracle: legacy/cbl/COUSR01C.cbl)
    // ------------------------------------------------------------------------------------------

    /** COBOL {@code 'First Name can NOT be empty...'} (COUSR01C.cbl:120). */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** COBOL {@code 'Last Name can NOT be empty...'} (COUSR01C.cbl:126). */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** COBOL {@code 'User ID can NOT be empty...'} (COUSR01C.cbl:132). */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** COBOL {@code 'Password can NOT be empty...'} (COUSR01C.cbl:138). */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** COBOL {@code 'User Type can NOT be empty...'} (COUSR01C.cbl:144). */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /** COBOL {@code 'User ID already exist...'} duplicate-key line (COUSR01C.cbl:263). */
    private static final String MSG_USER_ID_EXISTS = "User ID already exist...";

    /** COBOL {@code 'Unable to Add User...'} catch-all write-failure line (COUSR01C.cbl:270). */
    private static final String MSG_UNABLE_TO_ADD = "Unable to Add User...";

    /** Invalid-key literal (COBOL {@code CCDA-MSG-INVALID-KEY}) for the {@code WHEN OTHER} branch. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    // ------------------------------------------------------------------------------------------
    // Program / routing constants (COBOL WS-PGMNAME, WS-TRANID, XCTL targets)
    // ------------------------------------------------------------------------------------------

    /** This program's name (COBOL {@code WS-PGMNAME = 'COUSR01C'}), recorded as the hand-off origin. */
    private static final String PROGRAM_NAME = "COUSR01C";

    /** This program's transaction id (COBOL {@code WS-TRANID = 'CU01'}), recorded as the origin. */
    private static final String TRANSACTION_ID = "CU01";

    /** Sign-on program ({@code COSGN00C}) &mdash; the {@code EIBCALEN = 0} first-entry bounce target. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Admin-menu program ({@code COADM01C}) &mdash; the {@code DFHPF3} return destination. */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    // ------------------------------------------------------------------------------------------
    // Valid-form field fixtures (all within the BMS @Size bounds of COUSR01Form)
    // ------------------------------------------------------------------------------------------

    /** Fully-valid first name ({@code FNAMEI}, PIC X(20)). */
    private static final String VALID_FNAME = "John";

    /** Fully-valid last name ({@code LNAMEI}, PIC X(20)). */
    private static final String VALID_LNAME = "Doe";

    /** Fully-valid user id ({@code USERIDI}, PIC X(08)). */
    private static final String VALID_USERID = "USER0001";

    /** Fully-valid cleartext password ({@code PASSWDI}, PIC X(08)). */
    private static final String VALID_PASSWD = "PASS1234";

    /** Fully-valid user type ({@code USRTYPEI}, PIC X(01)); {@code 'U'} = regular user. */
    private static final String VALID_USRTYPE = "U";

    /**
     * Session-scoped navigation context (COMMAREA {@code COCOM01Y} replacement); mocked so the
     * first-entry detection ({@code EIBCALEN = 0}) and the {@code RETURN-TO-PREV-SCREEN} hand-off
     * writes can be stubbed and verified.
     */
    @Mock
    private CardDemoContext context;

    /**
     * The {@code USRSEC} repository standing in for {@code EXEC CICS WRITE DATASET('USRSEC')}; mocked
     * so the keyed-write existence check ({@code existsById}) and the insert ({@code save}) can be
     * stubbed, verified, and captured.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** Service under test, wired by constructor injection with the two mocks above. */
    @InjectMocks
    private UserAddService service;

    /** A fully-valid submitted add form, rebuilt fresh before every test. */
    private COUSR01Form form;

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /**
     * Rebuilds a fully-valid {@link COUSR01Form} before each test so that an empty-field test can
     * blank exactly one field and leave the other four populated, isolating the field under test.
     * No stubbing is performed here, keeping the suite strict-stubs clean.
     */
    @BeforeEach
    void setUp() {
        form = validForm();
    }

    /**
     * Builds a fully-valid user-add form mirroring the 3270 map {@code COUSR1AI} the COBOL program
     * receives, with all five entry fields populated within their BMS field-length bounds.
     *
     * @return a populated {@link COUSR01Form} whose five entry fields all pass the non-empty check
     */
    private static COUSR01Form validForm() {
        COUSR01Form f = new COUSR01Form();
        f.setFname(VALID_FNAME);
        f.setLname(VALID_LNAME);
        f.setUserid(VALID_USERID);
        f.setPasswd(VALID_PASSWD);
        f.setUsrtype(VALID_USRTYPE);
        return f;
    }

    /**
     * Builds a fully-populated {@link UserSecurity} record (the COBOL {@code SEC-USER-DATA}) for the
     * direct {@code WRITE-USER-SEC-FILE} tests.
     *
     * @param usrId    the user id ({@code SEC-USR-ID}); the VSAM {@code USRSEC} primary key
     * @param usrFname the first name ({@code SEC-USR-FNAME})
     * @param usrLname the last name ({@code SEC-USR-LNAME})
     * @param usrPwd   the cleartext password ({@code SEC-USR-PWD})
     * @param usrType  the user type ({@code SEC-USR-TYPE})
     * @return a populated {@link UserSecurity} entity
     */
    private static UserSecurity userWith(String usrId, String usrFname, String usrLname,
            String usrPwd, String usrType) {
        UserSecurity user = new UserSecurity();
        user.setUsrId(usrId);
        user.setUsrFname(usrFname);
        user.setUsrLname(usrLname);
        user.setUsrPwd(usrPwd);
        user.setUsrType(usrType);
        return user;
    }

    // ------------------------------------------------------------------------------------------
    // processEnterKey - PROCESS-ENTER-KEY empty-field validation (EVALUATE TRUE: first empty wins)
    // ------------------------------------------------------------------------------------------

    /**
     * A blank (here {@code null}, the COBOL {@code LOW-VALUES}) first name is rejected with the
     * byte-exact "First Name can NOT be empty..." message and the cursor on the first-name field,
     * and performs no write (COBOL {@code WHEN FNAMEI = SPACES OR LOW-VALUES}).
     */
    @Test
    void processEnterKey_withEmptyFirstName_returnsFirstNameEmptyMessage() {
        form.setFname(null);

        UserAddService.UserAddResult result = service.processEnterKey(form, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_FIRST_NAME_EMPTY);
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.FIRST_NAME);
        assertThat(result.isRedirect()).isFalse();
        verify(userSecurityRepository, never()).save(any());
        verifyNoInteractions(context);
    }

    /**
     * A blank (empty-string) last name is rejected with the byte-exact "Last Name can NOT be
     * empty..." message and the cursor on the last-name field, and performs no write
     * (COBOL {@code WHEN LNAMEI = SPACES OR LOW-VALUES}).
     */
    @Test
    void processEnterKey_withEmptyLastName_returnsLastNameEmptyMessage() {
        form.setLname("");

        UserAddService.UserAddResult result = service.processEnterKey(form, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_LAST_NAME_EMPTY);
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.LAST_NAME);
        assertThat(result.isRedirect()).isFalse();
        verify(userSecurityRepository, never()).save(any());
        verifyNoInteractions(context);
    }

    /**
     * A blank (all-spaces, the COBOL {@code SPACES}) user id is rejected with the byte-exact "User ID
     * can NOT be empty..." message and the cursor on the user-id field, and performs no write
     * (COBOL {@code WHEN USERIDI = SPACES OR LOW-VALUES}).
     */
    @Test
    void processEnterKey_withEmptyUserId_returnsUserIdEmptyMessage() {
        form.setUserid("   ");

        UserAddService.UserAddResult result = service.processEnterKey(form, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_USER_ID_EMPTY);
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.USER_ID);
        assertThat(result.isRedirect()).isFalse();
        verify(userSecurityRepository, never()).save(any());
        verifyNoInteractions(context);
    }

    /**
     * A blank (empty-string) password is rejected with the byte-exact "Password can NOT be empty..."
     * message and the cursor on the password field, and performs no write
     * (COBOL {@code WHEN PASSWDI = SPACES OR LOW-VALUES}).
     */
    @Test
    void processEnterKey_withEmptyPassword_returnsPasswordEmptyMessage() {
        form.setPasswd("");

        UserAddService.UserAddResult result = service.processEnterKey(form, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_PASSWORD_EMPTY);
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.PASSWORD);
        assertThat(result.isRedirect()).isFalse();
        verify(userSecurityRepository, never()).save(any());
        verifyNoInteractions(context);
    }

    /**
     * A blank (all-spaces) user type is rejected with the byte-exact "User Type can NOT be empty..."
     * message and the cursor on the user-type field, and performs no write
     * (COBOL {@code WHEN USRTYPEI = SPACES OR LOW-VALUES}).
     */
    @Test
    void processEnterKey_withEmptyUserType_returnsUserTypeEmptyMessage() {
        form.setUsrtype("   ");

        UserAddService.UserAddResult result = service.processEnterKey(form, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_USER_TYPE_EMPTY);
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.USER_TYPE);
        assertThat(result.isRedirect()).isFalse();
        verify(userSecurityRepository, never()).save(any());
        verifyNoInteractions(context);
    }

    /**
     * The empty-field checks run in COBOL {@code EVALUATE TRUE} order, so the <em>first</em> empty
     * field wins even when several are empty. With the first name <em>and</em> the last name both
     * blank, the first-name message is produced (not the last-name one), matching the top-down
     * {@code WHEN} evaluation of {@code PROCESS-ENTER-KEY}.
     */
    @Test
    void processEnterKey_withMultipleEmptyFields_reportsFirstFieldOnly() {
        form.setFname("");
        form.setLname("");

        UserAddService.UserAddResult result = service.processEnterKey(form, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_FIRST_NAME_EMPTY);
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.FIRST_NAME);
        verify(userSecurityRepository, never()).save(any());
        verifyNoInteractions(context);
    }

    /**
     * A {@code null} form has no field values, so the first COBOL emptiness branch
     * ({@code FNAMEI = SPACES OR LOW-VALUES}) fires: the first-name-empty message is returned and no
     * write occurs. This guards the receive-map-absent edge.
     */
    @Test
    void processEnterKey_withNullForm_returnsFirstNameEmptyMessage() {
        UserAddService.UserAddResult result = service.processEnterKey(null, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_FIRST_NAME_EMPTY);
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.FIRST_NAME);
        verify(userSecurityRepository, never()).save(any());
        verifyNoInteractions(context);
    }

    // ------------------------------------------------------------------------------------------
    // processEnterKey - PROCESS-ENTER-KEY successful add (WHEN OTHER -> move -> WRITE-USER-SEC-FILE)
    // ------------------------------------------------------------------------------------------

    /**
     * With all five fields valid, {@code PROCESS-ENTER-KEY} falls through the {@code EVALUATE TRUE}
     * to {@code WHEN OTHER}, moves the form fields onto a new {@code SEC-USER-DATA}, and performs
     * {@code WRITE-USER-SEC-FILE}. The persisted {@link UserSecurity} is captured and asserted
     * field-by-field (COBOL {@code MOVE USERIDI/FNAMEI/LNAMEI/USRTYPEI TO SEC-USR-ID/-FNAME/-LNAME/
     * -TYPE}), and the green confirmation line and cursor are asserted. The context is untouched on
     * the successful add path.
     */
    @Test
    void processEnterKey_withAllFieldsValid_savesUserAndReturnsConfirmation() {
        // existsById defaults to false (unstubbed) so the keyed write proceeds; save returns null,
        // which the service ignores.
        UserAddService.UserAddResult result = service.processEnterKey(form, context);

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).existsById(VALID_USERID);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity saved = captor.getValue();
        assertThat(saved.getUsrId()).isEqualTo(VALID_USERID);
        assertThat(saved.getUsrFname()).isEqualTo(VALID_FNAME);
        assertThat(saved.getUsrLname()).isEqualTo(VALID_LNAME);
        assertThat(saved.getUsrType()).isEqualTo(VALID_USRTYPE);

        assertThat(result.error()).isFalse();
        assertThat(result.isRedirect()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo("User " + VALID_USERID + " has been added ...");
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.FIRST_NAME);
        verifyNoInteractions(context);
    }

    /**
     * <b>Cleartext-password parity (AAP &sect;0.6.7 &mdash; CRITICAL).</b> {@code COUSR01C} moves the
     * entered password verbatim into {@code SEC-USR-PWD}; the migration copies it with no hashing.
     * This test captures the persisted {@link UserSecurity} and asserts {@link UserSecurity#getUsrPwd()}
     * equals the <em>exact</em> plaintext entered &mdash; no BCrypt/PBKDF2/any transformation.
     *
     * <p>Next task (out of scope here, recorded in {@code docs/decision-log.md}): introduce password
     * hashing. Until then this assertion locks the current 100%-parity cleartext behaviour.</p>
     */
    @Test
    void processEnterKey_storesPasswordAsCleartext_noHashing() {
        String plaintext = "S3cr3t!x";
        form.setPasswd(plaintext);

        service.processEnterKey(form, context);

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        assertThat(captor.getValue().getUsrPwd())
                .as("password must be persisted as cleartext for COBOL parity (no hashing)")
                .isEqualTo(plaintext);
    }

    /**
     * The user type is only checked for non-emptiness; {@code COUSR01C} does <em>not</em> restrict it
     * to {@code 'A'}/{@code 'U'}. A non-empty, non-{@code A}/{@code U} type (here {@code "X"}) must
     * therefore pass the empty-check and be persisted verbatim &mdash; no validation the COBOL lacks
     * is added.
     */
    @Test
    void processEnterKey_withNonAdminOrUserType_passesEmptyCheckAndSaves() {
        form.setUsrtype("X");

        UserAddService.UserAddResult result = service.processEnterKey(form, context);

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        assertThat(captor.getValue().getUsrType()).isEqualTo("X");

        assertThat(result.error()).isFalse();
        assertThat(result.message()).isEqualTo("User " + VALID_USERID + " has been added ...");
        verifyNoInteractions(context);
    }

    // ------------------------------------------------------------------------------------------
    // writeUserSecFile - WRITE-USER-SEC-FILE (EXEC CICS WRITE; EVALUATE WS-RESP-CD)
    // ------------------------------------------------------------------------------------------

    /**
     * A keyed write whose {@code RIDFLD} key already exists is the COBOL
     * {@code WHEN DFHRESP(DUPKEY) / WHEN DFHRESP(DUPREC)} branch. It is detected deterministically by
     * {@code existsById} and surfaced as the CardDemo {@link DuplicateKeyException} carrying the
     * byte-exact "User ID already exist..." message and FILE STATUS {@code "22"}. No insert is
     * attempted, so nothing is persisted.
     */
    @Test
    void writeUserSecFile_whenUserIdAlreadyExists_throwsDuplicateKeyException() {
        UserSecurity user = userWith(VALID_USERID, VALID_FNAME, VALID_LNAME, VALID_PASSWD, VALID_USRTYPE);
        when(userSecurityRepository.existsById(VALID_USERID)).thenReturn(true);

        assertThatThrownBy(() -> service.writeUserSecFile(user, form))
                .isInstanceOfSatisfying(DuplicateKeyException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo(MSG_USER_ID_EXISTS);
                    assertThat(ex.getFileStatus()).isEqualTo(DuplicateKeyException.FILE_STATUS);
                    assertThat(ex.getFileStatus()).isEqualTo("22");
                });

        verify(userSecurityRepository).existsById(VALID_USERID);
        verify(userSecurityRepository, never()).save(any());
        verifyNoMoreInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    /**
     * A concurrent insert that slips past the {@code existsById} check makes {@code save} raise a
     * Spring {@link DataIntegrityViolationException}; this is still the CICS
     * {@code DUPKEY}/{@code DUPREC} condition, so the service maps it to the same
     * {@link DuplicateKeyException} (preserving the original as the cause) with the byte-exact
     * duplicate message. Being unchecked, it rolls the {@code @Transactional} write back.
     */
    @Test
    void writeUserSecFile_whenSaveThrowsIntegrityViolation_mapsToDuplicateKeyException() {
        UserSecurity user = userWith(VALID_USERID, VALID_FNAME, VALID_LNAME, VALID_PASSWD, VALID_USRTYPE);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> service.writeUserSecFile(user, form))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessage(MSG_USER_ID_EXISTS)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);

        verify(userSecurityRepository).save(any(UserSecurity.class));
    }

    /**
     * Any non-duplicate write failure is the COBOL {@code WHEN OTHER} branch: the service catches the
     * Spring {@code DataAccessException} (here a {@link RecoverableDataAccessException}) and returns
     * the byte-exact "Unable to Add User..." error redisplay with the cursor on the first-name field.
     * Because the write did not succeed, {@code INITIALIZE-ALL-FIELDS} is not performed, so the form
     * fields are left intact for correction.
     */
    @Test
    void writeUserSecFile_whenSaveThrowsOtherDataAccessError_returnsUnableToAddError() {
        UserSecurity user = userWith(VALID_USERID, VALID_FNAME, VALID_LNAME, VALID_PASSWD, VALID_USRTYPE);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenThrow(new RecoverableDataAccessException("transient I/O failure"));

        UserAddService.UserAddResult result = service.writeUserSecFile(user, form);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_ADD);
        assertThat(result.isRedirect()).isFalse();
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.FIRST_NAME);
        // Form NOT cleared: INITIALIZE-ALL-FIELDS runs only on the DFHRESP(NORMAL) path.
        assertThat(form.getFname()).isEqualTo(VALID_FNAME);
        assertThat(form.getUserid()).isEqualTo(VALID_USERID);
        verifyNoInteractions(context);
    }

    /**
     * On a successful write (COBOL {@code WHEN DFHRESP(NORMAL)}) the service performs
     * {@code INITIALIZE-ALL-FIELDS} (clearing the five entry fields in place) and builds the green
     * confirmation line with the COBOL {@code STRING 'User ' SEC-USR-ID DELIMITED BY SPACE ' has been
     * added ...'} concatenation, positioning the cursor on the first-name field.
     */
    @Test
    void writeUserSecFile_onSuccess_clearsFormAndBuildsGreenConfirmation() {
        UserSecurity user = userWith("ADMIN001", "Ann", "Admin", "PW123456", "A");

        UserAddService.UserAddResult result = service.writeUserSecFile(user, form);

        verify(userSecurityRepository).existsById("ADMIN001");
        verify(userSecurityRepository).save(user);
        assertThat(result.error()).isFalse();
        assertThat(result.isRedirect()).isFalse();
        assertThat(result.message()).isEqualTo("User ADMIN001 has been added ...");
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.FIRST_NAME);
        // INITIALIZE-ALL-FIELDS cleared each entry field in place.
        assertThat(form.getUserid()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getPasswd()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        verifyNoInteractions(context);
    }

    /**
     * The confirmation-message concatenation reproduces the COBOL {@code STRING ... SEC-USR-ID
     * DELIMITED BY SPACE ...}: only the characters of the user id up to the first space are copied.
     * A user id containing an embedded space ({@code "AB CDEF"}) therefore yields "User AB has been
     * added ...", proving the {@code DELIMITED BY SPACE} truncation is preserved.
     */
    @Test
    void writeUserSecFile_confirmationMessage_delimitsUserIdAtFirstSpace() {
        UserSecurity user = userWith("AB CDEF", "Ann", "Bee", "PW123456", "U");

        UserAddService.UserAddResult result = service.writeUserSecFile(user, form);

        assertThat(result.message()).isEqualTo("User AB has been added ...");
        verify(userSecurityRepository).save(user);
    }

    // ------------------------------------------------------------------------------------------
    // mainEntry - MAIN-PARA (EIBCALEN=0 first-entry bounce; EVALUATE EIBAID dispatch)
    // ------------------------------------------------------------------------------------------

    /**
     * On first entry into the transaction (COBOL {@code EIBCALEN = 0}, reproduced by
     * {@link CardDemoContext#isNew()}), {@code MAIN-PARA} moves {@code 'COSGN00C'} to
     * {@code CDEMO-TO-PROGRAM} and performs {@code RETURN-TO-PREV-SCREEN}, producing a redirect to the
     * sign-on program and recording this program as the origin. The AID key is irrelevant on first
     * entry (the {@code EVALUATE EIBAID} is not reached), so no write occurs even for {@code ENTER}.
     */
    @Test
    void mainEntry_firstEntry_redirectsToSignonProgram() {
        when(context.isNew()).thenReturn(true);

        UserAddService.UserAddResult result = service.mainEntry(UserAddService.AidKey.ENTER, form);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(SIGNON_PROGRAM);
        verify(context).isNew();
        verify(context).setToProgram(SIGNON_PROGRAM);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).markEnter();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * On re-entry with {@code DFHPF3}, {@code MAIN-PARA} moves {@code 'COADM01C'} to
     * {@code CDEMO-TO-PROGRAM} and performs {@code RETURN-TO-PREV-SCREEN}, redirecting back to the
     * admin menu and writing the origin hand-off fields ({@code CDEMO-FROM-TRANID = 'CU01'},
     * {@code CDEMO-FROM-PROGRAM = 'COUSR01C'}, {@code CDEMO-PGM-CONTEXT = 0}). No write occurs.
     */
    @Test
    void mainEntry_withPf3Key_returnsToAdminMenu() {
        when(context.isNew()).thenReturn(false);

        UserAddService.UserAddResult result = service.mainEntry(UserAddService.AidKey.PF3, form);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(ADMIN_MENU_PROGRAM);
        verify(context).isNew();
        verify(context).setToProgram(ADMIN_MENU_PROGRAM);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).markEnter();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * On re-entry with {@code DFHPF4}, {@code MAIN-PARA} performs {@code CLEAR-CURRENT-SCREEN}: the
     * entry fields are reset in place and a message-less redisplay is returned with the cursor on the
     * first-name field. No redirect, no message, and no write occur.
     */
    @Test
    void mainEntry_withPf4Key_clearsScreen() {
        when(context.isNew()).thenReturn(false);

        UserAddService.UserAddResult result = service.mainEntry(UserAddService.AidKey.PF4, form);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.hasMessage()).isFalse();
        assertThat(result.message()).isNull();
        assertThat(result.error()).isFalse();
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.FIRST_NAME);
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getUserid()).isEmpty();
        assertThat(form.getPasswd()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        verify(context).isNew();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * On re-entry with an unmapped AID key, {@code MAIN-PARA}'s {@code WHEN OTHER} branch yields the
     * invalid-key message ({@code CCDA-MSG-INVALID-KEY}) with the cursor on the first-name field and
     * performs no write.
     */
    @Test
    void mainEntry_withUnmappedKey_returnsInvalidKeyMessage() {
        when(context.isNew()).thenReturn(false);

        UserAddService.UserAddResult result = service.mainEntry(UserAddService.AidKey.OTHER, form);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.FIRST_NAME);
        assertThat(result.isRedirect()).isFalse();
        verify(context).isNew();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * A {@code null} AID key collapses to the {@code WHEN OTHER} default (matching the COBOL
     * fall-through), likewise yielding the invalid-key message with no write.
     */
    @Test
    void mainEntry_withNullKey_treatedAsOtherReturnsInvalidKey() {
        when(context.isNew()).thenReturn(false);

        UserAddService.UserAddResult result = service.mainEntry(null, form);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        assertThat(result.isRedirect()).isFalse();
        verify(context).isNew();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * On re-entry with {@code DFHENTER} and a fully-valid form, {@code MAIN-PARA} performs
     * {@code PROCESS-ENTER-KEY}, which validates the fields and writes the user end-to-end. This
     * confirms the {@code EVALUATE EIBAID WHEN DFHENTER} dispatch reaches the write: the persisted
     * record's user id is captured and the green confirmation line is returned.
     */
    @Test
    void mainEntry_reentryWithEnterKey_validatesAddsAndConfirms() {
        when(context.isNew()).thenReturn(false);

        UserAddService.UserAddResult result = service.mainEntry(UserAddService.AidKey.ENTER, form);

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        assertThat(captor.getValue().getUsrId()).isEqualTo(VALID_USERID);

        assertThat(result.error()).isFalse();
        assertThat(result.isRedirect()).isFalse();
        assertThat(result.message()).isEqualTo("User " + VALID_USERID + " has been added ...");
        verify(context).isNew();
        verifyNoMoreInteractions(context);
    }

    // ------------------------------------------------------------------------------------------
    // clearCurrentScreen - CLEAR-CURRENT-SCREEN / initializeAllFields - INITIALIZE-ALL-FIELDS
    // ------------------------------------------------------------------------------------------

    /**
     * {@code CLEAR-CURRENT-SCREEN} performs {@code INITIALIZE-ALL-FIELDS} then redisplays: the five
     * entry fields are reset in place and a message-less redisplay is returned with the cursor on the
     * first-name field. Neither collaborator is touched.
     */
    @Test
    void clearCurrentScreen_resetsFieldsAndReturnsMessagelessRedisplay() {
        UserAddService.UserAddResult result = service.clearCurrentScreen(form);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.hasMessage()).isFalse();
        assertThat(result.message()).isNull();
        assertThat(result.error()).isFalse();
        assertThat(result.cursorField()).isEqualTo(UserAddService.CursorField.FIRST_NAME);
        assertThat(form.getUserid()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getPasswd()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        verifyNoInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} resets each of the five entry fields to the empty string (the
     * trimmed form of a blanked fixed-width field), mirroring the COBOL
     * {@code MOVE SPACES TO USERIDI, FNAMEI, LNAMEI, PASSWDI, USRTYPEI}.
     */
    @Test
    void initializeAllFields_clearsAllEntryFields() {
        service.initializeAllFields(form);

        assertThat(form.getUserid()).isEmpty();
        assertThat(form.getFname()).isEmpty();
        assertThat(form.getLname()).isEmpty();
        assertThat(form.getPasswd()).isEmpty();
        assertThat(form.getUsrtype()).isEmpty();
        verifyNoInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * A {@code null} form is tolerated by {@code INITIALIZE-ALL-FIELDS} as a no-op: the call
     * completes without throwing and touches neither collaborator.
     */
    @Test
    void initializeAllFields_withNullForm_isNoOp() {
        service.initializeAllFields(null);

        verifyNoInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }
}

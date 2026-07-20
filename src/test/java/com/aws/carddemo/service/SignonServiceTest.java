/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link SignonService}, the Java
 * re-platform of the CardDemo COBOL sign-on program {@code COSGN00C} (source
 * {@code legacy/cbl/COSGN00C.cbl}, CICS transaction {@code CC00}).
 *
 * <p>The service has two collaborators &mdash; the {@link UserSecurityRepository}
 * (which replaces the legacy keyed CICS {@code READ} of the {@code USRSEC} KSDS)
 * and the Spring Security {@link PasswordEncoder} &mdash; both mocked here so the
 * tests exercise the service's own control flow in isolation. There is
 * <strong>no Spring context, no {@code @SpringBootTest}, no database, and no
 * Testcontainers</strong>; these are fast, deterministic unit tests.</p>
 *
 * <p>The tests lock down the behavioral-parity contract the migration must
 * preserve exactly (AAP &sect;0.8.3 "preserve public/observable contracts" and
 * &sect;0.9.2 field-contract parity), reproducing the COBOL
 * {@code PROCESS-ENTER-KEY} ({@code legacy/cbl/COSGN00C.cbl:L117-L140}) and
 * {@code READ-USER-SEC-FILE} ({@code legacy/cbl/COSGN00C.cbl:L209-L257})
 * paragraphs branch-for-branch and in order:</p>
 * <ol>
 *   <li><strong>Blank user id</strong> ({@code WHEN USERIDI = SPACES OR
 *       LOW-VALUES}, L118-L120) &rarr; the verbatim message
 *       {@code "Please enter User ID ..."}, before any file read.</li>
 *   <li><strong>Blank password</strong> ({@code WHEN PASSWDI = SPACES OR
 *       LOW-VALUES}, L123-L125) &rarr; {@code "Please enter Password ..."},
 *       before any file read.</li>
 *   <li><strong>Upper-casing</strong> ({@code MOVE FUNCTION UPPER-CASE},
 *       L132-L136) &rarr; both id and password are folded to upper case; the
 *       repository is keyed with the upper-cased id and the encoder receives the
 *       upper-cased password.</li>
 *   <li><strong>Not found</strong> ({@code WHEN 13} / CICS {@code NOTFND},
 *       L247-L249) &rarr; {@code "User not found. Try again ..."}.</li>
 *   <li><strong>Password match</strong> ({@code WHEN 0}, L221-L246) &rarr;
 *       success, routing admins ({@code SEC-USR-TYPE = 'A'}) to {@code COADM01C}
 *       and everyone else to {@code COMEN01C}.</li>
 *   <li><strong>Password mismatch</strong> (L241-L242) &rarr;
 *       {@code "Wrong Password. Try again ..."}.</li>
 *   <li><strong>Other file error</strong> ({@code WHEN OTHER}, L252-L254) &rarr;
 *       {@code "Unable to verify the User ..."} &mdash; the relational analogue
 *       being a {@code DataAccessException} from the repository.</li>
 * </ol>
 *
 * <p><strong>Confidentiality.</strong> A dedicated invariant (AAP &sect;0.9.3,
 * &sect;0.7.3 L1) asserts the raw password never appears in any
 * {@link SignonService.SignonResult} message.</p>
 *
 * <p><strong>Mockito strictness.</strong> {@link MockitoExtension} runs in the
 * default {@code STRICT_STUBS} mode, so each test stubs only what it uses; the
 * short-circuit branches assert {@code verifyNoInteractions(...)} rather than
 * stubbing collaborators that are never reached.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignonService — COSGN00C sign-on credential-validation parity")
public class SignonServiceTest {

    /**
     * Distinctive raw (cleartext) password used by the confidentiality tests. It
     * is the value a user "types"; the sign-on contract requires it to never
     * appear in a {@link SignonService.SignonResult} message or be logged. It is
     * chosen so that neither it nor its upper-cased form ({@link #RAW_PASSWORD_UPPER})
     * is a substring of any error-message literal.
     */
    private static final String RAW_PASSWORD = "SuperSecret123";

    /**
     * The upper-cased form of {@link #RAW_PASSWORD}, i.e. what the service passes
     * to the encoder after the COBOL {@code FUNCTION UPPER-CASE} fold. Declared
     * explicitly (rather than computed) to keep the confidentiality assertions
     * free of any locale dependency.
     */
    private static final String RAW_PASSWORD_UPPER = "SUPERSECRET123";

    /**
     * An obvious, non-secret placeholder standing in for a stored BCrypt hash.
     * The {@link PasswordEncoder} is mocked, so this value is never actually
     * verified; it exists only to give the fixture a non-null credential and to
     * be echoed back as the second argument of {@code matches(...)}.
     */
    private static final String STORED_PASSWORD_HASH = "ENCODED-PLACEHOLDER-HASH";

    /**
     * Mocked data-access collaborator standing in for the {@code user_security}
     * table (the migrated legacy {@code USRSEC} KSDS).
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mocked Spring Security password encoder (the BCrypt bean in production).
     * Mocking it keeps the test independent of any real hashing algorithm.
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    /**
     * System under test. It is constructed explicitly (per the file contract)
     * rather than via {@code @InjectMocks} so the injected-collaborator order
     * &mdash; repository first, encoder second &mdash; is pinned by construction.
     */
    private SignonService service;

    @BeforeEach
    void setUp() {
        service = new SignonService(userSecurityRepository, passwordEncoder);
    }

    /**
     * Builds a {@link UserSecurity} fixture using the entity's business-field
     * constructor, whose parameter order mirrors the {@code CSUSR01Y} copybook
     * ({@code SEC-USR-ID}, {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME},
     * {@code SEC-USR-PWD}, {@code SEC-USR-TYPE}).
     *
     * @param id   the primary-key user id (already upper-cased, as stored)
     * @param type the single-character role code ({@code "A"} or {@code "U"})
     * @param pwd  the stored (hashed) credential placeholder
     * @return a populated, unpersisted {@link UserSecurity}
     */
    private static UserSecurity user(String id, String type, String pwd) {
        return new UserSecurity(id, "First", "Last", pwd, type);
    }

    // ------------------------------------------------------------------
    // 1. Blank user id (COSGN00C PROCESS-ENTER-KEY, L118-L120).
    // ------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("blank user id -> \"Please enter User ID ...\" before any repository/encoder interaction")
    void blankUserIdReturnsEnterUserIdAndSkipsLookup(String blankUserId) {
        SignonService.SignonResult result = service.signon(blankUserId, "pw");

        assertThat(result.success()).isFalse();
        assertThat(result.message())
                .isEqualTo(SignonService.MSG_ENTER_USER_ID)
                .isEqualTo("Please enter User ID ...");
        // A failure carries no user id / target program and the NUL user-type sentinel.
        assertThat(result.userId()).isNull();
        assertThat(result.targetProgram()).isNull();
        assertThat(result.userType()).isEqualTo('\u0000');
        // Short-circuit: the guard precedes the USRSEC read, so nothing is touched.
        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    // ------------------------------------------------------------------
    // 2. Blank password with a valid id (COSGN00C PROCESS-ENTER-KEY, L123-L125).
    // ------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("blank password (valid id) -> \"Please enter Password ...\" before any repository/encoder interaction")
    void blankPasswordReturnsEnterPasswordAndSkipsLookup(String blankPassword) {
        SignonService.SignonResult result = service.signon("USER0001", blankPassword);

        assertThat(result.success()).isFalse();
        assertThat(result.message())
                .isEqualTo(SignonService.MSG_ENTER_PASSWORD)
                .isEqualTo("Please enter Password ...");
        // The user-id guard passed, but the blank-password guard still precedes the read.
        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    // ------------------------------------------------------------------
    // 3. Unknown user + upper-cased key read (COSGN00C L132-L136, WHEN 13).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unknown user -> \"User not found. Try again ...\"; repository is queried with the UPPER-CASED id")
    void unknownUserReturnsUserNotFoundAndQueriesUpperCasedId() {
        // Lower-case input; the COBOL FUNCTION UPPER-CASE folds it to "USER0001".
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.empty());

        SignonService.SignonResult result = service.signon("user0001", "secret");

        assertThat(result.success()).isFalse();
        assertThat(result.message())
                .isEqualTo(SignonService.MSG_USER_NOT_FOUND)
                .isEqualTo("User not found. Try again ...");
        // Parity proof: the key read from USRSEC is the upper-cased id.
        verify(userSecurityRepository).findBySecUsrId("USER0001");
        // Not-found short-circuits before any credential comparison.
        verifyNoInteractions(passwordEncoder);
    }

    // ------------------------------------------------------------------
    // 4. Valid admin -> COADM01C (COSGN00C WHEN 0, L230-L233).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("valid admin ('A') -> success routing to COADM01C carrying the upper-cased id")
    void adminSignonSucceedsAndRoutesToAdminMenu() {
        UserSecurity admin = user("ADMIN001", "A", STORED_PASSWORD_HASH);
        when(userSecurityRepository.findBySecUsrId("ADMIN001")).thenReturn(Optional.of(admin));
        // Stubbing the matcher with "PASSWORD" (not "password") also proves the fold occurred:
        // had the service not upper-cased the input, this stub would not match and matches(...)
        // would default to false, yielding a wrong-password failure instead of success.
        when(passwordEncoder.matches("PASSWORD", STORED_PASSWORD_HASH)).thenReturn(true);

        SignonService.SignonResult result = service.signon("admin001", "password");

        assertThat(result.success()).isTrue();
        assertThat(result.userId()).isEqualTo("ADMIN001");
        assertThat(result.userType())
                .isEqualTo('A')
                .isEqualTo(SignonService.USER_TYPE_ADMIN);
        assertThat(result.targetProgram())
                .isEqualTo(SignonService.PROGRAM_ADMIN_MENU)
                .isEqualTo("COADM01C");
        // A success never carries an error message.
        assertThat(result.message()).isNull();
    }

    // ------------------------------------------------------------------
    // 5. Valid standard user -> COMEN01C (COSGN00C WHEN 0 ELSE, L236-L238).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("valid standard user ('U') -> success routing to COMEN01C")
    void standardUserSignonSucceedsAndRoutesToMainMenu() {
        UserSecurity standardUser = user("USER0001", "U", STORED_PASSWORD_HASH);
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(standardUser));
        when(passwordEncoder.matches("PASSWORD", STORED_PASSWORD_HASH)).thenReturn(true);

        SignonService.SignonResult result = service.signon("user0001", "password");

        assertThat(result.success()).isTrue();
        assertThat(result.userId()).isEqualTo("USER0001");
        assertThat(result.userType()).isEqualTo('U');
        assertThat(result.targetProgram())
                .isEqualTo(SignonService.PROGRAM_MAIN_MENU)
                .isEqualTo("COMEN01C");
        assertThat(result.message()).isNull();
    }

    // ------------------------------------------------------------------
    // 5b. Valid user with a blank stored type -> main menu (non-'A' default).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("valid user whose stored type is blank -> defaults to the main menu COMEN01C (non-'A' path)")
    void signonWithBlankUserTypeRoutesToMainMenu() {
        // A stored type of "" exercises the "no user type" branch, which is not equal
        // to 'A' and therefore routes to the main menu, mirroring the COBOL ELSE of
        // IF CDEMO-USRTYP-ADMIN (any non-'A' value falls through to COMEN01C).
        UserSecurity noType = user("USER0002", "", STORED_PASSWORD_HASH);
        when(userSecurityRepository.findBySecUsrId("USER0002")).thenReturn(Optional.of(noType));
        when(passwordEncoder.matches("PASSWORD", STORED_PASSWORD_HASH)).thenReturn(true);

        SignonService.SignonResult result = service.signon("user0002", "password");

        assertThat(result.success()).isTrue();
        assertThat(result.userType()).isEqualTo('\u0000');
        assertThat(result.targetProgram()).isEqualTo(SignonService.PROGRAM_MAIN_MENU);
    }

    // ------------------------------------------------------------------
    // 6. Wrong password + confidentiality invariant (COSGN00C L241-L242).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("wrong password -> \"Wrong Password. Try again ...\" and the raw password never leaks into the message")
    void wrongPasswordReturnsWrongPasswordAndNeverLeaksRawPassword() {
        UserSecurity standardUser = user("USER0001", "U", STORED_PASSWORD_HASH);
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(standardUser));
        // The encoder rejects the (upper-cased) credential.
        when(passwordEncoder.matches(RAW_PASSWORD_UPPER, STORED_PASSWORD_HASH)).thenReturn(false);

        SignonService.SignonResult result = service.signon("user0001", RAW_PASSWORD);

        assertThat(result.success()).isFalse();
        assertThat(result.message())
                .isEqualTo(SignonService.MSG_WRONG_PASSWORD)
                .isEqualTo("Wrong Password. Try again ...");
        // Confidentiality invariant: neither the raw nor the folded password appears.
        assertThat(result.message())
                .doesNotContain(RAW_PASSWORD)
                .doesNotContain(RAW_PASSWORD_UPPER);
    }

    // ------------------------------------------------------------------
    // 7. Password is upper-cased before the encoder call (COSGN00C L135-L136).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the entered password is upper-cased before the encoder call, and the stored hash is passed through untouched")
    void signonUpperCasesPasswordBeforeEncoderComparison() {
        UserSecurity standardUser = user("USER0001", "U", STORED_PASSWORD_HASH);
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(standardUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        service.signon("user0001", "MixedCasePw");

        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(rawCaptor.capture(), hashCaptor.capture());
        // First argument: the folded (upper-cased) entered password.
        assertThat(rawCaptor.getValue()).isEqualTo("MIXEDCASEPW");
        // Second argument: the stored hash, forwarded without mutation.
        assertThat(hashCaptor.getValue()).isEqualTo(STORED_PASSWORD_HASH);
    }

    // ------------------------------------------------------------------
    // 8. Data-access failure -> "Unable to verify" (COSGN00C WHEN OTHER, L252-L254).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("repository DataAccessException -> \"Unable to verify the User ...\" (relational analogue of WHEN OTHER)")
    void dataAccessFailureReturnsUnableToVerify() {
        // QueryTimeoutException is a concrete subclass of the abstract DataAccessException.
        when(userSecurityRepository.findBySecUsrId("USER0001"))
                .thenThrow(new QueryTimeoutException("simulated data-access failure"));

        SignonService.SignonResult result = service.signon("user0001", "secret");

        assertThat(result.success()).isFalse();
        assertThat(result.message())
                .isEqualTo(SignonService.MSG_UNABLE_TO_VERIFY)
                .isEqualTo("Unable to verify the User ...");
        // The read failed, so credentials are never compared.
        verifyNoInteractions(passwordEncoder);
    }

    // ------------------------------------------------------------------
    // 9-10. Verbatim message / navigation constants (behavioral-parity pins).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("all five sign-on message constants are the verbatim COBOL WS-MESSAGE literals")
    void messageConstantsMatchCobolLiteralsVerbatim() {
        assertThat(SignonService.MSG_ENTER_USER_ID).isEqualTo("Please enter User ID ...");
        assertThat(SignonService.MSG_ENTER_PASSWORD).isEqualTo("Please enter Password ...");
        assertThat(SignonService.MSG_USER_NOT_FOUND).isEqualTo("User not found. Try again ...");
        assertThat(SignonService.MSG_WRONG_PASSWORD).isEqualTo("Wrong Password. Try again ...");
        assertThat(SignonService.MSG_UNABLE_TO_VERIFY).isEqualTo("Unable to verify the User ...");
    }

    @Test
    @DisplayName("navigation-target and admin-role constants match the COBOL XCTL programs and COCOM01Y role code")
    void navigationAndRoleConstantsMatchCobol() {
        assertThat(SignonService.PROGRAM_ADMIN_MENU).isEqualTo("COADM01C");
        assertThat(SignonService.PROGRAM_MAIN_MENU).isEqualTo("COMEN01C");
        assertThat(SignonService.USER_TYPE_ADMIN).isEqualTo('A');
    }
}

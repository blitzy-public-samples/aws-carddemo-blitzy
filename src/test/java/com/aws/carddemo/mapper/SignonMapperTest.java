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
package com.aws.carddemo.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.SignonResponse;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Fast, isolated, pure-logic unit tests for {@link SignonMapper}, the hand-written mapper for the
 * CardDemo Sign-on screen (BMS map {@code COSGN00}, online program {@code COSGN00C}, CICS
 * transaction {@code CC00}; source-branch {@code app/cbl/COSGN00C.cbl}, relocated to
 * {@code legacy/cbl/COSGN00C.cbl} during migration).
 *
 * <p>The suite locks down the field-level contract between the {@link UserSecurity} entity
 * (copybook {@code legacy/cpy/CSUSR01Y.cpy}, group {@code SEC-USER-DATA}) and the outbound
 * {@link SignonResponse} DTO (BMS symbolic copybook {@code legacy/cpy-bms/COSGN00.CPY}, output
 * group {@code COSGN0AO}). Hand-written mappers were chosen over an annotation processor precisely
 * so this boundary is 100&nbsp;% traceable and directly testable (AAP&nbsp;&sect;0.6.5), which is
 * what these tests exercise.</p>
 *
 * <h2>Construction (no Spring, no mocks, no database)</h2>
 * <p>{@link SignonMapper} is a stateless {@code @Component} with no injected collaborators&mdash;it
 * delegates date/time rendering to the static utility {@link DateUtils}. There is therefore nothing
 * to mock, so this test uses neither Mockito nor a Spring {@code ApplicationContext} nor
 * Testcontainers: the mapper is instantiated directly ({@code new SignonMapper()}) and its
 * <em>real</em>, deterministic output is asserted for a fixed timestamp and a hand-built
 * {@link UserSecurity} fixture. The suite is headless and reproducible and contributes to the
 * project's &ge;80&nbsp;% line-coverage gate (AAP&nbsp;&sect;0.9.2).</p>
 *
 * <h2>What is proven</h2>
 * <ol>
 *   <li><b>Happy path</b> ({@link SignonMapper#toResponse}): the only entity-derived field
 *       ({@code USERIDO}) echoes {@link UserSecurity#getSecUsrId()}; the clock fields are rendered
 *       through {@link DateUtils}; and the header / environment constants are wired to the correct
 *       {@link SignonResponse} components (distinct fixture values catch a mis-wire such as swapping
 *       {@code title01}/{@code title02} or {@code applId}/{@code sysId}).</li>
 *   <li><b>Sensitive-field discipline</b> (AAP&nbsp;&sect;0.7.3 L1 / &sect;0.9.3): the password is
 *       never exposed. The response contract declares no {@code pwd}/{@code password} component, no
 *       produced value carries the fixture credential, and {@code toString()} never renders it.</li>
 *   <li><b>Error / echo path</b> ({@link SignonMapper#toErrorResponse}) and <b>null-safety</b>: the
 *       operator-entered (non-secret) id and the error line are echoed, and {@code null} {@code user}
 *       / {@code null} {@code now} are handled without throwing.</li>
 * </ol>
 *
 * <p>{@link SignonMapper} resides in this same package, so it is referenced directly without an
 * import (mirroring the sibling {@code common.util.IdGeneratorTest} convention).</p>
 */
@DisplayName("SignonMapper — Sign-on screen (COSGN00) field mapping and password discipline")
class SignonMapperTest {

    // ------------------------------------------------------------------------
    // Phase 1 - Deterministic fixtures
    // ------------------------------------------------------------------------

    /**
     * A fixed instant so every date/time assertion is clock-independent. Chosen to match the
     * CardDemo version stamp used across the sibling {@code DateUtilsTest} fixtures
     * ({@code 2022-07-19 23:15:58}).
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2022, 7, 19, 23, 15, 58);

    /**
     * The primary-key id of the {@link UserSecurity} fixture; echoed to {@code USERIDO} on the
     * success path.
     */
    private static final String USER_ID = "USER0001";

    /**
     * The fixture password. It is deliberately recognizable so the sensitive-field scans in
     * {@link SensitiveFieldDiscipline} can search the produced response for any leak. It must never
     * appear anywhere on the outbound path.
     */
    private static final String PASSWORD = "PLAINPWD";

    /** CICS application identifier passed to {@code APPLIDO}; distinct from {@link #SYS_ID}. */
    private static final String APPL_ID = "AWSAPPL0";

    /** CICS system identifier passed to {@code SYSIDO}; distinct from {@link #APPL_ID}. */
    private static final String SYS_ID = "SYS1";

    /** Transaction-name header constant passed to {@code TRNNAMEO}. */
    private static final String TRANSACTION_NAME = "CC00";

    /** First title-line constant passed to {@code TITLE01O}; distinct from {@link #TITLE_02}. */
    private static final String TITLE_01 = "TITLE-ONE";

    /** Program-name header constant passed to {@code PGMNAMEO}. */
    private static final String PROGRAM_NAME = "COSGN00C";

    /** Second title-line constant passed to {@code TITLE02O}; distinct from {@link #TITLE_01}. */
    private static final String TITLE_02 = "TITLE-TWO";

    /**
     * Expected {@code mm/dd/yy} rendering of {@link #NOW}, computed through the <em>same</em>
     * {@link DateUtils} helper the mapper uses. Asserting against this value proves the mapper
     * delegates its formatting rather than fabricating its own mask.
     */
    private static final String EXPECTED_DATE = DateUtils.formatDateMmDdYy(NOW.toLocalDate());

    /**
     * Expected {@code hh:mm:ss} rendering of {@link #NOW}, computed through the <em>same</em>
     * {@link DateUtils} helper the mapper uses.
     */
    private static final String EXPECTED_TIME = DateUtils.formatTimeHhMmSs(NOW.toLocalTime());

    /**
     * The mapper under test. It is a pure, stateless component, so a single shared instance is
     * safe across every test method.
     */
    private final SignonMapper mapper = new SignonMapper();

    /**
     * Builds a fresh {@link UserSecurity} fixture through its public convenience constructor. The
     * no-argument JPA constructor is {@code protected} and therefore not visible from this test
     * package, so the business-field constructor is used and the optimistic-lock version is set
     * explicitly to mimic a persisted record.
     *
     * <p>The record carries a recognizable {@link #PASSWORD} in the sensitive credential slot so
     * the leak scans have a concrete needle to search for.</p>
     *
     * @return a populated, non-persisted user record ({@code USER0001} / {@code JOHN} / {@code DOE}
     *         / role {@code U})
     */
    private static UserSecurity newUser() {
        UserSecurity user = new UserSecurity(USER_ID, "JOHN", "DOE", PASSWORD, "U");
        user.setVersion(1L);
        return user;
    }

    // ------------------------------------------------------------------------
    // Reflection helpers for the sensitive-field discipline (Phase 3)
    // ------------------------------------------------------------------------

    /**
     * Collects the non-{@code null} values of every {@link String}-typed component of a
     * {@link SignonResponse}, read via the record's canonical accessors through reflection.
     *
     * <p>Using reflection rather than an explicit accessor list means a future field added to the
     * response is automatically included in the leak scan, so the sensitive-field contract cannot
     * silently regress.</p>
     *
     * @param response the response to introspect
     * @return the list of non-{@code null} {@code String} component values
     */
    private static List<String> stringComponentValues(SignonResponse response) {
        List<String> values = new ArrayList<>();
        for (RecordComponent component : SignonResponse.class.getRecordComponents()) {
            if (component.getType() == String.class) {
                try {
                    Object value = component.getAccessor().invoke(response);
                    if (value != null) {
                        values.add((String) value);
                    }
                } catch (ReflectiveOperationException ex) {
                    throw new AssertionError("Unable to read record component '"
                            + component.getName() + "' via reflection", ex);
                }
            }
        }
        return values;
    }

    /**
     * Asserts that a produced {@link SignonResponse} leaks the fixture password neither through any
     * of its {@code String} component values nor through {@code toString()}. Centralized so both
     * the success and error paths enforce the identical invariant (AAP&nbsp;&sect;0.9.3).
     *
     * @param response the response that must not contain {@link #PASSWORD}
     */
    private static void assertNoPasswordLeak(SignonResponse response) {
        assertThat(stringComponentValues(response))
                .as("no String component value may carry the password")
                .noneMatch(value -> value.contains(PASSWORD));
        assertThat(response.toString())
                .as("toString() must never render the password")
                .doesNotContain(PASSWORD);
    }

    // ------------------------------------------------------------------------
    // Phase 2 - Happy-path field-by-field mapping (toResponse)
    // ------------------------------------------------------------------------

    /**
     * Success / re-display path of {@code COSGN00C}: an authenticated {@link UserSecurity} record is
     * mapped to the outbound {@link SignonResponse}. These tests pin the single entity-derived field
     * (the echoed user id), the {@link DateUtils}-delegated clock fields, and the straight-through
     * wiring of the header and environment constants.
     */
    @Nested
    @DisplayName("toResponse(...) — success/re-display path field-by-field mapping")
    class HappyPathMapping {

        @Test
        @DisplayName("echoes the user id from UserSecurity.getSecUsrId() onto USERIDO")
        void echoesUserIdFromEntity() {
            SignonResponse response = mapper.toResponse(newUser(), APPL_ID, SYS_ID, null, NOW,
                    TRANSACTION_NAME, TITLE_01, PROGRAM_NAME, TITLE_02);

            assertThat(response).isNotNull();
            assertThat(response.userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("renders CURDATEO (mm/dd/yy) and CURTIMEO (hh:mm:ss) via DateUtils")
        void formatsDateAndTimeThroughDateUtils() {
            // Determinism lock: the fixed instant renders to these known literals, so the suite is
            // clock-independent and never calls LocalDateTime.now().
            assertThat(EXPECTED_DATE).isEqualTo("07/19/22");
            assertThat(EXPECTED_TIME).isEqualTo("23:15:58");

            SignonResponse response = mapper.toResponse(newUser(), APPL_ID, SYS_ID, null, NOW,
                    TRANSACTION_NAME, TITLE_01, PROGRAM_NAME, TITLE_02);

            // Delegation contract: the mapper formats through DateUtils, not an inline formatter.
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
        }

        @Test
        @DisplayName("wires header/environment constants to their correct response components")
        void passesThroughHeaderAndEnvironmentFields() {
            SignonResponse response = mapper.toResponse(newUser(), APPL_ID, SYS_ID, null, NOW,
                    TRANSACTION_NAME, TITLE_01, PROGRAM_NAME, TITLE_02);

            // Distinct values per field: a swap (e.g. title01<->title02, applId<->sysId) fails here.
            assertThat(response.transactionName()).isEqualTo(TRANSACTION_NAME);
            assertThat(response.title01()).isEqualTo(TITLE_01);
            assertThat(response.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(response.title02()).isEqualTo(TITLE_02);
            assertThat(response.applId()).isEqualTo(APPL_ID);
            assertThat(response.sysId()).isEqualTo(SYS_ID);
        }

        @Test
        @DisplayName("leaves the error line (ERRMSGO) null on the success path")
        void errorMessageIsNullOnSuccess() {
            SignonResponse response = mapper.toResponse(newUser(), APPL_ID, SYS_ID, null, NOW,
                    TRANSACTION_NAME, TITLE_01, PROGRAM_NAME, TITLE_02);

            assertThat(response.errorMessage()).isNull();
        }
    }

    // ------------------------------------------------------------------------
    // Phase 3 - SENSITIVE: the password must NEVER leak (AAP 0.7.3 L1 / 0.9.3)
    // ------------------------------------------------------------------------

    /**
     * The legacy design intentionally stored a plaintext credential (AAP&nbsp;&sect;0.7.3 L1); the
     * migration must preserve the sign-on behavior while guaranteeing the password is never exposed
     * or logged (AAP&nbsp;&sect;0.9.3). These tests enforce that guarantee at the mapper boundary:
     * the response contract declares no credential component, no produced value contains the
     * credential, and no textual representation reveals it. They are written to fail loudly if a
     * {@code password} field were ever added to {@link SignonResponse} or if the mapper ever copied
     * {@link UserSecurity#getSecUsrPwd()} onto the response.
     */
    @Nested
    @DisplayName("sensitive-field discipline — password never leaks (AAP 0.7.3 L1 / 0.9.3)")
    class SensitiveFieldDiscipline {

        @Test
        @DisplayName("compile-time contract: SignonResponse declares no pwd/password component")
        void responseDeclaresNoPasswordComponent() {
            List<String> componentNames = new ArrayList<>();
            for (RecordComponent component : SignonResponse.class.getRecordComponents()) {
                componentNames.add(component.getName().toLowerCase(Locale.ROOT));
            }

            // Locks the contract: a future component named e.g. "password" would fail this scan.
            assertThat(componentNames)
                    .isNotEmpty()
                    .noneMatch(name -> name.contains("pwd") || name.contains("password"));
        }

        @Test
        @DisplayName("value contract: no produced String value carries the fixture password")
        void noComponentValueContainsPassword() {
            SignonResponse response = mapper.toResponse(newUser(), APPL_ID, SYS_ID, null, NOW,
                    TRANSACTION_NAME, TITLE_01, PROGRAM_NAME, TITLE_02);

            // Proves the mapper never copied getSecUsrPwd() onto any outbound field.
            assertThat(stringComponentValues(response))
                    .noneMatch(value -> value.contains(PASSWORD));
        }

        @Test
        @DisplayName("toString contract: the response text never renders the password")
        void toStringDoesNotExposePassword() {
            SignonResponse response = mapper.toResponse(newUser(), APPL_ID, SYS_ID, null, NOW,
                    TRANSACTION_NAME, TITLE_01, PROGRAM_NAME, TITLE_02);

            assertThat(response.toString()).doesNotContain(PASSWORD);
        }
    }

    // ------------------------------------------------------------------------
    // Phase 4 - Error/echo path + null-safety (toErrorResponse)
    // ------------------------------------------------------------------------

    /**
     * Error / pre-authentication path of {@code COSGN00C}, where no {@link UserSecurity} entity is
     * available because credentials were not validated. The operator-entered (non-secret) id and the
     * error line are echoed back, the clock fields are still rendered, and the password invariant
     * continues to hold. The null-safety cases pin the mapper's documented no-throw contract for a
     * {@code null} user and a {@code null} timestamp.
     */
    @Nested
    @DisplayName("toErrorResponse(...) echo path and null-safety")
    class ErrorPathAndNullSafety {

        @Test
        @DisplayName("echoes the entered (non-secret) user id and the error message")
        void echoesEnteredUserIdAndErrorMessage() {
            SignonResponse response = mapper.toErrorResponse("BADUSER", APPL_ID, SYS_ID,
                    "Invalid credentials", NOW, TRANSACTION_NAME, TITLE_01, PROGRAM_NAME, TITLE_02);

            assertThat(response).isNotNull();
            assertThat(response.userId()).isEqualTo("BADUSER");
            assertThat(response.errorMessage()).isEqualTo("Invalid credentials");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
        }

        @Test
        @DisplayName("error path never carries the fixture password")
        void errorPathDoesNotLeakPassword() {
            SignonResponse response = mapper.toErrorResponse("BADUSER", APPL_ID, SYS_ID,
                    "Invalid credentials", NOW, TRANSACTION_NAME, TITLE_01, PROGRAM_NAME, TITLE_02);

            assertNoPasswordLeak(response);
        }

        @Test
        @DisplayName("null user yields a null echoed id without throwing")
        void nullUserIsHandledSafely() {
            assertThatCode(() -> {
                SignonResponse response = mapper.toResponse(null, APPL_ID, SYS_ID, null, NOW,
                        TRANSACTION_NAME, TITLE_01, PROGRAM_NAME, TITLE_02);
                assertThat(response).isNotNull();
                assertThat(response.userId()).isNull();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null timestamp yields null date/time without throwing")
        void nullTimestampIsHandledSafely() {
            assertThatCode(() -> {
                SignonResponse response = mapper.toResponse(newUser(), APPL_ID, SYS_ID, null, null,
                        TRANSACTION_NAME, TITLE_01, PROGRAM_NAME, TITLE_02);
                assertThat(response).isNotNull();
                assertThat(response.currentDate()).isNull();
                assertThat(response.currentTime()).isNull();
            }).doesNotThrowAnyException();
        }
    }
}

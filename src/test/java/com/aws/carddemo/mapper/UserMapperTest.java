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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.UserAddRequest;
import com.aws.carddemo.dto.UserAddResponse;
import com.aws.carddemo.dto.UserDeleteResponse;
import com.aws.carddemo.dto.UserListResponse;
import com.aws.carddemo.dto.UserUpdateRequest;
import com.aws.carddemo.dto.UserUpdateResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fast, isolated, pure-logic unit tests for {@link UserMapper}, the hand-written mapper that bridges
 * the {@link UserSecurity} entity and the four CardDemo user-administration screen DTOs. The mapper
 * is the Java re-platform of the entity&harr;screen data movement of the legacy online programs
 * {@code COUSR00C} (List Users, {@code CU00}), {@code COUSR01C} (Add User, {@code CU01}),
 * {@code COUSR02C} (Update User, {@code CU02}) and {@code COUSR03C} (Delete User, {@code CU03}); the
 * entity derives from copybook {@code CSUSR01Y.cpy} and the DTOs from the BMS symbolic copybooks
 * {@code COUSR00.CPY}&ndash;{@code COUSR03.CPY}.
 *
 * <p>These tests instantiate the mapper directly ({@code new UserMapper()}) and use JUnit 5 (Jupiter)
 * with AssertJ only &mdash; there is <strong>no</strong> Spring context, database, Mockito, or
 * Testcontainers dependency, so the suite runs headlessly and reproducibly. All header timestamps are
 * rendered from a single fixed {@link #NOW} so date/time assertions are deterministic. {@code UserMapper}
 * lives in the same package and is therefore referenced without an import.</p>
 *
 * <p>The two contract-critical behaviors under test (AAP &sect;0.7.3, &sect;0.9.3):</p>
 * <ul>
 *   <li><strong>Write-only password.</strong> The password is mapped <em>into</em> the entity raw on
 *       the inbound path (the mapper never hashes; the security layer applies BCrypt downstream) but is
 *       <em>never</em> echoed onto any response. The {@code SensitivePassword} group locks this both
 *       structurally (no response record declares a password component) and by reflectively scanning
 *       every produced response value and its {@code toString()}.</li>
 *   <li><strong>Blank-password skip on update.</strong> {@code updateEntity} always overwrites first
 *       name, last name and (normalized) role, but assigns the password only when it is non-null and
 *       non-blank; a blank/whitespace/null password means &quot;leave the stored credential
 *       unchanged&quot;. The primary key is never overwritten.</li>
 * </ul>
 */
class UserMapperTest {

    /**
     * A single, fixed timestamp used for every header rendering so that the {@code currentDate}
     * ({@code mm/dd/yy}) and {@code currentTime} ({@code hh:mm:ss}) assertions are deterministic. The
     * value intentionally matches the vintage of the seed data ({@code CSUSR01Y.cpy} version stamp
     * {@code 2022-07-19 23:15:59}).
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2022, 7, 19, 23, 15, 58);

    /** The expected {@code MM/dd/uu} rendering of {@link #NOW} (matches {@code DateUtils}). */
    private static final String EXPECTED_DATE = "07/19/22";

    /** The expected {@code HH:mm:ss} rendering of {@link #NOW} (matches {@code DateUtils}). */
    private static final String EXPECTED_TIME = "23:15:58";

    /**
     * The raw passwords carried by the test fixtures. No response produced by the mapper may surface
     * any of these values in a field or in {@code toString()} (AAP &sect;0.9.3).
     */
    private static final List<String> FIXTURE_PASSWORDS = List.of("SECRET99", "OLDHASH", "NEWPWD01");

    /** The stateless mapper under test; recreated per test method (JUnit default PER_METHOD lifecycle). */
    private final UserMapper mapper = new UserMapper();

    /**
     * A pre-existing, managed-style user record used by the update/list/delete tests. It is recreated
     * fresh for every test method, so its password always starts as {@code "OLDHASH"} &mdash; the
     * baseline the blank-password-skip assertions rely on.
     */
    private final UserSecurity existing =
            new UserSecurity("USER0002", "BOB", "BUILDER", "OLDHASH", "U");

    /**
     * A representative Add User request. The {@code userType} is intentionally lower-case ({@code "a"})
     * to exercise the mapper's uppercase normalization, and the password ({@code "SECRET99"}) exercises
     * the raw inbound-write path.
     */
    private final UserAddRequest addReq =
            new UserAddRequest("ALICE", "ADMIN", "ADMIN001", "SECRET99", "a", null);

    // ---------------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------------

    /**
     * Builds an Add User request identical to {@link #addReq} except for the {@code userType}, so a
     * single test can drive the normalization branch with different inputs.
     *
     * @param userType the raw user-type value to place on the request (may be {@code null})
     * @return the request
     */
    private static UserAddRequest addRequestWithUserType(String userType) {
        return new UserAddRequest("ALICE", "ADMIN", "ADMIN001", "SECRET99", userType, null);
    }

    /**
     * Builds an Update User request. The {@code action} attention key is irrelevant to the mapper and
     * is left {@code null}.
     *
     * @param userId    the request user id (never applied to the entity by {@code updateEntity})
     * @param firstName the editable first name
     * @param lastName  the editable last name
     * @param password  the (possibly blank/null) new password
     * @param userType  the raw role code
     * @return the request
     */
    private static UserUpdateRequest updateRequest(String userId,
                                                   String firstName,
                                                   String lastName,
                                                   String password,
                                                   String userType) {
        return new UserUpdateRequest(userId, firstName, lastName, password, userType, null);
    }

    /**
     * Asserts, structurally, that a record type declares no component whose name hints at a password
     * ({@code pwd}/{@code password}, case-insensitive). This locks the "no password on any response"
     * contract at the type level, independent of any particular instance.
     *
     * @param recordType the response record class to inspect
     */
    private static void assertNoPasswordComponent(Class<?> recordType) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertThat(name)
                    .as("record component '%s' of %s must not expose a password",
                            component.getName(), recordType.getSimpleName())
                    .doesNotContain("pwd")
                    .doesNotContain("password");
        }
    }

    /**
     * Recursively collects every {@link String} value reachable from a record's components, descending
     * into {@link List} components (for example the nested {@code UserListRow}s of a list response). Used
     * by the sensitive-password scan to prove no password value leaks onto any response.
     *
     * @param root the record instance to scan
     * @return every String value transitively held by {@code root}'s components
     */
    private static List<String> collectStringValues(Object root) {
        List<String> values = new ArrayList<>();
        collectInto(root, values);
        return values;
    }

    private static void collectInto(Object node, List<String> sink) {
        if (node == null) {
            return;
        }
        RecordComponent[] components = node.getClass().getRecordComponents();
        if (components == null) {
            return;
        }
        for (RecordComponent component : components) {
            Object value;
            try {
                value = component.getAccessor().invoke(node);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError("unable to read record component " + component.getName(), ex);
            }
            if (value instanceof String string) {
                sink.add(string);
            } else if (value instanceof List<?> list) {
                for (Object element : list) {
                    collectInto(element, sink);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Add User (COUSR01, CU01) — toEntity + toAddResponse
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Add User (COUSR01/CU01): toEntity + toAddResponse")
    class Add {

        @Test
        @DisplayName("toEntity copies id, first name and last name verbatim")
        void toEntity_copiesIdentityFieldsVerbatim() {
            UserSecurity entity = mapper.toEntity(addReq);

            assertThat(entity.getSecUsrId()).isEqualTo("ADMIN001");
            assertThat(entity.getSecUsrFname()).isEqualTo("ALICE");
            assertThat(entity.getSecUsrLname()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("toEntity normalizes user type 'a' -> 'A'")
        void toEntity_normalizesLowercaseAdminType() {
            assertThat(mapper.toEntity(addRequestWithUserType("a")).getSecUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("toEntity normalizes user type 'u' -> 'U'")
        void toEntity_normalizesLowercaseUserType() {
            assertThat(mapper.toEntity(addRequestWithUserType("u")).getSecUsrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("toEntity trims surrounding whitespace before normalizing (' a ' -> 'A')")
        void toEntity_trimsAndNormalizesUserType() {
            assertThat(mapper.toEntity(addRequestWithUserType(" a ")).getSecUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("toEntity leaves a null user type as null")
        void toEntity_nullUserTypeStaysNull() {
            assertThat(mapper.toEntity(addRequestWithUserType(null)).getSecUsrType()).isNull();
        }

        @Test
        @DisplayName("toEntity maps the password RAW inbound (mapper never hashes)")
        void toEntity_mapsRawPasswordInbound() {
            // The password is a write-only INBOUND credential: the mapper assigns it verbatim to the
            // entity and never hashes it. One-way encoding (BCrypt) is applied later by the
            // security/service layer, not here (AAP 0.7.3). Echoing it OUTBOUND is separately forbidden
            // and is proven by the SensitivePassword group.
            UserSecurity entity = mapper.toEntity(addReq);
            assertThat(entity.getSecUsrPwd()).isEqualTo("SECRET99");
        }

        @Test
        @DisplayName("toEntity rejects a null request")
        void toEntity_nullRequestThrows() {
            assertThatThrownBy(() -> mapper.toEntity(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("toAddResponse carries the header and error message (no user detail, no password)")
        void toAddResponse_carriesHeaderAndMessage() {
            UserAddResponse response = mapper.toAddResponse(
                    "User has been added ...", NOW, "CU01", "Add User", "CardDemo", "COUSR01C");

            assertThat(response.transactionName()).isEqualTo("CU01");
            assertThat(response.title01()).isEqualTo("Add User");
            assertThat(response.title02()).isEqualTo("CardDemo");
            assertThat(response.programName()).isEqualTo("COUSR01C");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
            assertThat(response.errorMessage()).isEqualTo("User has been added ...");
        }

        @Test
        @DisplayName("toAddResponse renders null date/time when 'now' is null")
        void toAddResponse_nullNowYieldsNullDateTime() {
            UserAddResponse response =
                    mapper.toAddResponse("msg", null, "CU01", "Add User", "CardDemo", "COUSR01C");

            assertThat(response.currentDate()).isNull();
            assertThat(response.currentTime()).isNull();
            assertThat(response.errorMessage()).isEqualTo("msg");
        }
    }

    // ---------------------------------------------------------------------
    // Update User (COUSR02, CU02) — updateEntity + toUpdateResponse
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Update User (COUSR02/CU02): updateEntity + toUpdateResponse")
    class Update {

        @Test
        @DisplayName("updateEntity applies name, type and a non-blank password")
        void updateEntity_appliesFieldsAndNonBlankPassword() {
            mapper.updateEntity(updateRequest("USER0002", "ROBERT", "BUILDER", "NEWPWD01", "A"), existing);

            assertThat(existing.getSecUsrFname()).isEqualTo("ROBERT");
            assertThat(existing.getSecUsrLname()).isEqualTo("BUILDER");
            assertThat(existing.getSecUsrType()).isEqualTo("A");
            assertThat(existing.getSecUsrPwd()).isEqualTo("NEWPWD01");
        }

        @Test
        @DisplayName("updateEntity normalizes the user type on update ('a' -> 'A')")
        void updateEntity_normalizesUserType() {
            mapper.updateEntity(updateRequest("USER0002", "ROBERT", "BUILDER", "NEWPWD01", "a"), existing);
            assertThat(existing.getSecUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("updateEntity leaves the password unchanged when it is empty (name+type still updated)")
        void updateEntity_emptyPasswordIsSkipped() {
            mapper.updateEntity(updateRequest("USER0002", "ROBERT", "BUILDER", "", "A"), existing);

            // Key branch: a blank password means "leave the stored credential unchanged". If the mapper
            // wrongly assigned the password unconditionally, this assertion would fail (red-team guard).
            assertThat(existing.getSecUsrPwd()).isEqualTo("OLDHASH");
            // ... while first name, last name and type ARE always updated.
            assertThat(existing.getSecUsrFname()).isEqualTo("ROBERT");
            assertThat(existing.getSecUsrLname()).isEqualTo("BUILDER");
            assertThat(existing.getSecUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("updateEntity leaves the password unchanged when it is all whitespace")
        void updateEntity_whitespacePasswordIsSkipped() {
            mapper.updateEntity(updateRequest("USER0002", "ROBERT", "BUILDER", "   ", "A"), existing);

            assertThat(existing.getSecUsrPwd()).isEqualTo("OLDHASH");
            assertThat(existing.getSecUsrFname()).isEqualTo("ROBERT");
            assertThat(existing.getSecUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("updateEntity leaves the password unchanged when it is null")
        void updateEntity_nullPasswordIsSkipped() {
            mapper.updateEntity(updateRequest("USER0002", "ROBERT", "BUILDER", null, "A"), existing);

            assertThat(existing.getSecUsrPwd()).isEqualTo("OLDHASH");
            assertThat(existing.getSecUsrFname()).isEqualTo("ROBERT");
            assertThat(existing.getSecUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("updateEntity never overwrites the primary key, even if the request carries a different id")
        void updateEntity_neverOverwritesId() {
            // The request deliberately carries a DIFFERENT user id; the entity key must be preserved
            // because it identifies the record being updated.
            mapper.updateEntity(updateRequest("XXXXXXXX", "ROBERT", "BUILDER", "NEWPWD01", "A"), existing);
            assertThat(existing.getSecUsrId()).isEqualTo("USER0002");
        }

        @Test
        @DisplayName("updateEntity rejects a null request")
        void updateEntity_nullRequestThrows() {
            assertThatThrownBy(() -> mapper.updateEntity(null, existing))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("updateEntity rejects a null entity")
        void updateEntity_nullEntityThrows() {
            UserUpdateRequest request = updateRequest("USER0002", "ROBERT", "BUILDER", "NEWPWD01", "A");
            assertThatThrownBy(() -> mapper.updateEntity(request, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("toUpdateResponse echoes id/first/last/type from the entity plus the header")
        void toUpdateResponse_echoesEntityDetail() {
            UserUpdateResponse response = mapper.toUpdateResponse(
                    existing, "Update confirmed", NOW, "CU02", "Update User", "CardDemo", "COUSR02C");

            assertThat(response.userId()).isEqualTo("USER0002");
            assertThat(response.firstName()).isEqualTo("BOB");
            assertThat(response.lastName()).isEqualTo("BUILDER");
            assertThat(response.userType()).isEqualTo("U");
            assertThat(response.transactionName()).isEqualTo("CU02");
            assertThat(response.title01()).isEqualTo("Update User");
            assertThat(response.title02()).isEqualTo("CardDemo");
            assertThat(response.programName()).isEqualTo("COUSR02C");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
            assertThat(response.errorMessage()).isEqualTo("Update confirmed");
        }

        @Test
        @DisplayName("toUpdateResponse yields null detail fields for a null (not found) user")
        void toUpdateResponse_nullUserYieldsNullDetail() {
            UserUpdateResponse response = mapper.toUpdateResponse(
                    null, "User not found", NOW, "CU02", "Update User", "CardDemo", "COUSR02C");

            assertThat(response.userId()).isNull();
            assertThat(response.firstName()).isNull();
            assertThat(response.lastName()).isNull();
            assertThat(response.userType()).isNull();
            assertThat(response.errorMessage()).isEqualTo("User not found");
            // The header is still populated even when there is no user detail.
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
        }
    }

    // ---------------------------------------------------------------------
    // List Users (COUSR00, CU00) — toListRow + toListResponse
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("List Users (COUSR00/CU00): toListRow + toListResponse")
    class ListUsers {

        @Test
        @DisplayName("toListRow projects id/first/last/type and carries no password")
        void toListRow_projectsFieldsWithoutPassword() {
            UserListResponse.UserListRow row = mapper.toListRow(existing);

            assertThat(row.userId()).isEqualTo("USER0002");
            assertThat(row.firstName()).isEqualTo("BOB");
            assertThat(row.lastName()).isEqualTo("BUILDER");
            assertThat(row.userType()).isEqualTo("U");

            // Structurally, a list row exposes no password component.
            assertNoPasswordComponent(UserListResponse.UserListRow.class);
        }

        @Test
        @DisplayName("toListRow rejects a null user")
        void toListRow_nullUserThrows() {
            assertThatThrownBy(() -> mapper.toListRow(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("toListResponse maps rows in order and echoes page number, date and time")
        void toListResponse_mapsRowsAndHeader() {
            UserSecurity a = new UserSecurity("USER0001", "ANN", "ADAMS", "PWDAAAAA", "U");
            UserSecurity b = new UserSecurity("USER0002", "BOB", "BUILDER", "PWDBBBBB", "A");

            UserListResponse response = mapper.toListResponse(
                    List.of(a, b), "1", null, NOW, "CU00", "List Users", "CardDemo", "COUSR00C");

            assertThat(response.users()).hasSize(2);
            assertThat(response.users().get(0).userId()).isEqualTo("USER0001");
            assertThat(response.users().get(1).userId()).isEqualTo("USER0002");
            assertThat(response.pageNumber()).isEqualTo("1");
            assertThat(response.transactionName()).isEqualTo("CU00");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
        }

        @Test
        @DisplayName("toListResponse returns an immutable snapshot decoupled from the source list")
        void toListResponse_returnsImmutableDefensiveCopy() {
            UserSecurity a = new UserSecurity("USER0001", "ANN", "ADAMS", "PWDAAAAA", "U");
            UserSecurity b = new UserSecurity("USER0002", "BOB", "BUILDER", "PWDBBBBB", "A");
            List<UserSecurity> source = new ArrayList<>(List.of(a, b));

            UserListResponse response = mapper.toListResponse(
                    source, "1", null, NOW, "CU00", "List Users", "CardDemo", "COUSR00C");

            // Mutating the source AFTER mapping must not change the already-produced response.
            source.clear();
            assertThat(response.users()).hasSize(2);

            // The response list itself is unmodifiable (defensive copy).
            UserListResponse.UserListRow extra =
                    new UserListResponse.UserListRow("USER0003", "CARL", "CODER", "U");
            assertThatThrownBy(() -> response.users().add(extra))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("toListResponse maps a null user page to a non-null empty list (no NPE)")
        void toListResponse_nullUsersYieldsEmptyList() {
            UserListResponse response = mapper.toListResponse(
                    null, "1", null, NOW, "CU00", "List Users", "CardDemo", "COUSR00C");

            assertThat(response.users()).isNotNull().isEmpty();
        }
    }

    // ---------------------------------------------------------------------
    // Delete User (COUSR03, CU03) — toDeleteResponse
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Delete User (COUSR03/CU03): toDeleteResponse")
    class Delete {

        @Test
        @DisplayName("toDeleteResponse echoes id/first/last/type from the entity plus the header")
        void toDeleteResponse_echoesEntityDetail() {
            UserDeleteResponse response = mapper.toDeleteResponse(
                    existing, "Press PF5 to confirm", NOW, "CU03", "Delete User", "CardDemo", "COUSR03C");

            assertThat(response.userId()).isEqualTo("USER0002");
            assertThat(response.firstName()).isEqualTo("BOB");
            assertThat(response.lastName()).isEqualTo("BUILDER");
            assertThat(response.userType()).isEqualTo("U");
            assertThat(response.transactionName()).isEqualTo("CU03");
            assertThat(response.title01()).isEqualTo("Delete User");
            assertThat(response.title02()).isEqualTo("CardDemo");
            assertThat(response.programName()).isEqualTo("COUSR03C");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
            assertThat(response.errorMessage()).isEqualTo("Press PF5 to confirm");
        }

        @Test
        @DisplayName("toDeleteResponse yields null detail fields for a null (not found) user")
        void toDeleteResponse_nullUserYieldsNullDetail() {
            UserDeleteResponse response = mapper.toDeleteResponse(
                    null, "User not found", NOW, "CU03", "Delete User", "CardDemo", "COUSR03C");

            assertThat(response.userId()).isNull();
            assertThat(response.firstName()).isNull();
            assertThat(response.lastName()).isNull();
            assertThat(response.userType()).isNull();
            assertThat(response.errorMessage()).isEqualTo("User not found");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
        }
    }

    // ---------------------------------------------------------------------
    // SENSITIVE: the password is never echoed onto any response (AAP 0.9.3)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("SENSITIVE: password never on any response (AAP 0.9.3)")
    class SensitivePassword {

        @Test
        @DisplayName("no response record type declares a password component")
        void noResponseTypeDeclaresAPasswordComponent() {
            // Locks the contract at the type level: passwords are write-only inbound and are never
            // projected outbound, so none of the response records may declare a pwd/password component.
            assertNoPasswordComponent(UserAddResponse.class);
            assertNoPasswordComponent(UserUpdateResponse.class);
            assertNoPasswordComponent(UserDeleteResponse.class);
            assertNoPasswordComponent(UserListResponse.class);
            assertNoPasswordComponent(UserListResponse.UserListRow.class);
        }

        @Test
        @DisplayName("no produced response value (or toString) contains any user password")
        void noResponseLeaksAFixturePassword() {
            // Entities carry the known raw fixture passwords; the responses produced from them must
            // never surface those values in any String field or in toString(). AAP 0.9.3: passwords are
            // never logged or echoed. These guards would fail the moment a response gained a password
            // field or the mapper began echoing the credential.
            UserSecurity secret = new UserSecurity("USER0001", "ANN", "ADAMS", "SECRET99", "U");
            UserSecurity old = new UserSecurity("USER0002", "BOB", "BUILDER", "OLDHASH", "A");
            UserSecurity fresh = new UserSecurity("USER0003", "CARL", "CODER", "NEWPWD01", "U");

            UserUpdateResponse update = mapper.toUpdateResponse(
                    fresh, "ok", NOW, "CU02", "Update User", "CardDemo", "COUSR02C");
            UserDeleteResponse delete = mapper.toDeleteResponse(
                    old, "ok", NOW, "CU03", "Delete User", "CardDemo", "COUSR03C");
            UserListResponse list = mapper.toListResponse(
                    List.of(secret, old, fresh), "1", "ok", NOW, "CU00", "List Users", "CardDemo", "COUSR00C");

            for (Object response : List.of(update, delete, list)) {
                for (String value : collectStringValues(response)) {
                    for (String password : FIXTURE_PASSWORDS) {
                        assertThat(value)
                                .as("a %s field must not leak password '%s'",
                                        response.getClass().getSimpleName(), password)
                                .doesNotContain(password);
                    }
                }
                String rendered = response.toString();
                for (String password : FIXTURE_PASSWORDS) {
                    assertThat(rendered)
                            .as("toString of %s must not leak password '%s'",
                                    response.getClass().getSimpleName(), password)
                            .doesNotContain(password);
                }
            }
        }
    }
}

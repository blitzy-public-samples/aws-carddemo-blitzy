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

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.UserAddRequest;
import com.aws.carddemo.dto.UserAddResponse;
import com.aws.carddemo.dto.UserDeleteResponse;
import com.aws.carddemo.dto.UserListResponse;
import com.aws.carddemo.dto.UserUpdateRequest;
import com.aws.carddemo.dto.UserUpdateResponse;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Hand-written, stateless mapper between the {@link UserSecurity} JPA entity and
 * the request/response DTOs of the four CardDemo user-administration screens.
 *
 * <p><strong>Source lineage.</strong> This component is the Java re-platform of
 * the entity&harr;screen data movement performed by the legacy online programs
 * {@code COUSR00C} (List Users, {@code CU00}), {@code COUSR01C} (Add User,
 * {@code CU01}), {@code COUSR02C} (Update User, {@code CU02}) and
 * {@code COUSR03C} (Delete User, {@code CU03}). The entity derives from copybook
 * {@code CSUSR01Y.cpy} ({@code SEC-USER-DATA}); the DTOs derive from the BMS
 * symbolic copybooks {@code COUSR00.CPY}&ndash;{@code COUSR03.CPY}. Each field
 * assignment below preserves the copybook/BMS field name and semantics one-for-one
 * so the field-level 3270 contract is carried forward unchanged (AAP &sect;0.5.3).</p>
 *
 * <p><strong>Design.</strong> Per AAP &sect;0.6.5 the project deliberately uses
 * explicit, hand-written mappers rather than an annotation-processing library
 * (for example MapStruct), keeping every field movement visible and directly
 * traceable. The bean is stateless and therefore thread-safe; the date/time
 * utility {@link DateUtils} is used through its {@code static} API only. No
 * monetary values are involved, so neither {@code BigDecimal} nor the prohibited
 * {@code double}/{@code float} types appear here.</p>
 *
 * <p><strong>Sensitive-field discipline (absolute).</strong> The user password
 * is a <em>write-only, inbound-only</em> credential:</p>
 * <ul>
 *   <li>It is read from a request DTO ({@link UserAddRequest#password()} or
 *       {@link UserUpdateRequest#password()}) only within {@link #toEntity} and
 *       {@link #updateEntity}, and assigned <em>verbatim</em> to
 *       {@code UserSecurity.secUsrPwd}. This mapper never hashes the value &mdash;
 *       one-way encoding (BCrypt) is the responsibility of the security/service
 *       layer before persistence (AAP &sect;0.7.3).</li>
 *   <li>It is never logged, and it is never copied onto any response. The four
 *       response DTOs expose no password field; consequently
 *       {@link UserSecurity#getSecUsrPwd()} is never referenced on any outbound
 *       path in this class (AAP &sect;0.9.3).</li>
 * </ul>
 *
 * <p><strong>Screen-header convention.</strong> Every response carries the
 * standard 3270 header fields. The four screen-constant values
 * ({@code transactionName}, {@code title01}, {@code title02}, {@code programName})
 * are supplied by the calling controller/service as method arguments, while the
 * volatile {@code currentDate} ({@code mm/dd/yy}) and {@code currentTime}
 * ({@code hh:mm:ss}) fields are rendered from the supplied {@link LocalDateTime}
 * through {@link DateUtils}. Passing {@code now} in (rather than reading the clock
 * here) keeps the mapping pure and deterministically testable. The physical field
 * order of each generated response mirrors its originating BMS map exactly, which
 * is why the Add/Update headers order {@code currentDate} before {@code title02}
 * while the List/Delete headers order {@code title02} before {@code currentDate}.</p>
 */
@Component
public class UserMapper {

    // ------------------------------------------------------------------
    // Inbound: request DTO -> UserSecurity entity
    // ------------------------------------------------------------------

    /**
     * Builds a new {@link UserSecurity} entity from an Add User request
     * (screen {@code COUSR01}, program {@code COUSR01C}).
     *
     * <p>Field mapping (request accessor &rarr; entity field):</p>
     * <ul>
     *   <li>{@code userId()} &rarr; {@code secUsrId} (primary key)</li>
     *   <li>{@code firstName()} &rarr; {@code secUsrFname}</li>
     *   <li>{@code lastName()} &rarr; {@code secUsrLname}</li>
     *   <li>{@code userType()} &rarr; {@code secUsrType}, normalized to an
     *       uppercase {@code 'A'}/{@code 'U'} role code</li>
     *   <li>{@code password()} &rarr; {@code secUsrPwd}, assigned <em>raw</em>;
     *       the security/service layer applies BCrypt before persisting</li>
     * </ul>
     *
     * @param request the Add User request; must not be {@code null}
     * @return a fully populated, unpersisted {@link UserSecurity} (its optimistic
     *         {@code version} is assigned later by the persistence provider)
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public UserSecurity toEntity(UserAddRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        // The raw password is assigned as-is; encoding (BCrypt) happens in the
        // security/service layer. It is never hashed or logged here.
        return new UserSecurity(
                request.userId(),
                request.firstName(),
                request.lastName(),
                request.password(),
                normalizeUserType(request.userType()));
    }

    /**
     * Applies the editable fields of an Update User request (screen
     * {@code COUSR02}, program {@code COUSR02C}) onto an existing, managed
     * {@link UserSecurity} entity, in place.
     *
     * <p>The first name, last name and role are always overwritten with the
     * request values (the role is normalized to an uppercase {@code 'A'}/
     * {@code 'U'} code). The password is only overwritten when a non-blank value
     * is supplied &mdash; a blank/absent password means &quot;leave the stored
     * credential unchanged&quot;, matching the legacy {@code COUSR02C} behavior;
     * when a new password is supplied it is assigned raw and re-encoded by the
     * security/service layer. The primary key {@code secUsrId} is never modified,
     * because it identifies the record being updated.</p>
     *
     * @param request the Update User request; must not be {@code null}
     * @param entity  the existing entity to mutate; must not be {@code null}
     * @throws NullPointerException if {@code request} or {@code entity} is {@code null}
     */
    public void updateEntity(UserUpdateRequest request, UserSecurity entity) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(entity, "entity must not be null");

        // The key (secUsrId) is intentionally never reassigned here.
        entity.setSecUsrFname(request.firstName());
        entity.setSecUsrLname(request.lastName());
        entity.setSecUsrType(normalizeUserType(request.userType()));

        // Blank password => leave the stored credential unchanged. When present,
        // assign the raw value (never logged); the service layer re-encodes it.
        String password = request.password();
        if (password != null && !password.isBlank()) {
            entity.setSecUsrPwd(password);
        }
    }

    // ------------------------------------------------------------------
    // Outbound: UserSecurity entity -> response DTOs (never any password)
    // ------------------------------------------------------------------

    /**
     * Projects a single {@link UserSecurity} entity onto one List Users page row
     * (repeating group {@code USRIDnn}/{@code FNAMEnn}/{@code LNAMEnn}/
     * {@code UTYPEnn} of screen {@code COUSR00}).
     *
     * <p>The row carries the user id, first name, last name and role only; it
     * deliberately has no password field and the entity's password is never
     * read.</p>
     *
     * @param user the entity to project; must not be {@code null}
     * @return the immutable {@link UserListResponse.UserListRow} projection
     * @throws NullPointerException if {@code user} is {@code null}
     */
    public UserListResponse.UserListRow toListRow(UserSecurity user) {
        Objects.requireNonNull(user, "user must not be null");
        return new UserListResponse.UserListRow(
                user.getSecUsrId(),
                user.getSecUsrFname(),
                user.getSecUsrLname(),
                user.getSecUsrType());
    }

    /**
     * Assembles the List Users response (screen {@code COUSR00}, program
     * {@code COUSR00C}) from a page of user entities plus the surrounding screen
     * context.
     *
     * <p>Each entity is projected through {@link #toListRow(UserSecurity)} into an
     * immutable list; a {@code null} entity page is normalized to an empty list.
     * No password is ever projected.</p>
     *
     * @param users           the page of user entities (up to ten); may be
     *                        {@code null}, which is treated as an empty page
     * @param pageNumber      the page indicator shown in the header
     *                        ({@code PAGENUMO})
     * @param errorMessage    the status/error message line ({@code ERRMSGO})
     * @param now             the timestamp used to render the header date/time;
     *                        may be {@code null}
     * @param transactionName the header transaction id ({@code TRNNAMEO})
     * @param title01         the first header title line ({@code TITLE01O})
     * @param title02         the second header title line ({@code TITLE02O})
     * @param programName     the header program name ({@code PGMNAMEO})
     * @return the immutable {@link UserListResponse}
     */
    public UserListResponse toListResponse(List<UserSecurity> users,
                                           String pageNumber,
                                           String errorMessage,
                                           LocalDateTime now,
                                           String transactionName,
                                           String title01,
                                           String title02,
                                           String programName) {
        List<UserListResponse.UserListRow> rows = (users == null)
                ? List.of()
                : users.stream().map(this::toListRow).toList();
        return new UserListResponse(
                transactionName,
                title01,
                title02,
                formatHeaderDate(now),
                programName,
                formatHeaderTime(now),
                pageNumber,
                rows,
                errorMessage);
    }

    /**
     * Assembles the Update User response (screen {@code COUSR02}, program
     * {@code COUSR02C}), echoing the fetched/saved user detail back for editing.
     *
     * <p>The user id, first name, last name and role are echoed from the entity;
     * no password is echoed. A {@code null} {@code user} (for example, a
     * &quot;user not found&quot; outcome) yields {@code null} detail fields while
     * still returning the header and {@code errorMessage}.</p>
     *
     * @param user            the fetched/updated entity; may be {@code null}
     * @param errorMessage    the status/error message line ({@code ERRMSGO})
     * @param now             the timestamp used to render the header date/time;
     *                        may be {@code null}
     * @param transactionName the header transaction id ({@code TRNNAMEO})
     * @param title01         the first header title line ({@code TITLE01O})
     * @param title02         the second header title line ({@code TITLE02O})
     * @param programName     the header program name ({@code PGMNAMEO})
     * @return the {@link UserUpdateResponse}
     */
    public UserUpdateResponse toUpdateResponse(UserSecurity user,
                                               String errorMessage,
                                               LocalDateTime now,
                                               String transactionName,
                                               String title01,
                                               String title02,
                                               String programName) {
        String userId = (user == null) ? null : user.getSecUsrId();
        String firstName = (user == null) ? null : user.getSecUsrFname();
        String lastName = (user == null) ? null : user.getSecUsrLname();
        String userType = (user == null) ? null : user.getSecUsrType();
        return new UserUpdateResponse(
                transactionName,
                title01,
                formatHeaderDate(now),
                programName,
                title02,
                formatHeaderTime(now),
                userId,
                firstName,
                lastName,
                userType,
                errorMessage);
    }

    /**
     * Assembles the Delete User response (screen {@code COUSR03}, program
     * {@code COUSR03C}), echoing the fetched user detail for delete confirmation.
     *
     * <p>The user id, first name, last name and role are echoed from the entity;
     * no password is echoed. A {@code null} {@code user} (for example, a
     * &quot;user not found&quot; outcome) yields {@code null} detail fields while
     * still returning the header and {@code errorMessage}.</p>
     *
     * @param user            the fetched entity; may be {@code null}
     * @param errorMessage    the status/error message line ({@code ERRMSGO})
     * @param now             the timestamp used to render the header date/time;
     *                        may be {@code null}
     * @param transactionName the header transaction id ({@code TRNNAMEO})
     * @param title01         the first header title line ({@code TITLE01O})
     * @param title02         the second header title line ({@code TITLE02O})
     * @param programName     the header program name ({@code PGMNAMEO})
     * @return the {@link UserDeleteResponse}
     */
    public UserDeleteResponse toDeleteResponse(UserSecurity user,
                                               String errorMessage,
                                               LocalDateTime now,
                                               String transactionName,
                                               String title01,
                                               String title02,
                                               String programName) {
        String userId = (user == null) ? null : user.getSecUsrId();
        String firstName = (user == null) ? null : user.getSecUsrFname();
        String lastName = (user == null) ? null : user.getSecUsrLname();
        String userType = (user == null) ? null : user.getSecUsrType();
        return new UserDeleteResponse(
                transactionName,
                title01,
                title02,
                formatHeaderDate(now),
                programName,
                formatHeaderTime(now),
                userId,
                firstName,
                lastName,
                userType,
                errorMessage);
    }

    /**
     * Assembles the Add User response (screen {@code COUSR01}, program
     * {@code COUSR01C}).
     *
     * <p>This screen re-echoes the operator's input through the redisplayed
     * request object, so the response projection carries only the screen header
     * and the status/error message; it exposes no user detail and, in particular,
     * no password.</p>
     *
     * @param errorMessage    the status/error message line ({@code ERRMSGO})
     * @param now             the timestamp used to render the header date/time;
     *                        may be {@code null}
     * @param transactionName the header transaction id ({@code TRNNAMEO})
     * @param title01         the first header title line ({@code TITLE01O})
     * @param title02         the second header title line ({@code TITLE02O})
     * @param programName     the header program name ({@code PGMNAMEO})
     * @return the {@link UserAddResponse}
     */
    public UserAddResponse toAddResponse(String errorMessage,
                                         LocalDateTime now,
                                         String transactionName,
                                         String title01,
                                         String title02,
                                         String programName) {
        return new UserAddResponse(
                transactionName,
                title01,
                formatHeaderDate(now),
                programName,
                title02,
                formatHeaderTime(now),
                errorMessage);
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Normalizes a raw user-type value to the canonical uppercase role code.
     *
     * <p>Surrounding whitespace is trimmed and the value is upper-cased using the
     * {@linkplain Locale#ROOT root locale} (so the ASCII {@code 'a'}&rarr;{@code 'A'}
     * and {@code 'u'}&rarr;{@code 'U'} folding is locale-independent). A
     * {@code null} input is returned unchanged.</p>
     *
     * @param userType the raw user-type value, possibly {@code null}
     * @return the normalized role code, or {@code null} if the input was {@code null}
     */
    private static String normalizeUserType(String userType) {
        if (userType == null) {
            return null;
        }
        return userType.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Renders the header {@code currentDate} ({@code mm/dd/yy}) from the supplied
     * timestamp using {@link DateUtils}, tolerating a {@code null} timestamp.
     *
     * @param now the timestamp, possibly {@code null}
     * @return the formatted date, or {@code null} if {@code now} is {@code null}
     */
    private static String formatHeaderDate(LocalDateTime now) {
        return (now == null) ? null : DateUtils.formatDateMmDdYy(now.toLocalDate());
    }

    /**
     * Renders the header {@code currentTime} ({@code hh:mm:ss}) from the supplied
     * timestamp using {@link DateUtils}, tolerating a {@code null} timestamp.
     *
     * @param now the timestamp, possibly {@code null}
     * @return the formatted time, or {@code null} if {@code now} is {@code null}
     */
    private static String formatHeaderTime(LocalDateTime now) {
        return (now == null) ? null : DateUtils.formatTimeHhMmSs(now.toLocalTime());
    }
}

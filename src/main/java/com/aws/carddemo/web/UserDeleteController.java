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
package com.aws.carddemo.web;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.UserDeleteRequest;
import com.aws.carddemo.dto.UserDeleteResponse;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.UserMapper;
import com.aws.carddemo.service.UserService;

import jakarta.validation.Valid;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDateTime;

/**
 * REST controller that re-platforms the CardDemo online <em>Delete User</em>
 * program {@code COUSR03C} (CICS transaction {@code CU03}) &mdash; documented as
 * <q>Delete a user from USRSEC file</q> &mdash; onto Spring MVC. It reproduces
 * the program's two-step, read-then-confirm flow: the administrator keys a user
 * id and transmits {@code ENTER} to <em>fetch</em> the matching record for visual
 * confirmation, then transmits {@code F5} to <em>delete</em> it.
 *
 * <p><strong>Source lineage.</strong> The legacy program is a pseudo-conversational
 * CICS transaction: it {@code RECEIVE}s map {@code COUSR3A}, branches on the CICS
 * attention identifier ({@code EIBAID}) in its {@code MAIN-PARA} {@code EVALUATE},
 * and {@code RETURN}s with a COMMAREA to re-drive itself. Following the migration
 * design (AAP &sect;0.7.1, hotspot H1) the COMMAREA re-entry model becomes a
 * stateless HTTP request per screen submit, the {@code EXEC CICS XCTL} back to the
 * Admin Menu ({@code COADM01C}) becomes a navigation-header response, and the
 * {@code EVALUATE EIBAID} becomes a switch over the transport-neutral
 * {@link PfKeyAction} carried by the request. The paragraph-level behavior is
 * preserved as follows:</p>
 * <ul>
 *   <li>{@code DFHENTER} &rarr; {@link PfKeyAction#ENTER}: {@code PROCESS-ENTER-KEY}
 *       reads {@code USRSEC} and, on success, displays the fetched name/type with
 *       the prompt <q>Press PF5 key to delete this user ...</q>.</li>
 *   <li>{@code DFHPF5} &rarr; {@link PfKeyAction#PF5}: {@code DELETE-USER-INFO}
 *       deletes the record and reports <q>User &lt;id&gt; has been deleted ...</q>.</li>
 *   <li>{@code DFHPF4} &rarr; {@link PfKeyAction#PF4}: {@code CLEAR-CURRENT-SCREEN}
 *       blanks the form.</li>
 *   <li>{@code DFHPF3}/{@code DFHPF12} &rarr; {@link PfKeyAction#PF3}/{@link PfKeyAction#PF12}:
 *       {@code RETURN-TO-PREV-SCREEN} navigates back to the Admin Menu
 *       ({@code COADM01C}, transaction {@code CA00}).</li>
 *   <li>{@code WHEN OTHER} &rarr; the legacy {@code CCDA-MSG-INVALID-KEY} advisory
 *       <q>Invalid key pressed. Please see below...</q>.</li>
 * </ul>
 *
 * <p><strong>Authorization.</strong> Delete User is an administrator-only
 * transaction (legacy Admin Menu {@code COADM01C} option). The class-level
 * {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")} enforces that at the
 * method-security layer, complementing the URL rule ({@code /api/v1/admin/**}
 * requires {@code ROLE_ADMIN}) declared by {@code SecurityConfig}. The
 * {@code 'A'} (Admin) / {@code 'U'} (User) role model derives from the COMMAREA
 * condition names in {@code legacy/cpy/COCOM01Y.cpy}.</p>
 *
 * <p><strong>Outcome mapping.</strong> A caller-visible <q>User ID NOT found...</q>
 * outcome (legacy CICS {@code RESP = DFHRESP(NOTFND)}) is surfaced as a
 * {@link RecordNotFoundException}, which {@code GlobalExceptionHandler} maps to
 * HTTP {@code 404 Not Found}; the not-found path is therefore never caught here.
 * An empty user id (legacy <q>User ID can NOT be empty...</q>) is a same-screen
 * edit returned as HTTP {@code 200 OK} &mdash; enforced server-side on both the
 * ENTER (fetch) and PF5 (delete) paths so the PF-key branch is evaluated first,
 * matching the legacy {@code EVALUATE EIBAID}. Every successful branch returns
 * HTTP {@code 200 OK} with a {@link UserDeleteResponse}.</p>
 *
 * <p><strong>Design constraints.</strong> Collaborators are supplied by
 * constructor injection only (no field injection, no Lombok). The response DTO
 * carries no password, and neither the request nor the response is ever logged.
 * The controller holds no mutable state and is therefore thread-safe.</p>
 *
 * @see UserService
 * @see UserMapper
 * @see UserDeleteRequest
 * @see UserDeleteResponse
 * @see PfKeyAction
 */
@RestController
@RequestMapping("/api/v1/admin/users/delete")
@Tag(name = "User Delete",
        description = "User Delete screen (COBOL COUSR03C / CICS transaction CU03): delete an application user. Requires the ADMIN role.")
@PreAuthorize("hasRole('ADMIN')")
public class UserDeleteController {

    /** Legacy program identifier shown in the screen header ({@code WS-PGMNAME}). */
    private static final String PROGRAM_NAME = "COUSR03C";

    /** Legacy transaction identifier shown in the screen header ({@code WS-TRANID}). */
    private static final String TRANSACTION_NAME = "CU03";

    /**
     * Navigation target program for {@code F3}/{@code F12} &mdash; the Admin Menu
     * ({@code COADM01C}), matching {@code COUSR03C}'s {@code RETURN-TO-PREV-SCREEN}
     * default {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM}.
     */
    private static final String BACK_PROGRAM_NAME = "COADM01C";

    /** Navigation target transaction for {@code F3}/{@code F12} &mdash; the Admin Menu ({@code CA00}). */
    private static final String BACK_TRANSACTION_NAME = "CA00";

    /**
     * Response header conveying the next program to navigate to (the CICS
     * {@code XCTL PROGRAM(...)} target) when {@code F3}/{@code F12} is pressed
     * &mdash; the stateless-HTTP translation of the legacy {@code CDEMO-TO-PROGRAM}
     * COMMAREA field.
     */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /**
     * Response header conveying the next transaction id to navigate to when
     * {@code F3}/{@code F12} is pressed &mdash; the stateless-HTTP translation of
     * the legacy {@code CDEMO-TO-TRANID} COMMAREA field.
     */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * First application title header line ({@code CCDA-TITLE01} of copybook
     * {@code COTTL01Y}, {@code PIC X(40)}); preserved byte-for-byte, including padding.
     */
    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /**
     * Second application title header line ({@code CCDA-TITLE02} of copybook
     * {@code COTTL01Y}, {@code PIC X(40)}); preserved byte-for-byte, including padding.
     */
    private static final String TITLE02 = "              CardDemo                  ";

    /**
     * Confirmation prompt shown after a successful fetch, matching
     * {@code COUSR03C READ-USER-SEC-FILE} {@code WHEN DFHRESP(NORMAL)}.
     */
    private static final String MSG_CONFIRM_DELETE = "Press PF5 key to delete this user ...";

    /**
     * Not-found message, matching {@code COUSR03C} {@code WHEN DFHRESP(NOTFND)}.
     * Carried by the {@link RecordNotFoundException} surfaced as HTTP 404.
     */
    private static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * Empty-user-id edit message, matching {@code COUSR03C PROCESS-ENTER-KEY} (and
     * {@link UserService#deleteUser(String)}): a blank key is reported as a
     * same-screen edit ({@code 200 OK}), not treated as a not-found (404) or a
     * transport validation error (400). This keeps the ENTER (fetch) and PF5
     * (delete) blank-id behavior identical and legacy-faithful now that the empty
     * check is enforced server-side rather than by a {@code @NotBlank} constraint.
     */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Invalid-key advisory, matching the shared {@code CCDA-MSG-INVALID-KEY} of
     * copybook {@code CSMSG01Y} used by {@code COUSR03C}'s {@code WHEN OTHER} branch.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Page size used to read a single {@code USRSEC} record for the fetch step. */
    private static final int SINGLE_RECORD = 1;

    private final UserService userService;

    private final UserMapper userMapper;

    /**
     * Creates the controller with its collaborators. Constructor injection is used
     * exclusively (no field injection) so the dependencies are explicit and the
     * instance is fully initialized once constructed.
     *
     * @param userService the user-administration service (the Java re-platform of
     *                    the {@code COUSR0xC} program logic); must not be {@code null}
     * @param userMapper  the entity&harr;DTO mapper for the user screens; must not
     *                    be {@code null}
     */
    public UserDeleteController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    /**
     * Serves the blank Delete User screen &mdash; the first-entry rendering of
     * {@code COUSR03C} (its {@code NOT CDEMO-PGM-REENTER} path, which clears the map
     * and sends it so the operator can key a user id). The response carries the
     * standard screen header with no user detail and no message.
     *
     * @return HTTP {@code 200 OK} with a blank {@link UserDeleteResponse}
     */
    @GetMapping
    @Operation(summary = "Blank User Delete screen",
            description = "First-entry screen prompting for the user id to delete (COBOL COUSR03C initial SEND MAP). Requires the ADMIN role.")
    public ResponseEntity<UserDeleteResponse> getDeleteScreen() {
        UserDeleteResponse body = userMapper.toDeleteResponse(
                null, null, LocalDateTime.now(),
                TRANSACTION_NAME, TITLE01, TITLE02, PROGRAM_NAME);
        return ResponseEntity.ok(body);
    }

    /**
     * Handles a Delete User screen submission, routing on the transmitted
     * {@link PfKeyAction} exactly as {@code COUSR03C}'s {@code MAIN-PARA}
     * {@code EVALUATE EIBAID} does:
     * <ul>
     *   <li>{@link PfKeyAction#ENTER} &rarr; fetch the record for confirmation;</li>
     *   <li>{@link PfKeyAction#PF5} &rarr; delete the record;</li>
     *   <li>{@link PfKeyAction#PF4} &rarr; clear the form;</li>
     *   <li>{@link PfKeyAction#PF3} / {@link PfKeyAction#PF12} &rarr; navigate back
     *       to the Admin Menu;</li>
     *   <li>any other key (or an unspecified action) &rarr; the invalid-key advisory.</li>
     * </ul>
     *
     * <p>An empty user id is reported as the legacy <q>User ID can NOT be empty...</q>
     * same-screen edit (HTTP {@code 200}) on the ENTER and PF5 paths &mdash; enforced
     * server-side so the PF-key branch (for example PF3=Back) is evaluated first. A
     * not-found user id on the fetch or delete path propagates as
     * {@link RecordNotFoundException} (HTTP {@code 404}); it is never caught here.
     * Neither the request nor the response is logged.</p>
     *
     * @param request the validated Delete User request carrying the user id and the
     *                transmitted PF-key action; must not be {@code null}
     * @return HTTP {@code 200 OK} with the {@link UserDeleteResponse} for the
     *         selected branch
     */
    @PostMapping
    @Operation(summary = "Submit the User Delete screen",
            description = "Reproduces the COUSR03C flow: look up and delete the requested user record.")
    public ResponseEntity<UserDeleteResponse> delete(@Valid @RequestBody UserDeleteRequest request) {
        LocalDateTime now = LocalDateTime.now();
        return switch (request.action()) {
            case ENTER -> fetchForConfirmation(request.userId(), now);
            case PF5 -> performDelete(request.userId(), now);
            case PF4 -> clearScreen(now);
            case PF3, PF12 -> backToAdminMenu(now);
            case null, default -> invalidKey(now);
        };
    }

    /**
     * {@code ENTER} branch &mdash; {@code COUSR03C PROCESS-ENTER-KEY}: reads the
     * requested {@code USRSEC} record and echoes its name/type back for delete
     * confirmation with the <q>Press PF5 key to delete this user ...</q> prompt.
     *
     * <p>A blank user id is reported as the legacy same-screen edit
     * ({@value #MSG_USER_ID_EMPTY}, HTTP {@code 200}) &mdash; the same outcome as
     * {@link UserService#deleteUser(String)} on the PF5 path &mdash; rather than
     * attempting a lookup. An unknown (but non-blank) id raises
     * {@link RecordNotFoundException} (HTTP 404) from {@link #readUser(String)}.</p>
     *
     * @param userId the operator-keyed user id (may be blank)
     * @param now    the timestamp used to render the header date/time
     * @return HTTP {@code 200 OK} with the fetched user detail, or the empty-id edit
     */
    private ResponseEntity<UserDeleteResponse> fetchForConfirmation(String userId, LocalDateTime now) {
        if (isBlank(userId)) {
            UserDeleteResponse editBody = userMapper.toDeleteResponse(
                    null, MSG_USER_ID_EMPTY, now,
                    TRANSACTION_NAME, TITLE01, TITLE02, PROGRAM_NAME);
            return ResponseEntity.ok(editBody);
        }
        UserSecurity user = readUser(userId);
        UserDeleteResponse body = userMapper.toDeleteResponse(
                user, MSG_CONFIRM_DELETE, now,
                TRANSACTION_NAME, TITLE01, TITLE02, PROGRAM_NAME);
        return ResponseEntity.ok(body);
    }

    /**
     * {@code F5} branch &mdash; {@code COUSR03C DELETE-USER-INFO}: deletes the
     * requested user and reports the legacy success message
     * <q>User &lt;id&gt; has been deleted ...</q>. An unknown id raises
     * {@link RecordNotFoundException} (HTTP 404) from
     * {@link UserService#deleteUser(String)}. After deletion the confirmation
     * detail is cleared (no user echoed), matching the legacy
     * {@code INITIALIZE-ALL-FIELDS}.
     *
     * @param userId the operator-keyed user id (already validated non-blank)
     * @param now    the timestamp used to render the header date/time
     * @return HTTP {@code 200 OK} with the deletion confirmation message
     */
    private ResponseEntity<UserDeleteResponse> performDelete(String userId, LocalDateTime now) {
        UserService.UserResult result = userService.deleteUser(userId);
        UserDeleteResponse body = userMapper.toDeleteResponse(
                null, result.message(), now,
                TRANSACTION_NAME, TITLE01, TITLE02, PROGRAM_NAME);
        return ResponseEntity.ok(body);
    }

    /**
     * {@code F4} branch &mdash; {@code COUSR03C CLEAR-CURRENT-SCREEN}: returns a
     * blank form (no detail, no message), matching {@code INITIALIZE-ALL-FIELDS}.
     *
     * @param now the timestamp used to render the header date/time
     * @return HTTP {@code 200 OK} with a cleared {@link UserDeleteResponse}
     */
    private ResponseEntity<UserDeleteResponse> clearScreen(LocalDateTime now) {
        UserDeleteResponse body = userMapper.toDeleteResponse(
                null, null, now,
                TRANSACTION_NAME, TITLE01, TITLE02, PROGRAM_NAME);
        return ResponseEntity.ok(body);
    }

    /**
     * {@code F3}/{@code F12} branch &mdash; {@code COUSR03C RETURN-TO-PREV-SCREEN}:
     * navigates back to the Admin Menu ({@code COADM01C}, transaction {@code CA00})
     * by returning {@code 200 OK} with the navigation target in the
     * {@link #HEADER_NEXT_PROGRAM} and {@link #HEADER_NEXT_TRANSACTION} response
     * headers; the body is the standard (blank) screen.
     *
     * @param now the timestamp used to render the header date/time
     * @return HTTP {@code 200 OK} with the Admin Menu navigation headers
     */
    private ResponseEntity<UserDeleteResponse> backToAdminMenu(LocalDateTime now) {
        UserDeleteResponse body = userMapper.toDeleteResponse(
                null, null, now,
                BACK_TRANSACTION_NAME, TITLE01, TITLE02, BACK_PROGRAM_NAME);
        return ResponseEntity.ok()
                .header(HEADER_NEXT_PROGRAM, BACK_PROGRAM_NAME)
                .header(HEADER_NEXT_TRANSACTION, BACK_TRANSACTION_NAME)
                .body(body);
    }

    /**
     * {@code WHEN OTHER} branch &mdash; returns the shared invalid-key advisory
     * ({@code CCDA-MSG-INVALID-KEY}) for any unrecognized or unspecified key,
     * matching {@code COUSR03C}'s default {@code EVALUATE} arm.
     *
     * @param now the timestamp used to render the header date/time
     * @return HTTP {@code 200 OK} with the invalid-key message
     */
    private ResponseEntity<UserDeleteResponse> invalidKey(LocalDateTime now) {
        UserDeleteResponse body = userMapper.toDeleteResponse(
                null, MSG_INVALID_KEY, now,
                TRANSACTION_NAME, TITLE01, TITLE02, PROGRAM_NAME);
        return ResponseEntity.ok(body);
    }

    /**
     * Reads a single {@link UserSecurity} record by id for the fetch step &mdash;
     * the Java re-platform of {@code COUSR03C READ-USER-SEC-FILE}. Because
     * {@link UserService} exposes no single-record accessor, this positions the
     * ascending user browse ({@link UserService#listUsers(String, org.springframework.data.domain.Pageable)},
     * the re-platform of the legacy {@code STARTBR}) at the requested key and
     * accepts it only on an exact match. Any other outcome &mdash; the browse lands
     * on a greater id, or no record exists at or beyond the key &mdash; reproduces
     * the legacy {@code DFHRESP(NOTFND)} condition as a {@link RecordNotFoundException}
     * (HTTP {@code 404}). Matching is trimmed and case-insensitive to tolerate the
     * fixed-width {@code PIC X(08)} key padding.
     *
     * @param userId the operator-keyed user id (already validated non-blank)
     * @return the matching {@link UserSecurity} record
     * @throws RecordNotFoundException if no record with exactly that id exists
     */
    private UserSecurity readUser(String userId) {
        String target = userId.trim();
        return userService.listUsers(target, PageRequest.of(0, SINGLE_RECORD))
                .getContent()
                .stream()
                .filter(candidate -> candidate.getSecUsrId() != null
                        && candidate.getSecUsrId().trim().equalsIgnoreCase(target))
                .findFirst()
                .orElseThrow(() -> new RecordNotFoundException(MSG_USER_ID_NOT_FOUND));
    }

    /**
     * Tests whether a user id is blank &mdash; {@code null}, empty, or only spaces
     * &mdash; reproducing the COBOL {@code = SPACES OR LOW-VALUES} emptiness test so
     * a blank key is handled as a same-screen edit rather than an attempted lookup
     * (which would otherwise trim a {@code null} and fail). Kept consistent with the
     * emptiness edit in {@link UserService}.
     *
     * @param userId the user id to test (may be {@code null})
     * @return {@code true} when the id is {@code null} or contains only whitespace
     */
    private static boolean isBlank(String userId) {
        return userId == null || userId.trim().isEmpty();
    }
}

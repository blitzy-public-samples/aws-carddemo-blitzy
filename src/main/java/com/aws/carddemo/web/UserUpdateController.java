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

import java.time.LocalDateTime;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.UserUpdateRequest;
import com.aws.carddemo.dto.UserUpdateResponse;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.mapper.UserMapper;
import com.aws.carddemo.service.UserService;

import jakarta.validation.Valid;

/**
 * REST controller for the CardDemo <em>Update User</em> screen &mdash; the Java
 * re-platform of the legacy CICS online program {@code COUSR02C}
 * (transaction {@code CU02}), which updated a record in the {@code USRSEC} file.
 *
 * <p><strong>Source lineage.</strong> This controller reproduces the observable
 * behavior of {@code legacy/cbl/COUSR02C.cbl} without any feature expansion. The
 * legacy program is pseudo-conversational: on each terminal transmit it inspected
 * the CICS attention identifier ({@code EIBAID}) and ran one paragraph set. This
 * controller maps each of those attention-key paths onto an explicit, transport
 * neutral action carried by {@link UserUpdateRequest#action()} (a
 * {@link PfKeyAction}). The CICS {@code XCTL}/{@code RETURN TRANSID} navigation
 * model has no HTTP analogue, so a &quot;return to the previous screen&quot; is
 * surfaced as a stateless {@code 200 OK} response that carries the next
 * program/transaction in dedicated response headers (see
 * {@link #HEADER_NEXT_PROGRAM} / {@link #HEADER_NEXT_TRANSACTION}), exactly as the
 * CardDemo migration design prescribes for the pseudo-conversational translation.</p>
 *
 * <h2>Attention-key routing (parity with {@code COUSR02C MAIN-PARA})</h2>
 * <table border="1">
 *   <caption>{@code EVALUATE EIBAID} &rarr; controller action</caption>
 *   <tr><th>Legacy key</th><th>{@link PfKeyAction}</th><th>Behavior</th></tr>
 *   <tr><td>{@code DFHENTER}</td><td>{@link PfKeyAction#ENTER}</td>
 *       <td>Fetch the user by id and echo the editable fields
 *           ({@code PROCESS-ENTER-KEY} + {@code READ-USER-SEC-FILE}).</td></tr>
 *   <tr><td>{@code DFHPF5}</td><td>{@link PfKeyAction#PF5}</td>
 *       <td>Save the edited user ({@code UPDATE-USER-INFO} +
 *           {@code UPDATE-USER-SEC-FILE}).</td></tr>
 *   <tr><td>{@code DFHPF4}</td><td>{@link PfKeyAction#PF4}</td>
 *       <td>Clear the form ({@code CLEAR-CURRENT-SCREEN}).</td></tr>
 *   <tr><td>{@code DFHPF3} / {@code DFHPF12}</td>
 *       <td>{@link PfKeyAction#PF3} / {@link PfKeyAction#PF12}</td>
 *       <td>Cancel and navigate back to the Admin Menu
 *           ({@code COADM01C}/{@code CA00}) via {@code RETURN-TO-PREV-SCREEN}.</td></tr>
 *   <tr><td>other</td><td>any other value</td>
 *       <td>Re-display with the invalid-key message
 *           ({@code CCDA-MSG-INVALID-KEY}).</td></tr>
 * </table>
 *
 * <p>A {@code null} {@link UserUpdateRequest#action() action} is treated as
 * {@link PfKeyAction#ENTER}, the default submit behavior, matching the legacy
 * screen's first-transmit path.</p>
 *
 * <h2>Not-found outcome</h2>
 * <p>When the requested user id does not exist &mdash; on the ENTER fetch or the
 * PF5 save &mdash; a {@link RecordNotFoundException} propagates (the legacy CICS
 * {@code DFHRESP(NOTFND)} condition). The controller deliberately does not
 * {@code try}/{@code catch} it; the shared {@code GlobalExceptionHandler}
 * translates it to HTTP {@code 404 Not Found}.</p>
 *
 * <h2>Authorization (ADMIN only)</h2>
 * <p>User maintenance is an administrator-only capability in the legacy design
 * (reached only from the Admin Menu {@code COADM01C}). This is enforced here by
 * the class-level {@link PreAuthorize @PreAuthorize}{@code ("hasRole('ADMIN')")}
 * and, in depth, by the {@code /api/v1/admin/**} URL rule in the security
 * configuration.</p>
 *
 * <h2>Sensitive-data discipline (absolute)</h2>
 * <p>The user password is a <em>write-only</em> credential. It is accepted on the
 * request (masked in {@link UserUpdateRequest#toString()}), passed verbatim to
 * {@link UserService} which decides whether to change it (a blank password leaves
 * the stored credential unchanged), and is <em>never</em> hashed, echoed, or
 * logged here. The {@link UserUpdateResponse} has no password field, so the
 * credential can never be returned. This controller performs no logging at all
 * (it holds no logger), so no request or response can leak into the logs
 * (AAP &sect;0.7.3, &sect;0.9.3).</p>
 *
 * <p>The controller is stateless and holds only its two injected collaborators;
 * instances are therefore thread-safe. Dependencies are supplied exclusively
 * through the constructor (no field injection, no Lombok).</p>
 */
@RestController
@RequestMapping("/api/v1/admin/users/update")
@PreAuthorize("hasRole('ADMIN')")
public class UserUpdateController {

    /**
     * Owning legacy program name ({@code WS-PGMNAME} in {@code COUSR02C}),
     * echoed into the screen header ({@code PGMNAMEO}).
     */
    private static final String PROGRAM_NAME = "COUSR02C";

    /**
     * Owning legacy transaction id ({@code WS-TRANID} in {@code COUSR02C}),
     * echoed into the screen header ({@code TRNNAMEO}).
     */
    private static final String TRANSACTION_ID = "CU02";

    /**
     * Navigation target program for the cancel/back keys &mdash; the Admin Menu
     * ({@code COADM01C}), matching {@code COUSR02C}'s
     * {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM} on PF3/PF12.
     */
    private static final String BACK_TARGET_PROGRAM = "COADM01C";

    /** Navigation target transaction for the cancel/back keys ({@code CA00}). */
    private static final String BACK_TARGET_TRANSACTION = "CA00";

    /**
     * First header title line ({@code CCDA-TITLE01} from {@code COTTL01Y.cpy},
     * {@code PIC X(40)}); reproduced verbatim, including its centering padding,
     * to preserve the {@code TITLE01O} display contract.
     */
    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /**
     * Second header title line ({@code CCDA-TITLE02} from {@code COTTL01Y.cpy},
     * {@code PIC X(40)}); reproduced verbatim to preserve the {@code TITLE02O}
     * display contract.
     */
    private static final String TITLE02 = "              CardDemo                  ";

    /**
     * Informational message shown after a successful fetch, reproducing
     * {@code COUSR02C READ-USER-SEC-FILE} {@code WHEN DFHRESP(NORMAL)}:
     * {@code 'Press PF5 key to save your updates ...'}.
     */
    private static final String MSG_PRESS_PF5 = "Press PF5 key to save your updates ...";

    /**
     * Not-found message reproducing {@code COUSR02C} {@code WHEN DFHRESP(NOTFND)}:
     * {@code 'User ID NOT found...'}. Carried by the {@link RecordNotFoundException}
     * raised when an ENTER fetch resolves no exact match.
     */
    private static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * Invalid-key message ({@code CCDA-MSG-INVALID-KEY} from {@code CSMSG01Y.cpy}),
     * shown for any attention key the legacy {@code EVALUATE EIBAID} did not handle.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Response header conveying the next program to navigate to (the CICS
     * {@code XCTL PROGRAM(...)} target) when a cancel/back key is pressed. This is
     * the stateless-HTTP translation of the legacy {@code CDEMO-TO-PROGRAM}
     * COMMAREA field.
     */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /**
     * Response header conveying the next transaction id to navigate to when a
     * cancel/back key is pressed &mdash; the stateless-HTTP translation of the
     * legacy {@code CDEMO-TO-TRANID} COMMAREA field.
     */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * Fixed page window used to position the {@link UserService#listUsers} browse
     * on a single candidate record for an exact-id fetch. The legacy program read
     * one keyed record ({@code EXEC CICS READ ... RIDFLD(SEC-USR-ID)}); this
     * requests the first record at or after the requested id so the controller can
     * confirm an exact match.
     */
    private static final PageRequest SINGLE_RECORD = PageRequest.of(0, 1);

    /** Value substituted for an absent user-type character (COBOL space). */
    private static final char BLANK_USER_TYPE = ' ';

    private final UserService userService;

    private final UserMapper userMapper;

    /**
     * Creates the controller with its collaborators. Constructor injection is used
     * exclusively so the dependencies are explicit and the instance is fully
     * initialized once constructed.
     *
     * @param userService the user-administration service (re-platform of the
     *                    {@code COUSR0xC} programs); must not be {@code null}
     * @param userMapper  the hand-written entity&harr;DTO mapper for the user
     *                    screens; must not be {@code null}
     */
    public UserUpdateController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    /**
     * Presents the blank Update User screen &mdash; the first-entry path of
     * {@code COUSR02C} (no COMMAREA / not yet re-entered), which sent the map with
     * the header populated and the input fields empty, prompting the operator for
     * a user id.
     *
     * @return {@code 200 OK} with a {@link UserUpdateResponse} whose detail fields
     *         are empty and whose header carries the current date/time
     */
    @GetMapping
    public ResponseEntity<UserUpdateResponse> showUpdateUserScreen() {
        return ResponseEntity.ok(blankScreen(LocalDateTime.now(), null));
    }

    /**
     * Handles an Update User screen submission &mdash; the re-entry path of
     * {@code COUSR02C MAIN-PARA}, which received the map and ran
     * {@code EVALUATE EIBAID}. The controller routes on the request's
     * {@link UserUpdateRequest#action() action} (a {@link PfKeyAction}), treating a
     * {@code null} action as {@link PfKeyAction#ENTER} (the default submit):
     *
     * <ul>
     *   <li>{@link PfKeyAction#ENTER ENTER} &mdash; fetch the user for editing.</li>
     *   <li>{@link PfKeyAction#PF5 PF5} &mdash; save the edited user.</li>
     *   <li>{@link PfKeyAction#PF4 PF4} &mdash; clear the form.</li>
     *   <li>{@link PfKeyAction#PF3 PF3} / {@link PfKeyAction#PF12 PF12} &mdash;
     *       cancel and navigate back to the Admin Menu.</li>
     *   <li>any other key &mdash; re-display with the invalid-key message.</li>
     * </ul>
     *
     * <p>An unknown user id (on ENTER or PF5) raises {@link RecordNotFoundException},
     * which is intentionally not caught here and is mapped to HTTP
     * {@code 404 Not Found} by the global exception handler.</p>
     *
     * @param request the validated Update User request; the write-only password is
     *                never logged or echoed
     * @return a {@code 200 OK} {@link UserUpdateResponse} for every in-screen
     *         outcome (fetch, save, clear, invalid key), or a {@code 200 OK} with
     *         navigation headers for the cancel/back keys
     */
    @PostMapping
    public ResponseEntity<UserUpdateResponse> update(@Valid @RequestBody UserUpdateRequest request) {
        LocalDateTime now = LocalDateTime.now();
        PfKeyAction action = (request.action() == null) ? PfKeyAction.ENTER : request.action();
        return switch (action) {
            case ENTER -> fetchUser(request, now);
            case PF5 -> saveUser(request, now);
            case PF4 -> ResponseEntity.ok(blankScreen(now, null));
            case PF3, PF12 -> navigateBack(now);
            default -> ResponseEntity.ok(blankScreen(now, MSG_INVALID_KEY));
        };
    }

    /**
     * ENTER path &mdash; fetches the user identified by the request and echoes the
     * editable fields for editing. This reproduces {@code COUSR02C}
     * {@code PROCESS-ENTER-KEY} followed by {@code READ-USER-SEC-FILE}.
     *
     * <p>Because {@link UserService} exposes no single-record read, the fetch is
     * positioned with {@link #SINGLE_RECORD} on {@link UserService#listUsers} (the
     * first record at or after the requested id, in ascending key order) and then
     * confirmed to be an <em>exact</em> match. A non-exact or empty result
     * reproduces the legacy {@code WHEN DFHRESP(NOTFND)} branch by raising a
     * {@link RecordNotFoundException} ({@value #MSG_USER_ID_NOT_FOUND}), which the
     * global handler maps to HTTP {@code 404}. On a match the current field values
     * are projected through {@link UserMapper#toUpdateResponse} with the
     * informational {@value #MSG_PRESS_PF5} message.</p>
     *
     * @param request the request carrying the user id to fetch
     * @param now     the timestamp used to render the header date/time
     * @return a {@code 200 OK} response echoing the user's editable fields
     * @throws RecordNotFoundException if no user with the exact id exists
     */
    private ResponseEntity<UserUpdateResponse> fetchUser(UserUpdateRequest request, LocalDateTime now) {
        String requestedId = normalizeRequestedId(request.userId());
        UserSecurity user = userService.listUsers(requestedId, SINGLE_RECORD)
                .getContent().stream()
                .filter(candidate -> candidate.getSecUsrId() != null
                        && candidate.getSecUsrId().equalsIgnoreCase(requestedId))
                .findFirst()
                .orElseThrow(() -> new RecordNotFoundException(MSG_USER_ID_NOT_FOUND));
        return ResponseEntity.ok(userMapper.toUpdateResponse(
                user, MSG_PRESS_PF5, now, TRANSACTION_ID, TITLE01, TITLE02, PROGRAM_NAME));
    }

    /**
     * PF5 path &mdash; saves the edited user. This reproduces {@code COUSR02C}
     * {@code UPDATE-USER-INFO} followed by {@code UPDATE-USER-SEC-FILE}.
     *
     * <p>The controller delegates the entire edit/compare/persist decision to
     * {@link UserService#updateUser}, which applies the mandatory-field edits (in
     * the legacy order), the change-detection, and the &quot;password only when a
     * new value is supplied&quot; rule. The raw password from the request is passed
     * through untouched &mdash; it is never hashed, echoed, or logged here; a blank
     * password means &quot;leave the stored credential unchanged&quot;. The
     * resulting message (a validation message, the &quot;nothing changed&quot;
     * message, or the {@code 'User <id> has been updated ...'} success message) and
     * the affected user (which may be {@code null} on a pre-persist validation
     * failure) are projected through {@link UserMapper#toUpdateResponse}. An
     * unknown id raises {@link RecordNotFoundException} (mapped to HTTP {@code 404});
     * it is intentionally not caught here.</p>
     *
     * @param request the request carrying the id and edited fields
     * @param now     the timestamp used to render the header date/time
     * @return a {@code 200 OK} response carrying the outcome message
     * @throws RecordNotFoundException if no user with the given id exists
     */
    private ResponseEntity<UserUpdateResponse> saveUser(UserUpdateRequest request, LocalDateTime now) {
        UserService.UserResult result = userService.updateUser(
                request.userId(),
                request.firstName(),
                request.lastName(),
                request.password(),
                toUserTypeChar(request.userType()));
        return ResponseEntity.ok(userMapper.toUpdateResponse(
                result.user(), result.message(), now,
                TRANSACTION_ID, TITLE01, TITLE02, PROGRAM_NAME));
    }

    /**
     * PF3/PF12 path &mdash; cancels the screen and navigates back to the Admin Menu
     * ({@code COADM01C}/{@code CA00}), reproducing {@code COUSR02C}
     * {@code RETURN-TO-PREV-SCREEN} (an {@code EXEC CICS XCTL} to the previous
     * program). The stateless-HTTP translation returns {@code 200 OK} with the next
     * program/transaction in the {@link #HEADER_NEXT_PROGRAM} and
     * {@link #HEADER_NEXT_TRANSACTION} response headers; the body is the standard
     * (empty) screen so the client always receives a well-formed payload.
     *
     * @param now the timestamp used to render the header date/time
     * @return a {@code 200 OK} response carrying the navigation headers
     */
    private ResponseEntity<UserUpdateResponse> navigateBack(LocalDateTime now) {
        return ResponseEntity.ok()
                .header(HEADER_NEXT_PROGRAM, BACK_TARGET_PROGRAM)
                .header(HEADER_NEXT_TRANSACTION, BACK_TARGET_TRANSACTION)
                .body(blankScreen(now, null));
    }

    /**
     * Builds an Update User response with no user detail (an empty screen) and an
     * optional status message. Used for the blank first-entry screen (GET), the
     * PF4 clear path, the invalid-key path, and the cancel/back body.
     *
     * @param now     the timestamp used to render the header date/time
     * @param message the status/error message to place on the screen, or
     *                {@code null} for none
     * @return a {@link UserUpdateResponse} with populated header, empty detail
     *         fields, and the supplied message
     */
    private UserUpdateResponse blankScreen(LocalDateTime now, String message) {
        return userMapper.toUpdateResponse(
                null, message, now, TRANSACTION_ID, TITLE01, TITLE02, PROGRAM_NAME);
    }

    /**
     * Normalizes the requested user id for an exact-match fetch: a {@code null} is
     * treated as empty, and surrounding whitespace is trimmed (the trailing blanks
     * of the legacy fixed-width {@code PIC X(8)} field are not significant). Case is
     * handled by the case-insensitive comparison at the call site, so no locale
     * dependent upper-casing is performed here.
     *
     * @param userId the raw user id from the request (may be {@code null})
     * @return the trimmed user id, never {@code null}
     */
    private static String normalizeRequestedId(String userId) {
        return (userId == null) ? "" : userId.trim();
    }

    /**
     * Converts the request's {@code userType} string ({@code PIC X(1)} on the
     * legacy map) to the single {@code char} expected by
     * {@link UserService#updateUser}. An absent/empty value becomes a space
     * (reproducing the COBOL {@code SPACES} empty-type condition, which the service
     * rejects as &quot;User Type can NOT be empty...&quot;); a present value is
     * upper-cased to the canonical {@code 'A'}/{@code 'U'} role code for 3270
     * upper-case parity. {@link Character#toUpperCase(char)} is used so no locale
     * import is required.
     *
     * @param userType the request user-type string (may be {@code null} or empty)
     * @return the upper-cased first character, or a space when absent
     */
    private static char toUserTypeChar(String userType) {
        if (userType == null || userType.isEmpty()) {
            return BLANK_USER_TYPE;
        }
        return Character.toUpperCase(userType.charAt(0));
    }
}

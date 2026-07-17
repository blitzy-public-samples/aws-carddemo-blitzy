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

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.UserAddRequest;
import com.aws.carddemo.dto.UserAddResponse;
import com.aws.carddemo.mapper.UserMapper;
import com.aws.carddemo.service.UserService;

import jakarta.validation.Valid;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * REST controller for the CardDemo <em>Add User</em> screen &mdash; the Java
 * re-platform of the legacy online program {@code COUSR01C} (CICS transaction
 * {@code CU01}), whose source is retained at {@code legacy/cbl/COUSR01C.cbl}.
 *
 * <p><strong>Administration-only.</strong> Adding a user is an administrative
 * capability, reached in the legacy application only from the Admin Menu
 * ({@code COADM01C}/{@code CA00}). The endpoint is therefore guarded by a
 * class-level {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")}, reproducing
 * the COBOL {@code CDEMO-USRTYP-ADMIN VALUE 'A'} role gate; this complements the
 * URL-level {@code /api/v1/admin/**} rule declared in the security configuration
 * so the contract is enforced both at the route and at the handler.</p>
 *
 * <h2>Pseudo-conversational translation (COMMAREA &rarr; request/response)</h2>
 * The legacy program is pseudo-conversational: on first entry (no re-enter flag)
 * it sends a blank map, and on re-entry it {@code RECEIVE}s the map and branches
 * on the terminal attention id ({@code EIBAID}). Because there is no 3270 terminal
 * in the re-platformed application, that model is expressed as two stateless HTTP
 * operations over the single screen resource:
 * <ul>
 *   <li>{@link #addUserForm() GET} renders the blank add-user form &mdash; the
 *       equivalent of the COBOL first-entry ({@code NOT CDEMO-PGM-REENTER})
 *       {@code SEND ... ERASE} of a {@code LOW-VALUES} map.</li>
 *   <li>{@link #add(UserAddRequest) POST} submits the screen; the
 *       {@link UserAddRequest#action() action} carries the translated attention
 *       key and drives the same branch logic as the COBOL {@code EVALUATE EIBAID}
 *       ({@code DFHENTER}, {@code DFHPF3}, {@code DFHPF4}, {@code WHEN OTHER}).</li>
 * </ul>
 *
 * <h2>Outcome model</h2>
 * The mandatory-field edits (COBOL {@code PROCESS-ENTER-KEY}) and the write
 * outcome (COBOL {@code WRITE-USER-SEC-FILE}) are owned by {@link UserService};
 * this controller performs no business validation of its own. Successful adds and
 * the service-surfaced same-screen edit messages are returned as {@code 200 OK}
 * carrying a {@link UserAddResponse} (the COBOL screen re-display with a message
 * on {@code ERRMSGO}). A duplicate user id makes the service throw
 * {@code DuplicateKeyException}, which the global exception handler maps to
 * {@code 409 Conflict}; that exception is intentionally <em>not</em> caught here.
 *
 * <h2>Sensitive-data discipline (absolute)</h2>
 * The password is a write-only, inbound-only credential. It is read from the
 * request only to hand it &mdash; raw and unmodified &mdash; to
 * {@link UserService#addUser(String, String, String, String, char)}, which is the
 * component responsible for one-way hashing (BCrypt) before persistence. This
 * controller never hashes, stores, echoes, or logs the password, and
 * {@link UserAddResponse} exposes no password field (AAP &sect;0.7.3, &sect;0.9.3).
 *
 * <p>Only constructor injection is used (no field injection, no Lombok) so the
 * collaborators are explicit and the instance is fully initialized once
 * constructed.</p>
 */
@RestController
@RequestMapping(UserAddController.BASE_PATH)
@PreAuthorize("hasRole('ADMIN')")
public class UserAddController {

    /**
     * Base path for the Add User screen resource. Grouped under
     * {@code /api/v1/admin} so it inherits the administrative URL security rule in
     * addition to this class's method-level {@code @PreAuthorize}.
     */
    static final String BASE_PATH = "/api/v1/admin/users/add";

    /**
     * This program's name shown in the screen header ({@code PGMNAMEO}); COBOL
     * {@code WS-PGMNAME VALUE 'COUSR01C'}.
     */
    private static final String PROGRAM_NAME = "COUSR01C";

    /**
     * This screen's CICS transaction id shown in the header ({@code TRNNAMEO});
     * COBOL {@code WS-TRANID VALUE 'CU01'}.
     */
    private static final String TRANSACTION_ID = "CU01";

    /**
     * Navigation target program for the PF3 (back) action &mdash; the Admin Menu;
     * COBOL {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM} in {@code RETURN-TO-PREV-SCREEN}.
     */
    private static final String BACK_PROGRAM_NAME = "COADM01C";

    /** Navigation target transaction id for the PF3 (back) action ({@code CA00}). */
    private static final String BACK_TRANSACTION_ID = "CA00";

    /**
     * First header title line ({@code TITLE01O}); COBOL {@code CCDA-TITLE01} from
     * copybook {@code COTTL01Y.cpy}, preserved at its full {@code PIC X(40)} width.
     */
    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /**
     * Second header title line ({@code TITLE02O}); COBOL {@code CCDA-TITLE02} from
     * copybook {@code COTTL01Y.cpy}, preserved at its full {@code PIC X(40)} width.
     */
    private static final String TITLE02 = "              CardDemo                  ";

    /**
     * Invalid-key advisory shown on the message line for an unmapped attention
     * key; COBOL {@code CCDA-MSG-INVALID-KEY} from copybook {@code CSMSG01Y.cpy}.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Blank message line, reproducing the COBOL {@code MOVE SPACES TO WS-MESSAGE}
     * that precedes a fresh (first-entry or cleared) screen send.
     */
    private static final String BLANK_MESSAGE = "";

    /**
     * Sentinel used for an absent single-character user type so the service's
     * {@code = SPACES OR LOW-VALUES} emptiness edit yields the COBOL
     * "User Type can NOT be empty..." same-screen message rather than a low-level
     * error in the controller.
     */
    private static final char BLANK_USER_TYPE = ' ';

    private final UserService userService;

    private final UserMapper userMapper;

    /**
     * Creates the controller with its collaborators.
     *
     * @param userService the user-administration service that performs the
     *                    mandatory-field edits, hashes the password, and writes the
     *                    record; must not be {@code null}
     * @param userMapper  the hand-written mapper that assembles the screen response
     *                    (header plus message line); must not be {@code null}
     */
    public UserAddController(UserService userService, UserMapper userMapper) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.userMapper = Objects.requireNonNull(userMapper, "userMapper must not be null");
    }

    /**
     * Renders the blank Add User form &mdash; the Java equivalent of the COBOL
     * first-entry path ({@code IF NOT CDEMO-PGM-REENTER}) that moved
     * {@code LOW-VALUES} to the output map and issued {@code SEND ... ERASE}.
     *
     * <p>The response carries the standard screen header and an empty message
     * line; no user detail is present because nothing has been entered yet.</p>
     *
     * @return {@code 200 OK} with a blank {@link UserAddResponse}
     */
    @GetMapping
    public ResponseEntity<UserAddResponse> addUserForm() {
        return ResponseEntity.ok(currentScreen(BLANK_MESSAGE));
    }

    /**
     * Submits the Add User screen and routes on the terminal attention key,
     * reproducing the COBOL {@code EVALUATE EIBAID} of {@code COUSR01C MAIN-PARA}.
     *
     * <p>The request body is validated with {@link Valid @Valid} against the
     * {@link UserAddRequest} Bean Validation constraints; a malformed body is
     * surfaced as {@code 400 Bad Request} by the global exception handler. An
     * absent {@link UserAddRequest#action() action} defaults to
     * {@link PfKeyAction#ENTER}, the primary submit action for this screen.</p>
     *
     * <ul>
     *   <li>{@link PfKeyAction#ENTER} &rarr; add the user (see
     *       {@link #processEnter(UserAddRequest)}).</li>
     *   <li>{@link PfKeyAction#PF3} &rarr; navigate back to the Admin Menu
     *       ({@code COADM01C}/{@code CA00}); COBOL {@code RETURN-TO-PREV-SCREEN}.</li>
     *   <li>{@link PfKeyAction#PF4} &rarr; clear the form; COBOL
     *       {@code CLEAR-CURRENT-SCREEN}.</li>
     *   <li>any other key &rarr; re-display with the invalid-key advisory; COBOL
     *       {@code WHEN OTHER}.</li>
     * </ul>
     *
     * @param request the submitted Add User screen; validated and never logged
     *                (its {@code toString()} masks the password)
     * @return {@code 200 OK} with the appropriate {@link UserAddResponse}; a
     *         duplicate id propagates as {@code DuplicateKeyException} &rarr;
     *         {@code 409 Conflict}
     */
    @PostMapping
    public ResponseEntity<UserAddResponse> add(@Valid @RequestBody UserAddRequest request) {
        PfKeyAction action = (request.action() == null) ? PfKeyAction.ENTER : request.action();
        return switch (action) {
            case ENTER -> processEnter(request);
            case PF3 -> backToAdminMenu();
            case PF4 -> clearScreen();
            default -> invalidKey();
        };
    }

    /**
     * Handles the ENTER (add) action &mdash; COBOL {@code PROCESS-ENTER-KEY}
     * followed by {@code WRITE-USER-SEC-FILE}.
     *
     * <p>The single-character user type ({@code SEC-USR-TYPE PIC X(01)}) is derived
     * from the first character of the request value; an absent value is forwarded
     * as a blank so the service reproduces the COBOL empty-type edit as a
     * same-screen message. The raw password is passed straight through to the
     * service, which hashes it &mdash; it is never hashed, echoed, or logged here.
     * The service returns the exact COBOL message text (a validation message, the
     * generic add-failure message, or the {@code "User <id> has been added ..."}
     * confirmation), which is placed on the response message line. A duplicate id
     * throws {@code DuplicateKeyException} ({@code 409}); it is deliberately not
     * caught.</p>
     *
     * @param request the validated Add User request
     * @return {@code 200 OK} with the re-displayed screen carrying the outcome message
     */
    private ResponseEntity<UserAddResponse> processEnter(UserAddRequest request) {
        char userType = firstCharOrBlank(request.userType());

        UserService.UserResult result = userService.addUser(
                request.userId(),
                request.firstName(),
                request.lastName(),
                request.password(),
                userType);

        return ResponseEntity.ok(currentScreen(result.message()));
    }

    /**
     * Handles the PF4 (clear) action &mdash; COBOL {@code CLEAR-CURRENT-SCREEN},
     * which initialized all fields and re-sent the (blank) screen.
     *
     * @return {@code 200 OK} with a blank {@link UserAddResponse}
     */
    private ResponseEntity<UserAddResponse> clearScreen() {
        return ResponseEntity.ok(currentScreen(BLANK_MESSAGE));
    }

    /**
     * Handles an unmapped attention key &mdash; COBOL {@code WHEN OTHER}, which set
     * {@code WS-MESSAGE} to {@code CCDA-MSG-INVALID-KEY} and re-sent the screen.
     *
     * @return {@code 200 OK} with the invalid-key advisory on the message line
     */
    private ResponseEntity<UserAddResponse> invalidKey() {
        return ResponseEntity.ok(currentScreen(MSG_INVALID_KEY));
    }

    /**
     * Handles the PF3 (back) action &mdash; COBOL {@code RETURN-TO-PREV-SCREEN},
     * which transferred control to the Admin Menu ({@code COADM01C}). Because the
     * re-platform has no {@code XCTL}, the returned response header identifies the
     * navigation target ({@code CA00}/{@code COADM01C}) so the client can navigate
     * to the Admin Menu screen.
     *
     * @return {@code 200 OK} with a header addressed to the Admin Menu
     */
    private ResponseEntity<UserAddResponse> backToAdminMenu() {
        UserAddResponse response = userMapper.toAddResponse(
                BLANK_MESSAGE,
                LocalDateTime.now(),
                BACK_TRANSACTION_ID,
                TITLE01,
                TITLE02,
                BACK_PROGRAM_NAME);
        return ResponseEntity.ok(response);
    }

    /**
     * Assembles an Add User screen response for this program, populating the
     * standard header (COBOL {@code POPULATE-HEADER-INFO}) and the supplied message
     * line. The current date/time are rendered by the mapper from {@code now}.
     *
     * @param message the message-line text ({@code ERRMSGO}); may be blank
     * @return the assembled {@link UserAddResponse}
     */
    private UserAddResponse currentScreen(String message) {
        return userMapper.toAddResponse(
                message,
                LocalDateTime.now(),
                TRANSACTION_ID,
                TITLE01,
                TITLE02,
                PROGRAM_NAME);
    }

    /**
     * Returns the first character of the given value, or a blank when the value is
     * {@code null} or empty. This mirrors reading a single-character
     * {@code PIC X(01)} field and lets an absent user type flow to the service's
     * emptiness edit instead of raising an index error.
     *
     * @param value the source text (for example the user-type field)
     * @return the first character, or {@link #BLANK_USER_TYPE} when none
     */
    private static char firstCharOrBlank(String value) {
        return (value == null || value.isEmpty()) ? BLANK_USER_TYPE : value.charAt(0);
    }
}

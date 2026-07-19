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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import java.time.LocalDateTime;
import java.util.List;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.UserListRequest;
import com.aws.carddemo.dto.UserListResponse;
import com.aws.carddemo.mapper.UserMapper;
import com.aws.carddemo.service.UserService;

/**
 * REST controller for the CardDemo <strong>List Users</strong> screen &mdash; the
 * Java re-platform of the legacy CICS online program {@code COUSR00C}
 * (transaction {@code CU00}), which browses the {@code USRSEC} security file a
 * page (ten rows) at a time and lets an administrator select a row to update
 * ({@code 'U'}) or delete ({@code 'D'}) a user. The legacy source is
 * {@code app/cbl/COUSR00C.cbl} (relocated to {@code legacy/cbl/COUSR00C.cbl}).
 *
 * <h2>Administrator-only</h2>
 * <p>List Users is an administrative function. Authorization is enforced
 * primarily by the URL security rule in {@code config/SecurityConfig}
 * ({@code /api/v1/admin/**} requires {@code hasRole("ADMIN")}); the class-level
 * {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")} here is defense-in-depth
 * (method security is enabled). {@code hasRole('ADMIN')} matches the
 * {@code ROLE_ADMIN} authority granted by {@code security/UserRole} for the
 * legacy user-type {@code 'A'} (COMMAREA condition name
 * {@code CDEMO-USRTYP-ADMIN}).</p>
 *
 * <h2>Pseudo-conversational translation (AAP &sect;0.7.1 H1)</h2>
 * <p>The legacy program is pseudo-conversational: it keeps its browse cursor and
 * running page number in the CICS COMMAREA ({@code CDEMO-CU00-USRID-FIRST} /
 * {@code -LAST} / {@code -PAGE-NUM}) and re-enters on every attention key. In the
 * re-platformed application there is no COMMAREA and no 3270 terminal; the screen
 * becomes a pair of stateless HTTP endpoints and the operator-supplied
 * {@link UserListRequest#userId() userId} acts as the browse anchor / start key
 * (the legacy {@code USRIDINI} search field and the {@code STARTBR} reposition
 * point). Screen navigation ({@code EXEC CICS XCTL}) is surfaced as response
 * headers naming the next program/transaction rather than an in-process transfer,
 * exactly as the migration re-expresses each screen as a REST contract instead of
 * a rendered panel (AAP &sect;0.3.3).</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/v1/admin/users} &mdash; the initial display: the first
 *       page (rows 1&ndash;10) of users in ascending user-id order, reproducing the
 *       legacy first-time entry that browses from the start of {@code USRSEC}.</li>
 *   <li>{@code POST /api/v1/admin/users/list} &mdash; a screen submit carrying the
 *       {@link UserListRequest} (start key, per-row selection flags, and the
 *       attention key). The action is routed exactly as the legacy
 *       {@code EVALUATE EIBAID}: Enter processes a selection or re-displays the
 *       page, PF7/PF8 page backward/forward, PF3 returns to the Admin Menu, and any
 *       other key yields the invalid-key message. The {@code /list} sub-path keeps
 *       this browse submit distinct from a resource-creating {@code POST} on the
 *       collection base.</li>
 * </ul>
 *
 * <h2>Paging model</h2>
 * <p>The service contract {@link UserService#listUsers(String, org.springframework.data.domain.Pageable)}
 * returns the users in a fixed ascending user-id order (the sort supplied on the
 * {@link PageRequest} is documentation of intent; the service fixes the order),
 * windowed by the requested offset/size, and reports the total via the returned
 * {@link Page}. Forward/backward paging is derived from that total and the
 * anchor's position so the displayed 1-based page number tracks the operator's
 * position through the list, reproducing {@code PROCESS-PAGE-FORWARD} /
 * {@code PROCESS-PAGE-BACKWARD}. Page boundaries reproduce the legacy messages
 * &quot;You are already at the top/bottom of the page...&quot;.</p>
 *
 * <h2>Sensitive data (AAP &sect;0.9.3)</h2>
 * <p>Each displayed row exposes only the user id, first name, last name, and role
 * &mdash; never the password: rows are projected exclusively through
 * {@link UserMapper#toListRow(UserSecurity)}, which never reads the credential.
 * The request and response are never logged.</p>
 *
 * <p>Collaborators are supplied by constructor injection only (no field
 * injection, no Lombok), so the controller is fully initialized once constructed
 * and is trivially testable.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "User List",
        description = "User List screen (COBOL COUSR00C / CICS transaction CU00): browse application users with PF7/PF8 paging. Requires the ADMIN role.")
@PreAuthorize("hasRole('ADMIN')")
public class UserListController {

    /** Rows displayed per page &mdash; the legacy {@code USER-REC OCCURS 10 TIMES}. */
    private static final int PAGE_SIZE = 10;

    /**
     * Fixed ascending sort by the {@code UserSecurity} identifier property, which
     * mirrors the legacy ascending-key VSAM browse. The service fixes this order
     * regardless; the value is passed for explicit, self-documenting intent.
     */
    private static final Sort SORT = Sort.by("secUsrId");

    /** This program's identifier ({@code WS-PGMNAME}) shown in the screen header. */
    private static final String PROGRAM_ID = "COUSR00C";

    /** This program's transaction id ({@code WS-TRANID}) shown in the screen header. */
    private static final String TRANSACTION_ID = "CU00";

    /** Update-User target program ({@code 'U'} selection &rarr; {@code XCTL COUSR02C}). */
    private static final String PROGRAM_UPDATE = "COUSR02C";

    /** Update-User target transaction ({@code CU02}). */
    private static final String TRANSACTION_UPDATE = "CU02";

    /** Delete-User target program ({@code 'D'} selection &rarr; {@code XCTL COUSR03C}). */
    private static final String PROGRAM_DELETE = "COUSR03C";

    /** Delete-User target transaction ({@code CU03}). */
    private static final String TRANSACTION_DELETE = "CU03";

    /** Admin-Menu target program (PF3 back &rarr; {@code XCTL COADM01C}). */
    private static final String PROGRAM_ADMIN_MENU = "COADM01C";

    /** Admin-Menu target transaction ({@code CA00}). */
    private static final String TRANSACTION_ADMIN_MENU = "CA00";

    /** First header title line ({@code CCDA-TITLE01} from {@code COTTL01Y}, {@code PIC X(40)}). */
    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /** Second header title line ({@code CCDA-TITLE02} from {@code COTTL01Y}, {@code PIC X(40)}). */
    private static final String TITLE02 = "              CardDemo                  ";

    /** Response header naming the next program to navigate to (legacy {@code XCTL} target). */
    private static final String HDR_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header naming the next transaction to navigate to. */
    private static final String HDR_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** Response header carrying the selected user id passed to the update/delete screen. */
    private static final String HDR_SELECTED_USER_ID = "X-CardDemo-Selected-User-Id";

    /** Selection marker that routes to Update User ({@code COUSR00C} {@code WHEN 'U'/'u'}). */
    private static final String SELECT_UPDATE = "U";

    /** Selection marker that routes to Delete User ({@code COUSR00C} {@code WHEN 'D'/'d'}). */
    private static final String SELECT_DELETE = "D";

    /** Invalid row-selection message (legacy {@code COUSR00C} lines 211&ndash;213). */
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid values are U and D";

    /** Invalid attention-key message ({@code CCDA-MSG-INVALID-KEY} from {@code CSMSG01Y}). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Top-of-list boundary message (legacy {@code PROCESS-PF7-KEY}, line 251). */
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";

    /** Bottom-of-list boundary message (legacy {@code PROCESS-PF8-KEY}, line 273). */
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";

    private final UserService userService;

    private final UserMapper userMapper;

    /**
     * Creates the controller with its collaborators (constructor injection only).
     *
     * @param userService the user-administration service reproducing the
     *                    {@code COUSR00C} browse logic; must not be {@code null}
     * @param userMapper  the hand-written entity&harr;DTO mapper (rows never carry
     *                    a password); must not be {@code null}
     */
    public UserListController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    /**
     * Initial display of the List Users screen &mdash; the first page of users in
     * ascending user-id order. This reproduces the legacy first-time entry
     * ({@code COUSR00C} {@code MAIN-PARA} when {@code NOT CDEMO-PGM-REENTER}), which
     * browses {@code USRSEC} from the start and displays page one.
     *
     * @return {@code 200 OK} with the first {@link UserListResponse} page (page
     *         number {@code "1"}, up to ten rows, no error message)
     */
    @GetMapping
    @Operation(summary = "First page of the User List",
            description = "First-entry screen listing the initial page of users (COBOL COUSR00C initial browse). Requires the ADMIN role.")
    public ResponseEntity<UserListResponse> firstPage() {
        LocalDateTime now = LocalDateTime.now();
        return ResponseEntity.ok(buildResponse(page(null, 0), null, now));
    }

    /**
     * Handles a List Users screen submit, routing on the transmitted attention key
     * exactly as the legacy {@code EVALUATE EIBAID} in {@code COUSR00C}
     * {@code MAIN-PARA}:
     * <ul>
     *   <li>{@link PfKeyAction#ENTER} (and an absent action, treated as the default
     *       entry path) &rarr; process a row selection or re-display the page;</li>
     *   <li>{@link PfKeyAction#PF7} &rarr; page backward;</li>
     *   <li>{@link PfKeyAction#PF8} &rarr; page forward;</li>
     *   <li>{@link PfKeyAction#PF3} &rarr; return to the Admin Menu ({@code CA00});</li>
     *   <li>any other key &rarr; the invalid-key message ({@code WHEN OTHER}).</li>
     * </ul>
     *
     * @param request the validated screen input (start key, per-row selection
     *                flags, and attention key); never logged
     * @return {@code 200 OK} with the resulting {@link UserListResponse}; selection
     *         and PF3 outcomes additionally carry navigation headers naming the next
     *         program/transaction
     */
    @PostMapping("/list")
    @Operation(summary = "Page the User List",
            description = "Reproduces the COUSR00C EVALUATE EIBAID paging: PF7/PF8 browse the user list backward and forward.")
    public ResponseEntity<UserListResponse> list(@Valid @RequestBody UserListRequest request) {
        LocalDateTime now = LocalDateTime.now();
        PfKeyAction action = (request.action() == null) ? PfKeyAction.ENTER : request.action();
        return switch (action) {
            case ENTER -> handleEnter(request, now);
            case PF7 -> navigate(request, now, false);
            case PF8 -> navigate(request, now, true);
            case PF3 -> exitToAdminMenu(now);
            default -> invalidKey(now);
        };
    }

    /**
     * Reproduces {@code COUSR00C} {@code PROCESS-ENTER-KEY}. The displayed page is
     * the first page anchored at the request's start key ({@code userId}); this is
     * exactly the set of rows the operator saw, so a selection flag is resolved
     * against it. The legacy program acts on the first non-blank selection marker;
     * an empty marker or a marker referencing a blank row simply re-displays the
     * page.
     *
     * @param request the screen input carrying the start key and per-row flags
     * @param now     the timestamp used to render the header date/time
     * @return a navigation response (to Update/Delete User) for a valid
     *         {@code 'U'}/{@code 'D'} marker, or a page (re)display carrying the
     *         invalid-selection message or no message
     */
    private ResponseEntity<UserListResponse> handleEnter(UserListRequest request, LocalDateTime now) {
        Page<UserSecurity> currentPage = page(request.userId(), 0);
        List<UserSecurity> rows = currentPage.getContent();

        int selectionIndex = firstSelectionIndex(request.rowSelections());
        if (selectionIndex >= 0 && selectionIndex < rows.size()) {
            String marker = request.rowSelections().get(selectionIndex).trim();
            String selectedUserId = rows.get(selectionIndex).getSecUsrId();
            if (SELECT_UPDATE.equalsIgnoreCase(marker)) {
                return navigationResponse(PROGRAM_UPDATE, TRANSACTION_UPDATE, selectedUserId, currentPage, now);
            }
            if (SELECT_DELETE.equalsIgnoreCase(marker)) {
                return navigationResponse(PROGRAM_DELETE, TRANSACTION_DELETE, selectedUserId, currentPage, now);
            }
            // Marker present but not U/D: re-display the page with the COBOL message.
            return ResponseEntity.ok(buildResponse(currentPage, MSG_INVALID_SELECTION, now));
        }

        // No resolvable selection: display page one from the start-key filter.
        return ResponseEntity.ok(buildResponse(currentPage, null, now));
    }

    /**
     * Reproduces {@code COUSR00C} {@code PROCESS-PF8-KEY} (forward) and
     * {@code PROCESS-PF7-KEY} (backward). The request's {@code userId} is the anchor
     * (the first user id of the currently displayed page). The anchor's position in
     * the full ascending ordering is derived from the total counts, and the target
     * page is fetched accordingly so the 1-based page number tracks the operator's
     * position. At the boundaries the current page is re-displayed with the legacy
     * &quot;already at the top/bottom&quot; message.
     *
     * @param request the screen input carrying the page anchor ({@code userId})
     * @param now     the timestamp used to render the header date/time
     * @param forward {@code true} to page forward (PF8), {@code false} to page
     *                backward (PF7)
     * @return {@code 200 OK} with the target (or boundary-limited current) page
     */
    private ResponseEntity<UserListResponse> navigate(UserListRequest request, LocalDateTime now, boolean forward) {
        long grandTotal = totalCount(null);
        int currentPageIndex = currentPageIndex(request.userId(), grandTotal);

        if (forward) {
            int nextPageIndex = currentPageIndex + 1;
            if ((long) nextPageIndex * PAGE_SIZE >= grandTotal) {
                // Already at the bottom: re-display the current page with the message.
                return ResponseEntity.ok(buildResponse(page(null, currentPageIndex), MSG_ALREADY_BOTTOM, now));
            }
            return ResponseEntity.ok(buildResponse(page(null, nextPageIndex), null, now));
        }

        if (currentPageIndex <= 0) {
            // Already at the top: re-display the first page with the message.
            return ResponseEntity.ok(buildResponse(page(null, 0), MSG_ALREADY_TOP, now));
        }
        return ResponseEntity.ok(buildResponse(page(null, currentPageIndex - 1), null, now));
    }

    /**
     * Reproduces the {@code WHEN DFHPF3} branch: return to the Admin Menu
     * ({@code COADM01C} / {@code CA00}). The legacy program transfers control via
     * {@code XCTL}; here that is surfaced as navigation headers with an empty-list
     * body (the user list is not re-rendered on exit).
     *
     * @param now the timestamp used to render the header date/time
     * @return {@code 200 OK} with navigation headers to the Admin Menu
     */
    private ResponseEntity<UserListResponse> exitToAdminMenu(LocalDateTime now) {
        return ResponseEntity.ok()
                .header(HDR_NEXT_PROGRAM, PROGRAM_ADMIN_MENU)
                .header(HDR_NEXT_TRANSACTION, TRANSACTION_ADMIN_MENU)
                .body(emptyResponse(null, now));
    }

    /**
     * Reproduces the {@code WHEN OTHER} branch: an unsupported attention key yields
     * the invalid-key message and does not re-browse the list.
     *
     * @param now the timestamp used to render the header date/time
     * @return {@code 200 OK} with an empty-list body carrying the invalid-key message
     */
    private ResponseEntity<UserListResponse> invalidKey(LocalDateTime now) {
        return ResponseEntity.ok(emptyResponse(MSG_INVALID_KEY, now));
    }

    /**
     * Builds a navigation response reproducing an {@code EXEC CICS XCTL} to the
     * Update or Delete User screen, carrying the selected user id. The current page
     * is echoed as the body so the client retains list context.
     *
     * @param targetProgram     the next program name ({@code COUSR02C}/{@code COUSR03C})
     * @param targetTransaction the next transaction id ({@code CU02}/{@code CU03})
     * @param selectedUserId    the user id selected on the current page
     * @param currentPage       the currently displayed page (echoed in the body)
     * @param now               the timestamp used to render the header date/time
     * @return {@code 200 OK} with navigation headers and the current-page body
     */
    private ResponseEntity<UserListResponse> navigationResponse(String targetProgram,
                                                                String targetTransaction,
                                                                String selectedUserId,
                                                                Page<UserSecurity> currentPage,
                                                                LocalDateTime now) {
        return ResponseEntity.ok()
                .header(HDR_NEXT_PROGRAM, targetProgram)
                .header(HDR_NEXT_TRANSACTION, targetTransaction)
                .header(HDR_SELECTED_USER_ID, selectedUserId)
                .body(buildResponse(currentPage, null, now));
    }

    /**
     * Assembles a {@link UserListResponse} from a fetched page and the surrounding
     * screen context. The 1-based page number is taken from the returned
     * {@link Page}. Rows are projected by {@link UserMapper}, which never maps a
     * password.
     *
     * @param page         the fetched page of users
     * @param errorMessage the status/error line ({@code ERRMSGO}), or {@code null}
     * @param now          the timestamp used to render the header date/time
     * @return the assembled response
     */
    private UserListResponse buildResponse(Page<UserSecurity> page, String errorMessage, LocalDateTime now) {
        String pageNumber = String.valueOf(page.getNumber() + 1);
        return userMapper.toListResponse(page.getContent(), pageNumber, errorMessage, now,
                TRANSACTION_ID, TITLE01, TITLE02, PROGRAM_ID);
    }

    /**
     * Assembles a {@link UserListResponse} with no user rows and no page number, for
     * the exit (PF3) and invalid-key outcomes where the legacy program does not
     * render the list.
     *
     * @param errorMessage the status/error line ({@code ERRMSGO}), or {@code null}
     * @param now          the timestamp used to render the header date/time
     * @return the assembled empty-list response
     */
    private UserListResponse emptyResponse(String errorMessage, LocalDateTime now) {
        return userMapper.toListResponse(List.of(), null, errorMessage, now,
                TRANSACTION_ID, TITLE01, TITLE02, PROGRAM_ID);
    }

    /**
     * Fetches a page of users, optionally positioned at an inclusive start key,
     * reproducing the legacy {@code STARTBR} reposition plus the ten-row page window.
     *
     * @param startKey  the inclusive start key ({@code null}/blank starts at the
     *                  beginning), reproducing {@code STARTBR RIDFLD(SEC-USR-ID)}
     * @param pageIndex the zero-based page index of the {@value #PAGE_SIZE}-row window
     * @return the requested {@link Page} of {@link UserSecurity} in ascending id order
     */
    private Page<UserSecurity> page(String startKey, int pageIndex) {
        return userService.listUsers(startKey, PageRequest.of(pageIndex, PAGE_SIZE, SORT));
    }

    /**
     * Returns the number of users at or after the given start key (or the grand
     * total when the key is {@code null}/blank) as a single {@code COUNT} query,
     * without fetching any rows. This backs the {@code PF7}/{@code PF8} paging
     * position math ({@link #currentPageIndex(String, long)} and the bottom-boundary
     * check) and deliberately avoids re-reading the user table just to obtain a
     * total, mirroring the database-side count used by the card and transaction
     * lists (AAP &sect;0.4.3).
     *
     * @param startKey the inclusive start key, or {@code null}/blank for the grand total
     * @return the matching user count
     */
    private long totalCount(String startKey) {
        return userService.countUsers(startKey);
    }

    /**
     * Derives the zero-based page index of the page anchored at {@code cursorUserId}
     * within the full ascending ordering. The number of users strictly before the
     * anchor is {@code grandTotal - count(id >= cursor)}; dividing by the page size
     * yields the current page index. A blank cursor or an empty list is page zero.
     *
     * @param cursorUserId the anchor (first user id of the current page); may be
     *                     {@code null}/blank
     * @param grandTotal   the grand total user count
     * @return the zero-based index of the anchor's page
     */
    private int currentPageIndex(String cursorUserId, long grandTotal) {
        if (cursorUserId == null || cursorUserId.isBlank() || grandTotal == 0) {
            return 0;
        }
        long usersBefore = grandTotal - totalCount(cursorUserId);
        if (usersBefore < 0) {
            usersBefore = 0;
        }
        return (int) (usersBefore / PAGE_SIZE);
    }

    /**
     * Finds the index of the first non-blank per-row selection marker, reproducing
     * the legacy {@code EVALUATE TRUE} that acts on the first {@code SEL000n} that is
     * {@code NOT = SPACES AND LOW-VALUES}. A {@code null} or blank element is an
     * unselected row.
     *
     * @param selections the per-row selection markers (may be {@code null} or contain
     *                   {@code null}/blank elements)
     * @return the zero-based index of the first non-blank marker, or {@code -1} if none
     */
    private static int firstSelectionIndex(List<String> selections) {
        if (selections == null) {
            return -1;
        }
        for (int i = 0; i < selections.size(); i++) {
            String marker = selections.get(i);
            if (marker != null && !marker.isBlank()) {
                return i;
            }
        }
        return -1;
    }
}

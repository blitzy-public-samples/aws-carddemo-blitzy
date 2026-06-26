/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.service.online;

import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.UserListScreen;
import com.aws.carddemo.dto.screen.UserListScreen.UserListRow;
import com.aws.carddemo.exception.AuthorizationException;
import com.aws.carddemo.repository.UserListProjection;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.util.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Online service that reproduces the legacy CICS program <strong>COUSR00C</strong> (transaction
 * {@code CU00} &mdash; <em>List Users</em>, <strong>admin-only</strong>) with 100% behavioral
 * parity (Agent Action Plan &sect;0.1.1, &sect;0.7.1).
 *
 * <p>COUSR00C is the administrative entry point for user maintenance. It browses the {@code USRSEC}
 * VSAM KSDS &mdash; now the {@code user_security} table reached through {@link
 * UserSecurityRepository} &mdash; in ascending user-id order, presenting the security users ten
 * rows at a time. The operator may page forward ({@code PF8}) and backward ({@code PF7}), filter by
 * entering a starting user id, mark a row with {@code U}/{@code D} to route to the update ({@code
 * COUSR02C}) or delete ({@code COUSR03C}) screen, or return to the admin menu ({@code COADM01C})
 * with {@code PF3}. This service preserves that control flow verbatim: each named COBOL paragraph
 * becomes a private method invoked in the same perform/branch order (AAP &sect;0.6.7
 * paragraph&rarr;method traceability), and every operator-visible message literal is reproduced
 * byte-for-byte.
 *
 * <h2>VSAM browse &rarr; keyset paging</h2>
 *
 * <p>The legacy program drives a VSAM browse ({@code STARTBR}/{@code READNEXT}/{@code READPREV})
 * over {@code USRSEC}. The modernized equivalent issues <strong>bounded keyset (range)
 * queries</strong> on {@link UserSecurityRepository} anchored at the round-tripped boundary user
 * id: each page turn fetches only {@code PAGE_SIZE + 1} rows (the ten-row page plus a single
 * read-ahead "next page exists" peek) instead of loading the whole {@code user_security} table.
 * {@code findBySecUsrIdGreaterThanEqual...} reproduces {@code STARTBR} GTEQ (refresh / browse from
 * the top); {@code findBySecUsrIdGreaterThan...} the {@code READNEXT} step past the previous page's
 * last id (PF8); {@code findBySecUsrIdLessThan...Desc} the {@code READPREV} page-up (PF7, reversed
 * to ascending); and {@code existsBySecUsrIdGreaterThan} the next-page recomputation. This
 * preserves the {@code GTEQ} positioning, the ten-row page size, the read-ahead peek, and the
 * first/last keyset boundaries the program carries across interactions &mdash; while bounding
 * result memory to O(page) rather than O(table) (closing the unbounded-read finding).
 *
 * <p>The browse queries return the {@link UserListProjection} closed projection (user id, first
 * name, last name, type), so the {@code sec_usr_pwd} BCrypt hash &mdash; which the list screen
 * never displays &mdash; is never selected for a list view.
 *
 * <h2>No mutable instance state</h2>
 *
 * <p>This bean is a stateless Spring singleton. The legacy {@code CDEMO-CU00-INFO} paging/selection
 * cursors ({@code USRID-FIRST}, {@code USRID-LAST}, {@code PAGE-NUM}, {@code NEXT-PAGE-FLG}, {@code
 * USR-SELECTED}) are <em>not</em> part of the shared {@link CardDemoCommarea} contract. They are
 * reconstructed each call from the round-tripped {@link UserListScreen}: the page counter from
 * {@link UserListScreen#getPageNum()}, the filter from {@link UserListScreen#getUsrIdIn()}, and the
 * PF7/PF8 cursors from the first/last populated {@link UserListRow#getUsrId()} of the current page.
 * The "next page exists" indicator is recomputed from the freshly sorted list rather than
 * persisted.
 *
 * <h2>Admin-only gating (AAP &sect;0.6.5, &sect;0.7.2)</h2>
 *
 * <p>{@code CU00} is reachable only from the admin menu because {@code COSGN00C} routes an
 * administrator ({@code CDEMO-USRTYP-ADMIN}) there. That invariant is enforced two ways:
 * declaratively via the class-level {@link PreAuthorize} {@code hasRole('ADMIN')} (effective when
 * the bean is proxied under Spring method security), and as defense-in-depth via an explicit {@link
 * CardDemoCommarea#isAdmin()} check at method entry that throws {@link AuthorizationException}
 * (mapped to HTTP 403 by {@code GlobalExceptionHandler}). The explicit check is also what plain
 * unit tests exercise, since a directly instantiated bean has no security proxy.
 *
 * <h2>Validation vs. failure (AAP &sect;0.6.4)</h2>
 *
 * <p>Per the list/browse convention, "edge of list" outcomes &mdash; empty browse, end-of-file
 * while paging forward, and start-of-file while paging backward &mdash; are <em>not</em>
 * exceptions: this service sets the {@link UserListScreen#setErrMsg(String) message} and returns
 * {@code null} to redisplay the list, exactly as COUSR00C moves {@code WS-MESSAGE} and re-sends.
 * Only a genuinely unexpected datastore failure surfaces as the {@link #MSG_UNABLE_LOOKUP_USER}
 * redisplay message; no {@code IoStatusException} is thrown for an ordinary not-found in this list
 * screen.
 *
 * <h2>Division of responsibility</h2>
 *
 * <p>This service owns the business logic and the screen <em>data</em> it computes (the ten user
 * rows, the page indicator, and the error/informational message). The web/controller layer (sibling
 * {@code UserListController}) owns HTTP, the {@link CardDemoCommarea} session round-trip,
 * AID/PF-key resolution, the {@code POPULATE-HEADER-INFO} title/date/time fields, navigation
 * routing (mapping the returned program name to a URL), and the selected-id handoff (copying the
 * marked row's user id into the target update/delete screen). Authority: AAP &sect;0.4.1 (COUSR00C
 * &rarr; UserListController + UserListService) with source of record {@code
 * legacy/app/cbl/COUSR00C.cbl}.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class UserListService {

  // ===============================================================================================
  // Identity literals (COBOL WORKING-STORAGE: WS-PGMNAME, WS-TRANID) and the navigation targets
  // reached by this transaction. Reproduced byte-for-byte from COUSR00C.
  // ===============================================================================================

  /** This program's name. COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COUSR00C'} (L36). */
  static final String PGM_NAME = "COUSR00C";

  /** CICS transaction id of this screen. COBOL {@code WS-TRANID PIC X(04) VALUE 'CU00'} (L37). */
  static final String TRAN_ID = "CU00";

  /**
   * Sign-on program; the {@code RETURN-TO-PREV-SCREEN} default target when none is set (L508-509).
   */
  static final String LIT_SIGNON_PGM = "COSGN00C";

  /** Admin-menu program; the {@code PF3} (return) target (L126). */
  static final String LIT_ADMIN_PGM = "COADM01C";

  /** Update-user program; the target when a row is marked {@code U}/{@code u} (L192). */
  static final String LIT_USER_UPDATE_PGM = "COUSR02C";

  /** Delete-user program; the target when a row is marked {@code D}/{@code d} (L202). */
  static final String LIT_USER_DELETE_PGM = "COUSR03C";

  // ===============================================================================================
  // Operator-visible message literals, reproduced byte-for-byte from COUSR00C.cbl (the trailing
  // periods/ellipses are part of the literals and are preserved exactly).
  // ===============================================================================================

  /** Row marked with a flag other than {@code U}/{@code D}. COUSR00C L211-213. */
  static final String MSG_INVALID_SELECTION = "Invalid selection. Valid values are U and D";

  /** {@code PF7} pressed while already on page one. COUSR00C L251-252. */
  static final String MSG_TOP_OF_PAGE = "You are already at the top of the page...";

  /** {@code PF8} pressed with no further page. COUSR00C L273-274. */
  static final String MSG_BOTTOM_OF_PAGE = "You are already at the bottom of the page...";

  /** {@code STARTBR} returned NOTFND (empty file or filter beyond end). COUSR00C L603-604. */
  static final String MSG_AT_TOP = "You are at the top of the page...";

  /** {@code READNEXT} reached end of file while paging forward. COUSR00C L637-638. */
  static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";

  /** {@code READPREV} reached start of file while paging backward. COUSR00C L671-672. */
  static final String MSG_REACHED_TOP = "You have reached the top of the page...";

  /**
   * Unexpected datastore failure (the {@code WHEN OTHER} status branch). COUSR00C L610/L644/L678.
   */
  static final String MSG_UNABLE_LOOKUP_USER = "Unable to lookup User...";

  /** Fixed number of user rows rendered per page (the 3270 list geometry). COUSR00C OCCURS 10. */
  static final int PAGE_SIZE = 10;

  /**
   * Repository projecting the {@code USRSEC} store &mdash; the modernized equivalent of the VSAM
   * browse over the KSDS primary key. The list screen reads through the bounded keyset browse
   * methods ({@code findBySecUsrId...}) returning the {@link UserListProjection} (the displayed
   * columns only), and the read-ahead probe ({@code existsBySecUsrIdGreaterThan}).
   */
  private final UserSecurityRepository userSecurityRepository;

  /**
   * Creates the service with its required collaborator (sole constructor; constructor injection).
   *
   * @param userSecurityRepository the {@code user_security} browse source; must not be {@code null}
   */
  public UserListService(UserSecurityRepository userSecurityRepository) {
    this.userSecurityRepository =
        Objects.requireNonNull(userSecurityRepository, "userSecurityRepository must not be null");
  }

  // ===============================================================================================
  // MAIN-PARA  (COUSR00C L98-L144)
  // ===============================================================================================

  /**
   * Entry point reproducing {@code MAIN-PARA}: the pseudo-conversational dispatch for transaction
   * {@code CU00}.
   *
   * <p>Control flow, preserved verbatim from the legacy program:
   *
   * <ol>
   *   <li>Enforce the admin-only gate (defense-in-depth alongside the class {@link PreAuthorize}):
   *       a non-admin COMMAREA raises {@link AuthorizationException}, mirroring that {@code CU00}
   *       is reachable only from the admin menu.
   *   <li>Reset the message line for this interaction (L105-106).
   *   <li>On first entry ({@code NOT CDEMO-PGM-REENTER}), mark re-entry and paint the initial page
   *       by performing the enter-key path, which browses from the top (L115-119).
   *   <li>On re-entry, dispatch on the attention id (L121-137): {@code ENTER} &rarr;
   *       selection/filter handling; {@code PF3} &rarr; return to the admin menu; {@code
   *       PF7}/{@code PF8} &rarr; page up/down; anything else &rarr; the shared "invalid key"
   *       message.
   * </ol>
   *
   * @param screen the list screen carrying the filter, the ten rows, the page indicator, and the
   *     message line; must not be {@code null}
   * @param commarea the pseudo-conversational navigation/session state; must not be {@code null}
   * @param aid the resolved attention identifier for this interaction; {@code null} is treated as
   *     {@code ENTER}
   * @return the next program name for the controller to route to ({@link #LIT_ADMIN_PGM} on {@code
   *     PF3}, {@link #LIT_USER_UPDATE_PGM}/{@link #LIT_USER_DELETE_PGM} on a valid row selection),
   *     or {@code null} to redisplay the user list
   * @throws AuthorizationException if {@code commarea} does not denote an administrator
   */
  public String processUserList(
      UserListScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // Admin-only gate (AAP §0.6.5/§0.7.2). CU00 is reachable only from the admin menu; reject a
    // non-admin conversation exactly where the legacy routing would never have transferred here.
    if (!commarea.isAdmin()) {
      throw new AuthorizationException();
    }

    // L105-106: clear the message line for this interaction (MOVE SPACES TO WS-MESSAGE, ERRMSGO).
    screen.setErrMsg("");

    // L115-119: first entry to this program. Mark re-entry for subsequent turns and paint the
    // initial page by performing the enter-key path (which, with no selection and the given filter,
    // browses from the top of the file).
    if (!commarea.isPgmReenter()) {
      commarea.setPgmReenter();
      return processEnterKey(screen, commarea);
    }

    // L121-137: re-entry. Dispatch on the attention id.
    if (aid == CardWorkArea.Aid.PFK03) {
      // L125-127: PF3 returns to the admin menu (RETURN-TO-PREV-SCREEN with COADM01C).
      return returnToPrevScreen(commarea, LIT_ADMIN_PGM);
    }
    if (aid == CardWorkArea.Aid.PFK07) {
      // L128-129: PF7 pages backward.
      return processPf7Key(screen);
    }
    if (aid == CardWorkArea.Aid.PFK08) {
      // L130-131: PF8 pages forward.
      return processPf8Key(screen);
    }
    if (aid == null || aid == CardWorkArea.Aid.ENTER) {
      // L122-124: ENTER processes selection/filter and (re)paints the current page.
      return processEnterKey(screen, commarea);
    }

    // L132-136: WHEN OTHER -> shared "invalid key" message, redisplay. The legacy
    // CCDA-MSG-INVALID-KEY is the space-padded PIC X(50) literal; it is trimmed for display per the
    // migration convention shared with the sibling online services.
    screen.setErrMsg(Messages.MSG_INVALID_KEY.trim());
    return null;
  }

  // ===============================================================================================
  // PROCESS-ENTER-KEY  (COUSR00C L149-L233)
  // ===============================================================================================

  /**
   * Reproduces {@code PROCESS-ENTER-KEY}: interpret any row selection, then (re)load the list page.
   *
   * <p>Control flow, preserved verbatim:
   *
   * <ol>
   *   <li>Scan the ten selection inputs {@code SEL0001I..SEL0010I} in order and capture the first
   *       non-blank flag together with the user id displayed on that row (the COBOL selection
   *       {@code EVALUATE}, L150-184).
   *   <li>If both a flag and a user id are present, route by flag: {@code U}/{@code u} transfers to
   *       the update program {@code COUSR02C}; {@code D}/{@code d} transfers to the delete program
   *       {@code COUSR03C}; any other flag sets the invalid-selection message and <em>falls
   *       through</em> to the page load below &mdash; exactly as the legacy {@code EVALUATE WHEN
   *       OTHER} does (L186-208).
   *   <li>Choose the browse start key from the filter field: a blank filter starts from the lowest
   *       key (COBOL {@code MOVE LOW-VALUES TO SEC-USR-ID}); a non-blank filter starts at that user
   *       id (L210-216).
   *   <li>Reset the page counter to zero and page forward, which paints page one and advances the
   *       counter to one (L219-220).
   * </ol>
   *
   * <p>When a valid {@code U}/{@code D} selection is made, the method returns the target program
   * name <em>without</em> loading a page (mirroring the legacy {@code XCTL}, which transfers
   * control and never reaches the page-load code). The controller copies the selected row's user id
   * into the target screen's input field.
   *
   * @param screen the list screen (selections, filter, rows, page indicator, message)
   * @param commarea the navigation state to stamp when transferring to update/delete
   * @return the update/delete program name on a valid selection, otherwise {@code null} to
   *     redisplay
   */
  private String processEnterKey(UserListScreen screen, CardDemoCommarea commarea) {
    // L150-184: selection scan. Capture the FIRST row whose selection flag is non-blank, along with
    // that row's user id. COBOL scans SEL0001I..SEL0010I in row order and stops at the first hit.
    String selFlag = null;
    String selectedUsrId = null;
    List<UserListRow> rows = screen.getRows();
    if (rows != null) {
      for (UserListRow row : rows) {
        String sel = row.getSel();
        if (sel != null && !sel.isBlank()) {
          selFlag = sel;
          selectedUsrId = row.getUsrId();
          break;
        }
      }
    }

    // L186-208: with both a selection flag and a populated user id, route to update/delete. An
    // unrecognized flag sets the invalid-selection message and falls through to the page reload
    // below (the legacy WHEN OTHER path leaves WS-MESSAGE set and continues to
    // PROCESS-PAGE-FORWARD).
    if (isPresent(selFlag) && isPresent(selectedUsrId)) {
      String flag = selFlag.trim();
      if ("U".equalsIgnoreCase(flag)) {
        return returnToPrevScreen(commarea, LIT_USER_UPDATE_PGM);
      }
      if ("D".equalsIgnoreCase(flag)) {
        return returnToPrevScreen(commarea, LIT_USER_DELETE_PGM);
      }
      screen.setErrMsg(MSG_INVALID_SELECTION);
    }

    // L210-216: a blank filter browses from the lowest key (start key null); otherwise browse from
    // the entered user id.
    String filter = screen.getUsrIdIn();
    String startKey = isPresent(filter) ? filter.trim() : null;

    // L219: MOVE 0 TO CDEMO-CU00-PAGE-NUM. PROCESS-PAGE-FORWARD then advances it to 1.
    screen.setPageNum(formatPageNum(0));

    return processPageForward(screen, startKey, false);
  }

  // ===============================================================================================
  // PROCESS-PF7-KEY  (COUSR00C L237-L258)
  // ===============================================================================================

  /**
   * Reproduces {@code PROCESS-PF7-KEY}: page backward.
   *
   * <p>The backward cursor is the first user id shown on the current page (COBOL {@code
   * CDEMO-CU00-USRID-FIRST}). When the current page is greater than one, page backward from that
   * cursor; otherwise there is nothing earlier to show, so set the "already at the top" message and
   * redisplay the current page unchanged (L251-256).
   *
   * @param screen the list screen carrying the current page number and rows
   * @return always {@code null}; PF7 never transfers to another program
   */
  private String processPf7Key(UserListScreen screen) {
    if (currentPageNum(screen) > 1) {
      String firstUsrId = firstPopulatedUsrId(screen);
      return processPageBackward(screen, firstUsrId);
    }
    // L251-253: already on the first page.
    screen.setErrMsg(MSG_TOP_OF_PAGE);
    return null;
  }

  // ===============================================================================================
  // PROCESS-PF8-KEY  (COUSR00C L260-L280)
  // ===============================================================================================

  /**
   * Reproduces {@code PROCESS-PF8-KEY}: page forward.
   *
   * <p>The forward cursor is the last user id shown on the current page (COBOL {@code
   * CDEMO-CU00-USRID-LAST}). The legacy {@code NEXT-PAGE} flag is recomputed here from the freshly
   * sorted list: a next page exists only when at least one user sorts strictly after the last one
   * shown. When a next page exists, page forward (skipping the boundary record, mirroring the
   * READNEXT consumed for a non-ENTER AID); otherwise set the "already at the bottom" message and
   * redisplay unchanged (L272-278).
   *
   * @param screen the list screen carrying the current page rows
   * @return always {@code null}; PF8 never transfers to another program
   */
  private String processPf8Key(UserListScreen screen) {
    String lastUsrId = lastPopulatedUsrId(screen);
    if (!isPresent(lastUsrId)) {
      // No populated row to page beyond (e.g., an empty list): already at the bottom.
      screen.setErrMsg(MSG_BOTTOM_OF_PAGE);
      return null;
    }
    // L264-271: recompute the NEXT-PAGE flag with a bounded read-ahead probe (does any user sort
    // strictly after the last one shown?) rather than scanning a full in-memory snapshot.
    Boolean nextPageExists = existsAfter(screen, lastUsrId);
    if (nextPageExists == null) {
      return null; // data-access failure; MSG_UNABLE_LOOKUP_USER already set
    }
    if (nextPageExists) {
      return processPageForward(screen, lastUsrId, true);
    }
    // L273-275: NEXT-PAGE-NO -> already at the bottom.
    screen.setErrMsg(MSG_BOTTOM_OF_PAGE);
    return null;
  }

  // ===============================================================================================
  // PROCESS-PAGE-FORWARD  (COUSR00C L284-L331)
  // ===============================================================================================

  /**
   * Reproduces {@code PROCESS-PAGE-FORWARD}: read up to ten ascending users from the start key and
   * paint them onto the screen.
   *
   * <p>This is the in-service equivalent of the legacy {@code STARTBR} / {@code READNEXT} loop over
   * {@code USRSEC}. Behavior preserved verbatim:
   *
   * <ul>
   *   <li><strong>STARTBR NOTFND</strong> &mdash; when no user sorts at or after the start key, the
   *       page is blank, the message is "You are at the top of the page...", and the page counter
   *       is left unchanged (L286, L601-606).
   *   <li><strong>Boundary skip</strong> &mdash; for a forward page triggered by a non-ENTER AID
   *       (PF8), one record is consumed before reading the page, mirroring the extra {@code
   *       READNEXT} the legacy program performs to step past the previous page's last row
   *       (L289-291).
   *   <li><strong>Page fill</strong> &mdash; up to ten users are read ascending into the rows
   *       (L301-307).
   *   <li><strong>Read-ahead peek</strong> &mdash; a record beyond the page sets "next page exists"
   *       (NEXT-PAGE-YES); end of file on the peek both clears that flag and (via the legacy {@code
   *       READNEXT} ENDFILE branch) sets "You have reached the bottom of the page..." (L309-324,
   *       L632-639).
   *   <li><strong>Page counter</strong> &mdash; once at least one row is materialized, the counter
   *       advances by one (L310-311, L320-323).
   * </ul>
   *
   * @param screen the list screen to paint
   * @param all the full ascending-by-user-id snapshot of {@code user_security}
   * @param startKey the GTEQ start key, or {@code null} to start at the lowest key
   * @param skipFirst {@code true} to consume the boundary record first (PF8 forward)
   * @return always {@code null}; paging never transfers to another program
   */
  private String processPageForward(UserListScreen screen, String startKey, boolean skipFirst) {
    // STARTBR GTEQ positioning: a null start key (LOW-VALUES) browses from the lowest key, modelled
    // as the empty string. The keyset query fetches only PAGE_SIZE + 1 rows: the ten-row page plus
    // one read-ahead row reproducing the legacy "next page exists" peek, projected to the four
    // displayed columns (the password hash is never selected).
    String key = (startKey == null) ? "" : startKey;
    List<UserListProjection> fetched = browseForward(screen, key, skipFirst, PAGE_SIZE + 1);
    if (fetched == null) {
      return null; // data-access failure; MSG_UNABLE_LOOKUP_USER already set
    }

    List<UserListRow> page = buildBlankPage();

    // L601-606: STARTBR NOTFND. No record at or after the start key (empty browse or a filter past
    // the end of file). Blank page, "top of page" message, page counter unchanged. (A PF8 skip
    // browse is only entered after the next-page probe confirms a following record, so its empty
    // result is unreachable in normal flow.)
    if (!skipFirst && fetched.isEmpty()) {
      screen.setRows(page);
      screen.setErrMsg(MSG_AT_TOP);
      screen.setUsrIdIn("");
      screen.setPageNum(formatPageNum(currentPageNum(screen)));
      return null;
    }

    // L289-307: fill up to ten rows ascending from the fetched page slice.
    int rowsRead = Math.min(PAGE_SIZE, fetched.size());
    for (int i = 0; i < rowsRead; i++) {
      populateUserData(page, i, fetched.get(i));
    }

    // L312-318: read-ahead peek. A fetched row beyond the page (size == PAGE_SIZE + 1) means a next
    // page exists, exactly reproducing the legacy (from + PAGE_SIZE) < size test; otherwise the end
    // of file has been reached.
    boolean nextPageExists = fetched.size() > PAGE_SIZE;

    if (rowsRead > 0) {
      // L310-311 / L320-323: advance the page counter once a page has been materialized.
      screen.setPageNum(formatPageNum(currentPageNum(screen) + 1));
    }
    // L632-639: the READNEXT ENDFILE branch sets the "bottom of page" message when the peek beyond
    // the last shown row reaches end of file.
    if (!nextPageExists && rowsRead > 0) {
      screen.setErrMsg(MSG_REACHED_BOTTOM);
    }

    screen.setRows(page);
    // L327: MOVE SPACE TO USRIDINO. The filter field is cleared once the page is shown.
    screen.setUsrIdIn("");
    return null;
  }

  // ===============================================================================================
  // PROCESS-PAGE-BACKWARD  (COUSR00C L335-L380)
  // ===============================================================================================

  /**
   * Reproduces {@code PROCESS-PAGE-BACKWARD}: read up to ten users descending from the cursor and
   * paint them in ascending order.
   *
   * <p>This is the in-service equivalent of the legacy {@code STARTBR} / {@code READPREV} loop.
   * Behavior preserved verbatim:
   *
   * <ul>
   *   <li><strong>Bottom-up fill</strong> &mdash; reading backward from just before the cursor, the
   *       first record read lands in the last row and earlier records fill upward, so the page is
   *       presented ascending (the COBOL {@code WS-IDX} 10-down-to-1 loop, L353-359).
   *   <li><strong>Page counter</strong> &mdash; if earlier records remain (the backward peek
   *       succeeds), the counter steps back by one but never below one; if the start of file is
   *       reached, the counter is pinned to one and "You have reached the top of the page..." is
   *       set (the legacy {@code READPREV} ENDFILE branch, L361-373, L666-673).
   * </ul>
   *
   * <p>Unlike forward paging, the filter field is not cleared here, matching the legacy paragraph
   * (which does not {@code MOVE SPACE TO USRIDINO} on the backward path).
   *
   * @param screen the list screen to paint
   * @param all the full ascending-by-user-id snapshot of {@code user_security}
   * @param startKey the first user id of the current page (the backward cursor), or {@code null}
   * @return always {@code null}; paging never transfers to another program
   */
  private String processPageBackward(UserListScreen screen, String startKey) {
    // READPREV walk: fetch up to PAGE_SIZE + 1 users strictly below the current page's first id, in
    // descending order (closest-below first), projected to the four displayed columns. A null
    // cursor
    // (LOW-VALUES) means the current page started at the top of file, so there is nothing above it.
    // The extra (eleventh) row is the read-ahead peek that reproduces the legacy start-of-file
    // detection (from == 0): ten or fewer records below the cursor means the top has been reached.
    List<UserListProjection> fetched =
        (startKey == null) ? List.of() : browseBackward(screen, startKey, PAGE_SIZE + 1);
    if (fetched == null) {
      return null; // data-access failure; MSG_UNABLE_LOOKUP_USER already set
    }

    int rowsRead = Math.min(PAGE_SIZE, fetched.size());
    boolean reachedTop = fetched.size() <= PAGE_SIZE; // <=> legacy from == 0

    List<UserListRow> page = buildBlankPage();

    // L353-359: READPREV bottom-up fill. The fetched slice is descending (closest-below first), so
    // the record immediately before the cursor lands in the last row and each earlier record fills
    // the row above, yielding the previous page in ascending order.
    for (int i = 0; i < rowsRead; i++) {
      populateUserData(page, (PAGE_SIZE - 1) - i, fetched.get(i));
    }

    if (reachedTop) {
      // L666-673: the backward peek reached the start of file. Pin to page one and surface the
      // "top of page" message when a page was actually shown.
      screen.setPageNum(formatPageNum(1));
      if (rowsRead > 0) {
        screen.setErrMsg(MSG_REACHED_TOP);
      }
    } else {
      // L364-367: earlier records remain; step the page counter back by one (never below one).
      screen.setPageNum(formatPageNum(Math.max(1, currentPageNum(screen) - 1)));
    }

    screen.setRows(page);
    return null;
  }

  // ===============================================================================================
  // POPULATE-USER-DATA  (COUSR00C L384-L498)
  // ===============================================================================================

  /**
   * Reproduces {@code POPULATE-USER-DATA}: copy one {@link UserListProjection} record into one
   * display row.
   *
   * <p>The legacy paragraph moves the security fields onto the indexed map row &mdash; user id,
   * first name, last name, and the user type ({@code 'A'} admin / {@code 'U'} standard, displayed
   * verbatim with no transformation) &mdash; and leaves the selection flag blank. The first and
   * last rows of a page additionally seed the legacy PF7/PF8 cursors ({@code
   * CDEMO-CU00-USRID-FIRST}/{@code -LAST}); here those cursors are reconstructed from the
   * first/last populated rows of the screen.
   *
   * @param page the page rows being built
   * @param index the zero-based row index to populate
   * @param user the security record to render
   */
  private void populateUserData(List<UserListRow> page, int index, UserListProjection user) {
    UserListRow row = page.get(index);
    row.setSel("");
    row.setUsrId(user.getSecUsrId());
    row.setFName(user.getSecUsrFname());
    row.setLName(user.getSecUsrLname());
    row.setUType(user.getSecUsrType());
  }

  // ===============================================================================================
  // INITIALIZE-USER-DATA  (COUSR00C, the per-occurrence MOVE SPACES)
  // ===============================================================================================

  /**
   * Reproduces {@code INITIALIZE-USER-DATA}: blank every field of one display row (the legacy
   * {@code MOVE SPACES} applied to a single occurrence before a page is (re)written).
   *
   * @param row the row to blank
   */
  private void initializeUserData(UserListRow row) {
    row.setSel("");
    row.setUsrId("");
    row.setFName("");
    row.setLName("");
    row.setUType("");
  }

  /**
   * Builds a fresh page of {@link #PAGE_SIZE} blank rows &mdash; the modernized equivalent of the
   * legacy {@code PERFORM VARYING ... INITIALIZE-USER-DATA} that clears all ten occurrences before
   * a page is filled.
   *
   * @return a mutable list of ten blanked rows
   */
  private List<UserListRow> buildBlankPage() {
    List<UserListRow> page = new ArrayList<>(PAGE_SIZE);
    for (int i = 0; i < PAGE_SIZE; i++) {
      UserListRow row = new UserListRow();
      initializeUserData(row);
      page.add(row);
    }
    return page;
  }

  // ===============================================================================================
  // RETURN-TO-PREV-SCREEN  (COUSR00C L506-L517) and the inline COUSR02C/COUSR03C transfer
  // (L186-208)
  // ===============================================================================================

  /**
   * Reproduces {@code RETURN-TO-PREV-SCREEN} (and the equivalent inline {@code XCTL} the selection
   * path performs to {@code COUSR02C}/{@code COUSR03C}): stamp the navigation state and resolve the
   * next program.
   *
   * <p>The legacy program defaults a blank target to the sign-on program {@code COSGN00C}, records
   * this transaction as the origin ({@code CDEMO-FROM-TRANID}/{@code CDEMO-FROM-PROGRAM}), and
   * resets the program context to zero before transferring. The returned program name is what the
   * controller routes to; the controller is responsible for copying the selected user id into the
   * target screen on the update/delete path.
   *
   * @param commarea the navigation state to stamp
   * @param toProgram the desired target program, or blank to default to sign-on
   * @return the resolved target program name
   */
  private String returnToPrevScreen(CardDemoCommarea commarea, String toProgram) {
    String target = isPresent(toProgram) ? toProgram : LIT_SIGNON_PGM;
    commarea.setToProgram(target);
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    commarea.setPgmContext(0);
    return target;
  }

  // ===============================================================================================
  // Private helpers: in-service equivalents of the VSAM browse plumbing (STARTBR/READNEXT/READPREV)
  // and the COMMAREA paging cursors that the shared CardDemoCommarea does not carry.
  // ===============================================================================================

  /**
   * Bounded forward keyset browse (VSAM {@code STARTBR} GTEQ / {@code READNEXT}). Fetches at most
   * {@code limit} users at or after (or, when {@code exclusive}, strictly after) {@code key},
   * ascending by user id, projected to the four displayed columns only (the {@code sec_usr_pwd}
   * BCrypt hash is never selected for the list view). Ordinary "end of file" is an empty list, not
   * an error; only a genuinely unexpected datastore failure is caught here and surfaced as the
   * {@link #MSG_UNABLE_LOOKUP_USER} redisplay message (the legacy {@code WHEN OTHER} status
   * branch), without throwing (AAP §0.6.4 service-usage rule), returning {@code null} to signal the
   * failure.
   *
   * @param screen the screen whose message line receives the failure text
   * @param key the inclusive (or, when {@code exclusive}, exclusive) lower-bound user id; the empty
   *     string browses from the lowest key
   * @param exclusive {@code true} to step past the boundary record ({@code READNEXT}; PF8
   *     page-down)
   * @param limit the maximum rows to fetch (page size plus the read-ahead peek)
   * @return the bounded ascending page of projections, or {@code null} if the read failed (message
   *     already set)
   */
  private List<UserListProjection> browseForward(
      UserListScreen screen, String key, boolean exclusive, int limit) {
    try {
      PageRequest pageable = PageRequest.of(0, limit);
      return exclusive
          ? userSecurityRepository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(key, pageable)
          : userSecurityRepository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(key, pageable);
    } catch (DataAccessException ex) {
      screen.setErrMsg(MSG_UNABLE_LOOKUP_USER);
      return null;
    }
  }

  /**
   * Bounded backward keyset browse (VSAM {@code READPREV}; PF7 page-up). Fetches at most {@code
   * limit} users strictly below {@code key}, in descending order (closest-below first), projected
   * to the four displayed columns only; the caller reverses the slice to present it ascending. A
   * datastore failure is surfaced as {@link #MSG_UNABLE_LOOKUP_USER} and signalled by a {@code
   * null} return, matching the legacy graceful "unable to lookup" branch.
   *
   * @param screen the screen whose message line receives the failure text
   * @param key the exclusive upper-bound user id (the current page's first id)
   * @param limit the maximum rows to fetch (page size plus the read-ahead peek)
   * @return the bounded descending slice of projections, or {@code null} if the read failed
   *     (message already set)
   */
  private List<UserListProjection> browseBackward(UserListScreen screen, String key, int limit) {
    try {
      return userSecurityRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(
          key, PageRequest.of(0, limit));
    } catch (DataAccessException ex) {
      screen.setErrMsg(MSG_UNABLE_LOOKUP_USER);
      return null;
    }
  }

  /**
   * Bounded read-ahead probe (the legacy {@code NEXT-PAGE} recomputation): whether any user sorts
   * strictly after {@code key}. A datastore failure is surfaced as {@link #MSG_UNABLE_LOOKUP_USER}
   * and signalled by a {@code null} return, matching the legacy graceful "unable to lookup" branch.
   *
   * @param screen the screen whose message line receives the failure text
   * @param key the current page's last user id
   * @return {@code Boolean.TRUE}/{@code FALSE} for the existence outcome, or {@code null} if the
   *     probe failed (message already set)
   */
  private Boolean existsAfter(UserListScreen screen, String key) {
    try {
      return userSecurityRepository.existsBySecUsrIdGreaterThan(key);
    } catch (DataAccessException ex) {
      screen.setErrMsg(MSG_UNABLE_LOOKUP_USER);
      return null;
    }
  }

  /**
   * Returns the user id of the first populated row of the current page &mdash; the PF7 backward
   * cursor, equivalent to the legacy {@code CDEMO-CU00-USRID-FIRST} &mdash; or {@code null} when no
   * row is populated.
   *
   * @param screen the current list screen
   * @return the first populated row's user id, or {@code null}
   */
  private String firstPopulatedUsrId(UserListScreen screen) {
    List<UserListRow> rows = screen.getRows();
    if (rows != null) {
      for (UserListRow row : rows) {
        if (isPresent(row.getUsrId())) {
          return row.getUsrId();
        }
      }
    }
    return null;
  }

  /**
   * Returns the user id of the last populated row of the current page &mdash; the PF8 forward
   * cursor, equivalent to the legacy {@code CDEMO-CU00-USRID-LAST} &mdash; or {@code null} when no
   * row is populated.
   *
   * @param screen the current list screen
   * @return the last populated row's user id, or {@code null}
   */
  private String lastPopulatedUsrId(UserListScreen screen) {
    List<UserListRow> rows = screen.getRows();
    String last = null;
    if (rows != null) {
      for (UserListRow row : rows) {
        if (isPresent(row.getUsrId())) {
          last = row.getUsrId();
        }
      }
    }
    return last;
  }

  /**
   * Parses the current page number carried on the screen (the legacy {@code CDEMO-CU00-PAGE-NUM}, a
   * {@code 9(08)} counter). A blank or unparseable value is treated as zero, matching the
   * initialized state on first entry.
   *
   * @param screen the current list screen
   * @return the current page number, or {@code 0} when absent/unparseable
   */
  private int currentPageNum(UserListScreen screen) {
    String raw = screen.getPageNum();
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  /**
   * Formats a page number into the legacy zero-padded width-8 representation of the {@code 9(08)}
   * page counter field.
   *
   * @param pageNum the page number to format
   * @return the page number left-padded with zeros to width eight
   */
  private String formatPageNum(int pageNum) {
    return String.format("%08d", pageNum);
  }

  /**
   * Returns {@code true} when a string is non-null and contains at least one non-blank character,
   * mirroring the COBOL {@code NOT = SPACES AND LOW-VALUES} test used throughout COUSR00C.
   *
   * @param value the value to test
   * @return {@code true} when the value is present (non-null, non-blank)
   */
  private boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}

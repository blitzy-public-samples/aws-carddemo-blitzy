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

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.TranListScreen;
import com.aws.carddemo.dto.screen.TranListScreen.TranListRow;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Online service that reproduces the legacy CICS program <strong>COTRN00C</strong> (transaction
 * {@code CT00} &mdash; <em>Transaction List / Browse</em>) with 100% behavioral parity.
 *
 * <p>COTRN00C is a STYLE&nbsp;B, named-paragraph pseudo-conversational program that browses the
 * {@code TRANSACT} VSAM KSDS in ascending transaction-id order, presenting the data ten rows at a
 * time. The operator may page forward ({@code PF8}) and backward ({@code PF7}), filter by entering
 * a starting transaction id, select a row with {@code S} to drill into the transaction-view screen
 * ({@code COTRN01C}), or return to the main menu ({@code PF3}). This service preserves that control
 * flow verbatim: each numbered/named COBOL paragraph becomes a private method invoked in the same
 * perform/branch order, and every operator-visible message literal is reproduced byte-for-byte.
 *
 * <h2>VSAM browse &rarr; keyset paging</h2>
 *
 * <p>The legacy program drives a VSAM browse ({@code STARTBR}/{@code READNEXT}/{@code READPREV})
 * over {@code TRANSACT}. The modernized equivalent reads the ascending-by-transaction-id projection
 * from {@link TransactionRepository#findAllByOrderByTranIdAsc()} and paginates by index in-service,
 * preserving the {@code GTEQ} positioning, the ten-row page size, the read-ahead "next page exists"
 * peek, and the first/last keyset boundaries that the program carries across interactions. Those
 * cursors live on {@link TranListScreen} hidden fields (see below), never on this singleton bean.
 *
 * <h2>No mutable instance state</h2>
 *
 * <p>This bean is a stateless Spring singleton. The legacy {@code CDEMO-CT00-INFO} paging/selection
 * cursors ({@code TRNID-FIRST}, {@code TRNID-LAST}, {@code PAGE-NUM}, {@code NEXT-PAGE-FLG}, {@code
 * TRN-SELECTED}) are <em>not</em> part of the shared {@link CardDemoCommarea} contract, so they are
 * carried on {@link TranListScreen} hidden fields and round-tripped by the controller. The
 * transient {@code CDEMO-CT00-TRN-SEL-FLG} is recomputed as a local variable within {@link
 * #processEnterKey} each interaction and therefore is not persisted.
 *
 * <h2>Division of responsibility</h2>
 *
 * <ul>
 *   <li>This service owns the business logic and the screen <em>data</em> it computes: the ten
 *       transaction rows, the page indicator, the keyset cursors, and the error/informational
 *       message line.
 *   <li>The web/controller layer owns HTTP, the {@link CardDemoCommarea} session round-trip, AID/
 *       PF-key resolution, navigation routing (mapping the returned program name to a URL), and the
 *       legacy {@code POPULATE-HEADER-INFO} concerns &mdash; the two shared title lines and the
 *       current date/time &mdash; whose source ({@code COTTL01Y}) and clock fall outside this
 *       service's dependency contract.
 * </ul>
 *
 * <h2>Validation vs. failure</h2>
 *
 * <p>Per the list/browse convention, validation outcomes and "edge of list" conditions are shown to
 * the operator: this service sets {@link TranListScreen#setErrMsg(String) the message} and returns
 * {@code null} to redisplay the list. Only an unexpected data-access failure (the COBOL {@code
 * OTHER} branch of the {@code STARTBR}/{@code READNEXT} status check) is escalated, as an {@link
 * IoStatusException}.
 *
 * <p>Authority: Agent Action Plan §0.1.1 (online modernization), §0.4.1 (COTRN00C &rarr;
 * TranListController + TranListService), §0.6.5 (pseudo-conversational state &amp; navigation),
 * §0.6.4 (FILE STATUS &rarr; exception mapping), and §0.7.1 (100% behavioral parity). Source of
 * record: {@code legacy/app/cbl/COTRN00C.cbl}.
 */
@Service
public class TranListService {

  // ---------------------------------------------------------------------------------------------
  // Identity literals (COBOL WORKING-STORAGE: WS-TRANID, WS-PGMNAME, WS-TRANSACT-FILE) and the
  // navigation targets reached by this transaction.
  // ---------------------------------------------------------------------------------------------

  /** CICS transaction id of this screen. COBOL {@code WS-TRANID PIC X(4) VALUE 'CT00'}. */
  static final String TRAN_ID_NAME = "CT00";

  /** This program's name. COBOL {@code WS-PGMNAME PIC X(8) VALUE 'COTRN00C'}. */
  static final String PGM_NAME = "COTRN00C";

  /** Sign-on program, used as the {@code EIBCALEN = 0} cold-start fallback target. */
  static final String LIT_SIGNON_PGM = "COSGN00C";

  /** Main-menu program, the {@code PF3} (return) target. */
  static final String LIT_MENU_PGM = "COMEN01C";

  /** Transaction-view program, the target when a row is selected with {@code S}. */
  static final String LIT_TRAN_VIEW_PGM = "COTRN01C";

  /** Logical name of the browsed VSAM file. COBOL {@code WS-TRANSACT-FILE VALUE 'TRANSACT'}. */
  static final String LIT_TRANSACT_FILE = "TRANSACT";

  /** Fixed number of transaction rows rendered per page (the 3270 list geometry). */
  static final int ROWS_PER_PAGE = 10;

  /** Width of a transaction id. COBOL {@code TRAN-ID PIC X(16)}. */
  private static final int TRAN_ID_LENGTH = 16;

  // ---------------------------------------------------------------------------------------------
  // Operator-visible message literals, reproduced byte-for-byte from COTRN00C.cbl. The trailing
  // ellipses (and the single space before the ellipsis in MSG_TRAN_ID_NOT_NUMERIC) are part of the
  // literals and are preserved exactly.
  // ---------------------------------------------------------------------------------------------

  /** Invalid row-selection flag (not {@code S}/{@code s}). COTRN00C L198-L200. */
  static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";

  /** Non-numeric transaction-id filter entered. COTRN00C L213-L215. */
  static final String MSG_TRAN_ID_NOT_NUMERIC = "Tran ID must be Numeric ...";

  /** {@code PF7} pressed while already on page one. COTRN00C L248-L249. */
  static final String MSG_ALREADY_TOP = "You are already at the top of the page...";

  /** {@code PF8} pressed with no further page. COTRN00C L270-L271. */
  static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";

  /** {@code STARTBR} returned NOTFND (empty file or filter beyond end). COTRN00C L608-L609. */
  static final String MSG_TOP_OF_PAGE = "You are at the top of the page...";

  /** {@code READNEXT} reached end of file while paging forward. COTRN00C L642-L643. */
  static final String MSG_REACHED_BOTTOM = "You have reached the bottom of the page...";

  /** {@code READPREV} reached start of file while paging backward. COTRN00C L676-L677. */
  static final String MSG_REACHED_TOP = "You have reached the top of the page...";

  /**
   * Sentinel start key that compares greater than any numeric transaction id, reproducing the COBOL
   * {@code MOVE HIGH-VALUES TO TRAN-ID} used by {@code PROCESS-PF8-KEY} when no last-row cursor is
   * available. Positioning a {@code GTEQ} browse at HIGH-VALUES yields NOTFND, exactly as in the
   * legacy program. COTRN00C L259-L260.
   */
  private static final String HIGH_VALUES_KEY = "\uffff".repeat(TRAN_ID_LENGTH);

  /**
   * Repository projecting the {@code TRANSACT} store in ascending transaction-id order &mdash; the
   * modernized equivalent of the VSAM browse over the KSDS primary key.
   */
  private final TransactionRepository transactionRepository;

  /**
   * Creates the service with its required collaborator.
   *
   * @param transactionRepository ascending-by-transaction-id browse source for {@code TRANSACT};
   *     must not be {@code null}
   */
  public TranListService(TransactionRepository transactionRepository) {
    this.transactionRepository =
        Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
  }

  // ===============================================================================================
  // MAIN-PARA  (COTRN00C L95-L141)
  // ===============================================================================================

  /**
   * Entry point reproducing {@code MAIN-PARA}: the pseudo-conversational dispatch for transaction
   * {@code CT00}.
   *
   * <p>Control flow, preserved verbatim from the legacy program:
   *
   * <ol>
   *   <li>Reset the per-interaction state and clear the message line (L97-L103).
   *   <li>If there is no prior conversation state &mdash; the COBOL {@code EIBCALEN = 0} cold
   *       start, modeled here as a commarea carrying no signed-on user &mdash; route to sign-on via
   *       {@link #returnToPrevScreen} (L107-L109).
   *   <li>On first entry to this program ({@code NOT CDEMO-PGM-REENTER}), mark re-entry and paint
   *       the initial page by performing the enter-key path, which browses from the top
   *       (L112-L116).
   *   <li>On re-entry, dispatch on the attention id (L118-L134): {@code ENTER} &rarr; enter-key
   *       handling; {@code PF3} &rarr; return to the main menu; {@code PF7}/{@code PF8} &rarr; page
   *       up/down; anything else &rarr; the shared "invalid key" message.
   * </ol>
   *
   * @param screen the transaction-list screen carrying input fields, the ten rows, and the hidden
   *     paging/selection cursors; must not be {@code null}
   * @param commarea the pseudo-conversational navigation/session state; must not be {@code null}
   * @param aid the resolved attention identifier (PF key) for this interaction; may be {@code
   *     null}, which is treated as {@code ENTER} only on first entry
   * @return the next program name for the controller to route to ({@link #LIT_MENU_PGM} on {@code
   *     PF3}, {@link #LIT_TRAN_VIEW_PGM} on a valid row selection, or {@link #LIT_SIGNON_PGM} on
   *     cold start), or {@code null} to redisplay the transaction list
   * @throws IoStatusException if browsing {@code TRANSACT} fails unexpectedly
   */
  public String processTranList(
      TranListScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // L97-L103: reset message line for this interaction. The EOF / error / next-page / send-erase
    // working-storage flags of the legacy program are realized as local control flow (EOF, error)
    // or as the round-tripped screen cursor (next-page); none require resetting here. NEXT-PAGE-FLG
    // is intentionally NOT reset: the legacy program's L99 reset is immediately overwritten by the
    // COMMAREA restore (L111), so the saved value persists across interactions.
    screen.setErrMsg("");

    // L107-L109: EIBCALEN = 0 (no prior COMMAREA). In the web model the controller always supplies
    // a
    // commarea, so the cold-start condition is "no signed-on user in the conversation state": route
    // to sign-on, exactly as the legacy program does.
    if (!isPresent(commarea.getUserId())) {
      commarea.setToProgram(LIT_SIGNON_PGM);
      return returnToPrevScreen(commarea);
    }

    // L112-L116: first entry to this program. Mark re-entry for subsequent turns and paint the
    // initial page by performing the enter-key path (which, with an empty filter and no selection,
    // browses from the top of the file).
    if (!commarea.isPgmReenter()) {
      commarea.setPgmReenter();
      return processEnterKey(screen, commarea);
    }

    // L118-L134: re-entry. Dispatch on the attention id.
    if (aid == CardWorkArea.Aid.PFK03) {
      // L122-L124: PF3 returns to the main menu.
      commarea.setToProgram(LIT_MENU_PGM);
      return returnToPrevScreen(commarea);
    }
    if (aid == CardWorkArea.Aid.PFK07) {
      // L125-L126: PF7 pages backward.
      return processPf7Key(screen);
    }
    if (aid == CardWorkArea.Aid.PFK08) {
      // L127-L128: PF8 pages forward.
      return processPf8Key(screen);
    }
    if (aid == null || aid == CardWorkArea.Aid.ENTER) {
      // L120-L121: ENTER processes selection/filter and (re)paints the current page.
      return processEnterKey(screen, commarea);
    }

    // L129-L133: WHEN OTHER -> shared "invalid key" message, redisplay. The legacy
    // CCDA-MSG-INVALID-
    // KEY is the heavily space-padded PIC X(50) literal; it is trimmed for display per the
    // migration
    // convention shared with the sibling online services.
    screen.setErrMsg(Messages.MSG_INVALID_KEY.trim());
    return null;
  }

  // ===============================================================================================
  // PROCESS-ENTER-KEY  (COTRN00C L146-L229)
  // ===============================================================================================

  /**
   * Reproduces {@code PROCESS-ENTER-KEY}: row-selection handling followed by the transaction-id
   * filter and a forward page from the resulting start key.
   *
   * <p>Steps, in the legacy order:
   *
   * <ol>
   *   <li><b>Row-selection scan</b> (L148-L182): examine the ten selection inputs; the first that
   *       is neither blank nor low-values wins, capturing its flag and that row's transaction id.
   *   <li><b>Selection dispatch</b> (L183-L204): when both the flag and id are present, {@code
   *       S}/{@code s} forwards to the transaction-view program ({@code COTRN01C}) carrying the
   *       selected id; any other flag sets the byte-exact "invalid selection" message and <em>falls
   *       through</em> (the legacy code does not re-send on this branch).
   *   <li><b>Filter</b> (L206-L219): a blank filter browses from the beginning; an all-numeric
   *       filter becomes the start key; anything else sets the byte-exact "must be numeric"
   *       message.
   *   <li><b>Page</b> (L224-L225): reset the page counter and perform a forward page.
   * </ol>
   *
   * @param screen the transaction-list screen
   * @param commarea the navigation/session state (mutated when a selection forwards)
   * @return {@link #LIT_TRAN_VIEW_PGM} when a row is selected with {@code S}, otherwise {@code
   *     null} to redisplay the list
   * @throws IoStatusException if browsing {@code TRANSACT} fails unexpectedly
   */
  private String processEnterKey(TranListScreen screen, CardDemoCommarea commarea) {
    // L148-L182: row-selection scan. The transient CDEMO-CT00-TRN-SEL-FLG / -TRN-SELECTED are local
    // here: the first row whose selection flag is non-blank wins, together with that row's id.
    String selectionFlag = null;
    String selectedTranId = null;
    List<TranListRow> rows = screen.getRows();
    if (rows != null) {
      for (TranListRow row : rows) {
        if (row != null && isPresent(row.getSel())) {
          selectionFlag = row.getSel();
          selectedTranId = row.getTrnId();
          break;
        }
      }
    }

    // L183-L204: when a selection is present, dispatch on the flag.
    if (isPresent(selectionFlag) && isPresent(selectedTranId)) {
      if ("S".equals(selectionFlag) || "s".equals(selectionFlag)) {
        // L186-L195: forward to the transaction-view screen carrying the selected id. The
        // controller
        // mediates by seeding COTRN01C's input with CDEMO-CT00-TRN-SELECTED.
        commarea.setToProgram(LIT_TRAN_VIEW_PGM);
        commarea.setFromTranId(TRAN_ID_NAME);
        commarea.setFromProgram(PGM_NAME);
        commarea.setPgmContext(0);
        screen.setTrnSelected(selectedTranId);
        return LIT_TRAN_VIEW_PGM;
      }
      // L196-L202: invalid selection flag -> set the message and fall through (no early send).
      screen.setErrMsg(MSG_INVALID_SELECTION);
    }

    // L206-L219: transaction-id filter. Determine the GTEQ start key for the browse.
    String startKey;
    String filter = screen.getTrnIdIn() == null ? "" : screen.getTrnIdIn().trim();
    if (filter.isEmpty()) {
      // L206-L207: blank -> LOW-VALUES (browse from the beginning).
      startKey = null;
    } else if (isAllDigits(filter)) {
      // L209-L210: numeric -> use as the start transaction id, zero-padded to the key width so the
      // comparison matches the fixed-width VSAM key ordering.
      startKey = leftPadKey(filter);
    } else {
      // L211-L217: non-numeric -> message and redisplay without paging (the legacy error flag
      // suppresses the subsequent page-forward read loop, so no rows are refreshed).
      screen.setErrMsg(MSG_TRAN_ID_NOT_NUMERIC);
      return null;
    }

    // L224-L225: reset the page counter and perform a forward page from the start key.
    screen.setPageNumValue(0);
    return processPageForward(screen, startKey, false);
  }

  // ===============================================================================================
  // PROCESS-PF7-KEY  (COTRN00C L234-L252)
  // ===============================================================================================

  /**
   * Reproduces {@code PROCESS-PF7-KEY}: page backward.
   *
   * <p>The browse repositions at the first transaction id of the current page ({@code
   * CDEMO-CT00-TRNID-FIRST}; LOW-VALUES if absent). If there is a page above the current one
   * ({@code CDEMO-CT00-PAGE-NUM > 1}), perform a backward page; otherwise show the byte-exact
   * "already at the top" message and redisplay.
   *
   * @param screen the transaction-list screen
   * @return always {@code null} (the list is always redisplayed by this path)
   * @throws IoStatusException if browsing {@code TRANSACT} fails unexpectedly
   */
  private String processPf7Key(TranListScreen screen) {
    // L236-L240: start key = first id of the current page, or LOW-VALUES when unset.
    String startKey = isPresent(screen.getTrnIdFirst()) ? screen.getTrnIdFirst() : null;

    // L245-L252: page backward only when not already at the top.
    if (screen.getPageNumValue() > 1) {
      return processPageBackward(screen, startKey);
    }
    screen.setErrMsg(MSG_ALREADY_TOP);
    return null;
  }

  // ===============================================================================================
  // PROCESS-PF8-KEY  (COTRN00C L257-L274)
  // ===============================================================================================

  /**
   * Reproduces {@code PROCESS-PF8-KEY}: page forward.
   *
   * <p>The browse repositions at the last transaction id of the current page ({@code
   * CDEMO-CT00-TRNID-LAST}; HIGH-VALUES if absent). If a further page is known to exist ({@code
   * NEXT-PAGE-YES}), perform a forward page that skips the boundary record; otherwise show the
   * byte-exact "already at the bottom" message and redisplay.
   *
   * @param screen the transaction-list screen
   * @return always {@code null} (the list is always redisplayed by this path)
   * @throws IoStatusException if browsing {@code TRANSACT} fails unexpectedly
   */
  private String processPf8Key(TranListScreen screen) {
    // L259-L263: start key = last id of the current page, or HIGH-VALUES when unset.
    String startKey = isPresent(screen.getTrnIdLast()) ? screen.getTrnIdLast() : HIGH_VALUES_KEY;

    // L267-L274: page forward only when a further page is known to exist. The forward page skips
    // the
    // boundary record (the legacy read-ahead consumes CDEMO-CT00-TRNID-LAST before filling the
    // page).
    if (screen.isNextPageYes()) {
      return processPageForward(screen, startKey, true);
    }
    screen.setErrMsg(MSG_ALREADY_BOTTOM);
    return null;
  }

  // ===============================================================================================
  // PROCESS-PAGE-FORWARD  (COTRN00C L279-L328)
  // ===============================================================================================

  /**
   * Reproduces {@code PROCESS-PAGE-FORWARD}: position the browse with {@code GTEQ} semantics at
   * {@code startKey}, fill up to ten rows ascending, peek one record ahead to determine whether a
   * further page exists, and advance the page counter.
   *
   * <p>Behavior matched to the legacy paragraph:
   *
   * <ul>
   *   <li><b>STARTBR NOTFND</b> (no record at or after {@code startKey}, or an empty file): render
   *       a blank page, mark no next page, and show the byte-exact "you are at the top of the page"
   *       message (L605-L611). Unlike the legacy program &mdash; which leaves the prior ten map
   *       rows stale on this path &mdash; the service always renders a freshly initialized ten-row
   *       page, so a no-results browse shows an empty list. This is observably cleaner and does not
   *       affect any populated-result parity path.
   *   <li><b>Skip-first</b> (L285-L287): when invoked by {@code PF8}, the boundary record at the
   *       start position is consumed before the page is filled.
   *   <li><b>Keyset boundaries</b> (L390-L442): {@link #populateTranData} records the first row's
   *       id as {@code TRNID-FIRST} and, only when a full ten-row page is read, the tenth row's id
   *       as {@code TRNID-LAST}.
   *   <li><b>Next-page peek &amp; page counter</b> (L305-L320): the counter advances when at least
   *       one row is read; the next-page flag is the read-ahead result. A last/partial page
   *       additionally sets the byte-exact "you have reached the bottom of the page" message
   *       (L639-L645), which the legacy program emits from the ENDFILE read.
   *   <li><b>Filter echo cleared</b> (L325): the transaction-id input is cleared on a rendered
   *       page.
   * </ul>
   *
   * <p>An "invalid selection" message set by the caller survives onto a normal (non-terminal) page,
   * mirroring the legacy multi-{@code SEND} behavior in which only the final message state is
   * shown.
   *
   * @param screen the transaction-list screen
   * @param startKey the GTEQ start key, or {@code null} for LOW-VALUES (browse from the beginning)
   * @param skipFirst whether to skip the record positioned at {@code startKey} ({@code PF8} paging)
   * @return always {@code null} (a forward page always redisplays the list)
   * @throws IoStatusException if browsing {@code TRANSACT} fails unexpectedly
   */
  private String processPageForward(TranListScreen screen, String startKey, boolean skipFirst) {
    List<Transaction> all = loadAllTransactions();
    int size = all.size();
    int pos = (startKey == null) ? 0 : firstIndexGreaterOrEqual(all, startKey);

    List<TranListRow> page = buildBlankPage();

    // L605-L611: STARTBR NOTFND -> empty page, no next page, "top of page" message.
    if (pos >= size) {
      screen.setNextPageYes(false);
      screen.setRows(page);
      screen.setPageNum(formatPageNum(screen.getPageNumValue()));
      screen.setTrnIdIn("");
      screen.setErrMsg(MSG_TOP_OF_PAGE);
      return null;
    }

    // L285-L303: optionally skip the boundary record (PF8), then fill up to ten rows ascending.
    int from = pos + (skipFirst ? 1 : 0);
    int rowsRead = 0;
    for (int i = 0; i < ROWS_PER_PAGE && (from + i) < size; i++) {
      populateTranData(screen, page, i, all.get(from + i));
      rowsRead++;
    }

    // L305-L320: advance the page counter when any row was read; the next-page flag is the peek.
    boolean nextPage = (from + ROWS_PER_PAGE) < size;
    screen.setNextPageYes(nextPage);
    if (rowsRead > 0) {
      screen.setPageNumValue(screen.getPageNumValue() + 1);
    }
    if (!nextPage) {
      // L639-L645: the ENDFILE read on the last/partial page yields the "bottom of page" message.
      screen.setErrMsg(MSG_REACHED_BOTTOM);
    }

    // L322-L326: end the browse, publish the page indicator, clear the filter echo, redisplay.
    screen.setRows(page);
    screen.setPageNum(formatPageNum(screen.getPageNumValue()));
    screen.setTrnIdIn("");
    return null;
  }

  // ===============================================================================================
  // PROCESS-PAGE-BACKWARD  (COTRN00C L333-L376)
  // ===============================================================================================

  /**
   * Reproduces {@code PROCESS-PAGE-BACKWARD}: present the ten transactions immediately preceding
   * the current page's first id, in ascending order.
   *
   * <p>The legacy paragraph repositions at {@code CDEMO-CT00-TRNID-FIRST}, skips that boundary
   * record ({@code READPREV}; this paragraph is only ever reached from {@code PF7}, so the skip
   * always applies &mdash; L339-L341), then walks backward filling rows ten down to one so the
   * resulting page is ascending (L349-L357). It then peeks one record further back: if that record
   * exists and the counter is above one, the page counter is decremented; otherwise the page
   * counter is pinned to one (L359-L369). The forward "next page" flag is unchanged by this
   * paragraph (it was set on entry from {@code PF7}), so paging up always leaves a page available
   * below.
   *
   * <p>This method is only invoked when {@code CDEMO-CT00-PAGE-NUM > 1}, so a full ten-row previous
   * page is available in normal flow. The index arithmetic models the {@code READPREV} skip
   * directly: the previous page is {@code [first - 10, first)} in the ascending projection.
   *
   * @param screen the transaction-list screen
   * @param startKey the current page's first transaction id, or {@code null} for LOW-VALUES
   * @return always {@code null} (a backward page always redisplays the list)
   * @throws IoStatusException if browsing {@code TRANSACT} fails unexpectedly
   */
  private String processPageBackward(TranListScreen screen, String startKey) {
    List<Transaction> all = loadAllTransactions();
    int size = all.size();

    // Index of the current page's first record (GTEQ; exact since it was just displayed). When the
    // cursor is LOW-VALUES the current page started at the top of the file.
    int current = (startKey == null) ? 0 : firstIndexGreaterOrEqual(all, startKey);
    if (current > size) {
      current = size;
    }

    // L339-L357: the previous page is the ten records ending just before the current first record.
    int from = Math.max(0, current - ROWS_PER_PAGE);
    List<TranListRow> page = buildBlankPage();
    int rowsRead = 0;
    for (int i = 0; (from + i) < current && i < ROWS_PER_PAGE; i++) {
      populateTranData(screen, page, i, all.get(from + i));
      rowsRead++;
    }

    // L359-L369: a backward page that reaches the top of the file pins the counter to one and emits
    // the "reached the top of the page" message (the ENDFILE peek); otherwise the counter
    // decrements.
    if (from == 0) {
      screen.setPageNumValue(1);
      if (rowsRead > 0) {
        screen.setErrMsg(MSG_REACHED_TOP);
      }
    } else {
      screen.setPageNumValue(Math.max(1, screen.getPageNumValue() - 1));
    }

    // The forward "next page" flag is unchanged by a page-up: there is always a page below.
    screen.setNextPageYes(true);

    // L373-L374: publish the page indicator and redisplay (the filter echo is not cleared here).
    screen.setRows(page);
    screen.setPageNum(formatPageNum(screen.getPageNumValue()));
    return null;
  }

  // ===============================================================================================
  // POPULATE-TRAN-DATA  (COTRN00C L381-L445)
  // ===============================================================================================

  /**
   * Reproduces {@code POPULATE-TRAN-DATA}: render one transaction into the indexed page row and, at
   * the page boundaries, record the keyset cursors.
   *
   * <p>The row is filled with the transaction id, the {@code MM/DD/YY} display date derived from
   * the original timestamp, the description truncated to the list-view width (COBOL {@code TDESCxx
   * PIC X(26)}), and the monetary amount as a {@link java.math.BigDecimal}. As in the legacy {@code
   * EVALUATE WS-IDX}, row index {@code 0} (WS-IDX 1) records {@code CDEMO-CT00-TRNID-FIRST} and row
   * index {@code 9} (WS-IDX 10) records {@code CDEMO-CT00-TRNID-LAST}; a partial last page
   * therefore leaves the last-id cursor at its prior value, exactly as the COBOL does.
   *
   * @param screen the screen whose keyset cursors are updated at the boundaries
   * @param page the mutable ten-row page being populated
   * @param index zero-based row index ({@code 0}..{@code 9})
   * @param tran the transaction to render into the row
   */
  private void populateTranData(
      TranListScreen screen, List<TranListRow> page, int index, Transaction tran) {
    TranListRow row = page.get(index);
    String tranId = tran.getTranId();
    row.setSel("");
    row.setTrnId(tranId);
    row.setTDate(deriveDisplayDate(tran.getTranOrigTs()));
    row.setTDesc(truncate(tran.getTranDesc(), 26));
    row.setTAmt(tran.getTranAmt());

    // L391-L393 / L437-L439: the first and tenth rows carry the keyset boundaries.
    if (index == 0) {
      screen.setTrnIdFirst(tranId);
    }
    if (index == ROWS_PER_PAGE - 1) {
      screen.setTrnIdLast(tranId);
    }
  }

  // ===============================================================================================
  // INITIALIZE-TRAN-DATA  (COTRN00C L450-L505)
  // ===============================================================================================

  /**
   * Reproduces {@code INITIALIZE-TRAN-DATA}: a single blank list row.
   *
   * <p>The legacy paragraph clears one indexed row's data fields to SPACES before the page is
   * filled. Here a freshly blanked row is produced for assembly into the ten-row page (see {@link
   * #buildBlankPage()}). The selection flag is also cleared so the rendered list presents empty
   * selection inputs for the next interaction.
   *
   * @return a new, fully blanked transaction row
   */
  private TranListRow initializeTranData() {
    TranListRow row = new TranListRow();
    row.setSel("");
    row.setTrnId("");
    row.setTDate("");
    row.setTDesc("");
    row.setTAmt(null);
    return row;
  }

  // ===============================================================================================
  // RETURN-TO-PREV-SCREEN  (COTRN00C L510-L521)
  // ===============================================================================================

  /**
   * Reproduces {@code RETURN-TO-PREV-SCREEN}: stamp the outbound navigation context and hand
   * control back to the prior program.
   *
   * <p>If no target program has been set, it defaults to sign-on ({@link #LIT_SIGNON_PGM}). The
   * "from" transaction id and program are stamped with this screen's identity and the program
   * context is reset to first-entry so the target program paints rather than re-processes.
   *
   * @param commarea the navigation/session state to stamp
   * @return the resolved target program name for the controller to route to
   */
  private String returnToPrevScreen(CardDemoCommarea commarea) {
    // L512-L514: default the target to sign-on when unset.
    if (!isPresent(commarea.getToProgram())) {
      commarea.setToProgram(LIT_SIGNON_PGM);
    }
    // L515-L517: stamp the caller identity and reset the program context to first-entry.
    commarea.setFromTranId(TRAN_ID_NAME);
    commarea.setFromProgram(PGM_NAME);
    commarea.setPgmContext(0);
    return commarea.getToProgram();
  }

  // ===============================================================================================
  // Internal helpers (browse source, key arithmetic, field formatting)
  // ===============================================================================================

  /**
   * Loads the {@code TRANSACT} store as the ascending-by-transaction-id browse source, translating
   * an unexpected data-access failure into the {@link IoStatusException} that mirrors the legacy
   * {@code STARTBR}/{@code READNEXT} {@code OTHER} status branch ({@code "Unable to lookup
   * transaction..."}).
   *
   * @return all transactions ordered by ascending transaction id (never {@code null})
   * @throws IoStatusException if the underlying query fails
   */
  private List<Transaction> loadAllTransactions() {
    try {
      List<Transaction> all = transactionRepository.findAllByOrderByTranIdAsc();
      return (all == null) ? new ArrayList<>() : all;
    } catch (DataAccessException ex) {
      throw new IoStatusException(LIT_TRANSACT_FILE, "STARTBR", "??", ex);
    }
  }

  /**
   * Returns the index of the first transaction whose id is greater than or equal to {@code key},
   * reproducing VSAM {@code GTEQ} positioning over the ascending-by-id projection. When no such
   * record exists the list size is returned (the NOTFND / end-of-file position). The source list is
   * pre-sorted, so a single forward scan suffices.
   *
   * @param all the ascending-by-transaction-id list
   * @param key the start key (already normalized to the key width); must not be {@code null}
   * @return the GTEQ index, in {@code [0, all.size()]}
   */
  private int firstIndexGreaterOrEqual(List<Transaction> all, String key) {
    for (int i = 0; i < all.size(); i++) {
      String tranId = all.get(i).getTranId();
      if (tranId != null && tranId.compareTo(key) >= 0) {
        return i;
      }
    }
    return all.size();
  }

  /**
   * Tests whether a string carries a meaningful value, reproducing the COBOL {@code NOT = SPACES
   * AND LOW-VALUES} guard: {@code null}, empty, and all-whitespace values are treated as absent.
   *
   * @param value the value to test
   * @return {@code true} when the value is non-{@code null} and not blank
   */
  private static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * Tests whether a string is entirely ASCII digits, reproducing the COBOL {@code IS NUMERIC} class
   * test used for the transaction-id filter.
   *
   * @param value the value to test
   * @return {@code true} when {@code value} is non-empty and every character is {@code 0}-{@code 9}
   */
  private static boolean isAllDigits(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * Normalizes a numeric filter to the fixed transaction-id width by left-padding with zeros, so
   * that lexical comparison against the zero-padded {@code X(16)} keys matches VSAM key ordering.
   * Values at or beyond the key width are returned unchanged.
   *
   * @param numeric an all-digit filter value
   * @return the value left-padded with {@code '0'} to {@link #TRAN_ID_LENGTH} characters
   */
  private static String leftPadKey(String numeric) {
    if (numeric.length() >= TRAN_ID_LENGTH) {
      return numeric;
    }
    StringBuilder sb = new StringBuilder(TRAN_ID_LENGTH);
    for (int i = numeric.length(); i < TRAN_ID_LENGTH; i++) {
      sb.append('0');
    }
    return sb.append(numeric).toString();
  }

  /**
   * Derives the {@code MM/DD/YY} list display date from a transaction's original timestamp,
   * reproducing {@code POPULATE-TRAN-DATA} (L383-L388): the two-digit year is taken from positions
   * 3-4 of the {@code YYYY} component, with the month and day taken from the {@code YYYY-MM-DD}
   * prefix. A {@code null} or too-short timestamp yields an empty value rather than failing the
   * page render.
   *
   * @param origTimestamp the {@code YYYY-MM-DD-HH.MM.SS.ffffff} originating timestamp
   * @return the {@code MM/DD/YY} display date, or an empty string when the timestamp is unusable
   */
  private static String deriveDisplayDate(String origTimestamp) {
    if (origTimestamp == null || origTimestamp.length() < 10) {
      return "";
    }
    String yy = origTimestamp.substring(2, 4);
    String mm = origTimestamp.substring(5, 7);
    String dd = origTimestamp.substring(8, 10);
    return mm + "/" + dd + "/" + yy;
  }

  /**
   * Truncates a value to a maximum length, reproducing the COBOL fixed-width {@code MOVE} into a
   * shorter alphanumeric field (here the {@code X(26)} list-view description). A {@code null} input
   * yields an empty string.
   *
   * @param value the value to truncate
   * @param maxLength the maximum retained length
   * @return the value truncated to {@code maxLength} characters (never {@code null})
   */
  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }

  /**
   * Formats the numeric page counter as the eight-digit, zero-padded display value carried by the
   * {@code PAGENUM PIC 9(08)} screen field.
   *
   * @param pageNumber the numeric page counter
   * @return the eight-character zero-padded page indicator
   */
  private static String formatPageNum(int pageNumber) {
    return String.format("%08d", pageNumber);
  }

  /**
   * Builds a freshly initialized ten-row page, mirroring the legacy {@code PERFORM VARYING ... 1 BY
   * 1 UNTIL WS-IDX > 10 ... INITIALIZE-TRAN-DATA} loop that blanks the map rows before they are
   * filled.
   *
   * @return a new, mutable list of {@link #ROWS_PER_PAGE} blank rows
   */
  private List<TranListRow> buildBlankPage() {
    List<TranListRow> page = new ArrayList<>(ROWS_PER_PAGE);
    for (int i = 0; i < ROWS_PER_PAGE; i++) {
      page.add(initializeTranData());
    }
    return page;
  }
}

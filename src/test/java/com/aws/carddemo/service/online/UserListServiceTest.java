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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.UserSecurity;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link UserListService}, the online <em>List
 * Users</em> / browse service migrated from the legacy CICS COBOL program {@code COUSR00C} (CICS
 * transaction {@code CU00}, <strong>admin-only</strong>; behavioral spec {@code
 * legacy/app/cbl/COUSR00C.cbl}).
 *
 * <p>The service's only collaborator, {@link UserSecurityRepository}, is mocked so that the
 * ascending-by-user-id browse source is fully controlled and deterministic; no Spring context,
 * database, or Testcontainers is required. Control-flow parity (Agent Action Plan &sect;0.6.5,
 * &sect;0.7.1) is asserted three ways:
 *
 * <ul>
 *   <li><b>Externally observable effects</b> of {@link UserListService#processUserList(
 *       UserListScreen, CardDemoCommarea, CardWorkArea.Aid)}: its return value (the {@code XCTL}
 *       target program, or {@code null} to redisplay), the mutations it makes to the {@link
 *       CardDemoCommarea} navigation state and to the {@link UserListScreen} (the ten user rows,
 *       the page indicator, the filter echo, and the message line), and the byte-exact message
 *       literals it writes.
 *   <li><b>Interaction ordering</b> via Mockito {@link InOrder}: the single VSAM-browse-equivalent
 *       query ({@code findAll(Sort)} ascending by user id) is verified to occur exactly once on a
 *       page paint, and never on the short-circuit paths.
 *   <li><b>Interaction suppression</b> via {@link org.mockito.Mockito#verifyNoInteractions}: the
 *       paths the legacy program resolves <em>before</em> the browse (the admin-only gate, a row
 *       selection forward, a {@code PF3} return, the {@code PF7} top-of-page edge, and an unmapped
 *       key) must never touch the repository &mdash; encoding the COBOL evaluate order.
 * </ul>
 *
 * <p><b>Admin-only gate (the first of the four user-management services).</b> {@code CU00} is
 * reachable only from the admin menu, so a non-admin conversation must be rejected with {@link
 * AuthorizationException} <em>before</em> any repository access &mdash; a mandatory assertion here.
 *
 * <p><b>Credential hygiene (&sect;0.6.6).</b> The list rows carry only the user id, names, and the
 * {@code A}/{@code U} type; there is no password on the row contract. These tests reference users
 * by id/name/type only and <em>never</em> assert (or even read) the {@code secUsrPwd} hash.
 *
 * <p><b>FILE STATUS mapping (&sect;0.6.4).</b> For this list/browse screen, edge-of-list outcomes
 * (empty browse, end-of-file, start-of-file) and an unexpected datastore failure are surfaced as
 * on-screen messages with a {@code null} redisplay return &mdash; they are <em>not</em> mapped to
 * {@code IoStatusException}, matching the service's documented {@code WHEN OTHER} behavior.
 */
@ExtendWith(MockitoExtension.class)
class UserListServiceTest {

  @Mock private UserSecurityRepository userSecurityRepository;

  @InjectMocks private UserListService service;

  // ===============================================================================================
  // Fixtures
  // ===============================================================================================

  /**
   * Zero-pads an integer to the eight-character {@code SEC-USR-ID} key width (USER0001..USER9999).
   */
  private static String userId(int n) {
    return String.format("USER%04d", n);
  }

  /**
   * Builds one security record with a deterministic, parity-friendly layout: a zero-padded id, name
   * fields, and a type flag alternating {@code U}/{@code A}. The password is a fake, BCrypt-shaped
   * placeholder that is NEVER asserted (credential hygiene, AAP &sect;0.6.6).
   */
  private static UserSecurity user(int n) {
    UserSecurity u = new UserSecurity();
    u.setSecUsrId(userId(n));
    u.setSecUsrFname("First" + n);
    u.setSecUsrLname("Last" + n);
    u.setSecUsrType((n % 2 == 0) ? "A" : "U");
    u.setSecUsrPwd("$2a$HASH"); // fake hash; never read or asserted by any test
    return u;
  }

  /** Builds an ascending-by-id list of {@code count} users (ids {@code USER0001..}). */
  private static List<UserSecurity> users(int count) {
    List<UserSecurity> list = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      list.add(user(i));
    }
    return list;
  }

  /**
   * Stubs the keyset browse source over an in-memory ascending snapshot of {@code count} users.
   * Models the four repository methods {@link UserListService} now uses &mdash; the forward GTEQ
   * page, the forward strictly-greater page (the PF8 {@code READNEXT} step), the backward
   * strictly-below descending page (PF7 {@code READPREV}), and the bounded next-page existence
   * probe &mdash; each bounded to the {@link Pageable} row limit and returning the {@link
   * UserListProjection} (the four displayed columns only; the BCrypt password hash is NEVER
   * selected, satisfying finding F-4 and credential hygiene, AAP &sect;0.6.6).
   */
  private void givenUsers(int count) {
    stubKeyset(users(count));
  }

  /**
   * Installs lenient keyset simulators for the four browse methods over {@code sorted}. Stubs are
   * {@link org.mockito.Mockito#lenient() lenient} because a given test exercises only one browse
   * direction (or the existence probe), so the unused stubs do not trip strict stubbing.
   */
  private void stubKeyset(List<UserSecurity> sorted) {
    lenient()
        .when(
            userSecurityRepository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
                anyString(), any(Pageable.class)))
        .thenAnswer(inv -> forwardSlice(sorted, inv.getArgument(0), true, inv.getArgument(1)));
    lenient()
        .when(
            userSecurityRepository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(
                anyString(), any(Pageable.class)))
        .thenAnswer(inv -> forwardSlice(sorted, inv.getArgument(0), false, inv.getArgument(1)));
    lenient()
        .when(
            userSecurityRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(
                anyString(), any(Pageable.class)))
        .thenAnswer(inv -> backwardSlice(sorted, inv.getArgument(0), inv.getArgument(1)));
    lenient()
        .when(userSecurityRepository.existsBySecUsrIdGreaterThan(anyString()))
        .thenAnswer(inv -> anyUserAfter(sorted, inv.getArgument(0)));
  }

  /**
   * Reproduces the forward keyset query (GTEQ when {@code inclusive}, else strictly-greater): the
   * ascending slice of users at/after the key, bounded to the page size, as projections.
   */
  private static List<UserListProjection> forwardSlice(
      List<UserSecurity> sorted, String key, boolean inclusive, Pageable pageable) {
    List<UserListProjection> out = new ArrayList<>();
    int limit = pageable.getPageSize();
    for (UserSecurity u : sorted) {
      int cmp = u.getSecUsrId().compareTo(key);
      if (inclusive ? cmp >= 0 : cmp > 0) {
        out.add(projection(u));
        if (out.size() >= limit) {
          break;
        }
      }
    }
    return out;
  }

  /**
   * Reproduces {@code findBySecUsrIdLessThanOrderBySecUsrIdDesc}: the descending (closest-below
   * first) slice of users strictly below the key, bounded to the page size, as projections.
   */
  private static List<UserListProjection> backwardSlice(
      List<UserSecurity> sorted, String key, Pageable pageable) {
    List<UserListProjection> out = new ArrayList<>();
    int limit = pageable.getPageSize();
    for (int i = sorted.size() - 1; i >= 0; i--) {
      UserSecurity u = sorted.get(i);
      if (u.getSecUsrId().compareTo(key) < 0) {
        out.add(projection(u));
        if (out.size() >= limit) {
          break;
        }
      }
    }
    return out;
  }

  /**
   * Reproduces {@code existsBySecUsrIdGreaterThan}: whether any user sorts strictly after the key.
   */
  private static boolean anyUserAfter(List<UserSecurity> sorted, String key) {
    for (UserSecurity u : sorted) {
      if (u.getSecUsrId().compareTo(key) > 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Wraps a {@link UserSecurity} fixture as the closed {@link UserListProjection} the browse
   * queries return &mdash; the four displayed columns only. The password hash is deliberately
   * unreachable through this view, mirroring the production projection that finding F-4 mandates.
   */
  private static UserListProjection projection(UserSecurity u) {
    return new UserListProjection() {
      @Override
      public String getSecUsrId() {
        return u.getSecUsrId();
      }

      @Override
      public String getSecUsrFname() {
        return u.getSecUsrFname();
      }

      @Override
      public String getSecUsrLname() {
        return u.getSecUsrLname();
      }

      @Override
      public String getSecUsrType() {
        return u.getSecUsrType();
      }
    };
  }

  /** A fresh, empty list screen (the ten-row grid has not yet been painted). */
  private static UserListScreen screen() {
    return new UserListScreen();
  }

  /**
   * A screen already displaying a page: the ten-row grid is populated with users {@code
   * USER<firstId>..USER<firstId+count-1>} (selection blank), trailing rows blanked, and the page
   * counter set to {@code pageNum}. This mirrors a round-tripped page whose first/last populated
   * user ids are the PF7/PF8 cursors the stateless service reconstructs from the screen.
   */
  private static UserListScreen screenShowing(int firstId, int count, int pageNum) {
    UserListScreen s = new UserListScreen();
    List<UserListRow> rows = new ArrayList<>();
    for (int i = 0; i < UserListService.PAGE_SIZE; i++) {
      UserListRow row = new UserListRow();
      row.setSel("");
      if (i < count) {
        int n = firstId + i;
        row.setUsrId(userId(n));
        row.setFName("First" + n);
        row.setLName("Last" + n);
        row.setUType((n % 2 == 0) ? "A" : "U");
      } else {
        row.setUsrId("");
        row.setFName("");
        row.setLName("");
        row.setUType("");
      }
      rows.add(row);
    }
    s.setRows(rows);
    s.setPageNum(String.format("%08d", pageNum));
    return s;
  }

  /** A blank ten-row screen carrying a single row selection (a selection flag plus a user id). */
  private static UserListScreen screenWithSelection(int rowIndex, String sel, String usrId) {
    UserListScreen s = new UserListScreen();
    List<UserListRow> rows = new ArrayList<>();
    for (int i = 0; i < UserListService.PAGE_SIZE; i++) {
      rows.add(new UserListRow());
    }
    rows.get(rowIndex).setSel(sel);
    rows.get(rowIndex).setUsrId(usrId);
    s.setRows(rows);
    return s;
  }

  /** An administrator conversation that has already entered this program (re-entry). */
  private static CardDemoCommarea adminCommarea() {
    CardDemoCommarea c = new CardDemoCommarea();
    c.setUserId("ADMIN001");
    c.setUsrTypAdmin();
    c.setPgmReenter();
    return c;
  }

  /** An administrator conversation on first entry to this program. */
  private static CardDemoCommarea adminFirstEntry() {
    CardDemoCommarea c = new CardDemoCommarea();
    c.setUserId("ADMIN001");
    c.setUsrTypAdmin();
    c.setPgmEnter();
    return c;
  }

  /** A standard (non-admin) conversation, used to exercise the admin-only gate rejection. */
  private static CardDemoCommarea userCommarea() {
    CardDemoCommarea c = new CardDemoCommarea();
    c.setUserId("USER0001");
    c.setUsrTypUser();
    c.setPgmReenter();
    return c;
  }

  // ===============================================================================================
  // Null-argument guards (Objects.requireNonNull on screen and commarea)
  // ===============================================================================================

  @Test
  @DisplayName("null screen is rejected before any browse")
  void nullScreen_throws() {
    assertThatThrownBy(() -> service.processUserList(null, adminCommarea(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class);
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName("null commarea is rejected before any browse")
  void nullCommarea_throws() {
    assertThatThrownBy(() -> service.processUserList(screen(), null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class);
    verifyNoInteractions(userSecurityRepository);
  }

  // ===============================================================================================
  // Admin-only gate (AAP 0.6.5/0.7.2): a non-admin is rejected before any repository access
  // ===============================================================================================

  @Test
  @DisplayName("a standard (non-admin) user is rejected by the admin-only gate before any browse")
  void nonAdminUser_isRejectedByGate() {
    assertThatThrownBy(
            () -> service.processUserList(screen(), userCommarea(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(AuthorizationException.class)
        .hasMessage(AuthorizationException.ADMIN_ONLY_MESSAGE);
    verifyNoInteractions(userSecurityRepository);
  }

  // ===============================================================================================
  // MAIN-PARA: first entry and re-entry ENTER paint page one
  // ===============================================================================================

  @Test
  @DisplayName("first entry marks re-entry and paints page one from the top of the file")
  void firstEntry_marksReenterAndPaintsPageOne() {
    givenUsers(25);
    CardDemoCommarea commarea = adminFirstEntry();
    UserListScreen s = screen();

    String next = service.processUserList(s, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(s.getRows()).hasSize(UserListService.PAGE_SIZE);
    assertThat(s.getRows().get(0).getUsrId()).isEqualTo(userId(1));
    assertThat(s.getRows().get(9).getUsrId()).isEqualTo(userId(10));
    assertThat(s.getPageNum()).isEqualTo("00000001");
    assertThat(s.getErrMsg()).isEmpty();
  }

  @Test
  @DisplayName("admin first page fills the full ten-row grid with id/name/type (never password)")
  void adminFirstPage_full10Rows_populatesGrid() {
    givenUsers(25);
    UserListScreen s = screen();

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getRows()).hasSize(UserListService.PAGE_SIZE);

    // Rows one and two show the id/first-name/last-name mapping and the user-type flag rendered
    // verbatim ('U' standard / 'A' admin). Credential hygiene (AAP 0.6.6): the row contract has no
    // password field to leak, and this test never references secUsrPwd.
    UserListRow row0 = s.getRows().get(0);
    assertThat(row0.getUsrId()).isEqualTo(userId(1));
    assertThat(row0.getFName()).isEqualTo("First1");
    assertThat(row0.getLName()).isEqualTo("Last1");
    assertThat(row0.getUType()).isEqualTo("U");
    assertThat(row0.getSel()).isEmpty();
    assertThat(s.getRows().get(1).getUType()).isEqualTo("A");
    assertThat(s.getRows().get(9).getUsrId()).isEqualTo(userId(10));
    assertThat(s.getPageNum()).isEqualTo("00000001");
    assertThat(s.getErrMsg()).isEmpty();

    // Control-flow parity: exactly one bounded keyset browse from the top of the file (GTEQ ""),
    // and nothing else.
    InOrder inOrder = inOrder(userSecurityRepository);
    inOrder
        .verify(userSecurityRepository)
        .findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(eq(""), any(Pageable.class));
    inOrder.verifyNoMoreInteractions();
  }

  // ===============================================================================================
  // PROCESS-ENTER-KEY: user-id filter positioning
  // ===============================================================================================

  @Test
  @DisplayName("ENTER with a blank filter browses from the top and clears the filter echo")
  void enter_blankFilter_browsesFromTop() {
    givenUsers(25);
    UserListScreen s = screen();

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getUsrId()).isEqualTo(userId(1));
    assertThat(s.getPageNum()).isEqualTo("00000001");
    assertThat(s.getUsrIdIn()).isEmpty();
  }

  @Test
  @DisplayName("ENTER with a user-id filter positions the browse GTEQ at that id")
  void enter_nonBlankFilter_positionsGteq() {
    givenUsers(25);
    UserListScreen s = screen();
    s.setUsrIdIn(userId(15));

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getUsrId()).isEqualTo(userId(15));
    assertThat(s.getPageNum()).isEqualTo("00000001");
  }

  @Test
  @DisplayName("ENTER with a short filter is space-padded to the key width before GTEQ positioning")
  void enter_shortFilter_isSpacePaddedForGteq() {
    givenUsers(25);
    UserListScreen s = screen();
    s.setUsrIdIn("USER000"); // 7 chars -> padded to "USER000 " (fixed-width X(8) key semantics)

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getUsrId()).isEqualTo(userId(1));
    assertThat(s.getPageNum()).isEqualTo("00000001");
  }

  @Test
  @DisplayName("ENTER with a filter beyond the last id shows the 'at the top' (NOTFND) message")
  void enter_filterBeyondEnd_showsAtTop() {
    givenUsers(25);
    UserListScreen s = screen();
    s.setUsrIdIn("ZZZZZZZZ");

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are at the top of the page...");
    assertThat(s.getRows()).hasSize(UserListService.PAGE_SIZE);
    assertThat(s.getRows().get(0).getUsrId()).isEmpty();
  }

  @Test
  @DisplayName("ENTER against an empty file shows the 'at the top' (NOTFND) message")
  void enter_emptyFile_showsAtTop() {
    givenUsers(0);
    UserListScreen s = screen();

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are at the top of the page...");
    assertThat(s.getPageNum()).isEqualTo("00000000");
  }

  // ===============================================================================================
  // PROCESS-ENTER-KEY: row selection (navigation/state parity, AAP 0.6.5)
  // ===============================================================================================

  @Test
  @DisplayName("row marked 'U' forwards to the user-update program without browsing")
  void selectRow_U_forwardsToUserUpdate() {
    UserListScreen s = screenWithSelection(2, "U", userId(3));
    CardDemoCommarea commarea = adminCommarea();

    String next = service.processUserList(s, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COUSR02C");
    assertThat(commarea.getToProgram()).isEqualTo("COUSR02C");
    assertThat(commarea.getFromTranId()).isEqualTo("CU00");
    assertThat(commarea.getFromProgram()).isEqualTo("COUSR00C");
    assertThat(commarea.isPgmEnter()).isTrue();
    // The selected user id is carried into COUSR02C by the controller, not this service.
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName("row marked lowercase 'd' forwards to the user-delete program (case-insensitive)")
  void selectRow_lowercaseD_forwardsToUserDelete() {
    UserListScreen s = screenWithSelection(0, "d", userId(1));
    CardDemoCommarea commarea = adminCommarea();

    String next = service.processUserList(s, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COUSR03C");
    assertThat(commarea.getToProgram()).isEqualTo("COUSR03C");
    assertThat(commarea.getFromTranId()).isEqualTo("CU00");
    assertThat(commarea.getFromProgram()).isEqualTo("COUSR00C");
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName("an unrecognized selection flag shows the byte-exact message and renders the list")
  void invalidSelectionCode_redisplaysWithMessage() {
    givenUsers(25);
    UserListScreen s = screenWithSelection(0, "X", userId(3));

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("Invalid selection. Valid values are U and D");
    assertThat(s.getRows().get(0).getUsrId()).isEqualTo(userId(1)); // list still rendered
  }

  @Test
  @DisplayName("a selection flag paired with a blank row id is ignored and falls through to browse")
  void selectionWithBlankId_isIgnored() {
    givenUsers(25);
    UserListScreen s = screenWithSelection(0, "U", "        ");

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getUsrId()).isEqualTo(userId(1));
  }

  // ===============================================================================================
  // PROCESS-PF8-KEY: page forward
  // ===============================================================================================

  @Test
  @DisplayName("PF8 with a further page advances to the next page and refreshes the rows")
  void forwardPaging_pf8_advances() {
    givenUsers(25);
    UserListScreen s = screenShowing(1, UserListService.PAGE_SIZE, 1);

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getUsrId()).isEqualTo(userId(11));
    assertThat(s.getRows().get(9).getUsrId()).isEqualTo(userId(20));
    assertThat(s.getPageNum()).isEqualTo("00000002");
    assertThat(s.getErrMsg()).isEmpty();
  }

  @Test
  @DisplayName("PF8 onto the final partial page shows the 'reached the bottom' message")
  void forwardPaging_pf8_ontoLastPartialPage_showsReachedBottom() {
    givenUsers(25);
    UserListScreen s = screenShowing(11, UserListService.PAGE_SIZE, 2);

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getUsrId()).isEqualTo(userId(21));
    assertThat(s.getRows().get(4).getUsrId()).isEqualTo(userId(25));
    assertThat(s.getRows().get(5).getUsrId()).isEmpty();
    assertThat(s.getPageNum()).isEqualTo("00000003");
    assertThat(s.getErrMsg()).isEqualTo("You have reached the bottom of the page...");
  }

  @Test
  @DisplayName("PF8 with no further page shows the 'already at the bottom' message")
  void forwardPaging_pf8_atBottom_showsAlreadyAtBottom() {
    givenUsers(10);
    UserListScreen s = screenShowing(1, UserListService.PAGE_SIZE, 1);

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are already at the bottom of the page...");
  }

  @Test
  @DisplayName("PF8 on an empty list shows the 'already at the bottom' message")
  void forwardPaging_pf8_noPopulatedRow_showsAlreadyAtBottom() {
    givenUsers(5);
    UserListScreen s = screen();

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are already at the bottom of the page...");
  }

  // ===============================================================================================
  // PROCESS-PF7-KEY: page backward
  // ===============================================================================================

  @Test
  @DisplayName("PF7 on page one shows the 'already at the top' message without browsing")
  void backwardPaging_pf7_atTop_showsAlreadyAtTop() {
    UserListScreen s = screen(); // blank page counter is treated as page <= 1

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.PFK07);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("You are already at the top of the page...");
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName("PF7 from page two pages back to page one and shows the 'reached the top' message")
  void backwardPaging_pf7_fromPage2_returnsToPageOne() {
    givenUsers(25);
    UserListScreen s = screenShowing(11, UserListService.PAGE_SIZE, 2);

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.PFK07);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getUsrId()).isEqualTo(userId(1));
    assertThat(s.getRows().get(9).getUsrId()).isEqualTo(userId(10));
    assertThat(s.getPageNum()).isEqualTo("00000001");
    assertThat(s.getErrMsg()).isEqualTo("You have reached the top of the page...");
  }

  @Test
  @DisplayName("PF7 from page three steps back to page two without the top-of-file message")
  void backwardPaging_pf7_fromPage3_stepsBackToPageTwo() {
    givenUsers(25);
    UserListScreen s = screenShowing(21, 5, 3);

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.PFK07);

    assertThat(next).isNull();
    assertThat(s.getRows().get(0).getUsrId()).isEqualTo(userId(11));
    assertThat(s.getRows().get(9).getUsrId()).isEqualTo(userId(20));
    assertThat(s.getPageNum()).isEqualTo("00000002");
    assertThat(s.getErrMsg()).isEmpty();
  }

  // ===============================================================================================
  // MAIN-PARA: PF3 dispatch, the unmapped-key path, and the datastore-failure redisplay
  // ===============================================================================================

  @Test
  @DisplayName("PF3 returns to the admin menu without browsing")
  void pfk03_returnsToAdminMenu() {
    CardDemoCommarea commarea = adminCommarea();

    String next = service.processUserList(screen(), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COADM01C");
    assertThat(commarea.getToProgram()).isEqualTo("COADM01C");
    assertThat(commarea.getFromTranId()).isEqualTo("CU00");
    assertThat(commarea.getFromProgram()).isEqualTo("COUSR00C");
    assertThat(commarea.isPgmEnter()).isTrue();
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName("an unmapped key redisplays with the trimmed shared 'invalid key' message")
  void unmappedKey_redisplaysWithInvalidKeyMessage() {
    UserListScreen s = screen();

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.PFK12);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName("a datastore failure shows 'Unable to lookup User...' and redisplays (no exception)")
  void repositoryThrows_setsUnableToLookupMessage() {
    when(userSecurityRepository.findBySecUsrIdGreaterThanEqualOrderBySecUsrIdAsc(
            anyString(), any(Pageable.class)))
        .thenThrow(new DataAccessResourceFailureException("simulated datastore failure"));
    UserListScreen s = screen();

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("Unable to lookup User...");
    assertThat(s.getRows()).isEmpty();
  }

  @Test
  @DisplayName("a datastore failure while paging forward (PF8) shows 'Unable to lookup User...'")
  void forwardPaging_pf8_datastoreFailure_showsUnableToLookup() {
    when(userSecurityRepository.existsBySecUsrIdGreaterThan(anyString()))
        .thenThrow(new DataAccessResourceFailureException("simulated datastore failure"));
    UserListScreen s = screenShowing(1, UserListService.PAGE_SIZE, 1);

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.PFK08);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("Unable to lookup User...");
  }

  @Test
  @DisplayName("a datastore failure while paging backward (PF7) shows 'Unable to lookup User...'")
  void backwardPaging_pf7_datastoreFailure_showsUnableToLookup() {
    when(userSecurityRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(
            anyString(), any(Pageable.class)))
        .thenThrow(new DataAccessResourceFailureException("simulated datastore failure"));
    UserListScreen s = screenShowing(11, UserListService.PAGE_SIZE, 2);

    String next = service.processUserList(s, adminCommarea(), CardWorkArea.Aid.PFK07);

    assertThat(next).isNull();
    assertThat(s.getErrMsg()).isEqualTo("Unable to lookup User...");
  }
}

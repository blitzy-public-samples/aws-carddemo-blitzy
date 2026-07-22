/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.online;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COUSR00Form;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.service.online.UserListService.AidKey;
import com.aws.carddemo.service.online.UserListService.UserListResult;
import com.aws.carddemo.service.online.UserListService.UserListState;
import com.aws.carddemo.service.online.UserListService.UserRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link UserListService}, the Java migration of the CICS COBOL
 * program {@code COUSR00C} (the AWS CardDemo administrator "List Users" screen).
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COUSR00C.cbl} &mdash; program
 * {@code COUSR00C}, CICS transaction id {@code CU00}. {@code COUSR00C} is an <em>admin-only</em>,
 * <em>read-only</em> paged browse over the {@code USRSEC} security file: it displays ten user rows
 * per page and pages backward/forward with PF7/PF8, mirroring the COBOL
 * {@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR} browse. These tests assert
 * control-flow parity with the program's numbered paragraphs:</p>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr;
 *       {@link UserListService#mainEntry(AidKey, COUSR00Form, UserListState)} (the
 *       {@code EIBCALEN = 0} first-entry bounce, the {@code NOT CDEMO-PGM-REENTER} first-display
 *       browse, the {@code EVALUATE EIBAID} dispatch, and the {@code WHEN OTHER} invalid-key
 *       branch)</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr;
 *       {@link UserListService#processEnterKey(COUSR00Form, CardDemoContext)} (the
 *       {@code EVALUATE TRUE} scan of the ten selection fields, the {@code 'U'}/{@code 'D'}
 *       {@code XCTL} routing, the invalid-selection message, and the first-page browse)</li>
 *   <li>{@code PROCESS-PF7-KEY} &rarr; {@link UserListService#processPf7Key(UserListState)} (page
 *       backward, and the {@code CDEMO-CU00-PAGE-NUM &gt; 1} already-at-top guard)</li>
 *   <li>{@code PROCESS-PF8-KEY} &rarr; {@link UserListService#processPf8Key(UserListState)} (page
 *       forward, and the {@code NEXT-PAGE-YES} already-at-bottom guard)</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} / {@code PROCESS-PAGE-BACKWARD} &rarr; exercised through the
 *       PF7/PF8 and ENTER browses (the fill-ten-rows loops and page-number arithmetic)</li>
 *   <li>{@code POPULATE-USER-DATA} / {@code INITIALIZE-USER-DATA} &rarr; asserted through the
 *       displayed {@link UserRow} contents (row user ids and the first/last page keys)</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr;
 *       {@link UserListService#returnToPrevScreen(CardDemoContext)} (the
 *       {@code CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES} default to {@code COSGN00C})</li>
 * </ul>
 *
 * <p><b>Test tier.</b> This is a strict-stubs pure-Mockito unit test:
 * {@link MockitoExtension} with {@link Mock} collaborators and an {@link InjectMocks} service. It
 * starts no Spring context, opens no database connection, and loads no persistence types &mdash; it
 * verifies the service's business logic in complete isolation. The presentation paragraphs
 * ({@code SEND-USRLST-SCREEN}, {@code RECEIVE-USRLST-SCREEN}, {@code POPULATE-HEADER-INFO}) are
 * owned by the paired {@code UserAdminController} and are therefore not exercised here.</p>
 *
 * <p><b>How routing parity is asserted.</b> {@code COUSR00C}'s {@code XCTL} to a maintenance program
 * is reproduced by the service returning a {@link UserListResult} whose
 * {@link UserListResult#targetProgram() targetProgram} and
 * {@link UserListResult#targetTransactionId() targetTransactionId} are the destination program and
 * transaction id. The COBOL hand-off moves ({@code CDEMO-FROM-TRANID = 'CU00'},
 * {@code CDEMO-FROM-PROGRAM = 'COUSR00C'}, {@code CDEMO-PGM-CONTEXT = 0}, and the
 * {@code CDEMO-TO-PROGRAM}) are asserted directly on the mocked {@link CardDemoContext}. The target
 * transaction id is carried on the returned result rather than written through
 * {@code CardDemoContext.setToTranid}, which the program does not perform; routing is therefore
 * verified through the returned redirect target plus the context hand-off writes.</p>
 *
 * <p><b>Browse data.</b> {@link UserListService} loads each page as a bounded, key-ordered window
 * (review finding #21): a forward page comes from
 * {@link UserSecurityRepository#findByUsrIdGreaterThanEqualOrderByUsrIdAsc(String, Limit)} and a
 * backward page from
 * {@link UserSecurityRepository#findByUsrIdLessThanEqualOrderByUsrIdDesc(String, Limit)}, each
 * capped at the page size plus the browse's skip-one and look-ahead reads, rather than
 * materialising the whole {@code USRSEC} table. The tests stub those window finders with a
 * controlled, pre-sorted user fixture &mdash; each stub computes the exact slice a real
 * {@code C}-collated, key-ordered query would return for the requested start key and limit &mdash;
 * and assert the ten-row page window, the first/last page keys, and the page arithmetic, so the
 * VSAM KSDS ascending-key browse is reproduced faithfully while the query stays bounded.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserListServiceTest {

    /** Rows displayed per page &mdash; COBOL {@code USER-REC OCCURS 10 TIMES}. */
    private static final int ROWS_PER_PAGE = 10;

    /** Width of the {@code SEC-USR-ID} key ({@code PIC X(08)}), used to right-pad browse keys. */
    private static final int USER_ID_LENGTH = 8;

    /** Byte-exact invalid-selection literal (COBOL {@code PROCESS-ENTER-KEY}, oracle line 212). */
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid values are U and D";

    /** Byte-exact already-at-top guard literal (COBOL {@code PROCESS-PF7-KEY}, oracle line 251). */
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";

    /** Byte-exact already-at-bottom guard literal (COBOL {@code PROCESS-PF8-KEY}, oracle line 273). */
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";

    /** Invalid-key literal (COBOL {@code CCDA-MSG-INVALID-KEY}) for the {@code WHEN OTHER} branch. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Sign-on program ({@code COSGN00C}) &mdash; first-entry bounce and default return target. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Sign-on transaction id ({@code CC00}). */
    private static final String SIGNON_TRANID = "CC00";

    /**
     * Administrator main-menu program ({@code COADM01C}) &mdash; the PF3 return target. This is the
     * <em>admin</em> menu, not the general user main menu ({@code COMEN01C}).
     */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** Administrator main-menu transaction id ({@code CA00}). */
    private static final String ADMIN_MENU_TRANID = "CA00";

    /** User-update program ({@code COUSR02C}) &mdash; the {@code 'U'}/{@code 'u'} selection target. */
    private static final String USER_UPDATE_PROGRAM = "COUSR02C";

    /** User-update transaction id ({@code CU02}). */
    private static final String USER_UPDATE_TRANID = "CU02";

    /** User-delete program ({@code COUSR03C}) &mdash; the {@code 'D'}/{@code 'd'} selection target. */
    private static final String USER_DELETE_PROGRAM = "COUSR03C";

    /** User-delete transaction id ({@code CU03}). */
    private static final String USER_DELETE_TRANID = "CU03";

    /** This program's transaction id (COBOL {@code WS-TRANID = 'CU00'}), the hand-off origin. */
    private static final String FROM_TRANID = "CU00";

    /** This program's name (COBOL {@code WS-PGMNAME = 'COUSR00C'}), the hand-off origin. */
    private static final String FROM_PROGRAM = "COUSR00C";

    /** Session context (COMMAREA replacement); mocked so hand-off writes can be verified. */
    @Mock
    private CardDemoContext context;

    /** {@code USRSEC} repository; stubbed to return controlled, pre-sorted browse pages. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** Service under test, wired by constructor injection with the two mocks above. */
    @InjectMocks
    private UserListService service;

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /**
     * Builds one {@link UserSecurity} record with the four browse-relevant fields populated.
     *
     * @param usrId     the user id ({@code SEC-USR-ID}, {@code PIC X(08)}); the KSDS key
     * @param firstName the first name ({@code SEC-USR-FNAME})
     * @param lastName  the last name ({@code SEC-USR-LNAME})
     * @param userType  the user type ({@code SEC-USR-TYPE}: {@code A}=admin, {@code U}=user)
     * @return a populated user-security record
     */
    private static UserSecurity user(String usrId, String firstName, String lastName,
                                     String userType) {
        UserSecurity record = new UserSecurity();
        record.setUsrId(usrId);
        record.setUsrFname(firstName);
        record.setUsrLname(lastName);
        record.setUsrType(userType);
        return record;
    }

    /**
     * Builds {@code count} user-security records with eight-character ids {@code USER0001},
     * {@code USER0002}, &hellip; in ascending order, alternating the {@code U}/{@code A} type. The
     * ids are already sorted ascending, reproducing the ordered result a {@code C}-collated,
     * key-ordered browse window query would return over the {@code SEC-USR-ID} key column.
     *
     * @param count the number of users to build
     * @return an ascending, pre-sorted list of {@code count} user-security records
     */
    private static List<UserSecurity> usersUpTo(int count) {
        List<UserSecurity> users = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String id = String.format("USER%04d", i);
            String type = (i % 2 == 0) ? "A" : "U";
            users.add(user(id, "First" + i, "Last" + i, type));
        }
        return users;
    }

    /**
     * Returns a fresh, empty user-list form (all fields {@code null}), the web-tier equivalent of a
     * cleared/low-values {@code COUSR0AI} map carrying no selection and a blank filter.
     *
     * @return a new, empty {@link COUSR00Form}
     */
    private static COUSR00Form emptyForm() {
        return new COUSR00Form();
    }

    /**
     * Returns a form carrying a single row selection: the selection flag and user id of the given
     * one-based row, with all other rows left blank.
     *
     * @param row           the one-based selection row (1..10)
     * @param selectionFlag the selection flag placed in {@code SELnnnnI}
     * @param userId        the user id placed in {@code USRIDnnI}
     * @return a form with exactly the requested row populated
     */
    private static COUSR00Form formSelecting(int row, String selectionFlag, String userId) {
        COUSR00Form form = new COUSR00Form();
        setRow(form, row, selectionFlag, userId);
        return form;
    }

    /**
     * Sets the selection flag and user id of a one-based row on the given form, mapping the flat
     * indexed BMS properties ({@code SEL0001I}..{@code SEL0010I} and {@code USRID01I}..{@code
     * USRID10I}).
     *
     * @param form          the form to mutate
     * @param row           the one-based row index (1..10)
     * @param selectionFlag the selection-flag value
     * @param userId        the row user-id value
     */
    private static void setRow(COUSR00Form form, int row, String selectionFlag, String userId) {
        switch (row) {
            case 1 -> { form.setSel0001(selectionFlag); form.setUsrid01(userId); }
            case 2 -> { form.setSel0002(selectionFlag); form.setUsrid02(userId); }
            case 3 -> { form.setSel0003(selectionFlag); form.setUsrid03(userId); }
            case 4 -> { form.setSel0004(selectionFlag); form.setUsrid04(userId); }
            case 5 -> { form.setSel0005(selectionFlag); form.setUsrid05(userId); }
            case 6 -> { form.setSel0006(selectionFlag); form.setUsrid06(userId); }
            case 7 -> { form.setSel0007(selectionFlag); form.setUsrid07(userId); }
            case 8 -> { form.setSel0008(selectionFlag); form.setUsrid08(userId); }
            case 9 -> { form.setSel0009(selectionFlag); form.setUsrid09(userId); }
            case 10 -> { form.setSel0010(selectionFlag); form.setUsrid10(userId); }
            default -> throw new IllegalArgumentException("row out of range: " + row);
        }
    }

    /**
     * Builds a paging cursor ({@link UserListState}) for the PF7/PF8/invalid-key re-entry paths.
     *
     * @param pageNumber        the current page number ({@code CDEMO-CU00-PAGE-NUM})
     * @param firstUserId       the first displayed user id ({@code CDEMO-CU00-USRID-FIRST})
     * @param lastUserId        the last displayed user id ({@code CDEMO-CU00-USRID-LAST})
     * @param nextPageAvailable whether a further forward page exists ({@code CDEMO-CU00-NEXT-PAGE-FLG})
     * @return the paging cursor
     */
    private static UserListState stateAt(int pageNumber, String firstUserId, String lastUserId,
                                         boolean nextPageAvailable) {
        return new UserListState(pageNumber, firstUserId, lastUserId, nextPageAvailable);
    }

    /**
     * Stubs the <em>forward</em> browse window finder to behave like the real {@code C}-collated,
     * key-ordered query: for a requested inclusive lower-bound start key and {@link Limit}, it
     * returns the ascending slice of {@code ascending} whose right-padded user id is greater than
     * or equal to the padded start key, capped at the limit. Only the forward finder is stubbed, so
     * forward-paging tests raise no unnecessary-stubbing error under strict stubs.
     *
     * @param ascending the full fixture, already sorted ascending by user id
     */
    private void stubForwardWindows(List<UserSecurity> ascending) {
        when(userSecurityRepository.findByUsrIdGreaterThanEqualOrderByUsrIdAsc(anyString(),
                any(Limit.class)))
                .thenAnswer(invocation -> forwardWindow(ascending, invocation.getArgument(0),
                        invocation.getArgument(1)));
    }

    /**
     * Stubs the <em>backward</em> browse window finder to behave like the real {@code C}-collated,
     * key-ordered query: for a requested inclusive upper-bound start key and {@link Limit}, it
     * returns the <em>descending</em> slice of {@code ascending} whose right-padded user id is less
     * than or equal to the padded start key, capped at the limit (the service reverses it to
     * ascending for the cursor). Only the backward finder is stubbed.
     *
     * @param ascending the full fixture, already sorted ascending by user id
     */
    private void stubBackwardWindows(List<UserSecurity> ascending) {
        when(userSecurityRepository.findByUsrIdLessThanEqualOrderByUsrIdDesc(anyString(),
                any(Limit.class)))
                .thenAnswer(invocation -> backwardWindow(ascending, invocation.getArgument(0),
                        invocation.getArgument(1)));
    }

    /**
     * Computes the forward (ascending, greater-than-or-equal) window a real key-ordered query would
     * return, using the same eight-byte right-padded key comparison the service and the
     * {@code C}-collated column use.
     *
     * @param ascending the full fixture sorted ascending by user id
     * @param startKey  the inclusive lower-bound start key (already padded by the service)
     * @param limit     the row cap
     * @return the ascending window, at most {@code limit} records
     */
    private static List<UserSecurity> forwardWindow(List<UserSecurity> ascending, String startKey,
                                                    Limit limit) {
        String padded = pad8(startKey);
        List<UserSecurity> window = new ArrayList<>();
        for (UserSecurity candidate : ascending) {
            if (pad8(candidate.getUsrId()).compareTo(padded) >= 0) {
                window.add(candidate);
                if (window.size() == limit.max()) {
                    break;
                }
            }
        }
        return window;
    }

    /**
     * Computes the backward (descending, less-than-or-equal) window a real key-ordered query would
     * return, matching the repository contract that hands back rows in descending key order for the
     * service to reverse.
     *
     * @param ascending the full fixture sorted ascending by user id
     * @param startKey  the inclusive upper-bound start key (already padded by the service)
     * @param limit     the row cap
     * @return the descending window, at most {@code limit} records
     */
    private static List<UserSecurity> backwardWindow(List<UserSecurity> ascending, String startKey,
                                                     Limit limit) {
        String padded = pad8(startKey);
        List<UserSecurity> window = new ArrayList<>();
        for (int i = ascending.size() - 1; i >= 0; i--) {
            UserSecurity candidate = ascending.get(i);
            if (pad8(candidate.getUsrId()).compareTo(padded) <= 0) {
                window.add(candidate);
                if (window.size() == limit.max()) {
                    break;
                }
            }
        }
        return window;
    }

    /**
     * Right-pads (or truncates) a key to the eight-byte {@code SEC-USR-ID} width, reproducing the
     * fixed-width key comparison the browse and the {@code C}-collated {@code CHAR(8)} column use.
     *
     * @param value the raw key (may be {@code null} or shorter/longer than eight characters)
     * @return the value padded with spaces (or truncated) to exactly eight characters
     */
    private static String pad8(String value) {
        String safe = (value == null) ? "" : value;
        if (safe.length() >= USER_ID_LENGTH) {
            return safe.substring(0, USER_ID_LENGTH);
        }
        return safe + " ".repeat(USER_ID_LENGTH - safe.length());
    }

    // ------------------------------------------------------------------------------------------
    // processEnterKey - PROCESS-ENTER-KEY (parity item 1: exactly ten rows per page, ordered)
    // ------------------------------------------------------------------------------------------

    /**
     * With an empty (cleared) form and more than ten users available, the first browse fills
     * exactly ten display rows &mdash; the COBOL {@code USER-REC OCCURS 10 TIMES} table &mdash; in
     * ascending user-id order, sets the page to one, records the first/last page keys, and reports
     * that a further page exists. The browse is loaded ascending by {@code usrId}
     * ({@code STARTBR}/{@code READNEXT} order).
     */
    @Test
    void processEnterKey_firstPage_populatesExactlyTenRowsOrderedByUserId() {
        stubForwardWindows(usersUpTo(12));

        UserListResult result = service.processEnterKey(emptyForm(), context);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.rows()).hasSize(ROWS_PER_PAGE);
        assertThat(result.rows())
                .extracting(UserRow::userId)
                .containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005",
                        "USER0006", "USER0007", "USER0008", "USER0009", "USER0010");
        assertThat(result.pageNumber()).isEqualTo(1);
        assertThat(result.firstUserId()).isEqualTo("USER0001");
        assertThat(result.lastUserId()).isEqualTo("USER0010");
        assertThat(result.nextPageAvailable()).isTrue();
        assertThat(result.error()).isFalse();

        verify(userSecurityRepository).findByUsrIdGreaterThanEqualOrderByUsrIdAsc(anyString(),
                any(Limit.class));
        verifyNoInteractions(context);
    }

    /**
     * Finding P13-INPUT-01: a browse filter carrying an embedded NUL (U+0000, COBOL LOW-VALUES) is
     * treated exactly like a blank/low-values filter &mdash; the browse starts from the top of the
     * file &mdash; and, crucially, the raw NUL-bearing string is never forwarded to the repository as
     * a start key. A start position cannot be NUL-truncated into a different identity, so treating it
     * as low-values is the faithful, safe behaviour; it also guarantees a value PostgreSQL cannot
     * store (SQLSTATE 22021) never reaches a query argument. The captured lower-bound key must contain
     * no NUL.
     */
    @Test
    void processEnterKey_withEmbeddedNulFilter_browsesFromTopAndNeverForwardsNulKey() {
        stubForwardWindows(usersUpTo(12));
        COUSR00Form form = emptyForm();
        form.setUsridin("A\u0000B");

        UserListResult result = service.processEnterKey(form, context);

        // Behaves like a blank filter: first page from the top, exactly ten ordered rows.
        assertThat(result.isRedirect()).isFalse();
        assertThat(result.rows())
                .extracting(UserRow::userId)
                .containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005",
                        "USER0006", "USER0007", "USER0008", "USER0009", "USER0010");
        assertThat(result.pageNumber()).isEqualTo(1);
        assertThat(result.error()).isFalse();

        // The raw NUL-bearing filter must never be handed to the query as a start key.
        ArgumentCaptor<String> startKey = ArgumentCaptor.forClass(String.class);
        verify(userSecurityRepository).findByUsrIdGreaterThanEqualOrderByUsrIdAsc(startKey.capture(),
                any(Limit.class));
        assertThat(startKey.getValue()).doesNotContain("\u0000");
        assertThat(startKey.getValue().isBlank()).isTrue();
        verifyNoInteractions(context);
    }

    // ------------------------------------------------------------------------------------------
    // processEnterKey - PROCESS-ENTER-KEY (parity items 4 & 5: selection routing)
    // ------------------------------------------------------------------------------------------

    /**
     * A row flagged {@code 'U'} or {@code 'u'} routes to the user-update program (COBOL
     * {@code XCTL PROGRAM('COUSR02C')}): the result is a redirect to {@code COUSR02C} / {@code CU02}
     * carrying the selected user id ({@code CDEMO-CU00-USR-SELECTED}), and the hand-off origin
     * fields ({@code CDEMO-TO-PROGRAM}, {@code CDEMO-FROM-TRANID = 'CU00'},
     * {@code CDEMO-FROM-PROGRAM = 'COUSR00C'}, {@code CDEMO-PGM-CONTEXT = 0}) are written to the
     * context. No browse runs on the selection path.
     *
     * @param flag the selection flag ({@code "U"} or its lowercase equivalent {@code "u"})
     */
    @ParameterizedTest(name = "selection flag \"{0}\" routes to the user-update program COUSR02C")
    @ValueSource(strings = {"U", "u"})
    void processEnterKey_withUpdateSelection_routesToUserUpdateProgram(String flag) {
        UserListResult result = service.processEnterKey(formSelecting(3, flag, "USER0003"), context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(USER_UPDATE_PROGRAM);
        assertThat(result.targetTransactionId()).isEqualTo(USER_UPDATE_TRANID);
        assertThat(result.selectedUserId()).isEqualTo("USER0003");

        verify(context).setToProgram(USER_UPDATE_PROGRAM);
        verify(context).setFromTranid(FROM_TRANID);
        verify(context).setFromProgram(FROM_PROGRAM);
        verify(context).markEnter();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * A row flagged {@code 'D'} or {@code 'd'} routes to the user-delete program (COBOL
     * {@code XCTL PROGRAM('COUSR03C')}): the result is a redirect to {@code COUSR03C} / {@code CU03}
     * carrying the selected user id, with the same hand-off origin writes. No browse runs.
     *
     * @param flag the selection flag ({@code "D"} or its lowercase equivalent {@code "d"})
     */
    @ParameterizedTest(name = "selection flag \"{0}\" routes to the user-delete program COUSR03C")
    @ValueSource(strings = {"D", "d"})
    void processEnterKey_withDeleteSelection_routesToUserDeleteProgram(String flag) {
        UserListResult result = service.processEnterKey(formSelecting(5, flag, "USER0005"), context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(USER_DELETE_PROGRAM);
        assertThat(result.targetTransactionId()).isEqualTo(USER_DELETE_TRANID);
        assertThat(result.selectedUserId()).isEqualTo("USER0005");

        verify(context).setToProgram(USER_DELETE_PROGRAM);
        verify(context).setFromTranid(FROM_TRANID);
        verify(context).setFromProgram(FROM_PROGRAM);
        verify(context).markEnter();
        verifyNoMoreInteractions(context);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * The COBOL {@code EVALUATE TRUE} scans the ten selection fields top-to-bottom and the first
     * non-blank flag wins. With row two flagged {@code 'U'} and row four flagged {@code 'D'}, the
     * earlier row two selection is taken, routing to the user-update program with row two's user id.
     */
    @Test
    void processEnterKey_withMultipleSelections_firstNonBlankRowWins() {
        COUSR00Form form = emptyForm();
        setRow(form, 2, "U", "USER0002");
        setRow(form, 4, "D", "USER0004");

        UserListResult result = service.processEnterKey(form, context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(USER_UPDATE_PROGRAM);
        assertThat(result.selectedUserId()).isEqualTo("USER0002");

        verify(context).setToProgram(USER_UPDATE_PROGRAM);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * A selected row flagged with anything other than {@code U}/{@code D} yields the
     * invalid-selection message and then <em>falls through to browse the first page</em> (the COBOL
     * {@code WHEN OTHER} moves the message to {@code WS-MESSAGE} but does <em>not</em> set
     * {@code WS-ERR-FLG}, then control continues into {@code PROCESS-PAGE-FORWARD}). The message is
     * therefore informational (not an error line), a ten-row page is still displayed, and the
     * selection path never touches the context.
     */
    @Test
    void processEnterKey_withInvalidSelectionFlag_showsInvalidSelectionMessageAndBrowsesFirstPage() {
        stubForwardWindows(usersUpTo(12));

        UserListResult result = service.processEnterKey(formSelecting(1, "X", "USER0001"), context);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.message()).isEqualTo(MSG_INVALID_SELECTION);
        assertThat(result.error()).isFalse();
        assertThat(result.rows()).hasSize(ROWS_PER_PAGE);
        assertThat(result.rows().get(0).userId()).isEqualTo("USER0001");
        assertThat(result.pageNumber()).isEqualTo(1);

        verify(userSecurityRepository).findByUsrIdGreaterThanEqualOrderByUsrIdAsc(anyString(),
                any(Limit.class));
        verifyNoInteractions(context);
    }


    // ------------------------------------------------------------------------------------------
    // processPf8Key - PROCESS-PF8-KEY (parity item 2: page down, and already-at-bottom guard)
    // ------------------------------------------------------------------------------------------

    /**
     * PF8 with a further page available (COBOL {@code IF NEXT-PAGE-YES}) advances forward: starting
     * from the last id of page one ({@code USER0010}), the browse skips that row and displays the
     * remaining users on page two ({@code USER0011}, {@code USER0012}), leaving the unused rows
     * blank. The page number advances to two and, having hit end-of-file, no further page is
     * reported.
     */
    @Test
    void processPf8Key_withNextPageAvailable_advancesToNextPage() {
        stubForwardWindows(usersUpTo(12));

        UserListResult result = service.processPf8Key(stateAt(1, "USER0001", "USER0010", true));

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.rows()).hasSize(ROWS_PER_PAGE);
        assertThat(result.rows().get(0).userId()).isEqualTo("USER0011");
        assertThat(result.rows().get(1).userId()).isEqualTo("USER0012");
        assertThat(result.rows().get(2)).isEqualTo(UserRow.blank());
        assertThat(result.pageNumber()).isEqualTo(2);
        assertThat(result.nextPageAvailable()).isFalse();

        verify(userSecurityRepository).findByUsrIdGreaterThanEqualOrderByUsrIdAsc(anyString(),
                any(Limit.class));
        verifyNoInteractions(context);
    }

    /**
     * PF8 with no further page available (COBOL {@code NEXT-PAGE-YES} false) shows the verbatim
     * already-at-bottom guard message without re-browsing: the {@code SEND} in that path does not
     * repopulate the map, so the outcome carries no rows and the page is unchanged. Because no
     * browse runs, neither the repository nor the context is touched.
     */
    @Test
    void processPf8Key_atBottom_showsAlreadyAtBottomGuardWithoutBrowsing() {
        UserListResult result = service.processPf8Key(stateAt(2, "USER0011", "USER0012", false));

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.message()).isEqualTo(MSG_ALREADY_BOTTOM);
        assertThat(result.error()).isFalse();
        assertThat(result.rows()).isEmpty();
        assertThat(result.pageNumber()).isEqualTo(2);

        verifyNoInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }

    // ------------------------------------------------------------------------------------------
    // processPf7Key - PROCESS-PF7-KEY (parity item 3: page up, and already-at-top guard)
    // ------------------------------------------------------------------------------------------

    /**
     * PF7 beyond the first page (COBOL {@code IF CDEMO-CU00-PAGE-NUM &gt; 1}) pages backward:
     * starting from the first id of page two ({@code USER0011}) the backward browse re-displays the
     * ten users of page one in ascending order and resets the page number to one.
     */
    @Test
    void processPf7Key_beyondFirstPage_pagesBackToPreviousPage() {
        stubBackwardWindows(usersUpTo(12));

        UserListResult result = service.processPf7Key(stateAt(2, "USER0011", "USER0012", false));

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.rows()).hasSize(ROWS_PER_PAGE);
        assertThat(result.rows())
                .extracting(UserRow::userId)
                .containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005",
                        "USER0006", "USER0007", "USER0008", "USER0009", "USER0010");
        assertThat(result.pageNumber()).isEqualTo(1);
        assertThat(result.firstUserId()).isEqualTo("USER0001");

        verify(userSecurityRepository).findByUsrIdLessThanEqualOrderByUsrIdDesc(anyString(),
                any(Limit.class));
        verifyNoInteractions(context);
    }

    /**
     * PF7 on the first page (COBOL {@code CDEMO-CU00-PAGE-NUM} not greater than one) shows the
     * verbatim already-at-top guard message without re-browsing: the outcome carries no rows and
     * the page is unchanged, and no browse means neither the repository nor the context is touched.
     */
    @Test
    void processPf7Key_atTop_showsAlreadyAtTopGuardWithoutBrowsing() {
        UserListResult result = service.processPf7Key(stateAt(1, "USER0001", "USER0010", true));

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.message()).isEqualTo(MSG_ALREADY_TOP);
        assertThat(result.error()).isFalse();
        assertThat(result.rows()).isEmpty();
        assertThat(result.pageNumber()).isEqualTo(1);

        verifyNoInteractions(userSecurityRepository);
        verifyNoInteractions(context);
    }


    // ------------------------------------------------------------------------------------------
    // mainEntry - MAIN-PARA (parity item 7: first entry vs first display vs re-entry dispatch)
    // ------------------------------------------------------------------------------------------

    /**
     * True first entry into the transaction (COBOL {@code EIBCALEN = 0}, reproduced by
     * {@link CardDemoContext#isNew()}) bounces back to the sign-on program: the service sets the
     * return target to {@code COSGN00C} and performs {@code RETURN-TO-PREV-SCREEN}, yielding a
     * redirect to {@code COSGN00C} / {@code CC00} with the hand-off origin fields written. No
     * browse runs, and the program-enter re-entry flag is never consulted (the first-entry test
     * short-circuits).
     */
    @Test
    void mainEntry_firstEntry_bouncesToSignonProgram() {
        when(context.isNew()).thenReturn(true);

        UserListResult result =
                service.mainEntry(AidKey.ENTER, emptyForm(), UserListState.initial());

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(SIGNON_PROGRAM);
        assertThat(result.targetTransactionId()).isEqualTo(SIGNON_TRANID);
        assertThat(result.selectedUserId()).isNull();

        verify(context, atLeastOnce()).setToProgram(SIGNON_PROGRAM);
        verify(context).setFromTranid(FROM_TRANID);
        verify(context).setFromProgram(FROM_PROGRAM);
        verify(context).markEnter();
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * On the first display of the screen (COBOL {@code IF NOT CDEMO-PGM-REENTER}, reproduced by
     * {@link CardDemoContext#isProgramEnter()}) the program marks itself re-entrant and browses the
     * first page from the top with a cleared map &mdash; ignoring the received form and the carried
     * paging state. Here the received form carries an update selection and the state claims page
     * three, yet the outcome is a first-page show-screen (not a redirect) of ten rows starting at
     * {@code USER0001}, proving the received input is discarded on first display.
     */
    @Test
    void mainEntry_firstDisplay_browsesFirstPageFromTopAndIgnoresReceivedForm() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);
        stubForwardWindows(usersUpTo(12));

        UserListResult result = service.mainEntry(AidKey.ENTER, formSelecting(1, "U", "USER0001"),
                stateAt(3, "USER0021", "USER0030", true));

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.rows()).hasSize(ROWS_PER_PAGE);
        assertThat(result.rows().get(0).userId()).isEqualTo("USER0001");
        assertThat(result.pageNumber()).isEqualTo(1);
        assertThat(result.nextPageAvailable()).isTrue();

        verify(context).markReenter();
        verify(userSecurityRepository).findByUsrIdGreaterThanEqualOrderByUsrIdAsc(anyString(),
                any(Limit.class));
    }

    /**
     * On re-entry (COBOL {@code CDEMO-PGM-REENTER}) the {@code EVALUATE EIBAID} ENTER branch
     * processes the received form. With row two flagged {@code 'U'}, the service routes to the
     * user-update program, confirming that &mdash; unlike first display &mdash; re-entry honours the
     * received selection.
     */
    @Test
    void mainEntry_reentryEnter_processesReceivedFormAndRoutesOnSelection() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        UserListResult result = service.mainEntry(AidKey.ENTER, formSelecting(2, "U", "USER0002"),
                stateAt(1, "USER0001", "USER0010", true));

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(USER_UPDATE_PROGRAM);
        assertThat(result.targetTransactionId()).isEqualTo(USER_UPDATE_TRANID);
        assertThat(result.selectedUserId()).isEqualTo("USER0002");

        verify(context).setToProgram(USER_UPDATE_PROGRAM);
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * PF3 on this admin-only screen returns to the <em>admin</em> menu {@code COADM01C} / {@code
     * CA00}, <b>not</b> the general user main menu {@code COMEN01C}. The service sets the return
     * target to {@code COADM01C} and performs {@code RETURN-TO-PREV-SCREEN}; with the context's
     * to-program resolving to {@code COADM01C}, the redirect targets the admin menu. This guards the
     * critical admin-vs-user routing distinction.
     */
    @Test
    void mainEntry_reentryPf3_returnsToAdminMenuNotUserMenu() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(context.getToProgram()).thenReturn(ADMIN_MENU_PROGRAM);

        UserListResult result = service.mainEntry(AidKey.PF3, emptyForm(),
                stateAt(1, "USER0001", "USER0010", true));

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(ADMIN_MENU_PROGRAM);
        assertThat(result.targetProgram()).isNotEqualTo("COMEN01C");
        assertThat(result.targetTransactionId()).isEqualTo(ADMIN_MENU_TRANID);

        verify(context, atLeastOnce()).setToProgram(ADMIN_MENU_PROGRAM);
        verify(context).setFromTranid(FROM_TRANID);
        verify(context).setFromProgram(FROM_PROGRAM);
        verify(context).markEnter();
        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * On re-entry the PF7 branch delegates to {@code PROCESS-PF7-KEY}. With the carried state on
     * page one, the already-at-top guard fires (verbatim message, no browse), confirming
     * {@code mainEntry} dispatches PF7 correctly.
     */
    @Test
    void mainEntry_reentryPf7_delegatesToPageBackwardGuard() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        UserListResult result = service.mainEntry(AidKey.PF7, emptyForm(),
                stateAt(1, "USER0001", "USER0010", true));

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.message()).isEqualTo(MSG_ALREADY_TOP);

        verifyNoInteractions(userSecurityRepository);
    }

    /**
     * On re-entry the PF8 branch delegates to {@code PROCESS-PF8-KEY}. With the carried state on
     * page one and a further page available, the forward browse advances to page two starting at
     * {@code USER0011}, confirming {@code mainEntry} dispatches PF8 correctly.
     */
    @Test
    void mainEntry_reentryPf8_delegatesToPageForwardAdvance() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        stubForwardWindows(usersUpTo(12));

        UserListResult result = service.mainEntry(AidKey.PF8, emptyForm(),
                stateAt(1, "USER0001", "USER0010", true));

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.rows().get(0).userId()).isEqualTo("USER0011");
        assertThat(result.pageNumber()).isEqualTo(2);

        verify(userSecurityRepository).findByUsrIdGreaterThanEqualOrderByUsrIdAsc(anyString(),
                any(Limit.class));
    }

    /**
     * Any unmapped AID key &mdash; the COBOL {@code WHEN OTHER} default, including a {@code null}
     * key which collapses to that default &mdash; yields the invalid-key message with the error
     * flag set and performs no browse. The carried page is preserved (no rows are repopulated), so
     * the controller keeps the current display and only refreshes the error line.
     *
     * @param aid an unmapped AID key ({@code null} or {@link AidKey#OTHER})
     */
    @ParameterizedTest(name = "unmapped AID key [{0}] yields the invalid-key message")
    @NullSource
    @EnumSource(value = AidKey.class, names = {"OTHER"})
    void mainEntry_withUnmappedAidKey_showsInvalidKeyMessage(AidKey aid) {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        UserListResult result =
                service.mainEntry(aid, emptyForm(), stateAt(2, "USER0011", "USER0012", false));

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        assertThat(result.rows()).isEmpty();
        assertThat(result.pageNumber()).isEqualTo(2);

        verifyNoInteractions(userSecurityRepository);
    }

    // ------------------------------------------------------------------------------------------
    // returnToPrevScreen - RETURN-TO-PREV-SCREEN (the to-program default and hand-off)
    // ------------------------------------------------------------------------------------------

    /**
     * When the return target is blank (COBOL {@code CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES}) it
     * defaults to the sign-on program {@code COSGN00C} / {@code CC00}, and the hand-off origin
     * fields ({@code CDEMO-FROM-TRANID = 'CU00'}, {@code CDEMO-FROM-PROGRAM = 'COUSR00C'},
     * program-context reset) are written. The unstubbed to-program getter returns {@code null}
     * (blank), exercising the default branch.
     */
    @Test
    void returnToPrevScreen_withBlankTarget_defaultsToSignonProgram() {
        UserListResult result = service.returnToPrevScreen(context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(SIGNON_PROGRAM);
        assertThat(result.targetTransactionId()).isEqualTo(SIGNON_TRANID);
        assertThat(result.selectedUserId()).isNull();

        verify(context).setToProgram(SIGNON_PROGRAM);
        verify(context).setFromTranid(FROM_TRANID);
        verify(context).setFromProgram(FROM_PROGRAM);
        verify(context).markEnter();
    }

    /**
     * When the return target is already set (here {@code COADM01C}) it is honoured rather than
     * defaulted, and its transaction id ({@code CA00}) is resolved onto the redirect. This is the
     * path {@code MAIN-PARA} uses for the PF3 return to the admin menu.
     */
    @Test
    void returnToPrevScreen_withExplicitTarget_redirectsToThatProgram() {
        when(context.getToProgram()).thenReturn(ADMIN_MENU_PROGRAM);

        UserListResult result = service.returnToPrevScreen(context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(ADMIN_MENU_PROGRAM);
        assertThat(result.targetTransactionId()).isEqualTo(ADMIN_MENU_TRANID);

        verify(context).setToProgram(ADMIN_MENU_PROGRAM);
        verify(context).setFromTranid(FROM_TRANID);
        verify(context).setFromProgram(FROM_PROGRAM);
        verify(context).markEnter();
    }
}

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
package com.carddemo.common.dto;

import java.util.List;

/**
 * :purpose: Carry one page of the administrator user list together with the paging state
 *     and the user-facing banner that the legacy user-list screen displays. It is the
 *     response contract of ``COUSR00C`` (transaction ``CU00``, mapset ``COUSR00``): the
 *     ten displayed rows, the ``CDEMO-CU00-*`` paging cursors held in the COMMAREA, and
 *     ``WS-MESSAGE``, which ``COUSR00C`` moves to ``ERRMSGO OF COUSR0AO`` (L526) so the
 *     operator sees why a page request produced nothing.
 * :output: A JSON object shaped like the transaction-list contract:
 *     ``{users, pageNumber, userIdFirst, userIdLast, nextPage, selectedUserId,
 *     selectedAction, message}``.
 * :note: A bare ``List`` cannot carry ``WS-MESSAGE``, so the three ``COUSR00C`` paging
 *     banners were unreachable by any client before this contract existed.
 */
public class UserListResponseDto {

    /** :purpose: The users on the requested page; at most ten, matching ``OCCURS 10 TIMES``. */
    private List<UserResponseDto> users;

    /** :purpose: Zero-based index of the returned page (``CDEMO-CU00-PAGE-NUM``). */
    private int pageNumber;

    /** :purpose: First user id shown on this page (``CDEMO-CU00-USRID-FIRST``); the PF7 cursor. */
    private String userIdFirst;

    /** :purpose: Last user id shown on this page (``CDEMO-CU00-USRID-LAST``); the PF8 cursor. */
    private String userIdLast;

    /** :purpose: Whether a further forward page exists (``NEXT-PAGE-YES``/``NEXT-PAGE-NO``). */
    private boolean nextPage;

    /** :purpose: The row the operator selected (``CDEMO-CU00-USR-SELECTED``). */
    private String selectedUserId;

    /**
     * :purpose: The canonical action resolved from the row-selection flag
     *     (``CDEMO-CU00-USR-SEL-FLG``): ``"U"`` for update (``COUSR02C``) or ``"D"`` for
     *     delete (``COUSR03C``).
     */
    private String selectedAction;

    /** :purpose: The ``WS-MESSAGE`` banner the legacy screen displays; ``null`` when none. */
    private String message;

    /**
     * :purpose: Read the users on this page.
     * :output: the page's user projections.
     */
    public List<UserResponseDto> getUsers() {
        return users;
    }

    /**
     * :purpose: Set the users on this page.
     * :param users: the page's user projections.
     */
    public void setUsers(List<UserResponseDto> users) {
        this.users = users;
    }

    /**
     * :purpose: Read the zero-based page index.
     * :output: the page index.
     */
    public int getPageNumber() {
        return pageNumber;
    }

    /**
     * :purpose: Set the zero-based page index.
     * :param pageNumber: the page index.
     */
    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    /**
     * :purpose: Read the first user id on this page.
     * :output: the PF7 page-back cursor.
     */
    public String getUserIdFirst() {
        return userIdFirst;
    }

    /**
     * :purpose: Set the first user id on this page.
     * :param userIdFirst: the PF7 page-back cursor.
     */
    public void setUserIdFirst(String userIdFirst) {
        this.userIdFirst = userIdFirst;
    }

    /**
     * :purpose: Read the last user id on this page.
     * :output: the PF8 page-forward cursor.
     */
    public String getUserIdLast() {
        return userIdLast;
    }

    /**
     * :purpose: Set the last user id on this page.
     * :param userIdLast: the PF8 page-forward cursor.
     */
    public void setUserIdLast(String userIdLast) {
        this.userIdLast = userIdLast;
    }

    /**
     * :purpose: Report whether a further forward page exists.
     * :output: ``true`` when PF8 can advance.
     */
    public boolean isNextPage() {
        return nextPage;
    }

    /**
     * :purpose: Record whether a further forward page exists.
     * :param nextPage: ``true`` when PF8 can advance.
     */
    public void setNextPage(boolean nextPage) {
        this.nextPage = nextPage;
    }

    /**
     * :purpose: Read the selected row's user id.
     * :output: the selected user id, or ``null``.
     */
    public String getSelectedUserId() {
        return selectedUserId;
    }

    /**
     * :purpose: Set the selected row's user id.
     * :param selectedUserId: the selected user id.
     */
    public void setSelectedUserId(String selectedUserId) {
        this.selectedUserId = selectedUserId;
    }

    /**
     * :purpose: Read the canonical action resolved from the row-selection flag.
     * :output: ``"U"``, ``"D"``, or ``null`` when no row was selected.
     */
    public String getSelectedAction() {
        return selectedAction;
    }

    /**
     * :purpose: Set the canonical action resolved from the row-selection flag.
     * :param selectedAction: ``"U"`` or ``"D"``.
     */
    public void setSelectedAction(String selectedAction) {
        this.selectedAction = selectedAction;
    }

    /**
     * :purpose: Read the ``WS-MESSAGE`` banner.
     * :output: the banner text, or ``null`` when the page produced none.
     */
    public String getMessage() {
        return message;
    }

    /**
     * :purpose: Set the ``WS-MESSAGE`` banner.
     * :param message: the banner text.
     */
    public void setMessage(String message) {
        this.message = message;
    }
}

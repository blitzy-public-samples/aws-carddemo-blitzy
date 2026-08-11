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
 * :purpose: Outbound response DTO for the COTRN00 transaction-list screen (CICS
 *  transaction ``CT00``, legacy program ``COTRN00C``). Wraps the ordered page of
 *  transaction rows together with the stateless paging cursor the client echoes on
 *  the next turn: the first and last transaction id on the page (COBOL
 *  ``CDEMO-CT00-TRNID-FIRST``/``CDEMO-CT00-TRNID-LAST``), the page number
 *  (``CDEMO-CT00-PAGE-NUM``), and the next-page flag (``NEXT-PAGE-YES``). It also
 *  carries the selected transaction id that drives navigation to the view screen
 *  and an optional informational message (for example the page-boundary banners).
 * :output: A mutable carrier with the page rows, paging cursor, next-page flag,
 *  selected transaction id, and informational message.
 */
public class TransactionListResponseDto {

    /** :purpose: The ordered page of transaction list rows (up to ten). */
    private List<TransactionListItemDto> transactions;

    /** :purpose: The page number of the returned page (COTRN00 ``CDEMO-CT00-PAGE-NUM``). */
    private int pageNumber;

    /** :purpose: The first transaction id on the returned page (COTRN00 ``CDEMO-CT00-TRNID-FIRST``); the PF7 cursor for the next turn. */
    private String tranIdFirst;

    /** :purpose: The last transaction id on the returned page (COTRN00 ``CDEMO-CT00-TRNID-LAST``); the PF8 cursor for the next turn. */
    private String tranIdLast;

    /** :purpose: Whether a further forward page exists (COTRN00 ``NEXT-PAGE-YES``/``NEXT-PAGE-NO``). */
    private boolean nextPage;

    /** :purpose: The selected row's transaction id when a row was selected with ``S``; drives navigation to the view screen, otherwise ``null``. */
    private String selectedTranId;

    /** :purpose: Optional informational message (COTRN00 ``WS-MESSAGE``); for example a page-boundary banner. */
    private String message;

    /**
     * :purpose: Create an empty response. Required for JSON (Jackson) serialization.
     */
    public TransactionListResponseDto() {
    }

    /**
     * :purpose: Return the ordered page of transaction list rows.
     * :output: the ``transactions`` value.
     */
    public List<TransactionListItemDto> getTransactions() {
        return transactions;
    }

    /**
     * :purpose: Set the ordered page of transaction list rows.
     * :param transactions: the ``transactions`` value.
     */
    public void setTransactions(List<TransactionListItemDto> transactions) {
        this.transactions = transactions;
    }

    /**
     * :purpose: Return the page number of the returned page.
     * :output: the ``pageNumber`` value.
     */
    public int getPageNumber() {
        return pageNumber;
    }

    /**
     * :purpose: Set the page number of the returned page.
     * :param pageNumber: the ``pageNumber`` value.
     */
    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    /**
     * :purpose: Return the first transaction id on the returned page.
     * :output: the ``tranIdFirst`` value.
     */
    public String getTranIdFirst() {
        return tranIdFirst;
    }

    /**
     * :purpose: Set the first transaction id on the returned page.
     * :param tranIdFirst: the ``tranIdFirst`` value.
     */
    public void setTranIdFirst(String tranIdFirst) {
        this.tranIdFirst = tranIdFirst;
    }

    /**
     * :purpose: Return the last transaction id on the returned page.
     * :output: the ``tranIdLast`` value.
     */
    public String getTranIdLast() {
        return tranIdLast;
    }

    /**
     * :purpose: Set the last transaction id on the returned page.
     * :param tranIdLast: the ``tranIdLast`` value.
     */
    public void setTranIdLast(String tranIdLast) {
        this.tranIdLast = tranIdLast;
    }

    /**
     * :purpose: Return whether a further forward page exists.
     * :output: the ``nextPage`` flag.
     */
    public boolean isNextPage() {
        return nextPage;
    }

    /**
     * :purpose: Set whether a further forward page exists.
     * :param nextPage: the ``nextPage`` flag.
     */
    public void setNextPage(boolean nextPage) {
        this.nextPage = nextPage;
    }

    /**
     * :purpose: Return the selected row's transaction id.
     * :output: the ``selectedTranId`` value.
     */
    public String getSelectedTranId() {
        return selectedTranId;
    }

    /**
     * :purpose: Set the selected row's transaction id.
     * :param selectedTranId: the ``selectedTranId`` value.
     */
    public void setSelectedTranId(String selectedTranId) {
        this.selectedTranId = selectedTranId;
    }

    /**
     * :purpose: Return the informational message.
     * :output: the ``message`` value.
     */
    public String getMessage() {
        return message;
    }

    /**
     * :purpose: Set the informational message.
     * :param message: the ``message`` value.
     */
    public void setMessage(String message) {
        this.message = message;
    }
}

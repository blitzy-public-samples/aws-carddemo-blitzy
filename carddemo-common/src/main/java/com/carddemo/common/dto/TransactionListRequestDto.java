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

import jakarta.validation.constraints.Size;

/**
 * :purpose: Inbound request DTO for the COTRN00 transaction-list screen (CICS
 *  transaction ``CT00``, legacy program ``COTRN00C``). Carries the stateless paging
 *  cursor that the legacy pseudo-conversational flow held in the COMMAREA program
 *  area (first/last transaction id of the current page, the page number, and the
 *  next-page flag), the optional transaction-id filter, the navigation action
 *  (ENTER / PF7 page-back / PF8 page-forward), and the row-selection inputs that
 *  drive navigation to the view screen. All fields are optional and are validated
 *  by the transaction service, not this carrier.
 * :output: A mutable request carrier with the navigation action, tran-id filter,
 *  paging cursor, and row-selection inputs.
 */
public class TransactionListRequestDto {

    /**
     * :purpose: Navigation action; ``ENTER`` (initial entry / apply filter / row
     *  selection), ``PF7`` (page back), or ``PF8`` (page forward). A ``null`` or
     *  blank value is treated as ``ENTER``.
     */
    @Size(max = 8, message = "Action must be at most 8 characters")
    private String action;

    /** :purpose: Optional transaction-id filter (COTRN00 ``TRNIDINI``); the inclusive lower bound of the forward browse. */
    @Size(max = 16, message = "Tran ID must be at most 16 characters")
    private String tranIdFilter;

    /** :purpose: Current page number (COTRN00 ``CDEMO-CT00-PAGE-NUM``), echoed across turns. */
    private int pageNumber;

    /** :purpose: First transaction id shown on the current page (COTRN00 ``CDEMO-CT00-TRNID-FIRST``); the PF7 page-back cursor. */
    @Size(max = 16, message = "Tran ID must be at most 16 characters")
    private String tranIdFirst;

    /** :purpose: Last transaction id shown on the current page (COTRN00 ``CDEMO-CT00-TRNID-LAST``); the PF8 page-forward cursor. */
    @Size(max = 16, message = "Tran ID must be at most 16 characters")
    private String tranIdLast;

    /** :purpose: Whether the current page has a further forward page (COTRN00 ``NEXT-PAGE-YES``/``NEXT-PAGE-NO``); gates the PF8 boundary. */
    private boolean nextPage;

    /** :purpose: Row selection flag (COTRN00 ``SEL00nnI``); ``S``/``s`` selects the row identified by ``selectedTranId``. */
    @Size(max = 1, message = "Invalid selection. Valid value is S")
    private String selectionFlag;

    /** :purpose: Transaction id of the selected row (COTRN00 ``TRNIDnnI``); paired with ``selectionFlag``. */
    @Size(max = 16, message = "Tran ID must be at most 16 characters")
    private String selectedTranId;

    /**
     * :purpose: Create an empty request. Required for JSON (Jackson) deserialization.
     */
    public TransactionListRequestDto() {
    }

    /**
     * :purpose: Return the navigation action.
     * :output: the ``action`` value.
     */
    public String getAction() {
        return action;
    }

    /**
     * :purpose: Set the navigation action.
     * :param action: the ``action`` value.
     */
    public void setAction(String action) {
        this.action = action;
    }

    /**
     * :purpose: Return the transaction-id filter.
     * :output: the ``tranIdFilter`` value.
     */
    public String getTranIdFilter() {
        return tranIdFilter;
    }

    /**
     * :purpose: Set the transaction-id filter.
     * :param tranIdFilter: the ``tranIdFilter`` value.
     */
    public void setTranIdFilter(String tranIdFilter) {
        this.tranIdFilter = tranIdFilter;
    }

    /**
     * :purpose: Return the current page number.
     * :output: the ``pageNumber`` value.
     */
    public int getPageNumber() {
        return pageNumber;
    }

    /**
     * :purpose: Set the current page number.
     * :param pageNumber: the ``pageNumber`` value.
     */
    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    /**
     * :purpose: Return the first transaction id of the current page.
     * :output: the ``tranIdFirst`` value.
     */
    public String getTranIdFirst() {
        return tranIdFirst;
    }

    /**
     * :purpose: Set the first transaction id of the current page.
     * :param tranIdFirst: the ``tranIdFirst`` value.
     */
    public void setTranIdFirst(String tranIdFirst) {
        this.tranIdFirst = tranIdFirst;
    }

    /**
     * :purpose: Return the last transaction id of the current page.
     * :output: the ``tranIdLast`` value.
     */
    public String getTranIdLast() {
        return tranIdLast;
    }

    /**
     * :purpose: Set the last transaction id of the current page.
     * :param tranIdLast: the ``tranIdLast`` value.
     */
    public void setTranIdLast(String tranIdLast) {
        this.tranIdLast = tranIdLast;
    }

    /**
     * :purpose: Return whether the current page has a further forward page.
     * :output: the ``nextPage`` flag.
     */
    public boolean isNextPage() {
        return nextPage;
    }

    /**
     * :purpose: Set whether the current page has a further forward page.
     * :param nextPage: the ``nextPage`` flag.
     */
    public void setNextPage(boolean nextPage) {
        this.nextPage = nextPage;
    }

    /**
     * :purpose: Return the row selection flag.
     * :output: the ``selectionFlag`` value.
     */
    public String getSelectionFlag() {
        return selectionFlag;
    }

    /**
     * :purpose: Set the row selection flag.
     * :param selectionFlag: the ``selectionFlag`` value.
     */
    public void setSelectionFlag(String selectionFlag) {
        this.selectionFlag = selectionFlag;
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
}
